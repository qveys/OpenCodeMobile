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
| `lint.yml` | `lint` | `[self-hosted, hostinger]` (push) / `ubuntu-latest` (PR, OPE-265) | Detekt gate. Since OPE-212 runs in a digest-pinned, non-root `container:` (see §7). |
| `security-logging.yml` | `T4 static scan` | `[self-hosted, hostinger]` (all events, OPE-291) | JDK-free bash gate; already green on `vps-dokploy`. Keep it cheap and always-reportable — it is the **required** check. |
| `security-logging.yml` | `Redaction unit tests` | `[self-hosted, hostinger]` (all events, OPE-291) | Plain JVM/Android unit test (`:shared:networking:testDebugUnitTest`); moved off the single-lane `mac` runner in OPE-250 (Phase 0.1). Runs in the digest-pinned repository CI image (JDK 21 + Android SDK 35) as uid 10001 (OPE-255). |
| `build.yml` | `Build Android (APK/AAB)` | `[self-hosted, hostinger]` (all events, OPE-291) | Runs in the digest-pinned repository CI image (JDK 21 + Android SDK 35) as uid 10001 (OPE-255). |
| `build.yml` | `Build iOS/macOS frameworks` | `macos-latest` | The GitHub-hosted macOS image carries Xcode + JDK 21. |
| `t1-device-validation.yml` | `T1 handshake (JVM)` | `[self-hosted, hostinger]` (all events, OPE-291) | same digest-pinned repository CI image (OPE-255). |
| `t1-device-validation.yml` | `T1 Android instrumented test compiles` | `[self-hosted, hostinger]` (all events, OPE-291) | same digest-pinned repository CI image (OPE-255) (compile, no emulator). |
| `build.yml` | `Test Android (JVM unit tests)` | `[self-hosted, hostinger]` (all events, OPE-291) | Runs in the digest-pinned repository CI image (JDK 21 + Android SDK 35) as uid 10001 (OPE-255). |
| `t1-device-validation.yml` | `T1 handshake (Android emulator)` | `[self-hosted, hostinger]` | Needs an AVD **and** hardware acceleration (`/dev/kvm`). Provisioned by `scripts/t1/run-android-device-validation.sh`; depends on host capability, see §5. |
| `t1-device-validation.yml` | `T1 handshake (iOS simulator)` | `[self-hosted, mac]` | Needs Xcode + a bootable simulator. macOS only. |
| `cd.yml` | `prepare` | `[self-hosted, hostinger]` | Pure bash parameter resolution; no toolchain. |
| `cd.yml` | `deploy-android` | `[self-hosted, hostinger]` | Builds the release bundle; still sources the **host** toolchain via `scripts/ci/runner-toolchain-env.sh` (handles deploy secrets; not yet moved to the CI image — see ADR 0007 *Migration*). |
| `cd.yml` | `deploy-ios` | `[self-hosted, mac]` | Apple deployment leg; runs on the Xcode-capable host. |
| `cd.yml` | `smoke-test` | `[self-hosted, hostinger]` | Pure bash/curl pipeline check. |
| `smoke-test.yml` | `self-test` | `[self-hosted, hostinger]` | Pure bash/curl self-test of the smoke script. |
| `smoke-test.yml` | `smoke` | `[self-hosted, hostinger]` | Pure bash/curl health/version check after a deployment. |

**Default rule for new workflows:** prefer the self-hosted pool for trusted
runs. Use `[self-hosted, hostinger]` for JDK/Android/Gradle work and
`[self-hosted, mac]` for anything that needs Xcode or a simulator. GitHub-hosted
minutes are not part of the plan for any event (OPE-291 review).

**Untrusted `pull_request` routing (OPE-258, OPE-265 — superseded by OPE-291 review).**
Repo-controlled code from a `pull_request` used to stay off the shared
`hostinger` VPS, where it executes as uid 0 on a host that also exposes a
read-write Docker socket: every such job declared a conditional `runs-on`
so a `pull_request` run landed on an ephemeral GitHub-hosted runner while
trusted runs kept the company pool. Per @qveys review on PR #90 (GitHub-hosted
minutes are not in the plan), all events now run on `[self-hosted, hostinger]`
and untrusted-PR isolation relies on the digest-pinned CI image running as
uid 10001 (ADR 0007). The other workflows that still route `pull_request` to
`ubuntu-latest` (`lint.yml`, `architecture-tests.yml`, `executable-bits.yml`,
`smoke-test.yml`) are pre-existing on `main` and out of scope of PR #90.

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

> **One run provisions one host, not both (OPE-259).** `provision-linux` is a
> single job on the shared `[self-hosted, hostinger]` label, and the GitHub
> Actions scheduler sends it to **whichever host is free**. Run `37368590956`
> landed on `vps-dokploy` and reported `Android SDK already present:
> /opt/android-sdk`; it says nothing about `vps-openclaw`. Both Linux hosts must
> therefore be provisioned by dispatching the workflow until each has reported.
> Read the job log's `Runner name` line to confirm which host was covered.
> Giving each host its own label (`hostinger-dokploy`, `hostinger-openclaw`)
> plus a `strategy.matrix` would make coverage structural instead of a
> convention, but it needs a host-level runner re-registration; that is the
> DevOps follow-up, not a repo change. Until then the `lint` job fails fast
> (§7.2) rather than silently linting against a missing SDK.

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
- **no host `toolchain` or `cache` volume is mounted**, so the Gradle cache and
  the JDK are per-job. That is the whole of the isolation claim, and it was
  already narrower than "no host volume" — see §7.1 for the real mount set.

Applied first to `lint.yml` (`lint`) and `security-logging.yml` (`T4 static
scan`); both were validated on `vps-dokploy` **and** `vps-openclaw` before
wiring.

OPE-255 (stage 2) extends the model to the Android-SDK jobs with a
**repository-built** image instead of the JDK-only public one:

- `ci/android/Dockerfile` builds JDK 21 + Android SDK 35 (platform-tools,
  `platforms;android-35`, `build-tools;35.0.0`) from a digest-pinned
  `eclipse-temurin` base, accepts the SDK licenses at build time and ships the
  `builder` uid 10001 account.
- `.github/workflows/ci-image.yml` builds and pushes it to
  `ghcr.io/qveys/opencodemobile/ci-android`, printing the `name@sha256` digest.
  Only `main` pushes and manual dispatch publish; pull requests build without
  pushing.
- `build.yml` (`build-android`, `test-android`), `t1-device-validation.yml`
  (`jvm-handshake`, `android-instrumented-compile`) and
  `security-logging.yml` (`Redaction unit tests`) now consume that digest and
  run as uid 10001.

Not yet done: the runner **service** still runs as root as the scheduler;
`cd.yml` `deploy-android` still sources the host toolchain (so
`provision-runner-toolchain.yml` cannot be retired yet); and the Android
emulator leg needs `/dev/kvm`, which a container cannot get, so it stays on
`ubuntu-latest`. The staged plan is in the ADR's *Migration* section.

### 7.1 The real mount set of a `container:` job (OPE-259)

"no host volume is mounted" was **inaccurate**, and was already inaccurate
*before* OPE-254. The runner always bind-mounts its own paths into every
container job, whether or not the workflow asks for it (evidence: run
`37369536318`, step `Initialize containers`, `docker create`):

| Container path | Host source | Mode |
|---|---|---|
| `/__w` | `<runner>/_work` | rw |
| `/__w/_temp` | `<runner>/_work/_temp` | rw |
| `/__w/_actions` | `<runner>/_work/_actions` | rw |
| `/__w/_tool` | `<runner>/_work/_tool` | rw |
| `/github/home` | `<runner>/_work/_temp/_github_home` | rw |
| `/github/workflow` | `<runner>/_work/_temp/_github_workflow` | rw |
| `/__e` | `<runner>/_externals` | **ro** |
| `/var/run/docker.sock` | the host Docker socket | rw |

Those are outside the isolation claim: they are how the runner delivers the
checkout, its own temp/action caches and its execution environment. The
`docker.sock` entry is the sharp edge — see ADR 0007's *Consequences*, where
the uid 0 wrapper and the socket are recorded together.

What the workflow *adds* on top of that:

| Job | Extra mount | Why |
|---|---|---|
| `lint.yml` / `lint` | `/opt/android-sdk:/opt/android-sdk:ro` | OPE-254: detekt type resolution compiles the Android/KMP modules, which the `eclipse-temurin` image does not carry. |
| `security-logging.yml` / `T4 static scan` | *(none)* | JDK-free bash gate; only the runner mounts above apply. |

Among the `container:` jobs, only `lint` mounts a host toolchain, and it is
read-only. `build.yml`, `cd.yml` `deploy-android`, `t1-device-validation.yml`
and `security-logging.yml`'s `Redaction unit tests` job also read
`/opt/android-sdk`, but they are **not** container jobs — they run on the host
and use the toolchain in place, which is the "not yet done" item above.

### 7.2 Fail fast when the host SDK is missing (OPE-259)

`docker -v /opt/android-sdk:/opt/android-sdk:ro` **creates** an empty
root-owned directory on the host when the path does not exist. An
unprovisioned runner therefore mounted an empty directory, and `lint` failed
much later with `SDK location not found` — which reads as a detekt /
type-resolution bug and sends the next person down the wrong path.

`lint.yml` now asserts the platform directory immediately after
`. scripts/ci/runner-toolchain-env.sh` and fails with a provisioning
instruction naming the host:

```bash
test -d "$ANDROID_HOME/platforms/android-35" \
  || { echo "::error::host Android SDK missing at $ANDROID_HOME on $RUNNER_NAME — run the \"Provision runner toolchain\" workflow on this host"; exit 1; }
```
