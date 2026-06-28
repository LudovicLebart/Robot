# Journal de session — Robot App (session 4)

**Date** : 28 juin 2026  
**Branche** : `claude/phone-app-execution-yccjll`  
**Appareil cible** : Pixel 9 (Android 15, ARCore Neural Depth)

---

## Résumé des travaux

Cette session a implémenté la **Phase A** du plan d'architecture de la carte :
remplacement de la grille TSDF dense et fixe par du **voxel hashing illimité**.

---

## 1. Contexte et motivation

### Problème identifié en session 3

La grille dense 300×150×300 à 2 cm couvrait un volume fixe de 6 m × 3 m × 6 m centré
sur l'origine mondiale. Dès que le robot s'éloignait de ce volume, les voxels projetés
tombaient hors grille, zéro TSDF était mis à jour, et le mesh cessait de s'enrichir.

Symptômes logcat observés :
- `pos=(X/Y/5.2)m` → robot à Z=5.2 m, hors de la grille (bornée à Z=+3 m)
- vertices oscillant entre 400 k et 870 k sans cohérence spatiale
- triangles "flottants" dans des zones jamais vues par la caméra

### Solution retenue (plan Opus)

**Voxel hashing** : `std::unordered_map<BlockKey, TsdfBlock*>` avec blocs 8×8×8.  
La carte grandit à la demande, se nettoie automatiquement (éviction).  
L'API Kotlin/JNI reste identique — seule l'implémentation C++ change.

---

## 2. Fichiers créés

### `core-tsdf/src/main/cpp/block_hash.h` (nouveau)

```cpp
struct BlockKey { int32_t x, y, z; bool operator==(…) const noexcept; };
struct BlockKeyHash { size_t operator()(…) const noexcept; };  // Teschner hash
```

Hash spatial de Teschner : `(x*73856093u) ^ (y*19349663u) ^ (z*83492791u)`.  
Distribution uniforme, pas de dépendance externe.

### `core-tsdf/src/main/cpp/tsdf_block.h` (nouveau)

```cpp
static constexpr int BLOCK_SIZE   = 8;
static constexpr int BLOCK_VOXELS = 512;

struct TsdfVoxel { float tsdf=1.0f; float weight=0.0f; };

struct TsdfBlock {
    TsdfVoxel voxels[512];
    bool dirty = true;
    int  lastTouchedFrame = 0;
    TsdfVoxel& at(int lx, int ly, int lz) noexcept;
};
```

4 Ko par bloc. Le flag `dirty` déclenche le re-marching Marching Cubes.

---

## 3. Fichiers modifiés

### `core-tsdf/src/main/cpp/tsdf_volume.h`

Interface simplifiée :
- `interpolate(x,y,z)` → **`tsdfAt(gx,gy,gz)`** (coordonnées globales de voxel)
- `sizeX/Y/Z()`, `originX/Y/Z()` supprimés (plus de grille bornée)
- `voxelSize()` conservé (utilisé par Marching Cubes)
- Constructeur conserve `(int sx, int sy, int sz, float vs, float trunc)` pour compatibilité JNI — `sx/sy/sz` ignorés

### `core-tsdf/src/main/cpp/tsdf_volume.cpp`

**Intégration pixel-driven** (remplace le triple for-loop sur les voxels) :

```
Pour chaque pixel (iu, iv) avec depth > 0 et dans [0.3, 8.0] m :
  rayCamX = (iu - cx) / fx
  rayCamY = -(iv - cy) / fy   ← Y-flip ARCore conservé
  Pour t = depthM±truncation_, pas = voxelSize/2 :
    point_monde = c2w × (rayCam * t)
    gx = floor(wx / voxelSize)   ← std::floor obligatoire (coords négatives)
    bk = { gx>>3, gy>>3, gz>>3 }
    tsdf = clamp((depthM - t) / truncation, -1, 1)
    mise à jour pondérée du voxel
    si lx==0 → marquer (bx-1) dirty   ← dirty-neighbor rule
```

Complexité : ~1.2M étapes/frame vs 13.5M (÷11).  
Depth range élargi : 3 m → **8 m** (plus aucune contrainte de bord de grille).

**Éviction automatique** (toutes les 60 frames) :
- Blocs à distance > 12 m de la caméra ET âge > 300 frames → supprimés.

**Règle toBlock()** pour les coordonnées négatives :
```cpp
static int toBlock(int g) noexcept {
    return (g >= 0) ? (g / BLOCK_SIZE) : ((g - BLOCK_SIZE + 1) / BLOCK_SIZE);
}
```
Garantit `floor(g / 8)` en arithmétique entière même pour g < 0.

### `core-tsdf/src/main/cpp/marching_cubes.h`

Signature modifiée :
```cpp
// Avant
void runMarchingCubes(const TsdfVolume&, int sX, int sY, int sZ, float vs, float ox, oy, oz, MeshBuffers&);

// Après
void runMarchingCubesBlock(const TsdfVolume&, const BlockKey&, float voxelSize, MeshBuffers&);
```

### `core-tsdf/src/main/cpp/marching_cubes.cpp`

- Itère sur les 8×8×8 cubes du bloc
- Les coins hors-bloc sont lus via `vol.tsdfAt()` → accès cross-block transparent
- Cache par bloc : `extractMesh()` réagrège tous les `meshCache_[key]` existants

---

## 4. Invariants préservés

| Invariant | Vérifié |
|-----------|---------|
| Convention ARCore : Y+ haut, Z- avant, matrice colonne-major | ✓ Y-flip `rayCamY = -(iv-cy)/fy` conservé |
| DEPTH16 : décodage bits[15:3], confiance bits[2:0] | ✓ fait côté Kotlin (DepthFrameProvider) |
| `nativeDestroy` uniquement depuis `processLoop.finally` | ✓ jni_bridge.cpp inchangé |
| VBO uploadé uniquement sur GL thread | ✓ MeshRenderer inchangé |
| API Kotlin/JNI stable | ✓ jni_bridge.cpp inchangé, mêmes 5 méthodes |

---

## 5. Commit

```
1ae9af2  feat(tsdf): replace dense grid with voxel hashing (Phase A)
```

---

## 6. TODO après cette session

| Priorité | Tâche | Statut |
|----------|-------|--------|
| Haute | Tester sur Pixel 9 — vérifier mesh cohérent après sweep >3 m | En attente CI |
| Haute | Vérifier logcat : blocs créés progressivement, pas d'explosion mémoire | En attente |
| Haute | Finaliser installation Android Studio + NDK sur PC Windows | En cours |
| Moyenne | **[Phase B]** Grille d'occupation 2D (`core-nav`) pour la navigation | À faire |
| Basse | `core-net` WebSocket ESP32 + `SafetyState` IR | À faire |
