#!/usr/bin/env bash
# Local, hermetic tests for merge-agent-pr.sh using a fake `gh`.
# No live GitHub API call is made.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/merge-agent-pr.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

BIN="$WORK/bin"
mkdir -p "$BIN"
CALLS="$WORK/merge-calls.log"
export CALLS

cat > "$BIN/gh" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
if [ "$1" = "repo" ] && [ "$2" = "view" ]; then
  echo "qveys/OpenCodeMobile"; exit 0
fi
if [ "$1" = "pr" ] && [ "$2" = "view" ]; then
  echo "${FAKE_PR_JSON:?FAKE_PR_JSON not set}"; exit 0
fi
if [ "$1" = "pr" ] && [ "$2" = "merge" ]; then
  echo "$*" >> "$CALLS"
  exit 0
fi
echo "fake-gh: unhandled: $*" >&2
exit 1
FAKE
chmod +x "$BIN/gh"
export PATH="$BIN:$PATH"

run_case() {
  local name="$1" expect="$2" json="$3"; shift 3
  : > "$CALLS"
  local out status
  out=$(FAKE_PR_JSON="$json" "$@" 2>&1) && status=0 || status=$?
  local merged="no"; [ -s "$CALLS" ] && merged="yes"
  if [ "$expect" = "refuse" ]; then
    if [ "$status" -ne 0 ] && [ "$merged" = "no" ]; then
      echo "[✓] $name: refused and did not merge"
    else
      echo "[-] $name: expected refuse/no-merge, got status=$status merged=$merged"; echo "$out"; return 1
    fi
  else
    if [ "$status" -eq 0 ] && [ "$merged" = "yes" ]; then
      echo "[✓] $name: merged"
    else
      echo "[-] $name: expected merge, got status=$status merged=$merged"; echo "$out"; return 1
    fi
  fi
}

blocked='{"number":8,"state":"OPEN","isDraft":false,"reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"BLOCKED","author":{"login":"app/my-paperclip-company"},"url":"https://example/pr/8"}'
approved='{"number":8,"state":"OPEN","isDraft":false,"reviewDecision":"APPROVED","mergeStateStatus":"CLEAN","author":{"login":"app/my-paperclip-company"},"url":"https://example/pr/8"}'
behind='{"number":8,"state":"OPEN","isDraft":false,"reviewDecision":"APPROVED","mergeStateStatus":"BEHIND","author":{"login":"app/my-paperclip-company"},"url":"https://example/pr/8"}'
draft='{"number":8,"state":"OPEN","isDraft":true,"reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"BLOCKED","author":{"login":"app/my-paperclip-company"},"url":"https://example/pr/8"}'

FAILS=0
run_case "review-required blocks merge"      refuse  "$blocked"  bash "$SCRIPT" 8 --repo qveys/OpenCodeMobile || FAILS=$((FAILS+1))
run_case "approved + clean merges"           merge   "$approved" bash "$SCRIPT" 8 --repo qveys/OpenCodeMobile || FAILS=$((FAILS+1))
run_case "approved but behind refuses"       refuse  "$behind"   bash "$SCRIPT" 8 --repo qveys/OpenCodeMobile || FAILS=$((FAILS+1))
run_case "draft refuses"                     refuse  "$draft"    bash "$SCRIPT" 8 --repo qveys/OpenCodeMobile || FAILS=$((FAILS+1))
run_case "arm uses --auto"                   merge   "$approved" bash "$SCRIPT" 8 --repo qveys/OpenCodeMobile --arm || FAILS=$((FAILS+1))
run_case "rebase method accepted"             merge   "$approved" bash "$SCRIPT" 8 --repo qveys/OpenCodeMobile --method rebase || FAILS=$((FAILS+1))

if [ "$FAILS" -ne 0 ]; then
  echo "[-] $FAILS test(s) failed"; exit 1
fi
echo "[✓] all merge-agent-pr.sh tests passed"