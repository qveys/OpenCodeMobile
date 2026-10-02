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
| `lint.yml` | `lint` | `[self-hosted, hostinger]` | Required detekt gate. Since OPE-212 runs in a digest-pinned, non-root `container:` (see §7). |
| `security-logging.yml` | `T4 static scan` | `[self-hosted, hostinger]` | JDK-free bash gate; the **required** check and must stay cheap and always-reportable. Since OPE-212 runs in a digest-pinned, non-root `container:` (see §7). |
| `security-logging.yml` | `Redaction unit tests` | `[self-hosted, mac]` | Needs JDK 21 + Android SDK. Runs on `macbook-openclaw` once OPE-98 provisioning is green; disabled on `pull_request` in the meantime so it cannot block PRs. |
| `build.yml` | `Build Android (APK/AAB)` | `[self-hosted, hostinger]` | Linux runner carries JDK 21 + Android SDK after OPE-98. |
| `build.yml` | `Build iOS/macOS frameworks` | `[self-hosted, mac]` | Needs Xcode, which only the MacBook has. Also needs JDK 21. |
| `t1-device-validation.yml` | `T1 handshake (JVM)` | `[self-hosted, hostinger]` | JDK 21 + Android SDK only. |
| `t1-device-validation.yml` | `T1 Android instrumented test compiles` | `[self-hosted, hostinger]` | JDK 21 + Android SDK only (compile, no emulator). |
| `t1-device-validation.yml` | `T1 handshake (Android emulator)` | `[self-hosted, hostinger]` | Needs an AVD **and** hardware acceleration (`/dev/kvm`). Provisioned by `scripts/t1/run-android-device-validation.sh`; depends on host capability, see §5. |
| `t1-device-validation.yml` | `T1 handshake (iOS simulator)` | `[self-hosted, mac]` | Needs Xcode + a bootable simulator. macOS only. |
| `cd.yml` | `prepare` | `[self-hosted, hostinger]` | Pure bash parameter resolution; no toolchain. |
| `cd.yml` | `deploy-android` | `[self-hosted, hostinger]` | Builds the release bundle: JDK 21 + Android SDK. Sources `scripts/ci/runner-toolchain-env.sh`. |
| `cd.yml` | `deploy-ios` | `[self-hosted, mac]` | Apple deployment leg; runs on the Xcode-capable host. |
| `cd.yml` | `smoke-test` | `[self-hosted, hostinger]` | Pure bash/curl pipeline check. |
| `smoke-test.yml` | `self-test` | `[self-hosted, hostinger]` | Pure bash/curl self-test of the smoke script. |
| `smoke-test.yml` | `smoke` | `[self-hosted, hostinger]` | Pure bash/curl health/version check after a deployment. |

**Default rule for new workflows:** prefer the self-hosted pool. Use
`[self-hosted, hostinger]` for JDK/Android/Gradle work and `[self-hosted, mac]`
for anything that needs Xcode or a simulator. Do **not** go back to
`ubuntu-latest` / `macos-latest` — the board requires the company pool, and
GitHub-hosted minutes are not part of the plan.

## 3. Toolchain contract

`scripts/ci/provision-runner-toolchain.sh` installs, idempotently, on a runner:

| Component | Version | Location |
|---|---|---|
| JDK | Temurin / Corretto / Microsoft **21** | Linux `/opt/jdk-21`; macOS `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`; user fallback `$HOME/.local/jdk-21` |
| Android command-line tools | build `11076708` | `$ANDROID_HOME/cmdline-tools/latest` |
| `platform-tools` | latest | `$ANDROID_HOME/platform-tools` |
| Android platform | `android-35` | `$ANDROID_HOME/platforms/android-35` |
| Build tools | `35.0.0` | `$ANDROID_HOME/build-tools/35.0.0` |

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
which runs `scripts/ci/provision-runner-toolchain.sh` on both runners and then
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

- **Android emulator on `hostinger`** — `scripts/t1/run-android-device-validation.sh`
  already tries to create and boot an AVD from the SDK. It additionally needs
  hardware acceleration (`/dev/kvm` on the VPS) and enough disk/RAM. If the VPS
  cannot provide `/dev/kvm`, that job stays unavailable and the emulator leg of
  T1 is validated locally only. This is tracked as a follow-up, not part of
  OPE-98's toolchain acceptance.
- **Xcode on `macbook-openclaw`** — OPE-98 does not install Xcode. The `mac`
  runner already has it (the T1 iOS job has run there); if a future job reports
  `xcodebuild: command not found`, that is a separate provisioning task.
- **Action pinning** — every `uses:` in these workflows stays pinned to a full
  commit SHA, per `docs/CI-CD-SECURITY.md` §7. Any widen request for the
  allowlist must be escalated to the repo admin with the exact action and
  justification; do not bypass the policy.

## 6. Why not "just install the toolchain inside each job"

Downloading a JDK/Android SDK inside a job on every run would (a) add an
unverified, unpinned runtime artifact to a *security gate* (T4), and (b) put a
multi-hundred-MB download on the critical path of every PR. Installing once on
the persistent runner, with the version pinned in this repo and the hashes
recorded in the provisioning log, keeps the supply chain auditable. That is why
the T4 redaction job was made `workflow_dispatch`-only until this provisioning
landed, rather than having it fetch a JDK per run.

## 7. Ephemeral, non-root job isolation (OPE-212)

Context: Codex flagged in the review of PR #20 that the `lint` job runs on the
**persistent** `[self-hosted, hostinger]` runners. A capability probe found
that `hostinger` jobs ran as **root** (`uid=0`) on hosts shared with ~13 other
repositories, with the checkout and toolchain persisting between jobs. See
`docs/adr/0007-ephemeral-nonroot-hostinger-jobs.md` for the full analysis.

Decision: `[self-hosted, hostinger]` jobs that only need JDK 21 (or no JDK) run
in an **ephemeral, non-root container**:

- `container:` with a **digest-pinned** public image
  (`eclipse-temurin@sha256:6adefddd…`), so a fresh filesystem is created per job
  and discarded at the end;
- `scripts/ci/run-as-nonroot.sh` hands the root-owned checkout to an
  unprivileged user and runs the build as `uid 10001`;
- `defaults.run.shell: bash` is required, because container jobs otherwise
  default to `sh` and bash-isms such as `set -euo pipefail` fail;
- no host volume is mounted, so the Gradle cache and toolchain are per-job.

Applied to `lint.yml` (`lint`) and `security-logging.yml` (`T4 static scan`).
Both were validated on `vps-dokploy` **and** `vps-openclaw` before wiring.

Not yet done: the runner **service** still runs as root as the scheduler, and
the Android-SDK jobs still use the host toolchain / GitHub-hosted runners. The
staged plan (repository-built digest-pinned Android image in GHCR, then a
non-root runner service) is in the ADR's *Migration* section.
