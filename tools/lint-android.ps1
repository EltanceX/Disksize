$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$properties = Join-Path $repository 'android/local.properties'
if (-not (Test-Path -LiteralPath $properties)) { throw 'Run flutter build apk once to generate local.properties.' }

# Flutter's Windows generator escapes backslashes but leaves drive colons
# unescaped. Gradle reads those values, while Android Lint PropertyEscape
# requires Java-properties escaping. Change only the ignored local file.
$before = [IO.File]::ReadAllText($properties)
$after = [regex]::Replace($before, '(?m)^((?:sdk\.dir|flutter\.sdk)=[A-Za-z]):', '$1\:')
if ($before -ne $after) { [IO.File]::WriteAllText($properties, $after, [Text.UTF8Encoding]::new($false)) }

# local.properties is not a declared Lint task input. Recompute the analysis
# rather than reuse an old report produced before the local normalization.
& (Join-Path $repository 'android/gradlew.bat') -p (Join-Path $repository 'android') :app:lintDebug --rerun-tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Android Lint failed.' }
