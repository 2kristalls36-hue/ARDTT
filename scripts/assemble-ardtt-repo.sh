#!/usr/bin/env bash
# Assemble a clean public ARDTT repository from current code + ardtt-public/ docs.
# Does NOT modify nonameVPN — writes to OUTPUT_DIR only.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT="${1:?Usage: $0 /path/to/output-dir}"

echo "Source: $ROOT"
echo "Output: $OUTPUT"

rm -rf "$OUTPUT"
mkdir -p "$OUTPUT"

copy_tree() {
  local src="$1" dst="$2"
  if [[ -d "$ROOT/$src" ]]; then
    mkdir -p "$OUTPUT/$dst"
    tar -C "$ROOT/$src" \
      --exclude='.gradle' --exclude='build' --exclude='.cxx' \
      --exclude='local.properties' --exclude='*.keystore' \
      --exclude='keystore.properties' \
      -cf - . | tar -C "$OUTPUT/$dst" -xf -
  fi
}

# Code and legal
copy_tree android android
copy_tree server server
copy_tree scripts scripts
copy_tree docs/assets docs/assets

for f in LICENSE NOTICE .gitignore; do
  [[ -f "$ROOT/$f" ]] && cp "$ROOT/$f" "$OUTPUT/$f"
done

# Public documentation (replaces draft docs from nonameVPN)
cp "$ROOT/ardtt-public/README.md" "$OUTPUT/README.md"
mkdir -p "$OUTPUT/docs"
cp "$ROOT/ardtt-public/docs/"*.md "$OUTPUT/docs/"
cp -r "$ROOT/ardtt-public/.github" "$OUTPUT/.github" 2>/dev/null || true
cp "$ROOT/ardtt-public/REPOSITORY.md" "$OUTPUT/REPOSITORY.md"

# Trim android README to pointer (optional lightweight module readme)
cat > "$OUTPUT/android/README.md" <<'EOF'
# Android

См. [docs/android.md](../docs/android.md) и [README.md](../README.md).
EOF

cat > "$OUTPUT/server/README.md" <<'EOF'
# Server

См. [docs/server.md](../docs/server.md) и [docs/deploy.md](../docs/deploy.md).
EOF

echo ""
echo "Done. Public ARDTT tree:"
find "$OUTPUT" -maxdepth 2 -type d | sort | head -30
echo ""
echo "Next:"
echo "  cd $OUTPUT && git init && git add . && git commit -m 'Initial public release of ARDTT'"
echo "  git remote add origin https://github.com/2kristalls36-hue/ARDTT.git"
echo "  git push -u origin main --force"
