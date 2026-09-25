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
# Fail closed: a signing-enforcement failure must abort nonzero, never report success.
echo "Enabling required commit signatures..."
if ! gh api --method POST "repos/$REPO/branches/main/protection/required_signatures" \
     -H "Accept: application/vnd.github+json" >/dev/null; then
  echo "[-] FAILED: could not enable required commit signatures for $REPO:main" >&2
  exit 1
fi

# Read back the enabled state and require enabled=true before claiming success.
SIGNATURES_RESPONSE="$(gh api "repos/$REPO/branches/main/protection/required_signatures")" || {
  echo "[-] FAILED: could not read back required-signatures state for $REPO:main" >&2
  exit 1
}
SIGNATURES_ENABLED="$(printf '%s' "$SIGNATURES_RESPONSE" | jq -r '.enabled')" || {
  echo "[-] FAILED: required-signatures readback is not valid JSON for $REPO:main" >&2
  exit 1
}
if [ "$SIGNATURES_ENABLED" != "true" ]; then
  echo "[-] FAILED: required signatures readback enabled=$SIGNATURES_ENABLED (expected true)" >&2
  exit 1
fi
echo "[+] Required commit signatures enabled and verified (enabled=true)."

echo "Branch protection setup complete for $REPO:main."
