#!/usr/bin/env bash
# scripts/check-gradle-supply-chain.sh
#
# SEC-05 — CI gate: fail when the Gradle build toolchain is not integrity-pinned.
#
# Four controls, checked independently:
#   1. `distributionSha256Sum` is present in gradle/wrapper/gradle-wrapper.properties.
#      Without it the wrapper downloads `gradle-<v>-bin.zip` over TLS and trusts
#      whatever bytes arrive; the TLS channel protects the transfer but does not
#      prove the archive is the one Gradle published.
#   2. `distributionUrl` is https, so a downgrade to plain http (which would let
#      an on-path attacker rewrite the archive) cannot pass review.
#   3. gradle/wrapper/gradle-wrapper.jar matches its committed SHA-256. The JAR
#      is executable code that every build runs before Gradle even starts, and it
#      is not covered by the distribution checksum.
#   4. gradle/verification-metadata.xml is present, so every resolved artifact
#      (including transitive OkHttp/Netty/etc.) is pinned to a SHA-256 and a
#      substitution from Maven Central becomes detectable. It is committed and
#      required; regenerating it needs a JDK and Maven Central access. See
#      docs/SECURITY-REVIEW.md SEC-05.
#
# Deliberately independent of the Gradle build (no JDK required) so it can run on
# every pull request.
#
# Exit 0 when the toolchain is pinned, exit 1 otherwise.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PROPERTIES="gradle/wrapper/gradle-wrapper.properties"
WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
CHECKSUM_FILE="gradle/wrapper/gradle-wrapper.jar.sha256"

fail=0

fail_with() {
  printf '::error::%s\n' "$1"
  printf '  %s\n' "$1"
  fail=1
}

printf 'Checking Gradle toolchain integrity controls (SEC-05)...\n'

# --- 1/2. distributionSha256Sum + https distributionUrl --------------------

if [ ! -f "$PROPERTIES" ]; then
  fail_with "Missing $PROPERTIES; the Gradle toolchain is not pinned at all."
elif ! grep -q '^[[:space:]]*distributionSha256Sum[[:space:]]*=[[:space:]]*[0-9a-f]\{64\}[[:space:]]*$' "$PROPERTIES"; then
  fail_with "$PROPERTIES has no valid distributionSha256Sum; the downloaded Gradle distribution is not verified (SEC-05). Add: ./gradlew wrapper --gradle-version=<v> --gradle-distribution-sha256-sum=<sha256>"
fi

if [ -f "$PROPERTIES" ]; then
  distribution_url="$(grep -E '^[[:space:]]*distributionUrl[[:space:]]*=' "$PROPERTIES" | tail -n 1 | sed -e 's/^[^=]*=[[:space:]]*//' -e 's/\\//g' || true)"
  case "$distribution_url" in
    https://*) ;;
    "") fail_with "$PROPERTIES declares no distributionUrl." ;;
    *) fail_with "distributionUrl is not https ($distribution_url); a downgrade would let an on-path attacker rewrite the Gradle distribution (SEC-05)." ;;
  esac
fi

# --- 3. gradle-wrapper.jar checksum ---------------------------------------

if [ ! -f "$WRAPPER_JAR" ]; then
  fail_with "Missing $WRAPPER_JAR; nothing to verify."
elif [ ! -f "$CHECKSUM_FILE" ]; then
  fail_with "Missing $CHECKSUM_FILE; gradle-wrapper.jar has no committed checksum even though it executes before every build (SEC-05). Create it with: sha256sum $WRAPPER_JAR > $CHECKSUM_FILE"
else
  expected="$(awk '{print $1; exit}' "$CHECKSUM_FILE" | tr -d '[:space:]')"
  actual="$(sha256sum "$WRAPPER_JAR" | awk '{print $1}')"
  if ! printf '%s' "$expected" | grep -Eq '^[0-9a-f]{64}$'; then
    fail_with "$CHECKSUM_FILE does not contain a valid 64-character SHA-256 digest."
  elif [ "$expected" != "$actual" ]; then
    fail_with "gradle-wrapper.jar checksum mismatch: expected $expected, actual $actual. The committed wrapper JAR was modified (SEC-05)."
  else
    printf '  [ok] gradle-wrapper.jar matches its committed SHA-256 (%s)\n' "$actual"
  fi
fi

# --- 4. dependency verification metadata -----------------------------------

VERIFICATION_METADATA="gradle/verification-metadata.xml"

if [ ! -f "$VERIFICATION_METADATA" ]; then
  fail_with "Missing $VERIFICATION_METADATA; transitive dependency resolution is not pinned to checksums (SEC-05). Generate it with: ./gradlew --write-verification-metadata sha256 <task>"
elif ! grep -q '<components>' "$VERIFICATION_METADATA"; then
  fail_with "$VERIFICATION_METADATA has no <components> section; it does not pin any artifact checksums (SEC-05)."
else
  printf '  [ok] gradle/verification-metadata.xml is present and pins resolved dependency checksums.\n'
fi

if [ "$fail" -ne 0 ]; then
  printf '\nFAIL: Gradle toolchain integrity controls are not satisfied (SEC-05).\n'
  exit 1
fi

printf 'OK: Gradle distribution, transport, wrapper JAR and dependency checksums are integrity-pinned (SEC-05).\n'
exit 0
