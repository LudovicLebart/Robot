# ============================================================
# Robot App - Installation complete sur Windows (Administrateur)
# Usage : clic droit > "Executer avec PowerShell" en tant qu'admin
#         OU : PowerShell (admin) > .\docs\install-windows.ps1
# ============================================================

#Requires -RunAsAdministrator
$ErrorActionPreference = "Stop"

function Write-Step { param($msg) Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-OK   { param($msg) Write-Host "    OK: $msg" -ForegroundColor Green }
function Write-Warn { param($msg) Write-Host "    WARN: $msg" -ForegroundColor Yellow }

Write-Host "============================================================" -ForegroundColor Magenta
Write-Host "  Robot App - Installation Windows" -ForegroundColor Magenta
Write-Host "============================================================" -ForegroundColor Magenta

# ------------------------------------------------------------
# 0. Verifier winget
# ------------------------------------------------------------
Write-Step "Verification de winget..."
if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Write-Warn "winget non disponible. Installez 'App Installer' depuis le Microsoft Store puis relancez."
    exit 1
}
Write-OK "winget disponible : $(winget --version)"

# ------------------------------------------------------------
# 1. JDK 17 (Eclipse Temurin)
# ------------------------------------------------------------
Write-Step "Installation JDK 17 (Eclipse Temurin)..."
$javaVersion = try { (java -version 2>&1)[0] } catch { "" }
if ($javaVersion -match "17\.") {
    Write-OK "JDK 17 deja installe : $javaVersion"
} else {
    winget install --id EclipseAdoptium.Temurin.17.JDK --accept-package-agreements --accept-source-agreements --silent
    Write-OK "JDK 17 installe"
}

# ------------------------------------------------------------
# 2. JAVA_HOME et PATH
# ------------------------------------------------------------
Write-Step "Configuration JAVA_HOME..."
$jdkPath = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Filter "jdk-17*" -ErrorAction SilentlyContinue | Select-Object -First 1)?.FullName
if (-not $jdkPath) {
    # Fallback : chercher dans les chemins courants
    $candidates = @(
        "C:\Program Files\Microsoft\jdk-17*",
        "C:\Program Files\Java\jdk-17*",
        "C:\Program Files\Eclipse Adoptium\jdk-17*"
    )
    foreach ($c in $candidates) {
        $found = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { $jdkPath = $found.FullName; break }
    }
}
if ($jdkPath) {
    [System.Environment]::SetEnvironmentVariable("JAVA_HOME", $jdkPath, "Machine")
    $currentPath = [System.Environment]::GetEnvironmentVariable("Path", "Machine")
    if ($currentPath -notlike "*$jdkPath\bin*") {
        [System.Environment]::SetEnvironmentVariable("Path", "$jdkPath\bin;$currentPath", "Machine")
    }
    $env:JAVA_HOME = $jdkPath
    $env:Path = "$jdkPath\bin;$env:Path"
    Write-OK "JAVA_HOME = $jdkPath"
} else {
    Write-Warn "JDK 17 installe mais chemin non detecte automatiquement. Definissez JAVA_HOME manuellement."
}

# ------------------------------------------------------------
# 3. Android Studio
# ------------------------------------------------------------
Write-Step "Verification Android Studio..."
$asInstalled = Get-ItemProperty "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*" `
    -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -like "Android Studio*" }
if ($asInstalled) {
    Write-OK "Android Studio deja installe : $($asInstalled.DisplayName)"
} else {
    Write-Step "Telechargement et installation d'Android Studio..."
    $asUrl = "https://redirector.gvt1.com/edgedl/android/studio/install/2024.3.2.14/android-studio-2024.3.2.14-windows.exe"
    $asInstaller = "$env:TEMP\android-studio-installer.exe"
    Write-Host "    Telechargement (~1.2 Go) en cours..." -ForegroundColor Yellow
    Invoke-WebRequest -Uri $asUrl -OutFile $asInstaller -UseBasicParsing
    Write-Host "    Lancement de l'installeur (interface graphique)..."
    Start-Process -FilePath $asInstaller -ArgumentList "/S" -Wait
    Write-OK "Android Studio installe (installation silencieuse)"
    Remove-Item $asInstaller -ErrorAction SilentlyContinue
}

# ------------------------------------------------------------
# 4. ANDROID_HOME
# ------------------------------------------------------------
Write-Step "Configuration ANDROID_HOME..."
$sdkPath = "$env:LOCALAPPDATA\Android\Sdk"
if (-not (Test-Path $sdkPath)) {
    # Android Studio n'a peut-etre pas encore cree le SDK — on le cree
    New-Item -ItemType Directory -Path $sdkPath -Force | Out-Null
    Write-Warn "Dossier SDK cree : $sdkPath"
    Write-Warn "Lancez Android Studio une premiere fois pour qu'il telecharge le SDK de base."
} else {
    Write-OK "SDK trouve : $sdkPath"
}
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdkPath, "Machine")
[System.Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $sdkPath, "Machine")
$env:ANDROID_HOME = $sdkPath
$env:ANDROID_SDK_ROOT = $sdkPath

$platformTools = "$sdkPath\platform-tools"
$cmdlineTools  = "$sdkPath\cmdline-tools\latest\bin"
$currentPath   = [System.Environment]::GetEnvironmentVariable("Path", "Machine")
foreach ($p in @($platformTools, $cmdlineTools)) {
    if ($currentPath -notlike "*$p*") {
        $currentPath = "$p;$currentPath"
    }
}
[System.Environment]::SetEnvironmentVariable("Path", $currentPath, "Machine")
$env:Path = "$platformTools;$cmdlineTools;$env:Path"
Write-OK "ANDROID_HOME = $sdkPath"

# ------------------------------------------------------------
# 5. Command-line tools (sdkmanager)
# ------------------------------------------------------------
Write-Step "Telechargement des Android Command-line Tools..."
$cmdlineToolsDir = "$sdkPath\cmdline-tools"
$sdkmanager = "$cmdlineToolsDir\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkmanager)) {
    $cltUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    $cltZip = "$env:TEMP\cmdline-tools.zip"
    Write-Host "    Telechargement cmdline-tools..." -ForegroundColor Yellow
    Invoke-WebRequest -Uri $cltUrl -OutFile $cltZip -UseBasicParsing
    $extractDir = "$env:TEMP\cmdline-tools-extract"
    Expand-Archive -Path $cltZip -DestinationPath $extractDir -Force
    # Le ZIP contient un dossier "cmdline-tools" — le renommer en "latest"
    $latestDir = "$cmdlineToolsDir\latest"
    if (-not (Test-Path $latestDir)) { New-Item -ItemType Directory -Path $latestDir -Force | Out-Null }
    Copy-Item "$extractDir\cmdline-tools\*" -Destination $latestDir -Recurse -Force
    Remove-Item $cltZip, $extractDir -Recurse -ErrorAction SilentlyContinue
    Write-OK "sdkmanager installe"
} else {
    Write-OK "sdkmanager deja disponible"
}

# ------------------------------------------------------------
# 6. NDK 27.0.12077973 + CMake 3.22.1
# ------------------------------------------------------------
Write-Step "Installation NDK 27.0.12077973 et CMake 3.22.1..."
$sdkmanager = "$sdkPath\cmdline-tools\latest\bin\sdkmanager.bat"
if (Test-Path $sdkmanager) {
    # Accepter les licences
    echo "y`ny`ny`ny`ny`ny`ny" | & $sdkmanager --licenses 2>&1 | Out-Null
    # Installer composants
    & $sdkmanager "ndk;27.0.12077973" "cmake;3.22.1" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
    Write-OK "NDK + CMake + platform-tools installes"
} else {
    Write-Warn "sdkmanager non trouve a $sdkmanager — lancez Android Studio pour installer le SDK d'abord."
}

# ------------------------------------------------------------
# 7. local.properties dans le projet
# ------------------------------------------------------------
Write-Step "Creation de local.properties..."
$projectDir = "C:\MES_DOSSIERS\Python_projects\Robot"
$localProps = "$projectDir\local.properties"
if (Test-Path $localProps) {
    Write-OK "local.properties existe deja"
} else {
    $escapedPath = $sdkPath.Replace("\", "\\")
    Set-Content -Path $localProps -Value "sdk.dir=$escapedPath"
    Write-OK "local.properties cree : sdk.dir=$sdkPath"
}

# ------------------------------------------------------------
# 8. Premier build de verification
# ------------------------------------------------------------
Write-Step "Premier build (assembleDebug)..."
Set-Location $projectDir
if (Test-Path "$projectDir\gradlew.bat") {
    Write-Host "    Lancement : gradlew.bat assembleDebug --stacktrace" -ForegroundColor Yellow
    Write-Warn "Ca peut prendre 5-10 minutes au premier lancement (telechargement Gradle + compilation NDK)."
    & "$projectDir\gradlew.bat" assembleDebug --stacktrace
    if ($LASTEXITCODE -eq 0) {
        Write-OK "BUILD SUCCESSFUL !"
        Write-Host "`n    APK : $projectDir\app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
    } else {
        Write-Warn "Build echoue. Consultez les logs ci-dessus."
    }
} else {
    Write-Warn "gradlew.bat non trouve dans $projectDir"
}

# ------------------------------------------------------------
# 9. ADB install (si Pixel 9 branche)
# ------------------------------------------------------------
Write-Step "Installation sur Pixel 9 (si branche en USB)..."
$adb = "$sdkPath\platform-tools\adb.exe"
if (Test-Path $adb) {
    $devices = & $adb devices 2>&1
    if ($devices -match "device$") {
        Write-OK "Pixel 9 detecte ! Installation de l'APK..."
        & $adb install -r "$projectDir\app\build\outputs\apk\debug\app-debug.apk"
        Write-OK "APK installe sur le telephone !"
    } else {
        Write-Warn "Aucun appareil detecte. Branchez le Pixel 9 en USB avec le debogage USB actif."
        Write-Host "    Commande manuelle : adb install -r app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Yellow
    }
} else {
    Write-Warn "adb non trouve. Rechargez le terminal apres installation."
}

# ------------------------------------------------------------
# Resume final
# ------------------------------------------------------------
Write-Host "`n============================================================" -ForegroundColor Magenta
Write-Host "  Installation terminee !" -ForegroundColor Magenta
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host @"

Commandes utiles (dans C:\MES_DOSSIERS\Python_projects\Robot) :

  Build + install + lancer :
    gradlew.bat assembleDebug && adb install -r app\build\outputs\apk\debug\app-debug.apk

  Logcat (logs du robot en temps reel) :
    adb logcat -s RobotOverlay:V ARCore:I TsdfJNI:V -v time

  Logcat etendu (crashs natifs inclus) :
    adb logcat -v time | findstr /i "robot arcore tsdf crash fatal"

"@ -ForegroundColor White
