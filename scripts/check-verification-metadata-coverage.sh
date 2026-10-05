#!/usr/bin/env bash
# scripts/check-verification-metadata-coverage.sh
#
# SEC-05b — CI gate: fail when a declared build input is not covered by
# gradle/verification-metadata.xml, or when CI is wired to rewrite the pins.
#
# This gate closes the root cause behind OPE-220, which SEC-05's presence check
# could not see. `check-gradle-supply-chain.sh` proves the pin file *exists* and
# pins something; it cannot prove the pin file covers what the build actually
# resolves. So a pull request that adds a dependency — a new `libs.` alias, a
# new version in gradle/libs.versions.toml, a new string coordinate — while
# leaving gradle/verification-metadata.xml untouched passed every gate and then
# failed later, during plugin resolution, in every job at once:
#
#   > Dependency verification failed for configuration 'detachedConfiguration10'
#     One artifact failed verification: io.gitlab.arturbosch.detekt.gradle.plugin-1.23.8.pom
#
# Nothing in the pipeline tied "build inputs changed" to "pins changed", so the
# omission was invisible until the build ran. This gate makes it visible in the
# fast, JDK-free static check instead.
#
# Three controls, checked independently:
#
#   1. Coverage. Every external module coordinate the build scripts declare, by
#      any route it uses, must already exist in gradle/verification-metadata.xml
#      as <component group=… name=… version=…>. Applied to plugin markers too:
#      `id("x") version "v"` and `alias(libs.plugins.y)` resolve to the marker
#      coordinate `x:x.gradle.plugin:v`, which is the artifact that fails
#      during plugin resolution — before any job runs its own step, and the
#      reason all jobs went red together on OPE-220.
#   2. Declarations this check cannot resolve offline. Some notations carry no
#      coordinate at parse time: a plugin-provided extension such as
#      `compose.runtime`, `kotlin("stdlib")`, or a two-segment literal whose
#      version comes from a BOM. Silently ignoring them would leave a hole
#      exactly the shape of the bug, so an unresolvable declaration fails this
#      gate unless it is registered in
#      gradle/verification-coverage-exemptions.txt together with a reason.
#      Exemptions are a normal reviewed commit, not a bypass switch: reaching
#      the list means the change is visible in the diff and in review.
#   3. Nothing in CI rewrites the pins. `--write-verification-metadata` must not
#      appear in any file under .github/workflows/. This exists because the flag
#      is only correct from a machine that reaches both Maven Central and the
#      Gradle Plugin Portal, and the two serve *different bytes* for several
#      plugin marker POMs (measured on this repository; see
#      docs/CI-CD-SECURITY.md §8). Regenerating on `pull_request` would rewrite
#      the pins from an environment nobody reviewed. A workflow that needs to
#      do it may register in gradle/verification-regeneration-exemptions.txt,
#      but only if it triggers on `workflow_dispatch` and nothing else — the
#      exemption is checked against the workflow's own `on:` block, so adding
#      the flag and a `pull_request` trigger in one pull request fails.
#
# Deliberately independent of the Gradle build (no JDK, no network, no Android
# SDK) so it can run on every pull request, in the same job as the other
# supply-chain gates. It reads build scripts with perl (core modules only)
# because resolving a version-catalog alias and comparing it against the pin
# file needs a parse, not a grep. perl must exist on the runner and the gate
# fails closed when it does not.
#
# OPE-264: this was python3, which the digest-pinned eclipse-temurin image used by
# `T4 static scan` does not carry (ADR 0007), so the gate reported "python3 is
# required … and is not on PATH" — the environment instead of an integrity
# problem — and was the last red check in a required job. The image is NOT changed
# (the digest pin is the supply-chain control) and the gate is NOT moved off the
# container (that isolation is OPE-212's), so the parsing moves to perl, which the
# base image does carry. Rewriting it in awk was rejected on purpose: TOML and YAML
# expressed as awk substitutions is the classic route to a supply-chain gate that
# gets silently bypassed by one bad escape. SEC-05 took the same decision in
# OPE-257 and left the parsing in scripts/lib/sec05-parse.pl; SEC-05b now shares
# that library for the pin file and adds its own for the catalog and the workflow
# `on:` blocks, in scripts/lib/sec05b-parse.pl. The two implementations are held to
# identical output by the OPE-264 parity section of scripts/tests/
# test-supply-chain-gates.sh, which runs both over the same fixture corpus.
#
# A coverage gate that produces false positives gets bypassed, and a bypassed
# gate protects nothing — so an unrecognised declaration is reported as a
# finding to fix or to exempt, never quietly accepted. scripts/tests/
# test-supply-chain-gates.sh exercises each control against a fixture that used
# to make it pass when it should fail.
#
# Exit 0 when the pins cover the build inputs and CI cannot rewrite them, exit 1
# otherwise.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

VERIFICATION_METADATA="gradle/verification-metadata.xml"
VERSION_CATALOG="gradle/libs.versions.toml"
COVERAGE_EXEMPTIONS="gradle/verification-coverage-exemptions.txt"
REGEN_EXEMPTIONS="gradle/verification-regeneration-exemptions.txt"
WORKFLOWS_DIR=".github/workflows"
WRITE_FLAG="--write-verification-metadata"

fail=0

fail_with() {
  printf '::error::%s\n' "$1"
  printf '  %s\n' "$1"
  fail=1
}

SEC05_LIB="scripts/lib/sec05-parse.pl"
SEC05B_LIB="scripts/lib/sec05b-parse.pl"
have_parser=1
command -v perl >/dev/null 2>&1 || have_parser=0
[ -r "$SEC05_LIB" ] || have_parser=0
[ -r "$SEC05B_LIB" ] || have_parser=0
if [ "$have_parser" -eq 0 ]; then
  fail_with "perl and $SEC05B_LIB (which reads $VERIFICATION_METADATA through $SEC05_LIB) are required to resolve gradle/libs.versions.toml aliases, to parse $VERIFICATION_METADATA and to read workflow triggers, and at least one is missing; without them the dependency-pin coverage control cannot run, so this gate fails closed rather than skipping it."
fi

printf 'Checking that dependency pins cover the declared build inputs (SEC-05b)...\n'

# --- controls 1 and 2: pin coverage and unresolvable declarations -----------

coverage_report() {
  perl -I scripts/lib - "$VERIFICATION_METADATA" "$VERSION_CATALOG" "$COVERAGE_EXEMPTIONS" "$ROOT_DIR" <<'PERL'
use strict;
use warnings;
# The pin file is read through scripts/lib/sec05-parse.pl and the catalog and the
# workflow `on:` blocks through scripts/lib/sec05b-parse.pl. Both are loaded by
# path, from this repository, never from a caller-controlled location.
require './scripts/lib/sec05b-parse.pl';
sec05b_coverage_report($ARGV[0], $ARGV[1], $ARGV[2], $ARGV[3]);
PERL
}

if [ "$have_parser" -eq 0 ]; then
  :
elif [ ! -f "$VERIFICATION_METADATA" ]; then
  fail_with "Missing $VERIFICATION_METADATA; there is nothing for the declared build inputs to be covered by (SEC-05b)."
elif [ ! -f "$VERSION_CATALOG" ]; then
  fail_with "Missing $VERSION_CATALOG; the declared dependencies cannot be enumerated, so pin coverage cannot be checked (SEC-05b)."
else
  if coverage_output="$(coverage_report 2>&1)"; then
    printf '  [ok] %s\n' "$coverage_output"
  else
    while IFS= read -r problem; do
      [ -n "$problem" ] && fail_with "$problem"
    done <<< "$coverage_output"
  fi
fi

# --- control 3: no CI job rewrites the pins ---------------------------------

# `--write-verification-metadata` is only correct from a machine that reaches
# both Maven Central and the Gradle Plugin Portal: the two serve different
# bytes for several plugin marker POMs, so regenerating from one of them
# rewrites pins the other rejects (docs/CI-CD-SECURITY.md §8.1). Letting a
# `pull_request` job do it would therefore rewrite the pins from an environment
# nobody reviewed, which is the whole control in one step. The exemption list
# exists for a future manual, read-only regeneration job, and it only works for
# a workflow that triggers on `workflow_dispatch` and nothing else — checked
# here against the workflow's own `on:` block, so the flag and a `pull_request`
# trigger cannot be added in the same pull request.
regen_report() {
  perl -I scripts/lib - "$WORKFLOWS_DIR" "$WRITE_FLAG" "$REGEN_EXEMPTIONS" <<'PERL'
use strict;
use warnings;
require './scripts/lib/sec05b-parse.pl';
sec05b_regen_report($ARGV[0], $ARGV[1], $ARGV[2]);
PERL
}

if [ "$have_parser" -eq 0 ]; then
  :
elif [ ! -d "$WORKFLOWS_DIR" ]; then
  fail_with "Missing $WORKFLOWS_DIR; CI cannot be shown not to rewrite $VERIFICATION_METADATA (SEC-05b)."
else
  if regen_output="$(regen_report 2>&1)"; then
    printf '  [ok] %s\n' "$regen_output"
  else
    while IFS= read -r problem; do
      [ -n "$problem" ] && fail_with "$problem"
    done <<< "$regen_output"
  fi
fi

if [ "$fail" -ne 0 ]; then
  printf '\nFAIL: declared build inputs are not covered by %s, or CI is wired to rewrite it (SEC-05b).\n' "$VERIFICATION_METADATA"
  exit 1
fi

printf 'OK: every declared external dependency is pinned in %s, unresolvable declarations are registered with a reason, and no CI workflow rewrites the pins (SEC-05b).\n' "$VERIFICATION_METADATA"
exit 0