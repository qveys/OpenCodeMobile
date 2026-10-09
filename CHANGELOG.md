# Changelog

> Brouillon — version non publiée, aucun tag v1.0.0 posé (NO-GO qualité du 2026-10-10, voir OPE-308)

## v1.0.0 — brouillon (sans tag)

**Statut :** brouillon de contenu, non publié comme release. Les PR #87, #92, #93, #95, #96 et #98 sont indiquées mergées dans la revue code ; ces notes ne créent pas de tag `v1.0.0`.

### Ajouté

- Parcours v1 de connexion à un serveur et handshake, découverte et connexion par QR, gestion de sessions et chat en streaming.
- Permissions, questions/réponses et arrêt de génération ; sélection de modèles et d’agents.
- Accès aux fichiers en lecture seule et consultation des diffs.
- Protections d’accès local/biométrie optionnelle et effacement des données.
- Plan de recette v1 et améliorations de CI, de packaging et de publication d’un APK debug.

### Livré, validation appareil réel en attente

- Dictée (V1-10).
- Notifications (V1-13).

### Validation et portée

Le sign-off produit indique **11/13 parcours livrés** (V1-01 à V1-09, V1-11 et V1-12), **0 écarté**. Dictée et notifications ont une validation complète reportée, notamment sur appareil réel. Le sign-off sécurité est **GO avec réserves** : zéro défaut bloquant de code démontré, une condition opérationnelle P0 sur l’approbation/garde des PR de forks avant exécution sur runners persistants, et 13 réserves de sécurité. La revue code est **GO avec réserves** ; elle relève notamment l’absence de preuve publique vérifiée de CI verte sur certains changements et indique que ses contrôles étaient documentaires, pas un audit exhaustif des diffs/runs.

Ces validations ne valent pas preuve de recette manuelle PASS ni autorisation de release. La recette appareils réels (OPE-278) et les campagnes émulateur/simulateur (OPE-303, OPE-304) restent à examiner/consigner ; la recette manuelle n’a pas été jouée. La CI de `main` est rouge côté runner (hors code) : le runner doit être qualifié et une exécution exploitable verte obtenue avant release.
