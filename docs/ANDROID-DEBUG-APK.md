# Android debug APK per commit (OPE-300)

Every `Build` workflow run publishes one installable debug APK for its
commit, mirroring the iOS Simulator `.app` per-commit artifact from #94.
No local Gradle build is needed to try the current `main` on an emulator.

## Download

1. Open the repository on GitHub → **Actions** → **Build**.
2. Pick the run for your commit (runs are named by branch/commit).
3. Download the **Artifacts** entry `android-debug-<commit-sha>`
   (14-day retention).
4. Unzip it. Contents:
   - `opencodemobile-debug-<commit-sha>.apk` — signed with the Gradle
     **debug** key only, never a release key.
   - `sha256sums.txt` — SHA-256 of the APK.

Verify before installing:

```bash
cd android-debug-<commit-sha>
sha256sum -c sha256sums.txt
```

## Install on an emulator (API 31+)

`minSdk` is 31, so any emulator image at API 31 or above works
(recommended: Pixel, API 34, `google_apis` image).

Create and boot an emulator once (Android Studio Device Manager, or
headless with `sdkmanager` + `avdmanager` + `emulator -avd <name>`),
then install:

```bash
adb devices                      # the emulator must be listed as `device`
adb install -r opencodemobile-debug-<commit-sha>.apk
```

`adb install` prints `Success` on success. Each workflow run generates a
fresh debug keystore, so **APKs from different commits have different
signatures**. To install a newer commit's APK over an older one:

```bash
adb uninstall org.opencodemobile.android
adb install opencodemobile-debug-<commit-sha>.apk
```

The `-r` (reinstall) flag only works when reinstalling the **same**
APK (same commit); it does not allow cross-commit updates because the
signing keys differ.

Launch the app (or tap its icon on the emulator):

```bash
adb shell am start -n org.opencodemobile.android/.MainActivity
```

## Install on a physical device

Enable USB debugging on the device, connect it (`adb devices` shows it),
then run the same `adb install` command (no `-r` across commits). No Play
account needed.

## Notes

- Debug builds log verbosely and cannot be published to a store track;
  release signing/upload stays in the `CD` workflow (environment-gated,
  OPE-19).
- If the artifact is missing for a commit, the run was either
  path-filtered out (docs-only change, OPE-250) or the
  `package-android-debug` job failed — check the job log first.