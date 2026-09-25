#!/usr/bin/env bash
# scripts/setup-branch-protection.sh
# Applies branch protection on `main` branch via GitHub REST API.
# Also applies the Actions fork-PR approval and workflow-permission settings
# required by T10 / OPE-23.
# Requirements: gh, jq
#
# Required status checks are empty by default because no CI workflows exist
# yet. Once CI publishes check runs (lint/test/build), re-run with:
#   REQUIRED_STATUS_CHECKS="lint,test,build" scripts/setup-branch-protection.sh
# Otherwise PRs stay blocked waiting for checks that never report.

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

REQUIRED_STATUS_CHECKS="${REQUIRED_STATUS_CHECKS:-}"

echo "Setting up branch protection for repository: $REPO on branch: main"

# Build the required-status-check contexts array from a comma-separated list.
CONTEXTS_JSON=$(printf '%s' "$REQUIRED_STATUS_CHECKS" \
  | tr ',' '\n' \
  | sed '/^[[:space:]]*$/d' \
  | jq -R . \
  | jq -s .)

echo "Required status check contexts: $CONTEXTS_JSON"

# 1. Configure Branch Protection Rules
PROTECTION_PAYLOAD=$(jq -n --argjson contexts "$CONTEXTS_JSON" '{
  required_status_checks: {
    strict: true,
    contexts: $contexts
  },
  enforce_admins: true,
  required_pull_request_reviews: {
    dismiss_stale_reviews: true,
    require_code_owner_reviews: false,
    required_approving_review_count: 1,
    require_last_push_approval: true
  },
  restrictions: null,
  required_linear_history: false,
  allow_force_pushes: false,
  allow_deletions: false,
  block_creations: false,
  required_conversation_resolution: true,
  lock_branch: false,
  allow_fork_syncing: false
}')

echo "Applying protection configuration via GitHub API..."
echo "$PROTECTION_PAYLOAD" | gh api --method PUT "repos/$REPO/branches/main/protection" --input -

# 2. Require Signed Commits
echo "Enabling required commit signatures..."
gh api --method POST "repos/$REPO/branches/main/protection/required_signatures" \
  -H "Accept: application/vnd.github.zzzax-preview+json" 2>/dev/null || \
gh api --method POST "repos/$REPO/branches/main/protection/required_signatures" || true

# 3. Require approval for first-time contributors on fork pull request workflows (T10)
echo "Setting fork pull request workflow approval policy..."
gh api --method PUT "repos/$REPO/actions/permissions/fork-pr-contributor-approval" \
  -f approval_policy=first_time_contributors >/dev/null

# 4. Restrict default GITHUB_TOKEN workflow permissions to read-only (T10)
echo "Restricting default workflow permissions to read..."
gh api --method PUT "repos/$REPO/actions/permissions/workflow" \
  -f default_workflow_permissions=read \
  -F can_approve_pull_request_reviews=false >/dev/null

echo "Branch protection setup complete for $REPO:main."