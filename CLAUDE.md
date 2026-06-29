# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Protocole de démarrage obligatoire

Au début de chaque session, lire ces fichiers dans l'ordre — ils sont la **source de vérité** du projet :

1. `docs/platform.md` — architecture matérielle et logicielle complète (vision système)
2. `docs/explanation.md` — pipeline de threading, TSDF, choix techniques (le POURQUOI)
3. `docs/reference.md` — modules, types partagés, API JNI, dépendances
4. `docs/journal-session-5.md` — état actuel du pipeline, bugs corrigés, TODO prioritaires

Le `docs/tutorial.md` et `docs/how-to.md` sont en lecture **à la demande uniquement** (installation, recettes courantes).

**Ne jamais lire le code source directement si la documentation répond déjà à la question.**

---

## Personas & Triggers

| Trigger | Persona | Comportement clé |
|---------|---------|------------------|
| TSDF, Marching Cubes, JNI, C++, NDK | **Ingénieur Systèmes Natifs** | Penser temps réel, mémoire, thread-safety. Vérifier les conventions ARCore (Y+, colonne-major). |
| ARCore, depth map, pose 6-DoF, tracking | **Spécialiste ARCore** | Valider les unités (mm, DEPTH16 bits[15:3]), vérifier confiance. |
| OpenGL ES, shaders, VBO, rendu | **Graphiste GL** | Penser pipeline GL thread, upload synchrone, no-op si vertexCount == 0. |
| WebSocket, ESP32, SafetyState IR | **Ingénieur Embarqué** | Penser reconnexion, backoff, indépendance de la TSDF. |
| Kotlin, coroutines, Channel, StateFlow, Hilt | **Dev Android** | Penser cycle de vie Activity, dispatcher correct, pas de mutex explicite. |
| >2 modules impactés | **Architecte Général** | Planifier avant de coder (EnterPlanMode). Vérifier les interfaces entre modules. |
| Tout le reste | **Implémenteur** | Lire avant d'écrire. Modification minimale. |

---

## Règles critiques (toujours)

- **Jamais** push sans confirmation explicite de l'utilisateur
- **Jamais** modifier le pipeline TSDF et le rendu GL dans le même commit
- **Jamais** appeler `nativeDestroy` depuis le main thread — uniquement depuis `processLoop.finally`
- **Jamais** uploader un VBO depuis un thread non-GL
- **Jamais** hardcoder quoi que ce soit — aucun nombre magique, aucun paramètre de fallback inline, aucun chemin en dur. Toute valeur numérique nommée va dans un objet `Config` du module concerné (ex. `VioMapConfig`, `RenderConfig`). Les shaders GLSL reçoivent leurs constantes via interpolation de string depuis ces objets.
- **Jamais** hardcoder l'IP de l'ESP32 — paramètre dans `AppModule.kt`
- **Toujours** vérifier la convention ARCore : Y+ vers le haut, Z vers l'arrière, matrice colonne-major
- **Toujours** décoder DEPTH16 : `depth_mm = (raw_uint16 >> 3)`, ignorer si `(raw & 0x7) == 0`
- **Toujours** lister les risques de régression avant de modifier le pipeline de frames
- **Toujours** lire la documentation avant le code source

---

## Stack technique

| Couche | Technologie | Version |
|--------|------------|---------|
| Langage | Kotlin | 2.1.21 |
| Build | AGP + Gradle | 8.9.2 + 8.14.3 |
| NDK / C++ | NDK arm64-v8a C++17 `-O3` | 27.0.12077973 |
| ARCore | `com.google.ar:core` | 1.47.0 |
| DI | Hilt | 2.52 |
| Coroutines | kotlinx-coroutines-android | 1.9.0 |
| WebSocket | OkHttp | 4.12.0 |
| Appareil cible | Google Pixel 9, Android 15 (API 35) | minSdk 31 |

---

## Structure des modules

```
Robot/
├── app/             UI, lifecycle, Hilt wiring, MainActivity
├── core-common/     Types partagés : FrameData, MeshSnapshot, SafetyState, SessionState
├── core-slam/       ArSessionManager — session ARCore, pose 6-DoF
├── core-depth/      DepthFrameProvider — extraction depth map DEPTH16
├── core-tsdf/       TsdfVolume (Kotlin + JNI) — intégration + Marching Cubes C++
│   └── src/main/cpp/  tsdf_volume.cpp, marching_cubes.cpp, jni_bridge.cpp
├── core-render/     SlamRenderer, MeshRenderer, BackgroundRenderer — OpenGL ES 3.0
└── core-net/        WebSocketEsp32Client, VerticalSafetyMonitor — sécurité IR
```

---

## Pipeline de build

Le build se fait via **GitHub Actions CI** — aucun SDK local requis.

```bash
# Déclencher un build CI
git push origin <branche>
# → CI : JDK 17 + Gradle 8.14.3 + Android SDK + NDK 27 → app-debug.apk (~5 min)
```

**Build local** (si Android SDK installé via `docs/install-windows.ps1`) :
```powershell
.\gradlew.bat assembleDebug --stacktrace
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## Commandes ADB de référence

```bash
# Installer l'APK
adb install -r app\build\outputs\apk\debug\app-debug.apk

# Logcat pipeline (vue synthétique)
adb logcat -s RobotOverlay:V ARCore:I TsdfJNI:V -v time

# Logcat natif + crashs
adb logcat -v time | findstr /i "robot arcore tsdf crash fatal signal"

# Nombre de vertices dans le mesh
adb logcat -v time | findstr "TSDF mesh"
```

---

## État courant du projet (session 5 — 29 juin 2026)

**Pipeline fonctionnel :** ARCore → DEPTH16 décodé (confiance ≥3, clamp 0.3–8 m) → TSDF voxel hashing (truncation 8 cm, weight ≥5, carte illimitée) → Marching Cubes incrémental → Mesh opaque cyan + bouton SAVE → PLY. **[Session 5]** Nuage VIO accumulé (VioMapAccumulator, core-nav) → carte structurelle persistante colorée par hauteur.

**Améliorations cumulées (sessions 1 → 5) :**
- DEPTH16 : décodage `(raw >> 3)`, filtre confiance ≥3/7, clamp [300 mm, 8000 mm]
- Y-flip corrigé dans la projection TSDF (`-camY`)
- Truncation élargie : 4 cm → 8 cm
- Weight filter : voxels avec poids < 5 ignorés par Marching Cubes
- Alignement 16 Ko pour Android 15 / Pixel 9
- Mesh opaque (alpha 1.0), teinte cyan
- Vue plan : caméra overhead fixe `(0,8,0)`, ortho ±3.3 m
- Export PLY binaire via bouton SAVE + `adb pull`
- **[Session 4]** TSDF voxel hashing illimité (blocs 8×8×8, hash Teschner, éviction automatique)
- **[Session 5]** VioMapAccumulator (core-nav) :
  - Indexation par ID ARCore (stable pendant le tracking), moyenne pondérée par confiance
  - Éviction des points instables stagnants (STALE_FRAMES=150)
  - Snapshot trié par count → les 10 000 points les plus confirmés affichés (anti-scintillement)
  - StableMapRenderer : couleur par hauteur (vert sol → rouge mi → bleu plafond), taille par poids, points circulaires
  - Couleurs ancrées sur floorY/ceilingY réels (uniforms GL) depuis FloorCeilingDetector
- **[Session 5]** Refactoring "no hardcode" : 7 objets Config (VioMapConfig, RenderConfig, SlamConfig, TsdfConfig, DepthConfig, NetConfig, AppConfig), constantes GLSL injectées via string interpolation Kotlin

**TODO prioritaires :**

| Priorité | Tâche | Statut |
|----------|-------|--------|
| Haute | Tester B.1.5 sur Pixel 9 — vérifier stable count et absence de scintillement | En attente |
| Haute | Investiguer TSDF mesh plafonné à 300 000 vertices | À faire |
| Moyenne | **[Phase B.2]** Reconstruction de surface depuis la carte VIO accumulée | À planifier |
| Moyenne | Vue PLAN : zoom/pan + indicateur position courante | À faire |
| Basse | Détection objets mobiles lents (variance par point, garde dérive ARCore) | À faire |
| Basse | Persistance VioMapAccumulator entre sessions | À faire |
| Basse | `core-net` WebSocket ESP32 + `SafetyState` IR | À faire |
| ~~Moyenne~~ | ~~Volume TSDF glissant centré sur le robot~~ | **RÉSOLU** par voxel hashing |
| ~~Basse~~ | ~~Export `.ply` pour validation MeshLab~~ | **FAIT** |
| ~~Haute~~ | ~~Phase B.1 — VioMapAccumulator + nuage stable~~ | **FAIT session 5** |

---

## Validation

**Avant implémentation :** identifier le(s) module(s) impacté(s) → vérifier interfaces → confirmer avec l'utilisateur si >1 module.

**Après implémentation :**
- Thread correct (GL thread pour upload, TSDF dispatcher pour JNI, IO pour réseau)
- Convention ARCore respectée (unités mm, matrice colonne-major, Y+ haut)
- Pas de régression sur le pipeline FrameData → Channel → nativeIntegrate → MeshSnapshot

---

## Mise à jour de la documentation

- Mettre à jour la doc **uniquement après validation explicite** de l'utilisateur
- Nouveau journal de session : `docs/journal-session-N.md` (incrémenter N — actuel : session 4)
- Format entrée journal : `## [DATE] [TAG] Action effectuée → Résultat.`
- Mettre à jour `docs/reference.md` si un module ou type partagé change
- Mettre à jour `README.md` si l'architecture globale change
- **Interdit :** modifier la documentation via Terminal (`echo`, `sed`). Utiliser l'outil Edit.
