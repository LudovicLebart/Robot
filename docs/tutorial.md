# Tutoriel — Première mise en route

Ce tutoriel te guide de zéro jusqu'à un mesh 3D visible sur l'écran du Pixel 9.  
Tu n'as besoin d'aucun SDK Android installé en local.

---

## Prérequis

- Un compte GitHub avec accès au dépôt
- Un **Google Pixel 9** avec ARCore installé (Play Store → "Google Play Services for AR")
- Un **ESP32** flashé avec le firmware WebSocket IR (optionnel pour cette mise en route)
- Android Studio (uniquement pour installer l'APK via ADB)

---

## Étape 1 — Cloner le dépôt

```bash
git clone https://github.com/ludoviclebart/robot.git
cd robot
git checkout -b ma-branche
```

---

## Étape 2 — Déclencher le build CI

Le SDK Android et le NDK tournent uniquement en CI. Tu n'as rien à installer.

```bash
git commit --allow-empty -m "trigger build"
git push origin ma-branche
```

Ouvre l'onglet **Actions** sur GitHub. La pipeline :

1. Installe JDK 17 + Gradle 8.14.3 + Android SDK + NDK 27
2. Compile le C++ natif (TSDF + Marching Cubes) en `arm64-v8a`
3. Produit `app-debug.apk` et `app-release-unsigned.apk`

Le build prend environ **5 minutes**. Les APK sont disponibles sous **Artifacts** à la fin du job.

---

## Étape 3 — Installer l'APK sur le Pixel 9

Active le **mode développeur** sur le téléphone :  
*Paramètres → À propos du téléphone → Numéro de build (appuyer 7 fois)*

Puis active le **débogage USB** :  
*Paramètres → Options pour les développeurs → Débogage USB*

Connecte le téléphone en USB et installe :

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Si tu n'as pas le fichier en local, télécharge l'artifact depuis GitHub Actions et :

```bash
adb install -r app-debug.apk
```

---

## Étape 4 — Lancer l'application

1. Ouvre l'application **Robot SLAM** sur le Pixel 9
2. Accepte la permission **caméra** quand elle est demandée
3. Dirige le téléphone vers une pièce — ARCore initialise le tracking en 2-3 secondes
4. Déplace le téléphone lentement — le mesh 3D bleu apparaît en superposition après ~30 frames intégrées

### Ce que tu dois voir

| Indicateur | Signification |
|---|---|
| HUD vert "Tracking" | ARCore a un lock sur la pose |
| Mesh bleu semi-transparent | TSDF intégré, Marching Cubes actif |
| HUD rouge "Failed: ..." | Erreur ARCore — voir [guides pratiques](how-to.md#diagnostiquer-les-erreurs-arcore) |

---

## Étape 5 — Connecter l'ESP32 (optionnel)

Si tu as un ESP32 avec le firmware IR :

1. Connecte le Pixel 9 et l'ESP32 au même réseau WiFi
2. Modifie l'IP dans `app/src/main/kotlin/com/robot/app/di/AppModule.kt` :
   ```kotlin
   WebSocketEsp32Client(scope, ip = "192.168.1.100", port = 8080)
   ```
3. Rebuild et réinstalle

Quand la connexion est établie, le HUD affiche les distances IR haut/bas en mm.  
S'approcher d'un obstacle déclenche l'alerte `OBSTACLE_UP` en rouge.

---

## Résultat attendu

Après cette mise en route tu as :
- Un APK qui build en CI sans aucun SDK local
- Un mesh 3D de ta pièce qui s'affiche en temps réel sur le Pixel 9
- Une boucle de développement : modifier → pousser → CI → APK → ADB install
