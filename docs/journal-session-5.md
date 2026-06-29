# Journal de session — Robot App (session 5)

**Date** : 29 juin 2026  
**Branche** : `claude/phone-app-execution-yccjll`  
**Modèle** : Claude Sonnet 4.6 (advisor : Claude Opus)  
**Appareil cible** : Pixel 9 (Android 15, ARCore Neural Depth)

---

## Résumé des travaux

Session centrée sur trois axes :
1. **Phase B.1 — Accumulation VIO persistante** : nouveau module `core-nav`, `VioMapAccumulator`, rendu du nuage stable.
2. **Phase B.1.5 — Qualité du nuage** : indexation par ID ARCore, pondération par confiance, éviction des points stagnants, snapshot trié anti-scintillement.
3. **Refactoring global "no hardcode"** : 7 objets `Config` créés, toutes les constantes nommées et centralisées.

---

## 1. Phase B.1 — VioMapAccumulator + StableMapRenderer

### Contexte

Le nuage Neural Depth (jaune, dense, bruité) a été désactivé. Les points VIO ARCore (épars, world-anchored, précis) sont désormais la source de vérité pour la carte structurelle.

### Nouveau module `core-nav`

Fichiers créés :
- `core-nav/build.gradle.kts`
- `core-nav/src/main/kotlin/com/robot/nav/VioMapAccumulator.kt`
- `core-nav/src/main/kotlin/com/robot/nav/VioMapConfig.kt`

### VioMapAccumulator — design final (après B.1.5)

- **Clé** : ID ARCore du feature point (`pc.ids` — stable pendant le tracking)
- **Position** : moyenne pondérée par la confiance ARCore (`pc.points[w]`)
- **Stable** : point avec `count ≥ VioMapConfig.MIN_OBSERVATIONS` (8)
- **Éviction** : points instables non vus depuis `STALE_FRAMES` (150 frames ≈ 5 s) supprimés toutes les `EVICT_INTERVAL_FRAMES` (60 frames)
- **Snapshot trié** : liste de points stables triée par count décroissant, reconstruite toutes les 60 frames → les 10 000 points les plus confirmés sont toujours affichés, pas un sous-ensemble aléatoire

Résolution du scintillement : le HashMap avait > 21 000 points stables mais le cap de rendu était 10 000. L'itération non-déterministe du HashMap sélectionnait des sous-ensembles différents à chaque frame → scintillement. Corrigé par le snapshot trié.

### StableMapRenderer

Renderer dédié (stride-4 : x,y,z,count) :
- **Couleur par hauteur** (uniforms `uFloorY`/`uCeilingY` depuis `FloorCeilingDetector`) :
  - vert = sol (`floorY`)
  - rouge = mi-hauteur
  - bleu = plafond (`ceilingY`)
  - Fallback : `RenderConfig.FALLBACK_FLOOR_Y_M` / `FALLBACK_CEILING_Y_M` tant que le détecteur n'a pas convergé
- **Taille par poids** : `3 px` à 8 obs → `12 px` à 60+ obs
- **Points circulaires** : discard GLSL `dot(coord,coord) > 0.25`

### Câblage

- `SlamViewModel` : `val vioAccumulator = VioMapAccumulator()` (pas de Hilt)
- `SlamRenderer` : constructeur reçoit `vioAccumulator`, lit `pc.ids` + confiance, appelle `vioAccumulator.update()` à chaque frame, log `total/stable` toutes les 90 frames
- `MainActivity` : bouton MAP (blanc actif) ; bouton PTS Neural Depth supprimé
- Ordre boutons : LOG | PLAN | MESH | VIO | MAP | SAVE

---

## 2. Phase B.1.5 — Qualité du nuage

### Diagnostic logcat

Ligne ajoutée toutes les 90 frames :
```
VIO map: total=21356 stable=21348 pts=154
```

**Observations clés sur le Pixel 9** :
- `total ≈ stable` → l'éviction stale fonctionne, quasiment pas de points instables accumulés
- `total` croît de ~10–20 par cycle → carte se consolide progressivement
- `total` peut décroître (21517 → 21483) → éviction confirme le nettoyage
- **21 000+ points stables** → le cap 10 000 était atteint, cause principale du scintillement (résolu par snapshot trié)
- TSDF mesh plafonne à 300 000 vertices → cap à investiguer

### Indexation par ID vs. voxel

Passage de clé-voxel (5 cm) à clé-ID ARCore :
- Élimine les doublons créés par la variance Z en orbite autour d'un objet
- La même feature physique est raffinée à travers toutes les frames quelle que soit l'oscillation de position
- Limitation : les IDs se réinitialisent lors d'une perte de tracking → les anciens points orphelins restent, les nouvelles observations partent sur de nouveaux IDs (acceptable pour B.1.5)

### Objets mobiles — analyse (Opus consulté)

L'advisor confirme que les fast-moving objects ne deviennent jamais stables (éviction stale les nettoie). Pour les slow-moving objects (chaise glissée, humain qui bouge) : la détection par variance par point est la bonne approche, mais nécessite :
- Un garde contre les loop-closures ARCore (dérive globale ≠ mouvement individuel)
- Démote plutôt que delete immédiat pour les points à fort count
- Seuil calibré sur les données réelles (pas 15 cm assumés)
→ **Reporté à une session future** après collecte de données sur le Pixel 9.

---

## 3. Refactoring "no hardcode"

### Nouvelle règle CLAUDE.md

> **Jamais** hardcoder quoi que ce soit — aucun nombre magique, aucun paramètre de fallback inline, aucun chemin en dur. Toute valeur numérique nommée va dans un objet `Config` du module concerné. Les shaders GLSL reçoivent leurs constantes via interpolation de string depuis ces objets.

### Objets Config créés

| Objet | Module | Contenu principal |
|-------|--------|------------------|
| `VioMapConfig` | `core-nav` | MIN_OBSERVATIONS, MAX_STABLE_POINTS, STALE_FRAMES, EVICT_INTERVAL_FRAMES, INITIAL_MAP_CAPACITY |
| `RenderConfig` | `core-render` | Plan view camera, stable map colors/sizes, VIO cloud params, mesh tints, grid geometry, detector params, SlamRenderer misc |
| `SlamConfig` | `core-slam` | PROJ_NEAR_M, PROJ_FAR_M (évite dépendance circulaire avec core-render) |
| `TsdfConfig` | `core-tsdf` | VOXEL_SIZE_M, TRUNCATION_M, MESH_EXTRACT_INTERVAL, PLY_CHUNK/STREAM_BUFFER_BYTES |
| `DepthConfig` | `core-depth` | MIN_CONFIDENCE (seuil DEPTH16 bits[2:0]) |
| `NetConfig` | `core-net` | ESP32_IP, ESP32_PORT, WS_RETRY_DELAY, SAFETY thresholds |
| `AppConfig` | `app` | MAX_LOG_LINES, bouton couleurs, marges, paddings |

### Changements majeurs

- `FloorCeilingRenderer` : companion object supprimé → `RenderConfig` (HALF_EXTENT, STEP, LINES_PER_AXIS calculé dynamiquement, tints sol/plafond)
- `FloorCeilingDetector` : companion object supprimé → `RenderConfig` (Y_MIN/MAX, BIN_SIZE, MIN_POINTS, marges, EMA_ALPHA, PIXEL_STRIDE)
- `MeshRenderer` : constantes plan-view dupliquées avec `SlamRenderer` → `RenderConfig` (source unique)
- GLSL shaders : `#define` et `${RenderConfig.*}` via string interpolation Kotlin
- `AppModule.kt` : IP ESP32 hardcodée → `NetConfig.ESP32_IP`

---

## 4. TODO mis à jour

| Priorité | Tâche | Statut |
|----------|-------|--------|
| Haute | Tester B.1.5 sur Pixel 9 — vérifier stable count et absence de scintillement | En attente |
| Haute | Investiguer TSDF mesh plafonné à 300 000 vertices | À faire |
| Moyenne | **[Phase B.2]** Reconstruction de surface depuis la carte VIO accumulée | À planifier |
| Moyenne | Vue PLAN : zoom/pan + indicateur position courante | À faire |
| Basse | Détection objets mobiles lents (variance par point, garde dérive ARCore) | À faire (données d'abord) |
| Basse | Persistance VioMapAccumulator entre sessions | À faire |
| Basse | `core-net` WebSocket ESP32 + `SafetyState` IR | À faire |
