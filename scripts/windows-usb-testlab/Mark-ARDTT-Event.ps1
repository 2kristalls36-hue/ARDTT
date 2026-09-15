#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Note,
    [string]$ConfigPath,
    [string]$SessionDir
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\ArdttLab.ps1')

$labRoot = Get-ArdttLabRoot
$cfg = Get-ArdttLabConfig $ConfigPath
if (-not $SessionDir) {
    $lock = Get-ArdttSessionLockPath $labRoot
    if (-not (Test-Path -LiteralPath $lock)) { throw 'нет активной сессии; укажите -SessionDir' }
    $SessionDir = (Get-Content -LiteralPath $lock -Raw | ConvertFrom-Json).sessionDir
}
$marker = 'ARDTT_LAB_MARK_{0}' -f ([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))
Write-ArdttTimeline -SessionDir $SessionDir -Event 'user-mark' -Data @{ note = $Note; marker = $marker }
$safe = ($Note -replace '[^A-Za-z0-9._: -]', '_')
try {
    Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'log', '-t', 'ArdttLab', "$marker $safe") | Out-Null
} catch {
    Write-ArdttLog WARN "не удалось написать в logcat: $_"
}
Write-Host $marker
