# ADR 0007: Ephemeral, non-root execution for `hostinger` self-hosted jobs

- Status: accepted
- Date: 2026-10-01
- Driven by: OPE-212 — hardening the `hostinger` self-hosted runner after the
  Codex review of PR #20 (`.github/workflows/lint.yml`) and the existing
  `security-logging.yml` / `provision-runner-toolchain.yml` jobs on `main`.

## Context

OpenCode Mobile's CI uses a company self-hosted pool. A read-only capability
probe of the two Linux runners (`vps-dokploy`, `vps-openclaw`) was run as part
of this ADR (workflow run `36852214370`; the probe workflow was deleted before
merge). It established:

- OpenCode Mobile jobs execute as **root** (`uid=0`, `HOME` unset) on both
  hosts. The runner services
  (`actions.runner.qveys-OpenCodeMobile.<host>.service`) are installed as root.
- Each host runs **~13 repositories'** runner services; OpenCode Mobile shares
  the machine with other projects, and several of those runners also execute as
  root. A root job can read every other repository's runner workspace.
- State persists **between jobs**: the checkout workspace
  (`/opt/actions-runner-opencodemobile/_work/OpenCodeMobile`), the Gradle cache
  under the runner user's `$HOME`, and the OPE-98 host toolchain
  (`/opt/android-sdk`, `/usr/lib/jvm/java-1.21.0-openjdk-amd64`,
  `/etc/opencode-mobile-runner.env`).
- Both hosts have Docker 29.8.x (overlayfs, cgroup v2). Neither has podman or
  `/dev/kvm`.

"One job = a clean environment" is not true today: a job can observe files left
by a previous job, and it runs with host-root authority over a shared machine.
That is the finding.

Constraints that shape the fix:

- The organization Actions allowlist permits **only `actions/checkout@*`**; no
  setup action can install a toolchain.
- The repository requires **verified commit signatures**; all changes land
  through API-created, GitHub-signed commits.
- The host is shared, and other repositories' jobs run as root on it.

## Decision

Run `hostinger` **jobs** in an ephemeral, non-root container:

1. Each such job declares `container:` with a **digest-pinned** public base
   image (`eclipse-temurin@sha256:6adefddd…`, JDK 21, Ubuntu 22.04). The image
   is pulled on every run, so nothing is cached on the host between jobs.
2. The job's wrapper step runs as root inside the container to repair the
   ownership of the runner-mounted checkout (`/__w`), and then executes
   `scripts/ci/run-as-nonroot.sh` — **a file from this repository** — as uid 0.
   The **build** runs as an unprivileged user (`uid 10001`); the **steps**
   (`actions/checkout`, the wrapper, the report-publishing step) run as uid 0.
   Corrected in OPE-259: an earlier revision of this item said the wrapper ran
   as root "only to repair the ownership", which understated the root-executed
   repository code. Consequence in *Consequences* below.
3. **No host `toolchain` or `cache` volume is mounted.** The Gradle cache and
   the JDK are per-job and are discarded with the container, so no state is
   shared between jobs. This claim has always been narrower than "no host
   volume is mounted", because the runner injects its own bind mounts into
   every container job — see *The real mount set* below, added in OPE-259.
4. The runner agent itself stays registered on the host as the scheduler.
   Removing the root runner service is a separate, host-level change
   (see *Consequences*).

Applied in this change to the two JDK-free/JDK-only `hostinger` jobs:

- `.github/workflows/lint.yml` — the required `lint` gate (detekt).
- `.github/workflows/security-logging.yml` — the required `T4 static scan`.

Both were validated end-to-end on **both** hosts before wiring:
`T4 static scan` ran as `uid 10001` and printed `OK: scanned 7 files`
(run `36853180860`); the full detekt command ran as `uid 10001` and printed
the detekt result (run `36853841018`).

### The real mount set (OPE-259)

Decision item 3 originally read "No host volume is mounted". That was never
true and the isolation claim must be stated precisely, so the runner-injected
mounts are listed here. These exist in **every** container job, declared or
not (evidence: run `37369536318`, step `Initialize containers`):

| Container path | Host source | Mode |
|---|---|---|
| `/__w` | `<runner>/_work` | rw |
| `/__w/_temp` | `<runner>/_work/_temp` | rw |
| `/__w/_actions` | `<runner>/_work/_actions` | rw |
| `/__w/_tool` | `<runner>/_work/_tool` | rw |
| `/github/home` | `<runner>/_work/_temp/_github_home` | rw |
| `/github/workflow` | the workflow repository checkout | rw |
| `/__e` | `<runner>/_externals` | **ro** |
| `/var/run/docker.sock` | the host Docker socket | rw |

They are how the runner delivers the checkout, its own temp/action caches and
its execution environment. They are **outside** the isolation claim of items 1
and 3: items 1 and 3 are about the *toolchain and cache* being per-job, not
about the host being invisible. The only host toolchain either job adds on top
is `lint`'s read-only `/opt/android-sdk` (OPE-254 amendment below); `T4 static
scan` adds none.

### Amendment — type-resolved lint needs the Android SDK (OPE-254)

OPE-213 turned on detekt's type resolution. The type-resolved tasks compile the
Android/KMP modules, so the `lint` job now needs the Android SDK, which the
`eclipse-temurin` image does not carry. Decision item 3 above ("no host
**toolchain or cache** volume is mounted") is therefore narrowed for `lint` by
one read-only toolchain path:

- `lint.yml` bind-mounts the OPE-98 host toolchain **read-only** at
  `/opt/android-sdk` (`volumes: - /opt/android-sdk:/opt/android-sdk:ro`), and
  `scripts/ci/runner-toolchain-env.sh` exports `ANDROID_HOME` from it.
- The container remains digest-pinned, ephemeral and non-root; only a read-only
  toolchain path is exposed. Nothing is written back to the host.
- Verified on `hostinger` (run `37369536318`): the type-resolved tasks
  (`:androidApp:detektDebug`, `:design-system:detektAndroidDebug`, …) ran and
  reported 0 findings.
- This narrowing is routed to Security/DevOps for sign-off on OPE-254, and the
  Security review on **OPE-259** approved the mount and corrected the record:
  this amendment is **not** "the one exception" to an otherwise empty mount
  set, it sits on top of the runner-injected mounts catalogued in *The real
  mount set*. OPE-259 also made `lint` fail fast when the host SDK is absent,
  because `docker -v` silently creates an empty `/opt/android-sdk` on a
  never-provisioned runner.
- **Superseded by OPE-314:** `lint` now runs in the digest-pinned `ci-android`
  image and the host SDK mount (and its fail-fast assertion) is removed.
- The staged replacement stays the repository-built digest-pinned Android image
  in GHCR noted in *Migration* below; until then the read-only mount is the
  smallest change that keeps both the OPE-212 container and OPE-213 type
  resolution.

### Alternatives considered

- **A truly ephemeral runner** (`config.sh --ephemeral` + a one-shot runner
  container per job). This is the textbook answer, but re-registering a runner
  needs a repository-registration token, and minting one needs repository-admin
  authority. The GitHub App in use can mint such tokens, but the token would
  then have to be produced on the host by a **durable admin credential stored on
  a machine where other repositories' jobs run as root** — a strictly worse
  trade than the residual below. Actions Runner Controller would need a
  Kubernetes cluster this project does not have. Revisit if the runners ever
  move to a dedicated host.
- **`actions/setup-java` and friends.** Rejected: not on the Actions allowlist.
- **Installing the toolchain inside each job.** Rejected by
  `docs/CI-RUNNER-STRATEGY.md` §6 for supply-chain reasons (unverified,
  unpinned runtime artifact on a security gate). A digest-pinned image is the
  auditable version of this.
- **Leaving the job on the host and only dropping privileges.** Kubernetes-style
  `--user` on a container job fails today because the runner mounts `_work`
  root-owned (`EACCES` on `/__w/_temp`); the wrapper in this ADR exists to
  bridge exactly that.

## Consequences / known gaps

- **Steps run as uid 0 with the host Docker socket mounted** (OPE-259 record
  correction). The runner bind-mounts `/var/run/docker.sock` **rw** into every
  container job, and `container.options: --user 0:0` makes the step process
  uid 0 — both visible in the `docker create` line of run `37369536318`. The
  **build** is dropped to `uid 10001`, but the wrapper that performs that drop,
  `scripts/ci/run-as-nonroot.sh`, is a **repository file** executing as uid 0
  with the host Docker socket reachable, so a step can drive the host daemon.
  The OPE-212/254 mitigation is therefore "the *build* is unprivileged", not
  "the *job* is unprivileged". The read-only OPE-254 SDK mount does not change
  this: it is a separate, read-only path. PR #78 (OPE-258) already moved the
  `T4 static scan` job off the `hostinger` pool for untrusted `pull_request`
  runs; `lint` still runs on `hostinger` for every PR. Closing the socket gap
  is the DevOps follow-up and is **not** addressed by this ADR revision.
- **The runner service still runs as root** as the job scheduler. The job no
  longer does, and it no longer touches host state, but the agent process and
  its `_work` staging directory are still root-owned. Fully removing root means
  re-installing the runner service under a dedicated unprivileged user (and
  re-registering it), which requires host access and briefly takes the runner
  offline on a machine shared with other projects. Tracked as a follow-up; it
  needs an explicit owner decision because of the availability risk.
- **Android SDK jobs** (`build.yml` `build-android`/`test-android`,
  `t1-device-validation.yml` `jvm-handshake`/`android-instrumented-compile`, and
  `security-logging.yml`'s `Redaction unit tests`) run in the repository-built
  image published to GHCR by OPE-255:
  `ghcr.io/qveys/opencodemobile/ci-android@sha256:83077870201bbe6f8a4843da2c003b84db3e849236cb2cd71707ee639e6d18ab`
  (`ci/android/Dockerfile`, `.github/workflows/ci-image.yml`). They are pinned by
  that digest and run as uid 10001, so they no longer read the host toolchain.
  `cd.yml`'s `deploy-android` still sources the host toolchain (left for a later
  change: it carries deploy secrets); the Android emulator leg cannot run in a
  container (it needs `/dev/kvm`) and stays on `ubuntu-latest`.
- **Digest pinning of the images** must be refreshed deliberately. The
  `eclipse-temurin` digest used by `lint.yml` / `security-logging.yml` and the
  repository CI image digest used by the Android jobs are both recorded here,
  and are captured again by
  `docker image inspect --format '{{index .RepoDigests 0}}'` on a runner when
  they change.
- **No Gradle cache between jobs.** The `lint` job re-downloads the Gradle
  distribution and dependencies on every run. This is the price of "no shared
  state" and is acceptable for a fast detekt pass; a cache would have to be
  keyed and trusted, which reopens the exact problem this ADR closes.
- The public base image is third-party. It is pinned by digest and pulled on
  each run; `docs/CI-CD-SECURITY.md` now carries an explicit "container images
  are pinned by digest" control.

## Migration

1. **This change** — `lint.yml` and `security-logging.yml` `T4 static scan`.
2. **Done (OPE-255, 2026-10-05)** — `ci/android/Dockerfile` +
   `.github/workflows/ci-image.yml` build and publish a digest-pinned JDK 21 +
   Android SDK 35 image to GHCR; `build.yml` `build-android`/`test-android`,
   `t1-device-validation.yml` `jvm-handshake`/`android-instrumented-compile` and
   `security-logging.yml` `Redaction unit tests` now run on it.
3. **Later** — re-install the runner service under a dedicated non-root user
   (or move the pool to a dedicated host and adopt ephemeral runners
   properly). `cd.yml` `deploy-android` still sources the host toolchain, so the
   `provision-runner-toolchain.yml` Linux job cannot be retired until that and
   any other host-toolchain consumer move; the emulator leg needs `/dev/kvm`
   and stays on `ubuntu-latest`.
