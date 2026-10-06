# SEC-05b parsing helpers — perl only, no CPAN, no network, no python3.
#
# This is a library, loaded with `require` from
# check-verification-metadata-coverage.sh. It is deliberately NOT given a
# shebang: it is never executed directly, and a shebang here would make
# scripts/check-executable-bits.sh require mode 100755, which the GitHub API
# commit path does not transport (GIT.md §5).
#
# OPE-264. SEC-05 moved off python3 in OPE-257 because the digest-pinned
# eclipse-temurin image used by `T4 static scan` carries no python3 (ADR 0007).
# SEC-05b still did, so it was the last red check in that required job: the gate
# reported "python3 is required … and is not on PATH" — the environment instead
# of an integrity problem. The image is unchanged and the gate stays inside the
# container (that isolation is OPE-212's), so the parsing moves to perl, which the
# base image does carry.
#
# The XML half reuses scripts/lib/sec05-parse.pl — the library OPE-257 already
# wrote, tested and hardened. This file must not grow a second XML parser; two
# parsers of one document is how one of them quietly stops refusing something the
# other refuses. Only the two parsers SEC-05b adds live here: the Gradle version
# catalog (TOML) and a workflow's top-level `on:` block (YAML). Rewriting those in
# awk was rejected for the same reason as in OPE-257: TOML and YAML expressed as
# awk substitutions is the classic route to a supply-chain gate that gets silently
# bypassed by one bad escape.
#
# Security posture is unchanged and fail-closed. This is a mechanical port of the
# python3 implementation, checked against it line by line, and the two are held to
# *identical output* by the OPE-264 parity section of scripts/tests/
# test-supply-chain-gates.sh, which runs both over the same fixture corpus — the
# hostile ones included — and diffs their findings. Do not change a message or a
# rule here unless the parity test still agrees:
#   * anything unparseable is refused, never counted as a pass;
#   * a declaration the gate cannot resolve is refused, never ignored;
#   * an exemption with no reason is refused;
#   * a DOCTYPE is refused rather than resolved (sec05-parse.pl);
#   * comments and CDATA are discarded, so a coordinate that exists only inside a
#     comment pins nothing.
#
# Deliberate, documented divergences from the python3 original. All of them read
# *more* input rather than less, so none of them can turn a failure into a pass:
#
#   * Byte strings, not decoded text. The pinned image has perl-base only, so
#     PerlIO and its `:encoding` layer are absent (OPE-257 learned that in CI).
#     python3 opened these files with encoding="utf-8" and raised
#     UnicodeDecodeError on invalid UTF-8 — an uncaught traceback the shell turned
#     into `::error::` findings, i.e. it failed closed by crashing. perl reads the
#     bytes and compares them, so an ASCII-only catalog behaves identically and a
#     non-UTF-8 one is now analysed instead of crashing. Coordinates are ASCII in
#     every form Gradle accepts, so no verdict on real input changes.
#   * `.strip()` and `\s` cover the ASCII members of python's whitespace set.
#     python's set also holds Unicode spaces (U+00A0, U+2028, …); those are
#     ordinary bytes here.
#   * `%r` of a string is reproduced for ASCII input; a byte >= 0x7F prints as
#     `\xNN` where python printed the decoded character.
#   * The "no Gradle build script was found" finding uses the repository root the
#     caller passes in, not getcwd(), so this library needs no Cwd module (not
#     guaranteed to exist in perl-base).
#   * A parse-error or OS-error detail is this library's wording, not
#     ElementTree's or Python's errno string. The parity test normalises exactly
#     those two parentheticals and nothing else.
#   * The shared SEC-05 parser refuses a document that declares a DOCTYPE;
#     ElementTree accepts it. SEC-05's refusal is deliberate — a DOCTYPE can declare
#     entities, and resolving them is a denial-of-service and a text-substitution
#     trick — so SEC-05b inherits it and is *stricter* than the python3 it replaces on
#     that one input. scripts/lib/sec05-parse.pl has refused a DOCTYPE since OPE-257,
#     and scripts/tests/test-supply-chain-gates.sh pins that for SEC-05. The parity
#     test drops that one finding on both sides so it compares the verdicts: the same
#     inputs, the same exit code, the same other findings.

use strict;
use warnings;

# The shared XML parser. Required by path, exactly as check-gradle-supply-chain.sh
# requires it, so a checkout without it fails closed instead of parsing nothing.
require './scripts/lib/sec05-parse.pl';

# Findings for the report currently running. One perl process runs one report.
my @PROBLEMS;

sub sec05b_refuse {
    push @PROBLEMS, $_[0];
}

# python's `str.strip()` and re's `\s` also match a few Unicode spaces. This gate
# reads ASCII build files as bytes (see the header), so the ASCII members of that
# set are what is reachable. Spelled out rather than written `\s` so the two
# implementations cannot drift apart on an invisible default.
my $WS = qr/[ \t\n\r\f\x0b\x1c\x1d\x1e]/;

sub sec05b_lstrip {
    my ($s) = @_;
    return '' unless defined $s;
    $s =~ s/\A$WS+//;
    return $s;
}

sub sec05b_rstrip {
    my ($s) = @_;
    return '' unless defined $s;
    $s =~ s/$WS+\z//;
    return $s;
}

sub sec05b_trim {
    my ($s) = @_;
    return '' unless defined $s;
    $s =~ s/\A$WS+//;
    $s =~ s/$WS+\z//;
    return $s;
}

# python's `str.splitlines()`: \r\n, \r, \n, \v, \f and the three information
# separators. A trailing boundary produces no trailing empty line. U+0085, U+2028
# and U+2029 are line boundaries to python too, but they are multi-byte in UTF-8 and
# cannot occur as a single byte in a file python managed to decode, so treating them
# as ordinary bytes is the closer of the two behaviours.
sub sec05b_splitlines {
    my ($text) = @_;
    return () unless defined $text && $text ne '';
    $text =~ s/\r\n/\n/g;
    $text =~ s/\r/\n/g;
    $text =~ s/[\x0b\x0c\x1c-\x1e]/\n/g;
    $text =~ s/\n\z//;
    return split /\n/, $text, -1;
}

# python's `repr()` of a string, for the findings that quote an untrusted value back
# at the reader. ASCII only: python's escape forms for control characters are
# reproduced, and anything non-printable shows as \xNN.
sub sec05b_py_repr {
    my ($s) = @_;
    $s = '' unless defined $s;
    my $out = q{'};
    for my $char (split //, $s) {
        if    ($char eq "'")  { $out .= q{\\'} }
        elsif ($char eq "\\") { $out .= '\\\\' }
        elsif ($char eq "\n") { $out .= '\n' }
        elsif ($char eq "\r") { $out .= '\r' }
        elsif ($char eq "\t") { $out .= '\t' }
        elsif (ord($char) < 0x20 || ord($char) >= 0x7f) { $out .= sprintf('\\x%02x', ord $char) }
        else { $out .= $char }
    }
    return $out . q{'};
}

# python's `line.split(None, 1)` — split on a run of whitespace, at most once, with
# the leading run discarded. The caller has already trimmed, so a one-token line
# yields a single field and python's `len(fields) != 2` test is an element count.
sub sec05b_split_fields {
    my ($s) = @_;
    $s = sec05b_trim($s);
    return () if $s eq '';
    return split /$WS+/, $s, 2;
}

# Read a whole file as bytes. Returns ($content, 1) or ($why, 0), so the caller
# reports the failure in its own wording instead of dying on a perl warning.
sub sec05b_read_bytes {
    my ($path) = @_;
    open(my $fh, '<', $path) or return ("$!", 0);
    local $/;
    my $src = <$fh>;
    close $fh;
    return (defined $src ? $src : '', 1);
}

# --- the version catalog ----------------------------------------------------

# Split on the commas that are not inside a quote or a bracket.
sub sec05b_split_top_level {
    my ($text) = @_;
    my @parts;
    my $current = '';
    my $depth = 0;
    my $quote = '';
    for my $char (split //, $text) {
        if ($quote ne '') {
            $current .= $char;
            $quote = '' if $char eq $quote;
            next;
        }
        if ($char eq q{'} || $char eq q{"}) {
            $quote = $char;
            $current .= $char;
            next;
        }
        if ($char eq '[' || $char eq '{') { $depth += 1 }
        elsif ($char eq ']' || $char eq '}') { $depth -= 1 }
        if ($char eq ',' && $depth == 0) {
            push @parts, $current;
            $current = '';
        }
        else {
            $current .= $char;
        }
    }
    push @parts, $current if sec05b_trim($current) ne '';
    return grep { sec05b_trim($_) ne '' } map { sec05b_trim($_) } @parts;
}

# Fold a value that opens `[` or `{` and closes on a later line, so a multi-line
# inline table is read as one entry instead of being mistaken for several unreadable
# ones. Returns [{ number, text }], one entry per logical line.
sub sec05b_join_multiline {
    my ($lines) = @_;
    my @logical;
    my $index = 0;
    while ($index < @$lines) {
        my $number = $index + 1;
        my $text = sec05b_trim($lines->[$index]);
        $index++;
        next if $text eq '';
        next if index($text, '#') == 0;
        if ((index($text, '[') == 0 && substr($text, -1) ne ']')
            || (index($text, '{') == 0 && substr($text, -1) ne '}'))
        {
            my $depth = 0;
            my @parts;
            while (1) {
                for my $char (split //, $text) {
                    if ($char eq '[' || $char eq '{') { $depth += 1 }
                    elsif ($char eq ']' || $char eq '}') { $depth -= 1 }
                }
                push @parts, $text;
                last if $depth <= 0 || $index >= @$lines;
                $text = sec05b_trim($lines->[$index]);
                $index++;
            }
            $text = join ' ', @parts;
        }
        push @logical, { number => $number, text => $text };
    }
    return \@logical;
}

# Gradle's version catalog is TOML, but only a narrow subset of it can appear in a
# coordinate: a version string, a library entry (module, or group+name, with either a
# literal version or a version.ref), a plugin entry, or a bundle. That subset is read
# here; a line that does not fit is refused rather than skipped, because a silently
# unread catalog would silently under-report the build's dependencies — the same
# failure this gate exists to prevent.
sub sec05b_parse_catalog {
    my ($path) = @_;
    my ($raw, $opened) = sec05b_read_bytes($path);
    unless ($opened) {
        sec05b_refuse(
            "$path cannot be read ($raw); the declared dependencies are unknown, so pin coverage cannot be checked (SEC-05b)."
        );
        return { versions => {}, libraries => {}, plugins => {}, bundles => {} };
    }

    my %versions;
    my %libraries;
    my %plugins;
    my %bundles;
    my %targets = (versions => \%versions, libraries => \%libraries, plugins => \%plugins, bundles => \%bundles);
    my $section = '';

    for my $entry (@{ sec05b_join_multiline([ sec05b_splitlines($raw) ]) }) {
        my $line = $entry->{text};
        my $number = $entry->{number};

        if ($line =~ /\A\[([^\]]+)\]$/) {
            $section = exists $targets{$1} ? $1 : '';
            next;
        }

        unless ($line =~ /\A([A-Za-z0-9_.\-]+)$WS*=$WS*(.*)\z/) {
            next if $section eq '';
            sec05b_refuse(
                sprintf(
                    '%s:%d is `%s`, which this gate cannot read. A dependency entry it cannot read is a dependency whose pin it cannot check, so the line is refused rather than ignored (SEC-05b).',
                    $path, $number, $line
                )
            );
            next;
        }
        my ($name, $raw_value) = ($1, sec05b_trim($2));
        next if $section eq '';

        if ($section eq 'versions') {
            unless ($raw_value =~ /\A(["'])([^"']*)\1\z/) {
                sec05b_refuse(
                    sprintf('%s:%d is `%s`, which is not a quoted version string (SEC-05b).', $path, $number, $line)
                );
                next;
            }
            $versions{$name} = $2;
        }
        elsif ($section eq 'bundles') {
            unless ($raw_value =~ /\A\[(.*)\]\z/s) {
                sec05b_refuse(
                    sprintf('%s:%d is `%s`, which is not an array of alias names (SEC-05b).', $path, $number, $line)
                );
                next;
            }
            my @members;
            for my $item (sec05b_split_top_level($1)) {
                unless ($item =~ /\A(["'])([^"']*)\1\z/) {
                    sec05b_refuse(
                        sprintf(
                            '%s:%d lists %s in a bundle, which is not a plain alias name. Only declared aliases can be resolved offline (SEC-05b).',
                            $path, $number, sec05b_py_repr($item)
                        )
                    );
                    next;
                }
                push @members, $2;
            }
            # Assigned even when an item was refused, exactly as the original did: a
            # partially readable entry is still evidence, and dropping it would hide
            # the declarations it does declare.
            $bundles{$name} = \@members;
        }
        else {
            unless ($raw_value =~ /\A\{(.*)\}\z/s) {
                sec05b_refuse(
                    sprintf(
                        '%s:%d is `%s`, not an inline table (`{ … }`), which is the only library/plugin declaration form this gate can read. Declare it as an inline table so its coordinate can be checked against the pins (SEC-05b).',
                        $path, $number, $line
                    )
                );
                next;
            }
            my %entry;
            for my $item (sec05b_split_top_level($1)) {
                unless ($item =~ /\A([A-Za-z0-9_.\-]+)$WS*=$WS*(.*)\z/) {
                    sec05b_refuse(
                        sprintf('%s:%d has an entry %s that is not `key = value` (SEC-05b).',
                            $path, $number, sec05b_py_repr($item))
                    );
                    next;
                }
                my ($key, $value) = ($1, sec05b_trim($2));
                unless ($value =~ /\A(["'])([^"']*)\1\z/) {
                    sec05b_refuse(
                        sprintf(
                            '%s:%d has `%s` with a value this gate cannot read statically. Use a literal string, or declare the dependency in gradle/libs.versions.toml so the coordinate is checkable (SEC-05b).',
                            $path, $number, $key
                        )
                    );
                    next;
                }
                $entry{$key} = $2;
            }
            if ($section eq 'plugins') { $plugins{$name} = \%entry }
            else { $libraries{$name} = \%entry }
        }
    }

    return { versions => \%versions, libraries => \%libraries, plugins => \%plugins, bundles => \%bundles };
}

# The version a catalog entry resolves to, or undef.
sub sec05b_resolve_version {
    my ($entry, $versions, $path, $name, $metadata) = @_;
    if (exists $entry->{'version.ref'}) {
        my $ref = $entry->{'version.ref'};
        unless (exists $versions->{$ref}) {
            sec05b_refuse(
                sprintf(
                    '%s: `%s` refers to version.ref %s, which [versions] does not declare. The coordinate of this dependency is therefore unknown and cannot be checked against %s (SEC-05b).',
                    $path, $name, sec05b_py_repr($ref), $metadata
                )
            );
            return undef;
        }
        return $versions->{$ref};
    }
    if (exists $entry->{version}) {
        return $entry->{version};
    }
    sec05b_refuse(
        sprintf(
            '%s: `%s` declares no version and no version.ref. Its resolved coordinate is unknown, so it cannot be checked against %s (SEC-05b).',
            $path, $name, $metadata
        )
    );
    return undef;
}

# A library table -> [group, name, version], or undef.
sub sec05b_resolve_catalog_entry {
    my ($entry, $versions, $path, $name, $metadata) = @_;
    my $version = sec05b_resolve_version($entry, $versions, $path, $name, $metadata);
    return undef unless defined $version;

    my ($group, $artifact);
    if (exists $entry->{module}) {
        my $coordinate = $entry->{module};
        my @segments = split /:/, $coordinate, -1;
        if (@segments != 2) {
            sec05b_refuse(
                sprintf(
                    '%s: `%s` declares module %s, which is not `group:name`. Only a fully qualified module can be checked against %s (SEC-05b).',
                    $path, $name, sec05b_py_repr($coordinate), $metadata
                )
            );
            return undef;
        }
        ($group, $artifact) = @segments;
    }
    elsif (exists $entry->{group} && exists $entry->{name}) {
        $group = $entry->{group};
        $artifact = $entry->{name};
    }
    else {
        sec05b_refuse(
            sprintf(
                '%s: `%s` declares neither `module` nor `group`+`name`. Its group and artifact are unknown, so it cannot be checked against %s (SEC-05b).',
                $path, $name, $metadata
            )
        );
        return undef;
    }
    return [ sec05b_trim($group), sec05b_trim($artifact), sec05b_trim($version) ];
}

# `libs.kotlinx.coroutines.core` -> ("libraries", "kotlinx-coroutines-core").
#
# Gradle builds a catalog accessor by joining the accessor segments with `-`, so a
# dot in an accessor is a `-` in the alias. `libs.plugins.x` and `libs.bundles.x` are
# looked up in their own section.
sub sec05b_alias_to_key {
    my ($path_expr) = @_;
    my $trimmed = substr($path_expr, length('libs.'));
    my @segments = grep { $_ ne '' } split /\./, $trimmed, -1;
    return (undef, undef) unless @segments;
    my $head = $segments[0];
    if ($head eq 'plugins' || $head eq 'bundles' || $head eq 'versions') {
        return ($head, join '-', @segments[1 .. $#segments]);
    }
    return ('libraries', join '-', @segments);
}

# `libs.…` -> a list of [group, name, version] coordinates. Unresolvable -> [] plus a
# finding.
sub sec05b_resolve_alias {
    my ($path_expr, $catalog) = @_;
    my ($section, $key) = sec05b_alias_to_key($path_expr);
    unless (defined $section) {
        sec05b_refuse("`$path_expr` is not a usable version-catalog accessor (SEC-05b).");
        return [];
    }
    unless (length $key) {
        sec05b_refuse("`$path_expr` names no alias (SEC-05b).");
        return [];
    }

    if ($section eq 'versions') {
        sec05b_refuse(
            "`$path_expr` is a version reference, not a dependency coordinate; a version cannot appear where a dependency is declared (SEC-05b)."
        );
        return [];
    }

    if ($section eq 'bundles') {
        unless (exists $catalog->{bundles}{$key}) {
            sec05b_refuse(
                sprintf('`%s` names bundle %s, which [bundles] does not declare (SEC-05b).',
                    $path_expr, sec05b_py_repr($key))
            );
            return [];
        }
        my @coordinates;
        for my $member (@{ $catalog->{bundles}{$key} }) {
            push @coordinates, @{ sec05b_resolve_alias('libs.' . $member, $catalog) };
        }
        return \@coordinates;
    }

    if ($section eq 'plugins') {
        my $entry = $catalog->{plugins}{$key};
        unless (defined $entry) {
            sec05b_refuse(
                sprintf('`%s` names plugin alias %s, which [plugins] does not declare (SEC-05b).',
                    $path_expr, sec05b_py_repr($key))
            );
            return [];
        }
        my $plugin_id = $entry->{id};
        unless (defined $plugin_id && length $plugin_id) {
            sec05b_refuse(sprintf('%s: plugin alias `%s` declares no `id` (SEC-05b).', $catalog->{path}, $key));
            return [];
        }
        my $version = sec05b_resolve_version($entry, $catalog->{versions}, $catalog->{path}, $key, $catalog->{metadata});
        return [] unless defined $version;
        # A plugin id resolves to its marker artifact on the Gradle Plugin Portal.
        return [ [ $plugin_id, sprintf('%s.gradle.plugin', $plugin_id), $version ] ];
    }

    my $entry = $catalog->{libraries}{$key};
    unless (defined $entry) {
        sec05b_refuse(
            sprintf('`%s` names library alias %s, which [libraries] does not declare (SEC-05b).',
                $path_expr, sec05b_py_repr($key))
        );
        return [];
    }
    my $coordinate
        = sec05b_resolve_catalog_entry($entry, $catalog->{versions}, $catalog->{path}, $key, $catalog->{metadata});
    return defined $coordinate ? [$coordinate] : [];
}

# --- the pinned components --------------------------------------------------

# The coordinates gradle/verification-metadata.xml pins, as a lookup keyed on the
# three fields. Parsed by the shared SEC-05 parser (scripts/lib/sec05-parse.pl) — not
# by a second parser written for this gate — so the two controls read one document
# through one implementation, already hardened against a BOM, a trapped attribute, a
# comment-only pin and a DOCTYPE.
sub sec05b_pinned_coordinates {
    my ($path) = @_;
    my ($src, $opened) = sec05b_read_bytes($path);
    unless ($opened) {
        sec05b_refuse("$path cannot be read ($src); nothing can be shown to be pinned (SEC-05b).");
        return {};
    }
    my ($root, $why) = sec05_parse_xml($src);
    if (!defined $root) {
        sec05b_refuse("$path is not well-formed XML ($why); Gradle cannot read it, so it pins nothing (SEC-05b).");
        return {};
    }
    unless ($root->{name} eq 'verification-metadata') {
        sec05b_refuse(sprintf('%s has root element <%s>, not <verification-metadata> (SEC-05b).', $path, $root->{name}));
        return {};
    }
    my %pinned;
    for my $section (@{ $root->{kids} }) {
        next unless $section->{name} eq 'components';
        for my $component (@{ $section->{kids} }) {
            next unless $component->{name} eq 'component';
            my ($group, $name, $version)
                = map { defined $component->{attrs}{$_} ? $component->{attrs}{$_} : '' } qw(group name version);
            $pinned{ join("\x1f", $group, $name, $version) } = 1;
        }
    }
    return \%pinned;
}

# --- exemptions -------------------------------------------------------------

# Notation -> reason. A malformed line is refused, never skipped.
#
# Used for both exemption lists. The reason is mandatory: a bare entry is a hole with
# no justification attached, which is indistinguishable from a deliberate bypass of
# the control.
sub sec05b_read_exemptions {
    my ($path, $blame) = @_;
    return {} unless -e $path;
    my ($src, $opened) = sec05b_read_bytes($path);
    unless ($opened) {
        sec05b_refuse(sprintf('%s cannot be read (%s); %s (SEC-05b).', $path, $src, $blame->{unreadable}));
        return {};
    }

    my %exemptions;
    my $number = 0;
    for my $raw (sec05b_splitlines($src)) {
        $number++;
        my $line = sec05b_trim((split /#/, $raw, 2)[0]);
        next if $line eq '';
        my @fields = sec05b_split_fields($line);
        if (@fields != 2 || sec05b_trim($fields[1]) eq '') {
            sec05b_refuse(
                sprintf('%s:%d is `%s`. %s (SEC-05b).', $path, $number, sec05b_trim($raw), $blame->{no_reason})
            );
            next;
        }
        if (exists $exemptions{ $fields[0] }) {
            sec05b_refuse(
                sprintf('%s:%d exempts %s twice (SEC-05b).', $path, $number, sec05b_py_repr($fields[0]))
            );
            next;
        }
        $exemptions{ $fields[0] } = sec05b_trim($fields[1]);
    }
    return \%exemptions;
}

# --- build script scan ------------------------------------------------------

# Every dependency configuration this gate recognises, plus the two wrappers around
# another notation. A notation it does not know about is treated as an unresolvable
# declaration rather than ignored, so a new configuration cannot open a silent hole.
#
# The alternation below is python's `sorted(CONFIGURATIONS | WRAPPERS)`, character for
# character. Regex alternation is first-match-wins, so the order is part of the
# control: keep the two lists identical, or the two implementations can pick different
# notations out of the same line.
my @CONFIGURATIONS = qw(
  androidTestCompileOnly
  androidTestImplementation
  androidTestRuntimeOnly
  annotationProcessor
  api
  classpath
  compileOnly
  coreLibraryDesugaring
  debugApi
  debugCompileOnly
  debugImplementation
  debugRuntimeOnly
  detektPlugins
  enforcedPlatform
  implementation
  kapt
  kaptTest
  ksp
  kspTest
  lintChecks
  lintPublish
  platform
  releaseApi
  releaseCompileOnly
  releaseImplementation
  releaseRuntimeOnly
  runtimeOnly
  testCompileOnly
  testFixturesApi
  testFixturesImplementation
  testImplementation
  testRuntimeOnly
);

my $CALLED_RE       = qr/(?<![\w.])((?:@{[ join '|', @CONFIGURATIONS ]}))\(/;
my $PLUGIN_ALIAS_RE = qr/alias\($WS*(libs\.plugins\.[A-Za-z0-9_.\-]+)$WS*\)/;
my $PLUGIN_ID_RE    = qr/\bid\($WS*["']([^"']+)["']$WS*\)(?:$WS*version$WS*["']([^"']+)["'])?/;
my $STRING_RE       = qr/["']([^"']+)["']/;

my @SKIPPED_PREFIXES = ('project(', 'files(', 'fileTree(', 'file(', 'layout.');

# Drop a `//` comment that is not inside a string literal.
sub sec05b_strip_comments {
    my ($line) = @_;
    my $out = '';
    my $quote = '';
    my $length = length $line;
    my $index = 0;
    while ($index < $length) {
        my $char = substr($line, $index, 1);
        if ($quote ne '') {
            $out .= $char;
            $quote = '' if $char eq $quote;
        }
        elsif ($char eq q{'} || $char eq q{"}) {
            $quote = $char;
            $out .= $char;
        }
        elsif ($char eq '/' && $index + 1 < $length && substr($line, $index + 1, 1) eq '/') {
            last;
        }
        else {
            $out .= $char;
        }
        $index++;
    }
    return $out;
}

# Every build script under the current directory, relative and sorted. A symlinked
# *directory* is not descended into, matching the walk this replaces — otherwise a
# symlinked tree could turn one declaration into a loop. A symlinked build script is
# still read, because a declaration reached through a link is still a declaration.
sub sec05b_collect_build_scripts {
    my %skip = map { $_ => 1 } qw(.git .gradle .paperclip build node_modules);
    my @scripts;
    my @stack = ('.');
    while (@stack) {
        my $base = pop @stack;
        opendir(my $dh, $base) or next;
        my @names = sort grep { $_ ne '.' && $_ ne '..' } readdir($dh);
        closedir $dh;
        my @subdirs;
        for my $name (@names) {
            my $path = ($base eq '.') ? $name : "$base/$name";
            if (-d $path && !-l $path) {
                push @subdirs, $path unless $skip{$name};
                next;
            }
            next unless -f $path;
            push @scripts, $path
                if $name eq 'build.gradle.kts'
                || $name eq 'build.gradle'
                || $name eq 'settings.gradle.kts'
                || $name eq 'settings.gradle';
        }
        push @stack, reverse @subdirs;
    }
    return [ sort @scripts ];
}

# A python-compatible ordering over two coordinate triples. perl's default `sort` is
# string order, so this is spelled out: sorting the joined form would compare a
# separator against a letter and could order one coordinate differently from the
# original on some inputs.
sub sec05b_coordinate_cmp {
    my ($a, $b) = @_;
    for my $i (0 .. 2) {
        my $order = $a->[$i] cmp $b->[$i];
        return $order if $order;
    }
    return 0;
}

sub sec05b_declare {
    my ($state, $notation, $coordinate, $origin) = @_;
    my $key = join("\x1f", @$coordinate);
    $state->{declared}{$key} ||= { coordinate => $coordinate, origins => [] };
    my $origins = $state->{declared}{$key}{origins};
    push @$origins, $origin unless grep { $_ eq $origin } @$origins;
}

sub sec05b_declare_unresolved {
    my ($state, $notation, $origin) = @_;
    $state->{unresolved}{$notation} ||= [];
    my $origins = $state->{unresolved}{$notation};
    push @$origins, $origin unless grep { $_ eq $origin } @$origins;
}

# Turn the argument of a dependency configuration into coordinates.
sub sec05b_parse_notation {
    my ($state, $notation, $origin, $depth) = @_;
    $depth ||= 0;
    $notation = sec05b_trim($notation);
    $notation =~ s/,+\z//;
    $notation = sec05b_trim($notation);
    return if $notation eq '';
    if ($depth > 6) {
        sec05b_refuse("$origin: $notation nests dependency notations too deeply to resolve (SEC-05b).");
        return;
    }

    for my $prefix (@SKIPPED_PREFIXES) {
        return if index($notation, $prefix) == 0;
    }

    if ($notation =~ $CALLED_RE) {
        # The original took the slice from the `(`, which is one before the end of the
        # match — not from the start of the configuration name. `$+[0]`, not `pos()`:
        # `pos()` is only set after a /g match, and this one is not.
        my $inner = sec05b_trim(substr($notation, $+[0] - 1));
        if (substr($inner, -1) eq ')') {
            $inner = substr($inner, 1, length($inner) - 2);
        }
        sec05b_parse_notation($state, $inner, $origin, $depth + 1);
        return;
    }

    if (index($notation, 'libs.') == 0) {
        for my $coordinate (@{ sec05b_resolve_alias($notation, $state->{catalog}) }) {
            sec05b_declare($state, $notation, $coordinate, $origin);
        }
        return;
    }

    if ($notation =~ $STRING_RE) {
        my $value = $1;
        my @segments = split /:/, $value, -1;
        if (@segments == 3 && !grep { $_ eq '' } @segments) {
            sec05b_declare($state, $notation, \@segments, $origin);
            return;
        }
        if (@segments >= 4) {
            # group:name:version:classifier — still a real coordinate to pin.
            sec05b_declare($state, $notation, [ @segments[0 .. 2] ], $origin);
            return;
        }
    }

    sec05b_declare_unresolved($state, $notation, $origin);
}

# --- report 1: coverage and unresolvable declarations ------------------------

sub sec05b_coverage_report {
    my ($metadata, $catalog_path, $exemptions_path, $root) = @_;
    @PROBLEMS = ();

    my $catalog = sec05b_parse_catalog($catalog_path);
    $catalog->{path}     = $catalog_path;
    $catalog->{metadata} = $metadata;

    my $pinned = sec05b_pinned_coordinates($metadata);

    my $exemptions = sec05b_read_exemptions(
        $exemptions_path,
        {   unreadable =>
                'the set of entries exempt from this check is unknown, so this gate fails closed rather than approving them all',
            no_reason =>
                'Every entry needs the declaration text and the reason it is exempt, separated by whitespace — an exemption nobody can justify is indistinguishable from a bypass',
        }
    );

    my $scripts = sec05b_collect_build_scripts();
    unless (@$scripts) {
        sec05b_refuse(
            "no Gradle build script (build.gradle.kts, settings.gradle.kts) was found under $root. The declared dependencies are therefore unknown, and an empty result must not be read as 'fully covered' (SEC-05b)."
        );
    }

    my $state = { catalog => $catalog, declared => {}, unresolved => {} };

    for my $script (@$scripts) {
        my ($src, $opened) = sec05b_read_bytes($script);
        unless ($opened) {
            sec05b_refuse("$script cannot be read ($src), so the dependencies it declares cannot be checked (SEC-05b).");
            next;
        }
        my @lines = sec05b_splitlines($src);

        # Dependency notations.
        my $number = 0;
        for my $raw (@lines) {
            $number++;
            my $line = sec05b_strip_comments($raw);
            while ($line =~ /$CALLED_RE/g) {
                my $start = pos($line);
                my $depth = 1;
                my $index = $start;
                my $length = length $line;
                while ($index < $length && $depth) {
                    my $char = substr($line, $index, 1);
                    if ($char eq '(') { $depth += 1 }
                    elsif ($char eq ')') { $depth -= 1 }
                    $index++;
                }
                my $argument
                    = $depth == 0
                    ? substr($line, $start, $index - 1 - $start)
                    : substr($line, $start);
                sec05b_parse_notation($state, $argument, "$script:$number", 0);
            }
        }

        # Plugin declarations, including the `apply false` root-project form. A plugin
        # marker is resolved before any project is configured, so an unpinned marker
        # fails every job at once instead of one.
        my $in_plugins_block = 0;
        my $block_indent      = 0;
        $number               = 0;
        for my $raw (@lines) {
            $number++;
            my $line = sec05b_strip_comments($raw);
            my $stripped = sec05b_trim($line);
            next if $stripped eq '';
            my $indent = length($line) - length(sec05b_lstrip($line));
            if ($stripped eq 'plugins {') {
                $in_plugins_block = 1;
                $block_indent     = $indent;
                next;
            }
            next unless $in_plugins_block;
            if ($stripped eq '}' && $indent == $block_indent) {
                $in_plugins_block = 0;
                next;
            }
            my $origin = "$script:$number";
            while ($line =~ /$PLUGIN_ALIAS_RE/g) {
                my $alias = $1;
                for my $coordinate (@{ sec05b_resolve_alias($alias, $catalog) }) {
                    sec05b_declare($state, $alias, $coordinate, $origin);
                }
            }
            while ($line =~ /$PLUGIN_ID_RE/g) {
                my ($id, $version) = ($1, $2);
                unless (defined $version && length $version) {
                    sec05b_declare_unresolved($state, $stripped, $origin);
                    next;
                }
                sec05b_declare($state, $stripped, [ $id, "$id.gradle.plugin", $version ], $origin);
            }
        }
    }

    # --- control 1: is every declared coordinate pinned? ----------------------

    unless (%$pinned || @PROBLEMS) {
        sec05b_refuse("$metadata pins no component, so no declared dependency can be shown to be covered (SEC-05b).");
    }

    my @missing;
    my @exempted_coordinates;
    for my $key (
        sort {
            sec05b_coordinate_cmp($state->{declared}{$a}{coordinate}, $state->{declared}{$b}{coordinate})
        } keys %{ $state->{declared} }
        )
    {
        next if $pinned->{$key};
        my $text = join(':', @{ $state->{declared}{$key}{coordinate} });
        if (exists $exemptions->{$text}) {
            push @exempted_coordinates, $text;
            next;
        }
        push @missing, $state->{declared}{$key};
    }

    # --- control 2: unresolvable declarations must be registered -------------

    my @unexempted;
    for my $notation (sort keys %{ $state->{unresolved} }) {
        next if exists $exemptions->{$notation};
        push @unexempted, { notation => $notation, origins => $state->{unresolved}{$notation} };
    }

    # --- report --------------------------------------------------------------

    my $covered = scalar(keys %{ $state->{declared} }) - scalar(@missing);

    if (@missing) {
        my @shown;
        for my $item (@missing[0 .. ($#missing < 7 ? $#missing : 7)]) {
            my ($group, $name, $version) = @{ $item->{coordinate} };
            my @origins = @{ $item->{origins} };
            my $kept    = join ', ', @origins[0 .. ($#origins < 2 ? $#origins : 2)];
            $kept .= ', …' if @origins > 3;
            push @shown, sprintf('%s:%s:%s (declared at %s)', $group, $name, $version, $kept);
        }
        my $rest = scalar(@missing) - scalar(@shown);
        sec05b_refuse(
            sprintf(
                "%d declared dependenc%s not covered by %s: %s%s.\n  A build input changed without gradle/verification-metadata.xml being regenerated for it, which is how OPE-220 went red in every job at once. Regenerate from a machine that reaches BOTH Maven Central and the Gradle Plugin Portal (docs/CI-CD-SECURITY.md §8), or add the declared coordinate to %s with a reason if it is genuinely never resolved.",
                scalar(@missing),
                (@missing == 1 ? 'y is' : 'ies are'),
                $metadata,
                join('; ', @shown),
                ($rest > 0 ? sprintf('; +%d more', $rest) : ''),
                $exemptions_path,
            )
        );
    }

    if (@unexempted) {
        my @shown;
        for my $item (@unexempted[0 .. ($#unexempted < 7 ? $#unexempted : 7)]) {
            my @origins = @{ $item->{origins} };
            my $kept    = join ', ', @origins[0 .. ($#origins < 1 ? $#origins : 1)];
            $kept .= ', …' if @origins > 2;
            push @shown, sprintf('%s (%s)', $item->{notation}, $kept);
        }
        my $rest = scalar(@unexempted) - scalar(@shown);
        sec05b_refuse(
            sprintf(
                "%d dependency declaration%s cannot be resolved to a coordinate, so this gate cannot tell whether %s pins %s: %s%s.\n  Ignoring a declaration it cannot read is how a new dependency slips past the pins, so each one must either carry its coordinate (a `group:name:version` literal, or a gradle/libs.versions.toml alias) or be registered in %s with a reason.",
                scalar(@unexempted),
                (@unexempted == 1 ? ' is' : 's are'),
                $metadata,
                (@unexempted == 1 ? 'it' : 'them'),
                join('; ', @shown),
                ($rest > 0 ? sprintf('; +%d more', $rest) : ''),
                $exemptions_path,
            )
        );
    }

    print "$_\n" for @PROBLEMS;

    exit 1 if @missing || @unexempted || @PROBLEMS;

    if (@exempted_coordinates) {
        printf(
            "  %d declared coordinate(s) have no pin and are registered as exempt in %s: %s.\n",
            scalar(@exempted_coordinates),
            $exemptions_path,
            join(', ', @exempted_coordinates)
        );
    }

    printf(
        "%s covers all %d declared external dependenc%s (%d pinned, %d exempt with a recorded reason).\n",
        $metadata,
        scalar(keys %{ $state->{declared} }),
        (scalar(keys %{ $state->{declared} }) == 1 ? 'y' : 'ies'),
        $covered,
        scalar(@exempted_coordinates)
    );
}

# --- report 2: no CI job rewrites the pins ---------------------------------

# The trigger names declared in a workflow's top-level `on:` block.
#
# The block is located by finding the top-level `on:` key first. Scanning for
# indented keys without doing so would read the file's *first* top-level mapping
# (`name:`, `jobs:`, …) as the end of the trigger block and return nothing at all — a
# workflow that plainly triggers on `pull_request` would then look trigger-less, and
# an exemption meant for a manual-only job would be rejected for the wrong reason.
sub sec05b_workflow_triggers {
    my ($lines) = @_;
    my $on_re = qr/\A(["']?)on\1$WS*:$WS*(.*)\z/;
    my ($start, $header);
    for my $index (0 .. $#$lines) {
        my $line = sec05b_rstrip($lines->[$index]);
        next if index(sec05b_lstrip($line), '#') == 0;
        if ($line =~ $on_re) { ($start, $header) = ($index, $2); last }
    }
    return [] unless defined $start;

    $header = sec05b_trim($header);

    # `on: [push]` — the whole trigger list is on one line.
    if (index($header, '[') == 0) {
        my $body = $header;
        $body =~ s/\A\[+//;
        $body =~ s/\]+\z//;
        my @items;
        for my $item (split /,/, $body, -1) {
            $item = sec05b_trim($item);
            next if $item eq '';
            $item =~ s/\A["']+//;
            $item =~ s/["']+\z//;
            push @items, $item;
        }
        return \@items;
    }

    my @triggers;
    if ($header ne '') {
        # `on: pull_request`, or a mapping whose first key shares the line.
        my $trigger = $header;
        $trigger =~ s/\A["']+//;
        $trigger =~ s/["']+\z//;
        $trigger =~ s/:+\z//;
        push @triggers, sec05b_trim($trigger);
    }

    for my $index ($start + 1 .. $#$lines) {
        my $line = sec05b_rstrip($lines->[$index]);
        next if sec05b_trim($line) eq '';
        my $stripped = sec05b_lstrip($line);
        next if index($stripped, '#') == 0;
        my $indent = length($line) - length($stripped);
        last if $indent == 0;
        next unless $indent == 2;
        next unless $stripped =~ /\A([A-Za-z_][A-Za-z0-9_-]*)$WS*:/;
        my $trigger = $1;
        push @triggers, $trigger unless grep { $_ eq $trigger } @triggers;
    }

    return [ grep { length } @triggers ];
}

sub sec05b_regen_report {
    my ($workflows_dir, $write_flag, $exemptions_path) = @_;
    @PROBLEMS = ();

    my $exemptions = sec05b_read_exemptions(
        $exemptions_path,
        {   unreadable =>
                'the set of workflows allowed to regenerate pins is unknown, so this gate fails closed rather than waving them all through',
            no_reason =>
                'Every entry needs the workflow path and the reason it may regenerate pins — an exemption nobody can justify is indistinguishable from a bypass',
        }
    );

    my @workflow_paths;
    if (-d $workflows_dir) {
        opendir(my $dh, $workflows_dir);
        if ($dh) {
            my @names = sort grep { $_ ne '.' && $_ ne '..' } readdir($dh);
            closedir $dh;
            for my $name (@names) {
                next unless $name =~ /\.(?:yml|yaml)\z/;
                push @workflow_paths, "$workflows_dir/$name";
            }
        }
    }

    my @offenders;
    my @notes;
    for my $path (@workflow_paths) {
        my ($src, $opened) = sec05b_read_bytes($path);
        unless ($opened) {
            sec05b_refuse("$path cannot be read ($src) (SEC-05b).");
            next;
        }
        # The lines YAML actually gives meaning to. A line whose first non-blank
        # character is `#` is a comment: it is never executed, and a control that
        # flagged documentation for naming the flag would be a control contributors
        # learn to work around. Every other line is checked, including a `run:` block,
        # because that is where the flag would be used. A comment cannot run a command,
        # so ignoring it loses nothing.
        my @body = grep { index(sec05b_lstrip($_), '#') != 0 } sec05b_splitlines($src);

        next unless grep { index($_, $write_flag) >= 0 } @body;

        unless (exists $exemptions->{$path}) {
            push @offenders,
                sprintf('%s passes %s. That flag only produces correct pins from a machine reaching BOTH Maven Central and the Gradle Plugin Portal — the two serve different bytes for several plugin marker POMs — so a CI job using it rewrites the dependency pins from an environment nobody reviewed. Run the regeneration locally and land the pins by hand (docs/CI-CD-SECURITY.md §8), or register this workflow in %s with a reason if it is a manual, read-only job.',
                    $path, $write_flag, $exemptions_path);
            next;
        }
        my @triggers = @{ sec05b_workflow_triggers(\@body) };
        unless (@triggers) {
            sec05b_refuse(
                sprintf('%s is registered in %s as allowed to regenerate pins, but its `on:` block declares no trigger this gate can read, so it cannot be shown to be manual-only (SEC-05b).',
                    $path, $exemptions_path)
            );
            next;
        }
        my @extra = grep { $_ ne 'workflow_dispatch' } @triggers;
        if (@extra) {
            sec05b_refuse(
                sprintf('%s is registered in %s as allowed to regenerate pins, but it also triggers on %s. The exemption covers a manual, read-only regeneration only: a job that runs on %s would rewrite the dependency pins from an unreviewed environment, which is the control this gate exists to prevent. Remove the trigger, or drop the %s and regenerate by hand (SEC-05b).',
                    $path, $exemptions_path, join(', ', @extra), join('/', @extra), $write_flag)
            );
            next;
        }
        push @notes,
            sprintf('%s may regenerate pins: manual dispatch only, read-only (exemption in %s).', $path, $exemptions_path);
    }

    sec05b_refuse($_) for @offenders;

    print "$_\n" for @PROBLEMS;

    exit 1 if @PROBLEMS;

    print "$_\n" for @notes;
    printf(
        "no workflow under %s passes %s (%d workflow(s) checked; %d exemption(s) in %s).\n",
        $workflows_dir,
        $write_flag,
        scalar(@workflow_paths),
        scalar(keys %$exemptions),
        $exemptions_path
    );
}

1;