#Requires -Version 5.1
<#
.SYNOPSIS
  Download the signed Preview APK that actually updates over GitHub v0.5.264.
  Does not use releases/latest (that file is still versionCode 283).
#>
[CmdletBinding()]
param(
    [string]$RunId,
    [string]$OutDir,
    [string]$Repo = '2kristalls36-hue/ARDTT'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\ArdttLab.ps1')

$versions = Get-ArdttLabVersions
if (-not $RunId) { $RunId = [string]$versions.ARDTT_PREVIEW_RUN_ID }
if (-not $OutDir) {
    $OutDir = Join-Path (Get-ArdttLabRoot) 'apk'
}
$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) { throw 'нужен GitHub CLI (gh). Установите gh и выполните gh auth login.' }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$raw = Join-Path $OutDir 'raw'
if (Test-Path -LiteralPath $raw) {
    Remove-Item -Recurse -Force -LiteralPath $raw
}

Write-ArdttLog INFO ("скачиваю Preview APK run {0} (не GitHub releases/latest)" -f $RunId)
& $gh.Source run download $RunId --repo $Repo --dir $raw
if ($LASTEXITCODE -ne 0) { throw "gh run download failed: $RunId" }

$apk = Get-ChildItem -Path $raw -Recurse -Filter *.apk | Select-Object -First 1
if (-not $apk) { throw "в артефактах run $RunId нет APK" }

$minName = [string]$versions.ARDTT_MIN_INSTALL_VERSION_NAME
$rejectSha = [string]$versions.ARDTT_REJECT_ARTIFACT_SHA
$parent = $apk.Directory.Name
if ($parent -notlike "*$minName*" -or ($rejectSha -and $parent -like "*$rejectSha*")) {
    throw ("это не APK {0}: {1}\{2}. Запрещён повтор 0.5.264 / 51cf7ce. Нужен run {3}." -f $minName, $parent, $apk.Name, $versions.ARDTT_PREVIEW_RUN_ID)
}

$hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$rejectHash = ([string]$versions.ARDTT_REJECT_APK_SHA256).ToLowerInvariant()
if ($rejectHash -and $hash -eq $rejectHash) {
    throw ("это байты Preview 51cf7ce ($hash). На телефоне они уже стоят как 0.5.264/283. Нужен SHA $($versions.ARDTT_PREVIEW_APK_SHA256).")
}

$dest = Join-Path $OutDir ($parent + '.apk')
Copy-Item -Force -LiteralPath $apk.FullName -Destination $dest
Write-ArdttLog INFO ("APK: {0} sha256={1}" -f $dest, $hash)
Write-Output $dest
