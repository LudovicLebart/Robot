# Architecture — Comprendre le pipeline

Ce document explique les choix techniques et comment les pièces s'assemblent.  
Lis-le pour comprendre *pourquoi*, pas pour savoir *comment faire*.

---

## Rôle d'ARCore dans le pipeline

Le Pixel 9 n'a pas de LiDAR. ARCore fournit deux sources complémentaires :

- **Visual-Inertial Odometry (VIO)** : fusion caméra RGB + IMU → pose 6-DoF précise à ~1 cm. C'est la source de localisation principale du robot.
- **Raw Depth API** : depth map dense (~320×240) par stéréo + ML, utilisée pour détecter le sol/plafond (`FloorCeilingDetector`) et les sécurités verticales.

Le **LiDAR 2D** embarqué sur la base du robot (géré par l'ESP32) est la source de géométrie d'obstacles, pas ARCore. ARCore donne la pose ; le LiDAR donne la carte.

### Pourquoi le nuage VIO ne suffit pas pour reconstruire des surfaces

Le nuage VIO (~10 000 features visuels stables) est fondamentalement inadapté pour générer un maillage de l'environnement :

- **Densité insuffisante** : ~10 000 points sur une pièce de 5×5×3 m ≈ 1 point tous les 8 cm². Un maillage exploitable en a besoin d'au moins 10× plus.
- **Pas de normales** : les features ARCore sont des points 3D sans orientation de surface. Les algorithmes de maillage (Poisson, Ball Pivoting) en ont besoin.
- **Couverture non uniforme** : les murs lisses/uniformes ont 0 point VIO (pas de texture à tracker). Résultat : trous énormes sur les murs, et des triangles géants traversant l'air entre surfaces éloignées.

Le LiDAR 2D + voxel sweeping est la bonne source pour la géométrie navigable.

---

## Le pipeline de threading

Trois threads coexistent sans mutex explicite :

```
┌─────────────────────────────────────────┐
│  GL Thread (Android GLSurfaceView)      │
│  • session.update() → Frame             │
│  • Extrait Pose + depth map             │
│  • Accumule nuage VIO stable            │
│  • snapshotChannel.trySend(pts)         │  ← CONFLATED : perd l'ancienne si busy
│  • Lit @Volatile pendingPlanes          │
│  • Rend fond caméra + nuage + grilles   │
└──────────────┬──────────────────────────┘
               │ Channel(CONFLATED)
               ▼
┌─────────────────────────────────────────┐
│  Plane Dispatcher (Dispatchers.Default) │
│  • PlaneExtractor.extract() RANSAC      │
│  • PlaneStabilizer.update() EMA         │
│  • planesFlow (StateFlow)               │
└──────────────┬──────────────────────────┘
               │ StateFlow
               ▼
┌─────────────────────────────────────────┐
│  Main Thread (MainActivity)             │
│  • planesFlow.collect → pendingPlanes   │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│  Net Thread (Dispatchers.IO)            │
│  • WebSocket OkHttp → ESP32             │
│  • IrReading → SharedFlow              │
│  • VerticalSafetyMonitor → StateFlow    │
└─────────────────────────────────────────┘
```

### Pourquoi `Channel(CONFLATED)` ?

Si le thread TSDF est occupé à intégrer une frame, la frame suivante du GL thread la remplace plutôt que de s'accumuler. C'est délibéré : mieux vaut sauter une frame que de créer un backlog de frames à intégrer qui grossirait en mémoire et introduirait du lag.

### Pourquoi `@Volatile` sur `pendingMesh` et `session` ?

Deux threads distincts lisent/écrivent ces champs. `@Volatile` garantit la visibilité entre threads sans verrou. Le GL thread écrit `pendingMesh`, le TSDF dispatcher le lit. Le main thread écrit `session`, le GL thread le lit.

---

## La grille TSDF

### Principe

Chaque voxel stocke une valeur **Truncated Signed Distance Function** :
- `tsdf = +1.0` → loin devant une surface
- `tsdf = 0.0` → sur la surface
- `tsdf = -1.0` → derrière une surface (dans l'objet)

On intègre plusieurs frames pour moyenner le bruit de la depth map.  
Le mesh est extrait aux zéro-croisements via **Marching Cubes**.

### Pourquoi dense et non voxel hashing ?

Un volume dense de 300×150×300 à 2 cm = 108 MB RAM — acceptable sur le Pixel 9 (12 GB).  
L'implémentation dense est plus simple et cache-friendly pour le C++.  
Le passage au voxel hashing est prévu si la carte doit couvrir plusieurs pièces : l'API Kotlin (`TsdfVolume`) resterait identique, seule l'implémentation C++ changerait.

### Éviter l'overflow `idx()`

La grille 300×150×300 contient 13 500 000 voxels. Calculer `x + sizeX * (y + sizeY * z)` en `int` déborde pour les valeurs maximales (300 + 300 * (150 + 150 * 300) = ~13.5M, OK — mais le calcul intermédiaire `sizeY * z = 150 * 300 = 45 000`, puis `sizeX * 45 000 = 300 * 45 000 = 13.5M`). En int 32-bit ça passe juste, mais des grilles plus grandes débordeent. On utilise donc `size_t` pour sécuriser.

---

## Pourquoi JNI plutôt que Kotlin pur ?

Marching Cubes sur 13.5M voxels à 30 fps est hors de portée du JVM. En C++17 avec `-O3` sur le A52 du Pixel 9, une intégration complète prend ~20-40 ms. En Kotlin/JVM ce serait 10-20× plus lent.

Le JNI est réduit au minimum : un `handle: Long` opaque côté Kotlin, trois fonctions (`nativeIntegrate`, `nativeExtractMesh`, `nativeDestroy`). Le Kotlin ne touche jamais la mémoire native directement.

---

## Pourquoi la sécurité IR est séparée de la TSDF ?

Les capteurs IR mesurent des distances verticales ponctuelles (une direction unique). Les intégrer dans la TSDF n'apporterait rien : la depth map ARCore couvre déjà tout le champ de vue. Les IR servent uniquement à déclencher des alertes de sécurité immédiates (`SafetyState`) — un arrêt d'urgence si le robot approche d'un escalier ou d'une table basse.

Cette séparation permet aussi de faire tourner la sécurité même si ARCore a perdu son tracking.

---

## Gestion du cycle de vie Android

Le cycle de vie ARCore est couplé au cycle de vie de l'Activity :

```
onResume  → ArSessionManager.resume()   → session.resume()
onPause   → ArSessionManager.pause()    → session.pause()
onDestroy → SlamViewModel.onCleared()   → snapshotChannel.close()
                                          esp32Client.close()
                                          sessionManager.close()
```
