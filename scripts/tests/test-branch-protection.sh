#!/usr/bin/env bash
# scripts/tests/test-branch-protection.sh
# Local mocked-API tests for the SEC-01 / SEC-02 remediation (OPE-38).
#
# Runs scripts/setup-branch-protection.sh and scripts/verify-branch-protection.sh
# with a fake `gh` on PATH that serves fixtures from a temp directory. It never
# contacts GitHub and never touches live repository policy.
#
# Run: bash scripts/tests/test-branch-protection.sh

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SETUP="$ROOT/scripts/setup-branch-protection.sh"
VERIFY="$ROOT/scripts/verify-branch-protection.sh"
REPO="example/example"

PASS=0
FAIL=0

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

MOCK_BIN="$TMP_ROOT/bin"
mkdir -p "$MOCK_BIN"

cat > "$MOCK_BIN/gh" <<'STUB'
#!/usr/bin/env bash
# Minimal gh stub: serves fixtures from $MOCK_GH_DIR keyed by "<METHOD>_<path>".
set -u
if [ -z "${MOCK_GH_DIR:-}" ]; then
  echo "mock gh: MOCK_GH_DIR is not set" >&2
  exit 90
fi

method="GET"
path=""
args=("$@")
i=0
while [ "$i" -lt "${#args[@]}" ]; do
  case "${args[$i]}" in
    --method | -X)
      i=$((i + 1))
      method="${args[$i]:-GET}"
      ;;
    repos/*)
      path="${args[$i]}"
      ;;
  esac
  i=$((i + 1))
done
method="$(printf '%s' "$method" | tr '[:lower:]' '[:upper:]')"

if [ -z "$path" ]; then
  echo "mock gh: no repos/* path in args" >&2
  exit 91
fi

base="$MOCK_GH_DIR/${method}_$(printf '%s' "$path" | tr '/' '_')"

if [ -f "$base.exit" ]; then
  echo "mock gh: simulated API failure for $method $path" >&2
  exit "$(cat "$base.exit")"
fi
if [ -f "$base.json" ]; then
  cat "$base.json"
  exit 0
fi
echo "mock gh: no fixture for $method $path (looked for $base.json)" >&2
exit 92
STUB
chmod +x "$MOCK_BIN/gh"

# --- fixture helpers -------------------------------------------------------

fixture_name() { # method path
  printf '%s_%s' "$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')" \
    "$(printf '%s' "$2" | tr '/' '_')"
}

put_json() { # dir method path body
  printf '%s' "$4" > "$1/$(fixture_name "$2" "$3").json"
}

put_exit() { # dir method path code
  printf '%s\n' "$4" > "$1/$(fixture_name "$2" "$3").exit"
}

new_scenario() { # name -> prints dir
  local dir="$TMP_ROOT/scenario-$1"
  mkdir -p "$dir"
  printf '%s' "$dir"
}

protection_path() { printf 'repos/%s/branches/main/protection' "$REPO"; }
signatures_path() { printf 'repos/%s/branches/main/protection/required_signatures' "$REPO"; }
fork_path() { printf 'repos/%s/actions/permissions/fork-pr-contributor-approval' "$REPO"; }
workflow_path() { printf 'repos/%s/actions/permissions/workflow' "$REPO"; }

COMPLIANT_PROTECTION='{
  "required_status_checks": { "strict": true, "contexts": ["lint", "test", "build"] },
  "enforce_admins": { "enabled": true },
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_last_push_approval": true,
    "required_approving_review_count": 1
  },
  "allow_force_pushes": { "enabled": false },
  "allow_deletions": { "enabled": false },
  "required_conversation_resolution": { "enabled": true }
}'

SIGNATURES_ENABLED='{"enabled": true}'
FORK_APPROVAL='{"approval_policy": "first_time_contributors"}'
WORKFLOW_PERMS='{"default_workflow_permissions": "read", "can_approve_pull_request_reviews": false}'

# Seed a scenario with a compliant policy (then callers override individual keys).
seed_compliant() { # dir
  put_json "$1" GET "$(protection_path)" "$COMPLIANT_PROTECTION"
  put_json "$1" GET "$(signatures_path)" "$SIGNATURES_ENABLED"
  put_json "$1" GET "$(fork_path)" "$FORK_APPROVAL"
  put_json "$1" GET "$(workflow_path)" "$WORKFLOW_PERMS"
}

# --- run + assertion helpers ----------------------------------------------

run_setup() { # scenario-dir
  MOCK_GH_DIR="$1" PATH="$MOCK_BIN:$PATH" bash "$SETUP" "$REPO" 2>&1
}

run_verify() { # scenario-dir
  MOCK_GH_DIR="$1" PATH="$MOCK_BIN:$PATH" bash "$VERIFY" "$REPO" 2>&1
}

check() { # label expected actual
  if [ "$2" = "$3" ]; then
    echo "  ok   $1"
    PASS=$((PASS + 1))
  else
    echo "  FAIL $1 (expected: $2 | actual: $3)"
    FAIL=$((FAIL + 1))
  fi
}

check_contains() { # label haystack needle
  if printf '%s' "$2" | grep -qF -- "$3"; then
    echo "  ok   $1"
    PASS=$((PASS + 1))
  else
    echo "  FAIL $1 (output did not contain: $3)"
    FAIL=$((FAIL + 1))
  fi
}

check_not_contains() { # label haystack needle
  if printf '%s' "$2" | grep -qF -- "$3"; then
    echo "  FAIL $1 (output unexpectedly contained: $3)"
    FAIL=$((FAIL + 1))
  else
    echo "  ok   $1"
    PASS=$((PASS + 1))
  fi
}

echo "== SEC-01: setup-branch-protection.sh fails closed =="

s="$(new_scenario sec01-post-fails)"
put_json "$s" PUT "$(protection_path)" '{}'
put_exit "$s" POST "$(signatures_path)" 1
out="$(run_setup "$s")"; code=$?
check "signing POST failure exits nonzero" "1" "$code"
check_not_contains "signing POST failure does not report completion" "$out" "setup complete"
check_contains "signing POST failure names the control" "$out" "required commit signatures"

s="$(new_scenario sec01-readback-disabled)"
put_json "$s" PUT "$(protection_path)" '{}'
put_json "$s" POST "$(signatures_path)" '{}'
put_json "$s" GET "$(signatures_path)" '{"enabled": false}'
out="$(run_setup "$s")"; code=$?
check "disabled readback exits nonzero" "1" "$code"
check_not_contains "disabled readback does not report completion" "$out" "setup complete"
check_contains "disabled readback reports enabled=false" "$out" "enabled=false"

s="$(new_scenario sec01-success)"
put_json "$s" PUT "$(protection_path)" '{}'
put_json "$s" POST "$(signatures_path)" '{}'
put_json "$s" GET "$(signatures_path)" "$SIGNATURES_ENABLED"
out="$(run_setup "$s")"; code=$?
check "enabled readback exits zero" "0" "$code"
check_contains "enabled readback reports verified" "$out" "verified"
check_contains "enabled readback reports completion" "$out" "setup complete"

echo ""
echo "== SEC-02: verify-branch-protection.sh verifies every declared control =="

s="$(new_scenario sec02-compliant)"
seed_compliant "$s"
out="$(run_verify "$s")"; code=$?
check "compliant policy exits zero" "0" "$code"
check_contains "compliant policy reports all controls verified" "$out" "ALL DECLARED PROTECTION CONTROLS VERIFIED"

s="$(new_scenario sec02-missing-context)"
put_json "$s" GET "$(protection_path)" '{
  "required_status_checks": { "strict": true, "contexts": ["lint", "build"] },
  "enforce_admins": { "enabled": true },
  "required_pull_request_reviews": { "dismiss_stale_reviews": true, "require_last_push_approval": true, "required_approving_review_count": 1 },
  "allow_force_pushes": { "enabled": false },
  "allow_deletions": { "enabled": false },
  "required_conversation_resolution": { "enabled": true }
}'
put_json "$s" GET "$(signatures_path)" "$SIGNATURES_ENABLED"
put_json "$s" GET "$(fork_path)" "$FORK_APPROVAL"
put_json "$s" GET "$(workflow_path)" "$WORKFLOW_PERMS"
out="$(run_verify "$s")"; code=$?
check "missing CI context exits nonzero" "1" "$code"
check_contains "missing CI context is named" "$out" "context 'test' is missing"

s="$(new_scenario sec02-last-push)"
seed_compliant "$s"
put_json "$s" GET "$(protection_path)" "$(printf '%s' "$COMPLIANT_PROTECTION" | sed 's/"require_last_push_approval": true/"require_last_push_approval": false/')"
out="$(run_verify "$s")"; code=$?
check "last-push approval false exits nonzero" "1" "$code"
check_contains "last-push approval is checked" "$out" "most recent push"

s="$(new_scenario sec02-conversation)"
seed_compliant "$s"
put_json "$s" GET "$(protection_path)" "$(printf '%s' "$COMPLIANT_PROTECTION" | sed 's/"required_conversation_resolution": { "enabled": true }/"required_conversation_resolution": { "enabled": false }/')"
out="$(run_verify "$s")"; code=$?
check "conversation resolution false exits nonzero" "1" "$code"
check_contains "conversation resolution is checked" "$out" "conversation resolution"

s="$(new_scenario sec02-signatures-disabled)"
seed_compliant "$s"
put_json "$s" GET "$(signatures_path)" '{"enabled": false}'
out="$(run_verify "$s")"; code=$?
check "disabled signatures exit nonzero" "1" "$code"
check_contains "signatures control is checked" "$out" "Required commit signatures enabled"

s="$(new_scenario sec02-signatures-unreadable)"
seed_compliant "$s"
put_exit "$s" GET "$(signatures_path)" 1
out="$(run_verify "$s")"; code=$?
check "unreadable signatures exit nonzero" "1" "$code"
check_contains "unreadable signatures reported incomplete" "$out" "INCOMPLETE"

s="$(new_scenario sec02-fork-unreadable)"
seed_compliant "$s"
put_exit "$s" GET "$(fork_path)" 1
out="$(run_verify "$s")"; code=$?
check "unreadable fork policy exits nonzero" "1" "$code"
check_contains "unreadable fork policy reported incomplete" "$out" "INCOMPLETE"

s="$(new_scenario sec02-fork-missing)"
seed_compliant "$s"
put_json "$s" GET "$(fork_path)" '{}'
out="$(run_verify "$s")"; code=$?
check "missing fork policy exits nonzero" "1" "$code"
check_contains "missing fork policy reported" "$out" "fork-approval policy"

s="$(new_scenario sec02-malformed-protection)"
seed_compliant "$s"
put_json "$s" GET "$(protection_path)" 'not-json'
out="$(run_verify "$s")"; code=$?
check "malformed protection JSON exits nonzero" "1" "$code"
check_contains "malformed protection JSON reported" "$out" "malformed JSON"

s="$(new_scenario sec02-protection-unreadable)"
seed_compliant "$s"
put_exit "$s" GET "$(protection_path)" 1
out="$(run_verify "$s")"; code=$?
check "unreadable protection exits nonzero" "1" "$code"

s="$(new_scenario sec02-null-reviews)"
seed_compliant "$s"
put_json "$s" GET "$(protection_path)" '{
  "required_status_checks": { "strict": true, "contexts": ["lint", "test", "build"] },
  "enforce_admins": { "enabled": true },
  "required_pull_request_reviews": null,
  "allow_force_pushes": { "enabled": false },
  "allow_deletions": { "enabled": false },
  "required_conversation_resolution": { "enabled": true }
}'
out="$(run_verify "$s")"; code=$?
check "missing PR review block exits nonzero" "1" "$code"

s="$(new_scenario sec02-workflow-perms-bad)"
seed_compliant "$s"
put_json "$s" GET "$(workflow_path)" '{"default_workflow_permissions": "write", "can_approve_pull_request_reviews": true}'
out="$(run_verify "$s")"; code=$?
check "unsafe workflow permissions exit nonzero" "1" "$code"
check_contains "workflow permissions are checked" "$out" "default workflow permissions"

s="$(new_scenario sec02-workflow-unreadable)"
seed_compliant "$s"
put_exit "$s" GET "$(workflow_path)" 1
out="$(run_verify "$s")"; code=$?
check "unreadable workflow permissions exit nonzero" "1" "$code"
check_contains "unreadable workflow permissions reported incomplete" "$out" "INCOMPLETE"

echo ""
echo "== summary: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]