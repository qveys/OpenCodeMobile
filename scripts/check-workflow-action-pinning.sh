#!/usr/bin/env bash
# scripts/check-workflow-action-pinning.sh
#
# SEC-04 — CI gate: fail when a workflow step pins a GitHub Action to a floating
# ref (a tag or branch such as `@v4`, `@main`, `@master`) instead of a full
# 40-character commit SHA.
#
# Why a gate and not a one-time fix: a floating tag is a mutable pointer. If
# the upstream tag is moved — maliciously or through a compromised maintainer
# account — every pull request in this repository executes unreviewed code with
# the repository's own credentials. This was threat T10 in
# docs/THREAT-MODEL.md, and docs/CI-CD-SECURITY.md §7 requires SHA pinning for
# every `uses:` step. Fixing one file is not enough: the next workflow added
# reopens the hole. This gate makes the control structural instead of a
# point-in-time edit.
#
# Deliberately independent of the Gradle build (no JDK required) so it can run
# on every pull request.
#
# Local `uses: ./path` steps reference this repository and are exempt: there is
# no upstream tag to move.
#
# Exit 0 when every remote action is SHA-pinned, exit 1 otherwise.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

WORKFLOW_DIR=".github/workflows"

if [ ! -d "$WORKFLOW_DIR" ]; then
  printf '::error::No %s directory; this gate is no longer enforcing anything.\n' "$WORKFLOW_DIR"
  exit 1
fi

# 40 hex characters. A trailing `# vX.Y.Z` readability comment is allowed and is
# stripped before this test, so the value here is bare.
SHA_PINNED_RE='^[[:alnum:]_.-]+/[[:alnum:]_.-]+@[0-9a-f]{40}$'
DOCKER_ACTION_RE='^docker://'

fail=0
scanned=0
checked=0

# Report a floating `uses:` as a GitHub Actions annotation so it surfaces on the
# pull request diff, matching the house style of scripts/check-no-secret-logging.sh.
report_floating() {
  local file="$1" line="$2" value="$3"
  printf '::error file=%s,line=%s::action is not pinned to a full commit SHA (SEC-04): %s\n' \
    "$file" "$line" "$value"
  printf '  %s:%s: %s\n' "$file" "$line" "$value"
  fail=1
}

printf 'Scanning %s for GitHub Actions that are not SHA-pinned...\n' "$WORKFLOW_DIR"

while IFS= read -r workflow; do
  scanned=$((scanned + 1))
  line_number=0
  while IFS= read -r line; do
    line_number=$((line_number + 1))
    case "$line" in
      *uses:*) ;;
      *) continue ;;
    esac

    # Strip the YAML key and any trailing comment, then the surrounding quotes.
    value="${line#*uses:}"
    value="${value%%#*}"
    # Trim leading/trailing whitespace.
    value="$(printf '%s' "$value" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    # Drop surrounding quotes if present.
    value="${value%\"}"; value="${value#\"}"
    value="${value%\'}"; value="${value#\'}"

    [ -z "$value" ] && continue

    # A local step (`./path`) stays inside this repository: nothing to pin.
    # Count it as a decision this gate made, not as a gap.
    case "$value" in
      ./*|../*) checked=$((checked + 1)); continue ;;
    esac

    # Container actions are pinned by digest, not by git SHA, and are verified
    # differently; accept them and leave the check to the owner. This
    # repository declares none.
    if [[ "$value" =~ $DOCKER_ACTION_RE ]]; then
      checked=$((checked + 1))
      continue
    fi

    if [[ "$value" =~ $SHA_PINNED_RE ]]; then
      checked=$((checked + 1))
    else
      report_floating "$workflow" "$line_number" "$value"
    fi
  done < "$workflow"
done < <(find "$WORKFLOW_DIR" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort)

# A gate that silently stops matching anything is worse than no gate: it would
# report success while enforcing nothing. Fail closed if the scan found no
# pinned action to reason about.
if [ "$checked" -eq 0 ]; then
  printf '::error::No SHA-pinned action found across %d workflow file(s); the pinning rule is no longer matching anything.\n' "$scanned"
  exit 1
fi

if [ "$fail" -ne 0 ]; then
  printf '\nFAIL: %d workflow file(s) scanned; at least one `uses:` is not pinned to a full commit SHA (SEC-04).\n' "$scanned"
  printf 'Pin it to the exact 40-character commit SHA and add a `# vX.Y.Z` comment, e.g.\n'
  printf '  - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4.4.0\n'
  printf 'Find the SHA with: gh api repos/OWNER/REPO/commits/TAG --jq .sha\n'
  exit 1
fi

printf 'OK: scanned %d workflow file(s); %d remote action reference(s) pinned to a full commit SHA (SEC-04).\n' "$scanned" "$checked"
exit 0
