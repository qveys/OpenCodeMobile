#!/usr/bin/env bash
# scripts/verify-design-system-sources.sh
# Verifies the byte-for-byte design-system sources under design-system/ against
# design-system/SOURCES.sha256. Returns 0 on match, 1 on any mismatch or missing file.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DS="$ROOT/design-system"
SUMS="$DS/SOURCES.sha256"

if [ ! -f "$SUMS" ]; then
  echo "[-] Missing $SUMS"
  exit 1
fi

echo "=========================================================="
echo "Verifying design-system sources in: $DS"
echo "=========================================================="

failures=0
while read -r expected path; do
  [ -z "$path" ] && continue
  file="$DS/$path"
  if [ ! -f "$file" ]; then
    echo "[-] MISSING: $path"
    failures=$((failures + 1))
    continue
  fi
  actual="$(sha256sum "$file" | cut -d' ' -f1)"
  if [ "$actual" = "$expected" ]; then
    echo "[✓] OK: $path"
  else
    echo "[-] MISMATCH: $path"
    echo "      expected $expected"
    echo "      actual   $actual"
    failures=$((failures + 1))
  fi
done < "$SUMS"

echo "=========================================================="
if [ "$failures" -eq 0 ]; then
  echo "[✓] PASS: all design-system sources match SOURCES.sha256"
  exit 0
fi
echo "[-] FAILED: $failures source(s) did not match"
exit 1