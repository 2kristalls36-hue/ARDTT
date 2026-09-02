# One-shot: assemble clean ARDTT tree and force-push to GitHub (replaces existing content).
# Run from repo root in PowerShell:
#   .\scripts\push-ardtt-initial.ps1
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Out = if ($env:ARDTT_BUILD_DIR) { $env:ARDTT_BUILD_DIR } else {
    Join-Path $env:TEMP ("ardtt-publish-" + [guid]::NewGuid().ToString("n"))
}
$RemoteUrl = if ($env:ARDTT_REMOTE_URL) { $env:ARDTT_REMOTE_URL } else {
    "https://github.com/2kristalls36-hue/ARDTT.git"
}

Write-Host "Source: $Root"
Write-Host "Output: $Out"
Write-Host ""

& (Join-Path $Root "scripts\assemble-ardtt-repo.ps1") -OutputDir $Out

Push-Location $Out
try {
    git init -q
    git add .
    git commit -q -m @"
Initial public release of ARDTT

Android client and self-hosted VPS stack: AmneziaWG direct path
and RAW Dial via TURN bypass. GPL-3.0.
"@
    git branch -M main

    $remotes = git remote 2>$null
    if ($remotes -contains "origin") {
        git remote set-url origin $RemoteUrl
    } else {
        git remote add origin $RemoteUrl
    }

    Write-Host "Force-pushing to $RemoteUrl (replaces all existing commits)..."
    git push -u origin main --force
    Write-Host ""
    Write-Host "Done: $RemoteUrl"
} finally {
    Pop-Location
}
