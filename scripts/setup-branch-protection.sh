#!/usr/bin/env bash
# scripts/setup-branch-protection.sh
# Applies branch protection on `main` branch via GitHub REST API.
# Requirements: gh, jq

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

echo "Setting up branch protection for repository: $REPO on branch: main"

# 1. Configure Branch Protection Rules
PROTECTION_PAYLOAD=$(cat <<'EOF'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "lint",
      "test",
      "build"
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false,
    "required_approving_review_count": 1,
    "require_last_push_approval": true
  },
  "restrictions": null,
  "required_linear_history": false,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "block_creations": false,
  "required_conversation_resolution": true,
  "lock_branch": false,
  "allow_fork_syncing": true
}
EOF
)

echo "Applying protection configuration via GitHub API..."
echo "$PROTECTION_PAYLOAD" | gh api --method PUT "repos/$REPO/branches/main/protection" --input -

# 2. Require Signed Commits
echo "Enabling required commit signatures..."
gh api --method POST "repos/$REPO/branches/main/protection/required_signatures" \
  -H "Accept: application/vnd.github.zzzax-preview+json" 2>/dev/null || \
gh api --method POST "repos/$REPO/branches/main/protection/required_signatures" || true

echo "Branch protection setup complete for $REPO:main."
