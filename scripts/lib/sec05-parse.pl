# SEC-05 parsing helpers — perl only, no CPAN, no network, no python3.
#
# This is a library, loaded with `require` from check-gradle-supply-chain.sh. It
# is deliberately NOT given a shebang: it is never executed directly, and a
# shebang here would make scripts/check-executable-bits.sh require mode 100755,
# which the GitHub API commit path does not transport (GIT.md §5).
#
# OPE-257. The `T4 static scan` job runs in the digest-pinned
# eclipse-temurin image (ADR 0007). That image carries no python3, so both
# SEC-05 controls used to stop on "python3 is required" — a required check
# reporting the environment instead of an integrity problem.
#
# The image is not changed and the gate is not moved off it: the digest pin is
# the supply-chain control, and OPE-212's isolation is what makes the runner
# host safe to share. So the parsing moves to perl, which the base image does
# carry. Rewriting this in awk was rejected on purpose: ~220 lines of XML and
# URL parsing expressed as awk substitutions is the usual way a supply-chain
# gate gets silently bypassed by one bad escape. perl keeps real parsing
# (nested elements, quoted attributes, entity decoding) available in the image.
#
# Security posture is unchanged and fail-closed:
#   * anything unparseable is refused, never counted as a pass;
#   * XML comments and CDATA are discarded, so a pin that exists only inside a
#     comment pins nothing (the substring bypass SEC-05 exists to remove);
#   * a DOCTYPE is refused rather than resolved, so no entity expansion;
#   * attribute values are entity-decoded before use, so a trapped `name`
#     cannot hide behind `&quot;`.

use strict;
use warnings;

# --- URL parsing ------------------------------------------------------------

# A bare host name: at least two dot-separated labels of [a-z0-9-], no
# wildcard, no scheme, no port, no path, no userinfo. Anything else is refused
# rather than silently ignored, so an allowlist entry that could never match
# fails loudly instead of quietly approving nothing.
sub sec05_host_is_bare {
    my ($h) = @_;
    return 0 unless defined $h;
    return 0 if $h =~ /[*\/\\@:?\[\]]/;
    return 0 unless $h =~ /\A[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+\z/;
    return 1;
}

# Split an absolute URL into (scheme, host, path), or () when it is not a
# simple absolute https URL with a bare host.
#
# Parsed rather than prefix-matched, on purpose. A prefix test is trivially
# impersonated: `https://services.gradle.org@evil.example/x` (host is userinfo,
# the real host is evil.example) and
# `https://evil.example/?next=https://services.gradle.org` both *begin* with
# the approved string while naming a different publisher. So the authority is
# taken from between the scheme and the first `/`, and any authority carrying
# userinfo, a port, a query or a fragment is refused outright rather than
# guessed at.
sub sec05_split_url {
    my ($url) = @_;
    return () unless defined $url && $url ne '';
    return () unless $url =~ m{\A([A-Za-z][A-Za-z0-9+.\-]*)://(.*)\z}s;
    my ($scheme, $rest) = (lc($1), $2);
    return () if $rest =~ /[?#]/;          # query/fragment: not a distribution URL
    my ($authority, $path);
    if ($rest =~ m{\A([^/]*)(/.*)?\z}s) {
        ($authority, $path) = ($1, defined $2 ? $2 : '');
    }
    else {
        return ();
    }
    return () if $authority eq '';
    return () if $authority =~ /@/;        # userinfo: the real host is elsewhere
    return () if $authority =~ /:/;        # port: not a bare publisher
    return ($scheme, lc($authority), $path);
}

# --- XML parsing ------------------------------------------------------------

# Element name without the namespace Gradle declares (default xmlns, or a
# prefix). Gradle writes the default namespace, so `<verification-metadata>`
# arrives unprefixed; a prefixed document must still be recognised.
sub sec05_local_name {
    my ($tag) = @_;
    return '' unless defined $tag;
    $tag =~ s/\A.*?://s;
    return $tag;
}

# Decode the five predefined entities and numeric references, so an attribute is
# compared as the value it actually denotes.
sub sec05_decode_entities {
    my ($v) = @_;
    return '' unless defined $v;
    $v =~ s/&quot;/"/g;
    $v =~ s/&apos;/'/g;
    $v =~ s/&lt;/</g;
    $v =~ s/&gt;/>/g;
    $v =~ s/&#x([0-9A-Fa-f]+);/chr(hex($1))/ge;
    $v =~ s/&#([0-9]+);/chr($1)/ge;
    # &amp; last, so `&amp;lt;` decodes to the literal text `&lt;` and not to `<`.
    $v =~ s/&amp;/&/g;
    return $v;
}

# Parse attributes off the inside of a start tag. Returns undef when the
# attribute list is not well formed, so the caller can refuse.
sub sec05_parse_attrs {
    my ($s) = @_;
    return undef unless defined $s;
    $s =~ s/\A\s+//;
    $s =~ s{/\s*\z}{};
    my %a;
    while ($s =~ /\S/) {
        # Skip the whitespace that separates one attribute from the next; the
        # match above can leave it in front of the following name.
        $s =~ s/\A\s+//;
        last unless $s =~ /\S/;
        # An attribute name may carry a namespace prefix (`xmlns:xsi`), so `/`
        # is legal here — it is only a separator in a tag name.
        return undef unless $s =~ s/\A([^\s=]+)\s*=\s*//;
        my $key = $1;
        return undef unless $s =~ s/\A(["'])(.*?)\1//s;
        $a{$key} = sec05_decode_entities($2);
    }
    return \%a;
}

# Parse a document into a node tree. Returns ($root, undef) or (undef, $why).
# Each node is { name, attrs, kids, text } with `name` already namespace-stripped.
sub sec05_parse_xml {
    my ($src) = @_;
    return (undef, 'empty document') unless defined $src && $src ne '';

    # A UTF-8 BOM and CRLF endings are both legal XML; normalise them so the
    # tag scanner below sees one shape.
    $src =~ s/\A\x{FEFF}//;
    $src =~ s/\r\n/\n/g;

    # A DOCTYPE can declare entities. Refuse rather than resolve: entity
    # expansion is a denial-of-service and a text-substitution trick.
    return (undef, 'declares a DOCTYPE') if $src =~ /<!DOCTYPE/i;

    # Comments and CDATA carry no pins. Removing them is what stops a document
    # whose only <component> lives inside a comment from counting as pinned.
    $src =~ s/<!--.*?-->//gs;
    $src =~ s/<!\[CDATA\[.*?\]\]>//gs;
    # Processing instructions (the XML declaration) carry no pins either.
    $src =~ s/<\?.*?\?>//gs;

    my @stack;
    my $root;
    my $pos = 0;
    my $len = length $src;

    while ($pos < $len) {
        my $lt = index($src, '<', $pos);
        if ($lt < 0) {
            my $tail = substr($src, $pos);
            if (@stack) { $stack[-1]{text} .= $tail; }
            elsif ($tail =~ /\S/) { return (undef, 'character data outside any element'); }
            last;
        }
        if ($lt > $pos) {
            my $text = substr($src, $pos, $lt - $pos);
            if (@stack) { $stack[-1]{text} .= $text; }
            elsif ($text =~ /\S/) { return (undef, 'character data before the root element'); }
        }
        $pos = $lt;

        if (substr($src, $pos, 2) eq '</') {
            my $gt = index($src, '>', $pos);
            return (undef, 'unterminated end tag') if $gt < 0;
            my $raw = substr($src, $pos + 2, $gt - $pos - 2);
            $raw =~ s/\A\s+//;
            $raw =~ s/\s+\z//;
            my $name = sec05_local_name($raw);
            return (undef, "end tag </$name> with no open element") unless @stack;
            my $top = pop @stack;
            return (undef, "end tag </$name> does not match <$top->{name}>")
                if $top->{name} ne $name;
            # Attach on close, so an element appears in its parent exactly once.
            if (@stack) { push @{ $stack[-1]{kids} }, $top; }
            else { $root = $top; }
            $pos = $gt + 1;
            next;
        }

        # Open tag: scan to the matching '>' while honouring quoted attribute
        # values, so a '>' inside an attribute cannot end the tag early and
        # smuggle the rest of the line into an element name.
        my $i = $pos + 1;
        my $quote = '';
        my $gt = -1;
        while ($i < $len) {
            my $ch = substr($src, $i, 1);
            if ($quote ne '') { $quote = '' if $ch eq $quote; }
            elsif ($ch eq '"' || $ch eq "'") { $quote = $ch; }
            elsif ($ch eq '>') { $gt = $i; last; }
            $i++;
        }
        return (undef, 'unterminated start tag') if $gt < 0;

        my $inner = substr($src, $pos + 1, $gt - $pos - 1);
        my $selfclose = ($inner =~ s{/\s*\z}{}) ? 1 : 0;
        my ($raw_name) = $inner =~ /\A\s*([^\s\/>]+)/;
        return (undef, 'start tag with no element name')
            unless defined $raw_name && $raw_name ne '';
        # Drop the element name; only the attribute list is left to parse.
        $inner =~ s/\A\s*[^\s\/>]+//;
        my $attrs = sec05_parse_attrs($inner);
        return (undef, "unparseable attributes on <$raw_name>") unless defined $attrs;

        my $node = { name => sec05_local_name($raw_name), attrs => $attrs, kids => [], text => '' };
        if ($selfclose) {
            if (@stack) { push @{ $stack[-1]{kids} }, $node; }
            else {
                return (undef, 'more than one root element') if defined $root;
                $root = $node;
            }
        }
        else {
            push @stack, $node;
        }
        $pos = $gt + 1;
    }

    return (undef, "unclosed element <$stack[-1]{name}>") if @stack;
    return (undef, 'no root element') unless defined $root;
    return ($root, undef);
}

1;