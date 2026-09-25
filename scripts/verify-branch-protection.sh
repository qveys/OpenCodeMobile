#!/usr/bin/env bash
# scripts/verify-branch-protection.sh
# Verifies branch protection on `main` via `gh api repos/:owner/:repo/branches/main/protection`
# Returns 0 on complete compliance, 1 on failure.

set -euo pipefail

REPO="${1:-}"
if [ -z "$REPO" ]; then
  if git remote get-url origin >/dev/null 2>&1; then
    REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)"
  fi
fi

if [ -z "$REPO" ]; then
  REPO="qveys/OpenCodeMobile"
fi

echo "=========================================================="
echo "Verifying Branch Protection for: $REPO (branch: main)"
echo "=========================================================="

# Fetch branch protection configuration from GitHub API
RESPONSE=$(gh api "repos/$REPO/branches/main/protection" 2>&1) || {
  echo "[-] FAILED: Could not read branch protection for $REPO:main"
  echo "[-] GitHub API response: $RESPONSE"
  exit 1
}

FAILURES=0

# Helper to check JSON field
check_rule() {
  local description="$1"
  local expr="$2"
  local expected="$3"
  local actual
  actual=$(echo "$RESPONSE" | jq -r "$expr" 2>/dev/null || echo "null")
  if [ "$actual" = "$expected" ]; then
    echo "[✓] PASS: $description (value: $actual)"
  else
    echo "[-] FAIL: $description (expected: $expected, actual: $actual)"
    FAILURES=$((FAILURES + 1))
  fi
}

# Helper to check integer >=
check_ge() {
  local description="$1"
  local expr="$2"
  local min="$3"
  local actual
  actual=$(echo "$RESPONSE" | jq -r "$expr" 2>/dev/null || echo "0")
  if [ "$actual" -ge "$min" ] 2>/dev/null; then
    echo "[✓] PASS: $description (value: $actual >= $min)"
  else
    echo "[-] FAIL: $description (expected >= $min, actual: $actual)"
    FAILURES=$((FAILURES + 1))
  fi
}

echo ""
echo "--- Acceptance Criteria Checks (T10 / OPE-23 / OPE-10) ---"

# Criterion 1: Require PR review before merge
check_ge "Required approving review count >= 1" ".required_pull_request_reviews.required_approving_review_count" 1
check_rule "Dismiss stale reviews on new commits" ".required_pull_request_reviews.dismiss_stale_reviews" "true"

# Criterion 2: Disallow direct pushes / Enforce admins
check_rule "Enforce protection for administrators" ".enforce_admins.enabled" "true"
check_rule "Disallow force pushes" ".allow_force_pushes.enabled" "false"
check_rule "Disallow branch deletion" ".allow_deletions.enabled" "false"

# Status checks
check_rule "Strict status checks (branch up to date)" ".required_status_checks.strict" "true"

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
  echo "[✓] ALL ACCEPTANCE CRITERIA VERIFIED via GitHub API readback."
  exit 0
else
  echo "[-] VERIFICATION FAILED: $FAILURES check(s) did not meet required criteria."
  exit 1
fi
