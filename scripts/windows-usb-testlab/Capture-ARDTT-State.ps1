#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$SessionDir,
    [string]$Label = 'snapshot'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\ArdttLab.ps1')

$labRoot = Get-ArdttLabRoot
$cfg = Get-ArdttLabConfig $ConfigPath
if (-not $SessionDir) {
    $lock = Get-ArdttSessionLockPath $labRoot
    if (Test-Path -LiteralPath $lock) {
        $SessionDir = (Get-Content -LiteralPath $lock -Raw | ConvertFrom-Json).sessionDir
    } else {
        throw 'нет SessionDir'
    }
}
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$out = Join-Path $SessionDir (Join-Path 'snapshots' $stamp)
New-Item -ItemType Directory -Force -Path $out | Out-Null
Write-ArdttTimeline -SessionDir $SessionDir -Event 'capture' -Data @{ label = $Label; dir = $out }

$adb = Resolve-ArdttAdb $cfg
$env:ADB = $adb
$env:ANDROID_SERIAL = [string]$cfg.deviceSerial
$bashCapture = Join-Path $PSScriptRoot 'lib\capture-state.sh'
$png = Join-Path $out 'screen.png'
$shot = Join-Path $PSScriptRoot 'lib\capture-screenshot.sh'

if (Get-Command bash -ErrorAction SilentlyContinue) {
    $args = @($bashCapture, '--out', $out, '--adb', $adb)
    if ($cfg.deviceSerial) { $args += @('--serial', [string]$cfg.deviceSerial) }
    & bash @args
    $shotArgs = @($shot, '--out', $png, '--adb', $adb)
    if ($cfg.deviceSerial) { $shotArgs += @('--serial', [string]$cfg.deviceSerial) }
    & bash @shotArgs
} else {
    $catalog = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'lib\capture-commands.json') -Raw | ConvertFrom-Json
    foreach ($item in $catalog) {
        $r = Invoke-ArdttAdb -Config $cfg -AdbArgs @($item.args) -TimeoutSec ([int]$item.timeoutSec)
        $status = 'ok'
        if ($r.ExitCode -ne 0) { $status = 'unavailable' }
        Save-ArdttUtf8 -Path (Join-Path $out "dumps\$($item.id).txt") -Text $r.Stdout
        Save-ArdttUtf8 -Path (Join-Path $out "dumps\$($item.id).stderr.txt") -Text $r.Stderr
        Write-ArdttLog INFO ("{0}: {1} ({2})" -f $item.id, $status, $r.ExitCode)
    }
    # PNG via device file, never PowerShell text redirection.
    $remote = '/data/local/tmp/ardtt-screencap-' + $stamp + '.png'
    Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'screencap', '-p', $remote) | Out-Null
    Invoke-ArdttAdb -Config $cfg -AdbArgs @('pull', $remote, $png) | Out-Null
    Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'rm', '-f', $remote) | Out-Null
}

Write-Host $out
