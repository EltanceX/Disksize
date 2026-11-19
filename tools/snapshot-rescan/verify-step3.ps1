param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$RootInstall,
    [switch]$SkipBuild,
    [string]$Output
)
$ErrorActionPreference = 'Stop'
# Reuse the APK backup, isolated instrumentation, and exact restoration safeguards.
& (Join-Path $PSScriptRoot 'verify-step2.ps1') @PSBoundParameters -IncludeMerge
