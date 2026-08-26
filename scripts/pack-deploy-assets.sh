#!/usr/bin/env bash
# Refresh Android deploy asset from server/
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/src/main/assets/deploy"
mkdir -p "$OUT"
tar -czf "$OUT/stack.tar.gz" \
  -C "$ROOT/server" \
  --exclude='data' \
  --exclude='*.tmp' \
  docker-compose.yml .env.example README.md scripts provision direct bypass warp
ls -lh "$OUT/stack.tar.gz"
echo "Updated $OUT/stack.tar.gz"
