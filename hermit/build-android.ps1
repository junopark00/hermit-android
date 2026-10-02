#requires -Version 5.1
<#
.SYNOPSIS
Builds the Hermit Android APK on Windows (optional helper; Gradle works on its own as well).
.DESCRIPTION
Finds a JDK 21 and the Android SDK, runs the source checks, builds the nonRoot APK with Gradle and
copies it to build\hermit-android\Hermit-android-<git describe>.apk.

JDK:         JAVA_HOME, else a JDK in a common location (Android Studio's bundled JBR, Microsoft
             OpenJDK, Eclipse Adoptium, %LOCALAPPDATA%\Android\jdk-21), else java on PATH.
Android SDK: ANDROID_HOME / ANDROID_SDK_ROOT, else sdk.dir in local.properties, else
             %LOCALAPPDATA%\Android\Sdk. The NDK version in app\build.gradle must be installed.

Release builds are signed with the key described by keystore.properties in the repository root
(git-ignored). If that file is missing, the script uses <KeyDir>\keystore.properties, and when the
key directory is empty it creates a new key there (keytool, RSA 4096, PKCS12). KeyDir defaults to
.\signing in the repository, which is git-ignored. Back that folder up: an APK signed with a
different key cannot update an installed Hermit without uninstalling it, which deletes the pairing
and settings.
.EXAMPLE
./hermit/build-android.ps1
./hermit/build-android.ps1 -Install        # also installs over adb (USB or wireless debugging)
./hermit/build-android.ps1 -SmokeTest      # installs, starts the app and fails if it crashes
./hermit/build-android.ps1 -DebugBuild     # debug build (unoptimised native code; for crash hunting)
./hermit/build-android.ps1 -KeyDir D:\keys\hermit -SignerName 'Jane Doe' -SignerOrganization 'Example'

Before Gradle runs, source checks stop the build on mistakes that compile but crash at runtime:
  hermit\check-view-types.py  views looked up in Java have the class the layouts give them (a
                              layout override that changes a view's class crashes the screen)
  hermit\check-strings.py     translations keep their placeholders; getString() calls pass them
  hermit\check-prefs.py       each preference key is read and written with one type
#>
[CmdletBinding()]
param(
    [switch]$DebugBuild,
    [switch]$Install,
    # Install, start the app on the device and fail when it crashes within a few seconds
    [switch]$SmokeTest,
    # adb serial (adb devices) when more than one device is connected
    [string]$Device,
    # Folder holding the release key and its keystore.properties (default: .\signing, git-ignored)
    [string]$KeyDir,
    # Certificate subject of a newly created key: CN=<SignerName>[, O=<SignerOrganization>]
    [string]$SignerName = 'Hermit',
    [string]$SignerOrganization
)
$ErrorActionPreference = 'Stop'
# $PSScriptRoot is not available in parameter defaults on Windows PowerShell 5.1.
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $KeyDir) { $KeyDir = Join-Path $repo 'signing' }
# Absolute, so keytool and Gradle (which resolves a relative storeFile against app/) agree
$KeyDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($KeyDir)
$localProps = Join-Path $repo 'local.properties'

function Find-Jdk {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return $env:JAVA_HOME }
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr'),
        (Join-Path $env:LOCALAPPDATA 'Android\jdk-21')
    )
    foreach ($pattern in @('Microsoft\jdk-21*', 'Eclipse Adoptium\jdk-21*', 'Java\jdk-21*')) {
        $candidates += @(Get-ChildItem -Path (Join-Path $env:ProgramFiles $pattern) -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object { $_.FullName })
    }
    foreach ($dir in $candidates) {
        if ($dir -and (Test-Path -LiteralPath (Join-Path $dir 'bin\java.exe'))) { return $dir }
    }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) { return (Split-Path (Split-Path $java.Source -Parent) -Parent) }
    return $null
}

function Find-AndroidSdk {
    foreach ($dir in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($dir -and (Test-Path -LiteralPath $dir)) { return $dir }
    }
    if (Test-Path -LiteralPath $localProps) {
        $line = Get-Content -LiteralPath $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $dir = ($line -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'
            if (Test-Path -LiteralPath $dir) { return $dir }
        }
    }
    $dir = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $dir) { return $dir }
    return $null
}

$jdk = Find-Jdk
if (-not $jdk) { throw 'No JDK found. Install JDK 21 (or Android Studio) and set JAVA_HOME.' }
$env:JAVA_HOME = $jdk
$sdk = Find-AndroidSdk
if (-not $sdk) { throw 'No Android SDK found. Install it (Android Studio or the command-line tools) and set ANDROID_HOME.' }
$env:ANDROID_HOME = $sdk
Write-Host "JDK: $jdk"
Write-Host "Android SDK: $sdk"

# Gradle reads the SDK location from local.properties (git-ignored).
if (-not (Test-Path -LiteralPath $localProps)) {
    # Property files escape backslashes and the drive colon (lint's PropertyEscape)
    Set-Content -LiteralPath $localProps -Encoding ascii -Value ('sdk.dir=' + ($sdk -replace '\\', '\\' -replace ':', '\:'))
}

if (-not $DebugBuild) {
    $keystoreProps = Join-Path $repo 'keystore.properties'
    if (-not (Test-Path -LiteralPath $keystoreProps)) {
        $keyStore = Join-Path $KeyDir 'hermit-release.jks'
        $savedProps = Join-Path $KeyDir 'keystore.properties'
        if (-not (Test-Path -LiteralPath $savedProps)) {
            if (Test-Path -LiteralPath $keyStore) { throw "$keyStore exists without keystore.properties next to it" }
            New-Item -ItemType Directory -Force -Path $KeyDir | Out-Null
            $bytes = New-Object byte[] 24
            [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
            $password = [Convert]::ToBase64String($bytes) -replace '[+/=]', 'x'
            # keytool's distinguished-name syntax: commas and backslashes in a value are escaped
            $escape = { param($v) $v -replace '\\', '\\' -replace ',', '\,' }
            $dname = 'CN=' + (& $escape $SignerName)
            if ($SignerOrganization) { $dname += ', O=' + (& $escape $SignerOrganization) }
            & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -v -keystore $keyStore -storetype PKCS12 `
                -alias hermit -keyalg RSA -keysize 4096 -validity 36500 -storepass $password -keypass $password `
                -dname $dname | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
            Set-Content -LiteralPath $savedProps -Encoding ascii -Value @(
                ('storeFile=' + ($keyStore -replace '\\', '/')),
                "storePassword=$password",
                'keyAlias=hermit',
                "keyPassword=$password")
            Write-Host "Created a release signing key in $KeyDir ($dname). Back this folder up."
        }
        Copy-Item -LiteralPath $savedProps -Destination $keystoreProps
    }
}

# Mistakes that compile and crash at runtime only; stop here instead
$python = Get-Command python -ErrorAction SilentlyContinue
if ($python) {
    $env:PYTHONUTF8 = '1'
    foreach ($check in @('check-view-types.py', 'check-strings.py', 'check-prefs.py')) {
        & $python.Source (Join-Path $repo "hermit\$check")
        if ($LASTEXITCODE -ne 0) { throw "$check failed (see the ERROR lines above)" }
    }
} else {
    Write-Warning 'python not found: skipped the source checks'
}

$task = if ($DebugBuild) { 'assembleNonRootDebug' } else { 'assembleNonRootRelease' }
Push-Location $repo
try {
    & (Join-Path $repo 'gradlew.bat') $task
    if ($LASTEXITCODE -ne 0) { throw "Gradle $task failed ($LASTEXITCODE)" }
    # Windows PowerShell 5.1 turns native stderr into terminating errors under 'Stop'; a git
    # warning must not throw away a finished build.
    $ErrorActionPreference = 'Continue'
    $describe = (git describe --tags --always --dirty 2>$null)
    $ErrorActionPreference = 'Stop'
    if (-not $describe) { $describe = 'unknown' }
} finally {
    Pop-Location
}

$variant = if ($DebugBuild) { 'debug' } else { 'release' }
$apk = Get-ChildItem -LiteralPath (Join-Path $repo "app\build\outputs\apk\nonRoot\$variant") -Filter '*.apk' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "No APK found for $variant" }
$outDir = Join-Path $repo 'build\hermit-android'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$suffix = if ($DebugBuild) { '-debug' } else { '' }
$out = Join-Path $outDir "Hermit-android-$describe$suffix.apk"
Copy-Item -LiteralPath $apk.FullName -Destination $out -Force
Write-Host "APK: $out ($([math]::Round((Get-Item $out).Length / 1MB, 1)) MB)"

if ($Install -or $SmokeTest) {
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not $Device) {
        $devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" } | ForEach-Object { ($_ -split "`t")[0] })
        if ($devices.Count -eq 0) { throw 'No device connected over adb (USB or wireless debugging)' }
        $Device = $devices[0]
    }
    & $adb -s $Device install -r $out
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
}

if ($SmokeTest) {
    # Start the PC list (the launcher activity) cold and watch the crash log for a few seconds
    $package = if ($DebugBuild) { 'io.github.junopark00.hermit.debug' } else { 'io.github.junopark00.hermit' }
    & $adb -s $Device shell am force-stop $package
    & $adb -s $Device logcat -b main -b crash -c
    & $adb -s $Device shell am start -W -n "$package/com.junopark.hermit.PcView" | Out-Null
    Start-Sleep -Seconds 8
    # Only this app's crashes (the buffer is shared with every other app)
    $crash = @(& $adb -s $Device logcat -d -b crash)
    $ours = @($crash | Where-Object { $_ -match ('Process: ' + [regex]::Escape($package) + ',') })
    $running = [string](& $adb -s $Device shell pidof $package)
    if ($ours.Count -gt 0 -or -not $running.Trim()) {
        $crash | Select-Object -First 40 | ForEach-Object { Write-Host $_ }
        throw "Smoke test failed: $package crashed or is not running after start"
    }
    Write-Host "Smoke test passed: $package started and kept running on $Device"
}
