# Android screenshots (OPE-352)

One PNG per V1 screen state (connexion, sessions, chat, dictée, réglages, états
d'erreur), rendered on the JVM with Paparazzi: no emulator. Pixel 5 frame, OpenCode theme,
FR/EN x light/dark (`<screen>_<fr|en>_<clair|sombre>.png`, 72 files, copied here
from the Paparazzi output). Recorded at `68b496e`.

- Images: this folder; Paparazzi's raw output (`[locale-dark]` suffix) is in `androidApp/src/test/snapshots/images/`.
- Source: `androidApp/src/test/kotlin/org/opencodemobile/android/screenshots/V1ScreenshotTest.kt`.
- Re-record: `./gradlew :androidApp:recordPaparazziDebug`.
- Check for drift: `./gradlew :androidApp:verifyPaparazziDebug`.

Known gaps: the sessions list shows `SessionRow` (the full `SessionsScreen` needs a
live controller), and the assistant Markdown body renders asynchronously so the
transcript capture shows the role label only.
