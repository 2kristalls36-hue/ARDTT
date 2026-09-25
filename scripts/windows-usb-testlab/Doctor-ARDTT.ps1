#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$JsonOut
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\ArdttLab.ps1')

$labRoot = Get-ArdttLabRoot
$cfg = Get-ArdttLabConfig $ConfigPath
$checks = @()

function Add-Check($Name, $Status, $Detail) {
    $script:checks += [ordered]@{ name = $Name; status = $Status; detail = $Detail }
    Write-ArdttLog INFO ("{0}: {1} — {2}" -f $Name, $Status, $Detail)
}

if (Test-ArdttWindowsHost) {
    Add-Check 'windows' 'PASS' ([System.Environment]::OSVersion.VersionString)
    try {
        $adb = Resolve-ArdttAdb $cfg
        $ver = Invoke-ArdttNative -FilePath $adb -ArgumentList @('version') -TimeoutSec 20
        if ($ver.ExitCode -eq 0) { Add-Check 'adb' 'PASS' $ver.Stdout.Split("`n")[0].Trim() }
        else { Add-Check 'adb' 'FAIL' $ver.Stderr }
        $dev = Invoke-ArdttNative -FilePath $adb -ArgumentList @('devices', '-l') -TimeoutSec 20
        if ($dev.Stdout -match '\tdevice(\s|$)') { Add-Check 'usb-device' 'PASS' ($dev.Stdout.Trim()) }
        elseif ($dev.Stdout -match 'unauthorized') { Add-Check 'usb-device' 'BLOCKED' 'unauthorized: подтвердите RSA на телефоне' }
        elseif ($dev.Stdout -match 'offline') { Add-Check 'usb-device' 'BLOCKED' 'offline' }
        else { Add-Check 'usb-device' 'BLOCKED' 'телефон не в состоянии device' }

        if ($cfg.deviceSerial -or ($dev.Stdout -match '\tdevice')) {
            try { Test-ArdttUsbTetheringOff $cfg; Add-Check 'usb-tether' 'PASS' 'tethering not detected' }
            catch { Add-Check 'usb-tether' 'FAIL' "$_" }
            $shell = Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'echo', 'ardtt-ok') -TimeoutSec 15
            if ($shell.Stdout -match 'ardtt-ok') { Add-Check 'adb-shell' 'PASS' 'echo ok' }
            else { Add-Check 'adb-shell' 'FAIL' $shell.Stderr }
            $pkg = Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'pm', 'path', $cfg.package) -TimeoutSec 20
            if ($pkg.ExitCode -eq 0 -and $pkg.Stdout -match 'package:') { Add-Check 'app-installed' 'PASS' $pkg.Stdout.Trim() }
            else { Add-Check 'app-installed' 'BLOCKED' 'com.ardtt.app не установлен' }
        }
        $scrcpy = Get-Command scrcpy.exe -ErrorAction SilentlyContinue
        if ($scrcpy) { Add-Check 'scrcpy' 'PASS' $scrcpy.Source } else { Add-Check 'scrcpy' 'BLOCKED' 'scrcpy.exe не найден' }
    } catch {
        Add-Check 'adb' 'FAIL' "$_"
    }
    $wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue
    if ($wsl) { Add-Check 'wsl' 'PASS' 'wsl.exe present' } else { Add-Check 'wsl' 'BLOCKED' 'wsl.exe missing' }
} else {
    Add-Check 'windows' 'BLOCKED' 'not a Windows host'
}

$doc = [ordered]@{
    schema       = 'ardtt-lab-doctor/v1'
    checkedAtUtc = [DateTime]::UtcNow.ToString('o')
    labRoot      = $labRoot
    checks       = $checks
}
$json = $doc | ConvertTo-Json -Depth 6
if (-not $JsonOut) { $JsonOut = Join-Path $labRoot 'doctor.json' }
Save-ArdttUtf8 -Path $JsonOut -Text $json
Write-Host $json

$linuxDoctor = Join-Path $PSScriptRoot 'Doctor-ARDTT.sh'
if (Test-Path -LiteralPath $linuxDoctor) {
    if (Get-Command bash -ErrorAction SilentlyContinue) {
        & bash $linuxDoctor --json-out (Join-Path $labRoot 'doctor-linux.json')
    } elseif ((Test-ArdttWindowsHost) -and (Get-Command wsl.exe -ErrorAction SilentlyContinue) -and $cfg.wslDistro) {
        $linuxPath = ConvertTo-ArdttWslPath -Distro $cfg.wslDistro -WindowsPath $linuxDoctor
        Invoke-ArdttWsl -Distro $cfg.wslDistro -LinuxArgs @('bash', $linuxPath, '--json-out', '/tmp/ardtt-doctor-linux.json') -TimeoutSec 120 | Out-Null
    }
}
