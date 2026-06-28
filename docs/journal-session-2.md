# Journal de session — Robot App (session 2)

**Date** : 28 juin 2026  
**Branche** : `claude/phone-app-execution-yccjll`  
**Appareil cible** : Pixel 9 (Android 15, ARCore Neural Depth)

---

## Résumé des travaux

Cette session a corrigé les bugs bloquants identifiés à partir des logs on-device et ajouté
deux fonctionnalités de debug. Tout le code livré est poussé sur la branche de travail.

---

## 1. Correctifs

### 1.1 Décodage DEPTH16 (bug critique)

**Commit** : `d1f34f2 — fix(depth): decode ARCore DEPTH16 format correctly`  
**Fichier** : `core-depth/src/main/kotlin/com/robot/depth/DepthFrameProvider.kt`

**Symptôme** : Les logs montraient des profondeurs aberrantes (`center=8195mm` pour un objet
à ~1 m). La grille TSDF (6 m × 3 m × 6 m) ne captait presque rien car les profondeurs
dépassaient ses limites.

**Cause racine** : ARCore DEPTH16 encode dans un pixel 16 bits :
- Bits `[15:3]` → profondeur en mm (valeur réelle)
- Bits `[2:0]`  → niveau de confiance (0 = pas de donnée)

Le code lisait directement la valeur 16 bits brute comme une distance en mm,
produisant des valeurs ×8 trop grandes.

**Preuve dans les logs** :
```
center=8195mm  →  8195 >> 3 = 1024 mm  (1 m, correct)
center=55045mm →  55045 >> 3 = 6880 mm (6.88 m, grande pièce)
center=0mm     →  confidence=0, pas de donnée (comportement attendu)
```

**Correction** :
```kotlin
// Avant (faux) :
val depthValues = ShortArray(width * height).also { rawBuffer.get(it) }

// Après (correct) :
val depthValues = ShortArray(width * height)
for (i in 0 until width * height) {
    val raw = buf.get().toInt() and 0xFFFF
    depthValues[i] = if ((raw and 0x7) == 0) 0 else (raw ushr 3).toShort()
}
```

**Impact** : toutes les profondeurs intégrées dans le TSDF étaient fausses depuis le début.
Ce correctif est le plus important de la session — il débloque la reconstruction 3D réelle.

---

### 1.2 Y-flip dans la projection TSDF (bug majeur)

**Commit** : `4bc1feb — Fix Y-flip in TSDF + opaque mesh + plan-view mode`  
**Fichier** : `core-tsdf/src/main/cpp/tsdf_volume.cpp`

**Cause** : ARCore définit Y+ vers le haut dans l'espace caméra, mais les images de profondeur
ont V+ vers le bas (convention raster image). La projection d'un voxel sur l'image depth
n'inversait pas Y, causant une intégration symétrisée verticalement.

**Correction** :
```cpp
// Avant :  float v = fy *  camY  / camZ + cy;
// Après :  float v = fy * (-camY) / camZ + cy;
```

---

### 1.3 Alignement 16 Ko — Android 15 / Pixel 9

**Commit** : `153df60`  
**Fichiers** : `core-tsdf/src/main/cpp/CMakeLists.txt`, `app/build.gradle.kts`,
`gradle/libs.versions.toml`

Android 15 exige que les bibliothèques natives soient alignées sur des pages de 16 Ko.
Les bibliothèques `libarcore_sdk-jni`, `libarcore_sdk_c` et `librobot_tsdf` étaient
signalées comme incompatibles.

**Corrections** :
```cmake
# CMakeLists.txt
target_link_options(robot_tsdf PRIVATE "-Wl,-z,max-page-size=16384")
```
```kotlin
// app/build.gradle.kts
packaging { jniLibs { useLegacyPackaging = false } }
```
```toml
# libs.versions.toml : ARCore 1.46.0 → 1.47.0 (premier release 16 Ko compatible)
```

---

### 1.4 Mesh invisible en AR

**Commit** : `4bc1feb`  
**Fichier** : `core-render/src/main/kotlin/com/robot/render/MeshRenderer.kt`

Le mesh était rendu avec alpha 0.7 sur le fond caméra lumineux → imperceptible.
Correction : rendu opaque (alpha 1.0), teinte cyan vive.

---

### 1.5 Logs de diagnostic enrichis

**Commit** : `c94b62d`  
**Fichiers** : `core-depth`, `core-tsdf`, `core-render`, `core-slam`

Ajout de logs throttlés (première frame, puis toutes les 90 frames) sur tout le pipeline :
- Etat du tracking ARCore (`TRACKING` / `PAUSED` / `STOPPED`)
- Résolution et intrinsèques de la depth map
- Compteurs succès/échec par frame
- Nombre de vertices dans le mesh extrait
- Upload GL confirmé

---

## 2. Nouvelles fonctionnalités

### 2.1 Overlay de logs on-device

**Commit** : `153df60`  
**Fichiers** : `app/src/main/kotlin/com/robot/app/OverlayLogger.kt`, `MainActivity.kt`

`OverlayLogger` : singleton thread-safe, anneau de 80 lignes, `StateFlow<String>`.  
Bouton **LOG** dans l'UI → affiche/masque un `ScrollView` vert sur fond semi-transparent.  
Tous les modules injectent leur `onLog` via Hilt → `OverlayLogger::log`.

### 2.2 Vue plan (top-down)

**Commit** : `4bc1feb`  
**Fichiers** : `core-render/src/main/kotlin/com/robot/render/SlamRenderer.kt`,
`MeshRenderer.kt`, `MainActivity.kt`

Bouton **PLAN** → bascule entre :
- **Mode AR** : fond caméra + mesh TSDF en overlay cyan
- **Mode plan** : fond noir, projection orthographique depuis 5 m au-dessus du robot,
  fenêtre 6 m × 6 m centrée sur la position courante, teinte verte

Le flag `@Volatile planViewEnabled` est lu sur le GL thread sans lock.

---

## 3. Outillage

### Script d'installation Windows

**Commit** : `5621a78`  
**Fichier** : `docs/install-windows.ps1`

Script PowerShell à lancer en tant qu'Administrateur sur le PC de développement Windows.
Automatise :
1. JDK 17 (Eclipse Temurin) via winget
2. Android Studio (téléchargement + installation silencieuse)
3. Android Command-line Tools
4. NDK `27.0.12077973` + CMake `3.22.1` via `sdkmanager`
5. Création de `local.properties`
6. Premier build `assembleDebug`
7. `adb install` si un appareil est branché

Usage :
```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\docs\install-windows.ps1
```

---

## 4. État du pipeline après cette session

```
Caméra + IMU
    ↓
ARCore (RAW_DEPTH_ONLY sur Pixel 9)
    ↓ pose 6-DoF     ↓ DEPTH16 (bits[15:3]=mm, bits[2:0]=confiance)
    |                ↓ DECODE  ← FIXÉ session 2
    |           DepthFrameProvider → ShortArray propre
    └──────────────┐
                   ↓
              TsdfVolume (C++/JNI)
              intégration correcte  ← FIXÉ session 2 (Y-flip + depth scale)
                   ↓
              Marching Cubes → MeshSnapshot
                   ↓
              SlamRenderer (GL thread)
              upload VBO + rendu opaque  ← FIXÉ session 2
                   ↓
           Mode AR  ──ou──  Mode Plan (nouveau)
```

---

## 5. Ce qui reste à faire

| Priorité | Tâche |
|----------|-------|
| Haute | Tester sur Pixel 9 avec le DEPTH16 fix — vérifier que le mesh se construit en ~30 s |
| Haute | Vérifier le nombre de vertices en vue PLAN (doit monter à >10 000 après un sweep) |
| Moyenne | Filtrage par confiance plus fin : ignorer les pixels de confiance 1–2, ne garder que 3–7 |
| Moyenne | Ajuster l'origine TSDF pour suivre le robot (volume glissant) |
| Basse | Export `.ply` pour validation dans MeshLab |
| Basse | `core-net` WebSocket ESP32 + `SafetyState` IR |

---

## 6. Commandes de référence rapide

```bash
# Build depuis le PC
gradlew.bat assembleDebug

# Install + lancer
adb install -r app\build\outputs\apk\debug\app-debug.apk

# Logcat pipeline
adb logcat -s RobotOverlay:V ARCore:I TsdfJNI:V -v time

# Logcat natif + crashs
adb logcat -v time | findstr /i "robot arcore tsdf crash fatal signal"

# Nombre de vertices dans les logs
adb logcat -v time | findstr "TSDF mesh"
```

---

## 7. Commits de la session

| Hash | Description |
|------|-------------|
| `5621a78` | chore: Windows installation PowerShell script |
| `d1f34f2` | fix(depth): decode ARCore DEPTH16 format correctly ← le plus important |
| `4bc1feb` | Fix Y-flip in TSDF + opaque mesh + plan-view mode |
| `c94b62d` | Fix depth pipeline (Pixel 9) + rich diagnostic logs |
| `153df60` | Add log overlay button + fix 16KB page alignment |
