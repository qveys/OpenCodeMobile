# Android screenshots (OPE-352)

One PNG per V1 screen state (connexion, sessions, chat, dictée, réglages, états
d'erreur), rendered on the JVM with Paparazzi: no emulator. French fixtures,
Pixel 5 frame, OpenCode theme.

- Images: `androidApp/src/test/snapshots/images/` (Paparazzi's fixed location).
- Source: `androidApp/src/test/kotlin/org/opencodemobile/android/screenshots/V1ScreenshotTest.kt`.
- Re-record: `./gradlew :androidApp:recordPaparazziDebug`.
- Check for drift: `./gradlew :androidApp:verifyPaparazziDebug`.

Known gaps: the sessions list shows `SessionRow` (the full `SessionsScreen` needs a
live controller), and the assistant Markdown body renders asynchronously so the
transcript capture shows the role label only.
