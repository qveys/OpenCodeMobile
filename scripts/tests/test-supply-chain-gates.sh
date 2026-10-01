#!/usr/bin/env bash
# scripts/tests/test-supply-chain-gates.sh
# Local tests for the SEC-04 / SEC-05 supply-chain gates.
#
# Covers action pinning (SEC-04) — repository actions, sub-path actions,
# reusable workflows and container actions — and the four Gradle toolchain
# controls (SEC-05): distributionSha256Sum, https distributionUrl, the
# committed gradle-wrapper.jar digest, and the now-mandatory
# gradle/verification-metadata.xml dependency-checksum metadata.
#
# Every gate is checked against a fixture that used to make it pass or fail,
# not only against the happy path: a control that cannot be shown to reject a
# defective input is not evidence of anything.
#
# Runs scripts/check-workflow-action-pinning.sh and
# scripts/check-gradle-supply-chain.sh against synthetic fixtures in a temp
# directory. It never contacts GitHub or any artifact repository, and never
# touches the real repository files.
#
# Run: bash scripts/tests/test-supply-chain-gates.sh

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PINNING="$ROOT/scripts/check-workflow-action-pinning.sh"
GRADLE_GATE="$ROOT/scripts/check-gradle-supply-chain.sh"

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
  cp "$PINNING" "$GRADLE_GATE" "$dir/scripts/"
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

write_wrapper() { # dir [sha_sum_line] [url]
  local dir="$1" sha="${2:-}" url="${3:-https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip}"
  {
    printf 'distributionBase=GRADLE_USER_HOME\n'
    printf 'distributionUrl=%s\n' "$url"
    [ -n "$sha" ] && printf '%s\n' "$sha"
    printf 'networkTimeout=10000\n'
    printf 'validateDistributionUrl=true\n'
  } > "$dir/gradle/wrapper/gradle-wrapper.properties"
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
check "no python3 on PATH exits nonzero (fail closed)" "1" "$code"
check_contains "the missing interpreter is named" "$out" "python3 is required"

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

# --- the real repository must satisfy both gates ---------------------------

echo ""
echo "== The real repository satisfies both gates =="

out="$(cd "$ROOT" && bash "$PINNING" 2>&1)"; code=$?
check "real repository passes the SEC-04 gate" "0" "$code"

out="$(cd "$ROOT" && bash "$GRADLE_GATE" 2>&1)"; code=$?
check "real repository passes the SEC-05 gate" "0" "$code"
# The real file, not a fixture: it is the largest document the parser has to
# read, and a count that disagrees with the repository would mean the gate is
# describing something other than what is committed.
check_contains "the real metadata is measured, not assumed" "$out" "SHA-256 checksum(s) across"
check_missing "the real repository does not trip the vacuous guard" "$out" "not one SHA-256"

echo ""
echo "== summary: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ] || exit 1
exit 0
