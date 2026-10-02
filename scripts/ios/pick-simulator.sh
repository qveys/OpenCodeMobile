#!/usr/bin/env bash
#
# OPE-169 — print the UDID of an available iPhone simulator, preferring the
# newest installed iOS runtime. Used by .github/workflows/ios-app.yml so the
# job does not hard-code a device name/runtime that may not exist on the runner.
set -euo pipefail

xcrun simctl list devices available -j | python3 -c '
import json, sys

devices = json.load(sys.stdin).get("devices", {})
best = None
for runtime, entries in devices.items():
    if "iOS" not in runtime:
        continue
    for device in entries:
        if not device.get("isAvailable", True):
            continue
        if not device.get("name", "").startswith("iPhone"):
            continue
        # runtime strings sort lexicographically by version (iOS-17-4, ...).
        if best is None or runtime > best[0]:
            best = (runtime, device["udid"], device["name"])

if best is None:
    sys.exit("pick-simulator: no available iPhone simulator runtime found")

print(f"pick-simulator: using {best[2]} on {best[0]}", file=sys.stderr)
print(best[1])
'
