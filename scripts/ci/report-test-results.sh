#!/usr/bin/env bash
# scripts/ci/report-test-results.sh — summarize Gradle test results in the log.
#
# OPE-17 (test step). Gradle writes JUnit XML for every test task under
# `<module>/build/test-results/**`, but the org Actions allowlist permits no
# `actions/upload-artifact`, so the raw reports cannot be downloaded. This
# script inlines them: a per-suite count table plus the full name, message and
# stack trace of every failed/errored test case. A developer reading only the
# CI job log can then tell exactly what failed and why.
#
# It is meant to run as an always-run step after a test step, so it must never
# fail the job: it always exits 0, and it prints a clear "no reports found"
# message when the run died before any test executed (compile/config error).
#
# Usage:
#   bash scripts/ci/report-test-results.sh [--root DIR]
#
#   --root DIR   directory to scan (default: repository root)
#
# Requires: python3 (present on the GitHub-hosted runners used by build.yml).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
while [ "$#" -gt 0 ]; do
    case "$1" in
        --root) ROOT="${2:?--root needs a directory}"; shift 2 ;;
        -h | --help)
            sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "report-test-results: unknown argument '$1'" >&2
            exit 2
            ;;
    esac
done

if ! command -v python3 >/dev/null 2>&1; then
    echo "report-test-results: python3 not found; skipping the report."
    echo "See the test step output above for the Gradle failures."
    exit 0
fi

python3 - "$ROOT" <<'PY'
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_DETAIL_CHARS = 6000

root = os.path.abspath(sys.argv[1])
patterns = (
    os.path.join(root, "**", "build", "test-results", "**", "*.xml"),
    os.path.join(root, "**", "build", "test-results", "*.xml"),
)
files = sorted({path for pattern in patterns for path in glob.glob(pattern, recursive=True)})

print("== Test results ==")
print(f"Search root: {root}")

if not files:
    print("No JUnit XML reports found.")
    print("The run probably failed before any test executed (compile or")
    print("configuration error) — see the Gradle output in the step above.")
    print("== Test results: 0 report files ==")
    sys.exit(0)


def as_int(element, attribute):
    try:
        return int(element.get(attribute) or 0)
    except ValueError:
        return 0


suites = []
for path in files:
    try:
        document = ET.parse(path)
    except ET.ParseError as exc:
        print(f"  (cannot parse {os.path.relpath(path, root)}: {exc})")
        continue
    document_root = document.getroot()
    if document_root.tag == "testsuite":
        elements = [document_root]
    else:
        elements = document_root.findall("testsuite")
    for element in elements:
        suites.append((os.path.relpath(path, root), element))

print(f"Report files: {len(files)}")
print(f"{'Suite':<58} {'tests':>6} {'fail':>5} {'error':>5} {'skip':>5}")

totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
for rel_path, suite in suites:
    counts = {name: as_int(suite, name) for name in totals}
    for name, value in counts.items():
        totals[name] += value
    label = suite.get("name") or rel_path
    if len(label) > 58:
        label = label[:55] + "..."
    print(
        f"{label:<58} {counts['tests']:>6} {counts['failures']:>5} "
        f"{counts['errors']:>5} {counts['skipped']:>5}"
    )

print(
    f"\nTotals: tests={totals['tests']} failures={totals['failures']} "
    f"errors={totals['errors']} skipped={totals['skipped']}"
)

failures = []
for rel_path, suite in suites:
    for case in suite.findall("testcase"):
        for kind in ("failure", "error"):
            node = case.find(kind)
            if node is not None:
                failures.append((rel_path, case, kind, node))
                break

if not failures:
    print("No failing tests in the captured reports.")
    sys.exit(0)

print(f"\n== {len(failures)} failing test(s) — details ==")
for rel_path, case, kind, node in failures:
    classname = case.get("classname") or "<unknown-class>"
    testname = case.get("name") or "<unknown-test>"
    message = node.get("message") or ""
    print("\n" + "-" * 72)
    print(f"{kind.upper()}: {classname}.{testname}")
    print(f"report: {rel_path}")
    if message:
        print(f"message: {message}")
    detail = (node.text or "").strip()
    if detail:
        print("detail:")
        print(detail[:MAX_DETAIL_CHARS])
    system_out = case.find("system-out")
    if system_out is not None and (system_out.text or "").strip():
        print("system-out:")
        print((system_out.text or "").strip()[:MAX_DETAIL_CHARS])

sys.exit(0)
PY

# The test step owns the job verdict; this reporter only adds context.
exit 0
