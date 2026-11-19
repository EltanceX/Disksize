param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$RootInstall,
    [switch]$RootTests,
    [string]$Output
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $Output) { $Output = Join-Path $repo ('.local/snapshot-rescan-step5-' + [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssZ')) }
if (Test-Path -LiteralPath $Output) { throw 'Output directory already exists; choose a new run directory.' }
$results = (New-Item -ItemType Directory -Path $Output).FullName
$runner = Join-Path $PSScriptRoot 'verify-step2.ps1'
& $runner -Serial $Serial -RootInstall:$RootInstall -RootTests:$RootTests -IncludeMerge -IncludeService -Output (Join-Path $results 'core')
& $runner -Serial $Serial -RootInstall:$RootInstall -IncludeService -UiOnly -Output (Join-Path $results 'ui')
$core = Get-Content -LiteralPath (Join-Path $results 'core/summary.json') -Raw | ConvertFrom-Json
$ui = Get-Content -LiteralPath (Join-Path $results 'ui/summary.json') -Raw | ConvertFrom-Json
[pscustomobject]@{
    coreDevicePassed = $core.devicePassed
    coreDeviceSkipped = $core.deviceSkipped
    flutterUiDevicePassed = $ui.devicePassed
    normalSnapshotDataUnchanged = $core.normalSnapshotDataUnchanged -and $ui.normalSnapshotDataUnchanged
    originalMainApkRestored = $core.originalMainApkRestored -and $ui.originalMainApkRestored
    unitTests = $core.unitTests
} | ConvertTo-Json | Tee-Object -FilePath (Join-Path $results 'summary.json')
