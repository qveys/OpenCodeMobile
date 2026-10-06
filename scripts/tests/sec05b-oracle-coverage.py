# SEC-05b oracle — TEST ONLY, never executed by the gate.
#
# OPE-264 moved scripts/check-verification-metadata-coverage.sh off python3 onto
# perl, because the digest-pinned eclipse-temurin image used by `T4 static scan`
# carries no python3 (ADR 0007). Moving a supply-chain control onto a different
# implementation is only safe if the new one reaches the same verdicts, so this file
# keeps the original python3 body — extracted verbatim from the gate before the port
# and never edited — as the thing the perl must agree with.
#
# scripts/tests/test-supply-chain-gates.sh runs both over the same fixture corpus,
# the hostile fixtures included, and diffs their output. A change to the perl that
# the python3 does not make turns that test red.
#
# Frozen. If you believe a rule or a message here is wrong, change the gate and this
# file in the same commit, and say why in the pull request: a gate whose only
# definition of correct is its own output protects nothing.
#
# The gate does not read, ship or require this file, and CI never runs it: the image
# has no python3. It runs in the local suite and in review, which is where the
# comparison has to happen.

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
