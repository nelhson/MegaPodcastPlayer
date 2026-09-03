<#
.SYNOPSIS
    Creates MegaPodcastPlayer's release signing keystore and the keystore.properties that points at it.

.DESCRIPTION
    A release build fails without a signing key (see docs/RELEASE_SIGNING.md), and the phone and
    watch APKs must be signed with the *same* key — the Wearable Data Layer routes on package name
    plus signing certificate, so a mismatch silently breaks the watch remote.

    This script prompts once for a password, generates a 4096-bit RSA key valid for ~27 years, and
    writes keystore.properties at the repository root. Both files are git-ignored.

    Run it from the repository root:

        powershell -ExecutionPolicy Bypass -File tools\create-release-keystore.ps1

    Losing this keystore means every installed copy of the app has to be uninstalled before it can
    be updated, because Android will not accept an APK signed with a different certificate. Back it
    up somewhere you will still have in five years.

.PARAMETER Alias
    Key alias inside the keystore. Only change this if you are regenerating from scratch.

.PARAMETER KeystoreFile
    Where to write the keystore, relative to the repository root.
#>
[CmdletBinding()]
param(
    [string] $Alias = 'megapodcastplayer',
    [string] $KeystoreFile = 'megapodcastplayer-release.jks'
)

$ErrorActionPreference = 'Stop'

# Repository root is the parent of tools/, so the script works from any working directory.
$repoRoot = Split-Path -Parent $PSScriptRoot
$keystorePath = Join-Path $repoRoot $KeystoreFile
$propertiesPath = Join-Path $repoRoot 'keystore.properties'

if (Test-Path $keystorePath) {
    throw "$keystorePath already exists. Delete it first if you really mean to replace the key — " +
          "every device with the old key installed will need a reinstall."
}

# keytool ships with the JDK. JAVA_HOME is not set globally on this machine, so fall back to the
# standalone JDK that Gradle uses.
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = "$env:USERPROFILE\.jdks\openjdk-23.0.2" }
$keytool = Join-Path $javaHome 'bin\keytool.exe'
if (-not (Test-Path $keytool)) {
    throw "keytool not found at $keytool. Set JAVA_HOME to a JDK and re-run."
}

$secure = Read-Host -Prompt 'Choose a keystore password (min 6 characters)' -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
if ($plain.Length -lt 6) { throw 'keytool requires at least 6 characters.' }

# -dname is supplied so keytool never drops into its interactive questionnaire. The distinguished
# name is cosmetic for a sideloaded app: nothing verifies it.
& $keytool -genkeypair -v `
    -keystore $keystorePath `
    -alias $Alias `
    -keyalg RSA -keysize 4096 -validity 10000 `
    -storepass $plain -keypass $plain `
    -dname "CN=MegaPodcastPlayer, O=Boris Veriga, C=MD"
if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE." }

# storeFile is resolved relative to the repository root by the build (rootProject.file(...)).
$lines = @(
    "storeFile=$KeystoreFile",
    "storePassword=$plain",
    "keyAlias=$Alias",
    "keyPassword=$plain"
)
Set-Content -Path $propertiesPath -Value $lines -Encoding utf8

Write-Host ''
Write-Host "Wrote $keystorePath"
Write-Host "Wrote $propertiesPath"
Write-Host 'Both are git-ignored. Back up the .jks and the password before you forget them.'
