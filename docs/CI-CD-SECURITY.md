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
| Actions pinned to full commit SHA (not floating tags) | **Compliant** | all `uses:` steps pin `@<sha> # vX.Y.Z` |
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
- [ ] New/changed `uses:` steps are pinned to a full commit SHA, not a floating tag
- [ ] `permissions:` is explicit and least-privilege
- [ ] No credential value is printed to logs (`::add-mask::` used for any dynamically generated secret)
- [ ] Static long-lived store credentials are used only where OIDC/short-lived auth isn't supported by the target platform

## Related

- `docs/THREAT-MODEL.md` — T10 (this policy is its mitigation detail)
- `docs/CI-CD.md` (OPE-13) — pipeline structure and current secret inventory
- OPE-9, OPE-10, OPE-19 — own the GitHub-settings and deploy-implementation pieces this policy can't
  self-certify from a pre-repo, pre-code state
