#!/usr/bin/env bash
# scripts/test-pr-workflow.sh
# Verifies the protected-branch PR workflow on a throwaway test branch.
#
# Uses the GitHub REST API (Contents + Pulls) rather than `git push`, so it
# runs under the container's signed-push guard. It proves:
#   1. A direct commit to `main` is rejected server-side.
#   2. A test branch + PR can be created.
#   3. The PR cannot be merged without the required review.
#   4. The test branch and PR are cleaned up afterwards.
#
# Requirements: gh, jq. Egress: api.github.com. Usage:
#   scripts/test-pr-workflow.sh [OWNER/REPO]

set -euo pipefail

REPO="${1:-}"
if [ -z "$REPO" ]; then
  REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || echo qveys/OpenCodeMobile)"
fi

TEST_PATH=".branch-protection-test.txt"
TEST_BRANCH="test/pr-protection-check-$(date +%s)"
FAILURES=0
PR_URL=""

cleanup() {
  if [ -n "$PR_URL" ]; then
    gh pr close "$PR_URL" --delete-branch >/dev/null 2>&1 || true
  fi
  gh api -X DELETE "repos/$REPO/git/refs/heads/$TEST_BRANCH" >/dev/null 2>&1 || true
}
trap cleanup EXIT

pass() { echo "[✓] PASS: $1"; }
fail() { echo "[-] FAIL: $1"; FAILURES=$((FAILURES + 1)); }

echo "=========================================================="
echo "Testing PR Workflow and Branch Protection for: $REPO"
echo "Test branch: $TEST_BRANCH"
echo "=========================================================="

echo ""
echo "=== Step 1: Direct commit to main must be rejected ==="
set +e
DIRECT_OUT=$(printf 'direct-write-should-fail\n' | base64 -w0 | \
  xargs -I{} gh api -X PUT "repos/$REPO/contents/$TEST_PATH" \
    -f message="🧪 test: direct commit to main must be rejected" \
    -f content={} -f branch=main 2>&1)
DIRECT_RC=$?
set -e
if [ "$DIRECT_RC" -ne 0 ]; then
  pass "Direct commit to main rejected server-side"
  echo "     API response: $(echo "$DIRECT_OUT" | tr '\n' ' ' | cut -c1-160)"
else
  fail "Direct commit to main was NOT rejected"
  echo "     API response: $DIRECT_OUT"
fi

if [ "$FAILURES" -ne 0 ]; then
  echo ""
  echo "[-] Aborting before branch/PR creation: main may be unprotected."
  exit 1
fi

echo ""
echo "=== Step 2: Create test branch off main ==="
MAIN_SHA=$(gh api "repos/$REPO/git/ref/heads/main" --jq .object.sha)
gh api -X POST "repos/$REPO/git/refs" \
  -f ref="refs/heads/$TEST_BRANCH" -f sha="$MAIN_SHA" >/dev/null
echo "     Branch $TEST_BRANCH created at $MAIN_SHA"

echo ""
echo "=== Step 3: Commit a test file on the branch (API-signed) ==="
COMMIT_JSON=$(printf 'branch-protection test %s\n' "$TEST_BRANCH" | base64 -w0 | \
  xargs -I{} gh api -X PUT "repos/$REPO/contents/$TEST_PATH" \
    -f message="🧪 test: branch protection pr workflow check" \
    -f content={} -f branch="$TEST_BRANCH")
echo "     commit: $(echo "$COMMIT_JSON" | jq -r '.commit.sha') verified=$(echo "$COMMIT_JSON" | jq -r '.commit.verification.verified')"

echo ""
echo "=== Step 4: Open a pull request ==="
PR_URL=$(gh pr create --repo "$REPO" --base main --head "$TEST_BRANCH" \
  --title "test: automated branch protection verification" \
  --body "Automated test PR to verify branch protection and PR review requirements. Closed and deleted automatically.")
echo "     PR: $PR_URL"

echo ""
echo "=== Step 5: Merge must be blocked without required review ==="
set +e
MERGE_OUT=$(gh pr merge "$PR_URL" --merge 2>&1)
MERGE_RC=$?
set -e
PR_STATE=$(gh pr view "$PR_URL" --json mergeStateStatus,reviewDecision --jq '"\(.mergeStateStatus)/\(.reviewDecision)"')
if [ "$MERGE_RC" -ne 0 ]; then
  pass "Merge blocked without required review (mergeState=$PR_STATE)"
else
  fail "PR merged without required review (mergeState=$PR_STATE)"
fi

echo ""
if [ "$FAILURES" -eq 0 ]; then
  echo "[✓] PR WORKFLOW VERIFIED: direct commit rejected and merge blocked pending review."
  exit 0
else
  echo "[-] PR WORKFLOW VERIFICATION FAILED: $FAILURES check(s) failed."
  exit 1
fi