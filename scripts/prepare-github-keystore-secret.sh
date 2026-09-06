#!/usr/bin/env bash
# Print base64 keystore for GitHub Actions secret ANDROID_KEYSTORE_BASE64.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="$ROOT_DIR/android/keystore/ardtt-release.keystore"
if [[ ! -f "$KEYSTORE" ]]; then
  echo "Missing $KEYSTORE — run scripts/fetch-release-keystore.sh first." >&2
  exit 1
fi
base64 -w0 "$KEYSTORE"
echo
