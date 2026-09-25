#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ConfigPath,
    [ValidateSet('debug', 'release')]
    [string]$Variant,
    [string]$Abi,
    [switch]$ForceNative,
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\ArdttLab.ps1')

$cfg = Get-ArdttLabConfig $ConfigPath
if (-not $Variant) { $Variant = [string]$cfg.buildVariant }
if (-not $Abi) { $Abi = [string]$cfg.targetAbi }
if ($Abi -notmatch '^[A-Za-z0-9._-]+$') { throw "небезопасный ABI: $Abi" }
if ($cfg.requestedSha -and $cfg.requestedSha -notmatch '^[0-9a-fA-F]{7,40}$') { throw 'requestedSha не похож на git SHA' }

$labRoot = Get-ArdttLabRoot
$outDir = Join-Path $labRoot 'apk'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

if (-not (Test-ArdttWindowsHost)) {
    $args = @(
        (Join-Path $PSScriptRoot 'Build-ARDTT.sh'),
        '--abi', $Abi,
        '--variant', $Variant,
        '--out', $outDir,
        '--requested-sha', [string]$cfg.requestedSha
    )
    if ($ForceNative) { $args += '--force-native' }
    if ($SkipTests) { $args += @('--skip-unit-tests', '--skip-go-tests') }
    & bash @args
    Assert-ArdttNativeExit $LASTEXITCODE 'Build-ARDTT.sh'
    return
}

$distro = [string]$cfg.wslDistro
if (-not $distro) { $distro = 'Ubuntu-24.04' }
$checkout = [string]$cfg.wslCheckout
if (-not $checkout) { throw 'lab-config.json: задайте wslCheckout' }
if ($checkout -notmatch '^/home/[A-Za-z0-9._/-]+$') { throw "подозрительный wslCheckout: $checkout" }

$extra = ''
if ($ForceNative) { $extra += ' --force-native' }
if ($SkipTests) { $extra += ' --skip-unit-tests --skip-go-tests' }
# Separate .sh already validates flags. Pass only sanitized tokens.
$linux = "cd '$checkout' && bash scripts/windows-usb-testlab/Build-ARDTT.sh --abi $Abi --variant $Variant --out `$HOME/ardtt-testlab/apk --requested-sha $($cfg.requestedSha)$extra"
Write-ArdttLog INFO $linux
$wsl = Get-Command wsl.exe
$r = Invoke-ArdttNative -FilePath $wsl.Source -ArgumentList @('-d', $distro, '--', 'bash', '-lc', $linux) -TimeoutSec 7200
Assert-ArdttNativeExit $r.ExitCode 'WSL Build-ARDTT.sh'
Save-ArdttUtf8 -Path (Join-Path $labRoot 'logs\last-build.log') -Text ($r.Stdout + "`n" + $r.Stderr)
Write-ArdttLog INFO 'Сборка завершена. APK в WSL ~/ardtt-testlab/apk и при успехе копируется скриптом.'
