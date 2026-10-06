# Politique de confidentialité / Privacy Policy

<!--
  Document publié par le Technical Writer (OPE-277). Décrit le comportement
  **livré**, pas une promesse. Chaque affirmation renvoie soit à un ADR, soit
  au code livré et revu. Les écarts entre la décision et le livré sont
  signalés explicitement dans « Limites connues », jamais masqués.
-->

- **Version** : 1.0 — mis à jour le 2026-10-06
- **Portée** : OpenCode Mobile V1 (Android API 31+, iOS 26+), lots L0 à L4 livrés
- **Licence du logiciel** : MIT — le code est public et auditable
- **Textes normatifs** : *Cahier des charges d'architecture v1.0* ([OPE-2](/OPE/issues/OPE-2)) §7.3, §7.4, §8.1, §8.3, §9.3, §9.4, §9.5

Ce document existe en deux langues dans le même fichier : **français** puis
**anglais**. Les deux versions ont la même portée ; en cas de divergence, la
version correspondant à la langue de l'interface (français ou anglais) fait foi.

---

# Français

## 1. En bref

OpenCode Mobile est un **télécommandeur** de votre propre serveur OpenCode.
L'application ne comporte **aucun backend produit**, **aucun compte
utilisateur** et **aucune télémétrie**. Elle ne peut donc ni collecter vos
consignes, ni vos réponses, ni vos fichiers, ni vos identifiants, ni les
transmettre à qui que ce soit d'autre que le serveur que vous avez vous-même
configuré.

Vos données restent sur votre appareil et sur votre serveur. C'est le principe
fondateur du produit, et les décisions qui le protègent sont documentées en
architecture (voir §11).

## 2. Qui traite vos données

**Personne, en dehors de vous.** Il n'existe ni éditeur, ni équipe support, ni
sous-traitant recevant vos données. Le logiciel est distribué sous licence MIT ;
vous pouvez lire, compiler et auditer intégralement son code avant de
l'installer.

Il n'y a aucun compte à créer : l'application ne connaît ni votre identité,
ni votre adresse électronique, ni votre localisation.

## 3. Ce que l'application stocke sur l'appareil

| Donnée | Où elle vit | Durée |
| --- | --- | --- |
| **Credential serveur** (secret d'accès) | SecureStore — Keystore Android / Keychain iOS. Jamais en préférences, en base, ni en fichier en clair. | Tant que vous restez connecté, ou jusqu'à « Tout effacer » |
| **Profil serveur** (adresse, libellé) | Stockage local de l'application | Idem |
| **Pin d'identité** du serveur (TOFU) | Stockage local, vérifié à chaque connexion TLS | Idem |
| **Cache chiffré** (sessions, messages, transcription) | Base SQLDelight chiffrée au repos + matériel de clé dans le SecureStore | Jusqu'à la connexion suivante, puis reconstruit depuis le serveur |
| **Préférences** (accès local : langue, thème, masquage, biométrie optionnelle) | Préférences de l'application, non synchronisées | Tant que l'app est installée |

Points importants :

- **Les clés de fournisseur LLM ne sont jamais stockées sur l'appareil.** Si
  votre serveur OpenCode utilise un fournisseur, la clé reste sur votre
  serveur.
- **Le cache est un dérivé reconstructible.** Il est chiffré au repos tant
  qu'il existe, et il est reconstruit depuis le serveur à la prochaine
  connexion.
- **Les préférences d'accès local ne sont pas synchronisées** et ne quittent
  jamais l'appareil.

## 4. Ce qui quitte l'appareil, et vers où

La seule destination réseau de l'application est **l'adresse de serveur que
vous avez saisie**. Il n'existe dans le code produit aucune URL codée en dur
vers un tiers : pas d'API de télémétrie, pas d'analytics, pas de crash
reporting, pas de push, pas de CDN.

Le transport est chiffré (TLS). Une adresse en `http://` n'est acceptée que
sur réseau local ou Tailscale, et l'avertissement correspondant est affiché ;
une adresse publique en `http://` est refusée.

**Dictée : aucune exception.** Voir §6.

**Notifications : aucune exception.** Voir §7.

## 5. Permissions déclarées par l'application

L'application ne déclare que les permissions suivantes. Chacune est liée à une
fonction décrite dans ce document ; aucune n'est justifiée par de la télémétrie,
car il n'y en a pas.

| Permission | Plateforme | Usage | Demandée |
| --- | --- | --- | --- |
| `INTERNET` | Android | Atteindre **votre** serveur OpenCode | À l'installation (déclarée) |
| `RECORD_AUDIO` | Android | Dictée, traitée sur l'appareil | Au premier usage de la dictée |
| `POST_NOTIFICATIONS` | Android | Notifications **locales** | Au premier besoin, via le système |
| `USE_BIOMETRIC` | Android | Biométrie **optionnelle** | Au premier usage si vous l'activez |
| `CAMERA` | Android / iOS | Scanner le QR d'import d'un profil serveur | Au premier scan |
| Microphone (`NSMicrophoneUsageDescription`) | iOS | Dictée, traitée sur l'appareil | Au premier usage de la dictée |
| Reconnaissance vocale (`NSSpeechRecognitionUsageDescription`) | iOS | Transcription **sur l'appareil** | Au premier usage de la dictée |
| Face ID (`NSFaceIDUsageDescription`) | iOS | Biométrie **optionnelle** | Au premier usage si vous l'activez |

Si vous refusez une permission, la fonctionnalité correspondante reste
indisponible et le reste de l'application continue de fonctionner ; la dictée
affiche alors un message court et le clavier reste utilisable.

## 6. Dictée vocale — traitement entièrement sur l'appareil

Quand vous dictez une consigne dans le composeur :

- **L'audio et la transcription sont traités intégralement sur l'appareil.**
  Aucun enregistrement audio, ni transcription, ni fragment de texte
  dicté ne part vers Apple, Google, ou tout autre tiers.
- **Le réseau n'est pas utilisé pour la dictée.** La reconnaissance fonctionne
  hors ligne dès qu'un modèle linguistic local est présent.
- **Quand le traitement on-device est indisponible** — modèle absent pour la
  langue active, système d'exploitation sans reconnaissance on-device, non
  enrolé — **la dictée est désactivée**. Le bouton micro est désactivé (et non
  caché) avec un message court, et le clavier reste utilisable. Il n'existe
  **aucun repli cloud** : aucune version de l'application ne peut basculer sur
  un fournisseur distant, y compris en cas d'erreur.
- **La permission microphone** est demandée au premier usage, jamais au
  démarrage.
- **L'envoi reste un geste distinct.** La dictée écrit dans le brouillon du
  composeur ; l'envoi au serveur n'a lieu que sur une action explicite de
  votre part. Le port de dictation n'a aucune capacité d'envoi.

Sur Android, l'application n'utilise que
`SpeechRecognizer.createOnDeviceSpeechRecognizer` ; l'API réseau-capable et
l'intent de dictée système sont absents du binaire, et un test d'architecture
fait échouer la compilation s'ils réapparaissent. Sur iOS, chaque requête
force `requiresOnDeviceRecognition = true`, ce qui transforme « peut être
local » en « local ou échec ».

La spécification technique est [l'ADR dictée on-device](adr/on-device-speech-to-text.md).
Elle décrit aussi les évolutions possibles **après** la V1 (par exemple une
transcription hébergée par votre propre serveur). Ces évolutions **ne font pas
partie de la V1 livrée** et ne sont actives dans aucune version publiée : tant
qu'elles n'existent pas, la dictée reste purement on-device.

> Conséquence pratique : sur certains couples appareil / version d'OS / langue,
> la dictée n'est pas disponible. C'est un comportement voulu, pas un défaut.

## 7. Notifications — locales, et jamais une approbation

- Les notifications sont **strictement locales**. Elles sont produites par
  l'appareil à partir de l'état connu du serveur. Il n'y a **ni service push,
  ni serveur de notification**, ni infrastructure ajoutée à cette fin.
- Quatre déclencheurs : **permission en attente**, **question de l'agent**,
  **session terminée**, **session en erreur**.
- **Aucune approbation depuis une notification.** Le vocabulaire d'action
  partagé n'a pas de membre « Approuver » : c'est le système de types, et non
  une vérification à l'exécution, qui l'interdit. Les actions possibles sont au
  plus « Ouvrir » et « Refuser » — refuser est la direction sûre et réversible.
- **Approuver exige l'application au premier plan**, sur l'écran de
  confirmation dédié, avec son contrôle biométrique ou de code d'appareil.
- **Le contenu de la notification est minimal** : ni la commande, ni ses
  arguments, ni les diffs de fichiers. Une notification est visible sur un
  écran verrouillé ; le contenu exact n'est montré que sur la surface
  authentifiée de l'application. Sur Android, la notification est marquée
  privée.
- Une notification est un **signal, pas un état**. Si la plateforme la refuse
  ou si l'application est tuée, seul le signal est perdu : permissions,
  questions et statuts sont reconstruits depuis le serveur.

Cette règle est la décision ouverte **OP4**, tranchée dans
[ADR 0005 — décisions OP1 à OP5](adr/0005-v1-open-decisions-op1-op5.md).

## 8. Biométrie — facultative, et jamais un substitut

- La biométrie (Face ID, Touch ID, `BiometricPrompt`) est **facultative** et
  **désactivée par défaut**.
- **Elle ne remplace jamais l'authentification serveur.** Désactiver la
  biométrie ne donne accès à aucun credential. Le credential serveur reste le
  seul moyen de s'authentifier auprès de votre serveur.
- Elle sert de **re-confirmation de convenance** sur les écrans protégés, et,
  pour « Tout effacer », de **garde supplémentaire facultatif**.
- Elle est **fail-closed** : si aucun authentificateur n'est enrolé, ou si
  l'implémentation n'est pas câblée, le résultat est « indisponible », jamais
  « réussi ». Une annulation par l'utilisateur est traitée comme une annulation,
  pas comme un succès.
- Les données biométriques ne sont **ni accessibles à l'application, ni
  conservées** : elles sont traitées par le système d'exploitation et le
  matériel.

## 9. Masquage de l'aperçu multitâche et blocage des captures

- **Masquage de l'aperçu multitâche : activé par défaut.** Quand l'application
  passe en arrière-plan, une couverture opaque masque le contenu avant que le
  système ne capture l'aperçu du sélecteur d'apps. Le transcript, les sessions
  et les messages ne sont donc pas visibles dans l'aperçu.
- **Blocage des captures d'écran et d'enregistrement : désactivé par défaut.**
  Vous pouvez l'activer explicitement. Sur Android, cette option utilise
  `FLAG_SECURE`, qui bloque aussi les captures d'écran et l'enregistrement.
- **Les deux réglages sont distincts.** Le masquage ne doit jamais activer le
  blocage des captures : c'est le cas.

## 10. « Tout effacer » — ce que la commande efface, et ce qu'elle n'efface pas

« Tout effacer » est une action **explicite**, confirmée, jamais automatique,
jamais déclenchée depuis une notification, un lien profond ou une tâche de
fond. Avant confirmation, l'interface énumère exactement ce qui sera effacé.

**Sur cet appareil, la commande efface :**

1. la **session authentifiée en cours** — la connexion est fermée et le
   credential cesse d'être utilisable en mémoire ;
2. le **credential serveur** (SecureStore) ;
3. le **profil serveur** et son **pin d'identité** ;
4. le **cache chiffré** — base de données, fichiers annexes (`-wal`, `-shm`,
   `-journal`) et matériel de clé ;
5. les **notifications** postées par l'application.

**Ce qui n'est PAS effacé :**

- **Votre serveur.** « Tout effacer » est purement local : aucune session,
  aucun message, aucune permission n'est supprimé côté serveur.
- **Les préférences non sensibles** (langue, thème, masquage, blocage des
  captures, biométrie facultative). Elles ne contiennent ni secret, ni donnée de
  serveur, ni contenu de cache.
- **Les clés de fournisseur LLM**, qui ne sont jamais stockées sur l'appareil.

Après l'effacement, l'application revient à l'écran de connexion et **ne
reconnecte pas** automatiquement avec le credential effacé. Le cache est
reconstruit **uniquement depuis le serveur** lors de la prochaine connexion.

Si une catégorie n'a pas pu être effacée, l'application **le dit** et nomme la
catégorie concernée ; un effacement partiel n'est jamais présenté comme un
succès.

La spécification technique est [ADR 0009 — « Tout effacer » (OP5)](adr/0009-erase-everything-v1.md).

## 11. Journaux, diagnostics et télémétrie

- **Aucune télémétrie.** L'application n'embarque ni bibliothèque d'analytics,
  ni rapport de crash, ni compteur d'usage, ni identification d'appareil. Ces
  trois capacités sont *proposées* dans la spécification mais **ne sont pas
  implémentées dans la V1 livrée**.
- **Aucun secret ni contenu sensible n'est écrit dans les journaux** : ni mot de
  passe, ni token, ni en-tête `Authorization`, ni consigne, ni contenu de
  fichier, ni corps de requête ou de réponse HTTP. Un contrôle d'intégration
  continue échoue la compilation si une branche de journalisation contourne le
  mécanisme d'expurgation.
- Les journaux restent sur l'appareil et sont attachés au diagnostic de
  l'utilisateur ; ils sont expurgés au moment de l'export.

## 12. Limites connues de la version 1

Cette section décrit ce que **la V1 livrée ne fait pas encore**. Elle est
volontairement explicite.

- **Blocage des captures : Android uniquement.** Sur iOS, iOS ne permet pas de
  bloquer une capture indépendamment d'un voile ; aucun interrupteur utilisateur
  n'est proposé dans la V1. Le masquage de l'aperçu multitâche, lui, est actif
  sur les deux plateformes.
- **Effacement du cache sur iOS.** La pile de cache iOS n'est pas encore
  composée dans ce build ; aucune base de cache n'existe donc sur l'appareil.
  L'action « Tout effacer » y efface la session, le credential, le profil, le
  pin et les notifications ; l'effacement du cache est un no-op documenté, à
  brancher sur un vrai effaceur dès que la pile iOS atterrit. Aucune donnée
  n'est réellement exposée par ce no-op, puisqu'il n'y a rien à effacer.
- **Biométrie facultative non obligatoire pour « Tout effacer ».** Un
  utilisateur qui n'a pas enrolé d'empreinte peut tout effacer. C'est voulu.
- **Dictation indisponible sur certains appareils / langues.** Voir §6.
- **Notifications sans mode « application totalement inactive ».** Aucune
  garantie de notification n'est donnée quand le système la refuse ; l'état
  reste reconstruit depuis le serveur.
- **Aucune édition du cache, aucun terminal interactif, aucun profil serveur
  multiple dans la V1.** Ces capacités sont hors périmètre V1.

## 13. Modifier ce document

Ce document décrit un comportement livré. Il est mis à jour dans la même pull
request que le code qu'il décrit. Chaque affirmation renvoie à un ADR ou à une
intégration continue ; si une affirmation ne peut pas être vérifiée dans le
code, elle n'a rien à faire ici.

## 14. Références

- *Cahier des charges d'architecture v1.0* ([OPE-2](/OPE/issues/OPE-2)) — §7.3 (secrets et accès local), §7.4 (vie privée et télémétrie), §8.1 (cache et offline), §8.3 (logs et diagnostics), §9.4 (notifications), §9.5 (voix)
- [ADR — Dictée on-device ou désactivation](adr/on-device-speech-to-text.md)
- [ADR 0005 — Décisions V1 OP1 à OP5](adr/0005-v1-open-decisions-op1-op5.md) — OP4 (pas d'approbation depuis une notification), OP5 (« Tout effacer »)
- [ADR 0009 — « Tout effacer », périmètre et comportement V1](adr/0009-erase-everything-v1.md)
- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — modèle de sécurité, chiffrement du cache, SecureStore, journalisation expurgée
- [docs/THREAT-MODEL.md](THREAT-MODEL.md) — T2 (biométrie), T3 (cache au repos), T5 (dictée), T13 (notifications)
- [docs/local-access.md](local-access.md) — protections d'accès local, masquage et blocage des captures
- [docs/l4-mobile-integration.md](l4-mobile-integration.md) — comportement livré du lot L4

---

# English

## 1. In short

OpenCode Mobile is a **remote control** for your own OpenCode Server. The app
has **no product backend**, **no user account** and **no telemetry**. It cannot
collect your prompts, answers, files or credentials, and it cannot send them to
anyone other than the server you configured yourself.

Your data stays on your device and on your server. That is the product's
founding principle, and the decisions that protect it are documented in the
architecture (see §11).

## 2. Who handles your data

**Nobody, apart from you.** There is no operating company as a third party, no
support team, no sub-processor receiving your data. The software ships under the
MIT licence; you can read, build and fully audit its source before installing
it.

There is no account to create: the app knows neither your identity, nor your
email address, nor your location.

## 3. What the app stores on the device

| Data | Where it lives | Lifetime |
| --- | --- | --- |
| **Server credential** (access secret) | SecureStore — Android Keystore / iOS Keychain. Never in preferences, a database, or a plaintext file. | While you stay connected, or until "Erase everything" |
| **Server profile** (address, label) | App-local storage | Same |
| **Server identity pin** (trust on first use) | App-local storage, checked on every TLS connection | Same |
| **Encrypted cache** (sessions, messages, transcript) | SQLDelight database encrypted at rest + key material in the SecureStore | Until the next connection, rebuilt from the server |
| **Preferences** (local access: language, theme, masking, optional biometrics) | App preferences, not synced | While the app is installed |

Key points:

- **LLM provider keys are never stored on the device.** If your OpenCode server
  uses a provider, the key stays on your server.
- **The cache is a rebuildable derivative.** It is encrypted at rest for as long
  as it exists, and it is rebuilt from the server on the next connection.
- **Local-access preferences are not synced** and never leave the device.

## 4. What leaves the device, and where to

The app's only network destination is **the server address you typed**. The
product code contains no hard-coded third-party URL: no telemetry API, no
analytics, no crash reporting, no push, no CDN.

The transport is encrypted (TLS). An `http://` address is accepted only on a
local network or over Tailscale, with a visible warning; a public `http://`
address is rejected.

**Dictation is no exception.** See §6.

**Notifications are no exception.** See §7.

## 5. Permissions the app declares

The app declares only the following permissions. Each one maps to a feature
described in this document; none of them exists for telemetry, because there is
none.

| Permission | Platform | Used for | Requested |
| --- | --- | --- | --- |
| `INTERNET` | Android | Reaching **your** OpenCode Server | At install time (declared) |
| `RECORD_AUDIO` | Android | Dictation, processed on-device | At first use of dictation |
| `POST_NOTIFICATIONS` | Android | **Local** notifications | When the system first needs it |
| `USE_BIOMETRIC` | Android | **Optional** biometrics | At first use, if you enable it |
| `CAMERA` | Android / iOS | Scanning a server-profile import QR code | At first scan |
| Microphone (`NSMicrophoneUsageDescription`) | iOS | Dictation, processed on-device | At first use of dictation |
| Speech recognition (`NSSpeechRecognitionUsageDescription`) | iOS | Transcription **on-device** | At first use of dictation |
| Face ID (`NSFaceIDUsageDescription`) | iOS | **Optional** biometrics | At first use, if you enable it |

If you deny a permission, the matching feature becomes unavailable and the rest
of the app keeps working: dictation then shows a short message and the keyboard
stays usable.

## 6. Voice dictation — processed entirely on-device

When you dictate a prompt in the composer:

- **Audio and transcription are processed entirely on the device.** No audio
  recording, no transcript and no fragment of dictated text is sent to Apple,
  Google or any other third party.
- **The network is not used for dictation.** Recognition works offline as soon
  as a local language model is present.
- **When on-device processing is unavailable** — no model for the active
  language, an OS with no on-device recogniser, not enrolled — **dictation is
  disabled**. The mic button is disabled (not hidden) with a short message, and
  the keyboard stays usable. There is **no cloud fallback**: no build of the app
  can switch to a remote provider, including after an error.
- **The microphone permission** is requested at first use, never at startup.
- **Sending stays a separate gesture.** Dictation writes into the composer
  draft; sending to the server happens only on an explicit action of yours. The
  dictation port has no send capability at all.

On Android the app uses only `SpeechRecognizer.createOnDeviceSpeechRecognizer`;
the network-capable API and the system dictation intent are absent from the
binary, and an architecture test fails the build if either reappears. On iOS
every request forces `requiresOnDeviceRecognition = true`, which turns "may be
on-device" into "on-device or fail".

The technical specification is [the on-device dictation ADR](adr/on-device-speech-to-text.md).
It also describes possible **post-V1** evolutions (for example transcription
hosted by your own server). Those evolutions are **not part of the shipped V1**
and are active in no released version: while they do not exist, dictation stays
purely on-device.

> Practical consequence: on some device / OS version / language combinations,
> dictation is unavailable. That is intended behaviour, not a defect.

## 7. Notifications — local, and never an approval

- Notifications are **strictly local**. They are produced on the device from the
  server state the app already knows. There is **no push service, no
  notification server**, and no infrastructure added for one.
- Four triggers: **pending permission**, **agent question**, **session
  finished**, **session error**.
- **No approval from a notification.** The shared action vocabulary has no
  "Approve" member: the type system, not a runtime check, is what forbids it.
  The only possible actions are "Open" and "Deny" — denying is the safe,
  reversible direction.
- **Approving requires the app in the foreground**, on the dedicated
  confirmation screen, with its biometric or device-credential check.
- **Notification content is minimal**: no command, no arguments, no file diffs.
  A notification is visible on a locked screen; the exact content is shown only
  on the app's authenticated surface. On Android the notification is marked
  private.
- A notification is a **signal, not a state**. If the platform refuses it or
  the app is killed, only the signal is lost: permissions, questions and
  statuses are rebuilt from the server.

This rule is open decision **OP4**, settled in
[ADR 0005 — V1 open decisions OP1–OP5](adr/0005-v1-open-decisions-op1-op5.md).

## 8. Biometrics — optional, and never a substitute

- Biometrics (Face ID, Touch ID, `BiometricPrompt`) are **optional** and **off by
  default**.
- **They never replace server authentication.** Turning biometrics off grants
  access to no credential. The server credential remains the only way to
  authenticate to your server.
- They act as a **convenience re-confirmation** on protected screens and, for
  "Erase everything", as an **optional extra gate**.
- They are **fail-closed**: if no authenticator is enrolled, or the
  implementation is not wired, the result is "unavailable", never "succeeded".
  A user cancellation is treated as a cancellation, not a success.
- Biometric data is **neither accessible to the app nor stored**: it is handled
  by the operating system and the hardware.

## 9. App-switcher masking and capture blocking

- **App-switcher preview masking: on by default.** When the app moves to the
  background, an opaque cover hides the content before the system takes the
  app-switcher snapshot. Transcript, sessions and messages are therefore not
  visible in that snapshot.
- **Screenshot and screen-recording blocking: off by default.** You can turn it
  on explicitly. On Android this option uses `FLAG_SECURE`, which also blocks
  screenshots and screen recording.
- **The two settings are separate.** Masking must never turn on capture
  blocking — and it does not.

## 10. "Erase everything" — what it erases, and what it does not

"Erase everything" is an **explicit**, confirmed action, never automatic, never
triggered from a notification, a deep link or a background task. Before
confirmation, the interface lists exactly what will be erased.

**On this device, the action erases:**

1. the **live authenticated session** — the connection is closed and the erased
   credential ceases to be usable in memory;
2. the **server credential** (SecureStore);
3. the **server profile** and its **identity pin**;
4. the **encrypted cache** — database, sidecar files (`-wal`, `-shm`,
   `-journal`) and key material;
5. the **notifications** posted by the app.

**What is NOT erased:**

- **Your server.** "Erase everything" is local-only: no session, message or
  permission is deleted server-side.
- **Non-sensitive preferences** (language, theme, masking, capture blocking,
  optional biometrics). They hold no secret, no server data and no cache
  content.
- **LLM provider keys**, which are never stored on the device.

After the erase the app returns to the connection screen and **does not
reconnect** automatically with the erased credential. The cache is rebuilt
**from the server only** on the next connection.

If a category could not be erased, the app **says so** and names the affected
category; a partial erase is never presented as a success.

The technical specification is [ADR 0009 — "Erase everything" V1 scope and
behaviour (OP5)](adr/0009-erase-everything-v1.md).

## 11. Logs, diagnostics and telemetry

- **No telemetry.** The app ships no analytics library, no crash reporter, no
  usage counter and no device identifier. Those three capabilities are
  *proposed* in the specification but **are not implemented in the shipped V1**.
- **No secret or sensitive content is ever written to logs**: no password, no
  token, no `Authorization` header, no prompt, no file content, no HTTP
  request or response body. A continuous integration check fails the build if a
  logging call bypasses the redaction mechanism.
- Logs stay on the device and are attached to the user's diagnostics bundle;
  they are redacted at export time.

## 12. Known V1 limitations

This section states what **the shipped V1 does not do yet**. It is deliberately
explicit.

- **Capture blocking: Android only.** On iOS, the platform cannot block a
  capture independently of an overlay, so no user switch is offered in V1.
  App-switcher masking, on the other hand, is active on both platforms.
- **Cache erase on iOS.** The iOS cache stack is not composed in this build, so
  no cache database exists on the device. "Erase everything" there erases the
  session, credential, profile, pin and notifications; cache erasure is a
  documented no-op, to be bound to a real eraser once the iOS stack lands. No
  data is actually exposed by this no-op, because there is nothing to erase.
- **Optional biometrics are not required for "Erase everything".** A user who
  has not enrolled a fingerprint can erase everything. This is intended.
- **Dictation is unavailable on some devices / languages.** See §6.
- **No fully-background app guarantee for notifications.** No notification
  guarantee is made when the system refuses it; state is rebuilt from the
  server.
- **No file editing, no interactive terminal and no multi-server profiles in
  V1.** Those capabilities are out of V1 scope.

## 13. Changing this document

This document describes shipped behaviour. It is updated in the same pull
request as the code it describes. Every claim points to an ADR or to a
continuous check; a claim that cannot be verified in the code does not belong
here.

## 14. References

- *Cahier des charges d'architecture v1.0* — §7.3 (secrets and local access), §7.4 (privacy and telemetry), §8.1 (cache and offline), §8.3 (logs and diagnostics), §9.4 (notifications), §9.5 (voice)
- [ADR — Force on-device speech recognition, or disable dictation](adr/on-device-speech-to-text.md)
- [ADR 0005 — Closing the V1 open decisions OP1–OP5](adr/0005-v1-open-decisions-op1-op5.md) — OP4 (no approval from a notification), OP5 ("Erase everything")
- [ADR 0009 — "Erase everything" V1 scope and behaviour](adr/0009-erase-everything-v1.md)
- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — security model, cache encryption, SecureStore, redacted logging
- [docs/THREAT-MODEL.md](THREAT-MODEL.md) — T2 (biometrics), T3 (cache at rest), T5 (dictation), T13 (notifications)
- [docs/local-access.md](local-access.md) — local-access protections, masking and capture blocking
- [docs/l4-mobile-integration.md](l4-mobile-integration.md) — delivered L4 behaviour
