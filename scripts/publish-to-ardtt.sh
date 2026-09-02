#!/usr/bin/env bash
# Replace everything in github.com/2kristalls36-hue/ARDTT with the current ARDTT tree.
# Run from repo root after: gh auth login  (or with a PAT that can push to ARDTT)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

REMOTE_NAME="${ARDTT_REMOTE_NAME:-ardtt}"
REMOTE_URL="${ARDTT_REMOTE_URL:-https://github.com/2kristalls36-hue/ARDTT.git}"
SOURCE_BRANCH="${ARDTT_SOURCE_BRANCH:-$(git branch --show-current)}"
TARGET_BRANCH="${ARDTT_TARGET_BRANCH:-main}"

if ! git rev-parse --verify "$SOURCE_BRANCH" >/dev/null 2>&1; then
  echo "Source branch not found: $SOURCE_BRANCH" >&2
  exit 1
fi

if git remote get-url "$REMOTE_NAME" >/dev/null 2>&1; then
  git remote set-url "$REMOTE_NAME" "$REMOTE_URL"
else
  git remote add "$REMOTE_NAME" "$REMOTE_URL"
fi

echo "Force-pushing $SOURCE_BRANCH -> $REMOTE_NAME/$TARGET_BRANCH"
echo "This replaces all commits and files currently on GitHub."
read -r -p "Continue? [y/N] " ans
if [[ "${ans,,}" != "y" ]]; then
  echo "Aborted."
  exit 1
fi

git push "$REMOTE_NAME" "${SOURCE_BRANCH}:${TARGET_BRANCH}" --force

echo "Done. Open: ${REMOTE_URL%.git}"
