# iOS screenshots (OPE-354)

Écran Connexion, simulateur iOS, commit `d8493f5`, CI run Build `38050565318`
(zip SHA-256 `b4502819a06651dd088984acf81f560e29e08a6e2864ee16beefbede959d9128`).

Variantes : FR/EN, clair/sombre, EN Dynamic Type AXXL (`connexion_*.png`).

Non capturés : Réglages, import QR, erreur de lien (XCUITest bloqué, OPE-315).

## Capturer un écran (OPE-356)

Build Debug uniquement. Lancer un écran V1 avec ses fixtures fixes :

```sh
xcrun simctl launch <udid> org.opencodemobile.ios -OPEScreen <nom>
xcrun simctl io <udid> screenshot <nom>.png
```

Noms : `manual-entry`, `invalid-address`, `sessions`, `transcript`, `permission`,
`question`, `local-access`, `erase-idle`, `erase-confirming`, `catalog-loading`,
`catalog-failed`. Nom inconnu : l'app démarre normalement. Fixtures :
`iosAppHost/.../IosDebugScreens.kt` (miroir de `V1ScreenshotTest`).
