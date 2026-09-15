#Requires -Version 5.1
<#
.SYNOPSIS
  Shared helpers for ARDTT Windows USB lab scripts.
.NOTES
  PowerShell 5.1 compatible. Do not shadow $PID / $Host / $Error / $HOME.
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# When this file is dot-sourced, $PSScriptRoot is lib/.
$script:ArdttModuleDir = $PSScriptRoot
$script:ArdttLabDir = Split-Path -Parent $PSScriptRoot

function Get-ArdttLabRoot {
    if ($env:ARDTT_LAB_ROOT -and $env:ARDTT_LAB_ROOT.Trim()) {
        return $env:ARDTT_LAB_ROOT
    }
    if ($env:LOCALAPPDATA) {
        return (Join-Path $env:LOCALAPPDATA 'ARDTT-TestLab')
    }
    return (Join-Path $env:HOME 'ardtt-testlab')
}

function Get-ArdttScriptRoot {
    if ($script:ArdttLabDir) { return $script:ArdttLabDir }
    if ($PSScriptRoot) { return $PSScriptRoot }
    return Split-Path -Parent $MyInvocation.MyCommand.Path
}

function Test-ArdttWindowsHost {
    return ($env:OS -eq 'Windows_NT')
}

function Write-ArdttLog {
    param(
        [ValidateSet('INFO', 'WARN', 'ERROR')]
        [string]$Level,
        [string]$Message
    )
    $ts = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    Write-Host ("{0} [{1}] {2}" -f $ts, $Level, $Message)
}

function Save-ArdttUtf8 {
    param([string]$Path, [string]$Text)
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir | Out-Null
    }
    $enc = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($Path, $Text, $enc)
}

function Read-ArdttUtf8 {
    param([string]$Path)
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function Assert-ArdttNativeExit {
    param(
        [int]$Code,
        [string]$Label
    )
    if ($Code -ne 0) {
        throw "Команда завершилась с кодом ${Code}: $Label"
    }
}

function Invoke-ArdttNative {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [string[]]$ArgumentList = @(),
        [int]$TimeoutSec = 120,
        [string]$StdoutPath,
        [string]$StderrPath
    )
    if (-not (Test-Path -LiteralPath $FilePath) -and -not (Get-Command $FilePath -ErrorAction SilentlyContinue)) {
        throw "исполняемый файл не найден: $FilePath"
    }
    $argLine = ($ArgumentList | ForEach-Object {
            if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
        }) -join ' '
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.Arguments = $argLine
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    [void]$proc.Start()
    $ok = $proc.WaitForExit($TimeoutSec * 1000)
    if (-not $ok) {
        try { $proc.Kill() } catch { }
        throw "таймаут ${TimeoutSec}s: $FilePath $argLine"
    }
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    if ($StdoutPath) { Save-ArdttUtf8 -Path $StdoutPath -Text $stdout }
    if ($StderrPath) { Save-ArdttUtf8 -Path $StderrPath -Text $stderr }
    return [pscustomobject]@{
        ExitCode = $proc.ExitCode
        Stdout   = $stdout
        Stderr   = $stderr
    }
}

function Get-ArdttLabConfig {
    param([string]$ConfigPath)
    if (-not $ConfigPath) {
        $ConfigPath = Join-Path (Get-ArdttLabRoot) 'lab-config.json'
    }
    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        $example = Join-Path (Get-ArdttScriptRoot) 'lab-config.example.json'
        if (-not (Test-Path -LiteralPath $example)) {
            throw "нет lab-config.json ($ConfigPath) и нет lab-config.example.json"
        }
        return (Read-ArdttUtf8 $example | ConvertFrom-Json)
    }
    return (Read-ArdttUtf8 $ConfigPath | ConvertFrom-Json)
}

function Resolve-ArdttAdb {
    param($Config)
    if ($Config.adbPath -and (Test-Path -LiteralPath $Config.adbPath)) {
        return $Config.adbPath
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($Config.windowsSdkRoot) {
        $candidate = Join-Path $Config.windowsSdkRoot 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $local = Join-Path (Get-ArdttLabRoot) 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $local) { return $local }
    throw 'adb.exe не найден. Запустите Setup-ARDTT-TestLab.ps1'
}

function Invoke-ArdttAdb {
    param(
        $Config,
        [string[]]$AdbArgs,
        [int]$TimeoutSec = 60
    )
    $adb = Resolve-ArdttAdb $Config
    $all = @()
    if ($Config.deviceSerial) {
        $all += @('-s', [string]$Config.deviceSerial)
    }
    $all += $AdbArgs
    $env:ADB = $adb
    $r = Invoke-ArdttNative -FilePath $adb -ArgumentList $all -TimeoutSec $TimeoutSec
    return $r
}

function Test-ArdttUsbTetheringOff {
    param($Config)
    $r = Invoke-ArdttAdb -Config $Config -AdbArgs @('shell', 'dumpsys', 'wifi') -TimeoutSec 30
    if ($r.Stdout -match 'usbTethering.*true' -or $r.Stdout -match 'Tethering.*usb.*=.*true') {
        throw 'USB tethering включён. Выключите USB-модем: он ломает тест мобильных БС.'
    }
}

function Get-ArdttSessionLockPath {
    param([string]$LabRoot)
    return (Join-Path $LabRoot 'sessions\ACTIVE.lock')
}

function New-ArdttSessionDir {
    param(
        [string]$LabRoot,
        [string]$Scenario,
        [string]$ShortSha
    )
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $safeScenario = ($Scenario -replace '[^A-Za-z0-9._-]', '_')
    $name = '{0}-{1}-{2}' -f $stamp, $safeScenario, $ShortSha
    $dir = Join-Path $LabRoot (Join-Path 'sessions' $name)
    New-Item -ItemType Directory -Path $dir | Out-Null
    return $dir
}

function Write-ArdttTimeline {
    param(
        [string]$SessionDir,
        [string]$Event,
        [hashtable]$Data
    )
    $obj = [ordered]@{
        tsUtc     = [DateTime]::UtcNow.ToString('o')
        event     = $Event
        data      = $Data
    }
    $line = ($obj | ConvertTo-Json -Compress -Depth 8)
    $path = Join-Path $SessionDir 'timeline.jsonl'
    $enc = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::AppendAllText($path, $line + [Environment]::NewLine, $enc)
}

function Get-ArdttFreeBytes {
    param([string]$Path)
    $root = [System.IO.Path]::GetPathRoot((Resolve-Path $Path -ErrorAction SilentlyContinue).Path)
    if (-not $root) { $root = $Path }
    $drive = Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Root -eq $root } | Select-Object -First 1
    if ($drive) { return [int64]$drive.Free }
    return [int64]0
}

function Test-ArdttMinFreeSpace {
    param([string]$Path, [int64]$MinBytes)
    $free = Get-ArdttFreeBytes $Path
    if ($free -gt 0 -and $free -lt $MinBytes) {
        throw ("мало места на {0}: {1} байт, нужно {2}" -f $Path, $free, $MinBytes)
    }
}

function Invoke-ArdttWsl {
    param(
        [string]$Distro,
        [string[]]$LinuxArgs,
        [int]$TimeoutSec = 3600
    )
    $wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue
    if (-not $wsl) { throw 'wsl.exe не найден' }
    $argList = @()
    if ($Distro) { $argList += @('-d', $Distro) }
    $argList += $LinuxArgs
    $r = Invoke-ArdttNative -FilePath $wsl.Source -ArgumentList $argList -TimeoutSec $TimeoutSec
    Assert-ArdttNativeExit $r.ExitCode ("wsl " + ($LinuxArgs -join ' '))
    return $r
}

function Get-ArdttAliasesPath {
    param([string]$LabRoot)
    return (Join-Path $LabRoot 'aliases.json')
}

function Initialize-ArdttAliases {
    param(
        [string]$LabRoot,
        [string]$Serial
    )
    $path = Get-ArdttAliasesPath $LabRoot
    $map = @{}
    if (Test-Path -LiteralPath $path) {
        $map = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    }
    if ($Serial -and -not ($map.PSObject.Properties.Name -contains $Serial)) {
        $n = 1 + @($map.PSObject.Properties).Count
        $map | Add-Member -NotePropertyName $Serial -NotePropertyValue ("device-{0:00}" -f $n) -Force
        Save-ArdttUtf8 -Path $path -Text ($map | ConvertTo-Json -Depth 5)
    }
    return $path
}

function ConvertTo-ArdttWslPath {
    param([string]$Distro, [string]$WindowsPath)
    if ($WindowsPath -match '^/') { return $WindowsPath }
    $wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue
    if (-not $wsl) { throw 'wsl.exe не найден' }
    $r = Invoke-ArdttNative -FilePath $wsl.Source -ArgumentList @('-d', $Distro, 'wslpath', '-a', $WindowsPath) -TimeoutSec 30
    Assert-ArdttNativeExit $r.ExitCode 'wslpath'
    return $r.Stdout.Trim()
}
