#!/usr/bin/env bash
# scripts/ci/with-t1-tls-servers.sh — run a command with the T1 real-handshake
# TLS servers up.
#
# `IosTlsHandshakePinningTest` (shared/security/src/iosTest) cannot build an
# in-process TLS server with public Kotlin/Native APIs, so it dials
# `https://127.0.0.1:<port>` against the host-side recorder
# `tests/t1/tls_recording_server.py`: port 9943 presents the genuine cert A,
# port 9944 the swapped cert B. The T1 device-validation workflow starts those
# servers inside `scripts/t1/run-ios-simulator-validation.sh`; the generic iOS
# test job (OPE-17) uses this wrapper so the same suite is green there too.
#
# Usage:
#   bash scripts/ci/with-t1-tls-servers.sh <command> [args...]
#
# Exits with the wrapped command's exit status. On failure the captured server
# output is printed to help diagnose a fixture problem.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [ "$#" -eq 0 ]; then
    echo "usage: bash scripts/ci/with-t1-tls-servers.sh <command> [args...]" >&2
    exit 2
fi

CERT_DIR="$ROOT/tests/t1/certs"
SERVER="$ROOT/tests/t1/tls_recording_server.py"
PORT_A="${T1_PORT_A:-9943}"
PORT_B="${T1_PORT_B:-9944}"
TMP_DIR="$(mktemp -d "${RUNNER_TEMP:-/tmp}/t1-servers.XXXXXX")"

python3 "$SERVER" \
    --cert "$CERT_DIR/cert-a.pem" --key "$CERT_DIR/key-a-pkcs8.pem" \
    --port "$PORT_A" --log "$TMP_DIR/pinned-a.log" >"$TMP_DIR/server-a.out" 2>&1 &
PID_A=$!
python3 "$SERVER" \
    --cert "$CERT_DIR/cert-b.pem" --key "$CERT_DIR/key-b-pkcs8.pem" \
    --port "$PORT_B" --log "$TMP_DIR/swapped-b.log" >"$TMP_DIR/server-b.out" 2>&1 &
PID_B=$!

cleanup() { kill "$PID_A" "$PID_B" 2>/dev/null || true; }
trap cleanup EXIT

# Give the servers a moment to bind, and fail early if one died instead of
# letting every handshake test fail with a misleading "could not connect".
sleep 2
if ! kill -0 "$PID_A" 2>/dev/null || ! kill -0 "$PID_B" 2>/dev/null; then
    echo "error: T1 TLS test servers did not stay up" >&2
    cat "$TMP_DIR/server-a.out" "$TMP_DIR/server-b.out" >&2
    exit 1
fi

echo "== T1 TLS servers up (A=$PORT_A genuine, B=$PORT_B swapped) =="
"$@"
status=$?

if [ "$status" -ne 0 ]; then
    echo "== T1 TLS server output (wrapped command exited $status) ==" >&2
    cat "$TMP_DIR/server-a.out" "$TMP_DIR/server-b.out" >&2
fi

exit "$status"
