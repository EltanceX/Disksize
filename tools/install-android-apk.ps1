param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$Apk,
    [switch]$UseRoot
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Load the Android development environment first.' }
$resolvedApk = (Resolve-Path -LiteralPath $Apk).Path
if (-not $UseRoot) {
    & $adb -s $Serial install -r $resolvedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK installation failed; check the device installation confirmation.' }
    return
}
# Unique, fixed-format device path; never interpolate a host path into su.
$stagedApk = '/data/local/tmp/disksize-install-' + [Guid]::NewGuid().ToString('N') + '.apk'
try {
    & $adb -s $Serial push $resolvedApk $stagedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK staging failed.' }
    $output = & $adb -s $Serial shell "su -c 'pm install -r $stagedApk'"
    $output | Write-Output
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch '(?m)^Success\s*$') { throw 'Root APK installation failed.' }
} finally {
    & $adb -s $Serial shell rm -f $stagedApk
}
