#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$SessionDir,
    [switch]$SkipArchive
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\ArdttLab.ps1')

$labRoot = Get-ArdttLabRoot
$cfg = Get-ArdttLabConfig $ConfigPath
$lock = Get-ArdttSessionLockPath $labRoot
if (-not $SessionDir) {
    if (-not (Test-Path -LiteralPath $lock)) { throw 'нет активной сессии' }
    $SessionDir = (Get-Content -LiteralPath $lock -Raw | ConvertFrom-Json).sessionDir
}

$pidFile = Join-Path $SessionDir 'logcat.pids'
if (Test-Path -LiteralPath $pidFile) {
    Get-Content -LiteralPath $pidFile | ForEach-Object {
        $procId = 0
        if ([int]::TryParse($_.Trim(), [ref]$procId) -and $procId -gt 0) {
            $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
            if ($p) {
                Write-ArdttLog INFO ("stop pid {0} ({1})" -f $procId, $p.ProcessName)
                Stop-Process -Id $procId -ErrorAction SilentlyContinue
            }
        }
    }
}

& (Join-Path $PSScriptRoot 'Capture-ARDTT-State.ps1') -ConfigPath $ConfigPath -SessionDir $SessionDir -Label 'final'

$sessPath = Join-Path $SessionDir 'session.json'
if (Test-Path -LiteralPath $sessPath) {
    $doc = Get-Content -LiteralPath $sessPath -Raw | ConvertFrom-Json
    $doc | Add-Member stoppedAtUtc ([DateTime]::UtcNow.ToString('o')) -Force
    Save-ArdttUtf8 -Path $sessPath -Text ($doc | ConvertTo-Json -Depth 8)
}
Write-ArdttTimeline -SessionDir $SessionDir -Event 'session-stop' -Data @{}

$verdict = @"
# Verdict

- Сессия: $SessionDir
- Остановлены только PID из logcat.pids этой сессии.
- Гипотезы не подтверждаются одним именем оператора или одной надписью Wi-Fi.
"@
Save-ArdttUtf8 -Path (Join-Path $SessionDir 'verdict.md') -Text $verdict

if (-not $SkipArchive) {
    $shareOut = Join-Path $SessionDir 'share-out'
    New-Item -ItemType Directory -Force -Path $shareOut | Out-Null
    $aliasPath = Initialize-ArdttAliases -LabRoot $labRoot -Serial ([string]$cfg.deviceSerial)
    $pack = Join-Path $PSScriptRoot 'lib\pack-session.sh'
    if (Get-Command bash -ErrorAction SilentlyContinue) {
        & bash $pack --session $SessionDir --output-dir $shareOut --aliases $aliasPath
    } else {
        Write-ArdttLog WARN 'bash недоступен: zip для передачи соберите в WSL через pack-session.sh'
    }
}

if (Test-Path -LiteralPath $lock) {
    $lockDoc = Get-Content -LiteralPath $lock -Raw | ConvertFrom-Json
    if ($lockDoc.sessionDir -eq $SessionDir) { Remove-Item -LiteralPath $lock -Force }
}
Write-Host $SessionDir
