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
