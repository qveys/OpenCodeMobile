#!/usr/bin/env bash
# OPE-138 — executable-bit guard.
#
# The GitHub API commit path used by the agents (see GIT.md §5) does not
# transport the file mode. Any file created or replaced that way lands as
# `100644`, which silently breaks the first command a contributor is told to
# run:
#
#   $ ./gradlew build
#   bash: ./gradlew: Permission denied
#
# This cheap, JDK-free gate fails the PR when a file that must be executable
# loses its `100755` bit, so the regression cannot reach `main` unnoticed.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

fail=0

check_mode() {
  local path="$1" staged mode
  staged="$(git ls-files --stage -- "$path")"
  if [ -z "$staged" ]; then
    echo "::error file=$path::$path is not tracked by git"
    fail=1
    return
  fi
  mode="$(printf '%s\n' "$staged" | awk 'NR==1 {print $1}')"
  if [ "$mode" != "100755" ]; then
    echo "::error file=$path::$path has git mode $mode, expected 100755 (executable bit missing)"
    fail=1
  fi
}

# The Gradle wrapper is the documented first-contact command for every
# contributor, on every platform.
check_mode gradlew

# Any tracked file that declares a shebang is executable-intent.
while IFS= read -r path; do
  [ -n "$path" ] || continue
  [ "$path" = "gradlew" ] && continue
  if head -c 2 "$path" 2>/dev/null | grep -q '^#!'; then
    check_mode "$path"
  fi
done < <(git ls-files)

if [ "$fail" -ne 0 ]; then
  echo
  echo "Executable-intent files must be committed with mode 100755."
  echo "Fix: git update-index --chmod=+x <path>, or set mode 100755 when the"
  echo "file is (re)created through the GitHub API (see GIT.md §5)."
  exit 1
fi

echo "OK: gradlew and every tracked shebang file are mode 100755."
