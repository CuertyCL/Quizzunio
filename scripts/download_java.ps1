<#
.SYNOPSIS
    Instala o verifica Java 25 LTS (Eclipse Temurin) y configura el entorno.
.DESCRIPTION
    - Comprueba si la versión de Java instalada es >= 25; si es así, finaliza.
    - Si no se cumple, descarga e instala Temurin 25 LTS.
    - Windows: instala en %LOCALAPPDATA%\Programs\java y persiste JAVA_HOME y PATH en el registro de usuario.
    - Linux / macOS: instala en ~/.local/share/java, crea enlaces simbólicos en ~/.local/bin y muestra sugerencias para exportar variables.
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# 1. Parámetros base y detección de plataforma
# ---------------------------------------------------------------------------
$RequiredMajorVersion = 25
$JdkImageType = 'jdk'
$JvmImpl = 'hotspot'
$HeapSize = 'normal'
$Vendor = 'eclipse'

$IsWin = $IsWindows -or ($env:OS -eq 'Windows_NT')
$TempDir = [System.IO.Path]::GetTempPath()

$InstallBase = if ($IsWin) {
    Join-Path $env:LOCALAPPDATA 'Programs\java'
} else {
    Join-Path $HOME '.local/share/java'
}

# ---------------------------------------------------------------------------
# 2. Funciones auxiliares
# ---------------------------------------------------------------------------
function Get-InstalledJavaVersion {
    try {
        $versionOutput = & java -version 2>&1 | Out-String
        if ($versionOutput -match 'version "(\d+)(?:\.(\d+))?') {
            $major = [int]$Matches[1]
            if ($major -eq 1 -and$Matches[2]) {
                $major = [int]$Matches[2]
            }
            return $major
        }
    } catch {
        # 'java' no se encuentra en el PATH o no es ejecutable
    }
    return $null
}

function Get-Architecture {
    $arch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
    switch ($arch) {
        'X64'   { return 'x64' }
        'Arm64' { return 'aarch64' }
        default { throw "Arquitectura de CPU no soportada: $arch" }
    }
}

function Get-OperatingSystem {
    if ($IsWin)     { return 'windows' }
    if ($IsLinux)   { return 'linux' }
    if ($IsMacOS)   { return 'mac' }
    throw "Sistema operativo no soportado."
}

# ---------------------------------------------------------------------------
# 3. Verificación de versión existente
# ---------------------------------------------------------------------------
Write-Host "🔍 Verificando si Java $RequiredMajorVersion o superior ya está disponible..." -ForegroundColor Cyan
$currentMajor = Get-InstalledJavaVersion

if ($null -ne$currentMajor -and $currentMajor -ge$RequiredMajorVersion) {
    Write-Host "✅ Java $currentMajor ya se encuentra instalado (cumple con >=$RequiredMajorVersion). No se requieren cambios." -ForegroundColor Green
    return
}

if ($null -eq$currentMajor) {
    Write-Host "⚠️ No se detectó ninguna versión de Java accesible en el PATH." -ForegroundColor Yellow
} else {
    Write-Host "⚠️ Se encontró Java $currentMajor, inferior a la versión requerida ($RequiredMajorVersion)." -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# 4. Descarga del binario
# ---------------------------------------------------------------------------
$os = Get-OperatingSystem$arch = Get-Architecture
$fileExt = if ($IsWin) { 'zip' } else { 'tar.gz' }

$downloadUrl = "https://api.adoptium.net/v3/binary/latest/$RequiredMajorVersion/ga/$os/$arch/$JdkImageType/$JvmImpl/$HeapSize/$Vendor"
$tempFile = Join-Path $TempDir "temurin-$RequiredMajorVersion-$([guid]::NewGuid().ToString('N')).$fileExt"

Write-Host "📥 Descargando Eclipse Temurin $RequiredMajorVersion ($os/$arch)..." -ForegroundColor Cyan
Write-Host "   URL: $downloadUrl" -ForegroundColor Gray

try {
    Invoke-WebRequest -Uri $downloadUrl -OutFile$tempFile -UseBasicParsing
    Write-Host "✅ Descarga completada." -ForegroundColor Green
} catch {
    Write-Error "❌ Error al descargar el JDK: $_"
    exit 1
}

# ---------------------------------------------------------------------------
# 5. Extracción y despliegue
# ---------------------------------------------------------------------------
$installDir = Join-Path $InstallBase "jdk-$RequiredMajorVersion"

if (Test-Path $installDir) {
    Remove-Item -Path $installDir -Recurse -Force
}
New-Item -ItemType Directory -Path $installDir -Force | Out-Null

Write-Host "📂 Extrayendo archivos en: $installDir" -ForegroundColor Cyan
try {
    if ($IsWin) {
        Expand-Archive -Path $tempFile -DestinationPath$installDir -Force
    } else {
        tar -xzf $tempFile -C$installDir
    }

    # Normalizar estructura si el archivo comprimido incluye una carpeta raíz contenedora
    $extractedItems = Get-ChildItem -Path$installDir
    if ($extractedItems.Count -eq 1 -and$extractedItems[0].PSIsContainer) {
        $rootFolder =$extractedItems[0].FullName
        Get-ChildItem -Path $rootFolder -Force \vert{} Move-Item -Destination$installDir -Force
        Remove-Item -Path $rootFolder -Recurse -Force
    }
    Write-Host "✅ Extracción finalizada." -ForegroundColor Green
} catch {
    Write-Error "❌ Error al extraer el archivo: $_"
    exit 1
} finally {
    if (Test-Path $tempFile) {
        Remove-Item -Path $tempFile -Force -ErrorAction SilentlyContinue
    }
}

# ---------------------------------------------------------------------------
# 6. Resolución del directorio raíz del JDK (JAVA_HOME)
# ---------------------------------------------------------------------------
$exeName = if ($IsWin) { 'java.exe' } else { 'java' }

$javaHomePath = if (Test-Path (Join-Path $installDir "bin/$exeName")) {
    $installDir
} elseif (Test-Path (Join-Path $installDir "Contents/Home/bin/$exeName")) {
    Join-Path $installDir 'Contents/Home'
} else {
    $found = Get-ChildItem -Path $installDir -Recurse -Filter$exeName -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) {
        Split-Path (Split-Path $found.FullName -Parent) -Parent
    } else {
        $installDir
    }
}

$javaBinPath = Join-Path$javaHomePath 'bin'

# ---------------------------------------------------------------------------
# 7. Configuración de entorno específica por SO
# ---------------------------------------------------------------------------
if ($IsWin) {
    Write-Host "⚙️ Configurando variables de entorno a nivel de usuario en Windows..." -ForegroundColor Cyan

    [System.Environment]::SetEnvironmentVariable('JAVA_HOME', $javaHomePath, 'User')

    $userPath = [System.Environment]::GetEnvironmentVariable('Path', 'User')
    if ($userPath -notlike "*$javaBinPath*") {
        $newPath = if ([string]::IsNullOrWhiteSpace($userPath)) {$javaBinPath } else { "$userPath;$javaBinPath" }
        [System.Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
        Write-Host "🔧 Se agregó $javaBinPath a la variable PATH del usuario." -ForegroundColor Yellow
    }

    Write-Host "🎉 Instalación finalizada exitosamente." -ForegroundColor Green
    Write-Host "ℹ️ Abre una nueva terminal para que las variables surtan efecto." -ForegroundColor Gray

} else {
    # Linux / macOS: Creación de symlinks en ~/.local/bin
    $localBin = Join-Path$HOME '.local/bin'
    if (-not (Test-Path $localBin)) {
        New-Item -ItemType Directory -Path $localBin -Force | Out-Null
    }

    Write-Host "🔗 Generando enlaces simbólicos en: $localBin" -ForegroundColor Cyan
    $binFiles = Get-ChildItem -Path$javaBinPath -File
    foreach ($file in $binFiles) {$targetLink = Join-Path $localBin$file.Name
        if (Test-Path $targetLink) {
            Remove-Item -Path $targetLink -Force
        }
        New-Item -ItemType SymbolicLink -Path $targetLink -Target$file.FullName | Out-Null
    }
    Write-Host "✅ Enlaces simbólicos creados para las herramientas del JDK." -ForegroundColor Green

    # Sugerencia informativa sin tocar archivos de shell
    Write-Host "`n📌 Información de configuración manual de variables:" -ForegroundColor Yellow
    Write-Host "La instalación se completó en: $javaHomePath" -ForegroundColor White
    Write-Host "Si tu terminal no incluye '~/.local/bin' en el PATH o requieres definir JAVA_HOME explícitamente," -ForegroundColor Gray
    Write-Host "añade las siguientes líneas a tu archivo de configuración (~/.bashrc, ~/.zshrc o ~/.profile):`n" -ForegroundColor Gray
    
    Write-Host "    export JAVA_HOME=`"$javaHomePath`"" -ForegroundColor Cyan
    Write-Host "    export PATH=`"`$JAVA_HOME/bin:`$PATH`"`n" -ForegroundColor Cyan
}
