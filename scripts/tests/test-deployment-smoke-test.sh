#!/usr/bin/env bash
# scripts/tests/test-deployment-smoke-test.sh
#
# OPE-20 — self-test for scripts/smoke/deployment-smoke-test.sh.
#
# Runs the smoke test against a throwaway local HTTP server so every branch
# (healthy deployment, version drift, unhealthy endpoint, transient warm-up,
# alert delivery, bad configuration) is exercised without a real environment.
# Only bash, curl and python3 are required.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SMOKE="${ROOT}/scripts/smoke/deployment-smoke-test.sh"

TMP_DIR="$(mktemp -d)"
SERVER_PID=""
cleanup() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

PASS=0
FAIL=0
check() { # description, actual, expected
  if [ "$2" = "$3" ]; then
    printf '[✓] %s\n' "$1"
    PASS=$((PASS + 1))
  else
    printf '[✗] %s (expected %s, got %s)\n' "$1" "$3" "$2"
    FAIL=$((FAIL + 1))
  fi
}

# JSON helpers use python3 (already required) instead of jq, so the suite keeps
# its documented "bash, curl and python3 only" dependency set.
json_field() { # file, top-level key
  python3 -c 'import json, sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], ""))' "$1" "$2"
}
json_check_names() { # file -> sorted, comma-joined check names
  python3 -c 'import json, sys; print(",".join(sorted(c["name"] for c in json.load(open(sys.argv[1]))["checks"])))' "$1"
}
json_check_status() { # file, check name -> status
  python3 -c 'import json, sys; name = sys.argv[2]; print(next((c.get("status", "") for c in json.load(open(sys.argv[1]))["checks"] if c.get("name") == name), ""))' "$1" "$2"
}

[ -f "$SMOKE" ] || { echo "smoke script not found: $SMOKE" >&2; exit 1; }
chmod +x "$SMOKE"

# ------------------------------------------------------------ mock server ---
cat >"$TMP_DIR/mock-deployment-server.py" <<'PY'
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1])
VERSION = sys.argv[2] if len(sys.argv) > 2 else "0.1.0"
ALERT_FILE = sys.argv[3] if len(sys.argv) > 3 else ""
FLIGHTS = {"flaky": 0}
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="text/plain"):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/health":
            self._send(200, "ok")
        elif path == "/version":
            self._send(200, json.dumps({"version": VERSION}), "application/json")
        elif path == "/unhealthy":
            self._send(500, "boom")
        elif path == "/flaky":
            with lock:
                FLIGHTS["flaky"] += 1
                attempt = FLIGHTS["flaky"]
            # Fail the first two attempts, then become healthy (rollout warm-up).
            self._send(200, "ok") if attempt > 2 else self._send(503, "warming up")
        else:
            self._send(404, "not found")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8", "replace")
        if ALERT_FILE:
            with open(ALERT_FILE, "w", encoding="utf-8") as fh:
                fh.write(body)
        self._send(200, json.dumps({"ok": True}), "application/json")

    def log_message(self, *args):
        pass


port = PORT
for _ in range(50):
    try:
        server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        break
    except OSError:
        port += 1
else:
    sys.exit(1)
print(port, flush=True)
server.serve_forever()
PY

free_port() {
  python3 - <<'PY'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PY
}

start_server() { # version, alert_file -> sets BASE
  local version="$1" alert_file="${2:-}" port
  port="$(free_port)"
  python3 "$TMP_DIR/mock-deployment-server.py" "$port" "$version" "$alert_file" \
    >"$TMP_DIR/port" 2>"$TMP_DIR/server.log" &
  SERVER_PID=$!
  local waited=0
  until curl -fsS "http://127.0.0.1:${port}/health" >/dev/null 2>&1; do
    sleep 0.2
    waited=$((waited + 1))
    [ "$waited" -ge 50 ] && { echo "mock server failed to start" >&2; cat "$TMP_DIR/server.log" >&2; return 1; }
  done
  BASE="http://127.0.0.1:${port}"
}

stop_server() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
  wait "$SERVER_PID" 2>/dev/null
  SERVER_PID=""
}

echo "== deployment smoke test self-test =="

# --- configuration errors ---
"$SMOKE" --help >/dev/null 2>&1; check "--help exits 0" "$?" "0"
"$SMOKE" --report "$TMP_DIR/r.json" >/dev/null 2>&1; check "missing --base-url exits 2" "$?" "2"
"$SMOKE" --base-url "http://x" --report "$TMP_DIR/r.json" >/dev/null 2>&1; check "missing --expected-version exits 2" "$?" "2"
"$SMOKE" --dry-run >/dev/null 2>&1; check "--dry-run without config exits 0" "$?" "0"

# --- healthy deployment, version matches ---
start_server "0.1.0"
"$SMOKE" --base-url "$BASE" --expected-version "0.1.0" \
  --endpoint /health --report "$TMP_DIR/ok.json" --retries 2 --retry-delay 0 >/dev/null 2>&1
check "healthy deployment exits 0" "$?" "0"
check "report status success" "$(json_field "$TMP_DIR/ok.json" status)" "success"
check "report records endpoint+version" "$(json_check_names "$TMP_DIR/ok.json")" "endpoint:/health,version"

# --- version drift ---
"$SMOKE" --base-url "$BASE" --expected-version "9.9.9" \
  --endpoint /health --report "$TMP_DIR/drift.json" --retries 2 --retry-delay 0 >/dev/null 2>&1
check "version mismatch exits 1" "$?" "1"
check "version mismatch report" "$(json_check_status "$TMP_DIR/drift.json" version)" "fail"

# --- unhealthy key endpoint ---
"$SMOKE" --base-url "$BASE" --expected-version "0.1.0" \
  --endpoint /unhealthy --report "$TMP_DIR/bad.json" --retries 2 --retry-delay 0 >/dev/null 2>&1
check "unhealthy endpoint exits 1" "$?" "1"
check "unhealthy endpoint report" "$(json_check_status "$TMP_DIR/bad.json" endpoint:/unhealthy)" "fail"

# --- transient warm-up recovers via retries ---
"$SMOKE" --base-url "$BASE" --expected-version "0.1.0" \
  --endpoint /flaky --report "$TMP_DIR/flaky.json" --retries 4 --retry-delay 0 >/dev/null 2>&1
check "flaky endpoint recovers with retries" "$?" "0"

# --- alert webhook fires on failure ---
stop_server
ALERT_FILE="$TMP_DIR/alert.json"
start_server "0.1.0" "$ALERT_FILE"
"$SMOKE" --base-url "$BASE" --expected-version "9.9.9" \
  --report "$TMP_DIR/al.json" --alert-webhook "${BASE}/alert" --retries 1 --retry-delay 0 >/dev/null 2>&1
check "alerting failure exits 1" "$?" "1"
check "alert webhook received payload" "$( [ -s "$ALERT_FILE" ] && json_field "$ALERT_FILE" status || echo missing )" "failure"
stop_server

echo
echo "== results: ${PASS} passed, ${FAIL} failed =="
[ "$FAIL" -eq 0 ]
