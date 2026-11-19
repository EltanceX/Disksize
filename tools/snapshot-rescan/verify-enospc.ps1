param([Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$Output,
    [ValidatePattern('^/[a-zA-Z0-9_./-]+$')][string]$Busybox = '/data/adb/ksu/bin/busybox')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (Test-Path -LiteralPath $Output) { throw 'Output directory already exists.' }
$results = (New-Item -ItemType Directory -Path $Output).FullName
$remote = '/data/local/tmp/rescan-enospc-' + [Guid]::NewGuid().ToString('N')
if ($remote -notmatch '^/data/local/tmp/rescan-enospc-[0-9a-f]{32}$') { throw 'Invalid owned test path.' }
function Adb([string[]]$Arguments) {
    $lines = & $adb -s $Serial @Arguments 2>&1
    if ($Arguments[0] -eq 'shell' -and ($Arguments -join ' ') -match 'unshare -m') {
        $lines | Set-Content -LiteralPath (Join-Path $results 'device.log')
    }
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($lines -join "`n")" }
    return $lines
}
$uid = [int]((Adb @('shell', 'run-as', 'com.example.disksize', 'id', '-u')) -join '').Trim()
$script = @'
set -eu
__BUSYBOX__ mount --make-rprivate /
mount -t tmpfs -o size=12m,mode=0777 tmpfs __ROOT__/fs
trap 'umount __ROOT__/fs' EXIT
export CLASSPATH=__ROOT__/main.apk:__ROOT__/test.apk
app_process /system/bin com.example.disksize.index.SnapshotRescanEnospcMain __ROOT__/fs __UID__
'@.Replace('__ROOT__', $remote).Replace('__UID__', [string]$uid).Replace('__BUSYBOX__', $Busybox)
[IO.File]::WriteAllText((Join-Path $results 'run.sh'), $script.Replace("`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
try {
    Adb @('shell', 'mkdir', '-p', "$remote/fs")
    Adb @('push', (Join-Path $repo 'build/app/outputs/apk/debug/app-debug.apk'), "$remote/main.apk")
    Adb @('push', (Join-Path $repo 'build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk'), "$remote/test.apk")
    Adb @('push', (Join-Path $results 'run.sh'), "$remote/run.sh")
    $lines = Adb @('shell', "su -c 'unshare -m /system/bin/sh $remote/run.sh'")
    $lines | Set-Content -LiteralPath (Join-Path $results 'device.log')
    $line = @($lines | Where-Object { $_ -match '^RESCAN_ENOSPC ' })
    if ($line.Count -ne 1) { throw 'Missing bounded-filesystem result.' }
    $result = $line[0].Substring('RESCAN_ENOSPC '.Length) | ConvertFrom-Json
    if (-not $result.passed -or $result.uid -ne $uid) { throw 'ENOSPC acceptance failed.' }
    $result | ConvertTo-Json | Tee-Object -FilePath (Join-Path $results 'summary.json')
} finally {
    # The mount exists only in the terminated child namespace. Delete only this verified UUID root.
    Adb @('shell', "su -c 'rm -rf -- $remote'")
}
