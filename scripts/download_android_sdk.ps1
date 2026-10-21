<#
.SYNOPSIS
    Descarga e instala componentes del Android SDK directamente desde los repositorios XML de Google.
.PARAMETER CustomSdkPath
    Ruta personalizada opcional para instalar el SDK si no se detecta uno existente.
.PARAMETER ApiLevel
    Nivel de API de Android a instalar (Por defecto: 29 = Android 10).
.PARAMETER BuildToolsVersion
    Versión de build-tools (Por defecto: "latest" para la última estable, o ej. "29.0.3").
.PARAMETER IncludeSystemImage
    Flag para activar la descarga e instalación de la imagen de sistema x86_64.
#>
[CmdletBinding()]
param (
    [string]$CustomSdkPath = "",
    [int]$ApiLevel = 29,
    [string]$BuildToolsVersion = "latest",
    [switch]$IncludeSystemImage
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue" # Acelera drásticamente Invoke-WebRequest en PS 5.1

# ==============================================================================
# 1. VARIABLES DE CONFIGURACIÓN (EDITABLES)
# ==============================================================================
$RepoBaseUrl = "https://dl.google.com/android/repository/"
$RepoXmlUrl  = "${RepoBaseUrl}repository2-3.xml"

# Configuración de System Image (Editable para futuras versiones/variantes)
$SystemImageArch    = "x86_64"
$SystemImageTag     = "default" # Ej: "default" (AOSP) o "google_apis"
$SystemImagePkgPath = "system-images;android-$ApiLevel;$SystemImageTag;$SystemImageArch"
$SystemImageBaseUrl = "https://dl.google.com/android/repository/sys-img/android/"
$SystemImageXmlUrl  = "${SystemImageBaseUrl}sys-img2-3.xml"

# ==============================================================================
# 2. DETECCIÓN DE SISTEMA OPERATIVO Y ARQUITECTURA
# ==============================================================================
$IsWin = if ($PSVersionTable.PSVersion.Major -lt 6) { $true } else { $IsWindows }
$IsLin = if ($PSVersionTable.PSVersion.Major -lt 6) { $false } else { $IsLinux }
$IsMac = if ($PSVersionTable.PSVersion.Major -lt 6) { $false } else { $IsMacOS }

$HostOS = if ($IsWin) { "windows" } elseif ($IsLin) { "linux" } elseif ($IsMac) { "macosx" } else { throw "OS no soportado." }

$OsArch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLower()
$HostArch = if ($OsArch -eq "arm64") { "aarch64" } else { "x64" }

# ==============================================================================
# 3. DETECCIÓN DE ANDROID SDK PREEXISTENTE
# ==============================================================================
function Resolve-AndroidSdkPath {
    param([string]$OverridePath)

    if (-not [string]::IsNullOrWhiteSpace($OverridePath)) {
        Write-Host "[INFO] Usando ruta especificada por parámetro: $OverridePath" -ForegroundColor Cyan
        return $OverridePath
    }

    # 1. Revisar variables de entorno
    foreach ($var in @("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
        $val = [Environment]::GetEnvironmentVariable($var)
        if (-not [string]::IsNullOrWhiteSpace($val) -and (Test-Path $val)) {
            Write-Host "[DETECTADO] SDK encontrado en variable de entorno `$$var :$val" -ForegroundColor Green
            return $val
        }
    }

    # 2. Revisar rutas estándar según el SO
    $candidatePaths = if ($IsWin) {
        @(
            (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
            "C:\Android\Sdk"
        )
    } elseif ($IsMac) {
        @(
            (Join-Path $HOME "Library/Android/sdk")
        )
    } else {
        @(
            (Join-Path $HOME "Android/Sdk"),
            "/opt/android-sdk"
        )
    }

    foreach ($path in $candidatePaths) {
        if (Test-Path $path) {
            Write-Host "[DETECTADO] SDK preexistente encontrado en ruta estándar: $path" -ForegroundColor Green
            return $path
        }
    }

    $defaultPath = $candidatePaths[0]
    Write-Host "[INFO] No se detectó SDK previo. Se instalará en ruta estándar: $defaultPath" -ForegroundColor Yellow
    return $defaultPath
}

$SdkRoot = Resolve-AndroidSdkPath -OverridePath $CustomSdkPath

# Detección de preexistencia del SDK o variables de entorno
$SdkDirExists         = Test-Path $SdkRoot
$EnvAlreadyConfigured = if ($IsWin) {
    (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("ANDROID_HOME", "User"))) -or
    (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("ANDROID_HOME")))
} else {
    -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("ANDROID_HOME"))
}
$SdkPreExisting = $SdkDirExists -or $EnvAlreadyConfigured

if (-not (Test-Path $SdkRoot)) {
    New-Item -ItemType Directory -Path $SdkRoot -Force | Out-Null
}

# ==============================================================================
# 4. FUNCIONES DE PARSING XML Y DESCARGA
# ==============================================================================
function Get-RemotePackageInfo {
    param (
        [xml]$XmlDoc,
        [string]$PackagePath,
        [string]$BaseUrl
    )

    $nodes = $XmlDoc.SelectNodes("//remotePackage[@path='$PackagePath']") | Where-Object {
        $_.GetAttribute("obsolete") -ne "true" -and $_.channelRef.ref -eq "channel-0"
    }

    if (-not $nodes) {
        throw "No se encontró el paquete estable '$PackagePath' en el manifiesto XML."
    }

    # Seleccionar el de mayor revisión si hay varios
    $pkg = $nodes | Sort-Object { [int]($_.revision.major) }, { [int]($_.revision.minor) }, { [int]($_.revision.micro) } -Descending | Select-Object -First 1

    # Filtrar archivo según OS y Arquitectura
    $archive = @($pkg.archives.archive) | Where-Object {
        $osNode   = $_.'host-os'
        $archNode = $_.'host-arch'
        $osMatch   = (-not $osNode) -or ($osNode -eq $HostOS)
        $archMatch = (-not $archNode) -or ($archNode -eq $HostArch)
        $osMatch -and $archMatch
    } | Select-Object -First 1

    if (-not $archive) {
        throw "No hay binario compatible con OS='$HostOS' / Arch='$HostArch' para '$PackagePath'."
    }

    $rev = @($pkg.revision.major, $pkg.revision.minor, $pkg.revision.micro) | Where-Object { $_ -ne$null }
    $url =$archive.complete.url

    return [PSCustomObject]@{
        Path        = $pkg.path
        DisplayName = $pkg.'display-name'
        Revision    = ($rev -join ".")
        DownloadUrl = if ($url -match "^https?://") { $url } else { "${BaseUrl}${url}" }
        SizeMB      = [math]::Round(([long]$archive.complete.size / 1MB), 2)
        Sha1        = $archive.complete.checksum.'#text'
        FileName    = [System.IO.Path]::GetFileName($url)
    }
}

function Install-SdkPackage {
    param (
        [PSCustomObject]$PkgInfo,
        [string]$SdkBaseDir
    )

    $relSubPath = $PkgInfo.Path -replace ';', [System.IO.Path]::DirectorySeparatorChar
	$targetDir  = Join-Path $SdkBaseDir $relSubPath

    if (Test-Path $targetDir) {
        Write-Host "[OMITIDO] '$($PkgInfo.Path)' ya existe en: $targetDir" -ForegroundColor DarkGray
        return [PSCustomObject]@{
            Paquete = $PkgInfo.Path
            Version = $PkgInfo.Revision
            Estado  = "Preexistente"
            Ruta    = $targetDir
        }
    }

    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("android_sdk_" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null $zipFile = Join-Path $tempDir $PkgInfo.FileName

    try {
        Write-Host "[DESCARGANDO] $($PkgInfo.DisplayName) (v$($PkgInfo.Revision) - $($PkgInfo.SizeMB) MB)..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $PkgInfo.DownloadUrl -OutFile $zipFile -UseBasicParsing

        # Verificar SHA1
        $actualHash = (Get-FileHash -Path $zipFile -Algorithm SHA1).Hash.ToLower()
        if ($actualHash -ne $PkgInfo.Sha1.ToLower()) {
            throw "Fallo de checksum SHA1 para $($PkgInfo.FileName). Esperado: $($PkgInfo.Sha1), Obtenido: $actualHash"
        }

        Write-Host "[EXTRAYENDO] $($PkgInfo.Path) -> $targetDir" -ForegroundColor Gray
        $extractStaging = Join-Path $tempDir "extracted"
        Expand-Archive -Path $zipFile -DestinationPath $extractStaging -Force

        # Mover desde la carpeta raíz interna del ZIP hacia el directorio estándar del SDK
        $parentTarget = Split-Path $targetDir -Parent
        if (-not (Test-Path $parentTarget)) {
            New-Item -ItemType Directory -Path $parentTarget -Force | Out-Null
        }

        $innerItems = @(Get-ChildItem -Path$extractStaging)
        if ($innerItems.Count -eq 1 -and $innerItems[0].PSIsContainer) {
            Move-Item -Path $innerItems[0].FullName -Destination $targetDir -Force
        } else {
            New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
            Move-Item -Path (Join-Path $extractStaging "*") -Destination $targetDir -Force
        }

        # Restaurar permisos de ejecución en Linux / macOS
        if (-not $IsWin) {
            & chmod -R u+x $targetDir 2>$null
        }

        return [PSCustomObject]@{
            Paquete = $PkgInfo.Path
            Version = $PkgInfo.Revision
            Estado  = "Instalado"
            Ruta    = $targetDir
        }
    }
    finally {
        if (Test-Path $tempDir) {
            Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

# ==============================================================================
# 5. RESOLUCIÓN Y DESCARGA DE PAQUETES
# ==============================================================================
Write-Host "`n[INFO] Descargando manifiesto: $RepoXmlUrl" -ForegroundColor Cyan
[xml]$MainRepoXml = Invoke-RestMethod -Uri $RepoXmlUrl -UseBasicParsing

# Resolver versión de build-tools si es "latest"
if ($BuildToolsVersion -eq "latest") {
    $btNodes = $MainRepoXml.SelectNodes("//remotePackage[starts-with(@path, 'build-tools;')]") | Where-Object {
        $_.GetAttribute("obsolete") -ne "true" -and $_.channelRef.ref -eq "channel-0" -and (-not $_.revision.preview)
    }
    $latestBt = $btNodes | Sort-Object { [int]$_.revision.major }, { [int]$_.revision.minor }, { [int]$_.revision.micro } -Descending | Select-Object -First 1
    $BuildToolsPkg = $latestBt.path
} else {
    $BuildToolsPkg = "build-tools;$BuildToolsVersion"
}

$PackagesToInstall = @(
    "cmdline-tools;latest",
    "platform-tools",
    $BuildToolsPkg,
    "platforms;android-$ApiLevel",
    "emulator"
)

$InstalledReport = @()

foreach ($pkgPath in $PackagesToInstall) {
    $info = Get-RemotePackageInfo -XmlDoc $MainRepoXml -PackagePath $pkgPath -BaseUrl $RepoBaseUrl
    $InstalledReport += Install-SdkPackage -PkgInfo $info -SdkBaseDir $SdkRoot
}

# Descarga condicional de System Image por Flag
if ($IncludeSystemImage) {
    Write-Host "`n[INFO] Flag -IncludeSystemImage activo. Consultando: $SystemImageXmlUrl" -ForegroundColor Cyan
    [xml]$SysImgXml = Invoke-RestMethod -Uri $SystemImageXmlUrl -UseBasicParsing$sysImgInfo = Get-RemotePackageInfo -XmlDoc $SysImgXml -PackagePath$SystemImagePkgPath -BaseUrl $SystemImageBaseUrl$InstalledReport += Install-SdkPackage -PkgInfo $sysImgInfo -SdkBaseDir$SdkRoot
}

# ==============================================================================
# 6. CONFIGURACIÓN DE VARIABLES DE ENTORNO E INFORME FINAL
# ==============================================================================
Write-Host "`n==============================================================================" -ForegroundColor Green
Write-Host " RESUMEN DE INSTALACIÓN DE ANDROID SDK" -ForegroundColor Green
Write-Host "==============================================================================" -ForegroundColor Green
Write-Host "Sistema Operativo : $HostOS ($HostArch)"
Write-Host "Ruta Raíz del SDK : $SdkRoot`n"

$InstalledReport | Format-Table -AutoSize | Out-String | Write-Host

if ($IsWin) {
    function Get-UserConfirmation {
        param (
            [string]$PromptMessage
        )
        $response = Read-Host "$PromptMessage [Y/N]"
        if (-not [string]::IsNullOrWhiteSpace($response) -and ($response.Trim() -match '^(y|yes|s|si)$')) {
            return $true
        }
        return $false
    }

    $pathsToAdd = @(
        (Join-Path $SdkRoot "cmdline-tools\latest\bin"),
        (Join-Path $SdkRoot "platform-tools"),
        (Join-Path $SdkRoot "emulator")
    )

    if ($SdkPreExisting) {
        Write-Host "[INFO] Se detectó una instalación previa o configuración existente del SDK." -ForegroundColor Cyan

        # a) Confirmar si desea sobreescribir/actualizar ANDROID_HOME
        $updateHome = Get-UserConfirmation -PromptMessage "¿Desea sobreescribir/actualizar 'ANDROID_HOME' a nivel de usuario con la ruta '$SdkRoot'?"
        if ($updateHome) {
            [Environment]::SetEnvironmentVariable("ANDROID_HOME", $SdkRoot, "User")
            $env:ANDROID_HOME = $SdkRoot
            Write-Host "[WINDOWS] Variable 'ANDROID_HOME' actualizada a nivel de usuario: $SdkRoot" -ForegroundColor Green
        } else {
            Write-Host "[WINDOWS] Se mantuvo la variable 'ANDROID_HOME' sin cambios." -ForegroundColor Yellow
        }

        # b) Confirmar si desea añadir o actualizar las rutas del SDK en el Path de usuario
        $updatePath = Get-UserConfirmation -PromptMessage "¿Desea añadir o actualizar las rutas del SDK ('cmdline-tools/latest/bin', 'platform-tools', 'emulator') en el 'Path' de usuario?"
        if ($updatePath) {
            $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
            if ([string]::IsNullOrWhiteSpace($userPath)) { $userPath = "" }
            $pathModified = $false
            foreach ($p in $pathsToAdd) {
                if ($userPath -notlike "*$p*") {
                    $userPath = "$userPath;$p".TrimStart(';')
                    $pathModified = $true
                }
            }

            if ($pathModified) {
                [Environment]::SetEnvironmentVariable("Path", $userPath, "User")
                Write-Host "[WINDOWS] Rutas del SDK añadidas/actualizadas en el 'Path' de usuario." -ForegroundColor Green
            } else {
                Write-Host "[WINDOWS] Las rutas del SDK ya se encontraban presentes en el 'Path' de usuario." -ForegroundColor Gray
            }
        } else {
            Write-Host "[WINDOWS] Se mantuvo la variable 'Path' sin cambios." -ForegroundColor Yellow
        }

        if ($updateHome -or $updatePath) {
            Write-Host "          Reinicia tu terminal para que todas las sesiones reconozcan los cambios." -ForegroundColor Yellow
        }
    } else {
        # Configuración automática y silenciosa para instalación limpia
        [Environment]::SetEnvironmentVariable("ANDROID_HOME", $SdkRoot, "User")
        $env:ANDROID_HOME = $SdkRoot

        $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
        if ([string]::IsNullOrWhiteSpace($userPath)) { $userPath = "" }
        $pathModified = $false
        foreach ($p in $pathsToAdd) {
            if ($userPath -notlike "*$p*") {
                $userPath = "$userPath;$p".TrimStart(';')
                $pathModified = $true
            }
        }

        if ($pathModified) {
            [Environment]::SetEnvironmentVariable("Path", $userPath, "User")
        }

        Write-Host "[WINDOWS] Variable 'ANDROID_HOME' y 'Path' de usuario configuradas automáticamente." -ForegroundColor Green
        Write-Host "          Reinicia tu terminal para que todas las sesiones reconozcan los binarios." -ForegroundColor Yellow
    }
} else {
    Write-Host "[LINUX / MACOS] Añade las siguientes líneas a tu archivo de perfil (~/.bashrc, ~/.zshrc o ~/.profile):" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "export ANDROID_HOME=`"$SdkRoot`"" -ForegroundColor White
    Write-Host "export PATH=`"`$ANDROID_HOME/cmdline-tools/latest/bin:`$ANDROID_HOME/platform-tools:`$ANDROID_HOME/emulator:`$PATH`"" -ForegroundColor White
    Write-Host ""
    Write-Host "Sugerencia: Ejecuta 'source ~/.bashrc' (o tu shell correspondiente) tras guardar los cambios." -ForegroundColor Cyan
}
