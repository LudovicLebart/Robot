# Référence technique

Descriptions exhaustives des modules, types et paramètres. Pour les explications de *pourquoi*, voir [Architecture](explanation.md).

---

## Structure des modules

```
Robot/
├── app/                    UI, lifecycle, câblage ViewModel + Hilt
├── core-common/            Types partagés entre tous les modules
├── core-slam/              Session ARCore, extraction pose 6-DoF
├── core-depth/             ARCore Depth API, extraction depth map + FloorCeilingDetector
├── core-nav/               VioMapAccumulator, VioMapConfig — carte structurelle VIO
├── core-render/            OpenGL ES 3.0 : fond caméra, nuage stable, grilles, marqueur robot
└── core-net/               Client WebSocket OkHttp + sécurité IR
```

> **Note** : `core-tsdf` (TSDF + Marching Cubes C++) a été retiré en session 6. La reconstruction de surface sera assurée par le LiDAR 2D de l'ESP32 (voir `platform.md`).

---

## core-common

### `FrameData`

Snapshot immutable transmis du GL thread au dispatcher TSDF via `Channel(CONFLATED)`.

| Champ | Type | Description |
|---|---|---|
| `depthValues` | `ShortArray` | Depth map brute ARCore (uint16_t en mm, 0 = pas de donnée) |
| `depthWidth` | `Int` | Largeur de la depth map en pixels |
| `depthHeight` | `Int` | Hauteur de la depth map en pixels |
| `fx`, `fy` | `Float` | Focales de la caméra de profondeur (pixels) |
| `cx`, `cy` | `Float` | Centre optique de la caméra de profondeur (pixels) |
| `cameraToWorld` | `FloatArray(16)` | Matrice 4×4 colonne-major camera→monde |
| `timestampNs` | `Long` | Timestamp de la frame en nanosecondes |

> La matrice `cameraToWorld` suit la convention ARCore : axe Z vers l'arrière, Y vers le haut.

### `MeshSnapshot`

Résultat de Marching Cubes, prêt à uploader en VBO OpenGL.

| Champ | Type | Description |
|---|---|---|
| `vertices` | `FloatBuffer` | Positions XYZ des sommets (direct ByteBuffer, natif endianness) |
| `normals` | `FloatBuffer` | Normales XYZ par sommet (même layout) |
| `vertexCount` | `Int` | Nombre de triangles × 3 (non-indexé) |

### `SafetyState`

État retourné par `VerticalSafetyMonitor`.

| Valeur | Déclencheur |
|---|---|
| `Ok` | Distances IR dans les seuils normaux |
| `VoidDetectedDown(distanceMm: Int)` | IR bas > `VOID_THRESHOLD_MM` (300 mm par défaut) |
| `ObstacleUp(distanceMm: Int)` | IR haut < `OBSTACLE_THRESHOLD_MM` (200 mm par défaut) |

### `SessionState`

| Valeur | Signification |
|---|---|
| `Initializing` | Session ARCore pas encore démarrée |
| `Tracking` | Pose 6-DoF active |
| `Paused` | Activity en pause |
| `Failed(reason: String)` | Erreur ARCore irrémédiable |

---

## core-slam

### `ArSessionManager`

| Méthode | Thread | Description |
|---|---|---|
| `resume()` | Main | Crée/reprend la session ARCore. Configure `RAW_DEPTH_ONLY` si supporté. |
| `pause()` | Main | Met la session en pause. |
| `close()` | Main | Détruit la session. |
| `setCameraTextureName(id: Int)` | GL | Donne le nom de texture OES pour le fond caméra. |
| `update(): Frame?` | GL | Appelle `session.update()`. Retourne `null` si la session n'est pas prête ou si une exception est levée. |
| `isDepthSupported(): Boolean` | Any | `true` si le device supporte `RAW_DEPTH_ONLY`. |

---

## core-depth

### `DepthFrameProvider`

| Méthode | Description |
|---|---|
| `extract(frame: Frame): FrameData?` | Acquiert `acquireRawDepthImage16Bits()`, copie les valeurs dans un `ShortArray`, scale les intrinsèques de la texture à la résolution depth. Retourne `null` si la depth n'est pas disponible. Ferme toujours l'image via `use {}`. |

---

## core-tsdf

### `TsdfVolume` (Kotlin)

| Méthode | Thread | Description |
|---|---|---|
| `frameChannel` | — | `Channel<FrameData>(CONFLATED)` — entrée du pipeline |
| `mesh: StateFlow<MeshSnapshot>` | — | Dernier mesh extrait |
| `processLoop()` | TSDF dispatcher | Boucle `for (frame in frameChannel)` → intègre + extrait. Appelle `nativeDestroy` dans le `finally`. |
| `reset()` | Any | Vide le volume TSDF. |
| `close()` | Main | Ferme `frameChannel`. `nativeDestroy` est appelé par `processLoop.finally`. |

**Paramètres du constructeur :**

| Paramètre | Défaut | Description |
|---|---|---|
| `sizeX`, `sizeY`, `sizeZ` | 300, 150, 300 | **Ignorés depuis session 4** — voxel hashing, carte illimitée |
| `voxelSizeM` | 0.02 | Taille d'un voxel en mètres |
| `truncationM` | 0.08 | Troncature TSDF en mètres (= 4 voxels pour ARCore Neural Depth) |

**Architecture C++ interne (depuis session 4 — voxel hashing) :**

- `std::unordered_map<BlockKey, TsdfBlock*>` — blocs 8×8×8 alloués à la demande
- Intégration **pixel-driven** : pour chaque pixel depth, marche le long du rayon dans la bande ±truncation (stepSize = voxelSize/2)
- Depth range : [0.3 m, **8.0 m**] (relevé depuis 3 m en session 4)
- `extractMesh()` : re-marche uniquement les blocs `dirty`, agrège le cache par bloc
- Éviction : blocs à >12 m + âge >300 frames, toutes les 60 frames

### Fonctions JNI C++

| Fonction JNI (Kotlin) | Signature C++ | Description |
|---|---|---|
| `nativeCreate` | `TsdfVolume*(int,int,int,float,float)` | Alloue le volume sur le heap natif. `sx/sy/sz` ignorés (voxel hashing). |
| `nativeIntegrate` | `void integrate(const uint16_t*, int, int, float×4, float×16)` | Intègre une depth map par ray marching pixel-driven. |
| `nativeExtractMesh` | `FloatArray?` interleaved `[x,y,z,nx,ny,nz,…]` | Re-marche les blocs dirty, retourne `null` si aucun triangle. |
| `nativeReset` | `void reset()` | Supprime tous les blocs et vide le cache mesh. |
| `nativeDestroy` | `delete vol` | Libère la mémoire native. Appelé uniquement depuis `processLoop.finally`. |

---

## core-render

### `SlamRenderer : GLSurfaceView.Renderer`

| Méthode | Description |
|---|---|
| `onSurfaceCreated` | Crée la texture OES caméra, initialise `BackgroundRenderer` et `MeshRenderer`. |
| `onSurfaceChanged` | Met à jour les dimensions de viewport, recalcule la matrice de projection. |
| `onDrawFrame` | Appelle `session.update()`, extrait `FrameData`, l'envoie au channel. Consomme `pendingMesh`. Rend le fond caméra puis le mesh. |

### `ShaderUtil`

| Méthode | Description |
|---|---|
| `createProgram(vert, frag): Int` | Compile et link. Lève une `IllegalStateException` avec le log GL si la compilation ou le link échoue. |

### `MeshRenderer`

| Méthode | Description |
|---|---|
| `init()` | Compile le programme, génère VAO + 2 VBO, enregistre les locations (une seule fois). |
| `uploadMesh(snapshot)` | Upload les positions et normales dans les VBO. Appeler sur GL thread. |
| `draw(view, proj)` | Bind le VAO, envoie les matrices, appelle `glDrawArrays`. No-op si `vertexCount == 0`. |

---

## core-net

### `WebSocketEsp32Client`

Protocole attendu de l'ESP32 — trames JSON :
```json
{"up": 1234, "down": 87}
```
- `up` : distance IR capteur haut en mm
- `down` : distance IR capteur bas en mm
- Valeur `0` → pas de lecture valide (ignorée)

Reconnexion automatique avec backoff exponentiel (1s → 2s → 4s → … → 30s max).

### `VerticalSafetyMonitor`

```kotlin
suspend fun observe(readings: SharedFlow<IrReading>)
val safetyState: StateFlow<SafetyState>
```

Consomme `IrReading` et met à jour `safetyState`. Indépendant de la TSDF.

---

## Variables d'environnement CI

Aucune variable secrète n'est requise pour un build debug.  
Un build release signé nécessiterait d'ajouter dans les secrets GitHub :

| Secret | Description |
|---|---|
| `KEYSTORE_BASE64` | Keystore encodé en base64 |
| `KEY_ALIAS` | Alias de la clé dans le keystore |
| `KEY_PASSWORD` | Mot de passe de la clé |
| `STORE_PASSWORD` | Mot de passe du keystore |

---

## Dépendances clés

| Librairie | Version | Usage |
|---|---|---|
| `com.google.ar:core` | 1.46.0 | ARCore SLAM + Raw Depth API |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | Channel, StateFlow, Dispatchers |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | WebSocket ESP32 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | Parser trames IR JSON |
| `com.google.dagger:hilt-android` | 2.52 | DI scopes/lifecycle |
| NDK C++17 `arm64-v8a` `-O3` | NDK 27 | TSDF + Marching Cubes natifs |
| AGP | 8.9.2 | Build Android |
| Kotlin | 2.1.21 | Langage |
| Gradle | 8.14.3 | Build system |
