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
# supply-chain gates. It reads build scripts with python3 (standard library
# only) because resolving a version-catalog alias and comparing it against the
# pin file needs a parse, not a grep. python3 must exist on the runner and the
# gate fails closed when it does not.
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

have_python=0
command -v python3 >/dev/null 2>&1 && have_python=1
if [ "$have_python" -eq 0 ]; then
  fail_with "python3 is required to resolve gradle/libs.versions.toml aliases, to parse $VERIFICATION_METADATA and to read workflow triggers, and is not on PATH; without it the dependency-pin coverage control cannot run, so this gate fails closed rather than skipping it. Install it (Debian/Ubuntu: apt-get install -y python3)."
fi

printf 'Checking that dependency pins cover the declared build inputs (SEC-05b)...\n'

# --- controls 1 and 2: pin coverage and unresolvable declarations -----------

coverage_report() {
  python3 - "$VERIFICATION_METADATA" "$VERSION_CATALOG" "$COVERAGE_EXEMPTIONS" <<'PY'
import os
import re
import sys
import xml.etree.ElementTree as ET

METADATA, CATALOG, EXEMPTIONS = sys.argv[1], sys.argv[2], sys.argv[3]

problems = []


def refuse(message):
    problems.append(message)


# --- the version catalog ----------------------------------------------------

# Gradle's version catalog is TOML, but only a narrow subset of it can appear in
# a coordinate: a version string, a library entry (module, or group+name, with
# either a literal version or a version.ref), a plugin entry, or a bundle. That
# subset is read here; a line that does not fit is refused rather than skipped,
# because a silently unread catalog would silently under-report the build's
# dependencies — the same failure this gate exists to prevent.

SECTION_RE = re.compile(r"^\[([^\]]+)\]\s*$")
KV_RE = re.compile(r"^([A-Za-z0-9_.\-]+)\s*=\s*(.*)$")
INLINE_TABLE_RE = re.compile(r"^\{(.*)\}$", re.S)
ARRAY_RE = re.compile(r"^\[(.*)\]$", re.S)
STRING_RE = re.compile(r"""^["']([^"']*)["']$""")


def split_top_level(text):
    """Split on commas that are not inside quotes or brackets."""
    parts, current, depth, quote = [], [], 0, None
    for char in text:
        if quote:
            current.append(char)
            if char == quote:
                quote = None
            continue
        if char in "\"'":
            quote = char
            current.append(char)
            continue
        if char in "[{":
            depth += 1
        elif char in "]}":
            depth -= 1
        if char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    if "".join(current).strip():
        parts.append("".join(current))
    return [part.strip() for part in parts if part.strip()]


def join_multiline(lines):
    """Fold a value that opens `[` or `{` and closes on a later line.

    Returns [(line_number, text)] with one entry per logical line, so a
    multi-line inline table is read as a single entry instead of being
    mistaken for several unreadable ones.
    """
    logical = []
    index = 0
    while index < len(lines):
        number = index + 1
        text = lines[index].strip()
        index += 1
        if not text or text.startswith("#"):
            continue
        if (text.startswith("[") and not text.endswith("]")) or (
            text.startswith("{") and not text.endswith("}")
        ):
            depth = 0
            parts = []
            while True:
                for char in text:
                    if char in "[{":
                        depth += 1
                    elif char in "]}":
                        depth -= 1
                parts.append(text)
                if depth <= 0 or index >= len(lines):
                    break
                text = lines[index].strip()
                index += 1
            text = " ".join(parts)
        logical.append((number, text))
    return logical


def parse_catalog(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            raw_lines = handle.read().splitlines()
    except OSError as exc:
        refuse(
            "%s cannot be read (%s); the declared dependencies are unknown, so pin coverage cannot be checked (SEC-05b)."
            % (path, exc)
        )
        return {}, {}, {}, {}, path

    versions, libraries, plugins, bundles = {}, {}, {}, {}
    targets = {
        "versions": versions,
        "libraries": libraries,
        "plugins": plugins,
        "bundles": bundles,
    }
    section = None

    for number, line in join_multiline(raw_lines):
        header = SECTION_RE.match(line)
        if header:
            section = targets.get(header.group(1).strip())
            continue
        pair = KV_RE.match(line)
        if not pair:
            if section is None:
                # Outside a catalog section, so it cannot declare a dependency.
                continue
            refuse(
                "%s:%d is `%s`, which this gate cannot read. A dependency entry it cannot read is a dependency whose pin it cannot check, so the line is refused rather than ignored (SEC-05b)."
                % (path, number, line)
            )
            continue
        name, raw_value = pair.group(1), pair.group(2).strip()
        if section is None:
            continue
        if section is versions:
            match = STRING_RE.match(raw_value)
            if not match:
                refuse("%s:%d is `%s`, which is not a quoted version string (SEC-05b)." % (path, number, line))
                continue
            versions[name] = match.group(1)
        elif section is bundles:
            match = ARRAY_RE.match(raw_value)
            if not match:
                refuse("%s:%d is `%s`, which is not an array of alias names (SEC-05b)." % (path, number, line))
                continue
            members = []
            for item in split_top_level(match.group(1)):
                entry = STRING_RE.match(item)
                if not entry:
                    refuse(
                        "%s:%d lists %r in a bundle, which is not a plain alias name. Only declared aliases can be resolved offline (SEC-05b)."
                        % (path, number, item)
                    )
                    continue
                members.append(entry.group(1))
            bundles[name] = members
        else:
            table = INLINE_TABLE_RE.match(raw_value)
            if not table:
                refuse(
                    "%s:%d is `%s`, not an inline table (`{ … }`), which is the only library/plugin declaration form this gate can read. Declare it as an inline table so its coordinate can be checked against the pins (SEC-05b)."
                    % (path, number, line)
                )
                continue
            entry = {}
            for item in split_top_level(table.group(1)):
                pair2 = KV_RE.match(item)
                if not pair2:
                    refuse("%s:%d has an entry %r that is not `key = value` (SEC-05b)." % (path, number, item))
                    continue
                value = STRING_RE.match(pair2.group(2).strip())
                if not value:
                    refuse(
                        "%s:%d has `%s` with a value this gate cannot read statically. Use a literal string, or declare the dependency in gradle/libs.versions.toml so the coordinate is checkable (SEC-05b)."
                        % (path, number, pair2.group(1))
                    )
                    continue
                entry[pair2.group(1)] = value.group(1)
            section[name] = entry

    return versions, libraries, plugins, bundles, path


def resolve_version(entry, versions, path, name):
    """The version a catalog entry resolves to, or None."""
    if "version.ref" in entry:
        ref = entry["version.ref"]
        if ref not in versions:
            problems.append(
                "%s: `%s` refers to version.ref %r, which [versions] does not declare. The coordinate of this dependency is therefore unknown and cannot be checked against %s (SEC-05b)."
                % (path, name, ref, METADATA)
            )
            return None
        return versions[ref]
    if "version" in entry:
        return entry["version"]
    problems.append(
        "%s: `%s` declares no version and no version.ref. Its resolved coordinate is unknown, so it cannot be checked against %s (SEC-05b)."
        % (path, name, METADATA)
    )
    return None


def resolve_catalog_entry(entry, versions, path, name):
    """A library table -> "group:name:version", or None."""
    version = resolve_version(entry, versions, path, name)
    if version is None:
        return None

    if "module" in entry:
        coordinate = entry["module"]
        segments = coordinate.split(":")
        if len(segments) != 2:
            problems.append(
                "%s: `%s` declares module %r, which is not `group:name`. Only a fully qualified module can be checked against %s (SEC-05b)."
                % (path, name, coordinate, METADATA)
            )
            return None
        group, artifact = segments
    elif "group" in entry and "name" in entry:
        group, artifact = entry["group"], entry["name"]
    else:
        problems.append(
            "%s: `%s` declares neither `module` nor `group`+`name`. Its group and artifact are unknown, so it cannot be checked against %s (SEC-05b)."
            % (path, name, METADATA)
        )
        return None
    return (group.strip(), artifact.strip(), version.strip())


def alias_to_key(path_expr):
    """`libs.kotlinx.coroutines.core` -> `kotlinx-coroutines-core`.

    Gradle builds a catalog accessor by joining the accessor segments with `-`,
    so a dot in an accessor is a `-` in the alias. `libs.plugins.x` and
    `libs.bundles.x` are looked up in their own section.
    """
    trimmed = path_expr[len("libs."):]
    segments = [segment for segment in trimmed.split(".") if segment]
    if not segments:
        return None, None
    head = segments[0]
    if head in ("plugins", "bundles", "versions"):
        return head, "-".join(segments[1:])
    return "libraries", "-".join(segments)


versions, libraries, plugins, bundles, catalog_path = parse_catalog(CATALOG)


def resolve_alias(path_expr):
    """`libs.…` -> list of "g:n:v" coordinates. Unresolvable -> [] plus a problem."""
    section, key = alias_to_key(path_expr)
    if section is None:
        problems.append("`%s` is not a usable version-catalog accessor (SEC-05b)." % path_expr)
        return []
    if not key:
        problems.append("`%s` names no alias (SEC-05b)." % path_expr)
        return []

    if section == "versions":
        problems.append(
            "`%s` is a version reference, not a dependency coordinate; a version cannot appear where a dependency is declared (SEC-05b)."
            % path_expr
        )
        return []

    if section == "bundles":
        if key not in bundles:
            problems.append("`%s` names bundle %r, which [bundles] does not declare (SEC-05b)." % (path_expr, key))
            return []
        coordinates = []
        for member in bundles[key]:
            coordinates.extend(resolve_alias("libs." + member))
        return coordinates

    if section == "plugins":
        entry = plugins.get(key)
        if entry is None:
            problems.append(
                "`%s` names plugin alias %r, which [plugins] does not declare (SEC-05b)." % (path_expr, key)
            )
            return []
        plugin_id = entry.get("id")
        if not plugin_id:
            problems.append("%s: plugin alias `%s` declares no `id` (SEC-05b)." % (CATALOG, key))
            return []
        version = resolve_version(entry, versions, CATALOG, key)
        if version is None:
            return []
        # A plugin id resolves to its marker artifact on the Gradle Plugin Portal.
        return [(plugin_id, "%s.gradle.plugin" % plugin_id, version)]

    entry = libraries.get(key)
    if entry is None:
        problems.append(
            "`%s` names library alias %r, which [libraries] does not declare (SEC-05b)." % (path_expr, key)
        )
        return []
    coordinate = resolve_catalog_entry(entry, versions, CATALOG, key)
    return [coordinate] if coordinate else []


# --- the pinned components --------------------------------------------------


def local(tag):
    return tag.rsplit("}", 1)[-1]


def pinned_coordinates(path):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        problems.append(
            "%s is not well-formed XML (%s); Gradle cannot read it, so it pins nothing (SEC-05b)." % (path, exc)
        )
        return set()
    except OSError as exc:
        problems.append("%s cannot be read (%s); nothing can be shown to be pinned (SEC-05b)." % (path, exc))
        return set()
    if local(root.tag) != "verification-metadata":
        problems.append("%s has root element <%s>, not <verification-metadata> (SEC-05b)." % (path, local(root.tag)))
        return set()
    pinned = set()
    for section in root:
        if local(section.tag) != "components":
            continue
        for component in section:
            if local(component.tag) != "component":
                continue
            pinned.add(
                (
                    component.get("group", ""),
                    component.get("name", ""),
                    component.get("version", ""),
                )
            )
    return pinned


pinned = pinned_coordinates(METADATA)


# --- exemptions -------------------------------------------------------------


def read_exemptions(path):
    """Notation -> reason. A malformed line is refused, never skipped.

    Used for both exemption lists. The reason is mandatory: a bare entry is a
    hole with no justification attached, which is indistinguishable from a
    deliberate bypass of the control.
    """
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as handle:
            lines = handle.read().splitlines()
    except OSError as exc:
        problems.append(
            "%s cannot be read (%s); the set of entries exempt from this check is unknown, so this gate fails closed rather than approving them all (SEC-05b)."
            % (path, exc)
        )
        return {}

    exemptions = {}
    for number, raw in enumerate(lines, 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        fields = line.split(None, 1)
        if len(fields) != 2 or not fields[1].strip():
            problems.append(
                "%s:%d is `%s`. Every entry needs the declaration text and the reason it is exempt, separated by whitespace — an exemption nobody can justify is indistinguishable from a bypass (SEC-05b)."
                % (path, number, raw.strip())
            )
            continue
        if fields[0] in exemptions:
            problems.append("%s:%d exempts %r twice (SEC-05b)." % (path, number, fields[0]))
            continue
        exemptions[fields[0]] = fields[1].strip()
    return exemptions


exemptions = read_exemptions(EXEMPTIONS)

# --- build script scan ------------------------------------------------------

# Every dependency configuration this gate recognises. A notation it does not
# know about is treated as an unresolvable declaration rather than ignored, so
# a new configuration cannot open a silent hole.
CONFIGURATIONS = frozenset(
    (
        "implementation",
        "api",
        "compileOnly",
        "runtimeOnly",
        "testImplementation",
        "testCompileOnly",
        "testRuntimeOnly",
        "testFixturesImplementation",
        "testFixturesApi",
        "androidTestImplementation",
        "androidTestCompileOnly",
        "androidTestRuntimeOnly",
        "debugImplementation",
        "debugApi",
        "debugCompileOnly",
        "debugRuntimeOnly",
        "releaseImplementation",
        "releaseApi",
        "releaseCompileOnly",
        "releaseRuntimeOnly",
        "lintChecks",
        "lintPublish",
        "annotationProcessor",
        "kapt",
        "kaptTest",
        "ksp",
        "kspTest",
        "detektPlugins",
        "coreLibraryDesugaring",
        "classpath",
    )
)

# Wrappers around another notation: `platform("g:n:v")` and friends.
WRAPPERS = frozenset(("platform", "enforcedPlatform"))

CALLED_RE = re.compile(r"(?<![\w.])(%s)\(" % "|".join(sorted(CONFIGURATIONS | WRAPPERS)))
PLUGIN_ALIAS_RE = re.compile(r"alias\(\s*(libs\.plugins\.[A-Za-z0-9_.\-]+)\s*\)")
PLUGIN_ID_RE = re.compile(r"""\bid\(\s*["']([^"']+)["']\s*\)(?:\s*version\s*["']([^"']+)["'])?""")
STRING_RE = re.compile(r"""["']([^"'\n]+)["']""")

SKIPPED_PREFIXES = ("project(", "files(", "fileTree(", "file(", "layout.")


def strip_comments(line):
    """Drop a `//` comment that is not inside a string literal."""
    out, quote = [], None
    index = 0
    while index < len(line):
        char = line[index]
        if quote:
            out.append(char)
            if char == quote:
                quote = None
        elif char in "\"'":
            quote = char
            out.append(char)
        elif char == "/" and index + 1 < len(line) and line[index + 1] == "/":
            break
        else:
            out.append(char)
        index += 1
    return "".join(out)


def collect_build_scripts(root):
    scripts = []
    skip_dirs = {".git", ".gradle", ".paperclip", "build", "node_modules"}
    for base, directories, files in os.walk(root):
        directories[:] = [name for name in directories if name not in skip_dirs]
        for name in sorted(files):
            if name in ("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle"):
                scripts.append(os.path.relpath(os.path.join(base, name), root))
    return sorted(scripts)


declared = {}  # coordinate -> list of "file:line"
unresolved = {}  # notation -> list of "file:line"


def declare(notation, coordinate, origin):
    declared.setdefault(coordinate, [])
    if origin not in declared[coordinate]:
        declared[coordinate].append(origin)


def declare_unresolved(notation, origin):
    unresolved.setdefault(notation, [])
    if origin not in unresolved[notation]:
        unresolved[notation].append(origin)


def parse_notation(notation, origin, depth=0):
    """Turn the argument of a dependency configuration into coordinates."""
    notation = notation.strip().rstrip(",").strip()
    if not notation:
        return
    if depth > 6:
        problems.append("%s: %s nests dependency notations too deeply to resolve (SEC-05b)." % (origin, notation))
        return

    if notation.startswith(SKIPPED_PREFIXES):
        return
    head_match = CALLED_RE.match(notation)
    if head_match:
        inner = notation[head_match.end() - 1:].strip()
        if inner.endswith(")"):
            inner = inner[1:-1]
        parse_notation(inner, origin, depth + 1)
        return

    if notation.startswith("libs."):
        for coordinate in resolve_alias(notation):
            declare(notation, coordinate, origin)
        return

    literal = STRING_RE.match(notation)
    if literal:
        value = literal.group(1)
        segments = value.split(":")
        if len(segments) == 3 and all(segments):
            declare(notation, (segments[0], segments[1], segments[2]), origin)
            return
        if len(segments) >= 4:
            # group:name:version:classifier — still a real coordinate to pin.
            declare(notation, (segments[0], segments[1], segments[2]), origin)
            return

    declare_unresolved(notation, origin)


scripts = collect_build_scripts(".")
if not scripts:
    problems.append(
        "no Gradle build script (build.gradle.kts, settings.gradle.kts) was found under %s. The declared dependencies are therefore unknown, and an empty result must not be read as 'fully covered' (SEC-05b)."
        % os.getcwd()
    )

for script in scripts:
    try:
        with open(script, "r", encoding="utf-8") as handle:
            text = handle.read()
    except OSError as exc:
        problems.append("%s cannot be read (%s), so the dependencies it declares cannot be checked (SEC-05b)." % (script, exc))
        continue

    lines = text.splitlines()

    # Dependency notations.
    for number, raw in enumerate(lines, 1):
        line = strip_comments(raw)
        for match in CALLED_RE.finditer(line):
            # Take the balanced argument that follows the open paren.
            start = match.end()
            depth = 1
            index = start
            while index < len(line) and depth:
                if line[index] == "(":
                    depth += 1
                elif line[index] == ")":
                    depth -= 1
                index += 1
            argument = line[start:index - 1] if depth == 0 else line[start:]
            parse_notation(argument, "%s:%d" % (script, number))

    # Plugin declarations, including the `apply false` root-project form. A
    # plugin marker is resolved before any project is configured, so an
    # unpinned marker fails every job at once instead of one.
    in_plugins_block = False
    block_indent = 0
    for number, raw in enumerate(lines, 1):
        line = strip_comments(raw)
        stripped = line.strip()
        if not stripped:
            continue
        indent = len(line) - len(line.lstrip())
        if stripped == "plugins {":
            in_plugins_block = True
            block_indent = indent
            continue
        if in_plugins_block:
            if stripped == "}" and indent == block_indent:
                in_plugins_block = False
                continue
            origin = "%s:%d" % (script, number)
            for alias in PLUGIN_ALIAS_RE.findall(line):
                for coordinate in resolve_alias(alias):
                    declare(alias, coordinate, origin)
            for plugin_id, version in PLUGIN_ID_RE.findall(line):
                if not version:
                    declare_unresolved(stripped, origin)
                    continue
                declare(stripped, (plugin_id, "%s.gradle.plugin" % plugin_id, version), origin)

# --- control 1: is every declared coordinate pinned? -------------------------

if not pinned and not problems:
    problems.append("%s pins no component, so no declared dependency can be shown to be covered (SEC-05b)." % METADATA)

missing = []
exempted_coordinates = []
for coordinate in sorted(declared):
    if coordinate in pinned:
        continue
    text = ":".join(coordinate)
    if text in exemptions:
        exempted_coordinates.append(text)
        continue
    missing.append((coordinate, declared[coordinate]))

# --- control 2: unresolvable declarations must be registered ---------------

unexempted = []
for notation in sorted(unresolved):
    if notation in exemptions:
        continue
    unexempted.append((notation, unresolved[notation]))

# --- report -----------------------------------------------------------------

covered = len(declared) - len(missing)

if missing:
    shown = []
    for (group, name, version), origins in missing[:8]:
        shown.append(
            "%s:%s:%s (declared at %s)"
            % (
                group,
                name,
                version,
                ", ".join(origins[:3]) + (", …" if len(origins) > 3 else ""),
            )
        )
    rest = len(missing) - len(shown)
    refuse(
        "%d declared dependenc%s not covered by %s: %s%s.\n"
        "  A build input changed without gradle/verification-metadata.xml being regenerated for it, which is how OPE-220 went red in every job at once. "
        "Regenerate from a machine that reaches BOTH Maven Central and the Gradle Plugin Portal (docs/CI-CD-SECURITY.md §8), or add the declared coordinate to %s with a reason if it is genuinely never resolved."
        % (
            len(missing),
            "y is" if len(missing) == 1 else "ies are",
            METADATA,
            "; ".join(shown),
            ("; +%d more" % rest) if rest > 0 else "",
            EXEMPTIONS,
        )
    )

if unexempted:
    shown = []
    for notation, origins in unexempted[:8]:
        shown.append("%s (%s)" % (notation, ", ".join(origins[:2]) + (", …" if len(origins) > 2 else "")))
    rest = len(unexempted) - len(shown)
    refuse(
        "%d dependency declaration%s cannot be resolved to a coordinate, so this gate cannot tell whether %s pins %s: %s%s.\n"
        "  Ignoring a declaration it cannot read is how a new dependency slips past the pins, so each one must either carry its coordinate (a `group:name:version` literal, or a gradle/libs.versions.toml alias) or be registered in %s with a reason."
        % (
            len(unexempted),
            " is" if len(unexempted) == 1 else "s are",
            METADATA,
            "it" if len(unexempted) == 1 else "them",
            "; ".join(shown),
            ("; +%d more" % rest) if rest > 0 else "",
            EXEMPTIONS,
        )
    )

for problem in problems:
    print(problem)

if missing or unexempted or problems:
    sys.exit(1)

if exempted_coordinates:
    print(
        "  %d declared coordinate(s) have no pin and are registered as exempt in %s: %s."
        % (len(exempted_coordinates), EXEMPTIONS, ", ".join(exempted_coordinates))
    )

print(
    "%s covers all %d declared external dependenc%s (%d pinned, %d exempt with a recorded reason)."
    % (
        METADATA,
        len(declared),
        "y" if len(declared) == 1 else "ies",
        covered,
        len(exempted_coordinates),
    )
)
PY
}

if [ "$have_python" -eq 0 ]; then
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
  python3 - "$WORKFLOWS_DIR" "$WRITE_FLAG" "$REGEN_EXEMPTIONS" <<'PY'
import os
import re
import sys

WORKFLOWS_DIR, WRITE_FLAG, EXEMPTIONS = sys.argv[1], sys.argv[2], sys.argv[3]

problems = []
notes = []

ON_RE = re.compile(r"""^(['"]?)on\1\s*:\s*(.*)$""")
TRIGGER_KEY_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_-]*)\s*:")


def read_exemptions(path):
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as handle:
            lines = handle.read().splitlines()
    except OSError as exc:
        problems.append(
            "%s cannot be read (%s); the set of workflows allowed to regenerate pins is unknown, so this gate fails closed rather than waving them all through (SEC-05b)."
            % (path, exc)
        )
        return {}
    exemptions = {}
    for number, raw in enumerate(lines, 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        fields = line.split(None, 1)
        if len(fields) != 2 or not fields[1].strip():
            problems.append(
                "%s:%d is `%s`. Every entry needs the workflow path and the reason it may regenerate pins — an exemption nobody can justify is indistinguishable from a bypass (SEC-05b)."
                % (path, number, raw.strip())
            )
            continue
        if fields[0] in exemptions:
            problems.append("%s:%d exempts %r twice (SEC-05b)." % (path, number, fields[0]))
            continue
        exemptions[fields[0]] = fields[1].strip()
    return exemptions


def workflow_triggers(lines):
    """The trigger names declared in a workflow's top-level `on:` block.

    The block is located by finding the top-level `on:` key first. Scanning for
    indented keys without doing so would read the file's *first* top-level
    mapping (`name:`, `jobs:`, …) as the end of the trigger block and return
    nothing at all — a workflow that plainly triggers on `pull_request` would
    then look trigger-less, and an exemption meant for a manual-only job would
    be rejected for the wrong reason.
    """
    start = None
    for index, raw in enumerate(lines):
        line = raw.rstrip()
        if line.lstrip().startswith("#"):
            continue
        if ON_RE.match(line):
            start = index
            break
    if start is None:
        return []

    header = ON_RE.match(lines[start].rstrip()).group(2).strip()
    if header.startswith("["):
        # `on: [push]` — the whole trigger list is on one line.
        return [item.strip().strip("\"'") for item in header.strip("[]").split(",") if item.strip()]

    triggers = []
    if header:
        # `on: pull_request`, or a mapping whose first key shares the line.
        triggers.append(header.strip().strip("\"'").rstrip(":").strip())

    for raw in lines[start + 1:]:
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        stripped = line.lstrip()
        indent = len(line) - len(stripped)
        if indent == 0:
            # The block ends at the next top-level key.
            break
        if indent == 2:
            nested = TRIGGER_KEY_RE.match(stripped)
            if nested and nested.group(1) not in triggers:
                triggers.append(nested.group(1))

    return [trigger for trigger in triggers if trigger]


def executing_lines(path):
    """The lines of a workflow that YAML actually gives meaning to.

    A line whose first non-blank character is `#` is a comment: it is never
    executed, and a control that flagged documentation for naming the flag
    would be a control contributors learn to work around. Every other line is
    checked, including a `run:` block, because that is where the flag would be
    used. A comment cannot run a command, so ignoring it loses nothing.
    """
    try:
        with open(path, "r", encoding="utf-8") as handle:
            lines = handle.read().splitlines()
    except OSError as exc:
        problems.append("%s cannot be read (%s) (SEC-05b)." % (path, exc))
        return None
    return [line for line in lines if not line.lstrip().startswith("#")]


exemptions = read_exemptions(EXEMPTIONS)

workflow_paths = []
if os.path.isdir(WORKFLOWS_DIR):
    for name in sorted(os.listdir(WORKFLOWS_DIR)):
        if name.endswith((".yml", ".yaml")):
            workflow_paths.append(os.path.join(WORKFLOWS_DIR, name))

offenders = []
for path in workflow_paths:
    body = executing_lines(path)
    if body is None:
        continue
    if not any(WRITE_FLAG in line for line in body):
        continue
    reason = exemptions.get(path)
    if reason is None:
        offenders.append(
            "%s passes %s. That flag only produces correct pins from a machine reaching BOTH Maven Central and the Gradle Plugin Portal — the two serve different bytes for several plugin marker POMs — so a CI job using it rewrites the dependency pins from an environment nobody reviewed. Run the regeneration locally and land the pins by hand (docs/CI-CD-SECURITY.md §8), or register this workflow in %s with a reason if it is a manual, read-only job."
            % (path, WRITE_FLAG, EXEMPTIONS)
        )
        continue
    triggers = workflow_triggers(body)
    if not triggers:
        problems.append(
            "%s is registered in %s as allowed to regenerate pins, but its `on:` block declares no trigger this gate can read, so it cannot be shown to be manual-only (SEC-05b)."
            % (path, EXEMPTIONS)
        )
        continue
    extra = [trigger for trigger in triggers if trigger != "workflow_dispatch"]
    if extra:
        problems.append(
            "%s is registered in %s as allowed to regenerate pins, but it also triggers on %s. The exemption covers a manual, read-only regeneration only: a job that runs on %s would rewrite the dependency pins from an unreviewed environment, which is the control this gate exists to prevent. Remove the trigger, or drop the %s and regenerate by hand (SEC-05b)."
            % (path, EXEMPTIONS, ", ".join(extra), "/".join(extra), WRITE_FLAG)
        )
        continue
    notes.append(
        "%s may regenerate pins: manual dispatch only, read-only (exemption in %s)." % (path, EXEMPTIONS)
    )

for offender in offenders:
    problems.append(offender)

for problem in problems:
    print(problem)

if problems:
    sys.exit(1)

if notes:
    for note in notes:
        print(note)
print(
    "no workflow under %s passes %s (%d workflow(s) checked; %d exemption(s) in %s)."
    % (WORKFLOWS_DIR, WRITE_FLAG, len(workflow_paths), len(exemptions), EXEMPTIONS)
)
PY
}

if [ "$have_python" -eq 0 ]; then
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