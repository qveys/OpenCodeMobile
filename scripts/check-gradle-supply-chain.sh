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
#   2. `distributionUrl` names a publisher this repository trusts: the official
#      Gradle host, or a host explicitly allowlisted in
#      `gradle/wrapper/gradle-distribution-allowlist.txt` (OPE-216).
#
#      Controls 1 and 2 are what bind the digest to a publisher, and they are
#      only useful together. The digest says "these bytes"; control 2 says "the
#      publisher of these bytes is Gradle". Checked alone, neither constrains the
#      other: a pull request that edits both lines at once
#      (attacker host + the digest of the attacker's own archive) satisfies both,
#      and the wrapper then downloads and runs the attacker's Gradle. That is
#      threat T10, and it is why "https" is not sufficient on its own — requiring
#      TLS only constrains the transport, not who is at the other end, so an
#      attacker who can edit the file simply uses https and loses nothing. The
#      URL is parsed, not prefix-matched, so `https://services.gradle.org@evil`
#      and `https://evil/?x=https://services.gradle.org` do not pass.
#
#      A redirect to another host is still covered: the wrapper follows it, but
#      the bytes then have to match `distributionSha256Sum`, which control 2 has
#      bound to a trusted publisher.
#
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
# every pull request. Controls 2 and 4 parse their input with python3 (standard
# library only, no network) because a substring match cannot tell a document that
# pins an artifact from one that pins nothing, nor a URL whose publisher is
# Gradle from one whose publisher is an attacker. python3 must exist on the
# runner and the gate fails closed when it does not.
#
# Exit 0 when the toolchain is pinned, exit 1 otherwise.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PROPERTIES="gradle/wrapper/gradle-wrapper.properties"
WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
CHECKSUM_FILE="gradle/wrapper/gradle-wrapper.jar.sha256"
ALLOWLIST="gradle/wrapper/gradle-distribution-allowlist.txt"
VERIFICATION_METADATA="gradle/verification-metadata.xml"
OFFICIAL_HOST="services.gradle.org"

fail=0

fail_with() {
  printf '::error::%s\n' "$1"
  printf '  %s\n' "$1"
  fail=1
}

# Controls 2 and 4 both parse their input, so both need python3. Checked once,
# here, so the runner reports a missing interpreter as the single actionable
# cause instead of two dependent-looking failures.
have_python=0
command -v python3 >/dev/null 2>&1 && have_python=1
if [ "$have_python" -eq 0 ]; then
  fail_with "python3 is required to verify the distributionUrl publisher and to parse $VERIFICATION_METADATA, and is not on PATH; the SEC-05 publisher and dependency-checksum controls cannot be verified, so this gate fails closed rather than skipping them. Install it (Debian/Ubuntu: apt-get install -y python3)."
fi

printf 'Checking Gradle toolchain integrity controls (SEC-05)...\n'

# --- 1. distributionSha256Sum + 2. distributionUrl publisher ---------------

if [ ! -f "$PROPERTIES" ]; then
  fail_with "Missing $PROPERTIES; the Gradle toolchain is not pinned at all."
elif ! grep -q '^[[:space:]]*distributionSha256Sum[[:space:]]*=[[:space:]]*[0-9a-f]\{64\}[[:space:]]*$' "$PROPERTIES"; then
  fail_with "$PROPERTIES has no valid distributionSha256Sum; the downloaded Gradle distribution is not verified (SEC-05). Add: ./gradlew wrapper --gradle-version=<v> --gradle-distribution-sha256-sum=<sha256>"
fi

# Control 2. `distributionUrl` must name a publisher this repository trusts.
#
# The previous rule was `case "$url" in https://*)`. That tests the transport,
# not the publisher, so it accepted any https host: a pull request setting
# `distributionUrl=https\://attacker.example/…` alongside
# `distributionSha256Sum=<sha256 of the attacker's own zip>` passed, and
# `./gradlew` then downloaded and executed the attacker's Gradle — threat T10,
# and the one SEC-05 exists to prevent. Both halves of that edit are
# individually well-formed, so no per-property check can catch it; only binding
# the host to a trusted publisher can.
#
# The check parses the URL rather than prefix-matching it, because a prefix test
# is trivially impersonated: `https://services.gradle.org@evil.example/x` (the
# host is userinfo, the real host is evil.example) and
# `https://evil.example/?next=https://services.gradle.org` both begin with the
# approved string while naming a different publisher.
#
# Gradle legitimately supports mirrors, so a mirror is not a failure — but it is
# an *explicit, reviewed* decision recorded in $ALLOWLIST rather than whatever a
# pull request happens to write. The list is empty by default: a host reaches it
# through a normal reviewed commit, which is the friction a supply-chain gate is
# supposed to have.
check_distribution_url() {
  python3 - "$1" "$2" "$3" <<'PY'
import re
import sys

PATH, ALLOWLIST, OFFICIAL = sys.argv[1], sys.argv[2], sys.argv[3]

# A host: at least two dot-separated labels of [a-z0-9-], no wildcards, no
# scheme, no port, no path, no userinfo. Anything else is rejected rather than
# silently ignored, so an allowlist entry that could never match fails loudly
# instead of quietly approving nothing.
HOST_RE = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")


def refuse(message):
    print(message)
    sys.exit(1)


def read_allowed_hosts(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            raw_lines = handle.read().splitlines()
    except OSError as exc:
        # Fail closed. An unreadable or absent allowlist means the publisher
        # policy cannot be established, and defaulting to "trust the PR" is
        # exactly the failure mode this control exists to remove.
        refuse("%s cannot be read (%s); the set of trusted Gradle publishers is unknown, so distributionUrl cannot be checked (SEC-05)." % (path, exc))

    hosts = []
    for number, line in enumerate(raw_lines, 1):
        entry = line.split("#", 1)[0].strip()
        if not entry:
            continue
        candidate = entry.lower()
        if not HOST_RE.match(candidate):
            refuse(
                "%s:%d is not a bare host name: %r. List the host only — no scheme, no path, no port, no wildcard (SEC-05)."
                % (path, number, entry)
            )
        if candidate in hosts:
            refuse("%s:%d lists %s twice (SEC-05)." % (path, number, candidate))
        hosts.append(candidate)
    return hosts


def read_distribution_url(path):
    # Gradle writes these as a .properties file, so `:` and `=` arrive escaped
    # (`https\://…`). Reading the raw line and unescaping reproduces what the
    # wrapper itself resolves; a value split across lines cannot happen here
    # because the property is matched on a single line.
    url = ""
    seen = False
    try:
        with open(path, "r", encoding="utf-8") as handle:
            lines = handle.read().splitlines()
    except OSError as exc:
        refuse("%s cannot be read (%s) (SEC-05)." % (path, exc))
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("distributionUrl") and "=" in stripped:
            key = stripped.split("=", 1)[0].strip()
            if key == "distributionUrl":
                # Last assignment wins, matching java.util.Properties.
                url = stripped.split("=", 1)[1].strip()
                seen = True
    if not seen:
        refuse("%s declares no distributionUrl; there is no Gradle distribution to verify (SEC-05)." % path)
    return url.replace("\\", "")


allowed = read_allowed_hosts(ALLOWLIST)
url = read_distribution_url(PATH)

# urlsplit gives the parsed authority: it lowercases the scheme, and exposes
# netloc's userinfo, host and port separately, so the host below is the host
# that would actually be connected to.
from urllib.parse import urlsplit

parts = urlsplit(url)
scheme = parts.scheme.lower()

if not scheme:
    refuse("%s declares distributionUrl=%r, which has no URL scheme (SEC-05)." % (PATH, url))
if scheme != "https":
    refuse(
        "distributionUrl uses the %r scheme, not https (%s); a downgrade would let an on-path attacker rewrite the Gradle distribution (SEC-05)."
        % (scheme, url)
    )

try:
    host = parts.hostname
except ValueError as exc:
    refuse("distributionUrl has an unparseable authority (%s): %s (SEC-05)." % (url, exc))
if not host:
    refuse("distributionUrl names no host (%s); nothing identifies the Gradle publisher (SEC-05)." % url)
host = host.lower()

# A trailing dot is the same DNS name in another spelling. Reject it rather than
# strip it: an allowlist is a decision someone reviewed, and a form of it that
# does not match the reviewed form must not pass by accident.
if host.endswith("."):
    refuse(
        "distributionUrl host %r ends in a trailing dot (SEC-05). Use the plain host name so it matches the reviewed allowlist entry."
        % (host,)
    )

if host == OFFICIAL:
    # Gradle publishes every official distribution under /distributions/ as
    # gradle-<version>-{bin,all}.zip. Requiring that shape keeps a valid host
    # from being pointed at an unrelated path on it.
    if not re.match(r"^/distributions/gradle-[0-9][0-9A-Za-z._-]*\.zip$", parts.path):
        refuse(
            "distributionUrl host is the official %s but its path %r is not /distributions/gradle-<version>-{bin,all}.zip (SEC-05)."
            % (OFFICIAL, parts.path)
        )
    print(
        "distributionUrl points at the official Gradle publisher (%s), path %s." % (OFFICIAL, parts.path)
    )
    sys.exit(0)

if host in allowed:
    print(
        "distributionUrl points at allowlisted mirror host %s (listed in %s)." % (host, ALLOWLIST)
    )
    sys.exit(0)

refuse(
    "distributionUrl host %r is not a trusted Gradle publisher. Allowed: the official %s, or a host listed in %s (currently %d entr%s). "
    "An unlisted host means the distribution comes from whoever controls that name, and editing distributionUrl together with distributionSha256Sum in one pull request would make this gate approve the attacker's Gradle (SEC-05)."
    % (host, OFFICIAL, ALLOWLIST, len(allowed), "y" if len(allowed) == 1 else "ies")
)
PY
}

if [ "$have_python" -eq 0 ]; then
  :
elif [ ! -f "$PROPERTIES" ]; then
  :
else
  if url_report="$(check_distribution_url "$PROPERTIES" "$ALLOWLIST" "$OFFICIAL_HOST" 2>&1)"; then
    printf '  [ok] %s\n' "$url_report"
  else
    while IFS= read -r problem; do
      [ -n "$problem" ] && fail_with "$problem"
    done <<< "$url_report"
  fi
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
# network). It is required — the same interpreter control 2 needs — and its
# absence is reported once, above, before either control runs.
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

if [ "$have_python" -eq 0 ]; then
  :
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

printf 'OK: Gradle distribution comes from a trusted publisher, and the transport, wrapper JAR and dependency checksums are integrity-pinned (SEC-05).\n'
exit 0
