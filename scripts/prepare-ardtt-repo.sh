#!/usr/bin/env bash
# Step 1: wipe ARDTT on GitHub and push a minimal placeholder (orphan commit).
#
# Linux / macOS / Git Bash:
#   ./scripts/prepare-ardtt-repo.sh
#
# Windows PowerShell:
#   .\scripts\prepare-ardtt-repo.ps1
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${ARDTT_PREP_DIR:-/tmp/ardtt-prepare-$$}"
REMOTE_URL="${ARDTT_REMOTE_URL:-https://github.com/2kristalls36-hue/ARDTT.git}"

echo "=== ARDTT Step 1: clear and prepare ==="
echo "Output: $OUT"
echo "Remote: $REMOTE_URL"
echo ""

rm -rf "$OUT"
mkdir -p "$OUT/docs/assets"

cp "$ROOT/ardtt-public/step1/README.md" "$OUT/README.md"
cp "$ROOT/LICENSE" "$OUT/LICENSE"
cp "$ROOT/ardtt-public/step1/docs/assets/.gitkeep" "$OUT/docs/assets/.gitkeep"
if [[ -f "$ROOT/docs/assets/ardtt-icon.png" ]]; then
  cp "$ROOT/docs/assets/ardtt-icon.png" "$OUT/docs/assets/ardtt-icon.png"
fi

cat > "$OUT/.gitignore" <<'EOF'
# Secrets / build artifacts (for step 2)
*.keystore
keystore.properties
local.properties
android/.gradle/
android/**/build/
server/data/
.env
EOF

cd "$OUT"
git init -q
git checkout -b main 2>/dev/null || git branch -M main
git add .
git commit -q -m "Prepare ARDTT repository for public migration

Replace previous content with a minimal placeholder README and GPL-3.0
license. Full source tree will be published in step 2."

if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REMOTE_URL"
else
  git remote add origin "$REMOTE_URL"
fi

echo ""
echo "Force-pushing placeholder to $REMOTE_URL ..."
echo "This REMOVES all existing commits and files on main."
read -r -p "Continue? [y/N] " ans
if [[ "${ans,,}" != "y" ]]; then
  echo "Aborted. Prepared tree kept at: $OUT"
  exit 1
fi

git push -u origin main --force

echo ""
echo "Step 1 done."
echo "  Repo: ${REMOTE_URL%.git}"
echo "  Next: ./scripts/push-ardtt-initial.ps1   (Windows)"
echo "        ./scripts/push-ardtt-initial.sh      (Git Bash)"
