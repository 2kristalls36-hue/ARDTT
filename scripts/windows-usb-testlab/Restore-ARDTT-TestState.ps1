#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ConfigPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\ArdttLab.ps1')

$cfg = Get-ArdttLabConfig $ConfigPath
$labRoot = Get-ArdttLabRoot
$stateFile = Join-Path $labRoot 'state\doze-saved.json'

if (-not (Test-ArdttWindowsHost)) {
    Write-ArdttLog WARN 'Restore на не-Windows хосте не трогает телефон'
    return
}

try {
    Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'dumpsys', 'deviceidle', 'unforce') -TimeoutSec 20 | Out-Null
} catch { Write-ArdttLog WARN "unforce: $_" }
try {
    Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'dumpsys', 'deviceidle', 'disable') -TimeoutSec 20 | Out-Null
} catch { Write-ArdttLog WARN "deviceidle disable: $_" }
try {
    Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'dumpsys', 'battery', 'reset') -TimeoutSec 20 | Out-Null
} catch { Write-ArdttLog WARN "battery reset: $_" }

if (Test-Path -LiteralPath $stateFile) {
    Write-ArdttLog INFO "исходный dumpsys сохранён в $stateFile (сравнение вручную)"
}
Write-ArdttLog INFO 'Restore-ARDTT-TestState: unforce + battery reset выполнены'
