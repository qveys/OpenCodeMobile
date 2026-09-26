#!/usr/bin/env bash
# verify-r3-generated-types.sh — enforce architecture Rule R3.
#
# Rule R3 (Cahier des charges v1.0 §5.2 / §14.2, ADR-0002 §4):
#   "Never expose generated OpenCode API types outside the Data layer."
#   The generated client lives exclusively in shared/networking; no other
#   Kotlin module may import org.opencode.mobile.networking.client.generated.*
#
# Rule R12: generated code is never hand-edited.
#
# This script is the CI gate for those two rules. It fails (exit 1) on any
# violation so a forbidden import or a hand-edited generated file blocks merge.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

GENERATED_PACKAGE="org.opencode.mobile.networking.client.generated"
GENERATED_DIR="shared/networking/src/commonMain/kotlin/org/opencode/mobile/networking/client/generated"

fail=0
note() { printf '%s\n' "$*"; }

note "=== verify-r3-generated-types.sh ==="

# ---------------------------------------------------------------------------
# 1. Rule R3 — no generated-package import outside shared/networking.
# ---------------------------------------------------------------------------
note "[R3] Checking that '${GENERATED_PACKAGE}' is imported only from shared/networking/ ..."

r3_violations="$(
  grep -rn --include='*.kt' "import ${GENERATED_PACKAGE}" . \
    | grep -v '^\./shared/networking/' \
    | grep -v '^\./build/' \
    || true
)"

if [ -n "${r3_violations}" ]; then
  note "FAIL: generated types imported outside shared/networking:"
  printf '%s\n' "${r3_violations}"
  fail=1
else
  note "OK: no generated-package import outside shared/networking/."
fi

# ---------------------------------------------------------------------------
# 2. Rule R12 — generated files carry the auto-generated banner.
#    (Cheap hand-edit tripwire; the authoritative check is regeneration.)
# ---------------------------------------------------------------------------
note "[R12] Checking that generated files carry the AUTO-GENERATED banner ..."

if [ -d "${GENERATED_DIR}" ]; then
  while IFS= read -r -d '' f; do
    if ! head -5 "$f" | grep -q 'AUTO-GENERATED CODE - DO NOT MODIFY BY HAND'; then
      note "FAIL: missing auto-generated banner: $f"
      fail=1
    fi
  done < <(find "${GENERATED_DIR}" -name '*.kt' -print0)
  note "OK: banner present on generated files (if any violations, listed above)."
else
  note "WARN: generated dir not found at ${GENERATED_DIR}; skipping banner check."
fi

# ---------------------------------------------------------------------------
# 3. Rule R12 (authoritative) — regenerate and require a clean diff.
#    Skipped when python3 is unavailable so the banner/import checks still run.
# ---------------------------------------------------------------------------
hash_generated() {
  if [ -d "${GENERATED_DIR}" ]; then
    find "${GENERATED_DIR}" -name '*.kt' -print0 | sort -z | xargs -0 sha256sum 2>/dev/null | sha256sum | awk '{print $1}'
  else
    echo "MISSING"
  fi
}

if command -v python3 >/dev/null 2>&1; then
  note "[R12] Regenerating client and checking for drift ..."
  before="$(hash_generated)"
  python3 "${SCRIPT_DIR}/generate-openapi-client.py" >/dev/null
  after="$(hash_generated)"
  if [ "${before}" != "${after}" ]; then
    note "FAIL: regeneration changed ${GENERATED_DIR} (hand-edited generated code?)."
    note "      before=${before} after=${after}"
    fail=1
  else
    note "OK: regeneration produced no drift."
  fi
else
  note "WARN: python3 not found; skipping regeneration drift check."
fi

if [ "${fail}" -ne 0 ]; then
  note "=== R3/R12 verification FAILED ==="
  exit 1
fi

note "=== R3/R12 verification passed ==="