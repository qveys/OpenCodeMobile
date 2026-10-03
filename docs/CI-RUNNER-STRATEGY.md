# CI Runner Strategy (OPE-98)

Status: **decided** — see §2. Owner: DevOps.
Companion docs: `docs/CI-CD-SECURITY.md` (T10 supply-chain policy), `docs/THREAT-MODEL.md`.

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
| `security-logging.yml` | `T4 static scan` | `[self-hosted, hostinger]` | JDK-free bash gate; already green on `vps-dokploy`. Keep it there — it is the **required** check and must stay cheap and always-reportable. |
| `security-logging.yml` | `Redaction unit tests` | `[self-hosted, hostinger]` | Plain JVM/Android unit test (`:shared:networking:testDebugUnitTest`); moved off the single-lane `mac` runner in OPE-250 (Phase 0.1). Sources the preinstalled JDK 21 + Android SDK. |
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

**Per-PR path conditioning (OPE-250).** Heavy workflows declare native
`on.pull_request.paths` filters so a docs-only or narrow PR triggers only the
always-on gates (`T4 static scan`, `Executable bits`). The device legs (T1
Android emulator / iOS simulator, and the GitHub-hosted iOS duplicate) run on
`main` pushes and a nightly schedule instead of on every PR. Because a
path-filtered workflow emits no status when it does not match, none of these
workflows may be added to `required_status_checks`; `T4 static scan` stays the
only required context (see `docs/BRANCH-PROTECTION.md`).

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
