# Rollback v1

Déclencher un rollback si un défaut confirmé empêche un parcours critique, provoque des plantages/pertes de données, ou si les smoke tests échouent après le changement. Noter le SHA fautif, le symptôme, l’environnement et l’heure. Ne pas déployer ni modifier la production dans cette procédure.

### Réinstaller l’application debug précédente (Android/iOS)
1. Identifier le SHA précédent connu comme sain (`<sha>`), généralement le parent du commit fautif sur `main`.
2. Dans GitHub Actions, ouvrir le run **Build** de `main` pour ce SHA et télécharger l’artefact correspondant : `android-debug-<sha>` et/ou `iosApp-simulator-<sha>`. Les artefacts sont conservés 14 jours. S’ils ont expiré ou si le run manque, ne pas reconstruire depuis un SHA différent : demander un nouveau build explicite de ce SHA.
3. Installer l’APK de l’artefact Android sur l’appareil de test, ou le build simulateur iOS dans le simulateur. Confirmer l’identifiant de build/SHA installé et relancer les scénarios concernés.

### Annuler le changement sur `main`
1. Ouvrir une PR qui inverse le squash-merge fautif : utiliser **Revert** sur la PR fusionnée, ou produire le revert du commit squash et le soumettre dans une nouvelle branche/PR. Vérifier que le diff ne supprime que le changement ciblé.
2. Respecter le workflow PR : CI et contrôles requis verts, branche à jour, discussions résolues, approbation requise (et approbation propriétaire selon `docs/MERGE-PATH.md`). Historique linéaire, commits signés (`git-signed-commit`), aucun commit direct sur `main`, aucun force-push.
3. Après fusion autorisée de la PR de revert, relever son nouveau SHA sur `main`; vérifier qu’il contient le revert attendu. Ne pas utiliser un reset ni réécrire l’historique.

### Tag `v1.0.0` — immuable
Ne jamais déplacer, supprimer ou recréer `v1.0.0` sans accord explicite de [@qveys](https://github.com/qveys). Le rollback de `main` ne modifie pas le tag. Si le tag semble incorrect, arrêter et obtenir cet accord avant toute action sur le tag.

### Vérification après retour
- Refaire le test du parcours qui a déclenché le rollback, puis les smoke tests pertinents; confirmer version/SHA servis et vérifier qu’il n’y a pas de régression évidente dans les journaux et contrôles de santé.
- Pour le debug, confirmer l’installation du bon artefact/SHA et le fonctionnement sur appareil/simulateur. Pour `main`, confirmer le revert fusionné et que les checks post-merge sont verts.
- Consigner résultat, SHA/PR/run/artefact et anomalies. Si le défaut persiste, garder l’incident ouvert et préparer un correctif par PR; ne pas répéter des changements risqués sans diagnostic.

