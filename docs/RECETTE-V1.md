# Plan de recette V1 — émulateur Android + simulateur iOS (sans appareil physique)

OPE-301. Parent : OPE-2 (MVP v1). Baseline : cahier des charges v1.0 convergé
(document `opencode-mobile-cahier-des-charges-d-architecture-v1-0-converge` sur
[OPE-2](/OPE/issues/OPE-2)), §3.1 (V1-01 à V1-14).

## 1. Objet et périmètre

Ce plan rend la recette V1 exécutable par des agents, sans appareil physique,
sur :

- **émulateur Android API 31+** (`minSdk = 31`, `compileSdk/targetSdk = 35`),
- **simulateur iOS** (runtime iOS 26+ quand disponible, sinon le runtime iOS le
  plus récent installé ; l'app cible `IPHONEOS_DEPLOYMENT_TARGET = 16.0`).

Il couvre **V1-01 à V1-13**. Chaque point est classé :

- **Automatisable** — couvert par un test (JVM, instrumenté ou XCUITest) qui
  tourne sur émulateur/simulateur ou en CI, sans manipulation manuelle ;
- **Manuel agent** — un agent l'exécute à la main sur émulateur/simulateur en
  suivant les étapes ci-dessous ;
- **Appareil réel uniquement** — non prouvable sur émulateur/simulateur,
  réservé à [OPE-278](/OPE/issues/OPE-278).

**V1-14 (FR + EN, thème système)** est hors périmètre de ce plan : elle relève
du lot L6 (durcissement + release). Les garde-fous existants (parité FR/EN des
catalogues de chaînes, `SettingsStringsCatalogParityTest`) restent la preuve
automatisée en attendant.

Ce qui reste réservé aux appareils réels et couvert par
[OPE-278](/OPE/issues/OPE-278) est listé explicitement au §6. Les scripts de
smoke automatisés issus de ce plan sont suivis dans
[OPE-303](/OPE/issues/OPE-303) ; l'exécution complète et son rapport dans
[OPE-304](/OPE/issues/OPE-304).

## 2. Pré-requis

### 2.1 Backend : un OpenCode Server local (source de vérité)

L'application est une télécommande : le serveur est la seule source de vérité
et le cache local est jetable. La recette sur émulateur/simulateur tourne
contre **un vrai `opencode serve` local** (version cible v2 `2.0.6` ;
`docs/API.md` : la spec vendored a été extraite de `1.18.32` — noter la
version réelle du serveur utilisé dans le rapport, cf. §7).

```bash
# Terminal 1 — démarrer le serveur (port 4096 par défaut)
opencode serve
# ou, avec mot de passe (l'app envoie Authorization: Bearer) :
OPENCODE_SERVER_PASSWORD='<mot-de-passe>' opencode serve

# Vérification depuis la machine hôte
curl -s http://127.0.0.1:4096/global/health
```

Adresses à saisir dans l'app selon la cible :

| Cible | Hôte à saisir | Raison |
| --- | --- | --- |
| Émulateur Android | `http://10.0.2.2:4096` | `10.0.2.2` = loopback de l'hôte vu de l'émulateur |
| Simulateur iOS | `http://127.0.0.1:4096` | le simulateur partage la pile réseau de l'hôte |

HTTP en clair n'est accepté que sur loopback/LAN/Tailscale ; un hôte public en
HTTP doit être **refusé** explicitement (`HttpConnectionPolicy`, lot L1).

> `MockOpenCodeServer` (`shared/test-support`) est un faux **in-process**
> (moteur Ktor dédié, aucun socket) : il ne peut pas servir de backend réseau
> à l'app installée. Il prouve les scénarios au niveau test (voir la colonne
> **Automatisable** de chaque parcours et `shared/test-support/README.md`).
> La recette app installée utilise donc `opencode serve` ; les scénarios
> d'erreur réseau (401, 500, version incompatible, `/event` tronqué) se
> rejouent contre le serveur local en le reconfigurant (mauvais mot de passe,
> arrêt/redémarrage mid-tour) ou restent couverts par les tests listés.

### 2.2 Émulateur Android (API 31+)

Recette de provisioning reprise de `scripts/t1/run-android-device-validation.sh`
(les variables `T1_AVD_NAME` / `T1_SYSTEM_IMAGE` permettent de surcharger) :

```bash
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"

yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "platform-tools" "platforms;android-31" \
  "system-images;android-31;default;x86_64" "emulator"

export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"
echo no | avdmanager create avd -n recette-v1 \
  -k "system-images;android-31;default;x86_64" --device "pixel_2" --force

# Démarrage headless + attente de boot
emulator -avd recette-v1 -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect >"$RUNNER_TEMP/emulator-recette.log" 2>&1 &
adb start-server
timeout 240 adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 5; done
adb devices
adb shell getprop ro.build.version.sdk   # attendu : 31+
```

Installation de l'app (émulateur démarré) :

```bash
./gradlew :androidApp:installDebug
# ou, installation directe de l'APK construit :
APK="$(find androidApp/build/outputs/apk -name '*.apk' -print -quit)"
test -n "$APK"
adb install -r "$APK"
adb shell am start -n org.opencodemobile.android/.MainActivity
```

### 2.3 Simulateur iOS

Recette reprise de `.github/workflows/ios-app.yml` + `scripts/ios/pick-simulator.sh`
(runner macOS, Xcode 16+, XcodeGen) :

```bash
cd iosApp && xcodegen generate --spec project.yml && cd ..
UDID="$(bash scripts/ios/pick-simulator.sh)"
echo "Simulateur : $UDID"
xcrun simctl list devices | grep "$UDID"

# Build + installation + lancement
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination "id=$UDID" \
  -derivedDataPath iosApp/build/DerivedData build-for-testing
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b
APP="$(find iosApp/build/DerivedData/Build/Products -maxdepth 2 -name 'iosApp.app' -print -quit)"
test -n "$APP"
xcrun simctl install "$UDID" "$APP"
# Pré-autoriser la caméra pour ne pas bloquer le parcours QR (V1-03)
xcrun simctl privacy "$UDID" grant camera org.opencodemobile.ios || true
xcrun simctl launch "$UDID" org.opencodemobile.ios
```

Tests d'acceptation automatisés existants (écran de connexion : scan/cancel,
payloads QR valides vs non importables) :

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination "id=$UDID" \
  -derivedDataPath iosApp/build/DerivedData test-without-building
```

### 2.4 Captures et journaux (preuve PASS/FAIL)

```bash
# Android
adb exec-out screencap -p > "recette-v1-<ID>-<cible>.png"
adb logcat -d > "recette-v1-logcat-<ID>.txt"

# iOS
xcrun simctl io "$UDID" screenshot "recette-v1-<ID>-ios.png"
xcrun simctl spawn "$UDID" log show --last 10m --style compact \
  --predicate 'process == "iosApp"' > "recette-v1-syslog-<ID>.txt"
```

Chaque parcours manuel se termine par : capture d'écran + résultat **PASS/FAIL**
reporté dans la matrice du §7.

## 3. Matrice des parcours V1-01 → V1-13

Légende : **[A]** = Automatisable · **[M]** = Manuel agent · **[R]** = Appareil
réel uniquement (couvert par [OPE-278](/OPE/issues/OPE-278)).

### V1-01 — Connexion serveur **[M]** + **[A]**

Critère CDC : un serveur configuré et connecté ; modèle Domain scopé ServerId
(lot L1, OP3 : un seul profil serveur enregistré en V1).

| # | Étape (émulateur ET simulateur) | Résultat attendu |
| --- | --- | --- |
| 1 | Lancer l'app (cf. §2.2/§2.3) | Écran de connexion affiché |
| 2 | Saisie manuelle : hôte `10.0.2.2` (Android) / `127.0.0.1` (iOS), port `4096` | Champs acceptés, bouton de connexion actif |
| 3 | Taper « Connecter » | Serveur connecté, écran sessions ; le profil est persisté (Keystore/Keychain pour le secret) |
| 4 | Tuer l'app (`adb shell am force-stop …` / `xcrun simctl terminate`) puis relancer | Profil retrouvé, pas de ressaisie |
| 5 | Cas négatif : hôte public en HTTP (ex. `http://93.184.216.34:4096`) | Refus explicite avant toute persistance (politique HTTP) |

**[A]** : `ConnectionSetupControllerTest` (`features/connection`),
`HttpConnectionPolicyTest` + `TofuServerIdentityCoordinatorTest`
(`shared/domain`, `shared/security`), `MockOpenCodeServerAdapterTest`
(`shared/networking`, scénarios `AuthenticationFailure`, `ServerError`).

```bash
./gradlew :features:connection:testDebugUnitTest :shared:domain:testDebugUnitTest \
  :shared:security:testDebugUnitTest :shared:networking:testDebugUnitTest --no-daemon
```

### V1-02 — Handshake v2 **[M]** + **[A]**

Critère CDC : health + version + auth **avant persistance** ; refus explicite si
incompatible.

| # | Étape | Résultat attendu |
| --- | --- | --- |
| 1 | Serveur arrêté, tenter la connexion | Échec explicite, rien n'est persisté |
| 2 | Serveur démarré avec `OPENCODE_SERVER_PASSWORD` mais sans saisir le mot de passe dans l'app | `401` remonté en message clair, rien n'est persisté |
| 3 | Serveur de version incompatible (si disponible) ou handshake interrompu | Écran « incompatible », connexion refusée |
| 4 | `curl http://127.0.0.1:4096/global/health` côté hôte pendant la recette | `{"healthy": true, "version": "…"}` (noter la version au §7) |

**[A]** : scénario `UnsupportedVersion` (`MockOpenCodeServerAdapterTest`),
validation TLS/pinning T1 sur cible réelle :

```bash
bash scripts/t1/run-android-device-validation.sh   # émulateur (nécessite KVM ou mode lent)
bash scripts/t1/run-ios-simulator-validation.sh    # simulateur (macOS)
```

### V1-03 — Découverte (manuelle + QR sans secret) **[M]** + **[A]**

Critère CDC : saisie manuelle + QR versionné **sans secret** (Q8 → A).

| # | Étape | Résultat attendu |
| --- | --- | --- |
| 1 | Saisie manuelle (§V1-01 étapes 2-3) | Connexion OK |
| 2 | Ouvrir le scanner QR depuis l'écran de connexion | Aperçu caméra actif (permission pré-accordée au §2.3 sur iOS) |
| 3 | Annuler le scan (bouton retour) | Retour à l'écran de connexion, aucun état corrompu |
| 4 | Présenter un QR contenant un payload valide sans secret | Revue d'import affichée (`ServerImportReviewScreen`), connexion après confirmation |
| 5 | Présenter un QR avec payload non importable | Rejet explicite, aucun secret persisté |
| 6 | Vérifier qu'aucun QR n'embarque de mot de passe | Le secret n'est jamais dans le QR (saisi séparément) |

**[A]** (iOS, XCUITest `ConnectionScreenUITests` via `test-without-building`,
§2.3) : ouverture écran de connexion, scan/cancel, payloads valides vs non
importables. Côté Android : tests unitaires `features/connection`.

### V1-04 — Sessions **[M]** + **[A]**

Critère CDC : lister, créer, reprendre, renommer, supprimer, forker **si le
serveur le supporte** (lot L2).

| # | Étape | Résultat attendu |
| --- | --- | --- |
| 1 | Ouvrir l'écran sessions | Liste = `GET /session` du serveur local |
| 2 | Créer une session (titre explicite `recette-v1-<date>`) | La session apparaît en tête de liste |
| 3 | Reprendre la session, la renommer | Nouveau titre visible après retour liste |
| 4 | Forker (si proposé) | Session fille créée (titre dérivé du parent) ; si le serveur ne l'expose pas (`GET /doc` sans la route), l'action est désactivée sans appel rejeté |
| 5 | Supprimer la session de test | Disparue de la liste ; `GET /session` hôte conforme |
| 6 | Kill process pendant un tour actif (lot L2) puis relancer | État reconstitué depuis snapshot/réconciliation, aucun rejeu de mutation |

**[A]** : `SessionGatewayAdapterTest` (CRUD + fork + `NoFork`),
`EventProcessorMockServerTest` (scénarios `Disconnect`/`Reconnect`, pas de
rejeu : `server.prompts` compte 1 par session), kill → polling → resume (§3.2
`docs/ARCHITECTURE.md`, OPE-106).

### V1-05 — Chat **[M]** + **[A]**

Critère CDC : envoi de prompt, transcript streaming, Markdown + code coloré.

| # | Étape | Résultat attendu |
| --- | --- | --- |
| 1 | Dans une session, envoyer `Dis-moi bonjour en une ligne.` | Réponse streamée en direct dans le transcript |
| 2 | Envoyer un prompt demandant un bloc de code | Bloc de code coloré, numéros de ligne, copie |
| 3 | Faire défiler un long transcript | Lazy loading, pas de gel (référence L3 : 10 000 items) |
| 4 | Couper le serveur mid-stream (`Ctrl-C` sur `opencode serve`) puis le relancer | Dégradation vers polling `GET /session/status`, reconnexion, réconciliation sans doublon |

**[A]** : scénarios `Streaming`, `SlowNetwork`, `LongTranscript`,
`MalformedEvent` (`EventProcessorTest`, `EventProcessorMockServerTest`),
`server.prompts` = preuve anti-rejeu D9.

### V1-06 — Permissions **[M]** + **[A]**

Critère CDC : affichage fidèle, relais de la décision exacte
(once / deny / remember si exposé). OP4 : **aucune approbation depuis une
notification** — la décision se prend dans l'app.

| # | Étape | Résultat attendu |
| --- | --- | --- |
| 1 | Déclencher une permission (prompt demandant une action sensible au serveur local) | Bandeau permission fidèle au serveur |
| 2 | Approuver (« once ») | Le serveur exécute exactement cette décision |
| 3 | Refuser (« deny ») sur une seconde demande | Refus relayé, l'agent s'arrête |
| 4 | Aucune permission ne doit être perdue ni validée en bloc (OP5) | Chaque item exige sa décision individuelle |

**[A]** : scénario `PermissionRequest` (`OpenCodePermissionGatewayTest`,
`InteractionGatewayAdapterTest`), `server.permissionReplies` = décisions exactes.

### V1-07 — Questions agent **[M]** + **[A]**

Critère CDC : afficher et répondre aux questions pending (PROPOSÉ A, lot L3).

| # | Étape | Résultat attendu |
| --- | --- | --- |
| 1 | Déclencher une question agent côté serveur | Question affichée avec ses réponses proposées |
| 2 | Répondre (choix + envoi) | Réponse relayée (`POST /question/{id}/reply`), l'agent continue |
| 3 | Rejeter une autre question | Rejet relayé (`POST /question/{id}/reject`) |

**[A]** : `PendingQuestionsAppWiringTest`, `ServerCatalogAppWiringTest`
(`shared/application`), `server.questionReplies` (réponses `answers`
capturées).

### V1-08 — Abort **[M]** + **[A]**

Critère CDC : interrompre un tour busy (PROPOSÉ A, lot L3).

| # | Étape | Résultat attendu |
| --- | --- | --- |
| 1 | Lancer un prompt à réponse longue, taper « Stop/Abort » mid-tour | Le tour s'interrompt, l'UI redevient interactive (< 3 s, objectif §10.1) |
| 2 | Vérifier le transcript après abort | Seules les parties livrées avant l'abort sont conservées ; rien n'est rejoué à la reconnexion |

**[A]** : scénario `Abort` (`server.aborts`, `server.abortedSessions`,
`deliveredParts` ; le tour aborté n'est jamais rejoué ni commité).

### V1-09 — Modèles / agents **[M]** + **[A]**

Critère CDC : exposer ceux du serveur, **aucun catalogue codé en dur**.

| # | Étape | Résultat attendu |
| --- | --- | --- |
| 1 | Ouvrir le sélecteur modèle/agent | Liste = `GET /provider` + `GET /agent` du serveur local |
| 2 | Changer de modèle, envoyer un prompt | Le prompt part sur le nouveau modèle |
| 3 | Arrêter le serveur puis rouvrir le sélecteur | État d'erreur transitoire explicite, pas de fausse liste en dur |

**[A]** : scénarios `NoCatalog` (listes vides + motif), `NoCatalogRoutes`
(404 : non supporté), `CatalogUnavailable` (503 : erreur transitoire).

### V1-10 — Dictée **[R]** (vérification partielle **[M]** sur émulateur/simulateur)

Critère CDC : dictée OS vers le composer ; **envoi toujours confirmé** (Q10 → A,
lot L4, [OPE-272](/OPE/issues/OPE-272) : on-device imposé, aucun repli cloud).

| # | Étape | Résultat attendu |
| --- | --- | --- |
| 1 | **[M]** Ouvrir le composer sur émulateur/simulateur sans reconnaissance vocale | Si la locale n'est pas supportée : bouton dictée désactivé + message clair, **aucun repli cloud** |
| 2 | **[R]** Sur appareil réel, locale supportée : dicter puis envoyer par tap explicite | Transcript dans le composer, **aucun envoi automatique** (couvert par [OPE-278](/OPE/issues/OPE-278)) |

Les émulateurs/simulateurs n'offrent pas de reconnaissance vocale on-device
représentative (pas de micro fiable, pas de service de dictée) : la preuve
complète est réservée aux appareils réels.

### V1-11 — Fichiers (lecture seule) **[M]** + **[A]**

Critère CDC : navigation + viewer lecture seule (coloration, numéros de ligne,
recherche, copie), lot L5.

| # | Étape | Résultat attendu |
| --- | --- | --- |
| 1 | Naviguer dans les fichiers du projet serveur | Arborescence conforme au serveur |
| 2 | Ouvrir un fichier source | Coloration, numéros de ligne, recherche, copie |
| 3 | Ouvrir un fichier ~5 Mo | Viewer fluide (objectif L5) |
| 4 | Tenter une modification | Impossible : lecture seule (l'édition est P1, hors V1) |

**[A]** : tests unitaires `features/files` (`./gradlew :features:files:testDebugUnitTest`).

### V1-12 — Diffs **[M]** + **[A]**

Critère CDC : viewer de diff unifié des changements de l'agent, lot L5.

| # | Étape | Résultat attendu |
| --- | --- | --- |
| 1 | Après un tour modifiant des fichiers, ouvrir le diff | Diff unifié fidèle aux changements serveur |
| 2 | Vérifier l'absence de validation en bloc (OP5) | Aucun bouton « tout approuver » |

**[A]** : tests unitaires `features/files` ; flux `message.part.updated` couvert
par les scénarios SSE (V1-05).

### V1-13 — Notifications **[M]** partiel + **[R]** (couvert par [OPE-278](/OPE/issues/OPE-278))

Critère CDC : MAY — locales quand la plateforme le permet, **sans garantie app
inactive** (Q5 → B) ; OP4 : **aucune approbation depuis une notification**
([OPE-273](/OPE/issues/OPE-273)).

| # | Étape | Résultat attendu |
| --- | --- | --- |
| 1 | **[M]** Permission en attente, app au premier plan | Notification **sans** action « Approve » ; « Deny » fonctionne si présent |
| 2 | **[M]** Taper la notification | App au premier plan → écran de confirmation V1-06 (la décision se prend dans l'app, jamais dans la notification) |
| 3 | **[R]** App inactive / tuée, push, masquage multitâche | Pas de perte d'état ; aperçu app-switcher masqué ; preuve sur appareils réels ([OPE-278](/OPE/issues/OPE-278)) |

Les notifications **push** (FCM/APNs) ne sont pas testables sur
émulateur/simulateur et sont réservées aux appareils réels.

## 4. Cas négatifs transverses (chaque parcours)

| Cas | Commande / manipulation | Attendu |
| --- | --- | --- |
| 401 | `OPENCODE_SERVER_PASSWORD` changé côté serveur sans mettre à jour l'app | Message d'auth clair, aucune donnée affichée |
| 500 | Route serveur en erreur (ou scénario `ServerError` côté tests) | Erreur typée affichée, pas de crash |
| Perte réseau | `adb shell svc wifi disable` / coupe réseau hôte ; `xcrun simctl` équivalent côté mac | Mode offline lecture seule, cache ≤ 500 ms, reconnexion sans rejeu |
| Kill + reboot | `adb reboot` / effacement simulateur puis re-test V1-01 étape 4 | Profil + cache reconstruits depuis le serveur |

## 5. Commandes de preuve automatisée (toutes cibles)

```bash
# Unitaires tous modules (MockOpenCodeServer : scénarios V1-04 → V1-09)
./gradlew test --no-daemon --stacktrace

# Frontières d'architecture §5.2 (Konsist)
./gradlew :architecture-tests:test --no-daemon

# Instrumentés Android sur émulateur (T1 pinning + T3 cache)
./gradlew :shared:security:connectedDebugAndroidTest \
  :shared:persistence:connectedDebugAndroidTest --no-daemon --stacktrace

# iOS simulateur (T1 pinning NSURLSession + T3, macOS)
./gradlew :shared:security:iosSimulatorArm64Test \
  :shared:persistence:iosSimulatorArm64Test --no-daemon --stacktrace
```

Zéro test à `tests="0"` : un run vert sans test exécuté ne prouve rien
(voir la garde en fin de `scripts/t1/run-android-device-validation.sh`).

## 6. Réservé aux appareils réels — couvert par OPE-278 (pas par ce plan)

| Sujet | Pourquoi l'émulateur/simulateur ne suffit pas |
| --- | --- |
| **Biométrie** (Face ID / Touch ID / `BiometricPrompt`, optionnelle, fail-closed, [OPE-274](/OPE/issues/OPE-274)) | Enrôlement réel + annulation/échec au prompt système ; la simulation d'empreinte d'émulateur n'est pas une preuve |
| **Dictée** (V1-10 complète, on-device, [OPE-272](/OPE/issues/OPE-272)) | Micro + moteur de reconnaissance on-device par locale ; aucun envoi auto |
| **Notifications push** (FCM/APNs, app inactive, V1-13) | Pas de push sur émulateur/simulateur ; tap → confirmation in-app (OP4) à prouver sur réel |
| **Masquage multitâche** (aperçu app-switcher, capture) | Rendu app-switcher réel + `FLAG_SECURE` sur matériel |
| **« Tout effacer »** (secrets, cache + `-wal`/`-shm`, notifications, profil, [OPE-275](/OPE/issues/OPE-275), ADR 0009) | Vérification d'absence de résidu sur stockage réel ; le déclenchement et le retour à l'écran de connexion sont exerçables sur émulateur en complément |
| Mesures perf §10.1 (cold < 2 s, warm < 750 ms, SSE→UI < 100 ms hors réseau…) | Seuils à mesurer sur matériel (lot L6) |

## 7. Rapport de recette (template — recopié par OPE-304)

### Smoke automatisé en une commande

Depuis la racine du dépôt, avec un émulateur Android API 31+ déjà démarré ou
un Mac équipé de Xcode/XcodeGen :

```bash
bash scripts/smoke/recette-v1-smoke.sh android
bash scripts/smoke/recette-v1-smoke.sh ios
```

Le script Android exécute les tests JVM ciblés (dont les scénarios
`MockOpenCodeServer`), installe, lance puis efface les données de l'application.
Le script iOS construit l'app, l'installe et la lance, exécute les tests
`ConnectionScreenUITests`, puis désinstalle l'app pour effacer son état local.
Toute étape en échec produit un code retour non nul. Ces tests ne remplacent pas
le parcours manuel avec `opencode serve` décrit plus haut : le mock serveur est
in-process et ne reçoit pas de trafic réseau d'une app installée.

Un run réussi imprime `PASS` et peut être reporté dans la matrice ci-dessous ;
il ne prouve pas les étapes marquées **[M]** ou **[R]**.

Environnement : commit SHA `…………`, APK (sha256 `…………`), `.app` simulateur
(sha256 `…………`), AVD `…………` (API `…………`), simulateur `…………` (iOS `…………`),
serveur `opencode serve` version `…………` (sortie de `/global/health`).

| Parcours | Android | iOS | Classe | Preuve |
| --- | --- | --- | --- | --- |
| V1-01 Connexion | PASS / FAIL | PASS / FAIL | M+A | captures + logs |
| V1-02 Handshake | PASS / FAIL | PASS / FAIL | M+A | captures + `health` |
| V1-03 Découverte | PASS / FAIL | PASS / FAIL | M+A | captures QR |
| V1-04 Sessions | PASS / FAIL | PASS / FAIL | M+A | captures + `server.prompts`=1 |
| V1-05 Chat | PASS / FAIL | PASS / FAIL | M+A | captures streaming |
| V1-06 Permissions | PASS / FAIL | PASS / FAIL | M+A | captures bandeau |
| V1-07 Questions | PASS / FAIL | PASS / FAIL | M+A | captures |
| V1-08 Abort | PASS / FAIL | PASS / FAIL | M+A | captures + transcript |
| V1-09 Modèles/agents | PASS / FAIL | PASS / FAIL | M+A | captures sélecteur |
| V1-10 Dictée | N/A (partiel §3) | N/A (partiel §3) | R | renvoyé à OPE-278 |
| V1-11 Fichiers | PASS / FAIL | PASS / FAIL | M+A | captures viewer |
| V1-12 Diffs | PASS / FAIL | PASS / FAIL | M+A | captures diff |
| V1-13 Notifications | partiel (§3) | partiel (§3) | M+R | renvoyé à OPE-278 pour le reste |

Chaque FAIL bloquant ouvre une issue `mvp-v1` avec gravité, ou renvoie à une
décision go/no-go (critère [OPE-304](/OPE/issues/OPE-304)).
