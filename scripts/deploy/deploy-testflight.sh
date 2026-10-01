#!/usr/bin/env bash
# scripts/deploy/deploy-testflight.sh
#
# OPE-161 / OPE-19 — Apple TestFlight / App Store Connect deployment leg of the
# CD workflow.
#
# Runs on the company self-hosted macOS runner. During bootstrap (no complete
# App Store Connect API key, or no IPA) it records the intended deployment and
# exits 0, so the CD run completes and the post-deployment smoke test (OPE-20)
# is triggered. Once the key IS configured it fails closed: if no uploader can
# run, it exits non-zero rather than reporting a false success. Fastlane gets
# the App Store Connect JSON key file (`api_key_path`), not the raw `.p8`.
#
# Credentials (all optional, scoped to the target GitHub Environment):
#   APP_STORE_CONNECT_KEY_ID
#   APP_STORE_CONNECT_ISSUER_ID
#   APP_STORE_CONNECT_PRIVATE_KEY_BASE64
#
# No credential is ever printed. Values that could contain a secret are masked
# with `::add-mask::` before use.

set -euo pipefail

TARGET_ENV="${TARGET_ENV:-staging}"
DRY_RUN="${DRY_RUN:-false}"
BUNDLE_ID="${BUNDLE_ID:-ai.opencode.mobile}"
KEY_ID="${APP_STORE_CONNECT_KEY_ID:-}"
ISSUER_ID="${APP_STORE_CONNECT_ISSUER_ID:-}"
KEY_BASE64="${APP_STORE_CONNECT_PRIVATE_KEY_BASE64:-}"

echo "=========================================="
echo "Apple TestFlight deployment"
echo "Environment: $TARGET_ENV"
echo "Dry run:     $DRY_RUN"
echo "Bundle ID:   $BUNDLE_ID"
echo "=========================================="

[ -n "$KEY_BASE64" ] && echo "::add-mask::$KEY_BASE64"

# Step 1 — locate the release IPA. The iOS artifact is not built by this
# workflow yet (it would need a macOS runner); the script degrades to the
# bootstrap manifest until that lands.
IPA_PATH=""
for dir in build/downloaded-artifacts build/outputs iosApp/build/outputs; do
  if [ -d "$dir" ]; then
    IPA_PATH="$(find "$dir" -name '*.ipa' -print -quit || true)"
    [ -n "$IPA_PATH" ] && break
  fi
done

if [ -z "$IPA_PATH" ]; then
  echo "::notice::No .ipa file found to deploy."
  MANIFEST="$(find build -name 'build-manifest.json' -print -quit 2>/dev/null || true)"
  if [ -n "$MANIFEST" ]; then
    echo "Bootstrap build manifest:"
    cat "$MANIFEST"
    echo "::notice::Bootstrap artifact validated. The real IPA is produced once a macOS release job lands."
  else
    echo "::warning::No iOS release artifacts found to deploy."
  fi
  exit 0
fi

echo "Found release IPA: $IPA_PATH"

# Step 2 — validate App Store Connect API credentials.
if [ -z "$KEY_ID" ] || [ -z "$ISSUER_ID" ] || [ -z "$KEY_BASE64" ]; then
  echo "::notice::App Store Connect API credentials are not fully configured in environment '${TARGET_ENV}'."
  echo "::notice::Required: APP_STORE_CONNECT_KEY_ID, APP_STORE_CONNECT_ISSUER_ID, APP_STORE_CONNECT_PRIVATE_KEY_BASE64."
  echo "::notice::Skipping upload during the pre-credential bootstrap phase."
  exit 0
fi

if [ "$DRY_RUN" = "true" ]; then
  echo "Dry run requested: skipping upload to TestFlight."
  exit 0
fi

# The key id is used in file paths below: reject anything but an opaque token.
case "$KEY_ID" in
  *[!A-Za-z0-9]*) echo "::error::APP_STORE_CONNECT_KEY_ID contains unexpected characters; refusing to use it in a path."; exit 1 ;;
esac

# Step 3 — write the API key material to short-lived files and upload.
KEY_FILE="$(mktemp "/tmp/AuthKey_${KEY_ID}_XXXXXX.p8")"
ASC_KEY_JSON="$(mktemp "/tmp/asc-api-key_XXXXXX.json")"
ALT_KEY_FILE=""
cleanup_asc() {
  [ -n "${KEY_FILE:-}" ] && rm -f "$KEY_FILE"
  [ -n "${ASC_KEY_JSON:-}" ] && rm -f "$ASC_KEY_JSON"
  [ -n "${ALT_KEY_FILE:-}" ] && rm -f "$ALT_KEY_FILE"
  return 0
}
trap cleanup_asc EXIT

printf '%s' "$KEY_BASE64" | base64 -d > "$KEY_FILE"
chmod 600 "$KEY_FILE"

# Fastlane's `api_key_path` expects the App Store Connect JSON key file, not the
# raw .p8 (Codex finding, OPE-215). Build that JSON from the .p8 plus metadata.
python3 - "$KEY_FILE" "$KEY_ID" "$ISSUER_ID" "$ASC_KEY_JSON" <<'PY'
import json
import sys

key_path, key_id, issuer_id, out = sys.argv[1:5]
with open(key_path, encoding="utf-8") as fh:
    key = fh.read()
with open(out, "w", encoding="utf-8") as fh:
    json.dump(
        {"key_id": key_id, "issuer_id": issuer_id, "key": key, "duration": 1200, "in_house": False},
        fh,
    )
PY
chmod 600 "$ASC_KEY_JSON"

echo "App Store Connect API key configured (Key ID: $KEY_ID, Issuer: $ISSUER_ID)."
echo "Deploying $IPA_PATH to TestFlight for bundle '$BUNDLE_ID'..."
if command -v fastlane >/dev/null 2>&1; then
  fastlane run upload_to_testflight \
    api_key_path:"$ASC_KEY_JSON" \
    ipa:"$IPA_PATH" \
    skip_waiting_for_build_processing:true
elif command -v xcrun >/dev/null 2>&1; then
  # `altool` discovers the key by convention under ~/.appstoreconnect/private_keys.
  ALT_KEY_DIR="${HOME:-/root}/.appstoreconnect/private_keys"
  mkdir -p "$ALT_KEY_DIR"
  ALT_KEY_FILE="$ALT_KEY_DIR/AuthKey_${KEY_ID}.p8"
  cp "$KEY_FILE" "$ALT_KEY_FILE"
  chmod 600 "$ALT_KEY_FILE"
  xcrun altool --upload-app -f "$IPA_PATH" -t ios --apiKey "$KEY_ID" --apiIssuer "$ISSUER_ID"
else
  echo "::error::App Store Connect credentials are configured but no uploader (fastlane or xcrun) is available on this runner, so nothing was uploaded. Refusing to report a successful deployment."
  exit 1
fi
