#!/usr/bin/env bash
# scripts/ci/package-android-debug-apk.sh
#
# OPE-300 — assemble the `:androidApp` debug APK and stage it for per-commit
# publication (mirrors the `package-ios-simulator` model from #94).
 
# Debug signing only: `assembleDebug` signs with the Gradle debug key
# (`~/.android/debug.keystore`, auto-generated when absent). This script never
# touches a release keystore and reads no secrets.
#
# Layout: the APK is copied into a clean per-run `dist-android-debug/`
# directory under the checkout, renamed with the commit SHA, alongside a
# `sha256sums.txt`. Only that directory is uploaded by the workflow
# (OPE-298 harding: a previous run on the shared persistent runner can never
 #poison this run's artifact)

#Usage (from a job step; the caller cds to the checkout):
#   bash scripts/ci/package-android-debug-apk.sh <commit-sha>

# Invoked via scripts/ci/run-as-nonroot.sh in CI so the Gradle build runs as
# uid 10001 (OPE-212/OPE-291). Run locally with JDK 21 + Android SDK 35.
set -euo pipefail

SHA="${1:?usage: package-android-debug-apk.sh <commit-sha>}"

chmod +x gradlew
java -version
./gradlew :androidApp:assembleDebug --no-daemon --stacktrace

APK="$(find androidApp/build/outputs/apk/debug -maxdepth 1 -name '*.apk' -print -quit)"
if [ -z "$APK" ]; then
  echo "::error::no debug APK under androidApp/build/outputs/apk/debug" >&2
  exit 1
fi
echo "Build $APK"

STAGE="dist-android-debug"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$APK" "$STAGE/opencodemobile-debug-${SHA}.apk"
( cd "$STAGE" && sha256sum ./*.apk > sha256sums.txt )
echo "Staged:"
cat "$STAGE/sha256sums.txt"
