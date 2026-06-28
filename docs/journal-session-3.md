# Journal de session — Robot App (session 3)

**Date** : 27 juin 2026  
**Branche** : `claude/phone-app-execution-yccjll`  
**Appareil cible** : Pixel 9 (Android 15, ARCore Neural Depth)

---

## Résumé des travaux

Cette session a couvert deux volets distincts :
1. **Setup environnement de développement Windows** — script d'installation corrigé et lancé
2. **Pull des commits de session 2b** — 4 commits produits par une session Claude parallèle, améliorant la qualité du mesh et ajoutant l'export PLY

---

## 1. Setup environnement Windows

### 1.1 Correction du script `docs/install-windows.ps1`

**Problème** : le script échouait immédiatement sous PowerShell 5.1 (Windows 11) pour trois raisons :
- Opérateur `?.FullName` (null-conditional) absent en PS 5.1
- Opérateur `&&` non reconnu comme séparateur d'instruction dans les here-strings
- Fichier sauvegardé sans BOM UTF-8 → caractères Unicode (tirets `—`, accents) corrompus

**Corrections appliquées** :
- Remplacement de `?.FullName` par un bloc `if/else` explicite
- Suppression des here-strings problématiques (remplacés par `Write-Host` individuels)
- Resauvegarde du fichier avec `[System.Text.UTF8Encoding]::new($true)` (UTF-8 BOM)
- Remplacement de tous les caractères non-ASCII par des équivalents ASCII

**État** : script fonctionnel. Installation complète à finaliser (Android Studio + NDK en cours).

### 1.2 Nettoyage

- Suppression de `main.py` (fichier Python non tracké, sans rapport avec le projet Android)

---

## 2. Création du CLAUDE.md

**Fichier** : `CLAUDE.md` (racine du projet)

Premier fichier de guidance pour Claude Code dans ce dépôt. Structure inspirée du CLAUDE.md du projet MoneyBot, adaptée au contexte Android/C++ :

- Protocole de démarrage (ordre de lecture des 4 docs clés)
- Personas selon le contexte (C++/JNI, ARCore, GL, Android, Architecte)
- Règles critiques (DEPTH16, thread-safety GL, conventions ARCore, pas de push sans confirmation)
- Stack technique avec versions exactes
- Commandes ADB de référence
- État courant du projet et TODO prioritaires

---

## 3. Commits session 2b (pull)

Quatre commits produits par une session Claude parallèle, récupérés via `git pull`.

### 3.1 `f184b2f` — fix(tsdf): weight filter + truncation élargie

**Problème** : mesh incohérent avec triangles flottants isolés.

**Deux causes identifiées :**
1. Pas de poids minimum dans `interpolate()` : les voxels avec une seule observation (bruit) contribuaient à Marching Cubes.
2. Truncation de 4 cm trop étroite pour le bruit ARCore Neural Depth (~5-10 cm) → surfaces fantômes.

**Corrections** :
```cpp
// Filtrage poids : voxel trop peu observé → espace libre
if (weight < 3) return 1.0f;

// Truncation : 4 cm → 8 cm (bande de transition 4 voxels)
truncationM = 0.08f
```

### 3.2 `65b87df` — diag: logs % pixels valides + clamp profondeur [0.3, 3.0 m]

**Ajouts diagnostics** (toutes les 90 frames) :
- Pourcentage de pixels de profondeur valides
- Position caméra X/Y/Z (détection sortie de grille)
- Intrinsèques complets au premier frame

**Fix TSDF** : profondeurs hors `[0.3 m, 3.0 m]` ignorées. Les valeurs > 3 m atteignent ou dépassent les limites de la grille et produisent des triangles instables (compteur de vertices oscillant).

### 3.3 `ce87cfc` — fix: UVs caméra périmées lors des rotations en vue plan

**Problème** : en vue plan, `SlamRenderer` faisait un `return` anticipé sans appeler `backgroundRenderer.updateUVsIfNeeded()`, causant des UVs périmées après une rotation de l'appareil.

**Corrections** :
- `BackgroundRenderer` : extraction de `updateUVsIfNeeded()`, pré-allocation de `uvStagingBuf`
- `SlamRenderer` : appel de `updateUVsIfNeeded(frame)` avant le `return` anticipé
- `TsdfVolume.reset()` : réinitialisation de `integrateCount_` et `frameCount` pour cohérence des logs
- `OverlayLogger` : concaténation de string hors du bloc `synchronized`

### 3.4 `deb92d7` — feat: export PLY + réduction de bruit + vue plan fixée

**Export PLY (`PlyExporter`) :**
- Format binary little-endian avec face indices et stats de bounding box
- Écriture en chunks de 64 Ko (pas d'allocation heap unique)
- `SlamViewModel.saveMesh()` sur IO dispatcher
- Bouton **SAVE** dans `MainActivity` (désactivé pendant l'écriture)
- Toast affiche la commande `adb pull` pour récupérer le fichier

**Réduction de bruit :**
- `DepthFrameProvider` : seuil de confiance relevé de `>0` à `>=3` (sur 7). Les niveaux 1-2 d'ARCore Neural Depth sont souvent inexacts.
- `tsdf_volume.cpp` : poids minimum relevé de 3 à 5 frames avant contribution à Marching Cubes.

**Vue plan fixée :**
- `MeshRenderer.drawPlanView` : caméra overhead fixe à `(0, 8, 0)` regardant `(0, 0, 0)`, fenêtre ortho ±3.3 m couvrant toute la grille TSDF. Précédemment la caméra suivait le robot et affichait une vue vide dès que le robot sortait des limites.

---

## 4. État du pipeline après cette session

```
Caméra + IMU
    |
ARCore (RAW_DEPTH_ONLY sur Pixel 9)
    |
DepthFrameProvider
    - DEPTH16 decodé : bits[15:3]=mm, bits[2:0]=confiance
    - Confiance >= 3/7 uniquement  <- NOUVEAU session 2b
    - Clamp [300 mm, 3000 mm]      <- NOUVEAU session 2b
    |
TsdfVolume (C++/JNI)
    - Y-flip corrigé               <- session 2
    - Truncation 8 cm              <- NOUVEAU session 2b
    - Weight filter >= 5           <- NOUVEAU session 2b
    |
Marching Cubes -> MeshSnapshot
    |
SlamRenderer (GL thread)
    - UVs mise a jour avant early-return vue plan  <- NOUVEAU session 2b
    |
Mode AR  --ou--  Mode Plan (overhead fixe)         <- FIXE session 2b
    |
[SAVE] -> PlyExporter -> /sdcard/.../mesh.ply      <- NOUVEAU session 2b
```

---

## 5. TODO mis à jour

| Priorité | Tâche | Statut |
|----------|-------|--------|
| Haute | Tester sur Pixel 9 — vérifier mesh visible après ~30 s de sweep | En attente |
| Haute | Vérifier vertices en vue PLAN (doit monter à >10 000) | En attente |
| Haute | Finaliser installation Android Studio + NDK sur PC Windows | En cours |
| Moyenne | Volume TSDF glissant centré sur le robot | À faire |
| Basse | Export `.ply` pour validation MeshLab | **FAIT** (session 2b) |
| Basse | `core-net` WebSocket ESP32 + `SafetyState` IR | À faire |

---

## 6. Commits de la session

| Hash | Description |
|------|-------------|
| `cc1321d` | docs: add CLAUDE.md with project guidance for Claude Code |
| `deb92d7` | feat: PLY export + noise reduction + fixed plan view |
| `ce87cfc` | fix: stale camera UVs on rotation during plan view + minor cleanup |
| `65b87df` | diag: log valid pixel % + camera pos; fix: clamp depth to [0.3, 3.0] m |
| `f184b2f` | fix(tsdf): weight filter + wider truncation for coherent mesh |
