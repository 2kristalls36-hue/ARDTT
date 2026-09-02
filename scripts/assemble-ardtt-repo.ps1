# Assemble a clean public ARDTT repository from current code + ardtt-public/ docs.
# Run: .\scripts\assemble-ardtt-repo.ps1 -OutputDir C:\temp\ardtt-out
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDir
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

Write-Host "Source: $Root"
Write-Host "Output: $OutputDir"

if (Test-Path $OutputDir) {
    Remove-Item -Recurse -Force $OutputDir
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$Exclude = @(".gradle", "build", ".cxx", "local.properties", "*.keystore", "keystore.properties")

function Copy-Tree {
    param([string]$SrcRel, [string]$DstRel)
    $Src = Join-Path $Root $SrcRel
    $Dst = Join-Path $OutputDir $DstRel
    if (-not (Test-Path $Src)) { return }
    New-Item -ItemType Directory -Path $Dst -Force | Out-Null
    robocopy $Src $Dst /E /NFL /NDL /NJH /NJS /nc /ns /np `
        /XD .gradle build .cxx `
        /XF local.properties keystore.properties *.keystore | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed for $SrcRel" }
}

Copy-Tree "android" "android"
Copy-Tree "server" "server"
Copy-Tree "scripts" "scripts"
Copy-Tree "docs\assets" "docs\assets"

foreach ($f in @("LICENSE", "NOTICE", ".gitignore")) {
    $p = Join-Path $Root $f
    if (Test-Path $p) { Copy-Item $p (Join-Path $OutputDir $f) }
}

Copy-Item (Join-Path $Root "ardtt-public\README.md") (Join-Path $OutputDir "README.md")
New-Item -ItemType Directory -Path (Join-Path $OutputDir "docs") -Force | Out-Null
Copy-Item (Join-Path $Root "ardtt-public\docs\*.md") (Join-Path $OutputDir "docs\")
Copy-Item (Join-Path $Root "ardtt-public\REPOSITORY.md") (Join-Path $OutputDir "REPOSITORY.md")
$GhSrc = Join-Path $Root "ardtt-public\.github"
if (Test-Path $GhSrc) {
    Copy-Item $GhSrc (Join-Path $OutputDir ".github") -Recurse
}

@(
    @{ Path = "android\README.md"; Text = "# Android`n`nСм. [docs/android.md](../docs/android.md) и [README.md](../README.md).`n" }
    @{ Path = "server\README.md"; Text = "# Server`n`nСм. [docs/server.md](../docs/server.md) и [docs/deploy.md](../docs/deploy.md).`n" }
) | ForEach-Object {
    Set-Content -Path (Join-Path $OutputDir $_.Path) -Value $_.Text -Encoding UTF8
}

Write-Host ""
Write-Host "Done. Public ARDTT tree at: $OutputDir"
Get-ChildItem $OutputDir | Select-Object Name
