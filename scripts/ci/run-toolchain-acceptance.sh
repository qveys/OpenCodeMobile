#!/usr/bin/env bash
#
# OPE-98 — acceptance check for the self-hosted runner toolchain.
#
# Runs the exact command named in OPE-98's acceptance criteria on whatever
# runner executes it:
#
#   ./gradlew :shared:networking:testDebugUnitTest --no-daemon
#
# Use it as a CI step (after sourcing scripts/ci/runner-toolchain-env.sh) or
# locally from an operator shell on a runner. Exits non-zero if the toolchain
# or the build is broken.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# shellcheck source=/dev/null
. scripts/ci/runner-toolchain-env.sh

echo "== Toolchain =="
echo "JAVA_HOME=${JAVA_HOME:-<unset>}"
echo "ANDROID_HOME=${ANDROID_HOME:-<unset>}"
if ! command -v java >/dev/null 2>&1; then
  echo "::error::java is not on PATH — run scripts/ci/provision-runner-toolchain.sh first"
  exit 1
fi
java -version
if [ -x /usr/libexec/java_home ]; then
  echo "java_home -v 21 -> $(/usr/libexec/java_home -v 21 2>/dev/null || echo '<unresolved>')"
fi
if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "== Installed Android packages =="
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --list_installed 2>/dev/null || true
fi

echo "== Gradle acceptance: :shared:networking:testDebugUnitTest =="
chmod +x gradlew
./gradlew :shared:networking:testDebugUnitTest --no-daemon --stacktrace

echo "== Toolchain acceptance passed =="
