#!/usr/bin/env bash
# One-shot: assemble clean ARDTT tree and force-push to GitHub (replaces existing content).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${ARDTT_BUILD_DIR:-/tmp/ardtt-publish-$$}"
REMOTE_URL="${ARDTT_REMOTE_URL:-https://github.com/2kristalls36-hue/ARDTT.git}"

"$ROOT/scripts/assemble-ardtt-repo.sh" "$OUT"

cd "$OUT"
git init -q
git add .
git commit -q -m "Initial public release of ARDTT

Android client and self-hosted VPS stack: AmneziaWG direct path
and RAW Dial via TURN bypass. GPL-3.0."
git branch -M main

if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REMOTE_URL"
else
  git remote add origin "$REMOTE_URL"
fi

echo "Force-pushing to $REMOTE_URL (replaces all existing commits)..."
git push -u origin main --force

echo "Done: $REMOTE_URL"
