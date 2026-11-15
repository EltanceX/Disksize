param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [int[]]$Scales = @(10000, 100000, 1000000),
    [ValidateSet('ordinary', 'root')][string]$Backend = 'ordinary',
    [switch]$SkipBuild,
    [switch]$RootBaseline
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logs = Join-Path $repository '.local/phase5'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Load .local/android-env.ps1 first.' }
foreach ($size in $Scales) { if ($size -notin @(10000,100000,1000000)) { throw 'Unsupported fixture size.' } }
function Invoke-Device([string[]]$Arguments) {
    $output = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')`n$output" }
    return $output
}
if (-not $SkipBuild) {
    & (Join-Path $repository 'android/gradlew.bat') -p (Join-Path $repository 'android') :app:assembleDebug :app:assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Android test APK build failed.' }
}
$normalApk = Join-Path $repository 'build/app/outputs/apk/debug/app-debug.apk'
$testApk = Join-Path $repository 'build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
Invoke-Device @('install','-r',$normalApk) | Write-Output
Invoke-Device @('install','-r',$testApk) | Write-Output
function Invoke-Instrument([string[]]$Extra, [string]$Name) {
    $output = @(Invoke-Device (@('shell','am','instrument','-w','-r') + $Extra + @('com.example.disksize.test/androidx.test.runner.AndroidJUnitRunner')))
    $output | Set-Content -Encoding utf8 (Join-Path $logs "$Name.log")
    if (($output -join "`n") -notmatch 'OK \(\d+ tests?\)' -or ($output -join "`n") -match 'FAILURES!!!|Process crashed') { throw "Instrumentation failed: $Name" }
    $output | Write-Output
}
try {
    $extra = @('-e','class','com.example.disksize.index.IndexStoreTest,com.example.disksize.index.IndexDeviceTest')
    if ($RootBaseline) { $extra += @('-e','phase5Root','true') }
    Invoke-Instrument $extra 'correctness'
    if ($Scales.Count -gt 0) {
        Invoke-Device @('push',(Join-Path $PSScriptRoot 'memory.sh'),'/data/local/tmp/disksize-phase5-memory.sh') | Out-Null
    }
    foreach ($size in $Scales) {
        Invoke-Device @('shell','touch','/data/local/tmp/disksize-phase5-monitor') | Out-Null
        $monitor = Start-Job -ArgumentList $adb,$Serial -ScriptBlock {
            param($adbPath,$device)
            & $adbPath -s $device shell "su -c 'sh /data/local/tmp/disksize-phase5-memory.sh'"
        }
        try {
            Invoke-Instrument @('-e','class','com.example.disksize.index.IndexDeviceTest#realFileScaleBaseline',
                '-e','phase5Scale',"$size",'-e','phase5Backend',$Backend) "scale-$size-$Backend"
            $report = Invoke-Device @('shell','run-as','com.example.disksize','cat',"files/phase5-scale-$size-$Backend.json")
            $report | Set-Content -Encoding utf8 (Join-Path $logs "scale-$size-$Backend.json")
            $report | Write-Output
        } finally {
            Invoke-Device @('shell','rm','-f','/data/local/tmp/disksize-phase5-monitor') | Out-Null
            $monitor | Wait-Job -Timeout 10 | Out-Null
            $samples = @(Receive-Job $monitor)
            $samples | Set-Content -Encoding utf8 (Join-Path $logs "memory-$size-$Backend.txt")
            Remove-Job $monitor -Force
            if (-not ($samples | Where-Object { $_ -match 'com\.example\.disksize:scanner\s+\d+' })) {
                throw 'No scanner memory samples captured; check ADB shell root authorization.'
            }
        }
    }
} finally {
    Invoke-Device @('shell','rm','-f','/data/local/tmp/disksize-phase5-monitor','/data/local/tmp/disksize-phase5-memory.sh') | Out-Null
    # adb instrumentation preserves the installed dashboard APK and app UID.
    Invoke-Device @('shell','am','start','-n','com.example.disksize/.MainActivity') | Out-Null
}
