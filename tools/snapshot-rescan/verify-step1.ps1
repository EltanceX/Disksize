param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$RootInstall,
    [switch]$SkipBuild,
    [string]$Output
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Load the Android development environment first.' }
if (-not $Output) { $Output = Join-Path $repo ('.local/snapshot-rescan-step1-' + [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssZ')) }
if (Test-Path -LiteralPath $Output) { throw 'Output directory already exists; choose a new run directory.' }
$results = (New-Item -ItemType Directory -Path $Output).FullName
$testApk = Join-Path $repo 'build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk'

function Adb([string[]]$Command) {
    $value = & $adb -s $Serial @Command
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($Command -join ' ')" }
    return $value
}
function Package-Path([string]$Package) {
    $value = & $adb -s $Serial shell pm path $Package
    if ($LASTEXITCODE -ne 0 -or -not $value) { return $null }
    $path = @($value)[0].Trim()
    if (-not $path.StartsWith('package:')) { throw "Unexpected APK path for $Package" }
    return $path.Substring(8)
}
function Package-Hash([string]$Path) {
    $line = (Adb @('shell', 'sha256sum', $Path)) -join "`n"
    return ($line -split '\s+')[0]
}
function Data-Fingerprints {
    $names = @(Adb @('shell', 'run-as', 'com.example.disksize', 'ls', 'no_backup'))
    $files = @($names | ForEach-Object { $_.Trim() } | Where-Object { $_ -in @('scan-index.db', 'scan-index.db-wal', 'scan-session.json') } | Sort-Object)
    if ($files.Count -eq 0) { return '' }
    return (Adb (@('shell', 'run-as', 'com.example.disksize', 'sha256sum') + @($files | ForEach-Object { "no_backup/$_" }))) -join "`n"
}

if (-not $SkipBuild) {
    & (Join-Path $repo 'android/gradlew.bat') -p (Join-Path $repo 'android') :app:testDebugUnitTest :app:assembleDebugAndroidTest `
        -PdisksizeBenchmark=false '-Ptarget-platform=android-arm64,android-x64' --console=plain 2>&1 |
        Tee-Object -FilePath (Join-Path $results 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Contract tests or test APK build failed.' }
}
if (-not (Test-Path -LiteralPath $testApk)) { throw 'Instrumentation test APK is missing.' }
$appPath = Package-Path 'com.example.disksize'
if (-not $appPath) { throw 'Install a compatible normal development APK first; this runner never installs the main APK.' }
$appHash = Package-Hash $appPath
$names = @(Adb @('shell', 'run-as', 'com.example.disksize', 'ls', 'no_backup'))
if ($names -contains 'scan-session.json') {
    $journal = (Adb @('shell', 'run-as', 'com.example.disksize', 'cat', 'no_backup/scan-session.json')) -join "`n" | ConvertFrom-Json
    if ($journal.active -or $journal.state -in @('preparing', 'running', 'scanning', 'finalizing', 'cancelling')) {
        throw 'A normal scan is active; refusing to interrupt it.'
    }
}
$previousTest = Package-Path 'com.example.disksize.test'
$previousTestHash = if ($previousTest) { Package-Hash $previousTest } else { $null }
$backup = Join-Path $results 'previous-test.apk'
if ($previousTest) { Adb @('pull', $previousTest, $backup) }
Adb @('shell', 'am', 'force-stop', 'com.example.disksize')
$before = Data-Fingerprints
$testTouched = $false
try {
    $testTouched = $true
    & (Join-Path $repo 'tools/install-android-apk.ps1') -Serial $Serial -Apk $testApk -UseRoot:$RootInstall
    $classes = 'com.example.disksize.index.SnapshotRescanBaselineTest,com.example.disksize.index.SnapshotStoreTest'
    $lines = & $adb -s $Serial shell am instrument -w -r -e class $classes com.example.disksize.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $exit = $LASTEXITCODE
    $lines | Tee-Object -FilePath (Join-Path $results 'instrumentation.log')
    $text = $lines -join "`n"
    if ($exit -ne 0 -or $text -notmatch '(?m)^OK \((\d+) tests?\)' -or
        $text -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=') {
        throw 'Baseline or existing snapshot regression tests failed; inspect instrumentation.log.'
    }
    $deviceTests = [int][regex]::Match($text, '(?m)^OK \((\d+) tests?\)').Groups[1].Value
} finally {
    Adb @('shell', 'am', 'force-stop', 'com.example.disksize')
    if ($testTouched) {
        if ($previousTest) {
            & (Join-Path $repo 'tools/install-android-apk.ps1') -Serial $Serial -Apk $backup -UseRoot:$RootInstall
            if ((Package-Hash (Package-Path 'com.example.disksize.test')) -ne $previousTestHash) { throw 'Previous test APK was not restored exactly.' }
        } else {
            Adb @('uninstall', 'com.example.disksize.test')
        }
    }
    if ((Package-Hash (Package-Path 'com.example.disksize')) -ne $appHash) { throw 'Main APK changed unexpectedly.' }
    if ((Data-Fingerprints) -ne $before) { throw 'Normal snapshot data changed unexpectedly.' }
}
$unitFiles = @(Get-ChildItem (Join-Path $repo 'build/app/test-results/testDebugUnitTest') -Filter 'TEST-*.xml')
$unitTests = 0
foreach ($file in $unitFiles) {
    $xml = [xml](Get-Content -LiteralPath $file.FullName -Raw)
    if ([int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0) { throw 'Unit test report contains failures.' }
    $unitTests += [int]$xml.testsuite.tests
}
[pscustomobject]@{
    schemaChanged = $false
    mainApkReplaced = $false
    mainApkSha256 = $appHash
    normalSnapshotDataUnchanged = $true
    previousTestApkRestored = [bool]$previousTest
    unitTests = $unitTests
    deviceTests = $deviceTests
    timestampUtc = [DateTimeOffset]::UtcNow.ToString('o')
} | ConvertTo-Json | Tee-Object -FilePath (Join-Path $results 'summary.json')
