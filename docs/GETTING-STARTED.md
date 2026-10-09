# Guide de démarrage utilisateur — OpenCode Mobile v1

> **Public :** testeurs et premiers utilisateurs de l'app (pas les développeurs
> de l'app — pour compiler le projet, voir la section « Getting started »
> du [README](../README.md)).
> **Ce que vous allez faire :** installer l'app, lancer votre serveur
> OpenCode, connecter l'app au serveur, créer une session, approuver une
> permission, puis tout effacer.
> **Durée estimée :** ~20 minutes (hors installation des outils).
>
> Légende de vérification — chaque commande porte son statut :
> ✅ **vérifiée** = exécutée le 2026-10-09 pendant la rédaction ;
> ⚠️ **non vérifiée** = reprise de la documentation du dépôt, à confirmer
> lors de la recette ([OPE-301](/OPE/issues/OPE-301)).

---

## 1. Pré-requis

| Besoin | Détail |
| --- | --- |
| Un téléphone Android (API 31+) ou un Mac avec Xcode 16+ (simulateur iOS 16+) | L'app est un client natif, il n'y a pas de version web |
| Un ordinateur qui fait tourner **OpenCode Server v2** (`opencode serve`) | Votre machine, ou une machine du même réseau |
| Le téléphone et le serveur sur le **même réseau local (LAN)** ou le même **réseau Tailscale** | L'app se connecte en point à point à *votre* serveur ; il n'y a ni cloud ni relais |
| Serveur supporté | OpenCode Server v2, gamme `>= 1.18.0` (profil `CompatibilityProfile.OpenCodeServerV2`, voir [docs/API.md](API.md)) |

Confidentialité (à lire avant d'installer) :
[docs/PRIVACY.md](PRIVACY.md) — aucune télémétrie, stockage local chiffré,
« Tout effacer ».

---

## 2. Lancer OpenCode Server

Sur l'ordinateur qui hébergera le serveur :

```bash
opencode --version   # ✅ vérifiée : affiche p. ex. « opencode v2.0.18 »
```

```bash
opencode serve --port 4096 --hostname 0.0.0.0   # ⚠️ non vérifiée sous cette forme exacte
```

Notes :

- ✅ **vérifié** en boucle locale le 2026-10-09 :
  `opencode serve --port 45678 --hostname 127.0.0.1` démarre bien un
  serveur (v2.0.18) ; la racine `/` sert l'interface web OpenCode.
- Pour que le téléphone atteigne le serveur, liez une adresse du LAN
  (`--hostname 0.0.0.0`, ⚠️ non vérifiée) ou l'adresse Tailscale de la
  machine, et ouvrez le port choisi (ici `4096`) dans le pare-feu.
- Si vous protégez le serveur par mot de passe (`OPENCODE_SERVER_PASSWORD`),
  retenez-le : l'app vous le demandera une fois, puis le conservera dans le
  Keystore Android / le Keychain iOS (jamais en clair, jamais dans un QR —
  voir [docs/API.md](API.md#authentication)).
- ⚠️ **Écart de version constaté** (serveur v2.0.18, 2026-10-09) : sur ce
  serveur, `/global/health` répond `200 text/html` (interface web) et
  `/api/health` répond `401 {"_tag":"UnauthorizedError"}` sans mot de passe.
  L'app cible le profil documenté dans [docs/API.md](API.md) ; si l'écran de
  connexion affiche « serveur incompatible », vérifiez d'abord la version du
  serveur (`opencode --version`) avant de conclure à un bug de l'app.

---

## 3. Installer l'app

> Il n'y a **pas encore de build publié** (version, TestFlight, Play
> interne). La publication automatique de l'APK est suivie dans
> [OPE-300](/OPE/issues/OPE-300) ; tant qu'elle n'a pas abouti, utilisez
> l'une des deux méthodes ci-dessous. Les liens de téléchargement seront
> ajoutés ici dès la première publication.

### 3a. Android — APK de développement (⚠️ non vérifiée de bout en bout)

```bash
git clone https://github.com/qveys/OpenCodeMobile.git   # ⚠️ non vérifiée
cd OpenCodeMobile

./gradlew :androidApp:assembleDebug   # ⚠️ non vérifiée (produit aussi :assembleRelease + :bundleRelease en CI, voir .github/workflows/build.yml)
```

Installez ensuite l'APK sur un appareil en mode développeur :

```bash
adb install androidApp/build/outputs/apk/debug/*.apk   # ⚠️ non vérifiée (nécessite adb + USB debugging)
```

Repère : la CI construit exactement
`:androidApp:assembleDebug :androidApp:assembleRelease :androidApp:bundleRelease`
(`Build Android (APK/AAB)`, [.github/workflows/build.yml](../.github/workflows/build.yml)).

### 3b. Simulateur iOS — build `.app` (⚠️ non vérifiée, Mac uniquement)

`iosApp/` est un projet Xcode natif (pas un module Gradle), décrit par
`iosApp/project.yml`. Procédure complète dans
[iosApp/README.md](../iosApp/README.md) :

```bash
cd iosApp && xcodegen generate        # ⚠️ non vérifiée (génère iosApp.xcodeproj)
```

```bash
./scripts/ios/build-frameworks.sh      # ⚠️ non vérifiée (phase « Build Kotlin frameworks » de Xcode)
```

Ouvrez ensuite `iosApp.xcodeproj` dans Xcode, choisissez un simulateur
iPhone (iOS 16+) et lancez **Run**. La phase « Sync Compose resources »
s'exécute seule avant la signature.

---

## 4. Connecter l'app à votre serveur (V1-01…V1-03)

Ouvrez l'app : vous arrivez sur l'écran de connexion.

1. **Saisissez l'adresse du serveur** (profil serveur : hôte + port), ou
   **scannez le QR** d'import du profil (bouton « Scan QR code » — demande
   l'autorisation caméra au premier usage). Le QR n'est qu'un transport du
   lien d'import : il ne contient **jamais** de credential
   ([docs/ARCHITECTURE.md](ARCHITECTURE.md#server-profile-import-deep-link--qr)).
2. **Relisez l'écran de révision « Import server profile »** : il affiche
   l'hôte, le port et l'empreinte complets. Validez d'un tap explicite
   (« Add server »). Un lien d'import n'écrit **jamais** un profil tout seul,
   et ne remplace jamais silencieusement un profil existant.
3. **Premier contact — TOFU.** L'app affiche l'empreinte du serveur
   (SHA-256 du SPKI, en hexadécimal séparé par des deux-points, comme une clé
   SSH). Vérifiez-la (comparez avec ce que le serveur annonce localement) et
   confirmez : l'empreinte est épinglée pour ce profil. Le credential n'est
   **envoyé qu'après** cette confirmation.
4. **Reconnexions.** L'empreinte présentée est comparée à l'épingle à chaque
   connexion. En cas de changement : écran bloquant plein écran
   « server identity changed », aucune requête (et aucun credential) ne part
   tant que vous n'avez pas ré-accepté la nouvelle empreinte.

Règles réseau appliquées par l'app (immuables) :

- `https://` accepté partout.
- `http://` en clair accepté **uniquement** en local ou Tailscale
  (`Loopback`, `Lan`, `Tailscale`), avec un avertissement persistant en texte
  clair ; `http://` vers un hôte **public refusé** avant tout envoi.
- Un serveur dont la version est hors gamme supportée affiche un écran
  « serveur incompatible » avec la version détectée et la gamme supportée.

| Exigence | Ce que vous devez voir |
| --- | --- |
| V1-01 — connexion au serveur | Handshake réussi → écran des sessions |
| V1-02 — handshake (santé → version → profil de compatibilité) | Pas d'erreur ; sinon écran d'incompatibilité explicite |
| V1-03 — saisie ou scan de l'adresse | Saisie manuelle **et** import QR passent par le même écran de révision |

---

## 5. Premiers pas : sessions, transcript, permissions (V1-04…V1-09)

### V1-04 — Lister et gérer les sessions

- L'écran principal liste les sessions du serveur (`GET /session`).
- **Créer** : nouvelle session (`POST /session`).
- **Supprimer / forker** : actions sur la session (`DELETE`,
  `POST /session/{id}/fork`).
- Repère de robustesse : tuez l'app en pleine réponse de l'agent,
  rouvrez-la — l'état est reconstruit depuis le serveur (le cache local est
  jetable).

### V1-05 — Envoyer une consigne, lire le transcript en streaming

- Ouvrez une session, tapez une consigne dans le composeur
  (« Type a prompt… » / « Saisissez une consigne… ») et envoyez.
- Le transcript affiche en continu la sortie de l'agent (Markdown + code
  coloré), reçue via le flux d'événements temps réel (SSE `/event`).
- Hors ligne : l'app devient un lecteur du cache avec un bandeau « données
  périmées » ; toute action d'écriture est désactivée.

### V1-06 — Voir et répondre aux demandes de permission

1. Demandez à l'agent quelque chose qui déclenche un appel d'outil
   (p. ex. exécuter une commande ou modifier un fichier).
2. Une **bannière de permission non ignorable** apparaît, avec une
   notification locale strictement informative (jamais de bouton
   « Approuver » dans la notification — au plus « Ouvrir » et « Refuser »).
3. Ouvrez l'écran de confirmation dédié : il affiche la **commande ou le
   diff complet**, jamais un résumé tronqué.
4. Touchez **Approve** (action distincte, jamais pré-sélectionnée), puis
   validez le contrôle **biométrique / code de l'appareil** : la décision
   (`once` | `always` | `reject`) est envoyée (`POST /permission/{id}/reply`).
   - Si l'app passe en arrière-plan avant le tap, la confirmation est
     annulée : rouvrez la demande et recommencez (anti-tapjacking).

### V1-07 — Répondre aux questions de l'agent

- Quand l'agent pose une question (`GET /question`), elle apparaît dans
  l'app : choisissez les réponses et envoyez
  (`POST /question/{id}/reply`), ou rejetez (`POST /question/{id}/reject`).

### V1-08 — Interrompre le tour en cours

- Pendant que l'agent travaille, utilisez l'action **Abort**
  (`POST /session/{id}/abort`) : le tour s'arrête et n'est jamais rejoué.

### V1-09 — Modèles et agents disponibles

- L'app liste les fournisseurs/modèles (`GET /provider`) et les agents
  (`GET /agent`).
- États vides ou indisponibles gérés : liste vide → écran vide explicite ;
  routes absentes (404) ou erreur transitoire (503) → message, pas de crash.

---

## 6. Tout effacer

Réglages → **« Erase everything… » / « Tout effacer… »** (action explicite,
confirmée ; jamais depuis une notification) :

- Effacé **sur cet appareil** : session authentifiée en cours, credential
  serveur (SecureStore), profil serveur + pin d'identité, cache chiffré
  (base + `-wal`/`-shm`/`-journal` + clés), notifications de l'app.
- **Non effacé** : votre serveur (rien n'est supprimé côté serveur),
  préférences non sensibles (langue, thème…), clés LLM (jamais sur
  l'appareil).
- Après effacement : retour à l'écran de connexion, **sans reconnexion
  automatique**. Détail et limites iOS dans
  [docs/PRIVACY.md](PRIVACY.md) (§10 « Tout effacer »).

---

## 7. Dépannage

| Symptôme | Piste |
| --- | --- |
| L'app ne joint pas le serveur | Vérifiez que téléphone et serveur sont sur le même LAN/Tailscale ; `opencode serve` écoute bien sur une adresse joignable (pas `127.0.0.1` seul) et le port est ouvert. ✅ **vérifié** : sans serveur, la connexion échoue proprement (refusé). |
| Écran « serveur incompatible » | Comparez `opencode --version` (✅ vérifiée) à la gamme supportée (`>= 1.18.0`, [docs/API.md](API.md)). Sur serveur v2.0.18, voir l'écart documenté au §2. |
| `http://` refusé | Normal si l'hôte est classé public : passez en `https://` ou restez sur le LAN/Tailscale. |
| Alerte « server identity changed » | Le certificat a changé (réinstall serveur, usurpation possible sur LAN partagé). Ne ré-acceptez qu'après vérification hors bande de la nouvelle empreinte. |
| Dictée indisponible | Comportement voulu : la dictée est 100 % sur l'appareil ou désactivée (aucun repli cloud). Clavier utilisable en remplacement ([docs/PRIVACY.md](PRIVACY.md), §6 Dictée). |
| Notifications sans action « Approuver » | Comportement voulu (décision OP4) : approuver exige l'app au premier plan. |
| Capture d'écran bloquée (Android) | Vous avez activé « Bloquer les captures » (`FLAG_SECURE`) : désactivez-le dans Accès local. Sur iOS, aucun interrupteur n'existe (limite plateforme). |

---

## 8. Aller plus loin

- **Confidentialité** : [docs/PRIVACY.md](PRIVACY.md) (FR/EN).
- **Accès local** (biométrie optionnelle, masquage, captures) :
  [docs/local-access.md](local-access.md) — avec la recette appareil réel.
- **Comportement livré du lot L4** : [docs/l4-mobile-integration.md](l4-mobile-integration.md).
- **API consommée et génération du client** : [docs/API.md](API.md).
- **Architecture et règles de sécurité** :
  [docs/ARCHITECTURE.md](ARCHITECTURE.md).

> Aucune capture d'écran dans ce guide : les écrans portent des données
> potentiellement sensibles et aucune capture expurgée n'a été produite pour
> cette version. Les copies d'écran FR/EN citées entre guillemets reprennent
> verbatim le catalogue de chaînes de l'app.
