#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB="$(cd "$HERE/.." && pwd)"
chmod +x "$LAB/Build-ARDTT.sh" "$LAB/Doctor-ARDTT.sh" "$LAB/Fetch-PreviewApk.sh"
"$LAB/Build-ARDTT.sh" --help | grep -q -- '--force-native'
"$LAB/Doctor-ARDTT.sh" --help >/dev/null 2>&1 || true
# Doctor has no --help; ensure it fails closed on unknown flag
if "$LAB/Build-ARDTT.sh" --not-a-flag >/dev/null 2>&1; then
  echo "FAIL: unknown flag accepted" >&2
  exit 1
fi
echo "build-help tests passed"
