param([Parameter(Mandatory = $true)][string]$Apk, [Parameter(Mandatory = $true)][string]$Output)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (Test-Path -LiteralPath $Output) { throw 'Audit output already exists.' }
$results = (New-Item -ItemType Directory -Path $Output).FullName
$models = @(Get-ChildItem (Join-Path $repo 'build/.cxx/profile') -Recurse -Filter android_gradle_build.json)
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk).Path)
$audit = @()
try {
    foreach ($abi in @('arm64-v8a', 'x86_64')) {
        $entry = $zip.GetEntry("lib/$abi/libdisksize_jni.so")
        if (-not $entry) { throw "Missing ABI: $abi" }
        $packed = Join-Path $results "$abi.so"
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $packed)
        $match = $null
        foreach ($model in $models) {
            if ($model.Directory.Name -ne $abi) { continue }
            $commands = @(Get-Content (Join-Path $model.Directory.FullName 'compile_commands.json') -Raw | ConvertFrom-Json)
            $files = @('scan_bridge.cpp', 'stream_scanner.cpp', 'subtree_scope.cpp', 'filesystem.cpp', 'scanner.cpp')
            $selected = @($commands | Where-Object { (Split-Path $_.file -Leaf) -in $files })
            if ($selected.Count -ne $files.Count) { continue }
            if ($selected.command -match '(?<!\S)-DDISKSIZE_SCAN_BENCHMARK(?=\s|$)') { continue }
            $cache = Get-Content (Join-Path $model.Directory.FullName 'CMakeCache.txt') -Raw
            if ($cache -notmatch 'DISKSIZE_SCAN_BENCHMARK:BOOL=OFF') { continue }
            $info = Get-Content $model.FullName -Raw | ConvertFrom-Json
            $native = @($info.libraries.PSObject.Properties.Value | Where-Object artifactName -eq 'disksize_jni')[0].output
            if (-not (Test-Path $native)) { continue }
            $readelf = Join-Path (Split-Path $info.toolchains.toolchain.cppCompilerExecutable -Parent) 'llvm-readelf.exe'
            $packedId = [regex]::Match((& $readelf -n $packed | Out-String), 'Build ID:\s*([0-9a-f]+)').Groups[1].Value
            $nativeId = [regex]::Match((& $readelf -n $native | Out-String), 'Build ID:\s*([0-9a-f]+)').Groups[1].Value
            if (-not $packedId -or $packedId -ne $nativeId) { continue }
            foreach ($record in $selected) {
                $flags = [regex]::Matches($record.command, '(?<!\S)-O(?:[0-9zsg]|fast)?(?=\s|$)')
                if ($flags.Count -eq 0 -or $flags[$flags.Count - 1].Value -ne '-O2' -or $record.command -notmatch '\bDNDEBUG\b') {
                    throw "Normal Profile scanner is not optimized: $abi $($record.file)"
                }
            }
            $match = [pscustomobject]@{ abi=$abi; buildId=$packedId; effectiveOptimization='-O2'; benchmarkDisabled=$true; sourceFiles=$files; model=$model.FullName }
            break
        }
        if (-not $match) { throw "Cannot match optimized normal APK to current build: $abi" }
        $audit += $match
    }
} finally { $zip.Dispose() }
$audit | ConvertTo-Json -Depth 5 | Tee-Object -FilePath (Join-Path $results 'summary.json')
