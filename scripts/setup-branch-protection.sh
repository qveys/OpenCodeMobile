#!/usr/bin/env bash
# scripts/setup-branch-protection.sh
# Applies branch protection on `main` branch via GitHub REST API.
# Also applies the Actions fork-PR approval and workflow-permission controls
# (T10 / OPE-10 criterion 4). Every step fails closed: a failed call or a
# readback that is missing/false aborts nonzero instead of reporting success.
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
# Required check: "T4 static scan" is the job name in
# `.github/workflows/security-logging.yml` (OPE-56 / threat T4). Only checks
# produced by a workflow that actually runs on every PR may be required here;
# requiring a context no workflow emits would leave every PR "Expected" forever.
# The lint/test/build contexts (OPE-14/OPE-15/OPE-16/OPE-17) are added to this
# list once those workflows exist on `main`.
PROTECTION_PAYLOAD=$(cat <<'EOF'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "T4 static scan"
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

# 3. Require approval for first-time contributors on fork pull request workflows (T10 / OPE-10 criterion 4)
# Fail closed: the setting must be applied AND read back before claiming success.
echo "Setting fork pull request workflow approval policy..."
if ! gh api --method PUT "repos/$REPO/actions/permissions/fork-pr-contributor-approval" \
     -f approval_policy=first_time_contributors >/dev/null; then
  echo "[-] FAILED: could not set fork-PR contributor approval policy for $REPO" >&2
  exit 1
fi

FORK_RESPONSE="$(gh api "repos/$REPO/actions/permissions/fork-pr-contributor-approval")" || {
  echo "[-] FAILED: could not read back fork-PR contributor approval policy for $REPO" >&2
  exit 1
}
FORK_POLICY="$(printf '%s' "$FORK_RESPONSE" | jq -r '.approval_policy // empty')" || {
  echo "[-] FAILED: fork-PR approval readback is not valid JSON for $REPO" >&2
  exit 1
}
case "$FORK_POLICY" in
  first_time_contributors | first_time_contributors_new_to_github | all_external_contributors)
    echo "[+] Fork-PR contributor approval policy verified (approval_policy=$FORK_POLICY)."
    ;;
  *)
    echo "[-] FAILED: fork-PR approval readback approval_policy=${FORK_POLICY:-<missing>} (expected first_time_contributors)" >&2
    exit 1
    ;;
esac

# 4. Restrict default GITHUB_TOKEN workflow permissions to read-only (T10 / OPE-10)
# Fail closed: the setting must be applied AND read back before claiming success.
echo "Restricting default workflow permissions to read..."
if ! gh api --method PUT "repos/$REPO/actions/permissions/workflow" \
     -f default_workflow_permissions=read \
     -F can_approve_pull_request_reviews=false >/dev/null; then
  echo "[-] FAILED: could not restrict Actions workflow permissions for $REPO" >&2
  exit 1
fi

WORKFLOW_RESPONSE="$(gh api "repos/$REPO/actions/permissions/workflow")" || {
  echo "[-] FAILED: could not read back Actions workflow permissions for $REPO" >&2
  exit 1
}
WORKFLOW_DEFAULT="$(printf '%s' "$WORKFLOW_RESPONSE" | jq -r '.default_workflow_permissions // empty')" || {
  echo "[-] FAILED: Actions workflow-permissions readback is not valid JSON for $REPO" >&2
  exit 1
}
# Note: a JSON `false` must be compared as the string "false"; `// empty` would
# erase it, so read the raw value (missing key yields the string "null").
WORKFLOW_APPROVE="$(printf '%s' "$WORKFLOW_RESPONSE" | jq -r '.can_approve_pull_request_reviews')" || {
  echo "[-] FAILED: Actions workflow-permissions readback is not valid JSON for $REPO" >&2
  exit 1
}
if [ "$WORKFLOW_DEFAULT" != "read" ] || [ "$WORKFLOW_APPROVE" != "false" ]; then
  echo "[-] FAILED: workflow-permissions readback default=${WORKFLOW_DEFAULT:-<missing>} can_approve=${WORKFLOW_APPROVE:-<missing>} (expected read/false)" >&2
  exit 1
fi
echo "[+] Actions default workflow permissions restricted to read and cannot approve PR reviews."

echo "Branch protection setup complete for $REPO:main."
