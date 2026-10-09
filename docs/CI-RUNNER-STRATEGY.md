# CI Runner Strategy (OPE-98)

Status: **decided** — see §2. Owner: DevOps.
Companion docs: `docs/CI-CD-SECURITY.md` (T10 supply-chain policy), `docs/THREAT-MODEL.md`, ADR
`docs/adr/0007-ephemeral-nonroot-hostinger-jobs.md` (OPE-212 — ephemeral, non-root job isolation).

## 1. The constraint that drives every choice

The organization's Actions allowlist permits **only `actions/checkout@*`**. Every
other marketplace action is rejected with `startup_failure` *before any job step
runs*:

- `actions/setup-java`, `gradle/actions/setup-gradle`, `android-actions/setup-android`
- `actions/upload-artifact`, `actions/cache`
- `reactivecircus/android-emulator-runner`, and every other third-party action

Consequence: **no workflow can install a toolchain.** Every tool — JDK, Gradle,
Android SDK, Xcode, emulator — must already exist on the machine that runs the
job, and workflows may only use `actions/checkout` plus plain shell steps.

## 2. Decision

| Workflow | Job | Runner | Rationale |
|---|---|---|---|
| `lint.yml` | `lint` | `[self-hosted, hostinger]` (trusted) / `ubuntu-latest` (PR) | Digest-pinned repository Android image; hostinger builds run as uid 10001, no host SDK mount. |
| `security-logging.yml` | `T4 static scan` | `[self-hosted, hostinger]` (push) / `ubuntu-latest` (PR, OPE-258) | JDK-free bash gate; already green on `vps-dokploy`. Keep it cheap and always-reportable — it is the **required** check. |
| `security-logging.yml` | `Redaction unit tests` | `[self-hosted, hostinger]` | Digest-pinned repository Android image, uid 10001; skipped on PRs. |
| `build.yml` | `Build Android (APK/AAB)` | `[self-hosted, hostinger]` (trusted) / `ubuntu-latest` (PR) | Digest-pinned repository Android image; trusted runs use uid 10001. |
| `build.yml` | `Build iOS/macOS frameworks` | `macos-latest` | The GitHub-hosted macOS image carries Xcode + JDK 21. |
| `build.yml` | `Test Android (JVM unit tests)` | `[self-hosted, hostinger]` (trusted) / `ubuntu-latest` (PR) | Digest-pinned repository Android image; trusted runs use uid 10001. |
| `t1-device-validation.yml` | `T1 handshake (JVM)` | `[self-hosted, hostinger]` (trusted) / `ubuntu-latest` (PR) | Digest-pinned repository Android image; trusted runs use uid 10001. |
| `t1-device-validation.yml` | `T1 Android instrumented test compiles` | `[self-hosted, hostinger]` (trusted) / `ubuntu-latest` (PR) | Same image; compile only, no emulator. |
| `t1-device-validation.yml` | `T1 handshake (Android emulator)` | `ubuntu-latest` | Uses the hosted runner Android SDK and JDK; script installs the emulator image. `/dev/kvm` is optional and software acceleration is the fallback. Not a hostinger toolchain consumer. |
| `t1-device-validation.yml` | `T1 handshake (iOS simulator)` | `[self-hosted, mac]` | Needs Xcode + a bootable simulator. macOS only. |
| `cd.yml` | `prepare` | `[self-hosted, hostinger]` | Pure bash parameter resolution; no toolchain. |
| `cd.yml` | `deploy-android` | `[self-hosted, hostinger]` | Uses the digest-pinned repository Android image; deployment secrets are passed to the uid-10001 process. |
| `cd.yml` | `deploy-ios` | `[self-hosted, mac]` | Apple deployment leg; runs on the Xcode-capable host. |
| `cd.yml` | `smoke-test` | `[self-hosted, hostinger]` | Pure bash/curl pipeline check. |
| `smoke-test.yml` | `self-test` | `[self-hosted, hostinger]` | Pure bash/curl self-test of the smoke script. |
| `smoke-test.yml` | `smoke` | `[self-hosted, hostinger]` | Pure bash/curl health/version check after a deployment. |
| `ci-image.yml` | `Build JDK 21 + Android SDK 35 image` | `[self-hosted, hostinger]` (main) / `ubuntu-latest` (PR or non-main dispatch) | Trusted main publication uses Docker; PR image builds stay off the shared host. No host JDK or Android SDK is used. |

**Default rule for new workflows:** prefer the self-hosted pool for trusted
runs. Use `[self-hosted, hostinger]` for JDK/Android/Gradle work and
`[self-hosted, mac]` for anything that needs Xcode or a simulator. GitHub-hosted
minutes are not part of the plan for `push`/`workflow_dispatch`.

**Untrusted `pull_request` routing (OPE-258, OPE-265).** Repo-controlled code
from a `pull_request` must not run on the shared `hostinger` VPS, where it
executes as uid 0 on a host that also exposes a read-write Docker socket. Every
such job declares

```yaml
runs-on: ${{ github.event_name == 'pull_request' && 'ubuntu-latest' || fromJSON('["self-hosted","hostinger"]') }}
```

so a `pull_request` run lands on an ephemeral GitHub-hosted runner while trusted
runs keep the company pool: `architecture-tests.yml`, `executable-bits.yml`,
`security-logging.yml` (`T4 static scan`), `smoke-test.yml`, `build.yml`
(`test-android`) and `lint.yml` (`lint`). `security-logging.yml` `Redaction unit
tests` stays off `pull_request`; it uses the digest-pinned GHCR image on trusted
pushes and dispatches. The company `mac` pool is single-tenant, so `test-ios` and
`ios-app.yml` are not routed this way.

**Untrusted changes need human review (OPE-265).** Runner routing cannot protect
a same-repo branch, which is indistinguishable from a `push`. `.github/CODEOWNERS`
therefore assigns `.github/workflows/**` to the repository owner, and
`require_code_owner_reviews` is enabled on `main`.

**Per-PR path conditioning (OPE-250).** Heavy workflows declare native
`on.pull_request.paths` filters so a docs-only or narrow PR triggers only the
always-on gates (`T4 static scan`, `Executable bits`). The device legs (T1
Android emulator / iOS simulator, and the GitHub-hosted iOS duplicate) run on
`main` pushes and a nightly schedule instead of on every PR. Because a
path-filtered workflow emits no status when it does not match, none of these
workflows may be added to `required_status_checks`; `T4 static scan` stays the
only required context (see `docs/BRANCH-PROTECTION.md`).

## 3. Toolchain contract

`ci/android/Dockerfile` installs the Linux JDK 21 + Android SDK 35 toolchain in
the digest-pinned job image. `scripts/ci/provision-runner-toolchain.sh` is now
retained for the macOS runner, where Xcode jobs still need a provisioned JDK and
Android SDK:

| Component | Version | Location |
|---|---|---|
| JDK | Temurin **21** | Linux CI image (digest pinned); macOS `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` |
| Android command-line tools | build `11076708` | CI image / macOS `$ANDROID_HOME/cmdline-tools/latest` |
| `platform-tools` | latest | CI image / macOS `$ANDROID_HOME/platform-tools` |
| Android platform | `android-35` | CI image / macOS `$ANDROID_HOME/platforms/android-35` |
| Build tools | `35.0.0` | CI image / macOS `$ANDROID_HOME/build-tools/35.0.0` |

`android-35` / build-tools `35.0.0` match `compileSdk = 35` in every Gradle
module.

Every CI step that needs the toolchain starts with:

```bash
. scripts/ci/runner-toolchain-env.sh
```

That script resolves `JAVA_HOME` (env file → `/usr/libexec/java_home -v 21` →
known install dirs) and `ANDROID_HOME`, and prepends the SDK `cmdline-tools` and
`platform-tools` to `PATH`. It never fails the step; if the toolchain is absent
the step's own `java -version` produces the clear error.

The provisioning run also writes `$HOME/.opencode-mobile-runner.env` and
`/etc/opencode-mobile-runner.env` so operator shells and future jobs resolve the
same paths.

### Gradle

Gradle is **not** preinstalled: `gradlew` + the pinned wrapper
(`gradle-8.10.2-bin.zip`) download on first use into `~/.gradle`. The runners
need egress to `services.gradle.org`, `repo.maven.apache.org` and
`dl.google.com` (verified reachable during provisioning). The Gradle
distribution and every dependency are pinned in the repo
(`gradle/wrapper/gradle-wrapper.properties`, `gradle/libs.versions.toml`), so
the runner is a cache, not a source of truth.

## 4. Provisioning and verification

Provisioning is driven by `.github/workflows/provision-runner-toolchain.yml`,
which runs `scripts/ci/provision-runner-toolchain.sh` on the macOS runner and
runs OPE-98's acceptance command through
`scripts/ci/run-toolchain-acceptance.sh`:

```
./gradlew :shared:networking:testDebugUnitTest --no-daemon
```

Trigger it with **Actions → Provision runner toolchain → Run workflow** (also
runs automatically when the provisioning files change). Re-run it any time; it
skips what is already installed.

To re-provision manually while an operator is on the box:

```bash
bash scripts/ci/provision-runner-toolchain.sh
bash scripts/ci/run-toolchain-acceptance.sh
```

## 5. Known gaps and follow-ups

- **Android emulator** — `t1-device-validation.yml` runs the emulator leg on
  `ubuntu-latest`, not on the hostinger pool. The script checks `/dev/kvm` and
  falls back to software acceleration when it is absent. This job does not
  depend on host JDK/Android SDK provisioning.
- **Xcode on `macbook-openclaw`** — OPE-98 does not install Xcode. The `mac`
  runner already has it (the T1 iOS job has run there); if a future job reports
  `xcodebuild: command not found`, that is a separate provisioning task.
- **Action pinning** — every `uses:` in these workflows stays pinned to a full
  commit SHA, per `docs/CI-CD-SECURITY.md` §7. Any widen request for the
  allowlist must be escalated to the repo admin with the exact action and
  justification; do not bypass the policy.

## 6. Why not "just install the toolchain inside each job"

Downloading a JDK/Android SDK inside a job on every run would add unverified
runtime artifacts to CI and slow each build. The Android image instead installs
versioned SDK packages from Google's repository on a digest-pinned JDK base; the
resulting container digest is reviewed and pinned by its consumers. The T4
security scan remains JDK-free.

## 7. Ephemeral, non-root job isolation (OPE-212)

Context: Codex flagged in the review of PR #20 that the `lint` job runs on the
**persistent** `[self-hosted, hostinger]` runners. A capability probe found
that `hostinger` jobs ran as **root** (`uid=0`) on hosts shared with ~13 other
repositories, with the checkout and toolchain persisting between jobs. See
`docs/adr/0007-ephemeral-nonroot-hostinger-jobs.md` for the full analysis.

Decision: toolchain-dependent Linux jobs run in an **ephemeral, digest-pinned
container**:

- `container:` with a **digest-pinned** image. JDK-only jobs use the pinned
  Temurin image; Android/Kotlin jobs use
  `ghcr.io/qveys/opencodemobile/ci-android@sha256:83077870201bbe6f8a4843da2c003b84db3e849236cb2cd71707ee639e6d18ab`;
- `scripts/ci/run-as-nonroot.sh` hands the root-owned checkout to an
  unprivileged user and runs the build as `uid 10001`;
- `defaults.run.shell: bash` is required, because container jobs otherwise
  default to `sh` and bash-isms such as `set -euo pipefail` fail;
- no host toolchain volume is mounted, so the Gradle cache and toolchain are
  per-job. The Android image pins the JDK base by digest and installs Android
  platform 35 and build-tools 35.0.0 from Google's SDK repository.

Applied to the Android build, test, lint, architecture, redaction, and Android
deployment jobs. The Linux provisioner job is removed because no Linux job on
the hostinger pool needs the host JDK or Android SDK. The macOS provisioner is
retained for iOS/Xcode jobs. The runner service still runs as root as scheduler;
that host-level isolation work is outside Linux toolchain retirement.

Verification for OPE-314: workflow inventory via searches for
`runner-toolchain-env.sh` and `/opt/android-sdk` under `.github/workflows`;
`bash -n`, the SEC-04 workflow action pin check, the 279-assertion
`test-supply-chain-gates.sh` suite, and a mocked uid-10001 wrapper environment
handoff all passed. GitHub Actions execution is still required to verify the
pinned image pull and all affected CI jobs.
