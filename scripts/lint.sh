#!/usr/bin/env bash
# scripts/lint.sh — static analysis (detekt) for every Kotlin source file.
#
# Runs the root `detekt` task, configured in `build.gradle.kts` with the
# bug-focused rules in `config/detekt/detekt.yml`. One task keeps the CI `lint`
# gate simple: one command, one report, one pass/fail.
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

Runs `./gradlew detekt`. Rules are configured in config/detekt/detekt.yml and
focus on defects (potential bugs, coroutines, exceptions, empty blocks) rather
than formatting style.

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

run_gradle detekt "$@"

echo "OK: detekt found no issues."
