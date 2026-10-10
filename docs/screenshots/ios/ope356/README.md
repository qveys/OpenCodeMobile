# iOS screenshots V1 (OPE-356)

11 écrans, FR/EN, clair/sombre (44 PNG, `<écran>_<lang>_<thème>.png`, thèmes `clair`/`sombre`).
Recapturées depuis `main` 68b496e (PR #110 + correctifs visuels PR #113, OPE-359) via `xcrun simctl` (iPhone 17, iOS 27.0, barre d'état 9:41).

Écrans : `catalog-failed`, `catalog-loading`, `erase-confirming`, `erase-idle`, `invalid-address`,
`local-access`, `manual-entry`, `permission`, `question`, `sessions`, `transcript`.

Génération : lancer l'app avec l'argument `-OPEScreen <écran>` (PR #110).
