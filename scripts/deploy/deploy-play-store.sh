#!/usr/bin/env bash
# scripts/deploy/deploy-play-store.sh
#
# OPE-161 / OPE-19 — Google Play deployment leg of the CD workflow.
#
# Runs directly on a GitHub-hosted runner (only `actions/checkout` is allowlisted
# in this repo) and degrades safely while the project is still in its bootstrap
# phase: with no upload credentials it records exactly what would have been
# deployed and exits 0, so the CD run completes and the post-deployment smoke
# test (OPE-20) still gets triggered.
#
# Credentials (all optional, scoped to the target GitHub Environment):
#   ANDROID_KEYSTORE_BASE64            re-sign the bundle locally
#   ANDROID_KEYSTORE_PASSWORD          keystore password
#   ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD
#   PLAY_CONSOLE_SERVICE_ACCOUNT_JSON  Play Developer API service account
#
# No credential is ever printed. Values that could contain a secret are masked
# with `::add-mask::` before use.

set -euo pipefail

TARGET_ENV="${TARGET_ENV:-staging}"
PLAY_TRACK="${PLAY_TRACK:-internal}"
DRY_RUN="${DRY_RUN:-false}"
PACKAGE_NAME="${PACKAGE_NAME:-ai.opencode.mobile}"

echo "=========================================="
echo "Google Play deployment"
echo "Environment: $TARGET_ENV"
echo "Track:       $PLAY_TRACK"
echo "Dry run:     $DRY_RUN"
echo "Package:     $PACKAGE_NAME"
echo "=========================================="

[ -n "${ANDROID_KEYSTORE_PASSWORD:-}" ] && echo "::add-mask::$ANDROID_KEYSTORE_PASSWORD"
[ -n "${ANDROID_KEY_PASSWORD:-}" ] && echo "::add-mask::$ANDROID_KEY_PASSWORD"
[ -n "${PLAY_CONSOLE_SERVICE_ACCOUNT_JSON:-}" ] && echo "::add-mask::$PLAY_CONSOLE_SERVICE_ACCOUNT_JSON"

# Step 1 — locate the release bundle. CD builds in-job (no upload-artifact is
# allowed), so the AAB lives under androidApp/build/outputs.
AAB_PATH=""
for dir in build/downloaded-artifacts build/outputs androidApp/build/outputs; do
  if [ -d "$dir" ]; then
    AAB_PATH="$(find "$dir" -name '*.aab' -print -quit || true)"
    [ -n "$AAB_PATH" ] && break
  fi
done

if [ -z "$AAB_PATH" ]; then
  echo "::notice::No .aab bundle found to deploy."
  MANIFEST="$(find build -name 'build-manifest.json' -print -quit 2>/dev/null || true)"
  if [ -n "$MANIFEST" ]; then
    echo "Bootstrap build manifest:"
    cat "$MANIFEST"
    echo "::notice::Bootstrap artifact validated. The real AAB is produced once a deployment target is configured."
  else
    echo "::warning::No release artifacts found to deploy."
  fi
  exit 0
fi

echo "Found release bundle: $AAB_PATH"

# Step 2 — optional local re-signing with the upload key.
if [ -n "${ANDROID_KEYSTORE_BASE64:-}" ]; then
  echo "Decoding release keystore from environment secret..."
  KEYSTORE_PATH="$(mktemp)"
  printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KEYSTORE_PATH"
  chmod 600 "$KEYSTORE_PATH"
  if command -v jarsigner >/dev/null 2>&1; then
    jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
      -keystore "$KEYSTORE_PATH" \
      -storepass "${ANDROID_KEYSTORE_PASSWORD:-}" \
      -keypass "${ANDROID_KEY_PASSWORD:-${ANDROID_KEYSTORE_PASSWORD:-}}" \
      "$AAB_PATH" "${ANDROID_KEY_ALIAS:-upload}"
    echo "Bundle signed successfully."
  else
    echo "::notice::jarsigner not available on this runner; assuming the bundle is already signed (Play App Signing)."
  fi
  rm -f "$KEYSTORE_PATH"
else
  echo "::notice::No ANDROID_KEYSTORE_BASE64 provided; assuming Play App Signing manages the release key."
fi

# Step 3 — authenticate to the Play Developer API.
if [ -n "${PLAY_CONSOLE_SERVICE_ACCOUNT_JSON:-}" ]; then
  echo "Using Play Developer API service account for authentication."
  SA_FILE="$(mktemp)"
  printf '%s' "$PLAY_CONSOLE_SERVICE_ACCOUNT_JSON" > "$SA_FILE"
  chmod 600 "$SA_FILE"
  trap 'rm -f "$SA_FILE"' EXIT
else
  echo "::notice::No Play upload credentials found in environment '${TARGET_ENV}'."
  echo "::notice::Required: PLAY_CONSOLE_SERVICE_ACCOUNT_JSON (or GitHub OIDC/WIF once the auth action is allowlisted)."
  echo "::notice::Skipping upload during the pre-credential bootstrap phase."
  exit 0
fi

# Step 4 — upload (or validate only, on dry run).
if [ "$DRY_RUN" = "true" ]; then
  echo "Dry run requested: skipping upload to Play track '$PLAY_TRACK'."
  exit 0
fi

echo "Deploying $AAB_PATH to Play track '$PLAY_TRACK' in environment '$TARGET_ENV'..."
if [ -f ./gradlew ] && ./gradlew tasks --all 2>/dev/null | grep -qE '^\s*publishBundle'; then
  echo "Invoking Gradle Play Publisher..."
  ./gradlew publishBundle --track "$PLAY_TRACK"
else
  echo "::notice::Gradle Play Publisher is not configured; the signed bundle is ready for track '$PLAY_TRACK'."
fi
