# iOS screenshots OPE-364 / OPE-365

Même mécanisme que `../ope356/README.md` (`-OPEScreen <écran>`, iPhone 17 / iOS 27.0, 9:41).

Livrés : `transcript` (corps assistant rendu, Markdown synchrone dans la fixture), `review` (revue d'import de serveur). FR/EN, clair/sombre.
Le transcript est toujours rendu en contexte Session (sombre) : les variantes `clair` sont identiques aux `sombre`.

Non livrés :
- Settings principal : n'existe pas en V1 (seulement `local-access` et `erase-*`, déjà dans ope356).
- QR scanner/import caméra : AVFoundation, pas de caméra sur simulateur.
- Home post-connexion : exige un serveur OpenCode réel connecté, pas de fixture.
