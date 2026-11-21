#!/usr/bin/env bash
# Hermetic checks for the rootless uid/gid map guard (issue #128).
# Run: bash scripts/tests/test-restore-runner-workspace-ownership.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck disable=SC1091 # path is computed from this file's location
source "$ROOT/scripts/ci/restore-runner-workspace-ownership.sh"

PASS=0
FAIL=0
TMP="$(mktemp -d)"
trap 'chmod -R u+rwx "$TMP" 2>/dev/null || true; rm -rf "$TMP"' EXIT

check_file() {
  local name=$1 expect=$2 file=$3
  local got=0
  if namespace_root_maps_to_host_user "$file"; then
    got=0
  else
    got=1
  fi
  if [[ "$got" -eq "$expect" ]]; then
    PASS=$((PASS + 1))
    printf 'PASS  %s (exit %s)\n' "$name" "$got"
  else
    FAIL=$((FAIL + 1))
    printf 'FAIL  %s (expected exit %s, got %s)\n' "$name" "$expect" "$got" >&2
  fi
}

check_text() {
  local name=$1 expect=$2 text=$3
  local file="$TMP/map"
  printf '%s' "$text" >"$file"
  chmod 644 "$file"
  check_file "$name" "$expect" "$file"
}

check_text '0 994 1 accepted' 0 '0 994 1
'
check_text '0 100000 65536 accepted' 0 '0 100000 65536
'
check_text '0 0 4294967295 refused' 1 '0 0 4294967295
'
check_text '0 0 1 refused' 1 '0 0 1
'
check_text 'empty refused' 1 ''
check_file 'missing file refused' 1 "$TMP/does-not-exist"
check_text '0 abc 1 refused' 1 '0 abc 1
'
check_text 'uid 0 line among others accepted' 0 '0 100000 65536
65536 200000 1000
'
check_text 'any 0 0 line refused' 1 '0 100000 65536
0 0 1
'
check_text 'malformed trailing row refused' 1 '0 100000 65536
65536 abc 1000
'

unreadable="$TMP/unreadable"
printf '0 994 1\n' >"$unreadable"
chmod 000 "$unreadable"
if [[ "$(id -u)" -eq 0 ]]; then
  printf 'SKIP  unreadable refused (root bypasses file mode)\n'
else
  check_file 'unreadable refused' 1 "$unreadable"
fi

printf 'passed=%s failed=%s\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
