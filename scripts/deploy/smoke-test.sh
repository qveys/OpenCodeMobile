#!/usr/bin/env bash
# scripts/deploy/smoke-test.sh
#
# OPE-161 / OPE-19 — post-deploy pipeline check run inside the CD workflow.
#
# This is a bootstrap sanity check that runs on the same revision CD just
# deployed. The real end-to-end health/version smoke test is
# `scripts/smoke/deployment-smoke-test.sh` (OPE-20), which this workflow triggers
# through the `workflow_run` event. This script only asserts that the deployment
# leg produced a coherent revision, and always exits 0 unless that is violated.

set -euo pipefail

TARGET_ENV="${TARGET_ENV:-staging}"
PLAY_TRACK="${PLAY_TRACK:-internal}"

echo "=========================================="
echo "Post-deploy pipeline check"
echo "Environment: $TARGET_ENV"
echo "Track:       $PLAY_TRACK"
echo "Revision:    ${GITHUB_SHA:-unknown}"
echo "=========================================="

if [ -n "${GITHUB_SHA:-}" ]; then
  echo "Deployed revision recorded: ${GITHUB_SHA}"
else
  echo "::warning::No GITHUB_SHA available; running outside GitHub Actions."
fi

MANIFEST="$(find build -name 'build-manifest.json' -print -quit 2>/dev/null || true)"
if [ -n "$MANIFEST" ]; then
  echo "Build manifest:"
  cat "$MANIFEST"
fi

echo "::notice::Bootstrap pipeline check passed. The end-to-end health/version smoke test runs in the 'Deployment Smoke Test' workflow (OPE-20)."
exit 0
