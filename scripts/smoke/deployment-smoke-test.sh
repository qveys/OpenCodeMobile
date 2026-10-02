#!/usr/bin/env bash
# scripts/smoke/deployment-smoke-test.sh
#
# OPE-20 — Post-deployment smoke test.
#
# After each deployment this script verifies that the deployed environment is
# healthy and running the version that was actually deployed:
#
#   1. Key endpoints answer with the expected HTTP status (with bounded
#      retries to absorb rollout warm-up).
#   2. The deployed version reported by the environment matches the version
#      built from the deployed revision.
#   3. On any failure the script exits non-zero, emits GitHub annotations and
#      (when configured) posts an alert to a webhook. A machine-readable JSON
#      report is written for the workflow run.
#
# The script has no dependencies beyond bash, curl and python3, which are all
# present on GitHub-hosted runners. It never logs credential material.
#
# Usage:
#   bash scripts/smoke/deployment-smoke-test.sh \
#     --base-url https://staging.example.test \
#     --expected-version 0.1.0
#
# Every option has a DEPLOY_SMOKE_* environment variable equivalent so the
# workflow can configure it without argument plumbing.

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: deployment-smoke-test.sh --base-url URL --expected-version VERSION [options]

Required:
  --base-url URL            Base URL of the deployed environment.
  --expected-version VER    Version the deployment must report.

Checks:
  --endpoint PATH           Health endpoint to check. Repeatable.
                            Default: /health (or $DEPLOY_SMOKE_ENDPOINTS,
                            comma-separated).
  --version-path PATH       Path serving deployment version metadata.
                            Default: /version
  --version-field FIELD     JSON field in the version response that holds the
                            version. Default: version
  --method METHOD           HTTP method for health checks. Default: GET
  --expect-status CODE      Expected HTTP status for health checks. Default: 200

Timing:
  --timeout SECONDS         Per-request timeout. Default: 10
  --retries N               Attempts per check. Default: 5
  --retry-delay SECONDS     Base backoff between attempts (doubles each try).
                            Default: 3

Alerting / output:
  --alert-webhook URL       POST a JSON alert here when the check fails.
  --report FILE             Write the JSON report here.
                            Default: smoke-report.json
  --dry-run                 Print the resolved plan and exit 0. No network.
  -h, --help                Show this help.

Exit codes: 0 success, 1 check failed, 2 invalid configuration.
EOF
}

log() { printf '%s\n' "$*" >&2; }
die_config() { log "::error::$*"; exit 2; }

# ---------------------------------------------------------------- defaults ---
BASE_URL="${DEPLOY_SMOKE_BASE_URL:-}"
EXPECTED_VERSION="${DEPLOY_SMOKE_EXPECTED_VERSION:-}"
VERSION_PATH="${DEPLOY_SMOKE_VERSION_PATH:-/version}"
VERSION_FIELD="${DEPLOY_SMOKE_VERSION_FIELD:-version}"
METHOD="${DEPLOY_SMOKE_METHOD:-GET}"
EXPECT_STATUS="${DEPLOY_SMOKE_EXPECT_STATUS:-200}"
TIMEOUT="${DEPLOY_SMOKE_TIMEOUT:-10}"
RETRIES="${DEPLOY_SMOKE_RETRIES:-5}"
RETRY_DELAY="${DEPLOY_SMOKE_RETRY_DELAY:-3}"
ALERT_WEBHOOK="${DEPLOY_SMOKE_ALERT_WEBHOOK:-}"
REPORT="${DEPLOY_SMOKE_REPORT:-smoke-report.json}"
DRY_RUN=0

ENDPOINTS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --base-url)         BASE_URL="${2:?--base-url needs a value}"; shift 2 ;;
    --expected-version) EXPECTED_VERSION="${2:?--expected-version needs a value}"; shift 2 ;;
    --endpoint)         ENDPOINTS+=("${2:?--endpoint needs a value}"); shift 2 ;;
    --version-path)     VERSION_PATH="${2:?--version-path needs a value}"; shift 2 ;;
    --version-field)    VERSION_FIELD="${2:?--version-field needs a value}"; shift 2 ;;
    --method)           METHOD="${2:?--method needs a value}"; shift 2 ;;
    --expect-status)    EXPECT_STATUS="${2:?--expect-status needs a value}"; shift 2 ;;
    --timeout)          TIMEOUT="${2:?--timeout needs a value}"; shift 2 ;;
    --retries)          RETRIES="${2:?--retries needs a value}"; shift 2 ;;
    --retry-delay)      RETRY_DELAY="${2:?--retry-delay needs a value}"; shift 2 ;;
    --alert-webhook)    ALERT_WEBHOOK="${2:?--alert-webhook needs a value}"; shift 2 ;;
    --report)           REPORT="${2:?--report needs a value}"; shift 2 ;;
    --dry-run)          DRY_RUN=1; shift ;;
    -h|--help)          usage; exit 0 ;;
    *)                  log "::error::unknown argument: $1"; usage >&2; exit 2 ;;
  esac
done

# Endpoints may also arrive as a comma/space separated env value.
if [ "${#ENDPOINTS[@]}" -eq 0 ]; then
  raw="${DEPLOY_SMOKE_ENDPOINTS:-/health}"
  IFS=', ' read -r -a ENDPOINTS <<<"$raw"
fi

# Normalise the base URL (no trailing slash) so path joins are predictable.
BASE_URL="${BASE_URL%/}"

# Drop a leading "v" so "v0.1.0" and "0.1.0" compare equal.
normalize_version() { printf '%s' "${1#v}"; }

if [ "$DRY_RUN" -eq 0 ]; then
  [ -n "$BASE_URL" ] || die_config "--base-url (or DEPLOY_SMOKE_BASE_URL) is required"
  [ -n "$EXPECTED_VERSION" ] || die_config "--expected-version (or DEPLOY_SMOKE_EXPECTED_VERSION) is required"
fi

EXPECTED_VERSION_NORM="$(normalize_version "$EXPECTED_VERSION")"

if [ "$DRY_RUN" -eq 1 ]; then
  log "Deployment smoke test (dry run)"
  log "  base-url:         ${BASE_URL:-<unset>}"
  log "  expected-version: ${EXPECTED_VERSION:-<unset>}"
  log "  endpoints:        ${ENDPOINTS[*]}"
  log "  version-path:     ${VERSION_PATH} (field: ${VERSION_FIELD})"
  log "  expect-status:    ${EXPECT_STATUS}"
  log "  timeout/retries:  ${TIMEOUT}s / ${RETRIES} (backoff ${RETRY_DELAY}s)"
  log "  alert-webhook:    $([ -n "$ALERT_WEBHOOK" ] && echo configured || echo none)"
  log "  report:           ${REPORT}"
  exit 0
fi

# --------------------------------------------------------------- execution ---
RESULTS_TSV="$(mktemp)"
BODY_FILE="$(mktemp)"
trap 'rm -f "$RESULTS_TSV" "$BODY_FILE"' EXIT

FAILED=0

record() { # name, target, status, detail
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >>"$RESULTS_TSV"
}

# fetch URL -> writes body to BODY_FILE, prints the HTTP status code.
fetch() { # url
  local code
  code="$(curl -sS -o "$BODY_FILE" -w '%{http_code}' \
    --max-time "$TIMEOUT" -X "$METHOD" "$1" 2>/dev/null)" || code="000"
  printf '%s' "${code:-000}"
}

http_status() { # url
  local url="$1" attempt=1 delay="$RETRY_DELAY" code=""
  while :; do
    code="$(fetch "$url")"
    if [ "$code" = "$EXPECT_STATUS" ]; then
      printf '%s' "$code"
      return 0
    fi
    if [ "$attempt" -ge "$RETRIES" ]; then
      printf '%s' "$code"
      return 1
    fi
    log "  attempt ${attempt}/${RETRIES}: ${url} -> HTTP ${code}; retrying in ${delay}s"
    sleep "$delay"
    attempt=$((attempt + 1))
    delay=$((delay * 2))
  done
}

extract_version() { # body-file -> prints the version field or the trimmed body
  python3 -c '
import json, sys

field, path = sys.argv[1], sys.argv[2]
try:
    raw = open(path, encoding="utf-8").read()
except OSError:
    raw = ""
try:
    data = json.loads(raw)
except Exception:
    # Not JSON: fall back to the trimmed body so plain-text version endpoints work.
    print(raw.strip())
    raise SystemExit(0)
value = data
for part in field.split("."):
    if isinstance(value, dict) and part in value:
        value = value[part]
    else:
        value = None
        break
print("" if value is None else str(value).strip())
' "$VERSION_FIELD" "$1" 2>/dev/null || true
}

log "Deployment smoke test starting"
log "  base-url:         ${BASE_URL}"
log "  expected-version: ${EXPECTED_VERSION_NORM}"

# 1. Health of key endpoints.
for endpoint in "${ENDPOINTS[@]}"; do
  [ -n "$endpoint" ] || continue
  url="${BASE_URL}/${endpoint#/}"
  if code="$(http_status "$url")" && [ "$code" = "$EXPECT_STATUS" ]; then
    log "  PASS endpoint ${endpoint} -> HTTP ${code}"
    record "endpoint:${endpoint}" "$url" "pass" "HTTP ${code}"
  else
    log "::error::endpoint ${endpoint} failed: HTTP ${code:-000} (expected ${EXPECT_STATUS}) at ${url}"
    record "endpoint:${endpoint}" "$url" "fail" "HTTP ${code:-000} (expected ${EXPECT_STATUS})"
    FAILED=1
  fi
done

# 2. Correct version is deployed.
VERSION_URL="${BASE_URL}/${VERSION_PATH#/}"
if version_code="$(http_status "$VERSION_URL")" && [ "$version_code" = "$EXPECT_STATUS" ]; then
  observed="$(extract_version "$BODY_FILE")"
  observed_norm="$(normalize_version "$observed")"
  if [ -n "$observed_norm" ] && [ "$observed_norm" = "$EXPECTED_VERSION_NORM" ]; then
    log "  PASS version ${observed_norm}"
    record "version" "$VERSION_URL" "pass" "observed ${observed_norm}"
  else
    log "::error::version mismatch at ${VERSION_URL}: observed '${observed_norm:-<empty>}' expected '${EXPECTED_VERSION_NORM}'"
    record "version" "$VERSION_URL" "fail" "observed '${observed_norm:-<empty>}' expected '${EXPECTED_VERSION_NORM}'"
    FAILED=1
  fi
else
  log "::error::version endpoint failed: HTTP ${version_code:-000} (expected ${EXPECT_STATUS}) at ${VERSION_URL}"
  record "version" "$VERSION_URL" "fail" "HTTP ${version_code:-000} (expected ${EXPECT_STATUS})"
  FAILED=1
fi

STATUS="success"
[ "$FAILED" -eq 0 ] || STATUS="failure"

# ------------------------------------------------------------- json report ---
python3 - "$RESULTS_TSV" "$REPORT" "$STATUS" "$BASE_URL" "$EXPECTED_VERSION_NORM" <<'PY'
import json, sys, datetime

tsv, report_path, status, base_url, expected = sys.argv[1:6]
checks = []
with open(tsv, encoding="utf-8") as fh:
    for line in fh:
        line = line.rstrip("\n")
        if not line:
            continue
        name, target, outcome, detail = (line.split("\t") + ["", "", "", ""])[:4]
        checks.append({"name": name, "target": target, "status": outcome, "detail": detail})

report = {
    "status": status,
    "baseUrl": base_url,
    "expectedVersion": expected,
    "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "checks": checks,
}
with open(report_path, "w", encoding="utf-8") as fh:
    json.dump(report, fh, indent=2)
    fh.write("\n")

if summary := __import__("os").environ.get("GITHUB_STEP_SUMMARY"):
    try:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(f"## Deployment smoke test — {status}\n\n")
            fh.write(f"- Environment: `{base_url}`\n- Expected version: `{expected}`\n\n")
            fh.write("| Check | Status | Detail |\n| --- | --- | --- |\n")
            for c in checks:
                fh.write(f"| `{c['name']}` | {c['status']} | {c['detail']} |\n")
    except OSError:
        pass
PY
log "  report written to ${REPORT}"

if [ "$FAILED" -eq 0 ]; then
  log "Deployment smoke test PASSED (${#ENDPOINTS[@]} endpoint(s) + version check)"
  exit 0
fi

# ------------------------------------------------------------------ alert ---
if [ -n "$ALERT_WEBHOOK" ]; then
  alert_text="DEPLOYMENT SMOKE TEST FAILED: ${BASE_URL} (expected version ${EXPECTED_VERSION_NORM}). See run report ${REPORT}."
  payload="$(python3 - "$alert_text" "$STATUS" "$BASE_URL" "$EXPECTED_VERSION_NORM" <<'PY'
import json, sys
text, status, base_url, expected = sys.argv[1:5]
print(json.dumps({"text": text, "status": status, "baseUrl": base_url, "expectedVersion": expected}))
PY
)"
  if curl -sS --max-time "$TIMEOUT" -X POST -H 'Content-Type: application/json' \
    --data "$payload" "$ALERT_WEBHOOK" >/dev/null 2>&1; then
    log "  alert posted to configured webhook"
  else
    log "::warning::failed to post alert to configured webhook"
  fi
fi

log "Deployment smoke test FAILED"
exit 1
