param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [ValidatePattern('^/[A-Za-z0-9._/-]+$')][string]$BaselineRoot,
    [ValidatePattern('^/[A-Za-z0-9._/-]+$')][string]$BaselineShared
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logs = Join-Path $repository '.local/phase2'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
if (-not $AndroidSdk) { throw 'Load tools/enter-android-env.ps1 or supply -AndroidSdk first.' }
$cmake = Join-Path $AndroidSdk 'cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $AndroidSdk 'cmake/3.22.1/bin/ninja.exe'
$toolchain = Join-Path $AndroidSdk 'ndk/28.2.13676358/build/cmake/android.toolchain.cmake'
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
foreach ($required in @($cmake, $ninja, $toolchain, $adb)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing tool: $required" }
}

function Invoke-Device {
    param([string]$Command, [string]$LogName, [int]$ExpectedExit = 0, [switch]$Root)
    if ($Root) { $Command = "su -c '$Command'" }
    $lines = @(& $adb -s $Serial shell $Command 2>&1 | ForEach-Object { $_.ToString() })
    $code = $LASTEXITCODE
    $lines | Set-Content -Encoding utf8 (Join-Path $logs $LogName)
    if ($code -ne $ExpectedExit) { throw "$LogName returned $code (expected $ExpectedExit). See $logs" }
    return $lines
}
function Read-Summary {
    param([string[]]$Lines)
    $summaries = @($Lines | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object type -eq 'summary')
    if ($summaries.Count -ne 1) { throw 'Expected exactly one scan summary.' }
    return $summaries[0]
}

foreach ($abi in @('arm64-v8a', 'x86_64')) {
    $build = Join-Path $logs "native-$abi"
    & $cmake -S (Join-Path $repository 'native_tests') -B $build -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
        "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=RelWithDebInfo
    if ($LASTEXITCODE -ne 0) { throw "$abi configure failed" }
    & $cmake --build $build
    if ($LASTEXITCODE -ne 0) { throw "$abi build failed" }
}

$deviceAbi = (& $adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
if ($LASTEXITCODE -ne 0 -or $deviceAbi -notin @('arm64-v8a', 'x86_64')) { throw 'Expected a connected 64-bit Android device.' }
$remoteBin = '/data/local/tmp/disksize-phase2-bin'
Invoke-Device "mkdir -p $remoteBin" 'device-prepare.log' | Out-Null
& $adb -s $Serial push (Join-Path $logs "native-$deviceAbi/disksize_scan") `
    (Join-Path $logs "native-$deviceAbi/disksize_native_tests") "$remoteBin/"
if ($LASTEXITCODE -ne 0) { throw 'Push failed' }
Invoke-Device "chmod 755 $remoteBin/disksize_scan $remoteBin/disksize_native_tests" 'device-chmod.log' | Out-Null
$suite = Invoke-Device "$remoteBin/disksize_native_tests" 'native-tests.log' -Root
$suite | Write-Output
$testSummary = @($suite | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object type -eq 'test-summary')
if ($testSummary.Count -ne 1 -or $testSummary[0].failed -ne 0) { throw 'Native suite failed' }

if ($BaselineRoot) {
    # Reference tools emit numeric metadata only. Newlines/invalid UTF-8 in
    # filenames cannot split a path list or affect the independent totals.
    $reference = Invoke-Device "find $BaselineRoot -exec stat -c %f:%s:%b:%d:%i {} +" 'reference-stat.txt' -Root
    [uint64]$allocated = 0
    [uint64]$logical = 0
    $seen = [Collections.Generic.HashSet[string]]::new()
    foreach ($line in $reference) {
        $fields = $line.Split(':')
        if ($fields.Count -ne 5) { throw 'Invalid stat reference line' }
        $kind = [Convert]::ToUInt32($fields[0], 16) -band 0xf000
        if ($kind -eq 0x4000 -or $seen.Add("$($fields[3]):$($fields[4])")) {
            $allocated += [uint64]$fields[2] * 512
            if ($kind -eq 0x8000) { $logical += [uint64]$fields[1] }
        }
    }
    $rootScan = Read-Summary (Invoke-Device "$remoteBin/disksize_scan --root $BaselineRoot --nodes" 'baseline-root.ndjson' -Root)
    if ($rootScan.status -ne 'completed' -or $rootScan.uid -ne 0 -or
        $rootScan.nodes -ne 169 -or $rootScan.regular_entries -ne 136 -or $rootScan.unique_regular_files -ne 135 -or
        $rootScan.logical_bytes -ne 1073746077 -or $rootScan.logical_bytes -ne $logical -or
        $rootScan.allocated_bytes -ne $allocated -or $rootScan.nodes -ne $reference.Count) {
        throw 'Stage 0 root baseline differs from expected fixture/reference.'
    }
    $shellScan = Read-Summary (Invoke-Device "$remoteBin/disksize_scan --root $BaselineRoot --nodes" 'baseline-shell.ndjson' -ExpectedExit 2)
    if ($shellScan.status -ne 'partial' -or $shellScan.uid -ne 2000 -or $shellScan.root_complete -or
        $shellScan.nodes -ne 168 -or $shellScan.logical_bytes -ne 1073746069 -or $shellScan.issues -lt 1) {
        throw 'Stage 0 shell permission baseline differs from expected fixture.'
    }
    $rootScan | ConvertTo-Json -Compress | Write-Output
    $shellScan | ConvertTo-Json -Compress | Write-Output
}
if ($BaselineShared) {
    $shared = Read-Summary (Invoke-Device "$remoteBin/disksize_scan --root $BaselineShared --nodes" 'baseline-shared.ndjson')
    if ($shared.status -ne 'completed' -or $shared.nodes -ne 4 -or $shared.logical_bytes -ne 4105) {
        throw 'Stage 0 shared baseline differs from expected fixture.'
    }
    $shared | ConvertTo-Json -Compress | Write-Output
}
Write-Output "Phase 2 verification finished. Logs: $logs"
