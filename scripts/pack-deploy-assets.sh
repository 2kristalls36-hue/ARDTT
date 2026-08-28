#!/usr/bin/env bash
# Refresh Android deploy asset from server/
# NOTE: Android aapt unpacks *.gz and may rename stack.tar.gz → stack.tar inside the APK.
# Store as stack.tar.gz.bin so the gzipped bytes survive packaging.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/src/main/assets/deploy"
mkdir -p "$OUT"
TMP="$(mktemp)"
tar -czf "$TMP" \
  -C "$ROOT/server" \
  --exclude='data' \
  --exclude='*.tmp' \
  docker-compose.yml .env.example README.md scripts provision direct bypass warp
cp -f "$TMP" "$OUT/stack.tar.gz.bin"
cp -f "$TMP" "$OUT/stack.tar.gz"
rm -f "$TMP"
ls -lh "$OUT/stack.tar.gz.bin" "$OUT/stack.tar.gz"
echo "Updated $OUT/stack.tar.gz.bin (APK-safe) and stack.tar.gz"
