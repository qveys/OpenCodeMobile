# CI/CD & Release-Signing Security Policy

Status: v1.0 — mitigation spec for `docs/THREAT-MODEL.md` **T10** (Critical: CI/CD & release-signing
supply chain). Owner: DevOps. This is a binding checklist, not guidance — any PR that touches
`.github/workflows/**`, deployment config, or store-signing credentials must satisfy every
applicable row below, and reviewers should block on deviations.

Context: OpenCode Mobile is a public OSS repo with a solo maintainer, external contributor PRs, and
(eventually) automated signed releases to TestFlight and Google Play. That combination is a classic
supply-chain target — a malicious fork PR or a subverted workflow can exfiltrate secrets or tamper
with a shipped build. The controls below assume the worst case (a hostile PR author) at every layer.

## 1. Current pipeline status (as of this writing)

`.github/workflows/ci.yml` and `.github/workflows/cd.yml` were introduced by OPE-13. Reviewed
against this policy:

| Control | Status | Evidence |
|---|---|---|
| Fork PR CI uses `pull_request`, not `pull_request_target` | **Compliant** | `ci.yml` triggers on `pull_request: branches: [main]` only |
| CI workflow has no secrets | **Compliant** | `ci.yml` references no `secrets.*` |
| CD/signing restricted to protected-branch pushes | **Compliant so far** | `cd.yml` triggers on `push: branches: [main]` only, no tag/PR triggers |
| Actions pinned to full commit SHA (not floating tags) | **Compliant** | all `uses:` steps pin `@<sha> # vX.Y.Z`; enforced by `scripts/check-workflow-action-pinning.sh` (SEC-04), which also rejects a container action on a tag |
| Explicit least-privilege `permissions:` block | **Gap** | neither workflow sets `permissions:`, so jobs run with the repo's default token scope instead of an explicit minimum |
| Signing/upload implemented with short-lived creds | **N/A yet** | `deploy` job in `cd.yml` is a placeholder (OPE-19); no signing secrets exist yet — this policy governs how it must be built |
| GitHub Environment protection (required reviewers) on deploy job | **Gap** | `deploy` has no `environment:` — nothing currently stops it from running unattended on every `main` push once implemented |
| First-time-contributor workflow approval | **Not yet verifiable** | repo setting, requires a real GitHub repo (OPE-9) |
| Branch protection + required reviews enforced in GitHub | **Not yet verifiable** | repo setting, requires a real GitHub repo (OPE-9) + OPE-10 |

Two of the nine controls are gaps that can be fixed in the workflow YAML today, independent of the
Gradle scaffolding blockers (OPE-14/16/18) that `ci.yml`/`cd.yml` are already guarding against. They
are tracked as follow-ups against OPE-13's pipeline. The remaining controls are inherently
GitHub-repo-settings or not-yet-built deploy logic; they're carried forward to OPE-9, OPE-10, and
OPE-19 as explicit acceptance criteria (see §6).

## 2. Rules for workflow triggers (fork PRs)

- CI that runs on external/fork PRs **must** use the `pull_request` event, never
  `pull_request_target`. `pull_request_target` runs with the base repo's token and secrets
  against a workflow file that *is itself* attacker-controlled if combined with a checkout of the
  PR head (`actions/checkout` with `ref: ${{ github.event.pull_request.head.sha }}`) — that
  combination is the single most common way public repos get their secrets stolen.
- If a workflow genuinely needs `pull_request_target` (e.g., to label PRs or comment with a token),
  it must **not** check out or execute any code from the PR head, and must not have access to
  deployment/signing secrets.
- CI jobs that only lint/test/build (no deploy, no signing) get **no secrets at all**. `ci.yml`
  already satisfies this — keep it that way as tooling lands (OPE-14/16/18).

## 3. First-time contributor approval

GitHub's "Require approval for first-time contributors" (Settings → Actions → General → Fork pull
request workflows) must be enabled once the repo exists (OPE-9). This is a repo setting, not
something expressible in workflow YAML — record it as an explicit acceptance criterion on OPE-9/
OPE-10, not something CI can self-certify. Effect: any workflow run triggered by a first-time
contributor's PR pauses for a maintainer to click "Approve and run" before any job executes,
closing the window where a first PR is also the malicious one.

## 4. Signing/upload jobs: restrict to protected refs + gate with an Environment

- Signing and store-upload jobs run **only** on `push` to `main` (already true in `cd.yml`) or on
  protected release tags (e.g. `v*`) — never on `pull_request`/`pull_request_target`, and never on
  a branch that isn't protected.
- Once OPE-19 implements real deployment, the `deploy` job (and any future signing job) must declare
  a GitHub **Environment** (e.g. `environment: production`) with required reviewers configured in
  repo settings. This adds a manual approval gate *inside* GitHub Actions itself, independent of
  branch protection — even if branch protection is ever misconfigured, the signing job still can't
  run without a human clicking approve. This is achievable in the workflow file today and doesn't
  need to wait on OPE-9/OPE-10.
- Signing secrets (`ANDROID_KEYSTORE_*`, `PLAY_CONSOLE_SERVICE_ACCOUNT_JSON`,
  `APPLE_APP_STORE_CONNECT_API_KEY` — see `docs/CI-CD.md`) are scoped to that Environment, not the
  repository, so no other workflow or job can read them even accidentally.

## 5. Credential strategy: OIDC / short-lived over static secrets

| Target | Preferred approach | Fallback (if unsupported) |
|---|---|---|
| Google Play Developer API (upload) | Workload Identity Federation via `google-github-actions/auth`, minting short-lived OAuth2 tokens for the Play Developer API — no long-lived JSON key stored in GitHub | `PLAY_CONSOLE_SERVICE_ACCOUNT_JSON` as a GitHub Secret, scoped to the `production` Environment, rotated on a fixed schedule (≤90 days) |
| Android APK/AAB signing key | Keep the signing key out of GitHub entirely if feasible (e.g. Play App Signing, where Google holds the release key and CI only holds an upload key) | `ANDROID_KEYSTORE_BASE64` + password/alias as Environment-scoped secrets; never logged, never echoed in workflow steps |
| Apple / TestFlight upload | App Store Connect API key (JWT-based, already short-lived per token even though the key itself is a stored credential) rather than an Apple ID + app-specific password | N/A — this is already the recommended baseline; just don't downgrade to interactive Apple ID auth |

GitHub-hosted OIDC (`id-token: write` permission + `actions/github-script` or provider-specific
`auth` actions) is the mechanism for all of the above — it lets the workflow request a short-lived
token at run time instead of holding a standing credential. No OIDC exchange should ever be granted
from a `pull_request`-triggered job.

## 6. Acceptance criteria to carry into dependent issues

These are already tracked as separate backlog issues; this policy is the source of truth they must
implement against, so verification isn't duplicated inside T10/OPE-23 itself.

- **OPE-9** (Initialize GitHub repository): when created, enable "Require approval for first-time
  contributors" for fork PR workflow runs before any Actions run publicly.
- **OPE-10** (Set up branch protection and PR requirements): branch protection on `main` must
  require PR review before merge, disallow direct pushes (including by the maintainer, or at least
  require the same CI to pass), and must be confirmed by reading it back via
  `gh api repos/:owner/:repo/branches/main/protection` (or the Settings UI) — not just documented as
  intended process. Add that verification step to OPE-10's PR description/checklist.
- **OPE-19** (Set up deployment automation): implement signing/upload per §4/§5 above — Environment
  protection with required reviewers, secrets scoped to that Environment, OIDC/WIF where the target
  supports it, and no plaintext credentials in workflow files (already called out in `docs/CI-CD.md`).
- **OPE-13's workflows** (`ci.yml`/`cd.yml`, already merged): add an explicit least-privilege
  `permissions:` block (e.g. `permissions: contents: read` at the workflow level, elevated per-job
  only where actually needed, e.g. `id-token: write` for the future OIDC-based deploy job) and add
  `environment: production` to the `deploy` job once it does real work.

## 7. Review checklist for any future CI/CD PR

- [ ] No `pull_request_target` combined with a checkout of untrusted PR head content
- [ ] No secret is readable by a job triggered from a fork PR
- [ ] Signing/upload jobs are gated by a GitHub Environment with required reviewers
- [ ] Signing/upload jobs only trigger on protected `main`/release-tag pushes
- [ ] New/changed `uses:` steps are pinned to a full commit SHA, not a floating tag. This covers a repository action, a sub-path action (`owner/repo/path/to/action`) and a reusable workflow (`owner/repo/.github/workflows/x.yml`) alike; a container action (`docker://`) is pinned to an image digest, never to a tag. Local `./path` steps are exempt
      (enforced by `scripts/check-workflow-action-pinning.sh`, SEC-04)
- [ ] `gradle/wrapper/gradle-wrapper.properties` still carries a `distributionSha256Sum` and an `https` `distributionUrl` whose **host** is a trusted publisher — the official `services.gradle.org` on a `/distributions/gradle-<version>-{bin,all}.zip` path, or a host listed in `gradle/wrapper/gradle-distribution-allowlist.txt` — and `gradle/wrapper/gradle-wrapper.jar.sha256` matches the committed wrapper JAR. `https` alone is not sufficient: it constrains the transport, not the publisher, so a pull request editing `distributionUrl` and `distributionSha256Sum` together would otherwise point the build at an attacker's archive (T10)
      (enforced by `scripts/check-gradle-supply-chain.sh`, SEC-05)
- [ ] `gradle/verification-metadata.xml` is committed, parses, and still pins a SHA-256 for every resolved artifact, and every Gradle invocation in CI still resolves cleanly under strict dependency verification
      (enforced by `scripts/check-gradle-supply-chain.sh`, SEC-05; the gate needs `python3` on the runner and fails closed without it)
- [ ] If a PR changes a build input (`build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`), `gradle/verification-metadata.xml` was regenerated **from a machine reaching both Maven Central and the Gradle Plugin Portal**, and every pre-existing `.gradle.plugin` marker pin was re-read. The Portal and Central serve different bytes for six of the seven markers in this file; regenerating from a Central-only machine silently rewrites them and breaks the build (§8.1)
      (enforced by `scripts/check-verification-metadata-coverage.sh`, SEC-05b)
- [ ] Any declaration the coverage gate cannot resolve to a coordinate is registered in `gradle/verification-coverage-exemptions.txt` **with a reason**, and every exempt coordinate is genuinely never resolved by a task
- [ ] No workflow under `.github/workflows/` passes `--write-verification-metadata`; a workflow that does is registered in `gradle/verification-regeneration-exemptions.txt` with a reason and triggers on `workflow_dispatch` only
      (enforced by `scripts/check-verification-metadata-coverage.sh`, SEC-05b)
- [ ] `permissions:` is explicit and least-privilege
- [ ] No credential value is printed to logs (`::add-mask::` used for any dynamically generated secret)
- [ ] Static long-lived store credentials are used only where OIDC/short-lived auth isn't supported by the target platform

### Container image pinning

- Job container images are supply-chain inputs and must use immutable SHA-256
  digests, never floating tags.
- Linux Android/Kotlin jobs use the repository-built image from
  `ci/android/Dockerfile`, published by `.github/workflows/ci-image.yml` to
  `ghcr.io/qveys/opencodemobile/ci-android`. Consumers pin the reviewed digest
  `sha256:83077870201bbe6f8a4843da2c003b84db3e849236cb2cd71707ee639e6d18ab`.
- Only trusted pushes to `main` or manual dispatch on `main` can publish. Pull
  requests and dispatches from other refs build on GitHub-hosted runners and do
  not publish. Review Dockerfile changes and digest updates with CI consumers.
- Hostinger build and deploy commands run as uid 10001 through
  `scripts/ci/run-as-nonroot.sh`. CD secrets are passed only by explicit names
  from the protected deployment environment.

## 8. Regenerating `gradle/verification-metadata.xml`

Dependency verification is strict: every resolved artifact must have a SHA-256 in
`gradle/verification-metadata.xml`, or the build stops. Adding a dependency therefore means
regenerating that file:

```bash
./gradlew --write-verification-metadata sha256 <task>
```

Pick tasks that resolve the whole graph you care about — a configuration left unresolved
leaves its artifacts unpinned, and the failure then appears later, on whatever task happens
to touch them first.

### 8.1 The Portal / Maven Central divergence — read this before regenerating

**The command above is only correct from a machine that reaches *both* Maven Central and the
Gradle Plugin Portal. The two do not serve the same bytes for plugin marker POMs.**

A plugin id resolves to a marker artifact, `<id>:<id>.gradle.plugin:<version>`. For
JetBrains-published plugins the Portal carries its own marker, and its bytes differ from the
copy Central serves. Measured on this repository, by comparing the committed pins against the
bytes Maven Central actually returns:

| Marker | Committed pin | Maven Central bytes | Verdict |
|---|---|---|---|
| `app.cash.sqldelight.gradle.plugin` | `c48e1e1d…` | `c48e1e1d…` | identical |
| `org.jetbrains.kotlin.multiplatform.gradle.plugin` | `d0be7b59…` | `a30f2698…` | **different** |
| `org.jetbrains.kotlin.android.gradle.plugin` | `96e007b3…` | `0b1d7e9b…` | **different** |
| `org.jetbrains.kotlin.jvm.gradle.plugin` | `df8026dc…` | `1a630541…` | **different** |
| `org.jetbrains.compose.gradle.plugin` | `52d83f25…` | `1c945a53…` | **different** |
| `org.jetbrains.kotlin.plugin.compose.gradle.plugin` | `fe78fa62…` | `db3bde26…` | **different** |
| `org.jetbrains.kotlin.plugin.serialization.gradle.plugin` | `0e13653b…` | `05b990d0…` | **different** |

sqldelight matches because it is published on Central, and the Portal proxies Central for
plugins it does not publish itself. detekt behaved the same way — that is the pin that broke
CI on [OPE-220](/OPE/issues/OPE-220) (`expected fc15b14f but was 6b7aa8ea`).

`gradlePluginPortal()` is listed **first** in `pluginManagement`, so the Portal is the copy
the build actually resolves — Central is the fallback, not the source of truth.

**Consequence:** regenerating from a machine that cannot reach the Portal silently rewrites
those six pins with the Central value and breaks the build, with no warning from Gradle. To
regenerate safely:

1. **Preferred — regenerate where both repositories are reachable.** This project's own
   self-hosted runners reach the Portal.
2. **Otherwise — regenerate, then re-read every pre-existing marker pin.** Do not trust the
   diff for plugin markers. For each `<component>` whose `name` ends in `.gradle.plugin` and
   that already existed, confirm the pin still matches what CI resolves. Where the two servers
   genuinely disagree, keep the **Portal** bytes in `value` (the convention this file already
   uses for the JetBrains markers) and record the Central copy as
   `<also-trust value="…"/>`, so the pin verifies on either server without silently changing
   which artifact is trusted.

Regenerating with no new dependencies must produce an empty diff. A non-empty diff on a change
that declares nothing new means something else moved, and that diff is worth reading.

### 8.2 CI never regenerates the pins; the diff is reviewed by hand

`--write-verification-metadata` **must not** run on `pull_request` or on `push` to `main`. A
CI job running it would rewrite the pins from an environment nobody reviewed — the whole
control, defeated in one step. Pins are regenerated by a developer and landed through the
normal PR flow like any other change.

This is enforced, not merely documented.
`scripts/check-verification-metadata-coverage.sh` fails if `--write-verification-metadata`
appears in any line of a file under `.github/workflows/` that YAML would execute — `run:`
blocks included, comments excluded, since a comment cannot run a command. A workflow that
genuinely needs it must be registered in `gradle/verification-regeneration-exemptions.txt`
**with a reason**, and the gate then reads that workflow's own `on:` block to confirm it
triggers on `workflow_dispatch` and nothing else. Adding the flag and a `pull_request` trigger
in one pull request fails. The list is empty today: there is no CI pin writer.

### 8.3 Drift is caught before the build runs

Regeneration is a manual step, so the failure mode is a forgotten one: a dependency lands in
`build.gradle.kts`, `settings.gradle.kts` or `gradle/libs.versions.toml`, the pins are never
regenerated, and the build fails later during plugin resolution — every job red at once, with
no link back to the change that caused it. That is [OPE-220](/OPE/issues/OPE-220).

`scripts/check-verification-metadata-coverage.sh` closes that class of bug in the fast,
JDK-free static step. It resolves every `libs.` alias through the version catalog, expands
bundles, reads literal `group:name:version` coordinates, maps plugin aliases and
`id("…") version "…"` to their marker coordinates, and requires each to exist in
`gradle/verification-metadata.xml` as a `<component>`. On this repository it measures 37
declared external dependencies, all pinned. It also:

- **fails on a declaration it cannot resolve.** A plugin extension (`compose.runtime`), a
  `kotlin("stdlib")` call, or a two-segment literal whose version comes from a BOM carries no
  coordinate at parse time. Ignoring those would leave a hole exactly the shape of the bug, so
  each must be registered in `gradle/verification-coverage-exemptions.txt` **with a reason**. A
  line without a reason is refused — an exemption nobody can justify is indistinguishable from
  a bypass.
- **fails closed on an unreadable input.** A catalog entry whose form it cannot parse, an
  unknown `libs.` alias, an exemption file it cannot read, or a missing `python3` on the runner
  are errors, not skips.
- **refuses to count an empty pin file as coverage.** The document is parsed and its components
  measured, so `<components></components>` cannot pass as "fully pinned".

Five coordinates are currently exempt, all of them declared by `architecture-tests/` — a
module `settings.gradle.kts` does not include, so no task resolves it and the regeneration
cannot pin it. That is a temporary state, and [OPE-137](/OPE/issues/OPE-137), which wires the
module into the build, must regenerate the pins and delete those five entries in the same
commit.

## Related

- `docs/THREAT-MODEL.md` — T10 (this policy is its mitigation detail)
- `docs/CI-CD.md` (OPE-13) — pipeline structure and current secret inventory
- OPE-9, OPE-10, OPE-19 — own the GitHub-settings and deploy-implementation pieces this policy can't
  self-certify from a pre-repo, pre-code state
- [OPE-220](/OPE/issues/OPE-220) — the incident that motivated §8: the detekt marker pin, and the Portal / Central divergence behind it
