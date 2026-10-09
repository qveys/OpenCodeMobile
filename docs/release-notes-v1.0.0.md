# Release notes OpenCode Mobile v1.0.0

> Brouillon — version non publiée, aucun tag v1.0.0 posé (NO-GO qualité du 2026-10-10, voir OPE-308)

OpenCode Mobile v1 apporte les parcours essentiels pour connecter l’application à un serveur OpenCode et travailler depuis mobile : connexion manuelle ou par QR, sessions et chat en streaming, gestion des permissions et des questions, arrêt de génération, sélection de modèles/agents, consultation de fichiers en lecture seule et des diffs.

La version comprend également des protections d’accès local et des fonctions d’effacement. Les changements de cette étape incluent les PR #87, #92, #93, #95, #96 et #98 (mergées selon le sign-off Code Reviewer), qui apportent notamment protections d’accès local et biométrie, durcissement CI, packaging/tests et documentation de recette.

## Livré, validation appareil réel en attente

- Dictée sur appareil avec confirmation avant envoi (V1-10).
- Notifications (V1-13).

Ces deux fonctions ne sont pas validées sur appareil réel.

## État de validation

L’acceptation produit est assortie de réserves. 11 parcours sur 13 sont indiqués livrés ; dictée (V1-10) et notifications (V1-13) attendent une validation complète sur appareils réels. La revue sécurité est GO avec réserves, dont une condition opérationnelle P0 à vérifier avant d’exécuter du code de PR fork sur un runner self-hosted persistant. La revue Code Reviewer est GO avec réserves et ne certifie pas une CI verte indépendante pour tous les changements. La recette manuelle n’a pas été jouée ; la CI de `main` est rouge côté runner (hors code). La recette émulateur/simulateur et appareils réels, ainsi que la qualification du runner rouge, ne sont pas déclarées réussies ici.

**Brouillon — pas une annonce de disponibilité.** Aucun tag `v1.0.0` n’est créé. La décision de publication reste soumise à la levée ou à l’acceptation formelle des réserves et aux critères de release.
