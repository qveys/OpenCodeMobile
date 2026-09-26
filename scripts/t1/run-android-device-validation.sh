#!/usr/bin/env bash
#
# T1 (OPE-94) — Android real-device/simulator handshake validation.
#
# Boots a headless Android emulator and runs the instrumented T1 pinning tests
# in `:shared:security` (`androidInstrumentedTest`). The repository's org-level
# Actions allowlist permits only `actions/checkout@*`, so the emulator is
# provisioned entirely from shell steps here rather than with a marketplace
# action such as `reactivecircus/android-emulator-runner`.
#
# Exits non-zero if the emulator fails to boot or Gradle fails, and always
# prints the JUnit XML into the job log (there is no `actions/upload-artifact`).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"

AVD_NAME="${T1_AVD_NAME:-t1-device}"
SYSTEM_IMAGE="${T1_SYSTEM_IMAGE:-system-images;android-31;default;x86_64}"
EMULATOR_LOG="${RUNNER_TEMP:-/tmp}/t1-emulator.log"

echo "== Android SDK: $SDK =="
echo "== System image: $SYSTEM_IMAGE =="

echo "== Accepting SDK licenses =="
yes | sdkmanager --licenses >/dev/null 2>&1 || true

echo "== Installing emulator + system image =="
sdkmanager --install "platform-tools" "platforms;android-31" "$SYSTEM_IMAGE"

echo "== Creating AVD $AVD_NAME =="
echo no | avdmanager create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" --device "pixel_2" --force

echo "== KVM availability =="
if [ -e /dev/kvm ]; then
  ls -l /dev/kvm
  sudo chmod 666 /dev/kvm 2>/dev/null || true
else
  echo "WARNING: /dev/kvm is missing; the emulator will run without hardware acceleration"
fi

echo "== Booting emulator (headless) =="
nohup emulator -avd "$AVD_NAME" \
  -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -accel auto \
  >"$EMULATOR_LOG" 2>&1 &
EMULATOR_PID=$!

adb start-server
if ! adb wait-for-device; then
  echo "FAIL: adb never saw the emulator"
  tail -200 "$EMULATOR_LOG" || true
  kill "$EMULATOR_PID" 2>/dev/null || true
  exit 1
fi

if ! timeout 900 bash -c '
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" = "1" ]; do
    sleep 5
  done
'; then
  echo "FAIL: emulator did not finish booting"
  tail -200 "$EMULATOR_LOG" || true
  kill "$EMULATOR_PID" 2>/dev/null || true
  exit 1
fi

echo "== Emulator ready =="
adb devices
adb shell getprop ro.build.version.sdk

echo "== Running T1 instrumented handshake tests =="
export JAVA_HOME="${JAVA_HOME_21_X64:-${JAVA_HOME:-}}"
chmod +x gradlew
./gradlew :shared:security:connectedDebugAndroidTest --no-daemon --stacktrace
GRADLE_STATUS=$?

echo "== Instrumented test result XML =="
find shared/security/build/outputs/androidTest-results -name '*.xml' -print -exec cat {} \; 2>/dev/null || true

if [ "$GRADLE_STATUS" -ne 0 ]; then
  echo "== Emulator log tail (for diagnosis) =="
  tail -200 "$EMULATOR_LOG" || true
  echo "== adb logcat tail (for diagnosis) =="
  adb logcat -d 2>/dev/null | tail -200 || true
fi

kill "$EMULATOR_PID" 2>/dev/null || true
echo "== Android T1 validation exit status: $GRADLE_STATUS =="
exit "$GRADLE_STATUS"