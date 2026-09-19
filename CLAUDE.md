# CLAUDE.md — Guidance pour development Robot

This file provides guidance to Claude Code when working with code in this repository.

## 🎯 Vision du projet (maj 19 septembre 2026)

**Robot mobile autonome et conversationnel basé sur la vision.**

Deux phases interdépendantes :

1. **Phase 1 (SLAM Vision-First)** 
   - Cartographie via vision seule (ARCore VIO + points 3D accumulés)
   - Pas de LiDAR : la vision RGB + depth est suffisante
   - Logique floue pour prise de décision navigation robuste
   - Apprentissage itératif (chaque session raffine la carte)

2. **Phase 2 (Robot Conversationnel)**
   - Dialogue naturel (STT + NLU + LLM)
   - Reconnaissance utilisateurs (face ID)
   - Interaction spatiale (map + commandes mouvement)

👉 **Lire `PLAN.md` pour vision complète, architecture, timeline.**

---

## Protocole de démarrage obligatoire

Au début de chaque session, lire ces fichiers dans l'ordre — ils sont la **source de vérité** :

1. **`PLAN.md`** — vision globale, phases, architecture articulation
2. `docs/journal-session-6.md` — état actuel du code, TODO prioritaires
3. `docs/explanation.md` — SLAM pipeline, choix techniques, fuzzy logic
4. `docs/reference.md` — modules Kotlin, types partagés, API
5. `docs/platform.md` — architecture matérielle et logicielle

**`docs/tutorial.md` et `docs/how-to.md`** : lecture à la demande seule (install, recipes).

**🔴 Règle critique** : Ne jamais lire le code source directement si la documentation répond déjà à la question.

---

## Personas & Triggers

| Trigger | Persona | Comportement clé |
|---------|---------|------------------|
| ARCore VIO, tracking, pose 6-DoF | **Spécialiste SLAM Vision** | Penser localisation, dérive, loop closure, robustesse capture bruitée |
| Point cloud, fuzzy logic, navigation | **Ingénieur Spatial** | Penser nuage persistant, logique d'incertitude, apprentissage itératif |
| STT, NLU, dialogue, interaction | **AI/NLP Engineer** | Penser context, intent parsing, user state, memory |
| Face detection, user recognition | **Computer Vision** | Penser embedding, real-time latency, privacy (local processing) |
| Kotlin, coroutines, lifecycle | **Android Dev** | Penser dispatcher, thread safety, Activity lifecycle |
| >2 modules impactés | **Architecte** | Utiliser `EnterPlanMode`. Vérifier interfaces. |

---

## Règles critiques (toujours)

### Phase 1 (SLAM)

- **Jamais** hardcoder constantes numériques. Toute valeur → objet `Config` du module (ex `VioMapConfig`, `FuzzyLogicConfig`)
- **Jamais** modifier le pipeline VIO → accum → fuzzy sans documenter risques regression
- **Toujours** décoder DEPTH16 correctement : `depth_mm = (raw_uint16 >> 3)`
- **Toujours** respecter convention ARCore : Y+ vers haut, Z vers arrière, matrice colonne-major
- **Jamais** uploader VBO depuis thread non-GL
- **Toujours** lister risques regression avant modifier frame pipeline

### Phase 2 (Conversationnel)

- **Jamais** stocker credentials (API keys, user passwords) en clair → utiliser Android KeyStore
- **Jamais** appeler STT/NLU depuis main thread → dispatcher IO
- **Toujours** mettre dialog state dans DataStore/DB, pas juste memory
- **Jamais** faire face detection sans user consent (CCTV laws, privacy)
- **Toujours** supporter offline mode pour STT/NLU (local Whisper, intent classifier)

### Général

- **Jamais** push sans confirmation explicite utilisateur
- **Jamais** modifier deux modules d'architecture (ex: SLAM + conversationnel) dans un même commit
- **Toujours** lire la doc avant le code source
- **Interdit** : modifier doc via Terminal. Utiliser l'outil Edit.

---

## Stack technique

| Domaine | Techno | Version |
|---------|--------|---------|
| **Mobile (Android)** | Kotlin | 2.1.21 |
| **Build** | AGP + Gradle | 8.9.2 + 8.14.3 |
| **SLAM** | ARCore | 1.47.0 |
| **Vision** | MediaPipe + TFLite | Latest |
| **Fuzzy Logic** | Custom Kotlin impl. | — |
| **Persistence** | SQLite / Firebase | — |
| **Speech** | Whisper (local) ou Google Cloud STT | — |
| **NLU** | Custom + intent classifier | — |
| **Dialog** | Claude API (cloud) ou Llama2 (ollama) | — |
| **Face ID** | MediaPipe FaceAPI | — |
| **TTS** | Piper (local) ou Google Cloud | — |
| **Hardware** | Arduino/ESP32 (WebSocket) | — |
| **Cible** | Pixel 9, Android 15 (API 35) | — |

---

## Structure des modules

```
Robot/
├── app/                    Activity, lifecycle, Hilt wiring
├── core-common/            Types partagés : FrameData, Point, User, DialogState
├── core-slam/              ARSessionManager, VIO extraction
├── core-nav/               VioMapAccumulator, FuzzyLogicEngine (PHASE 1)
├── core-vision/            MediaPipe wrappers, face detection (PHASE 2)
├── core-dialogue/          STT wrapper, NLU classifier, dialog state (PHASE 2)
├── core-render/            OpenGL ES renderers
└── core-net/               WebSocket ESP32, hardware commands
```

---

## Validation de modification

**Avant implémentation** :
- Identifier le(s) module(s) impacté(s)
- Lire interfaces entre modules
- Si >1 module : confirmer approche avec utilisateur via plan

**Après implémentation** :
- ✅ Thread correct (GL thread, IO dispatcher, main dispatcher)
- ✅ Convention ARCore respectée (si SLAM)
- ✅ Config + constantes centralisées (pas de hardcode)
- ✅ Pas de regression pipeline FrameData → Channel → spatial processing
- ✅ Test sur Pixel 9 avant merge
- ✅ Documentation mise à jour

---

## Mise à jour de la documentation

- Mettre à jour doc **uniquement après validation explicite** utilisateur
- Nouveau journal session : `docs/journal-session-N.md`
- Format entrée : `## [DATE] [TAG] Action effectuée → Résultat.`
- Mettre à jour `PLAN.md` si vision ou phases changent
- Mettre à jour `docs/explanation.md` si architecture/algorithme change
- Mettre à jour `README.md` si usage global change
- **Interdit** : modifier doc via Terminal. Utiliser Edit tool.

---

## Branches et push

- Branche de travail : `claude/phone-app-execution-*`
- Développement : `dev` (branche principale)
- Production : `main` (synchronisé avec dev via fast-forward)
- **Jamais** : force push, `--no-verify`, merge avec `-no-ff`
- **Workflow** : commit sur feature → push dev → push dev:main → confirmation utilisateur

---

## État courant (session 6 — 19 septembre 2026)

**Pipeline SLAM fonctionnel** :
- ✅ ARCore pose tracking
- ✅ Point cloud VIO accumulé + stable (confiance ≥ %)
- ✅ Rendu plan view : nuage coloré par hauteur
- ✅ Trajectoire caméra enregistrée
- ✅ Export PLY

**En développement** :
- 🔄 Loop closure detection (reconnaître lieux revisités)
- 🔄 Persistance map sessions
- 🔄 Fuzzy logic navigation

**Prochaines priorités** :
1. **Haute** : Tester trajectoire + nuage sur Pixel 9
2. **Haute** : Loop closure (B.2)
3. **Moyenne** : Fuzzy logic commandes mouvement (B.4)
4. **Moyenne** : Phase 2 : STT + NLU prototype (C.1-C.2)

👉 **Détails complets** : voir `PLAN.md` et `docs/journal-session-6.md`

---

**Document créé** : 19 septembre 2026  
**Maintenu par** : Claude Code + utilisateur  
**Prochaine révision** : après complétion Phase B.2
