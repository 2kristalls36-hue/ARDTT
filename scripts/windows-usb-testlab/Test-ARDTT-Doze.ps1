#Requires -Version 5.1
<#
.SYNOPSIS
  Forced Doze test with guaranteed restore. This is NOT natural sleep proof.
#>
[CmdletBinding()]
param(
    [string]$ConfigPath,
    [int]$ObserveSec = 30
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\ArdttLab.ps1')

$cfg = Get-ArdttLabConfig $ConfigPath
$labRoot = Get-ArdttLabRoot
$stateFile = Join-Path $labRoot 'state\doze-saved.json'
New-Item -ItemType Directory -Force -Path (Split-Path $stateFile) | Out-Null

if (-not (Test-ArdttWindowsHost)) { throw 'Test-ARDTT-Doze.ps1 требует Windows ADB' }

$battery = Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'dumpsys', 'battery') -TimeoutSec 20
$idle = Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'dumpsys', 'deviceidle') -TimeoutSec 20
$saved = [ordered]@{
    savedUtc     = [DateTime]::UtcNow.ToString('o')
    batteryRaw   = $battery.Stdout
    deviceidleRaw= $idle.Stdout
}
Save-ArdttUtf8 -Path $stateFile -Text ($saved | ConvertTo-Json -Depth 4)

$restore = Join-Path $PSScriptRoot 'Restore-ARDTT-TestState.ps1'
try {
    Write-ArdttLog INFO 'forced Doze: battery unplug + deviceidle force-idle (не естественный сон)'
    Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'dumpsys', 'battery', 'unplug') | Out-Null
    Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'dumpsys', 'deviceidle', 'enable') | Out-Null
    Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'dumpsys', 'deviceidle', 'force-idle') | Out-Null
    Start-Sleep -Seconds $ObserveSec
    $after = Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'dumpsys', 'deviceidle') -TimeoutSec 20
    Save-ArdttUtf8 -Path (Join-Path $labRoot 'logs\doze-forced.txt') -Text $after.Stdout
} finally {
    & $restore -ConfigPath $ConfigPath
    Write-ArdttLog INFO 'Doze settings restore attempted in finally'
}
