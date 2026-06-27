# Architecture — Comprendre le pipeline

Ce document explique les choix techniques et comment les pièces s'assemblent.  
Lis-le pour comprendre *pourquoi*, pas pour savoir *comment faire*.

---

## Pourquoi ARCore sans LiDAR ?

Le Pixel 9 n'a pas de LiDAR. ARCore compense avec deux sources :

- **Visual-Inertial Odometry (VIO)** : fusion caméra RGB + IMU → pose 6-DoF précise à ~1 cm
- **Raw Depth API** : depth map dense reconstruite par stéréo et ML, résolution ~320×240

Cette combinaison suffit pour cartographier l'intérieur d'une maison à quelques centimètres près.  
La Raw Depth API donne des valeurs `uint16_t` en millimètres, `0` = pas de donnée.

---

## Le pipeline de threading

Trois threads coexistent sans mutex explicite :

```
┌─────────────────────────────────────────┐
│  GL Thread (Android GLSurfaceView)      │
│  • session.update() → Frame             │
│  • Extrait Pose + depth map             │
│  • Construit FrameData (immutable)      │
│  • frameChannel.trySend(frameData)      │  ← CONFLATED : perd l'ancienne si busy
│  • Lit @Volatile pendingMesh            │
│  • Rend fond caméra + mesh overlay      │
└──────────────┬──────────────────────────┘
               │ Channel(CONFLATED)
               ▼
┌─────────────────────────────────────────┐
│  TSDF Dispatcher (Dispatchers.Default)  │
│  • nativeIntegrate() → C++ JNI          │
│  • Toutes les 30 frames :               │
│    nativeExtractMesh() → FloatArray     │
│    → MeshSnapshot (direct ByteBuffers)  │
│    → StateFlow<MeshSnapshot>            │
└──────────────┬──────────────────────────┘
               │ StateFlow
               ▼
┌─────────────────────────────────────────┐
│  GL Thread (collecte dans SlamRenderer) │
│  • @Volatile pendingMesh mis à jour     │
│  • uploadMesh() sur GL thread           │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│  Net Thread (Dispatchers.IO)            │
│  • WebSocket OkHttp → ESP32             │
│  • IrReading → SharedFlow              │
│  • VerticalSafetyMonitor → StateFlow    │
│  • N'interagit jamais avec la TSDF      │
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
onDestroy → SlamViewModel.onCleared()   → tsdfVolume.close()
                                          frameChannel.close()
                                          processLoop() finally → nativeDestroy(handle)
```

`nativeDestroy` n'est jamais appelé depuis le main thread : il est dans le `finally` de `processLoop` qui tourne sur `Dispatchers.Default`. Cela garantit qu'il s'exécute après le dernier `nativeIntegrate`, évitant le use-after-free.
