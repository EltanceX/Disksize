param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$RootInstall,
    [switch]$SkipBuild,
    [switch]$IncludeMerge,
    [switch]$IncludeService,
    [switch]$UiOnly,
    [switch]$RootTests,
    [switch]$AcceptanceOnly,
    [switch]$IncludeRegression,
    [switch]$DeleteOnly,
    [string]$DeleteFixture,
    [string]$DeleteSnapshot,
    [string]$DeleteVariants = 'legacy,production',
    [ValidateRange(1,5)][int]$DeleteRepeats = 1,
    [ValidateSet('realProcessDeathBeforeAndAfterCommitHasExactlyOneDurableVersion', 'cancellationDuringRealApplyReleasesReadersAndRollsBack', 'measuredSmallLargeAndMillionNodeHistoricalUpdates')]
    [string[]]$AcceptanceMethods,
    [string]$Output
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Load the Android development environment first.' }
if ($RootTests -and -not $IncludeService) { throw 'RootTests requires IncludeService.' }
if ($UiOnly -and (-not $IncludeService -or $RootTests)) { throw 'UiOnly requires IncludeService and must run separately from RootTests.' }
if ($AcceptanceOnly -and (-not $IncludeService -or $UiOnly)) { throw 'AcceptanceOnly requires IncludeService and excludes UiOnly.' }
if ($AcceptanceMethods -and -not $AcceptanceOnly) { throw 'AcceptanceMethods requires AcceptanceOnly.' }
if ($IncludeRegression -and (-not $IncludeService -or $UiOnly -or $AcceptanceOnly)) { throw 'IncludeRegression requires the isolated service suite.' }
if ($DeleteOnly -and ($IncludeService -or $IncludeMerge -or $UiOnly -or $RootTests -or $AcceptanceOnly -or $IncludeRegression)) { throw 'DeleteOnly runs separately from rescan suites.' }
if ($DeleteOnly -and (-not (Test-Path -LiteralPath $DeleteFixture -PathType Leaf) -or $DeleteSnapshot -notmatch '^[0-9a-f-]{36}$')) { throw 'DeleteOnly requires a frozen SQLite fixture and a snapshot UUID.' }
if ($DeleteVariants -notmatch '^(legacy|primary|cache32-primary|cache64-primary|production|memory|spill|spill16|spill32|spill64|spill128)(,(legacy|primary|cache32-primary|cache64-primary|production|memory|spill|spill16|spill32|spill64|spill128))*$') { throw 'Unknown deletion measurement variant.' }
if (-not $Output) { $Output = Join-Path $repo ('.local/snapshot-rescan-step' + $(if ($IncludeService) { '4-' } elseif ($IncludeMerge) { '3-' } else { '2-' }) + [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssZ')) }
if (Test-Path -LiteralPath $Output) { throw 'Output directory already exists; choose a new run directory.' }
$results = (New-Item -ItemType Directory -Path $Output).FullName
$mainApk = Join-Path $repo 'build/app/outputs/apk/debug/app-debug.apk'
$testApk = Join-Path $repo 'build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk'

function Adb([string[]]$Command) {
    $value = & $adb -s $Serial @Command
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($Command -join ' ')" }
    return $value
}
function Package-Path([string]$Package) {
    $value = & $adb -s $Serial shell pm path $Package
    if ($LASTEXITCODE -ne 0 -or -not $value) { return $null }
    $paths = @($value | Where-Object { $_.Trim().StartsWith('package:') })
    if ($paths.Count -ne 1) { throw "Only single-APK installations are supported: $Package" }
    return $paths[0].Trim().Substring(8)
}
function Package-Hash([string]$Path) {
    $line = (Adb @('shell', 'sha256sum', $Path)) -join "`n"
    return ($line -split '\s+')[0]
}
function Data-Fingerprints {
    $names = @(Adb @('shell', 'run-as', 'com.example.disksize', 'ls', 'no_backup'))
    $files = @($names | ForEach-Object { $_.Trim() } | Where-Object {
        $_ -in @('scan-index.db', 'scan-index.db-wal', 'scan-index.db-journal', 'scan-session.json')
    } | Sort-Object)
    if ($files.Count -eq 0) { return '' }
    return (Adb (@('shell', 'run-as', 'com.example.disksize', 'sha256sum') + @($files | ForEach-Object { "no_backup/$_" }))) -join "`n"
}
function Install([string]$Apk) {
    & (Join-Path $repo 'tools/install-android-apk.ps1') -Serial $Serial -Apk $Apk -UseRoot:$RootInstall
}

if (-not $SkipBuild) {
    $uiBuild = @()
    if ($UiOnly) {
        $uiTarget = Join-Path $repo 'integration_test/snapshot_rescan_test.dart'
        $uiDefine = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('RESCAN_UI_TEST_HOST=true'))
        $uiBuild = @("-Ptarget=$uiTarget", "-Pdart-defines=$uiDefine")
    }
    & (Join-Path $repo 'android/gradlew.bat') -p (Join-Path $repo 'android') :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest `
        -PdisksizeBenchmark=false "-PdisksizeRescanTestHost=$($IncludeService.ToString().ToLowerInvariant())" '-Ptarget-platform=android-arm64,android-x64' @uiBuild --console=plain 2>&1 |
        Tee-Object -FilePath (Join-Path $results 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Unit tests or normal test-host APK build failed.' }
}
foreach ($apk in @($mainApk, $testApk)) { if (-not (Test-Path -LiteralPath $apk)) { throw "Missing APK: $apk" } }
$appPath = Package-Path 'com.example.disksize'
if (-not $appPath) { throw 'A normal development APK must already be installed.' }
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
$mainBackup = Join-Path $results 'previous-main.apk'
$testBackup = Join-Path $results 'previous-test.apk'
Adb @('pull', $appPath, $mainBackup)
if ($previousTest) { Adb @('pull', $previousTest, $testBackup) }
if ((Get-FileHash -LiteralPath $mainBackup -Algorithm SHA256).Hash.ToLowerInvariant() -ne $appHash) { throw 'Main APK backup hash mismatch.' }
Adb @('shell', 'am', 'force-stop', 'com.example.disksize')
$before = Data-Fingerprints
$before | Set-Content -LiteralPath (Join-Path $results 'normal-data-before.txt')
$mainTouched = $false
$testTouched = $false
try {
    $mainTouched = $true
    Install $mainApk
    $testTouched = $true
    Install $testApk
    if ($DeleteOnly) {
        # Stream bytes as the ordinary app UID into a fixed test-only name. No root or normal DB writes.
        $start = [Diagnostics.ProcessStartInfo]::new($adb)
        foreach ($arg in @('-s', $Serial, 'exec-in', "run-as com.example.disksize sh -c 'cat > no_backup/snapshot-delete-input-v1.db'")) { $start.ArgumentList.Add($arg) }
        $start.UseShellExecute = $false
        $start.RedirectStandardInput = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $process = [Diagnostics.Process]::Start($start)
        $inputFile = [IO.File]::OpenRead((Resolve-Path -LiteralPath $DeleteFixture).Path)
        try { $inputFile.CopyTo($process.StandardInput.BaseStream) } finally { $inputFile.Dispose(); $process.StandardInput.Close() }
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw "Fixture stream failed: $stdout $stderr" }
        $fixtureHash = (Get-FileHash -LiteralPath $DeleteFixture -Algorithm SHA256).Hash.ToLowerInvariant()
        $deviceFixtureHash = ((Adb @('shell', 'run-as', 'com.example.disksize', 'sha256sum', 'no_backup/snapshot-delete-input-v1.db')) -split '\s+')[0]
        if ($deviceFixtureHash -ne $fixtureHash) { throw 'Deletion fixture hash mismatch.' }
    }
    # Baseline classes use UUID databases. Service tests require the fixed DE-only test-host build flag.
    $classes = 'com.example.disksize.index.SnapshotRescanStoreTest,com.example.disksize.index.SnapshotRescanBaselineTest,com.example.disksize.index.SnapshotStoreTest,com.example.disksize.index.IndexStoreTest'
    if ($IncludeMerge) { $classes += ',com.example.disksize.index.SnapshotRescanMergeTest' }
    if ($IncludeService) { $classes += ',com.example.disksize.index.SnapshotRescanServiceTest' }
    if ($IncludeRegression) { $classes += ',com.example.disksize.index.IndexDeviceTest,com.example.disksize.open.ExternalOpenTest' }
    if ($UiOnly) { $classes = 'com.example.disksize.index.SnapshotRescanUiDeviceTest' }
    if ($AcceptanceOnly) { $classes = 'com.example.disksize.index.SnapshotRescanAcceptanceTest' }
    if ($AcceptanceMethods) { $classes = ($AcceptanceMethods | ForEach-Object { "com.example.disksize.index.SnapshotRescanAcceptanceTest#$_" }) -join ',' }
    if ($DeleteOnly) { $classes = 'com.example.disksize.index.SnapshotDeletePerformanceTest,com.example.disksize.index.SnapshotStoreTest,com.example.disksize.index.SnapshotRescanStoreTest' }
    $arguments = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $classes)
    if ($RootTests) { $arguments += @('-e', 'snapshotRescanRoot', 'true') }
    if ($UiOnly) { $arguments += @('-e', 'snapshotRescanUi', 'true') }
    if ($AcceptanceOnly) { $arguments += @('-e', 'snapshotRescanAcceptance', 'true') }
    if ($DeleteOnly) { $arguments += @('-e', 'snapshotDelete', 'true', '-e', 'snapshotDeleteId', $DeleteSnapshot, '-e', 'snapshotDeleteVariants', $DeleteVariants, '-e', 'snapshotDeleteRepeats', "$DeleteRepeats") }
    $arguments += 'com.example.disksize.test/androidx.test.runner.AndroidJUnitRunner'
    $lines = & $adb -s $Serial @arguments 2>&1 | Tee-Object -FilePath (Join-Path $results 'instrumentation.log')
    $exit = $LASTEXITCODE
    $text = $lines -join "`n"
    if ($DeleteOnly) {
        (Adb @('shell', 'run-as', 'com.example.disksize', 'cat', 'no_backup/snapshot-delete-performance.json')) -join "`n" |
            Set-Content -LiteralPath (Join-Path $results 'delete-performance.json')
        if ('production' -in $DeleteVariants.Split(',')) {
            $download = [Diagnostics.ProcessStartInfo]::new($adb)
            foreach ($arg in @('-s', $Serial, 'exec-out', 'run-as', 'com.example.disksize', 'cat', 'no_backup/snapshot-delete-result-v1.db')) { $download.ArgumentList.Add($arg) }
            $download.UseShellExecute = $false
            $download.RedirectStandardOutput = $true
            $download.RedirectStandardError = $true
            $process = [Diagnostics.Process]::Start($download)
            $outputFile = [IO.File]::Create((Join-Path $results 'delete-result.db'))
            try { $process.StandardOutput.BaseStream.CopyTo($outputFile) } finally { $outputFile.Dispose() }
            $stderr = $process.StandardError.ReadToEnd(); $process.WaitForExit()
            if ($process.ExitCode -ne 0) { throw "Deletion result download failed: $stderr" }
        }
    }
    if ($AcceptanceOnly -and (-not $AcceptanceMethods -or 'measuredSmallLargeAndMillionNodeHistoricalUpdates' -in $AcceptanceMethods)) {
        $uid = [int]((Adb @('shell', 'run-as', 'com.example.disksize', 'id', '-u')) -join '').Trim()
        $userId = [int][Math]::Floor($uid / 100000)
        $performance = & $adb -s $Serial shell run-as com.example.disksize cat "/data/user_de/$userId/com.example.disksize/no_backup/snapshot-rescan-acceptance-performance.json" 2>$null
        if ($LASTEXITCODE -eq 0) { $performance | Set-Content -LiteralPath (Join-Path $results 'performance.json') }
    }
    if ($exit -ne 0 -or $text -notmatch '(?m)^OK \((\d+) tests?\)' -or
        $text -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=') {
        throw 'Persistence or regression tests failed; inspect instrumentation.log.'
    }
    $deviceTests = [int][regex]::Match($text, '(?m)^OK \((\d+) tests?\)').Groups[1].Value
    $deviceSkipped = [regex]::Matches($text, '(?m)^INSTRUMENTATION_STATUS_CODE: -(3|4)\s*$').Count
} finally {
    Adb @('shell', 'am', 'force-stop', 'com.example.disksize')
    $after = Data-Fingerprints
    $after | Set-Content -LiteralPath (Join-Path $results 'normal-data-after.txt')
    if ($after -ne $before) {
        throw 'Normal snapshot data changed. Old main APK was NOT restored to avoid a schema downgrade. Keep both APK backups and inspect before proceeding.'
    }
    # Restore the main APK first, even when restoring the old test APK subsequently fails.
    if ($mainTouched) {
        Install $mainBackup
        if ((Package-Hash (Package-Path 'com.example.disksize')) -ne $appHash) { throw 'Original main APK was not restored exactly.' }
    }
    if ($testTouched) {
        if ($previousTest) {
            Install $testBackup
            if ((Package-Hash (Package-Path 'com.example.disksize.test')) -ne $previousTestHash) { throw 'Previous test APK was not restored exactly.' }
        } else { Adb @('uninstall', 'com.example.disksize.test') }
    }
    if ((Data-Fingerprints) -ne $before) { throw 'Normal snapshot data changed during restoration.' }
    if ($DeleteOnly) {
        Adb @('shell', 'run-as', 'com.example.disksize', 'rm', '-f', 'no_backup/snapshot-delete-input-v1.db', 'no_backup/snapshot-delete-performance.json', 'no_backup/snapshot-delete-result-v1.db')
    }
    if ($IncludeService) {
        # Only the fixed, test-host-only DE database/journal. Never enumerate or remove ordinary data.
        $uid = [int]((Adb @('shell', 'run-as', 'com.example.disksize', 'id', '-u')) -join '').Trim()
        $userId = [int][Math]::Floor($uid / 100000)
        $testRoot = "/data/user_de/$userId/com.example.disksize/no_backup"
        $owned = @('snapshot-rescan-service-test-v1.db', 'snapshot-rescan-service-test-v1.db-wal',
            'snapshot-rescan-service-test-v1.db-shm', 'snapshot-rescan-service-test-v1.db-journal',
            'snapshot-rescan-service-test-v1.json', 'snapshot-rescan-service-test-v1.json.bak')
        $owned += @('snapshot-rescan-test-checkpoint.json', 'snapshot-rescan-test-checkpoint.reached', 'snapshot-rescan-acceptance-performance.json')
        Adb (@('shell', 'run-as', 'com.example.disksize', 'rm', '-f') + @($owned | ForEach-Object { "$testRoot/$_" }))
    }
}
$unitTests = 0
foreach ($file in @(Get-ChildItem (Join-Path $repo 'build/app/test-results/testDebugUnitTest') -Filter 'TEST-*.xml')) {
    $xml = [xml](Get-Content -LiteralPath $file.FullName -Raw)
    if ([int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0) { throw 'Unit test report contains failures.' }
    $unitTests += [int]$xml.testsuite.tests
}
if ($unitTests -eq 0) { throw 'Unit test reports are missing.' }
[pscustomobject]@{
    fixtureSchemaVersion = 5
    mergeSuiteIncluded = [bool]$IncludeMerge
    serviceSuiteIncluded = [bool]$IncludeService
    rootServiceTestsRequested = [bool]$RootTests
    flutterUiOnly = [bool]$UiOnly
    acceptanceOnly = [bool]$AcceptanceOnly
    regressionIncluded = [bool]$IncludeRegression
    deletionOnly = [bool]$DeleteOnly
    normalSnapshotDataUnchanged = $true
    originalMainApkRestored = $true
    mainApkSha256 = $appHash
    testedMainApkSha256 = (Get-FileHash -LiteralPath $mainApk -Algorithm SHA256).Hash.ToLowerInvariant()
    previousTestApkRestored = [bool]$previousTest
    unitTests = $unitTests
    deviceTests = $deviceTests
    devicePassed = $deviceTests - $deviceSkipped
    deviceSkipped = $deviceSkipped
    timestampUtc = [DateTimeOffset]::UtcNow.ToString('o')
} | ConvertTo-Json | Tee-Object -FilePath (Join-Path $results 'summary.json')
