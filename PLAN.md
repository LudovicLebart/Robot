# Plan général — Robot Mobile Autonome et Conversationnel

**Date** : 19 septembre 2026  
**Vision** : Un robot mobile qui explore son environnement via la vision, apprend incrementalement, et interagit naturellement avec les utilisateurs.

---

## 🎯 Objectif final

Créer une plateforme robotique complète combinant deux briques essentielles :

### **Phase 1 : SLAM Vision-First**
Un système de cartographie et localisation basé **uniquement sur la vision**, inspiré par la façon dont un humain découvre et navigue dans un environnement inconnu.

### **Phase 2 : Robot Conversationnel**
Un robot capable de :
- 🎤 Reconnaître la parole et dialoguer naturellement
- 👤 Identifier et reconnaître les utilisateurs
- 💬 Interagir intelligemment avec l'environnement et les humains
- 🧠 Maintenir contexte et mémoire de conversations

---

## 📍 Phase 1 — SLAM et Navigation Vision-Based

### Principes fondamentaux

1. **Vision comme source unique** de perception
   - ARCore VIO : localisation 6-DoF (pose du téléphone)
   - Nuage de points 3D : structure monde acquise
   - Pas de LiDAR ni sonar (vision = seul capteur)

2. **Logique floue pour la prise de décision**
   - Distance perçue : mesure floue (proche/moyen/loin)
   - Confiance en position : floue (certain/probable/incertain)
   - Similitude lieu : floue (même endroit/nouveau/intermédiaire)
   - Fusion floue → décision navigation robuste

3. **Apprentissage itératif**
   - L'app accumule points + expériences lors du scanning
   - Chaque visite : raffinement des positions, fusion points redondants
   - Loop closure : détection automatique visite lieu connu → correction dérive
   - Mémoire spatiale : carte persistante entre sessions

### Architecture SLAM

```
┌─────────────┐      ┌──────────────┐      ┌─────────────┐
│  ARCore VIO │─────▶│ Point Cloud  │─────▶│ Fuzzy Logic │
│  (pose)     │      │ Accumulator  │      │  Navigation │
└─────────────┘      └──────────────┘      └─────────────┘
       │                     │                     │
       │                     ▼                     ▼
       │              ┌──────────────┐      ┌────────────┐
       │              │ Loop Closure │◀─────│  User Move │
       │              │  Detector    │      │ Command    │
       │              └──────────────┘      └────────────┘
       │                     │
       ▼                     ▼
  ┌────────────────────────────────┐
  │  Persistent Map (DB/File)      │
  │  - Points stable (conf ≥ %)    │
  │  - Pose estimates              │
  │  - Loop closure constraints    │
  └────────────────────────────────┘
```

### Étapes actuelles et TODO

| Phase | Tâche | État |
|-------|-------|------|
| **B.1** | Accumulation points VIO stables | ✅ **FAIT** |
| **B.1.5** | Qualité nuage (confiance, anti-scintillement) | ✅ **FAIT** |
| **B.1.6** | Trajectoire caméra en vue plan | ✅ **FAIT** |
| **B.2** | Loop closure detection (reconnaître lieux revisités) | 🔄 À faire |
| **B.3** | Persistance map entre sessions | 🔄 À faire |
| **B.4** | Logique floue pour commands mouvement robot | 🔄 À faire |
| **B.5** | Intégration moteurs + asservissement | 🔄 À faire |

### Technologie Phase 1

| Couche | Techno |
|--------|--------|
| Localisation | ARCore VIO (6-DoF) |
| Perception | Camera RGB (depth map via Neural Depth ou structure-from-motion) |
| Cartographie | Point cloud persistant (VioMapAccumulator + DB) |
| Logique | Fuzzy inference engine (tâches : obstacle, target, loop closure) |
| Persistance | SQLite ou Firebase Realtime DB |

---

## 🤖 Phase 2 — Robot Conversationnel et Interactif

### Capacités requises

1. **Reconnaissance vocale & NLP**
   - 🎤 Capturer audio du microphone
   - 📝 STT (Speech-to-Text) : convertir parole → texte
   - 💭 NLU (intent / entities) : comprendre demande
   - 🎯 Commandes : "va à la cuisine", "qu'est-ce que tu vois ?", "quel est ton nom ?"

2. **Reconnaissance des utilisateurs**
   - 👥 Face detection + identification
   - 🧠 Apprentissage visages au fil du temps
   - 💾 Profils utilisateurs (préférences, historique)
   - 🔗 Lien conversations ↔ utilisateur

3. **Dialogue naturel**
   - 🤝 Contexte conversationnel (mémoire N derniers échanges)
   - 🧭 Connaissance de la map (localisation courante)
   - 📍 Tâches spatiales : "où suis-je ?", "montre-moi la salle"
   - 🎬 Exécution commandes : déplacement, prises de photo, etc.

4. **Output naturel**
   - 🔊 TTS (Text-to-Speech) : réponse vocale
   - 🎨 Visuel : affichage caméra live, points intéressants, trajectoire
   - ⚡ Feedback temps réel : "je vais voir...", "j'y suis !"

### Architecture Phase 2

```
┌──────────────┐
│ Microphone   │ ──┐
│ + Speaker    │   │
└──────────────┘   │     ┌──────────────────┐
                   └────▶│  Speech Pipeline │
                         ├─ STT            │
                         ├─ NLU (Intent)   │
                         ├─ Dialog State   │
┌──────────────┐         └────┬────────────┘
│ Camera RGB   │              │
│ + Face Lib   │ ──┐          │
└──────────────┘   │          ▼
                   │     ┌──────────────────┐
                   └────▶│ User Recognition │
                         ├─ Face embedding  │
                         ├─ ID + Profile    │
                         └────┬────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
   ┌─────────────┐  ┌──────────────┐  ┌─────────────┐
   │ SLAM Map    │  │ Dialog Engine│  │ Task Exec   │
   │ (position)  │  │ (LLM + ctx)  │  │ (move, see) │
   └─────────────┘  └──────────────┘  └─────────────┘
        │                     │              │
        └─────────────────────┼──────────────┘
                              │
                              ▼
                     ┌──────────────────┐
                     │ TTS + Output     │
                     │ (voix + visuel)  │
                     └──────────────────┘
```

### Étapes Phase 2

| Étape | Tâche | État |
|-------|-------|------|
| **C.1** | STT (speech-to-text en local ou cloud) | 🔄 À décider |
| **C.2** | NLU : intent + entities parser | 🔄 À faire |
| **C.3** | Face detection + embedding | 🔄 À faire |
| **C.4** | User profile DB + historique | 🔄 À faire |
| **C.5** | Dialog engine (LLM + context) | 🔄 À faire |
| **C.6** | Task planner : map spatial intent → robot action | 🔄 À faire |
| **C.7** | TTS (text-to-speech) | 🔄 À faire |
| **C.8** | UI conversationnel (chat + live feed) | 🔄 À faire |

### Technologie Phase 2

| Couche | Techno | Notes |
|--------|--------|-------|
| STT | Google Speech-to-Text (cloud) ou Whisper (local) | Local = offline |
| NLU | Intent classifier (custom ou Rasa) | Ou LLM prompt |
| Face ID | MediaPipe FaceAPI ou TensorFlow Lite | Local, temps réel |
| Dialog | Claude API ou Llama2 (ollama) | Context-aware |
| TTS | Google Cloud TTS ou local (piper) | Local = privacy |
| DB | Firebase Realtime / SQLite | Pour users + chats |

---

## 🔗 Articulation Phase 1 ↔ Phase 2

### Informations partagées

```
SLAM Map (Phase 1)
  ├─ Position courante du robot
  ├─ Points d'intérêt (POI)
  ├─ Zones explorées / à explorer
  └─ Loop closures (lieux revisités)
       │
       ▼ (utilisé par Phase 2)
       
Dialog Engine (Phase 2)
  ├─ "Je suis à la cuisine"
  ├─ "Que vois-tu devant toi ?"
  ├─ "Retourne où tu es arrivé la première fois"
  └─ Commands moteurs basés sur demande utilisateur
       │
       ▼ (feedback vers Phase 1)
       
SLAM Map
  ├─ Nouvelle visite enregistrée
  ├─ Observation utilisateur -> taille des points
  └─ Intent utilisateur -> priorité exploration
```

### Boucle complète exemple

```
1. [Utilisateur] "Robot, vas à la salle à manger"
2. [C.2 NLU] Intent: GO_TO, entity: KITCHEN
3. [C.5 Dialog] Contexte: "J'ai vu la cuisine à l'est, 3 m"
4. [C.6 Task] Command: move_toward(fuzzy_east, fuzzy_3m)
5. [B.4 Fuzzy Logic] Calcule direction + vélocité
6. [B.5 Motor] Robot avance
7. [B.1 SLAM] Points accumulés, position raffine
8. [B.2 Loop Closure] "Tiens, j'ai vu ce coin avant !"
9. [C.5 Dialog] "Voilà la salle à manger. Je vois une table."
10. [Utilisateur] [voit image live + hears TTS response]
```

---

## 📊 Timeline et priorités

### Court terme (mois 1-2)
- ✅ Phase 1 : B.1-B.1.6 finalisée
- 🔄 Phase 1 : B.2 (loop closure) commencée
- 🔄 Phase 2 : C.1-C.2 (STT + NLU) prototype

### Moyen terme (mois 3-6)
- ✅ Phase 1 : B.2-B.5 complétée, robot navigue
- ✅ Phase 2 : C.3-C.6 (Face ID, dialog, task planner)
- 🔄 Intégration Phase 1 ↔ Phase 2

### Long terme (mois 6+)
- ✅ Phase 2 : C.7-C.8 (TTS, UI chat)
- ✅ Déploiement et feedback utilisateurs
- 🔄 Amélioration apprentissage itératif (phase 1 + 2)

---

## 🛠️ Stack technique global

| Domaine | Tech |
|---------|------|
| **Mobile (Android)** | Kotlin 2.1, ARCore 1.47, OpenGL ES 3.0, Coroutines |
| **SLAM & Vision** | MediaPipe (pose, face), TFLite, structure-from-motion |
| **Fuzzy Logic** | Custom Kotlin impl. ou jFuzzyLogic |
| **Persistance** | SQLite (local) / Firebase (sync) |
| **Speech** | Whisper (local) ou Google Cloud STT |
| **NLU** | Custom intent parser ou Rasa |
| **Dialog** | Claude API (cloud) ou Llama2 (local ollama) |
| **Face ID** | MediaPipe FaceAPI ou TFLite face embedding |
| **TTS** | Piper (local) ou Google Cloud TTS |
| **Motors & Hardware** | Arduino/ESP32 (WebSocket + JSON commands) |

---

## 🎓 Philosophie : "Learning by Exploration"

Comme un enfant qui grandit dans une maison :
1. **Premiers jours** : explore, accumule observations, se fait des cartes mentales
2. **Jours suivants** : reconnaît les pièces, anticipe chemins, raffine estimés
3. **Après semaines** : navigue sans penser, dialogue sur ce qu'il voit, aide les autres

**Notre robot :** même cycle, mais **accéléré** via apprentissage numérique.

---

## 📝 Notes de conception clés

- **Pas de LiDAR** : la vision suffit, + robuste en intérieur (glass walls, mirrors)
- **Logique floue** : traite incertitude naturellement (distance ≠ booléen exact)
- **Itératif** : chaque session → meilleure carte, meilleur modèle
- **Conversationnel** : robot = assistant, pas juste machine de mapping
- **Local first** : STT, face ID, dialog doivent marcher **offline** (privacy + latency)

---

**Document créé** : 19 septembre 2026  
**Prochaine révision** : après complétion Phase B.2 (loop closure)
