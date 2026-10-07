# OPE-255 (Stage 2 of ADR 0007) — repository-built CI image with JDK 21 + Android SDK 35.
#
# Why this file exists
# -------------------
# GitHub always `docker pull`s the `container:` image of a job, so a
# locally-built image cannot be used by a self-hosted job. To move the
# Android-SDK jobs off the shared host toolchain (and off GitHub-hosted
# runners) without re-introducing host state, the image has to live in a
# registry. It is built here, from this repo, and published to GHCR by
# `.github/workflows/ci-image.yml`.
#
# The image is consumed by digest, e.g.
#   container:
#     image: ghcr.io/qveys/opencodemobile/ci-android@sha256:<digest>
# See docs/adr/0007-ephemeral-nonroot-hostinger-jobs.md and
# docs/CI-RUNNER-STRATEGY.md §7.
#
# Supply-chain notes
# ------------------
# - The base image is pinned **by digest**, not by tag, exactly like the
#   existing `lint.yml` / `security-logging.yml` jobs.
# - The Android packages are pinned to explicit versions (`android-35`,
#   `build-tools;35.0.0`, `platform-tools`) and installed from Google's official
#   repository through `sdkmanager`, matching what
#   `scripts/ci/provision-runner-toolchain.sh` installs on the host. The result
#   is then pinned by the digest of this built image.
# - Rebuilding is deliberate, never automatic on a PR from a fork: the publish
#   workflow only pushes on `main` / manual dispatch (see ci-image.yml).

FROM eclipse-temurin:21-jdk-jammy@sha256:6adefddd4a20bceef702cedb0b03952fcd7691f9c7ccffe27014992abc0b46bd

ARG COMMAND_LINE_TOOLS_BUILD=11076708
ARG ANDROID_PLATFORM=android-35
ARG ANDROID_BUILD_TOOLS=35.0.0
ARG BUILDER_UID=10001
ARG BUILDER_USER=builder

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH

# Minimal tools needed to fetch and unpack the Android command-line tools.
# Kept to one layer and cleaned up so the published image stays small.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl unzip; \
    rm -rf /var/lib/apt/lists/*

# Android command-line tools (versioned build), laid out under
# $ANDROID_HOME/cmdline-tools/latest as `sdkmanager` expects.
RUN set -eux; \
    mkdir -p "$ANDROID_HOME/cmdline-tools"; \
    tmp="$(mktemp -d)"; \
    curl -fsSL -o "$tmp/clt.zip" \
      "https://dl.google.com/android/repository/commandlinetools-linux-${COMMAND_LINE_TOOLS_BUILD}_latest.zip"; \
    unzip -q "$tmp/clt.zip" -d "$ANDROID_HOME/cmdline-tools"; \
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"; \
    rm -rf "$tmp"

# Platform + build tools pinned to the project's compileSdk (35). Licenses are
# accepted at build time so the image carries the `licenses/` hashes and a job
# never has to hit the network or prompt.
RUN set -eux; \
    yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true; \
    sdkmanager --sdk_root="$ANDROID_HOME" \
      "platform-tools" \
      "platforms;${ANDROID_PLATFORM}" \
      "build-tools;${ANDROID_BUILD_TOOLS}"; \
    rm -rf "$ANDROID_HOME/.temp" "$ANDROID_HOME/.downloadIntermediates"

# Unprivileged build user. `scripts/ci/run-as-nonroot.sh` reuses this account
# when it exists and only creates it otherwise, so the uid is identical to the
# one the existing container jobs use.
RUN set -eux; \
    groupadd -g "${BUILDER_UID}" "${BUILDER_USER}"; \
    useradd -m -u "${BUILDER_UID}" -g "${BUILDER_UID}" -s /bin/bash "${BUILDER_USER}"; \
    mkdir -p "/home/${BUILDER_USER}/.gradle"; \
    chown -R "${BUILDER_UID}:${BUILDER_UID}" "/home/${BUILDER_USER}" "$ANDROID_HOME"

# Fail the build if any pinned component is missing, so a broken image can
# never be published under a digest that later jobs blindly trust.
RUN set -eux; \
    java -version; \
    sdkmanager --version; \
    test -x "$ANDROID_HOME/platform-tools/adb"; \
    test -d "$ANDROID_HOME/platforms/${ANDROID_PLATFORM}"; \
    test -d "$ANDROID_HOME/build-tools/${ANDROID_BUILD_TOOLS}"

USER ${BUILDER_USER}
WORKDIR /home/${BUILDER_USER}
