#!/usr/bin/env bash
# scripts/tests/test-supply-chain-gates.sh
# Local tests for the SEC-04 / SEC-05 supply-chain gates.
#
# Runs scripts/check-workflow-action-pinning.sh and
# scripts/check-gradle-supply-chain.sh against synthetic fixtures in a temp
# directory. It never contacts GitHub or any artifact repository, and never
# touches the real repository files.
#
# Run: bash scripts/tests/test-supply-chain-gates.sh

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PINNING="$ROOT/scripts/check-workflow-action-pinning.sh"
GRADLE_GATE="$ROOT/scripts/check-gradle-supply-chain.sh"

PASS=0
FAIL=0

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

check() { # description expected actual
  if [ "$2" = "$3" ]; then
    printf '  ok   %s\n' "$1"
    PASS=$((PASS + 1))
  else
    printf '  FAIL %s (expected: %s, actual: %s)\n' "$1" "$2" "$3"
    FAIL=$((FAIL + 1))
  fi
}

check_contains() { # description haystack needle
  case "$2" in
    *"$3"*) printf '  ok   %s\n' "$1"; PASS=$((PASS + 1)) ;;
    *) printf '  FAIL %s (missing: %s)\n' "$1" "$3"; FAIL=$((FAIL + 1)) ;;
  esac
}

# --- fixtures ---------------------------------------------------------------

# Build a throwaway repository containing only the files the gates read.
make_repo() { # name
  local dir="$TMP_ROOT/$1"
  mkdir -p "$dir/.github/workflows" "$dir/gradle/wrapper" "$dir/scripts"
  cp "$PINNING" "$GRADLE_GATE" "$dir/scripts/"
  printf '%s' "$dir"
}

PINNED='      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4.4.0'

write_wrapper() { # dir [sha_sum_line] [url]
  local dir="$1" sha="${2:-}" url="${3:-https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip}"
  {
    printf 'distributionBase=GRADLE_USER_HOME\n'
    printf 'distributionUrl=%s\n' "$url"
    [ -n "$sha" ] && printf '%s\n' "$sha"
    printf 'networkTimeout=10000\n'
    printf 'validateDistributionUrl=true\n'
  } > "$dir/gradle/wrapper/gradle-wrapper.properties"
}

write_wrapper_jar() { # dir [digest_override]
  local dir="$1" override="${2:-}"
  printf 'not really a jar\n' > "$dir/gradle/wrapper/gradle-wrapper.jar"
  local actual
  actual="$(sha256sum "$dir/gradle/wrapper/gradle-wrapper.jar" | awk '{print $1}')"
  printf '%s  gradle/wrapper/gradle-wrapper.jar\n' "${override:-$actual}" \
    > "$dir/gradle/wrapper/gradle-wrapper.jar.sha256"
}

# --- SEC-04: action pinning -------------------------------------------------

echo ""
echo "== SEC-04: check-workflow-action-pinning.sh rejects floating action refs =="

d="$(make_repo sec04-pinned)"
printf 'name: x\njobs:\n  a:\n    steps:\n%s\n' "$PINNED" > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "SHA-pinned workflow exits zero" "0" "$code"
check_contains "SHA-pinned workflow reports the count" "$out" "1 remote action reference(s) pinned"

d="$(make_repo sec04-floating)"
printf 'name: x\njobs:\n  a:\n    steps:\n      - uses: actions/checkout@v4\n' \
  > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "floating major tag exits nonzero" "1" "$code"
check_contains "floating major tag names the ref" "$out" "actions/checkout@v4"
check_contains "floating major tag cites SEC-04" "$out" "SEC-04"

d="$(make_repo sec04-branch)"
printf 'name: x\njobs:\n  a:\n    steps:\n      - uses: gradle/actions/setup-gradle@main\n' \
  > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "floating branch ref exits nonzero" "1" "$code"
check_contains "floating branch ref names the ref" "$out" "setup-gradle@main"

d="$(make_repo sec04-partial-sha)"
# 39 hex characters — one short of a real commit SHA.
printf 'name: x\njobs:\n  a:\n    steps:\n      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af67726\n' \
  > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "truncated SHA exits nonzero" "1" "$code"

d="$(make_repo sec04-extra-sha)"
# 41 hex characters — must not be accepted by a prefix match.
printf 'name: x\njobs:\n  a:\n    steps:\n      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af6772622\n' \
  > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "over-long SHA exits nonzero" "1" "$code"

d="$(make_repo sec04-local)"
printf 'name: x\njobs:\n  a:\n    steps:\n      - uses: ./.github/actions/local\n' \
  > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "local ./ action is exempt" "0" "$code"

d="$(make_repo sec04-quoted)"
printf 'name: x\njobs:\n  a:\n    steps:\n      - uses: "actions/checkout@v4"\n' \
  > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "quoted floating ref is still caught" "1" "$code"

d="$(make_repo sec04-no-dir)"
rm -rf "$d/.github/workflows"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "missing workflow dir exits nonzero" "1" "$code"

d="$(make_repo sec04-vacuous)"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "no workflows at all exits nonzero (fail closed)" "1" "$code"
check_contains "vacuous scan is reported" "$out" "no longer matching anything"

# --- SEC-05: Gradle toolchain integrity -------------------------------------

echo ""
echo "== SEC-05: check-gradle-supply-chain.sh requires an integrity-pinned toolchain =="

GOOD_SHA='distributionSha256Sum=31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26'

d="$(make_repo sec05-ok)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "fully pinned toolchain exits zero" "0" "$code"
check_contains "wrapper JAR match is reported" "$out" "matches its committed SHA-256"

d="$(make_repo sec05-no-sum)"
write_wrapper "$d" ""
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "missing distributionSha256Sum exits nonzero" "1" "$code"
check_contains "missing sum names the property" "$out" "distributionSha256Sum"

d="$(make_repo sec05-bad-sum)"
write_wrapper "$d" 'distributionSha256Sum=not-a-hash'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "malformed distributionSha256Sum exits nonzero" "1" "$code"

d="$(make_repo sec05-http)"
write_wrapper "$d" "$GOOD_SHA" 'http\://services.gradle.org/distributions/gradle-8.10.2-bin.zip'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "http distributionUrl exits nonzero" "1" "$code"
check_contains "http downgrade is named" "$out" "not https"

d="$(make_repo sec05-tampered-jar)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d" '0000000000000000000000000000000000000000000000000000000000000000'
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "wrapper JAR checksum mismatch exits nonzero" "1" "$code"
check_contains "mismatch is reported" "$out" "checksum mismatch"

d="$(make_repo sec05-no-jar-checksum)"
write_wrapper "$d" "$GOOD_SHA"
printf 'not really a jar\n' > "$d/gradle/wrapper/gradle-wrapper.jar"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "missing wrapper JAR checksum exits nonzero" "1" "$code"

d="$(make_repo sec05-no-properties)"
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "missing wrapper properties exits nonzero" "1" "$code"

# --- the real repository must satisfy both gates ---------------------------

echo ""
echo "== The real repository satisfies both gates =="

out="$(cd "$ROOT" && bash "$PINNING" 2>&1)"; code=$?
check "real repository passes the SEC-04 gate" "0" "$code"

out="$(cd "$ROOT" && bash "$GRADLE_GATE" 2>&1)"; code=$?
check "real repository passes the SEC-05 gate" "0" "$code"

echo ""
echo "== summary: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ] || exit 1
exit 0
