# Android screenshots (OPE-352)

One PNG per V1 screen state (connexion, sessions, chat, dictée, réglages, états
d'erreur), rendered on the JVM with Paparazzi: no emulator. Pixel 5 frame, OpenCode theme,
FR/EN x light/dark (`<screen>_<fr|en>_<clair|sombre>.png`, 80 files, copied here
from the Paparazzi output). Recorded at `68b496e`.

- Images: this folder; Paparazzi's raw output (`[locale-dark]` suffix) is in `androidApp/src/test/snapshots/images/`.
- Source: `androidApp/src/test/kotlin/org/opencodemobile/android/screenshots/V1ScreenshotTest.kt`.
- Re-record: `./gradlew :androidApp:recordPaparazziDebug`.
- Check for drift: `./gradlew :androidApp:verifyPaparazziDebug`.

Known gaps: the QR scanner is a live CameraX preview (no camera on the JVM, and its
composable is private), so it is not capturable; the QR import is shown by
`connection_review` (source "Code QR"). There is no main Settings screen in V1: the
two settings surfaces are `settings_local_access` and `settings_erase_*`. `sessions_list`
is the bare `SessionRow`; `home_sessions` is the real `SessionsScreen` over a fake gateway.
