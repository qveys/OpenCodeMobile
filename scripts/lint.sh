#!/usr/bin/env bash
# scripts/lint.sh — static analysis (detekt) for every Kotlin source file.
#
# Runs the `detektAll` aggregate task, configured in `build.gradle.kts` with the
# bug-focused rules in `config/detekt/detekt.yml`. It combines the portable root
# scan (every `.kt`, no type resolution, covers `iosMain`) with the type-resolved
# per-module tasks, so the rules that need a compilation classpath (OPE-213)
# actually run. One command keeps the CI `lint` gate simple: one pass/fail.
#
# Usage:
#   scripts/lint.sh [-- <extra gradle args>]
#
# Environment:
#   CI=1      adds `--no-daemon` for reproducible CI runs.
#
# Note: the GitHub commit API does not carry the executable bit, so call this
# as `bash scripts/lint.sh` unless the checkout already made it executable.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ "${1:-}" = "--" ]; then shift; fi

usage() {
    cat <<'EOF'
scripts/lint.sh — static analysis (detekt) for every Kotlin source file.

Usage:
  scripts/lint.sh [-- <extra gradle args>]

Runs `./gradlew detektAll`. Rules are configured in config/detekt/detekt.yml and
focus on defects (potential bugs, coroutines, exceptions, empty blocks) rather
than formatting style. detektAll runs the portable root scan plus the
type-resolved per-module tasks, so rules that need a classpath are active.

Environment:
  CI=1      adds `--no-daemon` for reproducible CI runs.

Note: the GitHub commit API does not carry the executable bit, so call this
as `bash scripts/lint.sh` unless the checkout already made it executable.
EOF
}

case "${1:-}" in
    -h | --help)
        usage
        exit 0
        ;;
esac

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

run_gradle detektAll "$@"

echo "OK: detekt found no issues."
