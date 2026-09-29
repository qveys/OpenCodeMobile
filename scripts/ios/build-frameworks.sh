#!/usr/bin/env bash
#
# OPE-169 — build the per-module Kotlin/Native static frameworks the iosApp
# Xcode target links, for the SDK/arch Xcode is currently building, and stage
# them in iosApp/build/frameworks so the project's FRAMEWORK_SEARCH_PATHS finds
# them.
#
# ADR 0001 §3/§4 fixes the model: there is no umbrella framework, Xcode links
# each module's `.framework` directly (the iOS counterpart of `androidApp`'s one
# Gradle dependency per module). This script is the bridge between the two
# build systems and is safe to run standalone:
#
#   scripts/ios/build-frameworks.sh
#
# Inside Xcode it is invoked from the "Build Kotlin frameworks" run-script build
# phase, which Xcode runs before the target's Swift compile/link phases. It
# derives the Kotlin/Native target from Xcode's PLATFORM_NAME/ARCHS and the
# Gradle variant from CONFIGURATION.
#
# Only the framework-producing modules the app actually reaches are linked
# (see `iosApp/project.yml`): iosAppHost -> featuresConnection -> designSystem
# and the shared/domain, shared/application, shared/security frameworks.
# Modules without a framework binary (shared/networking, shared/tls-test-support)
# are compiled into whichever framework depends on them.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# Xcode's run-script phase does not source the runner toolchain env itself, so
# resolve JDK 21 the same way CI steps do when JAVA_HOME is not already set.
if [ -z "${JAVA_HOME:-}" ] && [ -f "$ROOT/scripts/ci/runner-toolchain-env.sh" ]; then
  # shellcheck disable=SC1091
  . "$ROOT/scripts/ci/runner-toolchain-env.sh"
fi

PLATFORM="${PLATFORM_NAME:-iphonesimulator}"
ARCH_LIST="${ARCHS:-${CURRENT_ARCH:-}}"

# Xcode passes a space-separated ARCHS list (e.g. "arm64 x86_64"). Kotlin/Native
# frameworks are per-arch, so build the host's active arch.
ACTIVE_ARCH="${ARCH_LIST%% *}"
case "$PLATFORM/${ACTIVE_ARCH:-$(uname -m)}" in
  iphoneos/arm64*)          KMP_TARGET=IosArm64 ;;
  iphonesimulator/arm64*)   KMP_TARGET=IosSimulatorArm64 ;;
  iphonesimulator/x86_64*)  KMP_TARGET=IosX64 ;;
  *)
    if [ "$(uname -m)" = "arm64" ]; then KMP_TARGET=IosSimulatorArm64; else KMP_TARGET=IosX64; fi
    ;;
esac

case "${CONFIGURATION:-Debug}" in
  Release) KMP_VARIANT=Release ;;
  *)       KMP_VARIANT=Debug ;;
esac
KMP_VARIANT_LC="$(printf '%s' "$KMP_VARIANT" | tr '[:upper:]' '[:lower:]')"

# The app links a single framework: the composition root.
#
# The per-module frameworks are still declared (ADR 0001 §3) and their outputs
# are what the Build CI verifies, but Xcode must not link several of them at
# once. A Kotlin/Native *static* framework contains the Kotlin runtime **and**
# the code of all its transitive dependencies (linking iosAppHost + a module it
# depends on produces duplicate `_Kotlin_*` symbols), so injecting more than one
# runtime aborts at `+[KotlinBase load]` (`injectToRuntime()`). Building and
# linking only `iosAppHost.framework` gives exactly one runtime and still
# resolves every symbol the Swift shell imports, because the framework already
# carries featuresConnection, designSystem, sharedDomain/application/security
# and the Compose dependencies.
MODULE_PATH=":iosAppHost"
FRAMEWORK_NAME="iosAppHost"

OUT="$ROOT/iosApp/build/frameworks"
rm -rf "$OUT"
mkdir -p "$OUT"

echo "== Building Kotlin/Native framework (target=${KMP_TARGET}, variant=${KMP_VARIANT}) =="
# `bash ./gradlew` because the repository's signed-commit flow cannot carry the
# executable bit (gradlew is recorded 100644), so `./gradlew` fails in a fresh
# checkout.
bash ./gradlew "${MODULE_PATH}:link${KMP_VARIANT}Framework${KMP_TARGET}" --no-daemon --stacktrace

rel="$(printf '%s' "${MODULE_PATH#:}" | tr ':' '/')"
src="$ROOT/$rel/build/bin/${KMP_TARGET}/${KMP_VARIANT_LC}Framework/${FRAMEWORK_NAME}.framework"
if [ ! -d "$src" ]; then
  echo "::error::expected framework not found: $src" >&2
  exit 1
fi
cp -R "$src" "$OUT/"

echo "== Staged framework in $OUT =="
ls -1 "$OUT"
