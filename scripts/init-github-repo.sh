#!/usr/bin/env bash
# scripts/init-github-repo.sh
# Initializes and links the GitHub repository once created on GitHub, pushes main, and enforces branch protection.
# Usage: ./scripts/init-github-repo.sh [OWNER/REPO]

set -euo pipefail

REPO="${1:-qveys/OpenCodeMobile}"
REMOTE_URL="https://github.com/${REPO}.git"

echo "=== OpenCode Mobile GitHub Initialization ==="
echo "Target repository: $REPO"

# 1. Check if git remote exists; if not, configure it
if git remote get-url origin >/dev/null 2>&1; then
  CURRENT_URL="$(git remote get-url origin)"
  if [ "$CURRENT_URL" != "$REMOTE_URL" ]; then
    echo "Updating remote origin from $CURRENT_URL to $REMOTE_URL..."
    git remote set-url origin "$REMOTE_URL"
  fi
else
  echo "Setting remote origin to $REMOTE_URL..."
  git remote add origin "$REMOTE_URL"
fi

# 2. Check if the remote repository exists and is accessible via GitHub CLI / API
echo "Checking remote repository accessibility on GitHub..."
if ! gh api "repos/$REPO" >/dev/null 2>&1; then
  echo "[-] ERROR: Repository $REPO was not found or is not accessible."
  echo "[-] Reminders:"
  echo "    1. The repository must be created on GitHub (https://github.com/new) under owner: ${REPO%%/*}"
  echo "    2. The GitHub App (my-paperclip-company) must be granted access at:"
  echo "       https://github.com/settings/installations/137591757"
  exit 1
fi
echo "[✓] Remote repository $REPO is reachable and authenticated."

# 3. Push main branch
echo "Pushing main branch to origin..."
git push -u origin main

# 4. Apply Branch Protection (if script exists)
if [ -f "scripts/setup-branch-protection.sh" ]; then
  echo "Setting up branch protection on main..."
  bash scripts/setup-branch-protection.sh "$REPO"
fi

# 5. Verify Branch Protection (if script exists)
if [ -f "scripts/verify-branch-protection.sh" ]; then
  echo "Verifying branch protection..."
  bash scripts/verify-branch-protection.sh "$REPO"
fi

echo "=== Initialization Completed Successfully ==="
