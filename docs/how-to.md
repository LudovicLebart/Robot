# Guides pratiques

Recettes courtes pour des tâches précises. Chaque guide part du principe que le projet build déjà.

---

## Modifier la résolution du volume TSDF

Le volume par défaut est **300 × 150 × 300 voxels à 2 cm** (~108 MB RAM, couvre 6m × 3m × 6m).

Pour une pièce plus grande ou un robot qui se déplace loin :

```kotlin
// app/src/main/kotlin/com/robot/app/di/AppModule.kt
TsdfVolume(
    sizeX = 500, sizeY = 200, sizeZ = 500,  // 10m × 4m × 10m
    voxelSizeM = 0.02f,                      // 2 cm/voxel
    truncationM = 0.04f,                     // 2× voxelSize recommandé
)
```

> **Attention RAM** : `sizeX × sizeY × sizeZ × 8 octets`. 500×200×500 = ~400 MB — acceptable sur Pixel 9 (12 GB), mais surveille le heap natif avec Android Profiler.

Pour une résolution plus fine (1 cm) sans augmenter la RAM, réduire la taille :

```kotlin
TsdfVolume(sizeX = 200, sizeY = 100, sizeZ = 200, voxelSizeM = 0.01f, truncationM = 0.02f)
```

---

## Changer la fréquence d'extraction du mesh

Par défaut le mesh est extrait toutes les **30 frames intégrées** (~1 extraction/seconde à 30 fps).

```kotlin
// core-tsdf/src/main/kotlin/com/robot/tsdf/TsdfVolume.kt
private val meshExtractInterval = 30  // ← augmenter pour moins de charge CPU
```

Marching Cubes sur 300×150×300 prend ~50-100 ms en C++ natif sur A52 (Pixel 9).  
Passer à 60 laisse 2 secondes entre extractions — raisonnable pour un robot lent.

---

## Modifier l'adresse IP de l'ESP32

```kotlin
// app/src/main/kotlin/com/robot/app/di/AppModule.kt
WebSocketEsp32Client(scope, ip = "192.168.4.1", port = 8080)
```

L'ESP32 peut aussi être configuré en **access point** (IP fixe `192.168.4.1`) pour éviter de dépendre d'un routeur.

---

## Ajuster les seuils de sécurité IR

```kotlin
// core-net/src/main/kotlin/com/robot/net/VerticalSafetyMonitor.kt
private const val VOID_THRESHOLD_MM    = 300  // ← en dessous = vide détecté
private const val OBSTACLE_THRESHOLD_MM = 200  // ← en dessous = obstacle en hauteur
```

| Paramètre | Valeur par défaut | Signification |
|---|---|---|
| `VOID_THRESHOLD_MM` | 300 mm | Si IR bas > 300 mm → `VoidDetectedDown` |
| `OBSTACLE_THRESHOLD_MM` | 200 mm | Si IR haut < 200 mm → `ObstacleUp` |

---

## Exporter un mesh en .PLY (débogage)

Ajouter un bouton dans `MainActivity` qui déclenche l'export depuis le `StateFlow<MeshSnapshot>` :

```kotlin
// Dans un nouveau fichier core-common/.../PlyExporter.kt
fun MeshSnapshot.writePly(file: File) {
    file.bufferedWriter().use { w ->
        w.write("ply\nformat ascii 1.0\nelement vertex $vertexCount\n")
        w.write("property float x\nproperty float y\nproperty float z\n")
        w.write("property float nx\nproperty float ny\nproperty float nz\nend_header\n")
        repeat(vertexCount) { i ->
            w.write("${vertices[i*3]} ${vertices[i*3+1]} ${vertices[i*3+2]} " +
                    "${normals[i*3]} ${normals[i*3+1]} ${normals[i*3+2]}\n")
        }
    }
}
```

Puis récupérer le fichier via ADB :
```bash
adb pull /sdcard/Android/data/com.robot.app/files/mesh.ply .
# Ouvrir dans MeshLab pour vérifier la géométrie
```

---

## Diagnostiquer les erreurs ARCore

| Message HUD | Cause probable | Solution |
|---|---|---|
| `ARCore not installed` | Google Play Services for AR absent | Installer depuis le Play Store |
| `Camera not available` | Autre app utilise la caméra | Fermer les autres apps |
| `ARCore APK too old` | Version ARCore trop ancienne | Mettre à jour via Play Store |
| Pas de mesh après 60s | Tracking perdu (lumière insuffisante) | Déplacer le téléphone lentement dans une pièce éclairée |

---

## Lancer un build local (si Android SDK disponible)

```bash
# Nécessite : Android SDK + NDK 27 + CMake 3.22 installés localement
./gradlew assembleDebug --stacktrace
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sans gradlew fonctionnel, utiliser directement Gradle 8.14.3 :
```bash
gradle assembleDebug --stacktrace
```
