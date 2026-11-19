param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$RootInstall,
    [switch]$RootTests,
    [switch]$SkipBuild,
    [string]$Output
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $Output) { $Output = Join-Path $repo ('.local/snapshot-rescan-step4-' + [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssZ')) }
& (Join-Path $PSScriptRoot 'verify-step2.ps1') -Serial $Serial -RootInstall:$RootInstall -RootTests:$RootTests `
    -SkipBuild:$SkipBuild -Output $Output -IncludeMerge -IncludeService
& (Join-Path $PSScriptRoot 'verify-native.ps1') -Serial $Serial -Output (Join-Path $Output 'native') -RootTests:$RootTests
$summaryFile = Join-Path $Output 'summary.json'
$summary = Get-Content -LiteralPath $summaryFile -Raw | ConvertFrom-Json
$native = Get-Content -LiteralPath (Join-Path $Output 'native/summary.json') -Raw | ConvertFrom-Json
$summary | Add-Member -NotePropertyName nativeTests -NotePropertyValue $native.tests
$summary | Add-Member -NotePropertyName nativeFailed -NotePropertyValue $native.failed
$summary | ConvertTo-Json | Tee-Object -FilePath $summaryFile
