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
$parent = $apk.Directory.Name
if ($parent -notlike "*$minName*" -and $apk.Name -notlike "*$minName*") {
    throw ("это не APK {0}: {1}\{2}. GitHub v0.5.264 (283) не обновляет уже установленный 0.5.264. Нужен run {3}." -f $minName, $parent, $apk.Name, $versions.ARDTT_PREVIEW_RUN_ID)
}

$dest = Join-Path $OutDir $apk.Name
Copy-Item -Force -LiteralPath $apk.FullName -Destination $dest
Write-ArdttLog INFO ("APK: {0}" -f $dest)
Write-Output $dest
