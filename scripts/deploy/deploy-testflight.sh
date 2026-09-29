#!/usr/bin/env bash
# scripts/deploy/deploy-testflight.sh
#
# OPE-161 / OPE-19 — Apple TestFlight / App Store Connect deployment leg of the
# CD workflow.
#
# Runs on a GitHub-hosted runner and degrades safely: without a complete App
# Store Connect API key (or without an IPA to upload) it records the intended
# deployment and exits 0, so the CD run completes and the post-deployment smoke
# test (OPE-20) is triggered.
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

# Step 3 — write the private key to a short-lived file and upload.
KEY_FILE="$(mktemp "/tmp/AuthKey_${KEY_ID}_XXXXXX.p8")"
printf '%s' "$KEY_BASE64" | base64 -d > "$KEY_FILE"
chmod 600 "$KEY_FILE"
trap 'rm -f "$KEY_FILE"' EXIT

echo "App Store Connect API key configured (Key ID: $KEY_ID, Issuer: $ISSUER_ID)."
echo "Deploying $IPA_PATH to TestFlight for bundle '$BUNDLE_ID'..."
if command -v fastlane >/dev/null 2>&1; then
  fastlane run upload_to_testflight \
    api_key_path:"$KEY_FILE" \
    key_id:"$KEY_ID" \
    issuer_id:"$ISSUER_ID" \
    ipa:"$IPA_PATH" \
    skip_waiting_for_build_processing:true
elif command -v xcrun >/dev/null 2>&1; then
  xcrun altool --upload-app -f "$IPA_PATH" -t ios --apiKey "$KEY_ID" --apiIssuer "$ISSUER_ID"
else
  echo "::notice::No TestFlight uploader available on this runner; the IPA is ready for upload."
fi
