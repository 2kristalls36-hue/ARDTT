# Step 1: wipe ARDTT on GitHub and push a minimal placeholder (orphan commit).
# Run from repo root:
#   .\scripts\prepare-ardtt-repo.ps1
param(
    [switch]$Yes
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Out = if ($env:ARDTT_PREP_DIR) { $env:ARDTT_PREP_DIR } else {
    Join-Path $env:TEMP ("ardtt-prepare-" + [guid]::NewGuid().ToString("n"))
}
$RemoteUrl = if ($env:ARDTT_REMOTE_URL) { $env:ARDTT_REMOTE_URL } else {
    "https://github.com/2kristalls36-hue/ARDTT.git"
}

Write-Host "=== ARDTT Step 1: clear and prepare ===" -ForegroundColor Cyan
Write-Host "Output: $Out"
Write-Host "Remote: $RemoteUrl"
Write-Host ""

if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Path (Join-Path $Out "docs\assets") -Force | Out-Null

Copy-Item (Join-Path $Root "ardtt-public\step1\README.md") (Join-Path $Out "README.md")
Copy-Item (Join-Path $Root "LICENSE") (Join-Path $Out "LICENSE")
Copy-Item (Join-Path $Root "ardtt-public\step1\docs\assets\.gitkeep") (Join-Path $Out "docs\assets\.gitkeep")
$Icon = Join-Path $Root "docs\assets\ardtt-icon.png"
if (Test-Path $Icon) {
    Copy-Item $Icon (Join-Path $Out "docs\assets\ardtt-icon.png")
}

@'
# Secrets / build artifacts (for step 2)
*.keystore
keystore.properties
local.properties
android/.gradle/
android/**/build/
server/data/
.env
'@ | Set-Content -Path (Join-Path $Out ".gitignore") -Encoding UTF8

Push-Location $Out
try {
    git init -q
    git checkout -b main 2>$null
    if ($LASTEXITCODE -ne 0) { git branch -M main }
    git add .
    git commit -q -m @"
Prepare ARDTT repository for public migration

Replace previous content with a minimal placeholder README and GPL-3.0
license. Full source tree will be published in step 2.
"@

    $remotes = git remote 2>$null
    if ($remotes -contains "origin") {
        git remote set-url origin $RemoteUrl
    } else {
        git remote add origin $RemoteUrl
    }

    Write-Host ""
    Write-Host "Force-pushing placeholder to $RemoteUrl ..." -ForegroundColor Yellow
    Write-Host "This REMOVES all existing commits and files on main."
    if (-not $Yes) {
        $ans = Read-Host "Continue? [y/N]"
        if ($ans -notmatch '^[yY]') {
            Write-Host "Aborted. Prepared tree kept at: $Out"
            exit 1
        }
    }

    git push -u origin main --force

    Write-Host ""
    Write-Host "Step 1 done." -ForegroundColor Green
    Write-Host "  Repo: $($RemoteUrl -replace '\.git$','')"
    Write-Host "  Next: .\scripts\push-ardtt-initial.ps1"
} finally {
    Pop-Location
}
