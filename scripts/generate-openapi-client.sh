#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "=== Generating OpenCode Server v2 API Client ==="
python3 "${REPO_ROOT}/scripts/generate-openapi-client.py"

echo "=== Generation Complete ==="
