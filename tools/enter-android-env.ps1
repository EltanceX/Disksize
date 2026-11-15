[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$FlutterSdk,

    [string]$AndroidSdk = $env:ANDROID_HOME,

    [string]$Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$resolvedFlutterSdk = (Resolve-Path -LiteralPath $FlutterSdk).Path
$resolvedAndroidSdk = (Resolve-Path -LiteralPath $AndroidSdk).Path
$resolvedJdk = (Resolve-Path -LiteralPath $Jdk).Path

$requiredExecutables = @(
    (Join-Path $resolvedFlutterSdk 'bin/flutter.bat'),
    (Join-Path $resolvedAndroidSdk 'platform-tools/adb.exe'),
    (Join-Path $resolvedJdk 'bin/java.exe')
)
foreach ($requiredExecutable in $requiredExecutables) {
    if (-not (Test-Path -LiteralPath $requiredExecutable -PathType Leaf)) {
        throw "Required executable not found: $requiredExecutable"
    }
}

$env:FLUTTER_ROOT = $resolvedFlutterSdk
$env:ANDROID_HOME = $resolvedAndroidSdk
$env:JAVA_HOME = $resolvedJdk
if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT = $resolvedAndroidSdk
}

$androidToolPaths = @(
    (Join-Path $resolvedFlutterSdk 'bin'),
    (Join-Path $resolvedAndroidSdk 'platform-tools'),
    (Join-Path $resolvedAndroidSdk 'cmdline-tools/latest/bin'),
    (Join-Path $resolvedJdk 'bin')
)
$remainingPaths = $env:PATH -split ';' | Where-Object { $_ -and $_ -notin $androidToolPaths }
$env:PATH = ($androidToolPaths + $remainingPaths) -join ';'

Write-Output 'Android development environment configured for this PowerShell process.'
