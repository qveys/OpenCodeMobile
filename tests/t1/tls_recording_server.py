#!/usr/bin/env python3
"""Minimal TLS server that records every HTTP request it receives.

Used by the T1 real-handshake validation (OPE-94) on the iOS side, where an
in-process TLS server cannot be built with public Kotlin/Native APIs: the iOS
simulator shares the host network stack, so this server runs on the macOS
runner (started from a CI shell step) and the ``iosSimulatorArm64`` test
connects to it over ``https://127.0.0.1:<port>``.

Each accepted request is appended as one JSON object to ``--log``:

    {"method": "HEAD", "path": "/global/health",
     "authorization": "Bearer ...", "headers": {...}}

The fail-closed assertion in CI is simply that the "swapped certificate"
server's log stays empty: the pinning challenge aborts the handshake, so no
request (and therefore no ``Authorization`` header) can ever reach the wire.

This server is deliberately dumb and test-only. It is not a product artifact.
"""

from __future__ import annotations

import argparse
import json
import ssl
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_LOCK = threading.Lock()


class RecordingHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "T1RecordingServer/1.0"

    def _record_and_respond(self) -> None:  # noqa: N802 (http.server API)
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        record = {
            "method": self.command,
            "path": self.path,
            "authorization": self.headers.get("Authorization"),
            "headers": {key: value for key, value in self.headers.items()},
        }
        with _LOCK:
            with open(self.server.log_path, "a", encoding="utf-8") as handle:  # type: ignore[attr-defined]
                handle.write(json.dumps(record) + "\n")

        payload = json.dumps({"healthy": True, "version": "t1-test"}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    do_GET = _record_and_respond
    do_HEAD = _record_and_respond
    do_POST = _record_and_respond

    def log_message(self, *args) -> None:  # silence default stderr logging
        return


class RecordingServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    log_path: str

    def handle_error(self, request, client_address) -> None:  # noqa: ANN001
        # A client that aborts the TLS handshake is the *expected* fail-closed
        # outcome; do not print a traceback for it.
        return


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--log", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certfile=args.cert, keyfile=args.key)

    server = RecordingServer((args.host, args.port), RecordingHandler)
    server.log_path = args.log
    server.socket = context.wrap_socket(server.socket, server_side=True)

    print(
        f"tls_recording_server listening on https://{args.host}:{args.port} (log={args.log})",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())