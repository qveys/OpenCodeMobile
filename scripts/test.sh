#!/usr/bin/env bash
# scripts/test.sh — Kotlin Multiplatform test runner (kotlin.test).
#
# Runs the shared/module test suites from a single revision so one commit is
# verified on both target families:
#   - Android (JVM-hosted) unit tests: Gradle `testDebugUnitTest`
#   - Apple (Kotlin/Native iOS simulator): `iosSimulatorArm64Test` on an
#     Apple Silicon host, `iosX64Test` on an Intel host (see `apple` below)
#   - JVM + all-native aggregate: Gradle `allTests`
#
# Every KMP module declares `commonTest.dependencies { implementation(kotlin-test) }`,
# so `commonTest` sources run on Android/JVM and on the Apple targets unchanged.
#
# Usage:
#   scripts/test.sh [all|android|apple] [-- <extra gradle args>]
#
#   all      (default) Android unit tests + `allTests` aggregate.
#            On macOS the aggregate runs the iOS tests too; on Linux the
#            disabled iOS targets are skipped via
#            `kotlin.native.ignoreDisabledTargets=true`.
#   android  Android/JVM unit tests only.
#   apple    iOS simulator tests only. Requires a macOS host.
#
# Environment:
#   CI=1      adds `--no-daemon` for reproducible CI runs.
#
# Note: the GitHub commit API does not carry the executable bit, so call this
# as `bash scripts/test.sh` unless the checkout already made it executable.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODE="${1:-all}"
if [ "$#" -gt 0 ]; then shift; fi
if [ "${1:-}" = "--" ]; then shift; fi

usage() {
    cat <<'EOF'
scripts/test.sh — Kotlin Multiplatform test runner (kotlin.test).

Usage:
  scripts/test.sh [all|android|apple] [-- <extra gradle args>]

  all      (default) Android unit tests + `allTests` aggregate.
           On macOS the aggregate runs the iOS tests too; on Linux the
           disabled iOS targets are skipped via
           `kotlin.native.ignoreDisabledTargets=true`.
  android  Android/JVM unit tests only.
  apple    iOS simulator tests only. Requires a macOS host.

Environment:
  CI=1      adds `--no-daemon` for reproducible CI runs.

Note: the GitHub commit API does not carry the executable bit, so call this
as `bash scripts/test.sh` unless the checkout already made it executable.
EOF
}

case "$MODE" in
    all | android | apple) ;;
    -h | --help)
        usage
        exit 0
        ;;
    *)
        echo "error: unknown mode '$MODE' (expected: all|android|apple)" >&2
        echo "run 'bash scripts/test.sh --help' for usage" >&2
        exit 2
        ;;
esac

HOST="$(uname -s)"
if [ "$MODE" = "apple" ] && [ "$HOST" != "Darwin" ]; then
    echo "error: the 'apple' suite runs Kotlin/Native iOS tests and needs a macOS host (found $HOST)." >&2
    exit 2
fi

# Fail fast if the Gradle wrapper is present but the toolchain is missing.
if [ ! -f gradlew ]; then
    echo "error: gradlew not found; run this from the repository root." >&2
    exit 2
fi
chmod +x gradlew 2>/dev/null || true

GRADLE=("$ROOT/gradlew")
if [ -n "${CI:-}" ]; then
    GRADLE+=(--no-daemon)
fi

run_gradle() {
    echo "+ ./gradlew $*"
    "${GRADLE[@]}" "$@"
}

case "$MODE" in
    android)
        # Android unit tests for every module that declares androidTarget().
        run_gradle testDebugUnitTest "$@"
        ;;
    apple)
        # The runnable iOS simulator target must match the host architecture.
        # Kotlin/Native silently SKIPS the simulator test task whose
        # architecture cannot run on the host, which would make this job pass
        # with zero tests. The company self-hosted Mac (`macbook-openclaw`) is
        # Intel (x86_64), so it runs `iosX64Test`; an Apple Silicon host runs
        # `iosSimulatorArm64Test`.
        case "$(uname -m)" in
            arm64) SIM_TASK=iosSimulatorArm64Test ;;
            *) SIM_TASK=iosX64Test ;;
        esac
        run_gradle "$SIM_TASK" "$@"
        # Fail loudly if Kotlin/Native skipped the task: without this the job
        # would be a false green (BUILD SUCCESSFUL with zero tests executed).
        if [ -z "$(find . -path "*/build/test-results/$SIM_TASK/*.xml" -print -quit)" ]; then
            echo "error: '$SIM_TASK' produced no JUnit reports on $(uname -m);" >&2
            echo "the iOS simulator tests were skipped, so the suite did not run." >&2
            exit 1
        fi
        ;;
    all)
        run_gradle testDebugUnitTest "$@"
        # KMP lifecycle aggregate: JVM + native test tasks. iOS tasks execute on
        # macOS and are skipped on Linux (ignoreDisabledTargets), so this single
        # command covers both families on the host that can run them.
        run_gradle allTests "$@"
        ;;
esac

echo "OK: '$MODE' test suite passed."