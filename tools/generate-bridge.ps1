[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repositoryRoot
try {
    & dart run pigeon --input pigeons/scanner_api.dart
    if ($LASTEXITCODE -ne 0) {
        throw 'Pigeon code generation failed.'
    }
    & dart format lib/platform/generated/scanner_api.g.dart
    if ($LASTEXITCODE -ne 0) {
        throw 'Generated Dart formatting failed.'
    }
} finally {
    Pop-Location
}
