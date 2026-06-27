# Robot SLAM

Application Android de cartographie 3D en temps réel pour robot autonome.  
Tourne sur un **Google Pixel 9** (ARCore + caméra RGB + IMU), sans LiDAR.

```
Caméra RGB + IMU
      ↓
 ARCore SLAM (pose 6-DoF)  +  ARCore Depth API (depth map dense)
      ↓                              ↓
 Position exacte              Nuage de points dense
            \                /
             → Fusion 3D (sweeping)
                    ↓
             Grille TSDF en mémoire (intégration incrémentale)
                    ↓
             Marching Cubes → Mesh 3D lissé → OpenGL ES 3.0

 Capteurs IR haut/bas via ESP32 (WebSocket) → SafetyState uniquement
```

## Documentation

| Document | Contenu |
|---|---|
| [Plateforme](docs/platform.md) | Architecture matérielle et logicielle complète du robot (vision système) |
| [Tutoriel](docs/tutorial.md) | Première mise en route, du dépôt à l'APK qui tourne |
| [Guides pratiques](docs/how-to.md) | Modifier les paramètres TSDF, connecter l'ESP32, exporter un mesh |
| [Architecture](docs/explanation.md) | Pourquoi ces choix techniques, comment le pipeline tient ensemble |
| [Référence](docs/reference.md) | Modules, API JNI, types partagés, configuration |

## Build rapide

Le build se fait entièrement via **GitHub Actions** (pas d'Android SDK requis en local).

```
git push origin <votre-branche>
# → CI déclenche automatiquement, APK uploadé en artifact (~5 min)
```

## Matériel cible

| Composant | Rôle |
|---|---|
| Google Pixel 9 | Cerveau du robot — SLAM + rendu 3D |
| ESP32 | Pont WiFi → WebSocket pour les capteurs IR |
| Capteur IR haut | Détection d'obstacles (tables, portes basses) |
| Capteur IR bas | Détection de vide (escaliers, bords de table) |
