#Requires -Version 5.1
<#
.SYNOPSIS
  Idempotent Windows + WSL lab setup with Resume.
#>
[CmdletBinding()]
param(
    [switch]$Resume,
    [string]$LabRoot,
    [string]$RepoRoot
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\ArdttLab.ps1')

if (-not $LabRoot) { $LabRoot = Get-ArdttLabRoot }
if (-not $RepoRoot) {
    $candidate = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $RepoRoot = $candidate
}

$statePath = Join-Path $LabRoot 'setup-state.json'
New-Item -ItemType Directory -Force -Path $LabRoot | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $LabRoot 'downloads') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $LabRoot 'apk') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $LabRoot 'sessions') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $LabRoot 'logs') | Out-Null

function Read-SetupState {
    if (Test-Path -LiteralPath $statePath) {
        return Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
    }
    return [pscustomobject]@{
        schema         = 'ardtt-lab-setup-state/v1'
        updatedUtc     = $null
        stages         = @{}
        resumeCommand  = "powershell -NoProfile -File `"$PSCommandPath`" -Resume"
        labRoot        = $LabRoot
    }
}

function Save-SetupState($State, $Stage, $Status, $Detail) {
    if (-not $State.stages) { $State | Add-Member stages @{} -Force }
    $State.updatedUtc = [DateTime]::UtcNow.ToString('o')
    $State.labRoot = $LabRoot
    $entry = [ordered]@{ status = $Status; detail = $Detail; utc = $State.updatedUtc }
    $ht = @{}
    if ($State.stages -is [hashtable]) { $ht = $State.stages }
    else {
        $State.stages.psobject.Properties | ForEach-Object { $ht[$_.Name] = $_.Value }
    }
    $ht[$Stage] = $entry
    $State.stages = $ht
    Save-ArdttUtf8 -Path $statePath -Text ($State | ConvertTo-Json -Depth 8)
}

$state = Read-SetupState
Write-ArdttLog INFO ("LabRoot={0} Resume={1}" -f $LabRoot, [bool]$Resume)

if (-not (Test-ArdttWindowsHost)) {
    Write-ArdttLog WARN 'Это не Windows. Windows-пакеты и USB здесь недоступны.'
    Write-ArdttLog INFO 'Linux/WSL: запустите scripts/windows-usb-testlab/Install-LinuxToolchain.sh'
    Save-SetupState $state 'windows_host' 'BLOCKED' ([System.Environment]::OSVersion.VersionString)
    $linuxSetup = Join-Path $PSScriptRoot 'Install-LinuxToolchain.sh'
    if (Test-Path -LiteralPath $linuxSetup) {
        bash $linuxSetup
        Save-SetupState $state 'linux_toolchain' 'PASS' 'Install-LinuxToolchain.sh'
    }
    return
}

Test-ArdttMinFreeSpace -Path $LabRoot -MinBytes (8GB)

function Test-StageDone($Name) {
    if (-not $Resume) { return $false }
    try {
        $st = $state.stages.$Name.status
        return ($st -eq 'PASS')
    } catch { return $false }
}

function Install-WingetPackage {
    param([string]$Id, [string]$Name)
    $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
    if (-not $winget) { throw 'winget.exe не найден. Установите App Installer из Microsoft Store.' }
    Write-ArdttLog INFO ("winget show {0}" -f $Id)
    $shown = Invoke-ArdttNative -FilePath $winget.Source -ArgumentList @('show', '--id', $Id, '--exact') -TimeoutSec 120
    if ($shown.ExitCode -ne 0) {
        throw "пакет $Id не найден через winget show --exact"
    }
    $list = Invoke-ArdttNative -FilePath $winget.Source -ArgumentList @('list', '--id', $Id, '--exact') -TimeoutSec 120
    if ($list.Stdout -match [regex]::Escape($Id)) {
        Write-ArdttLog INFO ("уже установлен: {0}" -f $Id)
        return
    }
    Write-ArdttLog INFO ("winget install {0}" -f $Id)
    $inst = Invoke-ArdttNative -FilePath $winget.Source -ArgumentList @(
        'install', '--id', $Id, '--exact', '--source', 'winget',
        '--accept-package-agreements', '--accept-source-agreements'
    ) -TimeoutSec 1800
    Assert-ArdttNativeExit $inst.ExitCode "winget install $Id"
}

if (-not (Test-StageDone 'windows_tools')) {
    try {
        Install-WingetPackage -Id 'Git.Git' -Name 'Git'
        Install-WingetPackage -Id 'GitHub.cli' -Name 'GitHub CLI'
        Install-WingetPackage -Id 'Genymobile.scrcpy' -Name 'scrcpy'
        Save-SetupState $state 'windows_tools' 'PASS' 'Git, gh, scrcpy'
    } catch {
        Save-SetupState $state 'windows_tools' 'FAIL' "$_"
        throw
    }
}

if (-not (Test-StageDone 'platform_tools')) {
    try {
        $ptDir = Join-Path $LabRoot 'platform-tools'
        $adb = Join-Path $ptDir 'adb.exe'
        if (-not (Test-Path -LiteralPath $adb)) {
            $zip = Join-Path (Join-Path $LabRoot 'downloads') 'platform-tools-windows.zip'
            Write-ArdttLog INFO 'download official platform-tools-latest-windows.zip (hash recorded after download; not pre-invented)'
            Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
            $hash = (Get-FileHash -Algorithm SHA256 -Path $zip).Hash.ToLower()
            Save-ArdttUtf8 -Path (Join-Path $LabRoot 'downloads\platform-tools.sha256') -Text $hash
            if (Get-Command tar.exe -ErrorAction SilentlyContinue) {
                New-Item -ItemType Directory -Force -Path $ptDir | Out-Null
                Push-Location $LabRoot
                tar.exe -xf $zip
                Pop-Location
            } else {
                Add-Type -AssemblyName System.IO.Compression.FileSystem
                [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $LabRoot)
            }
        }
        if (-not (Test-Path -LiteralPath $adb)) { throw "нет $adb после распаковки" }
        $ver = Invoke-ArdttNative -FilePath $adb -ArgumentList @('version') -TimeoutSec 20
        Assert-ArdttNativeExit $ver.ExitCode 'adb version'
        Save-SetupState $state 'platform_tools' 'PASS' ($ver.Stdout.Split("`n")[0])
    } catch {
        Save-SetupState $state 'platform_tools' 'FAIL' "$_"
        throw
    }
}

$configPath = Join-Path $LabRoot 'lab-config.json'
if (-not (Test-Path -LiteralPath $configPath)) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'lab-config.example.json') -Destination $configPath
}
$cfg = Get-ArdttLabConfig $configPath
$cfg.adbPath = (Join-Path (Join-Path $LabRoot 'platform-tools') 'adb.exe')
$scrcpyCmd = Get-Command scrcpy.exe -ErrorAction SilentlyContinue
if ($scrcpyCmd) { $cfg.scrcpyPath = $scrcpyCmd.Source }
Save-ArdttUtf8 -Path $configPath -Text ($cfg | ConvertTo-Json -Depth 8)

if (-not (Test-StageDone 'wsl')) {
    try {
        $wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue
        if (-not $wsl) { throw 'wsl.exe отсутствует. Нужна установка WSL2 / Ubuntu 24.04 и возможно перезагрузка.' }
        $status = Invoke-ArdttNative -FilePath $wsl.Source -ArgumentList @('--status') -TimeoutSec 30
        $list = Invoke-ArdttNative -FilePath $wsl.Source -ArgumentList @('--list', '--verbose') -TimeoutSec 30
        Save-ArdttUtf8 -Path (Join-Path $LabRoot 'logs\wsl-list.txt') -Text ($status.Stdout + "`n" + $list.Stdout)
        if ($list.Stdout -notmatch 'Ubuntu') {
            Write-ArdttLog WARN 'Ubuntu не найден. Установка: wsl --install -d Ubuntu-24.04 (может потребовать перезагрузку). Не выполняю reboot.'
            Save-SetupState $state 'wsl' 'BLOCKED' 'Ubuntu distro missing; reboot may be required after wsl --install'
            Save-ArdttUtf8 -Path (Join-Path $LabRoot 'RESUME.txt') -Text @"
После установки WSL2 / Ubuntu 24.04 и перезагрузки:
powershell -NoProfile -File `"$PSCommandPath`" -Resume
"@
        } else {
            Save-SetupState $state 'wsl' 'PASS' 'Ubuntu present'
        }
    } catch {
        Save-SetupState $state 'wsl' 'FAIL' "$_"
        throw
    }
}

$wslState = $null
try { $wslState = $state.stages.wsl.status } catch { }
if ($wslState -eq 'PASS') {
    $distro = $cfg.wslDistro
    if (-not $distro) { $distro = 'Ubuntu-24.04' }
    $linuxScript = './scripts/windows-usb-testlab/Install-LinuxToolchain.sh'
    Write-ArdttLog INFO 'WSL Install-LinuxToolchain.sh'
    $wslCmd = Get-Command wsl.exe
    $r = Invoke-ArdttNative -FilePath $wslCmd.Source -ArgumentList @(
        '-d', $distro, '--', 'bash', '-lc',
        "test -d `$HOME/ardtt-testlab/src/ARDTT || git clone --filter=blob:none https://github.com/2kristalls36-hue/ARDTT.git `$HOME/ardtt-testlab/src/ARDTT; cd `$HOME/ardtt-testlab/src/ARDTT && git fetch origin && git checkout $($cfg.requestedSha) && bash scripts/windows-usb-testlab/Install-LinuxToolchain.sh"
    ) -TimeoutSec 7200
    if ($r.ExitCode -ne 0) {
        Save-SetupState $state 'linux_toolchain' 'FAIL' $r.Stderr
        throw "WSL toolchain failed: $($r.Stderr)"
    }
    Save-SetupState $state 'linux_toolchain' 'PASS' 'Install-LinuxToolchain.sh via WSL'
}

Write-ArdttLog INFO "setup-state.json -> $statePath"
Write-ArdttLog INFO 'Дальше: .\Doctor-ARDTT.ps1'
