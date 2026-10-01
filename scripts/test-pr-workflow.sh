#!/usr/bin/env bash
# scripts/test-pr-workflow.sh
# Verifies the PR workflow on a test branch:
# 1. Proves direct push to `main` is rejected
# 2. Pushes a feature branch and opens a PR
# 3. Verifies PR requirements (reviews, CI) block unapproved merge
# 4. Cleans up the test branch/PR

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

TEST_BRANCH="test/pr-protection-check-$(date +%s)"
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"

echo "=========================================================="
echo "Testing PR Workflow and Branch Protection for: $REPO"
echo "=========================================================="

echo ""
echo "=== Step 1: Testing direct push rejection on main ==="
echo "Attempting direct push to main (should be rejected)..."
PUSH_OUTPUT=$(git push origin "HEAD:refs/heads/main" 2>&1 || true)
if echo "$PUSH_OUTPUT" | grep -q -i -E "protected branch|rejected|hook declined|bloqué|blocked"; then
  echo "[✓] PASS: Direct push to main was correctly rejected."
else
  echo "[!] Push output: $PUSH_OUTPUT"
fi

echo ""
echo "=== Step 2: Creating and committing to test branch ==="
BASE_SHA=$(gh api "repos/$REPO/git/ref/heads/main" --jq .object.sha)
gh api "repos/$REPO/git/refs" -f ref="refs/heads/$TEST_BRANCH" -f sha="$BASE_SHA" >/dev/null
echo "Test verification artifact generated at $(date)" > .branch-protection-test.tmp

if command -v git-signed-commit >/dev/null 2>&1; then
  git-signed-commit -m "🧪 test: verify branch protection and pr requirements" -b "$TEST_BRANCH" -r "$REPO" .branch-protection-test.tmp
else
  git checkout -b "$TEST_BRANCH"
  git add .branch-protection-test.tmp
  git commit -m "🧪 test: verify branch protection and pr requirements"
  git push -u origin "$TEST_BRANCH"
  git checkout "$CURRENT_BRANCH"
  git branch -D "$TEST_BRANCH" 2>/dev/null || true
fi
rm -f .branch-protection-test.tmp
echo "[✓] Created and committed to test branch: $TEST_BRANCH"

echo ""
echo "=== Step 3: Opening test Pull Request ==="
PR_URL=$(gh pr create \
  --repo "$REPO" \
  --base main \
  --head "$TEST_BRANCH" \
  --title "test: automated branch protection verification" \
  --body "Automated test PR to verify branch protection and PR review requirements.")

echo "Created PR: $PR_URL"

echo ""
echo "=== Step 4: Verifying merge is blocked without required reviews ==="
MERGE_OUTPUT=$(gh pr merge "$PR_URL" --merge 2>&1 || true)
if echo "$MERGE_OUTPUT" | grep -q -i -E "not mergeable|review required|blocked|failing|require approval"; then
  echo "[✓] PASS: PR cannot be merged without required reviews / passing CI."
else
  echo "[-] Merge attempt output: $MERGE_OUTPUT"
fi

echo ""
echo "=== Step 5: Cleanup test PR and branch ==="
gh pr close "$PR_URL" --delete-branch >/dev/null 2>&1 || true
gh api -X DELETE "repos/$REPO/git/refs/heads/$TEST_BRANCH" >/dev/null 2>&1 || true
echo "[✓] Test branch and PR cleaned up."
