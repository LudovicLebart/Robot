# Journal de session — Robot App (session 6)

**Date** : 29 juin 2026  
**Branche** : `claude/phone-app-execution-yccjll`  
**Modèle** : Claude Sonnet 4.6 (advisor : Claude Opus)  
**Appareil cible** : Pixel 9 (Android 15, ARCore Neural Depth)

---

## Résumé des travaux

Session centrée sur cinq axes :
1. **Détection d'objets mobiles** : nettoyage des slow-movers dans `VioMapAccumulator`
2. **Phase B.2 — Plans RANSAC** : implémentée puis retirée après analyse architecturale
3. **Analyse architecturale** : clarification du rôle du LiDAR 2D vs VIO vs TSDF pour la navigation
4. **Vue PLAN améliorée** : pan/zoom tactile + marqueur robot jaune + correction aspect ratio
5. **Sol stable** : estimation via hauteur caméra (-1.40 m) au lieu du détecteur depth

---

## 1. Détection d'objets mobiles lents (VioMapAccumulator)

### Problème

Les objets déplacés lentement (chaise glissée, humain qui s'arrête puis bouge) devenaient stables dans le HashMap et n'en sortaient jamais, polluant la carte structurelle.

### Solution : two-pass update avec garde de dérive

**Algorithme** :
- Pass 1 : pour chaque point stable (`count ≥ MIN_OBSERVATIONS`) visible dans la frame courante, calculer sa distance à sa position EMA. Si `dist > MOVE_THRESHOLD_M` (15 cm), ajouter son ID à `jumperIds`.
- Garde de dérive : si `jumperIds.size / stableObserved > DRIFT_GUARD_FRACTION` (30 %), c'est une correction globale ARCore (loop-closure), pas des mouvements individuels → ignorer `jumperIds`.
- Pass 2 : pour chaque ID dans `jumperIds` (non-dérive), reset `count=1` (démote, ne supprime pas).

**Constantes ajoutées à `VioMapConfig`** :
- `MOVE_THRESHOLD_M = 0.15f`
- `DRIFT_GUARD_FRACTION = 0.30f`
- `DRIFT_GUARD_MIN_STABLE = 5`

**Optimisations** : `jumperIds` et `moveThresh2` déplacés en champs de classe (évite allocation par frame).

**Diagnostique** : `demotionCount()` exposé, loggé comme `demoted/cycle=N`.

---

## 2. Phase B.2 — Plans RANSAC (implémentée puis retirée)

### Implémentation

Fichiers créés :
- `core-nav/PlaneConfig.kt` — paramètres RANSAC + stabilisateur
- `core-nav/PlaneExtractor.kt` — extraction itérative de plans dominants (RANSAC)
- `core-nav/PlaneStabilizer.kt` — EMA temporel + gate confirmation + expiration
- `core-render/PlaneRenderer.kt` — quads semi-transparents stride-6 (x,y,z,r,g,b)

Pipeline :
```
SlamRenderer (GL thread) → snapshotChannel (CONFLATED) → planeExtractionLoop (Dispatchers.Default)
→ PlaneExtractor.extract() → PlaneStabilizer.update() → planesFlow → MainActivity → renderer.pendingPlanes
```

### Problèmes rencontrés

- `INLIER_DISTANCE_M = 5 cm` au niveau du bruit VIO → RANSAC fitait des clusters bruit
- Pas de persistance temporelle → plans changeaient à chaque run
- Corrigé par `INLIER_DISTANCE_M = 12 cm`, `MIN_INLIERS = 30`, `PlaneStabilizer` avec EMA

### Décision d'architecture : retrait

Après analyse :
- Le nuage VIO est **trop épars** pour détecter les murs (0 point sur surfaces lisses)
- 8 plans ne suffisent pas pour représenter un environnement complexe (pièce en L, portes, chaises)
- Les plans ne peuvent représenter ni le volume d'un fauteuil, ni une porte, ni un alcôve
- Le LiDAR 2D (ESP32) est la bonne source pour la géométrie d'obstacles

**Suppression complète** : `PlaneConfig`, `PlaneExtractor`, `PlaneStabilizer`, `PlaneRenderer`, `MeshRenderer` (code mort depuis retrait TSDF), toutes les constantes PLANE_* et MESH_* dans `RenderConfig`.

---

## 3. Analyse architecturale — Documentation mise à jour

### Clarifications dans `platform.md` et `explanation.md`

- **ARCore VIO** : source de localisation 6-DoF (pose), pas de géométrie de pièce
- **LiDAR 2D (ESP32)** : source de géométrie d'obstacles (tranche horizontale) → A*
- **Fusion (Sweeping)** : projection des lignes LiDAR dans l'espace 3D via pose ARCore → grille voxels
- **YOLO** : classification sémantique (mur réel vs fauteuil vs humain) → nettoyage carte 2D statique
- **Limite VIO pour maillage** : trous énormes sur murs lisses, triangles géants entre surfaces éloignées → documenté dans `explanation.md`

### TSDF

Retiré des dépendances lors de la session précédente (remplacé par plans). Les plans ayant été retirés, l'app ne fait plus de reconstruction de surface. La vue plan montre uniquement le nuage VIO stable + grilles sol/plafond + marqueur robot.

---

## 4. Vue PLAN améliorée

### Pan/zoom tactile (SlamGLSurfaceView)

- `ScaleGestureDetector` : zoom pinch → `planScale ∈ [0.25, 8.0]`
- `GestureDetector.onScroll` : drag → `planPanX`, `planPanZ`
- Actif uniquement quand `planViewEnabled == true`
- Pan calibré sur `worldPerPx = 2h / height` (après correction aspect ratio)

### Marqueur robot (RobotMarkerRenderer)

- Triangle plein jaune (30 cm en monde réel) à la position courante du téléphone
- Flèche pointant dans la direction de déplacement (vecteur forward horizontal extrait de `c2w` : `-col2`)
- Calcul de la rotation sans `atan2` : rotation directe via `cosA = -fwdZ/len`, `sinA = fwdX/len`
- Rendu : `GL_TRIANGLES`, shader uniforme, `glDisable(CULL_FACE)`, juste au-dessus de la grille sol

### Correction aspect ratio (bug)

La matrice ortho utilisait `[-h, +h, -h, +h]` (carré) sur écran portrait 1080×2340.
Résultat : 1 m en Z = 2.17× plus de pixels qu'1 m en X → distorsion majeure de la vue plan.

**Correction** :
```kotlin
val hZ = PLAN_VIEW_ORTHO_HALF_EXTENT_M / planScale
val hX = hZ * viewportW / viewportH   // ≈ 0.46 × hZ sur portrait
orthoM(proj, 0, -hX, hX, -hZ, hZ, near, far)
```

`viewportW`/`viewportH` stockés dans `onSurfaceChanged`.

---

## 5. Sol stable depuis hauteur caméra

### Problème

`FloorCeilingDetector` (histogramme sur depth map) produisait des sauts de position du sol, affectant la grille sol, le StableMapRenderer (couleurs) et le marqueur robot.

### Solution

```kotlin
private val computedFloorY: Float
    get() = if (hasValidPose) lastKnownPos[1] - RenderConfig.PHONE_HAND_HEIGHT_M
            else floorCeilingDetector.floorY ?: RenderConfig.FALLBACK_FLOOR_Y_M
```

- `PHONE_HAND_HEIGHT_M = 1.40f` dans `RenderConfig`
- `hasValidPose` mis à `true` dès la première frame trackée
- Le plafond vient encore du détecteur depth (pas d'estimation directe disponible)
- Log : `floor(cam)=-0.40m ceiling(det)=2.10m`

---

## 6. TODO mis à jour

| Priorité | Tâche | Statut |
|----------|-------|--------|
| Haute | Trajectoire caméra dans la vue PLAN (montrer le chemin parcouru) | À faire |
| Haute | Intégration LiDAR 2D ESP32 → carte 2D occupancy | À planifier |
| Moyenne | Vue PLAN : centrage auto sur le robot au démarrage | À faire |
| Moyenne | Persistance VioMapAccumulator entre sessions | À faire |
| Basse | Détection objets mobiles lents — calibrage seuils sur données réelles | À faire |
| Basse | `core-net` WebSocket ESP32 + `SafetyState` IR | À faire |
| ~~Haute~~ | ~~Phase B.2 — Plans RANSAC~~ | **Retiré** (pas pertinent) |
| ~~Haute~~ | ~~TSDF mesh + reconstruction surface~~ | **Retiré** (LiDAR 2D = bonne source) |
| ~~Moyenne~~ | ~~Vue PLAN : zoom/pan + indicateur position~~ | **FAIT** |
| ~~Haute~~ | ~~Sol stable~~ | **FAIT** (cameraY - 1.40 m) |
