param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$monitorRoot = Split-Path -Parent $PSScriptRoot
$monitorTemp = Join-Path $monitorRoot '.codex-work/java-tmp'
New-Item -ItemType Directory -Path $monitorTemp -Force | Out-Null
$monitorOldTemp = $env:TEMP
$monitorOldTmp = $env:TMP
$monitorOldDir = $env:MONITOR_ANDROID_DIR
$monitorOldOffline = $env:MONITOR_OFFLINE
try {
    $env:TEMP = $monitorTemp
    $env:TMP = $monitorTemp
    $monitorPosix = $monitorRoot.Replace('\','/')
    if ($monitorPosix -match '^([A-Za-z]):/(.*)$') { $monitorPosix = '/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2] }
    $env:MONITOR_ANDROID_DIR = $monitorPosix + '/android'
    $env:MONITOR_OFFLINE = if ($Offline) { '--offline' } else { '' }
    & 'C:/Program Files/Git/bin/bash.exe' -c 'source /d/vibeproject/android-tools/env.sh && cd "$MONITOR_ANDROID_DIR" && gradle --no-daemon testDebugUnitTest assembleDebug --console=plain $MONITOR_OFFLINE'
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    $env:TEMP = $monitorOldTemp
    $env:TMP = $monitorOldTmp
    $env:MONITOR_ANDROID_DIR = $monitorOldDir
    $env:MONITOR_OFFLINE = $monitorOldOffline
}
