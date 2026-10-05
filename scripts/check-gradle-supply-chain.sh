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
# every pull request. Controls 2 and 4 parse their input with perl (standard
# library only, no network) because a substring match cannot tell a document that
# pins an artifact from one that pins nothing, nor a URL whose publisher is
# Gradle from one whose publisher is an attacker. The parsing lives in
# scripts/lib/sec05-parse.pl; perl must exist on the runner and the gate fails
# closed when it does not. See OPE-257 for why this is perl and not python3.
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

# Controls 2 and 4 both parse their input, so both need the parser library.
# Checked once, here, so the runner reports a missing dependency as the single
# actionable cause instead of two dependent-looking failures.
#
# OPE-257: this was python3, which the digest-pinned eclipse-temurin image used
# by `T4 static scan` does not carry (ADR 0007). The image is not changed — the
# digest pin is the supply-chain control — so the parsing moved to perl, which
# the base image does carry. See scripts/lib/sec05-parse.pl.
SEC05_LIB="scripts/lib/sec05-parse.pl"
have_parser=1
command -v perl >/dev/null 2>&1 || have_parser=0
[ -r "$SEC05_LIB" ] || have_parser=0
if [ "$have_parser" -eq 0 ]; then
  fail_with "perl and $SEC05_LIB are required to verify the distributionUrl publisher and to parse $VERIFICATION_METADATA, and at least one is missing; the SEC-05 publisher and dependency-checksum controls cannot be verified, so this gate fails closed rather than skipping them."
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
  perl - "$1" "$2" "$3" <<'PERL'
use strict;
use warnings;
require "./scripts/lib/sec05-parse.pl";

my ($path, $allowlist, $official) = @ARGV;

sub refuse {
    print "$_[0]\n";
    exit 1;
}

sub read_allowed_hosts {
    my ($p) = @_;
    open(my $fh, '<', $p) or refuse("$p cannot be read ($!); the set of trusted Gradle publishers is unknown, so distributionUrl cannot be checked (SEC-05).");
    my @hosts;
    my $n = 0;
    while (my $line = <$fh>) {
        $n++;
        # Drop a trailing comment. `.` does not match the newline, so the
        # newline has to be part of the pattern or `#` to end-of-line never
        # strips and every comment line looks like a malformed host.
        $line =~ s/#.*\n?//;
        $line =~ s/\A\s+//;
        $line =~ s/\s+\z//;
        next if $line eq '';
        my $candidate = lc($line);
        refuse("$p:$n is not a bare host name: '$line'. List the host only - no scheme, no path, no port, no wildcard (SEC-05).")
            unless sec05_host_is_bare($candidate);
        refuse("$p:$n lists $candidate twice (SEC-05).")
            if grep { $_ eq $candidate } @hosts;
        push @hosts, $candidate;
    }
    close($fh);
    return \@hosts;
}

sub read_distribution_url {
    my ($p) = @_;
    open(my $fh, '<', $p) or refuse("$p cannot be read ($!) (SEC-05).");
    my ($url, $seen);
    while (my $line = <$fh>) {
        $line =~ s/\s+\z//;
        # Capture the value now: the two substitutions below reset $1, so
        # reading $1 after them silently yields the wrong string.
        next unless $line =~ /\A\s*(distributionUrl)\s*=\s*(.*)\z/;
        my ($key, $value) = ($1, $2);
        next unless $key eq 'distributionUrl';
        # Last assignment wins, matching java.util.Properties.
        $url = $value;
        $seen = 1;
    }
    close($fh);
    refuse("$p declares no distributionUrl; there is no Gradle distribution to verify (SEC-05).") unless $seen;
    $url = '' unless defined $url;
    # Gradle escapes `:` and `=` in .properties values; undo that the same way
    # the wrapper resolves them.
    $url =~ s/\\//g;
    $url =~ s/\A\s+//;
    $url =~ s/\s+\z//;
    return $url;
}

my $allowed = read_allowed_hosts($allowlist);
my $url = read_distribution_url($path);

my ($scheme, $host, $upath) = sec05_split_url($url);

if (!defined $scheme) {
    refuse("$path declares distributionUrl='$url', which is not a simple absolute URL naming a bare host (SEC-05).");
}
if ($scheme ne 'https') {
    refuse("distributionUrl uses the '$scheme' scheme, not https ($url); a downgrade would let an on-path attacker rewrite the Gradle distribution (SEC-05).");
}
if (!defined $host || $host eq '') {
    refuse("distributionUrl names no host ($url); nothing identifies the Gradle publisher (SEC-05).");
}
# A trailing dot is the same DNS name in another spelling. Reject it rather
# than strip it: an allowlist is a decision someone reviewed, and a form of it
# that does not match the reviewed form must not pass by accident.
if ($host =~ /\.\z/) {
    refuse("distributionUrl host '$host' ends in a trailing dot (SEC-05). Use the plain host name so it matches the reviewed allowlist entry.");
}

if ($host eq $official) {
    # Gradle publishes every official distribution under /distributions/ as
    # gradle-<version>-{bin,all}.zip. Requiring that shape keeps a valid host
    # from being pointed at an unrelated path on it.
    unless ($upath =~ m{\A/distributions/gradle-[0-9][0-9A-Za-z._-]*\.zip\z}) {
        refuse("distributionUrl host is the official $official but its path '$upath' is not /distributions/gradle-<version>-{bin,all}.zip (SEC-05).");
    }
    print "distributionUrl points at the official Gradle publisher ($official), path $upath.\n";
    exit 0;
}

if (grep { $_ eq $host } @$allowed) {
    print "distributionUrl points at allowlisted mirror host $host (listed in $allowlist).\n";
    exit 0;
}

my $count = scalar(@$allowed);
my $plural = $count == 1 ? 'y' : 'ies';
refuse("distributionUrl host '$host' is not a trusted Gradle publisher. Allowed: the official $official, or a host listed in $allowlist (currently $count entr$plural). "
    . "An unlisted host means the distribution comes from whoever controls that name, and editing distributionUrl together with distributionSha256Sum in one pull request would make this gate approve the attacker's Gradle (SEC-05).");
PERL
}

if [ "$have_parser" -eq 0 ]; then
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
# scripts/lib/sec05-parse.pl does that parse (no third-party module, no
# network). It is required — the same library control 2 needs — and its absence
# is reported once, above, before either control runs.
verify_metadata() {
  perl -I scripts/lib - "$1" <<'PERL'
use strict;
use warnings;
require "./scripts/lib/sec05-parse.pl";

my $path = $ARGV[0];
my %CHECKSUM_TAGS = map { $_ => 1 } qw(sha256 sha1 md5);
my %SIGNATURE_TAGS = map { $_ => 1 } qw(trusting-key trusted-key);

sub refuse {
    print "$_[0]\n";
    exit 1;
}

# Read raw bytes, deliberately NOT with `:encoding(UTF-8)`. That layer lives in
# PerlIO, and the pinned CI image carries perl-base only, so asking for it fails
# with "Can't locate PerlIO.pm" and the gate stops on the environment again —
# the same class of failure this issue exists to remove. Byte handling is correct
# here: every element name, attribute name and tag this control compares is
# ASCII, and sec05_parse_xml strips the UTF-8 BOM itself.
open(my $fh, '<', $path)
    or refuse("$path cannot be read ($!) (SEC-05).");
local $/;
my $src = <$fh>;
close($fh);

my ($root, $err) = sec05_parse_xml($src);
refuse("$path is not well-formed XML ($err); Gradle cannot read it, so it pins nothing (SEC-05).") if $err;

if ($root->{name} ne 'verification-metadata') {
    refuse("$path has root element <$root->{name}>, not <verification-metadata>: this is not Gradle dependency-verification metadata (SEC-05).");
}

my @sections = grep { $_->{name} eq 'components' } @{ $root->{kids} };
refuse("$path declares no <components> section; it pins no artifact checksum at all (SEC-05).") unless @sections;

my @components;
for my $section (@sections) {
    push @components, grep { $_->{name} eq 'component' } @{ $section->{kids} };
}
refuse("$path has an empty <components> section; it pins no component, so dependency verification proves nothing (SEC-05).")
    unless @components;

my ($sha256_count, $artifact_count) = (0, 0);
my @unpinned;
for my $component (@components) {
    my $group   = defined $component->{attrs}{group}   ? $component->{attrs}{group}   : '?';
    my $name    = defined $component->{attrs}{name}    ? $component->{attrs}{name}    : '?';
    my $version = defined $component->{attrs}{version} ? $component->{attrs}{version} : '?';
    for my $artifact (@{ $component->{kids} }) {
        next unless $artifact->{name} eq 'artifact';
        $artifact_count++;
        my %children = map { $_->{name} => 1 } @{ $artifact->{kids} };
        $sha256_count++ if $children{sha256};
        unless ($children{sha256} || $children{sha1} || $children{md5}
            || $children{'trusting-key'} || $children{'trusted-key'}) {
            my $aname = defined $artifact->{attrs}{name} ? $artifact->{attrs}{name} : '?';
            push @unpinned, "$group:$name:$version/$aname";
        }
    }
}

if (@unpinned) {
    my $shown = join(', ', @unpinned[0 .. ($#unpinned > 4 ? 4 : $#unpinned)]);
    my $rest = @unpinned > 5 ? sprintf(' (+%d more)', scalar(@unpinned) - 5) : '';
    refuse("$path leaves " . scalar(@unpinned) . " artifact(s) with neither a checksum nor a signature entry: $shown$rest (SEC-05).");
}

if ($sha256_count == 0) {
    refuse("$path pins $artifact_count artifact(s) but not one SHA-256 checksum; without SHA-256 an artifact substitution is cheap to arrange (SEC-05).");
}

for my $configuration (@{ $root->{kids} }) {
    next unless $configuration->{name} eq 'configuration';
    for my $flag (@{ $configuration->{kids} }) {
        next unless $flag->{name} eq 'verify-metadata';
        my $text = $flag->{text};
        $text = '' unless defined $text;
        $text =~ s/\A\s+//;
        $text =~ s/\s+\z//;
        unless (lc($text) eq 'true') {
            refuse("$path sets <verify-metadata> to '$text', which turns POM metadata verification off (SEC-05).");
        }
    }
}

print "gradle/verification-metadata.xml pins $sha256_count SHA-256 checksum(s) across " . scalar(@components) . " component(s) / $artifact_count artifact(s).\n";
PERL
}

if [ "$have_parser" -eq 0 ]; then
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
