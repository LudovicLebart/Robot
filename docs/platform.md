# Document Conceptuel : Plateforme Robotique Autonome d'Exploration Photovoltaïque

## 1. Vue d'Ensemble du Système

Ce document définit l'architecture matérielle et logicielle d'un robot autonome d'intérieur. L'objectif primaire du système est la **survie énergétique par apprentissage par renforcement (RL)** : le robot doit cartographier son environnement et découvrir de manière autonome les corrélations spatio-temporelles (localisation, heure, date) des sources de lumière pour optimiser sa recharge via ses panneaux solaires.

Le système est conçu autour d'une **architecture décentralisée** séparant le calcul cognitif lourd (téléphone) de l'exécution physique (microcontrôleur).

---

## 2. Architecture Matérielle

L'ingénierie physique est divisée en sous-systèmes fonctionnels.

### 2.1. Base Motrice et Énergie

| Composant | Description |
|---|---|
| Châssis | Configuration différentielle — deux roues motrices (moteurs CC) + une roue libre avant |
| Alimentation | Bloc batterie central alimentant l'ensemble de l'électronique de bord |
| Recharge | Panneaux solaires sur la structure + circuit de gestion de charge (BMS) |

### 2.2. Le Nœud Moteur — ESP32

L'ESP32 agit exclusivement comme le **tronc cérébral et les muscles** du robot. Il ne prend aucune décision complexe.

**Responsabilités :**
- Contrôle de la puissance des moteurs CC (via pont en H, PWM)
- Pilotage des deux servomoteurs de la perche (Pan / Tilt)
- Transmission des lectures de sécurité bas niveau (IR, luxmètre) au Pixel 9 via WebSocket

### 2.3. La Tête Cognitive — Pixel 9

Le centre de calcul principal hébergeant les modèles d'intelligence artificielle et de perception.

**Montage :** fixé au sommet d'une perche surélevée, sur un cardan à 2 axes (Pan/Tilt) piloté par l'ESP32. Cela permet d'orienter indépendamment l'angle de vue de la caméra et des micros sans déplacer la base.

### 2.4. Matrice de Capteurs

| Capteur | Emplacement | Rôle |
|---|---|---|
| LiDAR 2D | Base, ras du sol | Balayage de l'environnement, détection d'obstacles, topologie des murs |
| Modules IR haut/bas | Base | Sécurité verticale (escaliers, tables basses) |
| Luxmètre | Structure | Quantification de la récompense RL (intensité lumineuse captée) |
| Caméra (Pixel 9) | Perche Pan/Tilt | Vision, SLAM visuel |
| Microphones (Pixel 9) | Perche Pan/Tilt | Wake word, Speech-to-Text |
| IMU / Gyroscope (Pixel 9) | Perche | Odométrie, fusion avec SLAM |

---

## 3. Architecture Logicielle

Pour garantir des performances temps réel, le logiciel est structuré en **trois couches distinctes**.

```
┌─────────────────────────────────────────────────────────────┐
│  COUCHE 1 — Perception et Cartographie       (Pixel 9)      │
│  SLAM · Vision supervisée · Audio edge                      │
└──────────────────────────┬──────────────────────────────────┘
                           │  Données structurées
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  COUCHE 2 — Orchestrateur et Décision        (Pixel 9)      │
│  LLM léger · Agent RL de survie                             │
└──────────────────────────┬──────────────────────────────────┘
                           │  Commandes vectorielles
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  COUCHE 3 — Exécution et Contrôle            (ESP32)        │
│  Traduction cinématique · PWM moteurs et servos             │
└─────────────────────────────────────────────────────────────┘
```

### Couche 1 — Perception et Cartographie

Cette couche transforme les données brutes des capteurs en informations utilisables.

**Moteur SLAM**
- Fusionne LiDAR 2D, IR et IMU du téléphone
- Génère une carte de l'environnement par itérations successives
- Maintient les coordonnées exactes du robot (x, y, θ) dans cet espace

**Module de Vision Supervisée**
- Modèle d'inférence léger sur le Tensor Core (ex: MediaPipe)
- Dédié à la détection et reconnaissance faciale
- Émet un signal d'identification `ID_PERSONNE` quand un visage connu entre dans le champ

**Module Audio Edge**
- Surveillance continue hors ligne d'un mot-clé de réveil (`"Hey Robot"`)
- Transcription locale de la parole en texte (Speech-to-Text on-device)

### Couche 2 — Orchestrateur et Décision

**Le Routeur Conversationnel (LLM léger)**

Reçoit les transcriptions textuelles de la Couche 1 et remplit deux rôles :

| Rôle | Comportement |
|---|---|
| Agent de Dialogue | Génère des réponses naturelles couplées au TTS du téléphone ; salue les personnes reconnues par leur `ID_PERSONNE` |
| Extracteur d'Intentions | Traduit une commande vocale (`"Va dans la cuisine"`) en objectif de navigation → coordonnées cibles sur la carte SLAM |

**Agent de Survie et d'Exploration (Deep Reinforcement Learning)**

C'est le réseau de neurones responsable de la découverte autonome des zones de recharge optimales.

- **Vecteur d'observation :** `[x, y, heure, date, intensité_lumineuse_actuelle]`
- **Mécanisme d'apprentissage :** l'agent explore librement. Il apprend progressivement que l'intensité lumineuse dépend non seulement de la position (x, y) — par exemple près d'une fenêtre — mais fortement de l'heure et de la date (saison, angle du soleil).
- **Fonction de récompense R :** strictement corrélée à l'énergie effectivement captée par les panneaux solaires.

### Couche 3 — Exécution et Contrôle

**Interface de communication :** protocole série USB ou Bluetooth reliant le Pixel 9 à l'ESP32.

**Traduction cinématique :** l'Orchestrateur envoie des commandes vectorielles générales. L'ESP32 reçoit ces commandes et calcule les signaux PWM précis pour chaque actionneur.

| Commande haut niveau | Exécution bas niveau |
|---|---|
| `"Avancer à 0.5 m/s"` | PWM proportionnel sur les deux moteurs CC |
| `"Tourner la tête de 45° droite"` | Angle servo pan |
| `"Incliner la tête de 20° haut"` | Angle servo tilt |
| `"Arrêt d'urgence"` | Coupure PWM immédiate |

---

## 4. Flux de Données Global

```
Capteurs physiques
(LiDAR, IR, Luxmètre, Caméra, Micro, IMU)
         │
         ▼
  Couche 1 : Perception
  → Carte SLAM + Pose (x,y,θ)
  → ID_PERSONNE
  → Texte transcrit
  → Intensité lumineuse
         │
         ▼
  Couche 2 : Orchestrateur
  → Si commande vocale : extraction d'intention → objectif SLAM
  → Si visage connu : réponse TTS
  → En continu : Agent RL observe [x,y,heure,date,lux] → décide direction
         │
         ▼
  Couche 3 : ESP32
  → PWM moteurs + servos
  → Feedback capteurs bas niveau → Couche 1
```
