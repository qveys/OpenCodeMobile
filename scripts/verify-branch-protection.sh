#!/usr/bin/env bash
# scripts/verify-branch-protection.sh
# Read-only verification of branch protection on `main` via the GitHub REST API.
# Returns 0 only when every declared control is verified.
# Returns 1 when any declared control is missing/false, or when a control cannot
# be read or parsed (verification is then incomplete, never "compliant").

set -uo pipefail

REPO="${1:-}"
if [ -z "$REPO" ]; then
  if git remote get-url origin >/dev/null 2>&1; then
    REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)"
  fi
fi

if [ -z "$REPO" ]; then
  REPO="qveys/OpenCodeMobile"
fi

# Required status-check contexts declared by scripts/setup-branch-protection.sh.
# "T4 static scan" is the OPE-56 / threat T4 redaction gate job in
# `.github/workflows/security-logging.yml`. Add lint/test/build here once those
# workflows exist on `main` (OPE-14/OPE-15/OPE-16/OPE-17).
REQUIRED_CONTEXTS=("T4 static scan")

echo "=========================================================="
echo "Verifying Branch Protection for: $REPO (branch: main)"
echo "=========================================================="

# Fetch branch protection configuration from GitHub API (read-only).
API_JSON="$(gh api "repos/$REPO/branches/main/protection" 2>&1)" || {
  echo "[-] FAILED: Could not read branch protection for $REPO:main"
  echo "[-] GitHub API response: $API_JSON"
  exit 1
}

if ! printf '%s' "$API_JSON" | jq -e . >/dev/null 2>&1; then
  echo "[-] FAILED: branch protection response is malformed JSON; cannot verify"
  exit 1
fi

FAILURES=0

# Compare an arbitrary JSON document field against an expected string.
check_json_field() {
  local json="$1"
  local description="$2"
  local expr="$3"
  local expected="$4"
  local actual
  actual="$(printf '%s' "$json" | jq -r "$expr" 2>/dev/null)" || actual=""
  if [ "$actual" = "$expected" ]; then
    echo "[✓] PASS: $description (value: $actual)"
  else
    echo "[-] FAIL: $description (expected: $expected, actual: ${actual:-<missing>})"
    FAILURES=$((FAILURES + 1))
  fi
}

# Check an integer field is greater than or equal to a minimum.
check_json_ge() {
  local json="$1"
  local description="$2"
  local expr="$3"
  local min="$4"
  local actual
  actual="$(printf '%s' "$json" | jq -r "$expr" 2>/dev/null)" || actual=""
  if [ "$actual" -ge "$min" ] 2>/dev/null; then
    echo "[✓] PASS: $description (value: $actual >= $min)"
  else
    echo "[-] FAIL: $description (expected >= $min, actual: ${actual:-<missing>})"
    FAILURES=$((FAILURES + 1))
  fi
}

# Verify a required CI context by set membership, not by array length.
check_required_context() {
  local context="$1"
  if printf '%s' "$API_JSON" | jq -e --arg c "$context" \
       '.required_status_checks.contexts | type == "array" and index($c) != null' \
       >/dev/null 2>&1; then
    echo "[✓] PASS: required status check context '$context' is present"
  else
    echo "[-] FAIL: required status check context '$context' is missing"
    FAILURES=$((FAILURES + 1))
  fi
}

echo ""
echo "--- PR review controls ---"
check_json_ge "$API_JSON" "Required approving review count >= 1" ".required_pull_request_reviews.required_approving_review_count" 1
check_json_field "$API_JSON" "Dismiss stale reviews on new commits" ".required_pull_request_reviews.dismiss_stale_reviews" "true"
check_json_field "$API_JSON" "Require approval of the most recent push" ".required_pull_request_reviews.require_last_push_approval" "true"

echo ""
echo "--- Direct-push / admin controls ---"
check_json_field "$API_JSON" "Enforce protection for administrators" ".enforce_admins.enabled" "true"
check_json_field "$API_JSON" "Disallow force pushes" ".allow_force_pushes.enabled" "false"
check_json_field "$API_JSON" "Disallow branch deletion" ".allow_deletions.enabled" "false"

echo ""
echo "--- Status checks and conversation resolution ---"
check_json_field "$API_JSON" "Strict status checks (branch up to date)" ".required_status_checks.strict" "true"
for context in "${REQUIRED_CONTEXTS[@]}"; do
  check_required_context "$context"
done
check_json_field "$API_JSON" "Require conversation resolution before merge" ".required_conversation_resolution.enabled" "true"

echo ""
echo "--- Required commit signatures (read-only readback) ---"
SIGNATURES_JSON="$(gh api "repos/$REPO/branches/main/protection/required_signatures" 2>&1)" || SIGNATURES_JSON=""
if [ -z "$SIGNATURES_JSON" ] || ! printf '%s' "$SIGNATURES_JSON" | jq -e . >/dev/null 2>&1; then
  echo "[-] FAIL: could not read required-signatures state; verification INCOMPLETE"
  FAILURES=$((FAILURES + 1))
else
  check_json_field "$SIGNATURES_JSON" "Required commit signatures enabled" ".enabled" "true"
fi

echo ""
echo "--- Fork PR contributor approval (OPE-10 criterion 4) ---"
FORK_JSON="$(gh api "repos/$REPO/actions/permissions/fork-pr-contributor-approval" 2>&1)" || FORK_JSON=""
if [ -z "$FORK_JSON" ] || ! printf '%s' "$FORK_JSON" | jq -e . >/dev/null 2>&1; then
  echo "[-] FAIL: fork-approval policy unreadable; verification INCOMPLETE for this control"
  FAILURES=$((FAILURES + 1))
else
  APPROVAL_POLICY="$(printf '%s' "$FORK_JSON" | jq -r '.approval_policy // empty' 2>/dev/null)" || APPROVAL_POLICY=""
  case "$APPROVAL_POLICY" in
    first_time_contributors | first_time_contributors_new_to_github | all_external_contributors)
      echo "[✓] PASS: fork PR workflows require maintainer approval (approval_policy=$APPROVAL_POLICY)"
      ;;
    *)
      echo "[-] FAIL: fork-approval policy does not require maintainer approval (actual: ${APPROVAL_POLICY:-<missing>})"
      FAILURES=$((FAILURES + 1))
      ;;
  esac
fi

echo ""
echo "--- Actions workflow permissions (T10 / OPE-23) ---"
ACTIONS_JSON="$(gh api "repos/$REPO/actions/permissions/workflow" 2>&1)" || ACTIONS_JSON=""
if [ -z "$ACTIONS_JSON" ] || ! printf '%s' "$ACTIONS_JSON" | jq -e . >/dev/null 2>&1; then
  echo "[-] FAIL: could not read Actions workflow permissions; verification INCOMPLETE"
  FAILURES=$((FAILURES + 1))
else
  check_json_field "$ACTIONS_JSON" "Actions default workflow permissions restricted to 'read'" ".default_workflow_permissions" "read"
  check_json_field "$ACTIONS_JSON" "Actions workflows cannot approve pull request reviews" ".can_approve_pull_request_reviews" "false"
fi

# Criterion 4: Fork pull request workflows / Actions permissions (T10 / OPE-23)
echo ""
echo "--- Actions Security Configuration Checks (T10 / OPE-23) ---"
ACTIONS_RESPONSE=$(gh api "repos/$REPO/actions/permissions/workflow" 2>/dev/null || echo "{}")
ACTIONS_DEFAULT_PERM=$(echo "$ACTIONS_RESPONSE" | jq -r ".default_workflow_permissions // empty" 2>/dev/null)
ACTIONS_APPROVE_PR=$(echo "$ACTIONS_RESPONSE" | jq -r ".can_approve_pull_request_reviews" 2>/dev/null)

if [ "$ACTIONS_DEFAULT_PERM" = "read" ]; then
  echo "[✓] PASS: Actions default workflow permissions restricted to 'read'"
else
  echo "[-] FAIL: Actions default workflow permissions (expected: read, actual: $ACTIONS_DEFAULT_PERM)"
  FAILURES=$((FAILURES + 1))
fi

if [ "$ACTIONS_APPROVE_PR" = "false" ]; then
  echo "[✓] PASS: Actions workflows cannot approve pull request reviews"
else
  echo "[-] FAIL: Actions can approve pull requests (expected: false, actual: $ACTIONS_APPROVE_PR)"
  FAILURES=$((FAILURES + 1))
fi

echo ""
if [ "$FAILURES" -eq 0 ]; then
  echo "[✓] ALL DECLARED PROTECTION CONTROLS VERIFIED via read-only GitHub API."
  exit 0
else
  echo "[-] VERIFICATION FAILED/INCOMPLETE: $FAILURES check(s) did not meet required criteria."
  exit 1
fi