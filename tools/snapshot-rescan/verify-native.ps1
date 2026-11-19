param([Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$Output, [switch]$RootTests)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$cmake = Join-Path $env:ANDROID_HOME 'cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $env:ANDROID_HOME 'cmake/3.22.1/bin/ninja.exe'
$toolchain = Join-Path $env:ANDROID_HOME 'ndk/28.2.13676358/build/cmake/android.toolchain.cmake'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (Test-Path -LiteralPath $Output) { throw 'Native output directory already exists.' }
$logs = (New-Item -ItemType Directory -Path $Output).FullName
foreach ($abi in @('arm64-v8a', 'x86_64')) {
    $build = Join-Path $repo ".local/phase2/native-$abi"
    & $cmake -S (Join-Path $repo 'native_tests') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=RelWithDebInfo
    if ($LASTEXITCODE -ne 0) { throw 'Native configure failed.' }
    & $cmake --build $build 2>&1 | Tee-Object -FilePath (Join-Path $logs "build-$abi.log")
    if ($LASTEXITCODE -ne 0) { throw 'Native build failed.' }
}
$abi = (& $adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
if ($LASTEXITCODE -ne 0 -or $abi -notin @('arm64-v8a', 'x86_64')) { throw 'Unsupported device ABI.' }
$binaries = @('disksize_subtree_tests', 'disksize_root_pipe_tests')
if ($RootTests) { $binaries += @('disksize_native_tests', 'disksize_stream_tests') }
$remote = '/data/local/tmp/disksize-phase2-bin'
& $adb -s $Serial shell mkdir -p $remote
if ($LASTEXITCODE -ne 0) { throw 'Native directory creation failed.' }
$total = 0
foreach ($binary in $binaries) {
    & $adb -s $Serial push (Join-Path $repo ".local/phase2/native-$abi/$binary") "$remote/$binary"
    if ($LASTEXITCODE -ne 0) { throw 'Native push failed.' }
    & $adb -s $Serial shell chmod 755 "$remote/$binary"
    if ($LASTEXITCODE -ne 0) { throw 'Native chmod failed.' }
    $command = if ($RootTests) { "su -c '$remote/$binary'" } else { "$remote/$binary" }
    $lines = @(& $adb -s $Serial shell $command 2>&1)
    $exit = $LASTEXITCODE
    $lines | Tee-Object -FilePath (Join-Path $logs "$binary.log")
    if ($exit -ne 0) { throw "Native suite failed: $binary" }
    $result = @($lines | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object { $_.suite -or $_.type -eq 'test-summary' })
    if ($result.Count -ne 1 -or $result[0].failed -ne 0) { throw 'Missing native summary.' }
    $total += if ($result[0].PSObject.Properties['tests']) { [int]$result[0].tests } else { [int]$result[0].passed }
}
[pscustomobject]@{ tests = $total; failed = 0; rootSuites = [bool]$RootTests } | ConvertTo-Json |
    Tee-Object -FilePath (Join-Path $logs 'summary.json')
