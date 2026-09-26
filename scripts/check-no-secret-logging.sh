#!/usr/bin/env bash
#
# OPE-27 / threat T4 — CI gate: fail the build when secret-bearing fields are
# logged without going through the sanctioned sanitizing logger.
#
# This is deliberately independent of the Gradle build (no JDK required) so it
# can run on every PR and catch a future call site that adds a raw
# `install(Logging)`, a verbose `LogLevel`, or a log call that prints an
# `Authorization`/`Bearer` value or a request/response body.
#
# Sanctioned redaction implementation lives in the `.../logging/` package and is
# exempt; generated OpenAPI code and test sources are not production logging
# call sites and are exempt as well.
#
# Exit 0 when clean, exit 1 when any forbidden pattern is found.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SCAN_DIRS=("shared/networking/src" "shared/data/src")
ALLOW_RE='/(client/generated|logging|commonTest|androidUnitTest|androidInstrumentedTest|iosTest)/'

# --- forbidden patterns (extended regex, matched line-by-line) ----------------

# 1. Verbose Ktor log levels must only be reachable through the sanitizing factory.
PATTERN_VERBOSE_LEVEL='LogLevel[[:space:]]*[.][[:space:]]*(ALL|BODY|HEADERS)'

# 2. Raw Ktor Logging plugin install bypasses the sanitizing logger.
PATTERN_RAW_PLUGIN='install[[:space:]]*[(][[:space:]]*Logging[[:space:]]*[)]'

# 3. A log/print call that carries a credential or a raw request/response body.
PATTERN_SECRET_LOG='(logger?[[:space:]]*[.][[:space:]]*(log|debug|info|warn|error)|println|print|Log[[:space:]]*[.][[:space:]]*[dviwe])[[:space:]]*[(][^)]*(authorization|bearer|request[.]body|response[.]body|[.]body\(\)|prompt|api[_-]?key|access[_-]?token|password)'

fail=0
scanned=0

report() {
  printf '::error file=%s,line=%s::%s\n' "$1" "$2" "$3"
  printf '  %s:%s: %s\n' "$1" "$2" "$4"
  fail=1
}

scan_pattern() {
  local pattern="$1" label="$2"
  while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    local file="${hit%%:*}"
    local rest="${hit#*:}"
    local line="${rest%%:*}"
    local text="${rest#*:}"
    report "$file" "$line" "$label" "$text"
  done < <(grep -rIniE --include='*.kt' -- "$pattern" "${SCAN_DIRS[@]}" 2>/dev/null \
            | grep -vE "$ALLOW_RE" || true)
}

collect_files() {
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    printf '%s\n' "$f"
  done < <(find "${SCAN_DIRS[@]}" -type f -name '*.kt' 2>/dev/null | grep -vE "$ALLOW_RE" || true)
}

# --- run ---------------------------------------------------------------------

printf 'Scanning production networking/data sources for unredacted secret logging...\n'
for f in $(collect_files); do
  scanned=$((scanned + 1))
done

if [ "$scanned" -eq 0 ]; then
  printf '::error::No Kotlin sources found under %s; the scan target moved and this gate is no longer enforcing anything.\n' "${SCAN_DIRS[*]}"
  exit 1
fi

scan_pattern "$PATTERN_VERBOSE_LEVEL" "Verbose Ktor LogLevel outside the sanitizing factory"
scan_pattern "$PATTERN_RAW_PLUGIN" "Raw install(Logging) bypasses the sanitizing logger"
scan_pattern "$PATTERN_SECRET_LOG" "Log/print call carries a credential or raw body"

# Positive check: the sanctioned factory must keep redacting headers.
SANITIZING_DIR='shared/networking/src/commonMain/kotlin/org/opencodemobile/shared/networking/logging'
if ! grep -rq 'sanitizeHeader' "$SANITIZING_DIR" 2>/dev/null; then
  printf '::error::Sanctioned logging factory must call sanitizeHeader; header redaction is not enforced.\n'
  fail=1
fi
if ! grep -rq 'LogRedactor' "$SANITIZING_DIR" 2>/dev/null; then
  printf '::error::SanitizingHttpLogger must route every line through LogRedactor.\n'
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  printf '\nFAIL: secret-bearing HTTP logging detected (threat T4).\n'
  printf 'Route logging through installSanitizingLogging / SanitizingHttpLogger instead.\n'
  exit 1
fi

printf 'OK: scanned %d files; no unredacted secret logging (T4).\n' "$scanned"
exit 0