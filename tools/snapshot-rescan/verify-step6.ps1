param([Parameter(Mandatory = $true)][string]$Serial,
    [switch]$RootInstall, [switch]$RootTests,
    [ValidatePattern('^/[a-zA-Z0-9_./-]+$')][string]$Busybox = '/data/adb/ksu/bin/busybox',
    [string]$Output)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $Output) { $Output = Join-Path $repo ('.local/snapshot-rescan-step6-' + [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssZ')) }
if (Test-Path -LiteralPath $Output) { throw 'Output directory already exists.' }
$results = (New-Item -ItemType Directory -Path $Output).FullName
Push-Location $repo
try {
    flutter analyze 2>&1 | Tee-Object -FilePath (Join-Path $results 'analyze.log')
    if ($LASTEXITCODE -ne 0) { throw 'Flutter analyze failed.' }
    flutter test --machine | Set-Content -LiteralPath (Join-Path $results 'flutter.jsonl')
    if ($LASTEXITCODE -ne 0) { throw 'Flutter tests failed.' }
    $runner = Join-Path $PSScriptRoot 'verify-step2.ps1'
    & $runner -Serial $Serial -RootInstall:$RootInstall -RootTests:$RootTests -IncludeMerge -IncludeService -IncludeRegression -Output (Join-Path $results 'core')
    & $runner -Serial $Serial -RootInstall:$RootInstall -IncludeService -AcceptanceOnly -SkipBuild -Output (Join-Path $results 'acceptance')
    if ($RootTests) {
        & (Join-Path $PSScriptRoot 'verify-enospc.ps1') -Serial $Serial -Busybox $Busybox -Output (Join-Path $results 'enospc')
    }
    & (Join-Path $PSScriptRoot 'verify-native.ps1') -Serial $Serial -RootTests:$RootTests -Output (Join-Path $results 'native')
    & $runner -Serial $Serial -RootInstall:$RootInstall -IncludeService -UiOnly -Output (Join-Path $results 'ui')
    & (Join-Path $repo 'android/gradlew.bat') -p (Join-Path $repo 'android') :app:assembleDebug :app:assembleProfile `
        -PdisksizeBenchmark=false -PdisksizeRescanTestHost=false "-Ptarget=$(Join-Path $repo 'lib/main.dart')" `
        '-Ptarget-platform=android-arm64,android-x64' --console=plain 2>&1 | Tee-Object -FilePath (Join-Path $results 'production-build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Normal optimized build failed.' }
    foreach ($variant in @('debug', 'profile')) {
        $config = Get-Content -LiteralPath (Join-Path $repo "build/app/generated/source/buildConfig/$variant/com/example/disksize/BuildConfig.java") -Raw
        if ($config -notmatch 'SCAN_BENCHMARK = false' -or $config -notmatch 'RESCAN_TEST_HOST = false') { throw 'Test flags remain enabled.' }
    }
    & (Join-Path $PSScriptRoot 'audit-normal-apk.ps1') -Apk (Join-Path $repo 'build/app/outputs/apk/profile/app-profile.apk') -Output (Join-Path $results 'native-audit')
    & (Join-Path $repo 'tools/lint-android.ps1') 2>&1 | Tee-Object -FilePath (Join-Path $results 'lint.log')
    git diff --check
    if ($LASTEXITCODE -ne 0) { throw 'Diff check failed.' }
    [pscustomobject]@{
        passed = $true
        rootSuitesIncluded = [bool]$RootTests
        profileApkSha256 = (Get-FileHash (Join-Path $repo 'build/app/outputs/apk/profile/app-profile.apk') -Algorithm SHA256).Hash.ToLowerInvariant()
        originalApplicationRestored = $true
        deploymentPerformed = $false
        timestampUtc = [DateTimeOffset]::UtcNow.ToString('o')
    } | ConvertTo-Json | Tee-Object -FilePath (Join-Path $results 'summary.json')
} finally { Pop-Location }
