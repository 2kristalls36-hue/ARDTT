#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$Scenario = 'S00',
    [ValidateSet('interactive', 'background')]
    [string]$Mode = 'interactive'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\ArdttLab.ps1')

$cfg = Get-ArdttLabConfig $ConfigPath
$labRoot = Get-ArdttLabRoot
$lock = Get-ArdttSessionLockPath $labRoot
if (Test-Path -LiteralPath $lock) {
    throw "уже есть активная сессия: $lock (Stop-ARDTT-Diagnostics.ps1)"
}

$short = 'unknown'
if ($cfg.requestedSha) { $short = $cfg.requestedSha.Substring(0, [Math]::Min(7, $cfg.requestedSha.Length)) }
$session = New-ArdttSessionDir -LabRoot $labRoot -Scenario $Scenario -ShortSha $short
$adb = Resolve-ArdttAdb $cfg
$env:ADB = $adb

$props = Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'getprop') -TimeoutSec 20
Save-ArdttUtf8 -Path (Join-Path $session 'host-getprop.txt') -Text $props.Stdout
$model = (Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'getprop', 'ro.product.model') -TimeoutSec 10).Stdout.Trim()
$abi = (Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'getprop', 'ro.product.cpu.abi') -TimeoutSec 10).Stdout.Trim()
$rel = (Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'getprop', 'ro.build.version.release') -TimeoutSec 10).Stdout.Trim()
$phoneNow = (Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'date', '+%s') -TimeoutSec 10).Stdout.Trim()
$uptime = (Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'uptime') -TimeoutSec 10).Stdout.Trim()

$sessionDoc = [ordered]@{
    schema          = 'ardtt-lab-session/v1'
    id              = Split-Path $session -Leaf
    scenario        = $Scenario
    mode            = $Mode
    serialLocal     = $cfg.deviceSerial
    androidRelease  = $rel
    abi             = $abi
    model           = $model
    package         = $cfg.package
    requestedSha    = $cfg.requestedSha
    hostUtcStart    = [DateTime]::UtcNow.ToString('o')
    phoneUnixStart  = $phoneNow
    uptimeStart     = $uptime
    logcatProcesses = @()
}
Save-ArdttUtf8 -Path (Join-Path $session 'session.json') -Text ($sessionDoc | ConvertTo-Json -Depth 6)
Save-ArdttUtf8 -Path $lock -Text (([ordered]@{ sessionDir = $session; startedUtc = $sessionDoc.hostUtcStart }) | ConvertTo-Json)
Write-ArdttTimeline -SessionDir $session -Event 'session-start' -Data @{ mode = $Mode; model = $model }

# Do not clear logcat. Continuous collectors as separate processes (not PS jobs).
$pids = @()
$pidFile = Join-Path $session 'logcat.pids'
foreach ($buf in @($cfg.logcatBuffers)) {
    $out = Join-Path $session ("logcat-{0}.txt" -f $buf)
    $err = Join-Path $session ("logcat-{0}.stderr.txt" -f $buf)
    $arg = @()
    if ($cfg.deviceSerial) { $arg += @('-s', [string]$cfg.deviceSerial) }
    $arg += @('logcat', '-v', 'threadtime', '-b', [string]$buf)
    $p = Start-Process -FilePath $adb -ArgumentList $arg -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
    $pids += $p.Id
    Write-ArdttLog INFO ("logcat {0} pid={1}" -f $buf, $p.Id)
}
Save-ArdttUtf8 -Path $pidFile -Text (($pids | ForEach-Object { "$_" }) -join [Environment]::NewLine)
$sessionDoc.logcatProcesses = $pids
Save-ArdttUtf8 -Path (Join-Path $session 'session.json') -Text ($sessionDoc | ConvertTo-Json -Depth 6)

if ($Mode -eq 'interactive' -and $cfg.scrcpyPath -and (Test-Path -LiteralPath $cfg.scrcpyPath)) {
    Write-ArdttLog INFO 'интерактивный режим: можно запустить scrcpy отдельно; аудио по умолчанию не нужно'
}

Write-Host $session
