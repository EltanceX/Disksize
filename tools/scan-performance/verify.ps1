param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [ValidateSet('Smoke','Baseline','Custom')][string]$Suite = 'Smoke',
    [ValidateSet('A','B','C','Replay')][string[]]$Mode = @('A','B','C','Replay'),
    [ValidateSet('ordinary','root')][string]$Backend = 'ordinary',
    [ValidateSet('internal','fuse','emulated-direct')][string]$View = 'internal',
    [ValidateSet(10000,100000,1000000)][int]$Size = 10000,
    [ValidatePattern('^(256|1024|4096)-(eager|paced)-(list|typed)(-lean)?$')][string[]]$ImportVariant,
    [ValidateSet('baseline','dedup','directories','optimized')][string[]]$FinalizeVariant,
    [ValidateSet('baseline','metadata','transport','optimized')][string[]]$PipelineVariant,
    [ValidateSet('empty','sparse-links','dense-links','deep-wide')][string]$ReplayFixture = 'empty',
    [ValidateRange(1,30)][int]$Repeats = 5,
    [ValidateRange(0,5)][int]$Warmups = 1,
    [string]$Output,
    [switch]$SkipBuild,
    [switch]$RootInstall,
    [switch]$SampleResources,
    [switch]$MeasureMetadata,
    [switch]$LiveAppData
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$local = Join-Path $repo '.local/scan-performance'
New-Item -ItemType Directory -Force -Path $local | Out-Null
if (-not $Output) { $Output = Join-Path $local ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)) }
$results = (New-Item -ItemType Directory -Path $Output).FullName
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Load .local/android-env.ps1 first.' }
$package = 'com.example.disksize'
$androidTest = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "$package.index.ScanPerformanceTest#runBenchmark"
$app = Join-Path $repo 'build/app/outputs/apk/profile/app-profile.apk'
$testApk = Join-Path $repo 'build/app/outputs/apk/androidTest/profile/app-profile-androidTest.apk'
$runnerId = [Guid]::NewGuid().ToString('N')
$deviceLock = $null
$runChangedApp = $false
$originalApk = Join-Path $results 'restore-app.apk'
$originalTestApk = Join-Path $results 'restore-test.apk'
$sampleHelper = "/data/local/tmp/disksize-perf-sample-$runnerId.sh"
$sampleMarker = "/data/local/tmp/disksize-perf-sample-$runnerId"
$resources = $null
$records = [Collections.Generic.List[object]]::new()
$cleanup = [Collections.Generic.List[object]]::new()

function Write-Json($Value, [string]$Name) {
    ConvertTo-Json -InputObject $Value -Depth 25 | Set-Content -LiteralPath (Join-Path $results $Name) -Encoding utf8
}
function Invoke-Adb([string[]]$Arguments) {
    $lines = @(& $adb -s $Serial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "ADB $($Arguments[0]) failed: $lines" }
    return $lines
}
function Install-App([string]$Path) {
    & (Join-Path $repo 'tools/install-android-apk.ps1') -Serial $Serial -Apk $Path -UseRoot:$RootInstall
}
function Build-Audit([string]$DeviceAbi) {
    $zip = [IO.Compression.ZipFile]::OpenRead($app)
    try {
        $models = @(foreach ($root in @('build/.cxx', 'android/app/.cxx')) {
            $path = Join-Path $repo $root
            if (Test-Path -LiteralPath $path) { Get-ChildItem -LiteralPath $path -Filter 'android_gradle_build.json' -Recurse }
        })
        foreach ($abi in @('arm64-v8a', 'x86_64')) {
            $entry = $zip.GetEntry("lib/$abi/libdisksize_jni.so")
            if ($null -eq $entry) { throw "APK is missing the $abi JNI library." }
            $packed = Join-Path $results "$abi-packaged.so"
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $packed)
            $match = $null
            foreach ($model in $models) {
                if ($model.Directory.Name -ne $abi) { continue }
                $commandsPath = Join-Path $model.Directory.FullName 'compile_commands.json'
                if (-not (Test-Path -LiteralPath $commandsPath)) { continue }
                $commands = @(Get-Content -LiteralPath $commandsPath -Raw | ConvertFrom-Json)
                $bridge = @($commands | Where-Object { (Split-Path $_.file -Leaf) -eq 'scan_bridge.cpp' })
                if ($bridge.Count -ne 1 -or $bridge[0].command -notmatch '(?<!\S)-DDISKSIZE_SCAN_BENCHMARK(?=\s|$)') { continue }
                $info = Get-Content -LiteralPath $model.FullName -Raw | ConvertFrom-Json
                $unstripped = @($info.libraries.PSObject.Properties.Value | Where-Object artifactName -eq 'disksize_jni')[0].output
                if (-not (Test-Path -LiteralPath $unstripped)) { continue }
                $readelf = Join-Path (Split-Path $info.toolchains.toolchain.cppCompilerExecutable -Parent) 'llvm-readelf.exe'
                $packedNotes = & $readelf -n $packed | Out-String
                $packedId = [regex]::Match($packedNotes, 'Build ID:\s*([0-9a-f]+)').Groups[1].Value
                $notes = & $readelf -n $unstripped | Out-String
                $objectId = [regex]::Match($notes, 'Build ID:\s*([0-9a-f]+)').Groups[1].Value
                if (-not $packedId -or $packedId -ne $objectId) { continue }
                $files = @('scan_bridge.cpp','stream_scanner.cpp','filesystem.cpp','scanner.cpp')
                $verified = @($commands | Where-Object { (Split-Path $_.file -Leaf) -in $files })
                if ($verified.Count -ne $files.Count) { throw "Missing compile records for $abi" }
                foreach ($record in $verified) {
                    $flags = [regex]::Matches($record.command, '(?<!\S)-O(?:[0-9zsg]|fast)?(?=\s|$)')
                    if ($flags.Count -eq 0 -or $flags[$flags.Count - 1].Value -ne '-O2' -or
                        $record.command -notmatch '(?<!\S)-g(?=\s|$)' -or $record.command -notmatch '\bDNDEBUG\b') {
                        throw "Unexpected effective flags for $abi $($record.file)"
                    }
                }
                $sections = & $readelf -S $unstripped | Out-String
                if ($sections -notmatch '\.debug_info\b') { throw "No native debug symbols for $abi" }
                $ninja = $info.buildTargetsCommandComponents[0]
                $link = @(& $ninja -C $model.Directory.FullName -t commands disksize_jni | Where-Object { $_ -match ' -shared ' }) | Select-Object -Last 1
                if ($LASTEXITCODE -ne 0 -or -not $link) { throw "Cannot recover JNI link command for $abi" }
                Copy-Item -LiteralPath $unstripped -Destination (Join-Path $results "$abi-unstripped.so")
                $match = [ordered]@{ abi = $abi; buildId = $packedId; selectedDeviceAbi = $abi -eq $DeviceAbi
                    compileCommands = $commands; linkCommand = $link }
                break
            }
            if (-not $match) { throw "Could not match the APK's $abi build ID to an optimized scanner build." }
            Write-Json $match "native-$abi.json"
        }
    } finally { $zip.Dispose() }
}
function Invoke-Case([hashtable]$Arguments, [string]$Name) {
    # Separate each process and ensure no service owns the benchmark DB before it is reset.
    Invoke-Adb @('shell','am','force-stop',$package) | Out-Null
    $command = @('shell','am','instrument','-w','-r','-e','class',$testClass,'-e','scanPerformance','true')
    foreach ($key in $Arguments.Keys) { $command += @('-e', $key, [string]$Arguments[$key]) }
    $lines = Invoke-Adb ($command + @($androidTest))
    $lines | Set-Content -LiteralPath (Join-Path $results "$Name.log") -Encoding utf8
    $reportLines = @($lines | Where-Object { $_ -match '^INSTRUMENTATION_STATUS: scanPerformance=' })
    if (($lines -join "`n") -notmatch 'OK \(1 test\)' -or $reportLines.Count -ne 1) {
        $lines | Select-Object -Last 22 | Write-Host
        throw "Instrumentation $Name did not finish successfully. See $results."
    }
    return ($reportLines[0] -replace '^INSTRUMENTATION_STATUS: scanPerformance=', '' | ConvertFrom-Json -AsHashtable)
}
function Read-Battery {
    $battery = (Invoke-Adb @('shell','dumpsys','battery')) -join "`n"
    $temp = [regex]::Match($battery, '(?m)^\s*temperature:\s*(-?\d+)\s*$')
    $power = [regex]::Match($battery, '(?m)^\s*status:\s*(\d+)\s*$')
    return [ordered]@{ temperatureTenthsC = if ($temp.Success) { [int]$temp.Groups[1].Value } else { $null }
        status = if ($power.Success) { [int]$power.Groups[1].Value } else { $null } }
}
function New-Group([string]$Name, [int]$Entries, [string]$Location, [string[]]$Cases, [int]$Count, [int]$Warm) {
    return @{ name = $Name; size = $Entries; location = $Location; cases = $Cases; repeats = $Count; warmups = $Warm }
}
function Run-Group([hashtable]$Group) {
    $id = [Guid]::NewGuid().ToString()
    $common = @{ fixture = $id; location = $Group.location; size = $Group.size }
    $cleanup.Add($common)
    $name = $Group.name
    $digests = @{}
    $scanResults = @{}
    if ($Group.location -ne 'live' -and @($Group.cases | Where-Object { $_ -notmatch '/Replay(/|$)' }).Count -gt 0) {
        Write-Host "Generating $($Group.size) entries for $name"
        $setup = Invoke-Case ($common + @{ action = 'setup' }) "$name-setup"
        Write-Json $setup "$name-setup.json"
    }
    for ($round = 0; $round -lt ($Group.warmups + $Group.repeats); $round++) {
        $warm = $round -lt $Group.warmups
        for ($offset = 0; $offset -lt $Group.cases.Count; $offset++) {
            $case = $Group.cases[($offset + $round) % $Group.cases.Count]
            $source, $surface, $modeName, $variant, $finalizer, $pipeline = $case -split '/'
            $namePart = "$name-r$round-$source-$surface-$modeName"
            if ($variant) { $namePart += "-$variant" }
            if ($finalizer) { $namePart += "-$finalizer" }
            if ($pipeline) { $namePart += "-pipeline-$pipeline" }
            Write-Host "Running $namePart (warmup=$warm)"
            $battery = Read-Battery
            $settings = @{ mode = $modeName; backend = $source; view = $surface
                allowLiveAppData = ([string][bool]$LiveAppData).ToLowerInvariant() }
            if ($variant) { $settings.importVariant = $variant }
            if ($finalizer) { $settings.finalizeVariant = $finalizer }
            if ($pipeline) { $settings.pipelineVariant = $pipeline }
            if ($MeasureMetadata) { $settings.measureMetadata = 'true' }
            if ($modeName -eq 'Replay') { $settings.replayFixture = $ReplayFixture.ToLowerInvariant() }
            $value = Invoke-Case ($common + $settings) $namePart
            $value.group = $name
            $value.round = $round
            $value.warmup = $warm
            $value.batteryBefore = $battery
            $value.batteryAfter = Read-Battery
            $value.entriesPerSecond = if ($value.elapsedMicros -gt 0) {
                [Math]::Round($value.nodes * 1e6 / $value.elapsedMicros, 2)
            } else { $null }
            Write-Json $value "$namePart.json"
            if ($value.resultSha256) {
                $recipe = $value.sample.recipeSha256
                if ($digests.ContainsKey($recipe) -and $digests[$recipe] -ne $value.resultSha256) {
                    throw "Finalized replay rows differ for $namePart. See $results."
                }
                $digests[$recipe] = $value.resultSha256
            }
            if ($pipeline) {
                $boundary = if ($modeName -eq 'C') { 'finalized' } else { 'before-dedup' }
                $comparison = "$source/$surface/$boundary"
                $result = (@($value.state, $value.complete, $value.nodes, $value.issues, $value.logicalBytes,
                    $value.allocatedBytes, $value.reportedAllocatedBeforeDedupBytes, $value.maxDepth,
                    $value.mountId, $value.filesystemMagic, $value.openFdsAtFinish,
                    ($value.nodesByKind | ConvertTo-Json -Compress)) -join '/')
                if ($scanResults.ContainsKey($comparison) -and $scanResults[$comparison] -ne $result) {
                    throw "Scan results differ for $namePart. See $results."
                }
                $scanResults[$comparison] = $result
            }
            $records.Add($value)
            Write-Host ("{0:N3}s, {1:N0} nodes" -f ($value.elapsedMicros / 1e6), $value.nodes)
        }
    }
    if ($Group.location -eq 'live') { $common.location = 'internal' }
    $removed = Invoke-Case ($common + @{ action = 'cleanup' }) "$name-cleanup"
    Write-Json $removed "$name-cleanup.json"
    [void]$cleanup.Remove($common)
}
function Write-Summary {
    $summary = @($records | Where-Object { -not $_.warmup } |
        Group-Object group, mode, backend, view, importVariant, finalizeVariant, pipelineVariant | ForEach-Object {
            $samples = @($_.Group.elapsedMicros | Sort-Object)
            $n = $samples.Count
            $first = $_.Group[0]
            $median = if ($n % 2 -eq 0) { ($samples[$n / 2 - 1] + $samples[$n / 2]) / 2 } else { $samples[[int][Math]::Floor($n / 2)] }
            [ordered]@{ group = $first.group; mode = $first.mode; backend = $first.backend; view = $first.view
                importVariant = $first.importVariant; finalizeVariant = $first.finalizeVariant
                pipelineVariant = $first.pipelineVariant
                nodes = $first.nodes; samples = $n; minMicros = $samples[0]
                medianMicros = $median; maxMicros = $samples[-1] }
        })
    Write-Json $summary 'summary.json'
}

try {
    $serialLock = $Serial -replace ':', '_'
    $deviceLock = [IO.File]::Open((Join-Path $local "device-$serialLock.lock"), 'OpenOrCreate', 'ReadWrite', 'None')
    Write-Host "Report directory: $results"
    if ($LiveAppData -and ($Suite -ne 'Custom' -or $Backend -ne 'root' -or $Mode.Count -ne 1 -or $Mode[0] -ne 'C')) {
        throw 'Use -Suite Custom -Mode C -Backend root for -LiveAppData.'
    }
    if ($Suite -eq 'Custom' -and $View -eq 'emulated-direct' -and $Backend -ne 'root') { throw 'emulated-direct requires root.' }
    if ($Suite -eq 'Custom' -and 'Replay' -in $Mode -and ($View -ne 'internal' -or $Backend -ne 'ordinary')) {
        throw 'Replay runs with ordinary UID and does not traverse a filesystem.'
    }
    if ($ImportVariant -and ($Suite -ne 'Custom' -or $LiveAppData -or 'A' -in $Mode -or 'B' -in $Mode)) {
        throw 'Use -ImportVariant with -Suite Custom and mode C or Replay on a fixed fixture.'
    }
    if ($ImportVariant -and @($ImportVariant | Sort-Object -Unique).Count -ne $ImportVariant.Count) {
        throw 'Use each import variant once per run.'
    }
    if ($FinalizeVariant -and ($Suite -ne 'Custom' -or $LiveAppData -or 'A' -in $Mode -or 'B' -in $Mode -or $ImportVariant.Count -gt 1)) {
        throw 'Use -FinalizeVariant with -Suite Custom and mode C or Replay on a fixed fixture, with one import variant at most.'
    }
    if ($FinalizeVariant -and @($FinalizeVariant | Sort-Object -Unique).Count -ne $FinalizeVariant.Count) {
        throw 'Use each finalize variant once per run.'
    }
    if ($PipelineVariant -and ($Suite -ne 'Custom' -or $LiveAppData -or 'Replay' -in $Mode -or $ImportVariant -or $FinalizeVariant)) {
        throw 'Use -PipelineVariant with -Suite Custom, A/B/C and default database strategies on a fixed fixture.'
    }
    if ($PipelineVariant -and @($PipelineVariant | Sort-Object -Unique).Count -ne $PipelineVariant.Count) {
        throw 'Use each pipeline variant once per run.'
    }
    if ($MeasureMetadata -and ($Suite -ne 'Custom' -or $Mode.Count -ne 1 -or $Mode[0] -ne 'A')) {
        throw 'Use -MeasureMetadata with -Suite Custom -Mode A.'
    }
    if ($ReplayFixture -ne 'empty' -and ($Suite -ne 'Custom' -or 'Replay' -notin $Mode)) {
        throw 'Use -ReplayFixture with -Suite Custom and mode Replay.'
    }
    if (-not $SkipBuild) {
        & (Join-Path $repo 'android/gradlew.bat') -p (Join-Path $repo 'android') :app:assembleProfile :app:assembleProfileAndroidTest `
            -PdisksizeBenchmark=true '-Ptarget-platform=android-arm64,android-x64' --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Benchmark build failed.' }
    }
    $abi = (Invoke-Adb @('shell','getprop','ro.product.cpu.abi')).Trim()
    if ($abi -notin @('arm64-v8a','x86_64')) { throw "Unsupported device ABI: $abi" }
    Build-Audit $abi
    $journal = Invoke-Adb @('shell','run-as',$package,'sh','-c',"'if test -f no_backup/scan-session.json; then cat no_backup/scan-session.json; fi'")
    if ($journal -and ($journal -join "`n" | ConvertFrom-Json).active) { throw 'Finish the running scan before replacing the APK.' }
    $installed = @(Invoke-Adb @('shell','pm','path',$package) | Where-Object { $_ -match '^package:' })
    if ($installed.Count -ne 1) { throw 'Expected one installed development APK to back up and restore.' }
    Invoke-Adb @('pull', ($installed[0].Trim() -replace '^package:',''), $originalApk) | Out-Null
    $testInstalled = @(& $adb -s $Serial shell pm path "$package.test" | Where-Object { $_ -match '^package:' })
    if ($testInstalled.Count -gt 1) { throw 'Cannot back up a split instrumentation APK.' }
    if ($testInstalled.Count -eq 1) {
        Invoke-Adb @('pull', ($testInstalled[0].Trim() -replace '^package:',''), $originalTestApk) | Out-Null
    }
    Write-Json ([ordered]@{
        reportVersion = 1; suite = $Suite; startedAt = [DateTimeOffset]::UtcNow.ToString('o')
        commit = (git -C $repo rev-parse HEAD); workingTreeDirty = [bool](git -C $repo status --porcelain)
        variant = 'profile'; apkSha256 = (Get-FileHash -LiteralPath $app).Hash
        testApkSha256 = (Get-FileHash -LiteralPath $testApk).Hash
        restoredApkSha256 = (Get-FileHash -LiteralPath $originalApk).Hash
        device = (Invoke-Adb @('shell','getprop','ro.product.device'))
        fingerprint = (Invoke-Adb @('shell','getprop','ro.build.fingerprint'))
        kernel = (Invoke-Adb @('shell','uname','-r')); abi = $abi
        onlineCpus = (Invoke-Adb @('shell','cat','/sys/devices/system/cpu/online'))
        replayFixture = $ReplayFixture
        clockTicksPerSecond = (Invoke-Adb @('shell','getconf','CLK_TCK'))
        cache = 'warmup rounds; no cache flush; new app process and fresh benchmark DB each case'
        sampled = [bool]$SampleResources; measuredMetadata = [bool]$MeasureMetadata
    }) 'environment.json'
    $runChangedApp = $true
    Install-App $app
    Install-App $testApk
    if ($SampleResources) {
        if ((Invoke-Adb @('shell',"su -c 'id -u'")).Trim() -ne '0') { throw 'Sampling requires ADB shell root.' }
        $user = (Invoke-Adb @('shell','am','get-current-user')).Trim()
        if ($user -notmatch '^\d+$') { throw 'Cannot identify the Android user for resource sampling.' }
        $index = "/data/user_de/$user/$package/no_backup/scan-benchmark-v1.db"
        Invoke-Adb @('push', (Join-Path $PSScriptRoot 'sample.sh'), $sampleHelper) | Out-Null
        Invoke-Adb @('shell','touch',$sampleMarker) | Out-Null
        $resources = Start-Job -ArgumentList $adb,$Serial,$sampleHelper,$sampleMarker,$index,(Join-Path $results 'resources.tsv') -ScriptBlock {
            param($Adb,$Serial,$Helper,$Marker,$Index,$Log)
            & $Adb -s $Serial shell "su -c 'sh $Helper $Marker $Index'" > $Log
        }
    }
    if ($LiveAppData) {
        Run-Group (New-Group 'live-app-data' $Size 'live' @('root/live-app-data/C') $Repeats $Warmups)
    } elseif ($Suite -eq 'Custom') {
        $Backend = $Backend.ToLowerInvariant()
        $View = $View.ToLowerInvariant()
        $Mode = @($Mode | ForEach-Object { if ($_ -eq 'Replay') { 'Replay' } else { $_.ToUpperInvariant() } })
        $location = if ($View -eq 'internal') { 'internal' } else { 'external' }
        $cases = foreach ($modeName in $Mode) {
            if ($PipelineVariant) {
                foreach ($pipeline in $PipelineVariant) { "$Backend/$View/$modeName///$($pipeline.ToLowerInvariant())" }
            } elseif ($FinalizeVariant) {
                $variant = if ($ImportVariant) { $ImportVariant[0].ToLowerInvariant() } else { '' }
                foreach ($finalizer in $FinalizeVariant) { "$Backend/$View/$modeName/$variant/$($finalizer.ToLowerInvariant())" }
            } elseif ($ImportVariant) { foreach ($variant in $ImportVariant) { "$Backend/$View/$modeName/$($variant.ToLowerInvariant())" } }
            else { "$Backend/$View/$modeName" }
        }
        Run-Group (New-Group 'custom' $Size $location $cases $Repeats $Warmups)
    } else {
        $ordinary = @('ordinary/internal/A','ordinary/internal/B','ordinary/internal/C','ordinary/internal/Replay')
        Run-Group (New-Group 'smoke-10000' 10000 'internal' $ordinary 1 0)
        if ($Suite -eq 'Baseline') {
            Run-Group (New-Group 'internal-100000' 100000 'internal' ($ordinary + @('root/internal/A','root/internal/B','root/internal/C')) $Repeats $Warmups)
            $external = foreach ($target in @('ordinary/fuse','root/fuse','root/emulated-direct')) {
                foreach ($kind in @('A','B','C')) { "$target/$kind" }
            }
            Run-Group (New-Group 'external-100000' 100000 'external' $external $Repeats $Warmups)
            Run-Group (New-Group 'internal-1000000' 1000000 'internal' ($ordinary | Where-Object { $_ -notmatch '/Replay$' }) $Repeats $Warmups)
        }
    }
} finally {
    if ($resources) {
        try {
            Invoke-Adb @('shell','rm','-f',$sampleMarker) | Out-Null
            Wait-Job $resources -Timeout 5 | Out-Null
            if ($resources.State -eq 'Running') { Stop-Job $resources }
            Remove-Job $resources -Force
            Invoke-Adb @('shell','rm','-f',$sampleHelper) | Out-Null
        } catch { Write-Warning "Resource sampler cleanup failed: $_" }
    }
    try {
        if ($runChangedApp) {
            foreach ($fixture in $cleanup) {
                try {
                    if ($fixture.location -eq 'live') { $fixture.location = 'internal' }
                    Invoke-Case ($fixture + @{ action = 'cleanup' }) "recovery-$($fixture.fixture)" | Out-Null
                } catch { Write-Warning "Fixture $($fixture.fixture) was not cleaned: $_" }
            }
        }
        Write-Summary
    } finally {
        try {
            if ($runChangedApp) {
                Invoke-Adb @('shell','am','force-stop',$package) | Out-Null
                Install-App $originalApk
                if (Test-Path -LiteralPath $originalTestApk) { Install-App $originalTestApk }
                Invoke-Adb @('shell','am','start','-n',"$package/.MainActivity") | Out-Null
            }
        } finally { if ($deviceLock) { $deviceLock.Dispose() } }
    }
}
