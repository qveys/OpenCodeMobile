#!/usr/bin/env bash
#
# T1 (OPE-94) — iOS simulator real-handshake validation.
#
# An in-process TLS server cannot be built with public Kotlin/Native APIs, so
# this script starts a host-side TLS recording server on two ports:
#   - port 9943 presents the "genuine" certificate A,
#   - port 9944 presents the swapped certificate B.
# The `iosSimulatorArm64Test` suite (IosTlsHandshakePinningTest) drives
# NSURLSession through IosSpkiPinningChallengeDelegate against them; the
# simulator shares the host network stack, so 127.0.0.1 reaches these servers.
#
# After the tests it asserts, from the host-side logs, that the swapped server
# never received a request: independent proof that the fail-closed path sent no
# request carrying the Authorization header.
#
# Only `actions/checkout@*` is allowed by the org Actions allowlist, so this is
# invoked directly from a workflow shell step.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

CERT_DIR="$ROOT/tests/t1/certs"
SERVER="$ROOT/tests/t1/tls_recording_server.py"
PORT_A="${T1_PORT_A:-9943}"
PORT_B="${T1_PORT_B:-9944}"
TMP_DIR="$(mktemp -d "${RUNNER_TEMP:-/tmp}/t1.XXXXXX")"
LOG_A="$TMP_DIR/pinned-a.log"
LOG_B="$TMP_DIR/swapped-b.log"

count_auth() {
  if [ -f "$1" ]; then
    grep -c '"authorization": "Bearer' "$1" || true
  else
    echo 0
  fi
}

echo "== Starting TLS recording servers (A=$PORT_A genuine, B=$PORT_B swapped) =="
python3 "$SERVER" --cert "$CERT_DIR/cert-a.pem" --key "$CERT_DIR/key-a-pkcs8.pem" --port "$PORT_A" --log "$LOG_A" &
PID_A=$!
python3 "$SERVER" --cert "$CERT_DIR/cert-b.pem" --key "$CERT_DIR/key-b-pkcs8.pem" --port "$PORT_B" --log "$LOG_B" &
PID_B=$!

cleanup() {
  kill "$PID_A" "$PID_B" 2>/dev/null || true
}
trap cleanup EXIT
sleep 2

echo "== Running iosSimulatorArm64 T1 handshake tests =="
export JAVA_HOME="${JAVA_HOME_21_ARM64:-${JAVA_HOME_21_X64:-${JAVA_HOME:-}}}"
chmod +x gradlew
./gradlew :shared:security:iosSimulatorArm64Test --no-daemon --stacktrace
GRADLE_STATUS=$?

echo "== Pinned server (cert A) requests =="
cat "$LOG_A" 2>/dev/null || true
echo "== Swapped server (cert B) requests — must be empty (fail-closed) =="
cat "$LOG_B" 2>/dev/null || true

SWAPPED_REQUESTS=$( [ -f "$LOG_B" ] && wc -l < "$LOG_B" || echo 0 )
SWAPPED_REQUESTS=$(echo "$SWAPPED_REQUESTS" | tr -d ' ')
SWAPPED_AUTH=$(count_auth "$LOG_B")
PINNED_AUTH=$(count_auth "$LOG_A")

echo "swapped_server_requests=$SWAPPED_REQUESTS (expected 0)"
echo "swapped_server_authorization_headers=$SWAPPED_AUTH (expected 0)"
echo "pinned_server_authorization_headers=$PINNED_AUTH (expected >= 1)"

if [ "$GRADLE_STATUS" -ne 0 ]; then
  echo "FAIL: iOS simulator T1 tests failed (exit $GRADLE_STATUS)"
  exit "$GRADLE_STATUS"
fi
if [ "$SWAPPED_REQUESTS" -ne 0 ]; then
  echo "FAIL: the swapped server received a request; the handshake was not aborted"
  exit 1
fi
if [ "$SWAPPED_AUTH" -ne 0 ]; then
  echo "FAIL: an Authorization header reached the swapped server"
  exit 1
fi
if [ "$PINNED_AUTH" -lt 1 ]; then
  echo "FAIL: the pinned server never saw the Authorization header; the credential path was not exercised"
  exit 1
fi

echo "PASS: iOS simulator T1 validation complete"