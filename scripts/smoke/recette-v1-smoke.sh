#!/usr/bin/env bash
# Run the automated V1 smoke suite on an already-running Android emulator or
# iOS simulator. This intentionally does not modify CI workflows.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET="${1:-}"

usage() {
  echo "Usage: $0 android|ios" >&2
  exit 2
}

[[ "$TARGET" == android || "$TARGET" == ios ]] || usage
cd "$ROOT"

if [[ "$TARGET" == android ]]; then
  command -v adb >/dev/null || { echo "adb is required (start an API 31+ emulator first)" >&2; exit 1; }
  DEVICE="${ANDROID_SERIAL:-}"
  [[ -n "$DEVICE" ]] || DEVICE="$(adb devices | awk 'NR>1 && $2 == "device" {print $1; exit}')"
  [[ -n "$DEVICE" ]] || { echo "No running Android emulator found" >&2; exit 1; }
  API="$(adb -s "$DEVICE" shell getprop ro.build.version.sdk | tr -d '\r')"
  [[ "$API" =~ ^[0-9]+$ && "$API" -ge 31 ]] || { echo "Android API 31+ required; found: ${API:-unknown}" >&2; exit 1; }

  # MockOpenCodeServer scenarios cover sessions, chat, permissions and cleanup.
  ./gradlew :shared:test-support:testDebugUnitTest \
    :shared:networking:testDebugUnitTest :shared:realtime:testDebugUnitTest \
    :features:sessions:testDebugUnitTest :features:transcript:testDebugUnitTest \
    :features:permissions:testDebugUnitTest :androidApp:testDebugUnitTest --no-daemon
  ./gradlew :androidApp:installDebug --no-daemon
  adb -s "$DEVICE" shell am force-stop org.opencodemobile.android
  adb -s "$DEVICE" shell pm clear org.opencodemobile.android
  adb -s "$DEVICE" shell am start -W -n org.opencodemobile.android/.MainActivity
  echo "PASS: Android unit smoke suite, install, launch, and app-data clear (API $API, $DEVICE)"
else
  command -v xcrun >/dev/null || { echo "xcrun is required (run on macOS with Xcode installed)" >&2; exit 1; }
  command -v xcodebuild >/dev/null || { echo "xcodebuild is required" >&2; exit 1; }
  xcodegen --version >/dev/null 2>&1 || { echo "XcodeGen is required" >&2; exit 1; }
  UDID="$(bash scripts/ios/pick-simulator.sh)"
  (cd iosApp && xcodegen generate --spec project.yml)
  xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -sdk iphonesimulator -destination "id=$UDID" \
    -derivedDataPath iosApp/build/DerivedData build-for-testing
  xcrun simctl boot "$UDID" 2>/dev/null || true
  xcrun simctl bootstatus "$UDID" -b
  APP="$(find iosApp/build/DerivedData/Build/Products -maxdepth 2 -name iosApp.app -print -quit)"
  [[ -n "$APP" ]] || { echo "Built iosApp.app not found" >&2; exit 1; }
  xcrun simctl install "$UDID" "$APP"
  xcrun simctl privacy "$UDID" grant camera org.opencodemobile.ios || true
  xcrun simctl launch "$UDID" org.opencodemobile.ios
  xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -sdk iphonesimulator -destination "id=$UDID" \
    -derivedDataPath iosApp/build/DerivedData test-without-building
  xcrun simctl uninstall "$UDID" org.opencodemobile.ios
  echo "PASS: iOS build, install, launch, UI acceptance tests, and app removal ($UDID)"
fi
