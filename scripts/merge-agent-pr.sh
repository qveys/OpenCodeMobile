#!/usr/bin/env bash
# scripts/merge-agent-pr.sh
# Merge an agent-authored PR, but only after the branch review gate is satisfied.
#
# The shared GitHub App (my-paperclip-company) authors every agent PR and cannot
# self-approve, so this helper deliberately fails closed unless GitHub reports
# reviewDecision=APPROVED and mergeStateStatus=CLEAN. It never uses --admin and
# never bypasses branch protection.
#
# Usage:
#   merge-agent-pr.sh <pr-number> [--method squash|merge|rebase] [--repo owner/repo] [--arm]
#
#   --arm   Run `gh pr merge --auto` instead of merging immediately. Requires the
#           repository setting "Allow auto-merge" (owner/admin action).
#
# Exit codes: 0 merged/armed, 1 gate not satisfied or merge failed, 2 usage error.
set -euo pipefail

REPO=""
METHOD="squash"
ARM="false"
PR=""

while [ $# -gt 0 ]; do
  case "$1" in
    --method) METHOD="${2:-}"; shift 2 ;;
    --repo)   REPO="${2:-}"; shift 2 ;;
    --arm)    ARM="true"; shift ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    -*)
      echo "[-] Unknown option: $1" >&2
      exit 2 ;;
    *)
      if [ -z "$PR" ]; then PR="$1"; else echo "[-] Unexpected argument: $1" >&2; exit 2; fi
      shift ;;
  esac
done

if [ -z "$PR" ]; then
  echo "[-] Usage: merge-agent-pr.sh <pr-number> [--method squash|merge|rebase] [--repo owner/repo] [--arm]" >&2
  exit 2
fi

case "$METHOD" in
  squash|merge|rebase) ;;
  *) echo "[-] Invalid method: $METHOD (expected squash|merge|rebase)" >&2; exit 2 ;;
esac

if [ -z "$REPO" ]; then
  REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)"
fi
if [ -z "$REPO" ]; then
  echo "[-] Could not determine repository; pass --repo owner/repo" >&2
  exit 2
fi

echo "Checking review gate for $REPO PR #$PR ..."
STATE_JSON="$(gh pr view "$PR" --repo "$REPO" \
  --json number,state,isDraft,reviewDecision,mergeStateStatus,author,url 2>/dev/null)" || {
  echo "[-] Could not read PR #$PR from $REPO" >&2
  exit 1
}

read -r PR_STATE IS_DRAFT REVIEW_DECISION MERGE_STATE PR_AUTHOR PR_URL < <(
  printf '%s' "$STATE_JSON" | jq -r '[.state,.isDraft,.reviewDecision,.mergeStateStatus,.author.login,.url]|@tsv' 2>/dev/null
) || { echo "[-] Malformed PR JSON" >&2; exit 1; }

echo "  state=$PR_STATE draft=$IS_DRAFT reviewDecision=$REVIEW_DECISION mergeStateStatus=$MERGE_STATE author=$PR_AUTHOR"

if [ "$PR_STATE" != "OPEN" ]; then
  echo "[-] FAIL: PR #$PR is not OPEN (state=$PR_STATE)." >&2
  exit 1
fi
if [ "$IS_DRAFT" = "true" ]; then
  echo "[-] FAIL: PR #$PR is a draft." >&2
  exit 1
fi
if [ "$REVIEW_DECISION" != "APPROVED" ]; then
  echo "[-] FAIL: review gate not satisfied (reviewDecision=$REVIEW_DECISION; need APPROVED)." >&2
  echo "    The shared App cannot self-approve. Ask the owner/reviewer to approve this PR," >&2
  echo "    or adopt the App-bypass or dedicated-reviewer merge path first." >&2
  exit 1
fi
if [ "$MERGE_STATE" != "CLEAN" ]; then
  echo "[-] FAIL: mergeStateStatus=$MERGE_STATE (need CLEAN: no conflicts, CI green, branch up to date)." >&2
  exit 1
fi

if [ "$ARM" = "true" ]; then
  echo "Arming auto-merge for PR #$PR ($METHOD)..."
  gh pr merge "$PR" --repo "$REPO" --"$METHOD" --auto --delete-branch
  echo "[✓] Auto-merge armed for $PR_URL"
else
  echo "Merging PR #$PR ($METHOD)..."
  gh pr merge "$PR" --repo "$REPO" --"$METHOD" --delete-branch
  echo "[✓] Merged $PR_URL"
fi