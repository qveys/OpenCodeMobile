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
#   4. gradle/verification-metadata.xml is present AND actually pins artifacts:
#      at least one component carrying at least one SHA-256, no artifact left
#      without a checksum or a signature entry, and <verify-metadata> not
#      downgraded to false. A presence check cannot tell "pins 1609 artifacts"
#      from "pins none", so this one is parsed rather than grepped. It is
#      committed and required; regenerating it needs a JDK and Maven Central
#      access. See docs/SECURITY-REVIEW.md SEC-05.
#
# Deliberately independent of the Gradle build (no JDK required) so it can run on
# every pull request. Control 4 parses XML with python3 (standard library only,
# no network); python3 must exist on the runner and the gate fails closed when
# it does not.
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

# This control used to be `grep -q '<components>'`. A substring test is not a
# parse: it cannot tell a document that pins 1609 artifacts from one that pins
# none. An empty <components> element — or a file whose only occurrence of the
# string is inside an XML comment — passed, and the gate then printed
# "[ok] ... pins resolved dependency checksums". A one-line deletion of every
# <component> block would therefore have turned dependency verification off
# while the required SEC-05 check stayed green. The document is now parsed and
# its content measured: at least one component, at least one SHA-256 checksum,
# no artifact left without either a checksum or a signature entry, and
# <verify-metadata> not downgraded to false.
#
# python3 does that parse with the standard library (no third-party module, no
# network). It is required, and it fails closed: a runner without it reports the
# control as unverifiable rather than skipping it.
verify_metadata() {
  python3 - "$1" <<'PY'
import sys
import xml.etree.ElementTree as ET

PATH = sys.argv[1]
CHECKSUM_TAGS = frozenset(("sha256", "sha1", "md5"))
SIGNATURE_TAGS = frozenset(("trusting-key", "trusted-key"))


def local(tag):
    """Element name without the XML namespace Gradle declares."""
    return tag.rsplit("}", 1)[-1]


def refuse(message):
    print(message)
    sys.exit(1)


try:
    root = ET.parse(PATH).getroot()
except ET.ParseError as exc:
    refuse("%s is not well-formed XML (%s); Gradle cannot read it, so it pins nothing (SEC-05)." % (PATH, exc))
except OSError as exc:
    refuse("%s cannot be read (%s) (SEC-05)." % (PATH, exc))

if local(root.tag) != "verification-metadata":
    refuse("%s has root element <%s>, not <verification-metadata>: this is not Gradle dependency-verification metadata (SEC-05)." % (PATH, local(root.tag)))

components_sections = [child for child in root if local(child.tag) == "components"]
if not components_sections:
    refuse("%s declares no <components> section; it pins no artifact checksum at all (SEC-05)." % PATH)

components = [
    component
    for section in components_sections
    for component in section
    if local(component.tag) == "component"
]
if not components:
    refuse("%s has an empty <components> section; it pins no component, so dependency verification proves nothing (SEC-05)." % PATH)

sha256_count = 0
artifact_count = 0
unpinned = []
for component in components:
    for artifact in component:
        if local(artifact.tag) != "artifact":
            continue
        artifact_count += 1
        children = frozenset(local(child.tag) for child in artifact)
        sha256_count += len([child for child in artifact if local(child.tag) == "sha256"])
        if not children & (CHECKSUM_TAGS | SIGNATURE_TAGS):
            unpinned.append(
                "%s:%s:%s/%s"
                % (
                    component.get("group", "?"),
                    component.get("name", "?"),
                    component.get("version", "?"),
                    artifact.get("name", "?"),
                )
            )

if unpinned:
    shown = ", ".join(unpinned[:5])
    rest = " (+%d more)" % (len(unpinned) - 5) if len(unpinned) > 5 else ""
    refuse(
        "%s leaves %d artifact(s) with neither a checksum nor a signature entry: %s%s (SEC-05)."
        % (PATH, len(unpinned), shown, rest)
    )

if sha256_count == 0:
    refuse(
        "%s pins %d artifact(s) but not one SHA-256 checksum; without SHA-256 an artifact substitution is cheap to arrange (SEC-05)."
        % (PATH, artifact_count)
    )

for configuration in root:
    if local(configuration.tag) != "configuration":
        continue
    for flag in configuration:
        if local(flag.tag) == "verify-metadata" and (flag.text or "").strip().lower() != "true":
            refuse(
                "%s sets <verify-metadata> to '%s', which turns POM metadata verification off (SEC-05)."
                % (PATH, (flag.text or "").strip())
            )

print(
    "gradle/verification-metadata.xml pins %d SHA-256 checksum(s) across %d component(s) / %d artifact(s)."
    % (sha256_count, len(components), artifact_count)
)
PY
}

if ! command -v python3 >/dev/null 2>&1; then
  fail_with "python3 is required to parse $VERIFICATION_METADATA and is not on PATH; the SEC-05 dependency-checksum control cannot be verified. Install it (Debian/Ubuntu: apt-get install -y python3)."
elif [ ! -f "$VERIFICATION_METADATA" ]; then
  fail_with "Missing $VERIFICATION_METADATA; transitive dependency resolution is not pinned to checksums (SEC-05). Generate it with: ./gradlew --write-verification-metadata sha256 <task>"
else
  # On success the parser prints one `pinned N checksum(s)` line. On failure it
  # prints one `refuse` line per violated rule and exits non-zero.
  if metadata_report="$(verify_metadata "$VERIFICATION_METADATA" 2>&1)"; then
    printf '  [ok] %s\n' "$metadata_report"
  else
    while IFS= read -r problem; do
      [ -n "$problem" ] && fail_with "$problem"
    done <<< "$metadata_report"
  fi
fi

if [ "$fail" -ne 0 ]; then
  printf '\nFAIL: Gradle toolchain integrity controls are not satisfied (SEC-05).\n'
  exit 1
fi

printf 'OK: Gradle distribution, transport, wrapper JAR and dependency checksums are integrity-pinned (SEC-05).\n'
exit 0
