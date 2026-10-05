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
2. The job's wrapper step runs as root inside the container only to repair the
   ownership of the runner-mounted checkout (`/__w`). The **actual build runs
   as an unprivileged user** (`uid 10001`) via
   `scripts/ci/run-as-nonroot.sh`.
3. No host volume is mounted. The workspace, Gradle cache and toolchain are
   per-job and are discarded with the container, so no state is shared between
   jobs.
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

### Amendment — type-resolved lint needs the Android SDK (OPE-254)

OPE-213 turned on detekt's type resolution. The type-resolved tasks compile the
Android/KMP modules, so the `lint` job now needs the Android SDK, which the
`eclipse-temurin` image does not carry. Decision item 3 above ("No host volume
is mounted") therefore no longer holds for `lint`:

- `lint.yml` bind-mounts the OPE-98 host toolchain **read-only** at
  `/opt/android-sdk` (`volumes: - /opt/android-sdk:/opt/android-sdk:ro`), and
  `scripts/ci/runner-toolchain-env.sh` exports `ANDROID_HOME` from it.
- The container remains digest-pinned, ephemeral and non-root; only a read-only
  toolchain path is exposed. Nothing is written back to the host.
- Verified on `hostinger` (run `37369536318`): the type-resolved tasks
  (`:androidApp:detektDebug`, `:design-system:detektAndroidDebug`, …) ran and
  reported 0 findings.
- This narrowing is routed to Security/DevOps for sign-off on OPE-254. The
  staged replacement stays the repository-built digest-pinned Android image in
  GHCR noted in *Migration* below; until then the read-only mount is the
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

- **The runner service still runs as root** as the job scheduler. The job no
  longer does, and it no longer touches host state, but the agent process and
  its `_work` staging directory are still root-owned. Fully removing root means
  re-installing the runner service under a dedicated unprivileged user (and
  re-registering it), which requires host access and briefly takes the runner
  offline on a machine shared with other projects. Tracked as a follow-up; it
  needs an explicit owner decision because of the availability risk.
- **Android SDK jobs** (`build.yml` Android, `t1-device-validation.yml` Android)
  now run on GitHub-hosted `ubuntu-latest` on `main`; they are not affected.
  If they are moved back to `hostinger`, they need an image that carries the
  Android SDK. `eclipse-temurin` does not, so a repository-built image published
  to GHCR is the next step.
- **Digest pinning of the image** must be refreshed deliberately. The pinned
  digest in the two workflows is recorded here and captured again by
  `docker image inspect --format '{{index .RepoDigests 0}}'` on a runner when
  it changes.
- **No Gradle cache between jobs.** The `lint` job re-downloads the Gradle
  distribution and dependencies on every run. This is the price of "no shared
  state" and is acceptable for a fast detekt pass; a cache would have to be
  keyed and trusted, which reopens the exact problem this ADR closes.
- The public base image is third-party. It is pinned by digest and pulled on
  each run; the repository's supply-chain policy (`docs/CI-CD-SECURITY.md`)
  should be extended with an explicit "container images are pinned by digest"
  row.

## Migration

1. **This change** — `lint.yml` and `security-logging.yml` `T4 static scan`.
2. **Next** — a repository-built, digest-pinned image with JDK 21 + Android SDK
   published to GHCR, so the Android jobs can leave GitHub-hosted runners and
   run isolated on `hostinger`.
3. **Later** — re-install the runner service under a dedicated non-root user
   (or move the pool to a dedicated host and adopt ephemeral runners
   properly), and retire the host-toolchain `provision-runner-toolchain.yml`
   Linux job once no workflow depends on it.
