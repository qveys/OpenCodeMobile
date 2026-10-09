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

### Amendment — Android toolchain image and Linux provisioning retirement (OPE-255/OPE-314)

OPE-213 enabled detekt type resolution, which means lint needs the Android SDK.
OPE-254 mounted the host SDK read-only as a temporary exception. OPE-255 built
and published a repository Android image with JDK 21 and Android SDK 35, pinned
by digest. OPE-314 moved `lint`, Android build/test, T1 compile, redaction tests,
and Android deployment to this image. No Linux job mounts or resolves the host
SDK. Trusted hostinger jobs run their build/deploy commands as uid 10001; PR
jobs remain on isolated GitHub-hosted runners where required.

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
- **Android emulator** remains on `ubuntu-latest`: it needs an emulator runtime
  and `/dev/kvm` where available. The script falls back to software acceleration
  if KVM is absent; it does not use the hostinger pool or its provisioned SDK.
- **macOS toolchain** remains provisioned by the `provision-mac` job for Xcode,
  Kotlin/Native, and iOS deployment. This change does not alter macOS
  provisioning.
- **Android image digest pinning** must be refreshed deliberately. The current
  digest is recorded here and captured again by
  `docker image inspect --format '{{index .RepoDigests 0}}'` on a runner when
  it changes.
- **No Gradle cache between jobs.** The `lint` job re-downloads the Gradle
  distribution and dependencies on every run. This is the price of "no shared
  state" and is acceptable for a fast detekt pass; a cache would have to be
  keyed and trusted, which reopens the exact problem this ADR closes.
- The public Temurin base image is third-party and pinned by digest. The
  repository-built Android image is published by `.github/workflows/ci-image.yml`
  and consumed by its digest:
  `ghcr.io/qveys/opencodemobile/ci-android@sha256:83077870201bbe6f8a4843da2c003b84db3e849236cb2cd71707ee639e6d18ab`.

## Migration

1. **Done (OPE-255)** — build and publish the digest-pinned JDK 21 + Android
   SDK 35 image to GHCR.
2. **Done (OPE-314)** — move Linux toolchain consumers to the image and remove
   the `provision-linux` job. No Linux hostinger workflow requires host JDK or
   Android SDK provisioning. Keep `provision-mac` for Apple toolchain consumers.
3. **Later** — re-install the runner service under a dedicated non-root user
   (or move the pool to a dedicated host and adopt ephemeral runners
   properly). This is host-level scheduler hardening, not part of toolchain
   retirement.

OPE-314 verification: searched `.github/workflows` for
`runner-toolchain-env.sh` and `/opt/android-sdk`; remaining references are
macOS jobs/provisioning and comments, plus the emulator script on
`ubuntu-latest`. `bash -n`, SEC-04 action pinning, the 279-assertion supply-chain
test suite, and the mocked non-root environment handoff passed. GitHub Actions
must confirm image pulls and the affected jobs.
