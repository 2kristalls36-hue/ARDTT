#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Scenario,
    [string]$ConfigPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\ArdttLab.ps1')

$cfg = Get-ArdttLabConfig $ConfigPath
$labRoot = Get-ArdttLabRoot
$catalogPath = Join-Path $PSScriptRoot 'scenarios.json'
$catalog = Get-Content -LiteralPath $catalogPath -Raw -Encoding UTF8 | ConvertFrom-Json
$spec = $catalog.scenarios | Where-Object { $_.id -eq $Scenario } | Select-Object -First 1
if (-not $spec) { throw "неизвестный сценарий $Scenario" }

$report = [ordered]@{
    id          = $Scenario
    title       = $spec.title
    startedUtc  = [DateTime]::UtcNow.ToString('o')
    needs       = @($spec.needs)
    status      = 'PENDING'
    reason      = $null
    sessionDir  = $null
}

function Complete($Status, $Reason) {
    $report.status = $Status
    $report.reason = $Reason
    $report.finishedUtc = [DateTime]::UtcNow.ToString('o')
    $out = Join-Path $labRoot ("reports\{0}-{1}.json" -f $Scenario, ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')))
    New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
    Save-ArdttUtf8 -Path $out -Text ($report | ConvertTo-Json -Depth 8)
    Write-Host ($report | ConvertTo-Json -Depth 8)
}

if (-not (Test-ArdttWindowsHost)) {
    Complete 'BLOCKED' 'сценарий требует Windows ADB + USB телефон'
    return
}

try {
    $null = Resolve-ArdttAdb $cfg
    $dev = Invoke-ArdttAdb -Config $cfg -AdbArgs @('devices') -TimeoutSec 15
    if ($dev.Stdout -notmatch '\tdevice(\s|$)') {
        Complete 'BLOCKED' 'нет USB device'
        return
    }
    Test-ArdttUsbTetheringOff $cfg
} catch {
    Complete 'BLOCKED' "$_"
    return
}

if ($Scenario -eq 'S00') {
    & (Join-Path $PSScriptRoot 'Start-ARDTT-Diagnostics.ps1') -ConfigPath $ConfigPath -Scenario 'S00' -Mode interactive
    $lock = Get-ArdttSessionLockPath $labRoot
    $session = (Get-Content -LiteralPath $lock -Raw | ConvertFrom-Json).sessionDir
    $report.sessionDir = $session
    & (Join-Path $PSScriptRoot 'Mark-ARDTT-Event.ps1') -ConfigPath $ConfigPath -SessionDir $session -Note 'S00-smoke'
    & (Join-Path $PSScriptRoot 'Capture-ARDTT-State.ps1') -ConfigPath $ConfigPath -SessionDir $session -Label 'S00'
    try {
        Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'am', 'start', '-n', [string]$cfg.launcherActivity) -TimeoutSec 20 | Out-Null
    } catch {
        Write-ArdttLog WARN "am start: $_"
    }
    & (Join-Path $PSScriptRoot 'Stop-ARDTT-Diagnostics.ps1') -ConfigPath $ConfigPath -SessionDir $session
    Complete 'PASS' 'S00: start/mark/capture/stop'
    return
}

# Network scenarios require live operator conditions; do not fake them.
$missing = @()
foreach ($need in @($spec.needs)) {
    switch ($need) {
        'usb' { }
        'app-installed' {
            $p = Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'pm', 'path', [string]$cfg.package) -TimeoutSec 15
            if ($p.Stdout -notmatch 'package:') { $missing += 'app-installed' }
        }
        'profile' { $missing += 'profile (ручной вход VK / рабочий профиль не создаётся лабораторией)' }
        'vpn-consent' { $missing += 'vpn-consent (системный диалог VPN принимает пользователь)' }
        'cellular' { $missing += 'cellular (нужна живая SIM/LTE)' }
        'cellular-bs' { $missing += 'cellular-bs (нужна сеть с реальными БС, не имитация)' }
        'cellular-no-bs' { $missing += 'cellular-no-bs' }
        'wifi-radio-off' { $missing += 'wifi-radio-off (переключение radio — действие пользователя/сценария на устройстве)' }
        'wifi-radio-on-no-ap' { $missing += 'wifi-radio-on-no-ap' }
        'wifi-radio-on' { }
        'working-wifi' { $missing += 'working-wifi' }
        'captive-or-dead-wifi' { $missing += 'captive-or-dead-wifi' }
        'bypass-ready' { $missing += 'bypass-ready' }
        default { }
    }
}
Complete 'PENDING' ("не выполнено автоматически: " + ($missing -join '; '))
