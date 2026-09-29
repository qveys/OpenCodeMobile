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

# module path -> framework base name (ADR 0001 §3, iosApp/README.md).
MODULES=(
  ":iosAppHost|iosAppHost"
  ":features:connection|featuresConnection"
  ":design-system|designSystem"
  ":shared:domain|sharedDomain"
  ":shared:application|sharedApplication"
  ":shared:security|sharedSecurity"
)

OUT="$ROOT/iosApp/build/frameworks"
rm -rf "$OUT"
mkdir -p "$OUT"

TASKS=()
for entry in "${MODULES[@]}"; do
  TASKS+=("${entry%%|*}:link${KMP_VARIANT}Framework${KMP_TARGET}")
done

echo "== Building Kotlin/Native frameworks (target=${KMP_TARGET}, variant=${KMP_VARIANT}) =="
./gradlew "${TASKS[@]}" --no-daemon --stacktrace

for entry in "${MODULES[@]}"; do
  module_path="${entry%%|*}"
  name="${entry##*|}"
  rel="$(printf '%s' "${module_path#:}" | tr ':' '/')"
  src="$ROOT/$rel/build/bin/${KMP_TARGET}/${KMP_VARIANT_LC}Framework/${name}.framework"
  if [ ! -d "$src" ]; then
    echo "::error::expected framework not found: $src" >&2
    exit 1
  fi
  cp -R "$src" "$OUT/"
done

echo "== Staged frameworks in $OUT =="
ls -1 "$OUT"
