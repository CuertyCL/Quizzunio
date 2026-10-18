#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Lanzador del Gradle Wrapper para PowerShell 5.1+ (equivalente a gradlew.bat).
.DESCRIPTION
    Localiza un JVM, ejecuta gradle\wrapper\gradle-wrapper.jar con los argumentos
    recibidos y propaga el código de salida.
.EXAMPLE
    .\gradlew.ps1 build
.EXAMPLE
    .\gradlew.ps1 clean build --info -x test
.NOTES
    Si la ejecución de scripts está restringida, ejecútalo así:
        powershell -ExecutionPolicy Bypass -File .\gradlew.ps1 build
    o ajusta la política para el usuario actual:
        Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
#>

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs = @()
)

function Write-Stderr([string]$Message) {
    [Console]::Error.WriteLine($Message)
}

# --- Localizar APP_HOME y APP_BASE_NAME ---
$AppHome = $PSScriptRoot
if ([string]::IsNullOrEmpty($AppHome)) {
    $def = $MyInvocation.MyCommand.Definition
    if (-not [string]::IsNullOrEmpty($def)) { $AppHome = Split-Path -Parent $def }
}
if ([string]::IsNullOrEmpty($AppHome)) { $AppHome = (Get-Location).Path }
$AppHome = [System.IO.Path]::GetFullPath($AppHome)

$AppBaseName = [System.IO.Path]::GetFileNameWithoutExtension($MyInvocation.MyCommand.Name)
if ([string]::IsNullOrEmpty($AppBaseName)) { $AppBaseName = 'gradlew' }

# --- Verificar que exista el wrapper JAR ---
$WrapperJar = [System.IO.Path]::Combine($AppHome, 'gradle', 'wrapper', 'gradle-wrapper.jar')
if (-not (Test-Path -LiteralPath $WrapperJar -PathType Leaf)) {
    Write-Stderr "ERROR: no se encontró gradle-wrapper.jar en '$WrapperJar'."
    Write-Stderr "Ejecute 'gradle wrapper' con un Gradle instalado para generarlo."
    exit 1
}

# --- Opciones JVM por defecto ---
$DefaultJvmOpts = @('-Xmx64m', '-Xms64m')

# --- Parser mínimo para JAVA_OPTS y GRADLE_OPTS (respeta comillas) ---
function Split-JvmOpts([string]$Raw) {
    if ([string]::IsNullOrWhiteSpace($Raw)) { return @() }
    $tokens = New-Object System.Collections.Generic.List[string]
    $sb = New-Object System.Text.StringBuilder
    $inQuotes = $false
    $quoteChar = [char]0
    foreach ($ch in $Raw.ToCharArray()) {
        if ($inQuotes) {
            if ($ch -eq $quoteChar) { $inQuotes = $false } else { [void]$sb.Append($ch) }
        } elseif ($ch -eq '"' -or $ch -eq "'") {
            $inQuotes = $true; $quoteChar = $ch
        } elseif ([char]::IsWhiteSpace($ch)) {
            if ($sb.Length -gt 0) { $tokens.Add($sb.ToString()); [void]$sb.Clear() }
        } else {
            [void]$sb.Append($ch)
        }
    }
    if ($sb.Length -gt 0) { $tokens.Add($sb.ToString()) }
    return $tokens.ToArray()
}

# --- Localizar java.exe (JAVA_HOME primero, luego PATH) ---
if (-not [string]::IsNullOrEmpty($env:JAVA_HOME)) {
    $candidate = [System.IO.Path]::Combine($env:JAVA_HOME, 'bin', 'java.exe')
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        $JavaExe = $candidate
    } else {
        Write-Stderr "ERROR: JAVA_HOME está definido pero '$candidate' no existe."
        Write-Stderr "Defina JAVA_HOME al directorio correcto del JDK/JRE."
        exit 1
    }
} else {
    $cmd = Get-Command 'java.exe' -CommandType Application -ErrorAction SilentlyContinue |
           Select-Object -First 1
    if ($cmd) {
        $JavaExe = $cmd.Source
    } else {
        Write-Stderr "ERROR: JAVA_HOME no está definido y 'java.exe' no está en el PATH."
        Write-Stderr "Instale un JDK/JRE y configure JAVA_HOME o agregue Java al PATH."
        exit 1
    }
}

# --- Construir argumentos para el wrapper ---
$ArgList = New-Object System.Collections.Generic.List[string]
foreach ($o in $DefaultJvmOpts)                  { $ArgList.Add($o) }
foreach ($o in (Split-JvmOpts $env:JAVA_OPTS))   { $ArgList.Add($o) }
foreach ($o in (Split-JvmOpts $env:GRADLE_OPTS)) { $ArgList.Add($o) }
$ArgList.Add("-Dorg.gradle.appname=$AppBaseName")
$ArgList.Add('-jar')
$ArgList.Add($WrapperJar)
foreach ($a in $GradleArgs) { $ArgList.Add([string]$a) }

if ($env:DEBUG) {
    Write-Host "DEBUG: JavaExe    = $JavaExe"
    Write-Host "DEBUG: WrapperJar = $WrapperJar"
    Write-Host "DEBUG: Args       = $($ArgList -join ' | ')"
}

# --- Ejecutar y propagar el código de salida ---
try {
    & $JavaExe @ArgList
    $exitCode = $LASTEXITCODE
} catch {
    Write-Stderr "ERROR: fallo al ejecutar el wrapper: $($_.Exception.Message)"
    exit 1
}

if ($null -eq $exitCode) { $exitCode = 0 }
exit $exitCode
