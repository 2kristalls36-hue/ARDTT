#Requires -Version 5.1
<#
.SYNOPSIS
  Compare signatures/versions and install only when compatible. Never uninstalls.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [string]$ConfigPath,
    [switch]$WhatIfInstall
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\ArdttLab.ps1')

if (-not (Test-Path -LiteralPath $ApkPath)) { throw "нет APK: $ApkPath" }
$cfg = Get-ArdttLabConfig $ConfigPath
$labRoot = Get-ArdttLabRoot
$report = Join-Path $labRoot 'install-report.json'
$adb = Resolve-ArdttAdb $cfg

function Get-InstalledPackageInfo {
    $dump = Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'dumpsys', 'package', [string]$cfg.package) -TimeoutSec 40
    $text = $dump.Stdout
    $info = [ordered]@{
        installed     = ($text -match 'Package \[' + [regex]::Escape($cfg.package) + '\]')
        versionName   = $null
        versionCode   = $null
        debuggable    = ($text -match 'DEBUGGABLE')
        apkPath       = $null
        raw           = $null
    }
    if ($text -match 'versionName=([^\s]+)') { $info.versionName = $Matches[1] }
    if ($text -match 'versionCode=(\d+)') { $info.versionCode = [int]$Matches[1] }
    $path = Invoke-ArdttAdb -Config $cfg -AdbArgs @('shell', 'pm', 'path', [string]$cfg.package) -TimeoutSec 20
    if ($path.Stdout -match 'package:(.+)') { $info.apkPath = $Matches[1].Trim() }
    $info.raw = Join-Path $labRoot 'logs\installed-package.txt'
    Save-ArdttUtf8 -Path $info.raw -Text $text
    return [pscustomobject]$info
}

$versions = Get-ArdttLabVersions
$minCode = 284
if ($versions.ARDTT_MIN_INSTALL_VERSION_CODE) {
    $minCode = [int]$versions.ARDTT_MIN_INSTALL_VERSION_CODE
}
$minName = '0.5.265'
if ($versions.ARDTT_MIN_INSTALL_VERSION_NAME) {
    $minName = [string]$versions.ARDTT_MIN_INSTALL_VERSION_NAME
}

$apkPathHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$rejectHash = $null
if ($versions.ARDTT_REJECT_APK_SHA256) {
    $rejectHash = ([string]$versions.ARDTT_REJECT_APK_SHA256).ToLowerInvariant()
}
$apkLeaf = [IO.Path]::GetFileNameWithoutExtension($ApkPath)
$rejectArt = [string]$versions.ARDTT_REJECT_ARTIFACT_SHA

Write-ArdttLog INFO 'снимок установленного приложения до install'
$before = Get-InstalledPackageInfo

$sdkApksigner = $null
if ($cfg.windowsSdkRoot) {
    $cand = Join-Path $cfg.windowsSdkRoot "build-tools\35.0.0\apksigner.bat"
    if (Test-Path -LiteralPath $cand) { $sdkApksigner = $cand }
}

$apkBadging = $null
$apkVersionCode = $null
$apkVersionName = $null
if ($cfg.windowsSdkRoot) {
    $aapt = Join-Path $cfg.windowsSdkRoot "build-tools\35.0.0\aapt.exe"
    if (Test-Path -LiteralPath $aapt) {
        $badging = Invoke-ArdttNative -FilePath $aapt -ArgumentList @('dump', 'badging', $ApkPath) -TimeoutSec 60
        $apkBadging = $badging.Stdout
        if ($apkBadging -match "versionCode='(\d+)'") { $apkVersionCode = [int]$Matches[1] }
        if ($apkBadging -match "versionName='([^']+)'") { $apkVersionName = $Matches[1] }
    }
}

$certNew = $null
if ($sdkApksigner) {
    $pr = Invoke-ArdttNative -FilePath $sdkApksigner -ArgumentList @('verify', '--print-certs', $ApkPath) -TimeoutSec 60
    $certNew = $pr.Stdout
} elseif (Get-Command wsl.exe -ErrorAction SilentlyContinue) {
    Write-ArdttLog INFO 'apksigner через WSL Linux SDK'
}

$pulled = $null
if ($before.apkPath) {
    $pulled = Join-Path $labRoot 'apk\currently-installed.apk'
    $pull = Invoke-ArdttAdb -Config $cfg -AdbArgs @('pull', $before.apkPath, $pulled) -TimeoutSec 120
    if ($pull.ExitCode -ne 0) {
        Write-ArdttLog WARN "не удалось pull установленного APK: $($pull.Stderr)"
        $pulled = $null
    }
}

$blocker = $null
if ($rejectHash -and $apkPathHash -eq $rejectHash) {
    $blocker = ("это байты Preview 51cf7ce ({0}). lastUpdateTime меняется, versionCode остаётся 283. Нужен APK {1} SHA {2}." -f $apkPathHash, $minName, $versions.ARDTT_PREVIEW_APK_SHA256)
}
if (-not $blocker -and $rejectArt -and $apkLeaf -like "*$rejectArt*") {
    $blocker = "имя APK содержит $rejectArt (старый Preview PR 217). Не ставить."
}
if (-not $blocker -and $apkLeaf -like '*0.5.264*') {
    $blocker = 'это APK 0.5.264. Android не обновит уже установленный 0.5.264/283.'
}
if (-not $blocker -and $apkVersionCode -and ($apkVersionCode -lt $minCode)) {
    $blocker = ("это APK {0} (versionCode {1}), а для обновления нужен {2} ({3}). GitHub releases/latest = v0.5.264 / 283 — Android не заменит уже установленный 0.5.264. Скачайте Preview APK run {4}." -f $(if ($apkVersionName) { $apkVersionName } else { '?' }), $apkVersionCode, $minName, $minCode, $versions.ARDTT_PREVIEW_RUN_ID)
}
if (-not $blocker -and $before.installed -and $apkVersionCode -and $before.versionCode -and ($apkVersionCode -le [int]$before.versionCode)) {
    $blocker = ("versionCode APK {0} не больше установленного {1}. Установщик не считает это обновлением." -f $apkVersionCode, $before.versionCode)
}
if ($before.installed -and $pulled -and $sdkApksigner -and $certNew) {
    $old = Invoke-ArdttNative -FilePath $sdkApksigner -ArgumentList @('verify', '--print-certs', $pulled) -TimeoutSec 60
    $newSha = [regex]::Match($certNew, 'SHA-256 digest:\s*([0-9a-fA-F: ]+)').Groups[1].Value
    $oldSha = [regex]::Match($old.Stdout, 'SHA-256 digest:\s*([0-9a-fA-F: ]+)').Groups[1].Value
    if ($oldSha -and $newSha -and ($oldSha -replace '[\s:]', '') -ne ($newSha -replace '[\s:]', '')) {
        $blocker = 'INSTALL_INCOMPATIBLE_SIGNATURE: подпись нового APK не совпадает с установленным. Удаление запрещено этой лабораторией.'
    }
}

$result = [ordered]@{
    before     = $before
    apk        = $ApkPath
    apkSha256  = $apkPathHash
    blocker    = $blocker
    installed  = $false
}

if ($blocker) {
    Write-ArdttLog ERROR $blocker
    Save-ArdttUtf8 -Path $report -Text ($result | ConvertTo-Json -Depth 8)
    throw $blocker
}

if ($WhatIfInstall) {
    Write-ArdttLog INFO 'WhatIf: установка не выполнялась'
    Save-ArdttUtf8 -Path $report -Text ($result | ConvertTo-Json -Depth 8)
    return
}

Write-ArdttLog INFO 'adb install -r (без -d/-g/-grant, без uninstall)'
$inst = Invoke-ArdttAdb -Config $cfg -AdbArgs @('install', '-r', $ApkPath) -TimeoutSec 180
if ($inst.Stdout -match 'Failure' -or $inst.ExitCode -ne 0) {
    $msg = $inst.Stdout + $inst.Stderr
    if ($msg -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' -or $msg -match 'VERSION_DOWNGRADE') {
        $result.blocker = $msg
        Save-ArdttUtf8 -Path $report -Text ($result | ConvertTo-Json -Depth 8)
        throw "установка отклонена системой, приложение НЕ удалялось: $msg"
    }
    throw "adb install failed: $msg"
}
$result.installed = $true
$result.apkSha256 = $apkPathHash
$result.after = Get-InstalledPackageInfo
if (-not $result.after.versionCode -or ([int]$result.after.versionCode -lt $minCode)) {
    $msg = ("на устройстве versionName={0} versionCode={1}, нужен {2}/{3}. Повтор 51cf7ce или GitHub v0.5.264 не обновление — lastUpdateTime мог смениться при тех же байтах." -f $result.after.versionName, $result.after.versionCode, $minName, $minCode)
    $result.blocker = $msg
    Save-ArdttUtf8 -Path $report -Text ($result | ConvertTo-Json -Depth 8)
    throw $msg
}
Save-ArdttUtf8 -Path $report -Text ($result | ConvertTo-Json -Depth 8)
Write-ArdttLog INFO ("установлено versionName={0} versionCode={1} sha256={2}" -f $result.after.versionName, $result.after.versionCode, $apkPathHash)
