#!/usr/bin/env bash
# Download the permanent ARDTT release keystore from the distribution VPS.
# Requires: sshpass OR SSH key auth as root@45.129.2.3
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST="${ARDTT_DIST_HOST:-45.129.2.3}"
REMOTE_DIR="${ARDTT_SECRETS_DIR:-/opt/ardtt-distribution/secrets}"
OUT_DIR="$ROOT_DIR/android/keystore"
PROPS_OUT="$ROOT_DIR/android/keystore.properties"

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

if [[ -n "${ARDTT_SSH_PASSWORD:-}" ]]; then
  if ! command -v sshpass >/dev/null 2>&1; then
    echo "Install sshpass or use SSH keys / Paramiko." >&2
    exit 1
  fi
  sshpass -e scp -o StrictHostKeyChecking=accept-new \
    "root@${HOST}:${REMOTE_DIR}/ardtt-release.keystore" \
    "$OUT_DIR/ardtt-release.keystore"
  sshpass -e scp -o StrictHostKeyChecking=accept-new \
    "root@${HOST}:${REMOTE_DIR}/keystore.properties" \
    "$PROPS_OUT"
else
  scp -o StrictHostKeyChecking=accept-new \
    "root@${HOST}:${REMOTE_DIR}/ardtt-release.keystore" \
    "$OUT_DIR/ardtt-release.keystore"
  scp -o StrictHostKeyChecking=accept-new \
    "root@${HOST}:${REMOTE_DIR}/keystore.properties" \
    "$PROPS_OUT"
fi

chmod 600 "$OUT_DIR/ardtt-release.keystore" "$PROPS_OUT"
# Ensure storeFile path is relative to android/
python3 - <<'PY'
from pathlib import Path
p = Path("android/keystore.properties")
lines = []
for line in p.read_text().splitlines():
    if line.startswith("storeFile="):
        lines.append("storeFile=keystore/ardtt-release.keystore")
    else:
        lines.append(line)
p.write_text("\n".join(lines) + "\n")
PY

echo "Wrote $OUT_DIR/ardtt-release.keystore and $PROPS_OUT"
echo "Build: cd android && ./gradlew :app:assembleRelease :app:bundleRelease"
