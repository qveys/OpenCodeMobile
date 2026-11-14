#!/usr/bin/env bash
# scripts/check-commit-message.sh
#
# Validates commit messages against the emoji + Conventional Commits format
# enforced by qveys/my-housekeeper (handlers `commit-message-check` and
# `normalize-title`). Same rules, shell-only, no Node dependency so it can
# run from a pre-commit hook or a GitHub Actions runner with just bash + perl.
#
# Spec reference: qveys/my-housekeeper
#   - commitlint.config.mjs             (header pattern + rules)
#   - src/handlers/commit-lint.ts       (checkNoTrailers, checkConsecutiveEmojis)
#   - src/handlers/normalize-title/normalize.ts (CC_TYPES, emojiPoolByType)
#
# Usage:
#   scripts/check-commit-message.sh --self-test
#     Runs a built-in test battery. Exits 0 on success, 1 on any failure.
#
#   scripts/check-commit-message.sh --file <path-to-commit-msg-file>
#     Validates a single commit message read from a file (pre-commit-msg hook
#     or `git log -1 --format=%B` style). Exits 0 on valid, 1 on invalid.
#
#   scripts/check-commit-message.sh --range <base>..<head>
#     Validates every commit message produced by the merge of `base..head`
#     (used by CI). Exits 0 on valid, 1 on any invalid commit. Skips
#     merge/revert/fixup!/squash! commits the same way `@commitlint/is-ignored`
#     does for `commitlint --edit`, so GitHub-side squash merges don't fail.
#
#   scripts/check-commit-message.sh --pr-title "<title>"
#     Validates a PR/issue title against the `normalize-title` rule. Exits 0
#     on valid, 1 on invalid.
#
# Output: each invalid line is reported as "<header>: <reason>". The script
# returns the first failure encountered on `--file` / `--pr-title`, or all
# failures on `--range`. Returns exit code 1 on any violation, exit code 0 on
# full success.

set -u

# --- Configuration (mirrors my-housekeeper commitlint + normalize-title) -----

# Same enum as my-housekeeper commitlint.config.mjs and
# src/handlers/normalize-title/normalize.ts.
CC_TYPES_REGEX='(feat|fix|docs|style|refactor|perf|test|build|ci|chore)'

# Header pattern from my-housekeeper (commitlint.config.mjs + commit-lint.ts):
#   <emoji> <type>[(<scope>)][!]: <subject>
#   \p{Extended_Pictographic}\x{FE0F}? covers all Unicode emoji including
#   variation-selector variants (e.g. ⚡ vs ⚡️).
HEADER_PATTERN='^(\p{Extended_Pictographic}\x{FE0F}?)\s+(\w+)(\([^)]+\))?(!)?: (.+)$'

# header-max-length from my-housekeeper commitlint (raise to 100 vs the older
# 72-char rule to accommodate longer scopes; my-housekeeper rationale: see
# commitlint.config.mjs:header-max-length).
HEADER_MAX_LENGTH=100

# PR/issue title rule from normalize.ts: `emoji type[(scope)][!]: description`,
# description up to 256 chars, no trailing `(#N)` for PRs (GitHub adds it
# itself on squash-merge).
PR_TITLE_PATTERN="$HEADER_PATTERN"
PR_TITLE_MAX_LENGTH=256

# commitlint's isIgnored() skips these prefixes; we mirror it so the same
# commit doesn't fail on this script and pass on the bot (or vice versa).
IGNORED_HEADER_REGEX='^(Merge |Revert |fixup! |squash! |amend! )'

# Trailers we reject (my-housekeeper forbids every trailer; use TYPE!
# for breaking changes instead of "BREAKING CHANGE:" prose).
FORBIDDEN_TRAILER_KEY_REGEX='^(Co-authored-by|Signed-off-by|Reported-by|Suggested-by|Reviewed-by|Acked-by|Tested-by|Cc|Link|Refs|See-also|Helped-by|Noticed-by|Recommended-by):'
FORBIDDEN_BREAKING_PROSE_REGEX='^(BREAKING[ -]CHANGE):'

# --- Helpers ---------------------------------------------------------------

failures=()

# Record a parser-level failure against the given header. Bash-level rule
# checks all funnel through here so `--self-test` can assert on the message.
record_failure() {
  local header="$1"
  local reason="$2"
  failures+=("${header}: ${reason}")
}

# `match` runs perl with `use utf8; use Encode;` so the regex
# `\p{Extended_Pictographic}` matches Unicode codepoints, not raw bytes.
# `decode_utf8(@ARGV)` converts the byte sequence perl receives from the
# shell into a Perl-internal Unicode string (the alternative — passing the
# pattern as a literal in the source with `use utf8` — wastes a sub-process
# per call on the regex compilation, so we keep the regex in @ARGV but
# decode the subject once at the top).
match() {
  local pattern="$1"
  local subject="$2"
  perl -e '
    use strict;
    use warnings;
    use utf8;
    use Encode;
    my ($pat, $str) = map { decode_utf8($_) } @ARGV;
    exit($str =~ /$pat/ ? 0 : 1);
  ' -- "$pattern" "$subject"
}

# Extract HEADER_PATTERN capture groups from $1, joined by \x1f (ASCII unit
# separator) so a `|` inside a group cannot collide. Order:
# emoji|type|scope|breaking|subject. Empty capture => empty string.
capture_header() {
  local header="$1"
  perl -e '
    use strict;
    use warnings;
    use utf8;
    use Encode;
    binmode(STDOUT, ":utf8");
    my $h = decode_utf8(shift);
    my $pat = qr/^(\p{Extended_Pictographic}\x{FE0F}?)\s+(\w+)(\([^)]+\))?(!)?: (.+)$/;
    if ($h =~ /$pat/) {
      print join("\x1f", ($1, $2, $3 // "", $4 // "", $5)), "\n";
    }
  ' -- "$header"
}

# First non-empty line of a message, with CR stripped. Uses awk (POSIX) —
# BSD sed on macOS rejects the GNU-friendly `{p;q}` group as a single
# argument, so the awk form keeps this script portable to both macOS and
# Linux runners (CI runs on Linux, contributors run on macOS).
header_of() {
  local msg="$1"
  printf '%s' "$msg" | awk 'NF { print; exit }' | tr -d '\r'
}

# Run the built-in test battery. Used by CI to enforce that the script
# behaves exactly like my-housekeeper's commitlint + commit-lint.ts.
cmd_self_test() {
  local failed=0
  local color_ok=$'\033[32m'
  local color_ko=$'\033[31m'
  local color_off=$'\033[0m'

  expect_ok() {
    local label="$1" header="$2"
    failures=()
    validate_header "$header" "$(printf '%s\n\nbody line 1\nbody line 2' "$header")"
    if [[ ${#failures[@]} -eq 0 ]]; then
      printf '  %sPASS%s  %-30s %s\n' "$color_ok" "$color_off" "$label" "$header"
    else
      failed=$((failed + 1))
      printf '  %sFAIL%s  %-30s %s\n' "$color_ko" "$color_off" "$label" "$header"
      printf '        %s\n' "${failures[0]}"
    fi
  }

  expect_ko() {
    local label="$1" header="$2" expected_substring="$3"
    failures=()
    validate_header "$header" "$(printf '%s\n\nbody' "$header")"
    if [[ ${#failures[@]} -eq 1 && "${failures[0]}" == *"$expected_substring"* ]]; then
      printf '  %sPASS%s  %-30s %s\n' "$color_ok" "$color_off" "$label" "$header"
    else
      failed=$((failed + 1))
      printf '  %sFAIL%s  %-30s %s\n' "$color_ko" "$color_off" "$label" "$header"
      printf '        expected substring: %s\n' "$expected_substring"
      printf '        got                 : %s\n' "${failures[0]:-<no failure>}"
    fi
  }

  echo "Running commit-message self-tests (mirrors my-housekeeper commitlint + checkConsecutiveEmojis + checkNoTrailers)..."
  echo

  # Happy path — one per CC type.
  expect_ok "feat"             "✨ feat(sessions): add session fork action"
  expect_ok "feat (no scope)"  "🚀 feat: ship webhook receiver"
  expect_ok "fix"              "🐛 fix(realtime): recover SSE stream after network change"
  expect_ok "docs"             "📄 docs(architecture): add system overview and module map"
  expect_ok "chore"            "🧹 chore: prune stale cache directories"
  expect_ok "refactor"         "♻️ refactor(sessions): split EventProcessor into smaller reducers"
  expect_ok "perf"             "⚡ perf(realtime): batch snapshot reconciliation"
  expect_ok "test"             "✅ test(security): add keystore round-trip unit tests"
  expect_ok "build"            "🏗️ build(gradle): bump kotlin to 2.1.0"
  expect_ok "ci"               "🤖 ci(workflows): pin actions to commit SHAs"
  expect_ok "style"            "🎨 style(design-system): normalize button spacing tokens"
  expect_ok "breaking (feat!)" "💥 feat(api)!: drop legacy /v1 endpoint"

  # Ignored commits (the same way @commitlint/is-ignored treats them).
  expect_ok "merge"            "Merge branch 'main' into feat/next"
  expect_ok "revert"           "Revert \"feat(api): ship /v3 endpoint\""
  expect_ok "fixup"            "fixup! feat(api): ship /v3 endpoint"
  expect_ok "squash"           "squash! feat(api): ship /v3 endpoint"

  # Failures.
  expect_ko "no emoji"         "feat(sessions): add session fork"          "must start with a Unicode emoji"
  expect_ko "no type"          "✨ something without colon"                "must match"
  expect_ko "unknown type"     "✨ addStuff(sessions): add session fork"   "is not in the allowed enum"
  expect_ko "unknown type-2"   "🐛 feature(x): x"                          "is not in the allowed enum"
  expect_ko "subject empty"    "✨ feat(x):   "                             "subject is empty or whitespace-only"

# Co-authored-by (forbidden trailer).
  failures=()
  local _body='✨ feat(x): y

body line

Co-authored-by: Bob <bob@example.com>'
  validate_header "✨ feat(x): y" "$_body"
  if [[ ${#failures[@]} -eq 1 && "${failures[0]}" == *"git trailer forbidden"* ]]; then
    printf '  %sPASS%s  %-30s Co-authored-by trailer blocked\n' "$color_ok" "$color_off" "trailer"
  else
    failed=$((failed + 1))
    printf '  %sFAIL%s  %-30s Co-authored-by trailer NOT blocked\n' "$color_ko" "$color_off" "trailer"
    printf '        got: %s\n' "${failures[0]:-<no failure>}"
  fi

  # BREAKING CHANGE prose (forbidden; use type!).
  failures=()
  _body='✨ feat(x): y

BREAKING CHANGE: drops /v1'
  validate_header "✨ feat(x): y" "$_body"
  if [[ ${#failures[@]} -eq 1 && "${failures[0]}" == *"BREAKING CHANGE prose"* ]]; then
    printf '  %sPASS%s  %-30s BREAKING CHANGE prose blocked\n' "$color_ok" "$color_off" "breaking prose"
  else
    failed=$((failed + 1))
    printf '  %sFAIL%s  %-30s BREAKING CHANGE prose NOT blocked\n' "$color_ko" "$color_off" "breaking prose"
    printf '        got: %s\n' "${failures[0]:-<no failure>}"
  fi

  # Consecutive emoji rule.
  failures=()
  check_consecutive_emojis $'✨ feat(x): a\n✨ feat(y): b\n🐛 fix(z): c'
  if [[ ${#failures[@]} -eq 1 && "${failures[0]}" == *"consecutive commits share the same emoji"* ]]; then
    printf '  %sPASS%s  %-30s two same emojis in a row blocked\n' "$color_ok" "$color_off" "consecutive"
  else
    failed=$((failed + 1))
    printf '  %sFAIL%s  %-30s consecutive emoji NOT blocked\n' "$color_ko" "$color_off" "consecutive"
    printf '        got: %s\n' "${failures[0]:-<no failure>}"
  fi

  # Different emojis in a row: should NOT trigger.
  failures=()
  check_consecutive_emojis $'✨ feat(x): a\n🐛 fix(y): b\n📄 docs(z): c'
  if [[ ${#failures[@]} -eq 0 ]]; then
    printf '  %sPASS%s  %-30s varying emojis do not trigger\n' "$color_ok" "$color_off" "consecutive-ok"
  else
    failed=$((failed + 1))
    printf '  %sFAIL%s  %-30s varying emojis incorrectly triggered\n' "$color_ko" "$color_off" "consecutive-ok"
    printf '        got: %s\n' "${failures[0]}"
  fi

  # PR title — three scenarios.
  failures=()
  validate_pr_title "✨ feat(x): a"
  if [[ ${#failures[@]} -eq 0 ]]; then
    printf '  %sPASS%s  %-30s PR title format valid\n' "$color_ok" "$color_off" "PR title ok"
  else
    failed=$((failed + 1))
    printf '  %sFAIL%s  %-30s PR title format rejected\n' "$color_ko" "$color_off" "PR title ok"
  fi

  failures=()
  validate_pr_title "✨ feat(x): a (#12)"
  if [[ ${#failures[@]} -eq 1 && "${failures[0]}" == *"' (#N)'"* ]]; then
    printf '  %sPASS%s  %-30s PR title with (#N) flagged\n' "$color_ok" "$color_off" "PR title #N"
  else
    failed=$((failed + 1))
    printf '  %sFAIL%s  %-30s PR title with (#N) NOT flagged\n' "$color_ko" "$color_off" "PR title #N"
  fi

  failures=()
  validate_pr_title "feat(x): no emoji"
  if [[ ${#failures[@]} -eq 1 && "${failures[0]}" == *"must match"* ]]; then
    printf '  %sPASS%s  %-30s PR title without emoji blocked\n' "$color_ok" "$color_off" "PR title no emoji"
  else
    failed=$((failed + 1))
    printf '  %sFAIL%s  %-30s PR title without emoji NOT blocked\n' "$color_ko" "$color_off" "PR title no emoji"
  fi


  echo
  if (( failed == 0 )); then
    echo "All self-tests passed (header rules)."
    return 0
  else
    echo "${failed} self-test(s) failed."
    return 1
  fi
}

# Validate full commit message against the rule list.
# Order matches the order of rules in my-housekeeper commitlint.config.mjs:
# emoji prefix → header pattern → type enum → header-max-length → subject.
validate_header() {
  local header="$1"
  local msg="${2:-}"  # full message, optional, used by check_trailers

  # 1. Skip ignored commits (merge/revert/fixup!/squash!/amend!).
  if [[ "$header" =~ $IGNORED_HEADER_REGEX ]]; then
    return 0
  fi

  # 2. Must start with an emoji.
  if ! match '^\p{Extended_Pictographic}\x{FE0F}?' "$header"; then
    record_failure "$header" "message must start with a Unicode emoji (per qveys/my-housekeeper commit-message-check)"
    return 1
  fi

  # 3. Must match the full header pattern: capture groups so we can check
  #    the type enum separately and surface a precise error.
  local captured
  captured="$(capture_header "$header")"
  if [[ -z "$captured" ]]; then
    record_failure "$header" "header must match: <emoji> ${CC_TYPES_REGEX}[(<scope>)][!]: <subject>"
    return 1
  fi

  # 4. Type must be in the allowed enum.
  local type
  type="$(printf '%s' "$captured" | perl -CSDA -F'\x1f' -lane '
    use strict;
    binmode(STDOUT, ":utf8");
    if (@F >= 2) { print $F[1]; }
  ')"
  if ! [[ "$type" =~ ^${CC_TYPES_REGEX}$ ]]; then
    record_failure "$header" "type '$type' is not in the allowed enum (feat|fix|docs|style|refactor|perf|test|build|ci|chore — my-housekeeper commit-message-check)"
    return 1
  fi

  # 5. Subject must be non-empty AND non-whitespace-only. commitlint's
  #    `subject-empty: [2, never]` won't catch trailing whitespace because
  #    `(.+)` already matched, but a header like "✨ feat(x):   " is
  #    clearly malformed and a typo of a forgotten subject.
  local subject
  subject="$(printf '%s' "$captured" | perl -CSDA -F'\x1f' -lane '
    use strict;
    binmode(STDOUT, ":utf8");
    if (@F >= 5 && defined $F[4]) { print $F[4]; }
  ')"
  if [[ -z "$subject" || "$subject" =~ ^[[:space:]]+$ ]]; then
    record_failure "$header" "subject is empty or whitespace-only"
    return 1
  fi

  # 6. Length cap (header-max-length: 100, defined by commitlint).
  local byte_len
  byte_len="$(printf '%s' "$header" | wc -c | tr -d ' ')"
  if (( byte_len > HEADER_MAX_LENGTH )); then
    record_failure "$header" "header is ${byte_len} chars, max ${HEADER_MAX_LENGTH} (my-housekeeper commit-message-check)"
    return 1
  fi

  # 7. No forbidden git trailers (any line of the full message body) and
  #    no BREAKING CHANGE prose marker (use type! instead, per my-housekeeper).
  if [[ -n "$msg" ]]; then
    local line
    while IFS= read -r line; do
      if [[ "$line" =~ $FORBIDDEN_TRAILER_KEY_REGEX ]]; then
        record_failure "$header" "git trailer forbidden ('${BASH_REMATCH[1]}:'). Use <type>! on the subject for breaking changes."
        return 1
      fi
      if [[ "$line" =~ $FORBIDDEN_BREAKING_PROSE_REGEX ]]; then
        record_failure "$header" "BREAKING CHANGE prose marker forbidden. Use <type>! on the subject instead."
        return 1
      fi
    done <<< "$msg"
  fi

  return 0
}

# Consecutive-emoji check (my-housekeeper src/handlers/commit-lint.ts
# checkConsecutiveEmojis): when two adjacent commits share the same leading
# emoji, surface a failure. Ignored commits reset the previous-emoji pointer.
check_consecutive_emojis() {
  local previous_emoji=""
  local previous_header=""
  local header
  while IFS= read -r header; do
    [[ -z "$header" ]] && continue
    if [[ "$header" =~ $IGNORED_HEADER_REGEX ]]; then
      previous_emoji=""
      previous_header=""
      continue
    fi
    local current_emoji=""
    if match '^\p{Extended_Pictographic}\x{FE0F}?' "$header"; then
      current_emoji="$(perl -e '
        use strict;
        use warnings;
        use utf8;
        use Encode;
        my $s = decode_utf8(shift);
        binmode(STDOUT, ":utf8");
        if ($s =~ /^(\p{Extended_Pictographic}\x{FE0F}?)/) { print $1; }
      ' -- "$header")"
    fi
    if [[ -n "$previous_emoji" && "$previous_emoji" == "$current_emoji" && -n "$current_emoji" ]]; then
      record_failure "$header" "consecutive commits share the same emoji (${current_emoji}): '${previous_header}' -> '${header}'"
    fi
    previous_emoji="$current_emoji"
    previous_header="$header"
  done <<< "$1"
}

# PR/issue title: same header pattern, but length cap 256 per normalize-title
# clampTitle(). For PRs specifically, a ` (#N)` suffix is forbidden because
# GitHub adds it automatically on squash-merge (otherwise we'd get a double
# ` (#12)` in the generated commit message).
validate_pr_title() {
  local title="$1"
  if [[ "$title" =~ \(#[0-9]+\)[[:space:]]*$ ]]; then
    record_failure "$title" "PR titles must not include ' (#N)' — GitHub adds it automatically on squash-merge"
  fi
  local byte_len
  byte_len="$(printf '%s' "$title" | wc -c | tr -d ' ')"
  if (( byte_len > PR_TITLE_MAX_LENGTH )); then
    record_failure "$title" "title is ${byte_len} chars, max ${PR_TITLE_MAX_LENGTH} (my-housekeeper normalize-title cap)"
    return 1
  fi
  if ! match "$PR_TITLE_PATTERN" "$title"; then
    record_failure "$title" "title must match: <emoji> ${CC_TYPES_REGEX}[(<scope>)][!]: <description> (my-housekeeper normalize-title format)"
    return 1
  fi
  return 0
}

cmd_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    echo "ERROR: file not found: $path" >&2
    return 2
  fi
  local msg
  msg="$(cat "$path")"
  # Mirror stripCommitComments() (commit-lint.ts): drop Git's commit-template
  # comment lines (start with '#') before validating.
  local cleaned
  cleaned="$(printf '%s' "$msg" | sed -E '/^#/d')"
  local header
  header="$(header_of "$cleaned")"
  validate_header "$header" "$cleaned"
  if [[ ${#failures[@]} -ne 0 ]]; then
    printf '%s\n' "${failures[@]}" >&2
    return 1
  fi
  return 0
}

cmd_range() {
  local range="$1"
  # Resolve base..head. Both refs must exist (caller passes real refs).
  if ! git rev-parse --verify --quiet "${range%..*}^{commit}" >/dev/null; then
    echo "ERROR: '${range%..*}' is not a valid commit" >&2
    return 2
  fi
  if ! git rev-parse --verify --quiet "${range#*..}^{commit}" >/dev/null; then
    echo "ERROR: '${range#*..}' is not a valid commit" >&2
    return 2
  fi

  # `--no-merges` mirrors isIgnored() so merge commits produced by GitHub UI
  # rewinds don't fail the script. The bot commit-message-check uses the
  # PR API which already excludes merges, so both paths end up aligned.
  local headers
  if ! headers="$(git log --no-merges --pretty=format:'%s' "$range" 2>/dev/null)"; then
    echo "ERROR: git log failed for $range" >&2
    return 2
  fi
  if [[ -z "$headers" ]]; then
    return 0  # no non-merge commits — nothing to check
  fi

  # Per-subject header validation (rules that can be checked from the subject
  # alone: emoji prefix, header pattern, type enum, length cap).
  local subject
  while IFS= read -r subject; do
    [[ -z "$subject" ]] && continue
    validate_header "$subject" ""
  done <<< "$headers"

  # Consecutive-emoji check on the full sequence — passes the headers so
  # check_consecutive_emojis can compare i-1 and i.
  check_consecutive_emojis "$headers"

  if [[ ${#failures[@]} -ne 0 ]]; then
    printf '%s\n' "${failures[@]}" >&2
    return 1
  fi
  return 0
}

cmd_pr_title() {
  local title="$1"
  validate_pr_title "$title"
  if [[ ${#failures[@]} -ne 0 ]]; then
    printf '%s\n' "${failures[@]}" >&2
    return 1
  fi
  return 0
}

# --- Entry-point ----------------------------------------------------------

if [[ $# -lt 1 ]]; then
  sed -n '2,32p' "$0"
  exit 2
fi

case "$1" in
  --self-test)
    cmd_self_test
    ;;
  --file)
    [[ $# -ge 2 ]] || { echo "ERROR: --file expects a path" >&2; exit 2; }
    cmd_file "$2"
    ;;
  --range)
    [[ $# -ge 2 ]] || { echo "ERROR: --range expects a ref range (base..head)" >&2; exit 2; }
    cmd_range "$2"
    ;;
  --pr-title)
    [[ $# -ge 2 ]] || { echo "ERROR: --pr-title expects a title string" >&2; exit 2; }
    shift
    # Title may legitimately contain spaces, so accept the rest as one.
    cmd_pr_title "$*"
    ;;
  --help|-h)
    sed -n '2,32p' "$0"
    ;;
  *)
    echo "ERROR: unknown mode '$1'. Use --self-test | --file | --range | --pr-title | --help" >&2
    exit 2
    ;;
esac

