#!/usr/bin/env bash
# scripts/tests/test-supply-chain-gates.sh
# Local tests for the SEC-04 / SEC-05 / SEC-05b supply-chain gates.
#
# Covers action pinning (SEC-04) — repository actions, sub-path actions,
# reusable workflows and container actions — the four Gradle toolchain controls
# (SEC-05): distributionSha256Sum, a distributionUrl whose host is a publisher
# this repository trusts (official host or an entry in
# gradle/wrapper/gradle-distribution-allowlist.txt), the committed
# gradle-wrapper.jar digest, and the now-mandatory
# gradle/verification-metadata.xml dependency-checksum metadata.
#
# And the three pin-coverage controls (SEC-05b, OPE-238): every coordinate the
# build scripts declare must already be pinned; a declaration the gate cannot
# resolve must be registered with a reason; and no workflow may rewrite the
# pins. Those three exist because of the incident they follow — OPE-220 — which
# added a dependency without regenerating the pin file while every other gate
# stayed green, so the omission only surfaced when the build ran.
#
# Every gate is checked against a fixture that used to make it pass or fail,
# not only against the happy path: a control that cannot be shown to reject a
# defective input is not evidence of anything.
#
# Runs scripts/check-workflow-action-pinning.sh,
# scripts/check-gradle-supply-chain.sh and
# scripts/check-verification-metadata-coverage.sh against synthetic fixtures in
# a temp directory. It never contacts GitHub or any artifact repository, and
# never touches the real repository files.
#
# Run: bash scripts/tests/test-supply-chain-gates.sh

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PINNING="$ROOT/scripts/check-workflow-action-pinning.sh"
GRADLE_GATE="$ROOT/scripts/check-gradle-supply-chain.sh"
COVERAGE_GATE="$ROOT/scripts/check-verification-metadata-coverage.sh"

PASS=0
FAIL=0

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

check() { # description expected actual
  if [ "$2" = "$3" ]; then
    printf '  ok   %s\n' "$1"
    PASS=$((PASS + 1))
  else
    printf '  FAIL %s (expected: %s, actual: %s)\n' "$1" "$2" "$3"
    FAIL=$((FAIL + 1))
  fi
}

check_contains() { # description haystack needle
  case "$2" in
    *"$3"*) printf '  ok   %s\n' "$1"; PASS=$((PASS + 1)) ;;
    *) printf '  FAIL %s (missing: %s)\n' "$1" "$3"; FAIL=$((FAIL + 1)) ;;
  esac
}

check_missing() { # description haystack needle
  case "$2" in
    *"$3"*) printf '  FAIL %s (unexpectedly present: %s)\n' "$1" "$3"; FAIL=$((FAIL + 1)) ;;
    *) printf '  ok   %s\n' "$1"; PASS=$((PASS + 1)) ;;
  esac
}

# --- fixtures ---------------------------------------------------------------

# Build a throwaway repository containing only the files the gates read.
make_repo() { # name
  local dir="$TMP_ROOT/$1"
  mkdir -p "$dir/.github/workflows" "$dir/gradle/wrapper" "$dir/scripts"
  cp "$PINNING" "$GRADLE_GATE" "$COVERAGE_GATE" "$dir/scripts/"
  printf '%s' "$dir"
}

PINNED='      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4.4.0'
SHA40='11d5960a326750d5838078e36cf38b85af677262'
DIGEST64='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

# Write a one-step workflow; the caller supplies the `uses:` body lines.
write_workflow() { # dir uses-line...
  local dir="$1"; shift
  {
    printf 'name: x\njobs:\n  a:\n    steps:\n'
    printf '%s\n' "$@"
  } > "$dir/.github/workflows/a.yml"
}

# Every fixture that reaches control 2 also needs the publisher allowlist, so it
# is written by default alongside the wrapper properties. `empty` means no host
# is trusted beyond the official one — the real repository's state.
write_allowlist() { # dir [entry ...]
  local dir="$1"; shift
  mkdir -p "$dir/gradle/wrapper"
  if [ "$#" -eq 0 ]; then
    printf '# no additional trusted hosts\n' > "$dir/gradle/wrapper/gradle-distribution-allowlist.txt"
  else
    printf '%s\n' "$@" > "$dir/gradle/wrapper/gradle-distribution-allowlist.txt"
  fi
}

write_wrapper() { # dir [sha_sum_line] [url]
  local dir="$1" sha="${2:-}" url="${3:-https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip}"
  {
    printf 'distributionBase=GRADLE_USER_HOME\n'
    printf 'distributionUrl=%s\n' "$url"
    [ -n "$sha" ] && printf '%s\n' "$sha"
    printf 'networkTimeout=10000\n'
    printf 'validateDistributionUrl=true\n'
  } > "$dir/gradle/wrapper/gradle-wrapper.properties"
  write_allowlist "$dir"
}

write_wrapper_jar() { # dir [digest_override]
  local dir="$1" override="${2:-}"
  printf 'not really a jar\n' > "$dir/gradle/wrapper/gradle-wrapper.jar"
  local actual
  actual="$(sha256sum "$dir/gradle/wrapper/gradle-wrapper.jar" | awk '{print $1}')"
  printf '%s  gradle/wrapper/gradle-wrapper.jar\n' "${override:-$actual}" \
    > "$dir/gradle/wrapper/gradle-wrapper.jar.sha256"
}

write_verification_metadata() { # dir
  local dir="$1"
  cat > "$dir/gradle/verification-metadata.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>false</verify-signatures>
   </configuration>
   <components>
      <component group="org.jetbrains.kotlin" name="kotlin-stdlib" version="2.1.0">
         <artifact name="kotlin-stdlib-2.1.0.jar">
            <sha256 value="0000000000000000000000000000000000000000000000000000000000000000"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
XML
}

# Replace gradle/verification-metadata.xml with a raw document, so a fixture can
# be as broken (or as empty) as the defect being tested.
write_metadata_raw() { # dir xml
  cat > "$1/gradle/verification-metadata.xml"
}

# --- SEC-05b fixtures -------------------------------------------------------

# A version catalog with one library (via version.ref) and one plugin alias.
write_catalog() { # dir
  cat > "$1/gradle/libs.versions.toml" <<'TOML'
[versions]
kotlin = "2.1.0"

[libraries]
stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }

[plugins]
thing = { id = "com.example.thing", version = "1.0.0" }
TOML
}

# The pin file covering exactly what write_catalog declares: the library and the
# plugin marker.
write_covering_metadata() { # dir
  cat > "$1/gradle/verification-metadata.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>false</verify-signatures>
   </configuration>
   <components>
      <component group="org.jetbrains.kotlin" name="kotlin-stdlib" version="2.1.0">
         <artifact name="kotlin-stdlib-2.1.0.jar">
            <sha256 value="0000000000000000000000000000000000000000000000000000000000000000"/>
         </artifact>
      </component>
      <component group="com.example.thing" name="com.example.thing.gradle.plugin" version="1.0.0">
         <artifact name="com.example.thing.gradle.plugin-1.0.0.pom">
            <sha256 value="0000000000000000000000000000000000000000000000000000000000000000"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
XML
}

write_empty_exemptions() { # dir
  printf '# no exemptions\n' > "$1/gradle/verification-coverage-exemptions.txt"
  printf '# no exemptions\n' > "$1/gradle/verification-regeneration-exemptions.txt"
}

# A minimal project whose only declarations are the catalog entries, so a
# fixture's outcome is attributable to the change the test makes.
make_coverage_repo() { # name
  local dir="$TMP_ROOT/$1"
  mkdir -p "$dir/.github/workflows" "$dir/gradle" "$dir/scripts" "$dir/app"
  cp "$COVERAGE_GATE" "$dir/scripts/"
  write_catalog "$dir"
  write_covering_metadata "$dir"
  write_empty_exemptions "$dir"
  printf 'name: x\njobs:\n  a:\n    steps:\n%s\n' "$PINNED" > "$dir/.github/workflows/a.yml"
  cat > "$dir/app/build.gradle.kts" <<'KTS'
plugins {
    alias(libs.plugins.thing)
}

dependencies {
    implementation(libs.stdlib)
    implementation(project(":other"))
}
KTS
  cat > "$dir/settings.gradle.kts" <<'KTS'
rootProject.name = "fixture"
include(":app")
KTS
  printf '%s' "$dir"
}

# A PATH directory holding every executable currently reachable except python*.
# Used to prove the gate fails closed rather than skipping the SEC-05 parse when
# its interpreter is missing.
make_path_without_python() { # outdir
  local out="$1" dir entry name
  mkdir -p "$out"
  local IFS_SAVE="$IFS"
  IFS=':'
  for dir in $PATH; do
    [ -d "$dir" ] || continue
    for entry in "$dir"/*; do
      [ -x "$entry" ] && [ ! -d "$entry" ] || continue
      name="${entry##*/}"
      case "$name" in
        python | python[0-9] | python[0-9].[0-9] | python[0-9].[0-9]*) continue ;;
      esac
      [ -e "$out/$name" ] || ln -s "$entry" "$out/$name" 2>/dev/null || true
    done
  done
  IFS="$IFS_SAVE"
  printf '%s' "$out"
}

# --- SEC-04: action pinning -------------------------------------------------

echo ""
echo "== SEC-04: check-workflow-action-pinning.sh rejects mutable action refs =="

d="$(make_repo sec04-pinned)"
printf 'name: x\njobs:\n  a:\n    steps:\n%s\n' "$PINNED" > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "SHA-pinned workflow exits zero" "0" "$code"
check_contains "SHA-pinned workflow reports the count" "$out" "1 reference(s) pinned"
check_contains "SHA-pinned workflow reports the digest split" "$out" "1 to a full commit SHA, 0 container digest(s)"

d="$(make_repo sec04-floating)"
printf 'name: x\njobs:\n  a:\n    steps:\n      - uses: actions/checkout@v4\n' \
  > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "floating major tag exits nonzero" "1" "$code"
check_contains "floating major tag names the ref" "$out" "actions/checkout@v4"
check_contains "floating major tag cites SEC-04" "$out" "SEC-04"

d="$(make_repo sec04-branch)"
printf 'name: x\njobs:\n  a:\n    steps:\n      - uses: gradle/actions/setup-gradle@main\n' \
  > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "floating branch ref exits nonzero" "1" "$code"
check_contains "floating branch ref names the ref" "$out" "setup-gradle@main"

d="$(make_repo sec04-partial-sha)"
# 39 hex characters — one short of a real commit SHA.
printf 'name: x\njobs:\n  a:\n    steps:\n      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af67726\n' \
  > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "truncated SHA exits nonzero" "1" "$code"

d="$(make_repo sec04-extra-sha)"
# 41 hex characters — must not be accepted by a prefix match.
printf 'name: x\njobs:\n  a:\n    steps:\n      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af6772622\n' \
  > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "over-long SHA exits nonzero" "1" "$code"

d="$(make_repo sec04-local)"
# A local step is exempt, but the tree still needs one genuinely pinned
# reference for the fail-closed guard; see sec04-local-only below.
write_workflow "$d" "$PINNED" '      - uses: ./.github/actions/local'
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "local ./ action is exempt" "0" "$code"

d="$(make_repo sec04-local-only)"
write_workflow "$d" '      - uses: ./.github/actions/local'
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
# Regression guard: the exempt local reference used to satisfy the fail-closed
# check, so a tree whose every remote action had been deleted passed while the
# pinning rule matched nothing.
check "a tree of local steps only exits nonzero (fail closed)" "1" "$code"
check_contains "the vacuous tree is reported as unpinned" "$out" "No pinned action found"

d="$(make_repo sec04-quoted)"
printf 'name: x\njobs:\n  a:\n    steps:\n      - uses: "actions/checkout@v4"\n' \
  > "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "quoted floating ref is still caught" "1" "$code"

d="$(make_repo sec04-no-dir)"
rm -rf "$d/.github/workflows"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "missing workflow dir exits nonzero" "1" "$code"

d="$(make_repo sec04-vacuous)"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "no workflows at all exits nonzero (fail closed)" "1" "$code"
check_contains "vacuous scan is reported" "$out" "no longer matching anything"

# Sub-path actions (`owner/repo/path/to/action`) and reusable workflows
# (`owner/repo/.github/workflows/x.yml`) are pinned exactly like a
# repository-root action. The pattern used to accept only `owner/repo@sha`, so
# every pinned sub-path action was rejected — a rule this repository could not
# satisfy without weakening it.
echo ""
echo "== SEC-04: sub-path actions and reusable workflows =="

d="$(make_repo sec04-subpath-pinned)"
write_workflow "$d" "      - uses: aws-actions/amazon-ecr/amazon-ecr-login@$SHA40 # v2.3.0"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "pinned sub-path action exits zero" "0" "$code"
check_contains "pinned sub-path action is counted" "$out" "1 reference(s) pinned"

d="$(make_repo sec04-subpath-floating)"
write_workflow "$d" '      - uses: aws-actions/amazon-ecr/amazon-ecr-login@v2'
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "floating sub-path action exits nonzero" "1" "$code"
check_contains "floating sub-path action names the ref" "$out" "amazon-ecr-login@v2"

d="$(make_repo sec04-subpath-partial-sha)"
write_workflow "$d" '      - uses: aws-actions/amazon-ecr/amazon-ecr-login@11d5960a326750d5838078e36cf38b85af67726'
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "sub-path action with a truncated SHA exits nonzero" "1" "$code"

d="$(make_repo sec04-reusable-pinned)"
printf 'name: x\njobs:\n  call:\n    uses: my-org/my-repo/.github/workflows/build.yml@%s # v9\n' "$SHA40" \
  > "$d/.github/workflows/a.yml"
printf 'name: y\njobs:\n  b:\n    steps:\n%s\n' "$PINNED" >> "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "pinned reusable workflow exits zero" "0" "$code"

d="$(make_repo sec04-reusable-floating)"
printf 'name: x\njobs:\n  b:\n    steps:\n%s\n' "$PINNED" > "$d/.github/workflows/a.yml"
printf 'name: y\njobs:\n  call:\n    uses: my-org/my-repo/.github/workflows/build.yml@v1\n' \
  >> "$d/.github/workflows/a.yml"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "floating reusable workflow exits nonzero" "1" "$code"
check_contains "floating reusable workflow names the ref" "$out" "build.yml@v1"

# Container actions carry the same mutable pointer as a tag: `docker://IMG:TAG`
# is resolved at job time, so whoever controls the registry account runs their
# bytes. Every `docker://` value used to be waved through as "pinned".
echo ""
echo "== SEC-04: container actions are pinned by digest =="

d="$(make_repo sec04-container-digest)"
write_workflow "$d" "$PINNED" "      - uses: docker://alpine@sha256:$DIGEST64"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "digest-pinned container action exits zero" "0" "$code"
check_contains "digest-pinned container action is counted as a digest" "$out" "1 container digest(s)"

d="$(make_repo sec04-container-tag)"
write_workflow "$d" "$PINNED" '      - uses: docker://alpine:latest'
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "container action on a tag exits nonzero" "1" "$code"
check_contains "container tag names the ref" "$out" "docker://alpine:latest"
check_contains "container tag cites SEC-04" "$out" "SEC-04"
check_contains "container tag asks for a digest" "$out" "not pinned to an image digest"

d="$(make_repo sec04-container-bare)"
write_workflow "$d" "$PINNED" '      - uses: docker://'
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "bare docker:// reference exits nonzero" "1" "$code"

d="$(make_repo sec04-container-short-digest)"
write_workflow "$d" "$PINNED" '      - uses: docker://alpine@sha256:0123456789abcdef'
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "truncated image digest exits nonzero" "1" "$code"

d="$(make_repo sec04-container-sha-not-digest)"
# A 40-hex commit SHA is not an image digest; the two must not be interchangeable.
write_workflow "$d" "$PINNED" "      - uses: docker://alpine@$SHA40"
out="$(cd "$d" && bash scripts/check-workflow-action-pinning.sh 2>&1)"; code=$?
check "commit SHA offered as an image digest exits nonzero" "1" "$code"

# --- SEC-05: Gradle toolchain integrity -------------------------------------

echo ""
echo "== SEC-05: check-gradle-supply-chain.sh requires an integrity-pinned toolchain =="

GOOD_SHA='distributionSha256Sum=31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26'

d="$(make_repo sec05-ok)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
write_verification_metadata "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "fully pinned toolchain exits zero" "0" "$code"
check_contains "wrapper JAR match is reported" "$out" "matches its committed SHA-256"
check_contains "dependency verification counts what it pins" "$out" "pins 1 SHA-256 checksum(s) across 1 component(s) / 1 artifact(s)"

d="$(make_repo sec05-no-verification-metadata)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "missing verification-metadata.xml exits nonzero" "1" "$code"
check_contains "missing dependency metadata is named" "$out" "gradle/verification-metadata.xml"
check_contains "missing dependency metadata cites SEC-05" "$out" "SEC-05"

# The parse: every one of these documents passed the old `grep -q '<components>'`
# presence check while pinning nothing. `grep` cannot tell them apart from a file
# that pins 1609 artifacts, which is the whole point of the control.
echo ""
echo "== SEC-05: verification-metadata.xml is parsed, not grepped =="

d="$(make_repo sec05-empty-components)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
write_metadata_raw "$d" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <configuration>
      <verify-metadata>true</verify-metadata>
   </configuration>
   <!-- everything below was removed; the file is still "present" -->
   <components>
   </components>
</verification-metadata>
XML
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "empty <components> exits nonzero" "1" "$code"
check_contains "empty <components> is explained" "$out" "pins no component"
check_missing "empty <components> is not reported as pinned" "$out" "pins 1 SHA-256"

d="$(make_repo sec05-components-in-comment)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
write_metadata_raw "$d" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <configuration>
      <verify-metadata>true</verify-metadata>
   </configuration>
   <!-- <components><component group="g" name="n" version="v">
        <artifact name="n-v.jar"><sha256 value="0000000000000000000000000000000000000000000000000000000000000000"/></artifact>
      </component></components> -->
</verification-metadata>
XML
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
# The literal string <components> appears in the file, so the old grep matched.
check "<components> only inside a comment exits nonzero" "1" "$code"
check_contains "commented-out metadata is explained" "$out" "declares no <components> section"

d="$(make_repo sec05-component-without-artifact)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
write_metadata_raw "$d" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <components>
      <component group="org.x" name="y" version="1.0"/>
   </components>
</verification-metadata>
XML
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "component carrying no artifact exits nonzero" "1" "$code"
check_contains "empty component is explained" "$out" "not one SHA-256 checksum"

d="$(make_repo sec05-artifact-without-checksum)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
write_metadata_raw "$d" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <components>
      <component group="org.x" name="y" version="1.0">
         <artifact name="y-1.0.jar"/>
         <artifact name="y-1.0.pom">
            <sha256 value="0000000000000000000000000000000000000000000000000000000000000000"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
XML
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "an artifact with no checksum and no signature exits nonzero" "1" "$code"
check_contains "the unpinned artifact is identified" "$out" "org.x:y:1.0/y-1.0.jar"

d="$(make_repo sec05-md5-only)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
write_metadata_raw "$d" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <components>
      <component group="org.x" name="y" version="1.0">
         <artifact name="y-1.0.jar">
            <md5 value="0123456789abcdef0123456789abcdef"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
XML
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "MD5-only metadata exits nonzero" "1" "$code"
check_contains "MD5-only metadata is explained" "$out" "not one SHA-256 checksum"

d="$(make_repo sec05-malformed-xml)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
write_metadata_raw "$d" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <components><component group="org.x"
</verification-metadata>
XML
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "malformed XML exits nonzero" "1" "$code"
check_contains "malformed XML is explained" "$out" "not well-formed XML"

d="$(make_repo sec05-wrong-root)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
write_metadata_raw "$d" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata-disabled>
   <components/>
</verification-metadata-disabled>
XML
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "a file that is not Gradle metadata exits nonzero" "1" "$code"
check_contains "the wrong root element is named" "$out" "not <verification-metadata>"

d="$(make_repo sec05-verify-metadata-false)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
write_metadata_raw "$d" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <configuration>
      <verify-metadata>false</verify-metadata>
   </configuration>
   <components>
      <component group="org.x" name="y" version="1.0">
         <artifact name="y-1.0.jar">
            <sha256 value="0000000000000000000000000000000000000000000000000000000000000000"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
XML
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "<verify-metadata>false</verify-metadata> exits nonzero" "1" "$code"
check_contains "the metadata-verification downgrade is explained" "$out" "turns POM metadata verification off"

d="$(make_repo sec05-signature-instead-of-checksum)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
write_metadata_raw "$d" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>true</verify-signatures>
   </configuration>
   <components>
      <component group="org.x" name="y" version="1.0">
         <artifact name="y-1.0.jar">
            <trusting-key id="8f4e8f1a4f2a1c2b3d4e5f60718293a4b5c6d7e8" group="org.x" name="y" version="1.0"/>
         </artifact>
         <artifact name="y-1.0.pom">
            <sha256 value="0000000000000000000000000000000000000000000000000000000000000000"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
XML
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
# A signature entry is a pin too. Rejecting it would make the gate unusable for a
# repository that verifies by signature rather than by digest.
check "signature-pinned artifact is accepted" "0" "$code"

d="$(make_repo sec05-no-python)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d"
write_verification_metadata "$d"
NO_PYTHON_PATH="$(make_path_without_python "$TMP_ROOT/bin-no-python")"
out="$(cd "$d" && PATH="$NO_PYTHON_PATH" bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
# Without an interpreter the parse cannot run; the gate must say so, not pass.
# The interpreter now backs two controls, so a runner without it must not be
# able to pass a trusted-looking URL *or* a pinned-looking dependency file.
check "no python3 on PATH exits nonzero (fail closed)" "1" "$code"
check_contains "the missing interpreter is named" "$out" "python3 is required"
check_contains "the publisher control is reported as unverifiable" "$out" "distributionUrl"

d="$(make_repo sec05-no-sum)"
write_wrapper "$d" ""
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "missing distributionSha256Sum exits nonzero" "1" "$code"
check_contains "missing sum names the property" "$out" "distributionSha256Sum"

d="$(make_repo sec05-bad-sum)"
write_wrapper "$d" 'distributionSha256Sum=not-a-hash'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "malformed distributionSha256Sum exits nonzero" "1" "$code"

d="$(make_repo sec05-http)"
write_wrapper "$d" "$GOOD_SHA" 'http\://services.gradle.org/distributions/gradle-8.10.2-bin.zip'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "http distributionUrl exits nonzero" "1" "$code"
check_contains "http downgrade is named" "$out" "not https"

# --- SEC-05 control 2: the distribution publisher is trusted ----------------
#
# `distributionUrl` and `distributionSha256Sum` were each checked on their own:
# https on one hand, "64 hex characters" on the other. A pull request that
# edits both at once — an attacker host plus the checksum of the attacker's own
# archive — satisfied both and the gate exited 0, after which `./gradlew`
# downloaded and ran the attacker's Gradle (threat T10). The digest says which
# bytes; only the host says who published them. These fixtures pin the two
# together.

d="$(make_repo sec05-attacker-host)"
write_wrapper "$d" "$GOOD_SHA" 'https\://attacker.example/distributions/gradle-8.10.2-bin.zip'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "an untrusted distribution host exits nonzero" "1" "$code"
check_contains "the rejected host is named" "$out" "attacker.example"
check_contains "the official host is named as the alternative" "$out" "services.gradle.org"
check_contains "the allowlist location is named" "$out" "gradle-distribution-allowlist.txt"
check_contains "the finding cites SEC-05" "$out" "SEC-05"
check_missing "the attacker's Gradle is not reported as pinned" "$out" "OK:"

# The digest in that fixture is the real official one. Whatever value an
# attacker writes, the host is what makes it theirs; assert the host rule holds
# independently of the checksum so the two controls cannot be "fixed" apart.
d="$(make_repo sec05-attacker-host-self-consistent)"
write_wrapper "$d" 'distributionSha256Sum=0000000000000000000000000000000000000000000000000000000000000000' \
  'https\://attacker.example/distributions/gradle-8.10.2-bin.zip'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "attacker host with a well-formed digest still exits nonzero" "1" "$code"
check_contains "that attack is described as a two-line edit" "$out" "editing distributionUrl together with distributionSha256Sum"

# A mirror is a legitimate Gradle configuration, so an allowlisted host must
# pass — including on a path the official-host rule would reject, since a
# corporate proxy may lay out its own paths.
d="$(make_repo sec05-mirror-allowlisted)"
write_wrapper "$d" "$GOOD_SHA" 'https\://gradle.internal.example/dists/gradle-8.10.2-bin.zip'
write_allowlist "$d" 'gradle.internal.example'
write_wrapper_jar "$d"
write_verification_metadata "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "an allowlisted mirror host exits zero" "0" "$code"
check_contains "the allowlisted host is reported" "$out" "allowlisted mirror host gradle.internal.example"

d="$(make_repo sec05-mirror-not-allowlisted)"
write_wrapper "$d" "$GOOD_SHA" 'https\://gradle.internal.example/dists/gradle-8.10.2-bin.zip'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "the same mirror without an allowlist entry exits nonzero" "1" "$code"
check_contains "the unlisted mirror is named" "$out" "gradle.internal.example"

# The host check parses the URL. A prefix test is impersonated for free by
# anything that merely *starts* with the approved string; each of these used to
# satisfy `https://*`, or would satisfy a naive `grep services.gradle.org`.
echo ""
echo "== SEC-05: the distribution host is parsed, not prefix-matched =="

d="$(make_repo sec05-userinfo-trick)"
write_wrapper "$d" "$GOOD_SHA" 'https\://services.gradle.org@attacker.example/distributions/gradle-8.10.2-bin.zip'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
# Here services.gradle.org is URL userinfo; the connected host is attacker.example.
check "userinfo impersonation exits nonzero" "1" "$code"
check_contains "the real host is identified, not the userinfo" "$out" "attacker.example"

d="$(make_repo sec05-host-in-query)"
write_wrapper "$d" "$GOOD_SHA" 'https\://attacker.example/gradle.zip?src=https\://services.gradle.org'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "the approved host appearing in the query string exits nonzero" "1" "$code"

d="$(make_repo sec05-host-prefix-suffix)"
write_wrapper "$d" "$GOOD_SHA" 'https\://services.gradle.org.attacker.example/distributions/gradle-8.10.2-bin.zip'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "a host merely starting with the official name exits nonzero" "1" "$code"

d="$(make_repo sec05-trailing-dot)"
write_wrapper "$d" "$GOOD_SHA" 'https\://services.gradle.org./distributions/gradle-8.10.2-bin.zip'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "a trailing-dot host exits nonzero" "1" "$code"

# The official host is allowed, but only on the paths Gradle actually publishes
# to. Accepting any path on it would let a valid host be pointed elsewhere.
d="$(make_repo sec05-official-wrong-path)"
write_wrapper "$d" "$GOOD_SHA" 'https\://services.gradle.org/anything/gradle-8.10.2-bin.zip'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "the official host on a non-distribution path exits nonzero" "1" "$code"
check_contains "the expected path shape is named" "$out" "/distributions/gradle-<version>-{bin,all}.zip"

# Real official URLs must keep working, including the pre-release and uppercase
# spellings a mirror or a hand-edit produces.
d="$(make_repo sec05-official-all)"
write_wrapper "$d" "$GOOD_SHA" 'https\://services.gradle.org/distributions/gradle-8.10.2-all.zip'
write_wrapper_jar "$d"
write_verification_metadata "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "the official -all distribution exits zero" "0" "$code"

d="$(make_repo sec05-official-rc)"
write_wrapper "$d" "$GOOD_SHA" 'https\://services.gradle.org/distributions/gradle-8.11-rc-1-all.zip'
write_wrapper_jar "$d"
write_verification_metadata "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "a pre-release official distribution exits zero" "0" "$code"

d="$(make_repo sec05-uppercase-scheme)"
write_wrapper "$d" "$GOOD_SHA" 'HTTPS\://services.gradle.org/distributions/gradle-8.10.2-bin.zip'
write_wrapper_jar "$d"
write_verification_metadata "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
# URL schemes are case-insensitive; the old glob was not, so this valid URL was
# rejected for a reason that has nothing to do with security.
check "an uppercase HTTPS scheme exits zero" "0" "$code"

d="$(make_repo sec05-ftp-scheme)"
write_wrapper "$d" "$GOOD_SHA" 'ftp\://services.gradle.org/distributions/gradle-8.10.2-bin.zip'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "a non-https scheme on the official host exits nonzero" "1" "$code"
check_contains "the offending scheme is named" "$out" "ftp"

# The allowlist is the one place a publisher is trusted, so an entry that could
# never match — or that is broader than the host it names — must fail loudly
# rather than be silently ignored.
echo ""
echo "== SEC-05: the publisher allowlist is validated, not ignored =="

for bad_entry in 'https://gradle.internal.example' '*.internal.example' '.internal.example' 'localhost' 'gradle.internal.example/gradle' 'gradle.internal.example:8443' 'gradle internal example'; do
  d="$(make_repo "sec05-bad-entry-$(printf '%s' "$bad_entry" | tr -c 'a-zA-Z0-9' '-')")"
  write_wrapper "$d" "$GOOD_SHA" 'https\://gradle.internal.example/gradle.zip'
  write_allowlist "$d" "$bad_entry"
  write_wrapper_jar "$d"
  out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
  check "allowlist entry '$bad_entry' exits nonzero" "1" "$code"
  check_contains "allowlist entry '$bad_entry' is reported as malformed" "$out" "is not a bare host name"
done

d="$(make_repo sec05-allowlist-duplicate)"
write_wrapper "$d" "$GOOD_SHA" 'https\://gradle.internal.example/gradle.zip'
write_allowlist "$d" 'gradle.internal.example' 'gradle.internal.example'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "a duplicated allowlist entry exits nonzero" "1" "$code"

d="$(make_repo sec05-allowlist-comments)"
write_wrapper "$d" "$GOOD_SHA" 'https\://gradle.internal.example/gradle.zip'
printf '# a comment\n\n   gradle.internal.example   # trailing comment\n' \
  > "$d/gradle/wrapper/gradle-distribution-allowlist.txt"
write_wrapper_jar "$d"
write_verification_metadata "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "comments and blank lines in the allowlist are tolerated" "0" "$code"

d="$(make_repo sec05-allowlist-case)"
write_wrapper "$d" "$GOOD_SHA" 'https\://gradle.internal.example/gradle.zip'
write_allowlist "$d" 'GRADLE.Internal.Example'
write_wrapper_jar "$d"
write_verification_metadata "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "an allowlist entry differing only by case still matches" "0" "$code"

# Listing a parent domain must not trust its subdomains: an entry for
# example.com says nothing about who controls cdn.example.com.
d="$(make_repo sec05-parent-domain-not-subdomain)"
write_wrapper "$d" "$GOOD_SHA" 'https\://cdn.example.com/gradle.zip'
write_allowlist "$d" 'example.com'
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "listing a parent domain does not allow a subdomain" "1" "$code"

# A deleted allowlist means the trusted-publisher set is unknown. Defaulting to
# "whatever the pull request says" is the failure this control removes.
d="$(make_repo sec05-allowlist-missing)"
write_wrapper "$d" "$GOOD_SHA"
rm -f "$d/gradle/wrapper/gradle-distribution-allowlist.txt"
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "a missing publisher allowlist exits nonzero (fail closed)" "1" "$code"
check_contains "the missing allowlist is named" "$out" "gradle-distribution-allowlist.txt"

d="$(make_repo sec05-tampered-jar)"
write_wrapper "$d" "$GOOD_SHA"
write_wrapper_jar "$d" '0000000000000000000000000000000000000000000000000000000000000000'
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "wrapper JAR checksum mismatch exits nonzero" "1" "$code"
check_contains "mismatch is reported" "$out" "checksum mismatch"

d="$(make_repo sec05-no-jar-checksum)"
write_wrapper "$d" "$GOOD_SHA"
printf 'not really a jar\n' > "$d/gradle/wrapper/gradle-wrapper.jar"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "missing wrapper JAR checksum exits nonzero" "1" "$code"

d="$(make_repo sec05-no-properties)"
write_wrapper_jar "$d"
out="$(cd "$d" && bash scripts/check-gradle-supply-chain.sh 2>&1)"; code=$?
check "missing wrapper properties exits nonzero" "1" "$code"

# --- SEC-05b control 1: the pins cover the declared build inputs ------------

echo ""
echo "== SEC-05b control 1: an uncovered declared dependency fails =="

d="$(make_coverage_repo sec05b-covered)"
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "a fully covered repository exits zero" "0" "$code"
check_contains "the count of declared dependencies is measured" "$out" "covers all 2 declared external dependencies"

# The OPE-220 shape: a dependency added to a build script, pin file untouched.
# Every SEC-04/SEC-05 gate passes this — the pin file exists, parses and pins
# plenty — so only this control catches it.
d="$(make_coverage_repo sec05b-uncovered-library)"
printf '    implementation("com.example.evil:evil-lib:1.0.0")\n' >> "$d/app/build.gradle.kts"
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "an unpinned library dependency fails the gate" "1" "$code"
check_contains "the failing coordinate is named" "$out" "com.example.evil:evil-lib:1.0.0"
check_contains "the remediation names the regeneration procedure" "$out" "Maven Central and the Gradle Plugin Portal"

# The plugin-marker variant. A marker resolves during plugin resolution, before
# any job runs, which is why every job went red together on OPE-220.
d="$(make_coverage_repo sec05b-uncovered-plugin)"
python3 - "$d/gradle/libs.versions.toml" "$d/app/build.gradle.kts" <<'PY'
import sys
catalog, build = sys.argv[1], sys.argv[2]
text = open(catalog).read()
text += 'evilPlugin = { id = "com.example.evil", version = "1.0.0" }\n'
open(catalog, "w").write(text)
# `alias(...) apply false`, the root-build form for a plugin declared but not
# applied in this module.
source = open(build).read()
source = source.replace(
    '    alias(libs.plugins.thing)\n',
    '    alias(libs.plugins.thing)\n    alias(libs.plugins.evilPlugin) apply false\n',
    1,
)
open(build, "w").write(source)
PY
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "an unpinned plugin marker fails the gate" "1" "$code"
check_contains "the marker coordinate is reported in its resolved form" "$out" "com.example.evil:com.example.evil.gradle.plugin:1.0.0"

# A coordinate that IS pinned must not be reported, otherwise the control
# degrades into noise a contributor learns to ignore.
d="$(make_coverage_repo sec05b-no-false-positive)"
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check_missing "a pinned coordinate is not reported as missing" "$out" "not covered by"

# An empty pin file must not read as "everything is covered". This is the
# failure mode of a presence-only check, restated for this control.
d="$(make_coverage_repo sec05b-empty-pins)"
write_metadata_raw "$d" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <configuration>
      <verify-metadata>true</verify-metadata>
   </configuration>
   <components>
   </components>
</verification-metadata>
XML
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "an empty pin file fails the gate" "1" "$code"

# --- SEC-05b control 2: unresolvable declarations need a reason ------------

echo ""
echo "== SEC-05b control 2: an unresolvable declaration fails unless exempt =="

d="$(make_coverage_repo sec05b-unresolvable)"
printf '    implementation(compose.runtime)\n' >> "$d/app/build.gradle.kts"
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "a declaration with no readable coordinate fails" "1" "$code"
check_contains "the unresolvable declaration is named" "$out" "compose.runtime"
check_contains "the remediation names the exemption file" "$out" "verification-coverage-exemptions.txt"

d="$(make_coverage_repo sec05b-exempted)"
printf '    implementation(compose.runtime)\n' >> "$d/app/build.gradle.kts"
printf 'compose.runtime  fixture: plugin extension whose coordinates are pinned\n' \
  >> "$d/gradle/verification-coverage-exemptions.txt"
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "a registered exemption makes the gate pass" "0" "$code"

d="$(make_coverage_repo sec05b-exemption-without-reason)"
printf '    implementation(compose.runtime)\n' >> "$d/app/build.gradle.kts"
printf 'compose.runtime\n' >> "$d/gradle/verification-coverage-exemptions.txt"
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "an exemption with no reason fails" "1" "$code"
check_contains "the reasonless exemption is reported" "$out" "the reason it is exempt"

# An unreadable catalog entry is a dependency whose pin cannot be checked, so
# it is refused rather than ignored.
d="$(make_coverage_repo sec05b-unreadable-catalog)"
printf 'evil = someGradleCall("x")\n' >> "$d/gradle/libs.versions.toml"
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "a catalog entry the gate cannot read fails" "1" "$code"
check_contains "the unreadable entry is quoted back" "$out" "someGradleCall"

# An unknown alias is a dependency with no known coordinate, which is the same
# hole in a different spelling.
d="$(make_coverage_repo sec05b-unknown-alias)"
printf '    implementation(libs.does.not.exist)\n' >> "$d/app/build.gradle.kts"
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "an unknown libs alias fails the gate" "1" "$code"
check_contains "the unknown alias is reported" "$out" "does-not-exist"

# --- SEC-05b control 3: CI must not rewrite the pins -----------------------

echo ""
echo "== SEC-05b control 3: no workflow rewrites the pins =="

d="$(make_coverage_repo sec05b-no-write-flag)"
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "a repository with no regeneration flag passes" "0" "$code"
check_contains "the number of workflows checked is reported" "$out" "workflow(s) checked"

d="$(make_coverage_repo sec05b-write-flag)"
cat > "$d/.github/workflows/regen.yml" <<'YML'
name: regen
on:
  pull_request:
jobs:
  a:
    steps:
      - run: ./gradlew --write-verification-metadata sha256 detekt
YML
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "a workflow passing the regeneration flag fails" "1" "$code"
check_contains "the offending workflow is named" "$out" "regen.yml"

# An exemption only helps a workflow that is manual. The trigger list is read
# from the workflow's own `on:` block, so the flag and a `pull_request` trigger
# cannot be added together.
d="$(make_coverage_repo sec05b-exempted-regen-pr)"
cat > "$d/.github/workflows/regen.yml" <<'YML'
name: regen
on:
  pull_request:
jobs:
  a:
    steps:
      - run: ./gradlew --write-verification-metadata sha256 detekt
YML
printf '.github/workflows/regen.yml  fixture exemption\n' >> "$d/gradle/verification-regeneration-exemptions.txt"
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "an exemption on a pull_request workflow still fails" "1" "$code"
check_contains "the extra trigger is named" "$out" "pull_request"

d="$(make_coverage_repo sec05b-exempted-regen-dispatch)"
cat > "$d/.github/workflows/regen.yml" <<'YML'
name: regen
on:
  workflow_dispatch:
jobs:
  a:
    steps:
      - run: ./gradlew --write-verification-metadata sha256 detekt
YML
printf '.github/workflows/regen.yml  fixture exemption\n' >> "$d/gradle/verification-regeneration-exemptions.txt"
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "an exemption on a dispatch-only workflow passes" "0" "$code"
check_contains "the dispatch-only exemption is reported" "$out" "manual dispatch only"

# The trigger reader must find the `on:` block rather than reading the first
# top-level key as the end of it. A `name:` line before `on:` used to make every
# workflow look trigger-less.
d="$(make_coverage_repo sec05b-dispatch-with-name-first)"
cat > "$d/.github/workflows/regen.yml" <<'YML'
name: regen

on:
  workflow_dispatch:

jobs:
  a:
    steps:
      - run: ./gradlew --write-verification-metadata sha256 detekt
YML
printf '.github/workflows/regen.yml  fixture exemption\n' >> "$d/gradle/verification-regeneration-exemptions.txt"
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "a dispatch-only workflow with a leading name: still passes" "0" "$code"

# A comment naming the flag must not trip the control — a control that flags
# documentation pushes contributors toward exempting real workflows.
d="$(make_coverage_repo sec05b-flag-in-comment)"
cat > "$d/.github/workflows/a.yml" <<'YML'
name: x
# This workflow must never pass --write-verification-metadata.
on:
  workflow_dispatch:
jobs:
  a:
    steps:
      - run: bash scripts/check-verification-metadata-coverage.sh
YML
out="$(cd "$d" && bash scripts/check-verification-metadata-coverage.sh 2>&1)"; code=$?
check "the flag named only in a comment passes" "0" "$code"

# --- the real repository must satisfy every gate ---------------------------

echo ""
echo "== The real repository satisfies every gate =="

out="$(cd "$ROOT" && bash "$PINNING" 2>&1)"; code=$?
check "real repository passes the SEC-04 gate" "0" "$code"

out="$(cd "$ROOT" && bash "$GRADLE_GATE" 2>&1)"; code=$?
check "real repository passes the SEC-05 gate" "0" "$code"
# The real file, not a fixture: it is the largest document the parser has to
# read, and a count that disagrees with the repository would mean the gate is
# describing something other than what is committed.
check_contains "the real metadata is measured, not assumed" "$out" "SHA-256 checksum(s) across"
check_missing "the real repository does not trip the vacuous guard" "$out" "not one SHA-256"
check_contains "the real distribution publisher is identified" "$out" "official Gradle publisher (services.gradle.org)"

out="$(cd "$ROOT" && bash "$COVERAGE_GATE" 2>&1)"; code=$?
check "real repository passes the SEC-05b gate" "0" "$code"
check_contains "the real repository's declared dependencies are counted" "$out" "covers all"
check_missing "the real repository has no uncovered dependency" "$out" "not covered by"
check_contains "the real repository has no CI pin writer" "$out" "no workflow under"

echo ""
echo "== summary: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ] || exit 1
exit 0
