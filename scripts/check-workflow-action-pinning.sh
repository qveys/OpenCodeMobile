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
# Two mutable-pointer shapes are rejected, not one:
#   * a repository or sub-path action on a tag/branch (`@v4`, `@main`) — pinned
#     form is `@<40-hex-commit-sha>`;
#   * a container action on a tag (`docker://alpine:latest`) — a container is
#     pinned by image digest, so the pinned form is `docker://image@sha256:<64-hex>`.
# Both are the same adversary: whoever can move the pointer controls the bytes
# every pull request executes.
#
# Exit 0 when every remote action is pinned to an immutable value, exit 1 otherwise.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

WORKFLOW_DIR=".github/workflows"

if [ ! -d "$WORKFLOW_DIR" ]; then
  printf '::error::No %s directory; this gate is no longer enforcing anything.\n' "$WORKFLOW_DIR"
  exit 1
fi

# One path segment: an owner, a repository, or one directory of a sub-path
# action. `[[:alnum:]_.-]` is alphanumerics plus `_`, `.` and `-`; the trailing
# `-` is literal, not a range.
SEGMENT='[[:alnum:]_.-]'
# 40 hex characters, anchored on both sides so a 39- or 41-character value can
# never pass by prefix match. A trailing `# vX.Y.Z` readability comment is
# allowed and is stripped before this test, so the value here is bare.
#
# A sub-path action (`aws-actions/amazon-ecr/amazon-ecr-login`, or a reusable
# workflow `owner/repo/.github/workflows/build.yml`) is pinned exactly like a
# repository-root action, so it is matched here too. The previous pattern
# accepted only `owner/repo@sha` and rejected every correctly pinned sub-path
# action — a rule the repository cannot satisfy without weakening it.
SHA_PINNED_RE="^${SEGMENT}+/${SEGMENT}+(/${SEGMENT}+)*@[0-9a-f]{40}\$"
# Container actions are pinned by image digest, never by a git SHA. A tag is
# the same mutable pointer SEC-04 exists to forbid: whoever controls the
# registry account republishes `alpine:latest` and the next pull request runs
# their bytes. So `docker://` is accepted only with an `@sha256:` digest.
DOCKER_ACTION_RE='^docker://'
DOCKER_DIGEST_RE='^docker://[^@[:space:]]+@sha256:[0-9a-f]{64}$'

fail=0
scanned=0
# References this gate ruled on: SHA-pinned, digest-pinned, or exempt local.
checked=0
# The subset actually pinned to an immutable value. A local `./path` step is a
# real reference with nothing to pin, so counting it toward the fail-closed
# guard below would let a tree whose remote actions had all been deleted pass
# while the pinning rule stopped matching anything.
sha_pinned=0
container_pinned=0

# Report a floating `uses:` as a GitHub Actions annotation so it surfaces on the
# pull request diff, matching the house style of scripts/check-no-secret-logging.sh.
report_floating() {
  local file="$1" line="$2" value="$3"
  printf '::error file=%s,line=%s::action is not pinned to a full commit SHA (SEC-04): %s\n' \
    "$file" "$line" "$value"
  printf '  %s:%s: %s\n' "$file" "$line" "$value"
  fail=1
}

# Same annotation channel, different defect: a container action referenced by a
# mutable tag rather than by digest.
report_unpinned_container() {
  local file="$1" line="$2" value="$3"
  printf '::error file=%s,line=%s::container action is not pinned to an image digest (SEC-04): %s\n' \
    "$file" "$line" "$value"
  printf '  %s:%s: %s\n' "$file" "$line" "$value"
  printf '    A tag such as `:latest` is a mutable pointer. Pin the digest, e.g.\n'
  printf '      - uses: docker://alpine@sha256:<64-hex-digest>\n'
  printf '    Read it with: docker buildx imagetools inspect IMAGE:TAG --format "{{json .Manifest.Digest}}"\n'
  fail=1
}

printf 'Scanning %s for GitHub Actions not pinned to an immutable value...\n' "$WORKFLOW_DIR"

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

    # Container actions are pinned by digest. An unpinned one is a finding, not
    # an owner conversation: the registry account that can move `alpine:latest`
    # is the same supply-chain adversary this gate exists to stop.
    if [[ "$value" =~ $DOCKER_ACTION_RE ]]; then
      checked=$((checked + 1))
      if [[ "$value" =~ $DOCKER_DIGEST_RE ]]; then
        container_pinned=$((container_pinned + 1))
      else
        report_unpinned_container "$workflow" "$line_number" "$value"
      fi
      continue
    fi

    if [[ "$value" =~ $SHA_PINNED_RE ]]; then
      checked=$((checked + 1))
      sha_pinned=$((sha_pinned + 1))
    else
      report_floating "$workflow" "$line_number" "$value"
    fi
  done < "$workflow"
done < <(find "$WORKFLOW_DIR" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort)

pinned=$((sha_pinned + container_pinned))

# A gate that silently stops matching anything is worse than no gate: it would
# report success while enforcing nothing. Fail closed unless at least one
# reference was matched against a pinning rule. An exempt local `./path` step
# does not count: it is the one reference shape that can never be pinned, so
# counting it let a tree from which every remote action had been deleted
# satisfy this guard while the SHA rule matched nothing. A digest-pinned
# container action does count — it is genuinely pinned.
if [ "$pinned" -eq 0 ]; then
  printf '::error::No pinned action found across %d workflow file(s) (%d reference(s) seen); the pinning rule is no longer matching anything.\n' "$scanned" "$checked"
  exit 1
fi

if [ "$fail" -ne 0 ]; then
  printf '\nFAIL: %d workflow file(s) scanned; %d reference(s) were not pinned to an immutable value (SEC-04).\n' "$scanned" "$checked"
  printf 'Pin a repository or sub-path action to the exact 40-character commit SHA and add a `# vX.Y.Z` comment, e.g.\n'
  printf '  - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4.4.0\n'
  printf 'Find the SHA with: gh api repos/OWNER/REPO/commits/TAG --jq .sha\n'
  printf 'Pin a container action to an image digest (`docker://image@sha256:<64-hex>`), not to a tag.\n'
  exit 1
fi

printf 'OK: scanned %d workflow file(s); %d reference(s) pinned to an immutable value — %d to a full commit SHA, %d container digest(s) (SEC-04).\n' \
  "$scanned" "$pinned" "$sha_pinned" "$container_pinned"
exit 0
