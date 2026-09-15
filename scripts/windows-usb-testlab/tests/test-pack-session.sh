#!/usr/bin/env bash
# RED/GREEN: pack-session.sh must redact secrets and write a SHA-256 manifest.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB="$(cd "$HERE/.." && pwd)"
PACK="$LAB/lib/pack-session.sh"

FAIL=0
fail() { echo "FAIL: $*" >&2; FAIL=1; }
ok() { echo "OK $*"; }

[[ -x "$PACK" ]] || { echo "FAIL: missing $PACK" >&2; exit 1; }

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
SESSION="$WORKDIR/session"
mkdir -p "$SESSION/raw"
printf 'Authorization: Bearer super-secret-token\nkeep=value\n' >"$SESSION/raw/logcat.txt"
printf '{"password":"hunter2","note":"ok"}\n' >"$SESSION/raw/app.json"
printf 'serial=SECRETPHONE\n' >"$SESSION/raw/device.txt"
cat >"$WORKDIR/aliases.json" <<'EOF'
{"SECRETPHONE":"device-01"}
EOF

OUT="$WORKDIR/out"
mkdir -p "$OUT"
"$PACK" --session "$SESSION" --output-dir "$OUT" --aliases "$WORKDIR/aliases.json" >/dev/null

ARCHIVE="$(find "$OUT" -maxdepth 1 -name 'ardtt-session-*.zip' | head -1)"
[[ -n "$ARCHIVE" ]] || fail "archive not created"
ok "archive $ARCHIVE"

MANIFEST="$OUT/share/manifest.json"
[[ -f "$MANIFEST" ]] || fail "manifest missing"
ok "manifest"

if unzip -p "$ARCHIVE" raw/logcat.txt | grep -q 'super-secret-token'; then
  fail "secret leaked in archive logcat"
else
  ok "logcat redacted"
fi
if unzip -p "$ARCHIVE" raw/app.json | grep -q 'hunter2'; then
  fail "password leaked"
else
  ok "json password redacted"
fi
if unzip -p "$ARCHIVE" raw/device.txt | grep -q 'SECRETPHONE'; then
  fail "serial leaked"
else
  ok "serial aliased"
fi
unzip -p "$ARCHIVE" raw/device.txt | grep -q 'device-01' || fail "alias missing"

python3 - "$MANIFEST" "$ARCHIVE" <<'PY' || fail "manifest schema"
import json, sys, zipfile
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert "files" in manifest and manifest["files"], "files"
z = zipfile.ZipFile(sys.argv[2])
names = set(z.namelist())
for item in manifest["files"]:
    assert item["path"] in names, item["path"]
    assert len(item["sha256"]) == 64, item
print("manifest ok", len(manifest["files"]))
PY

if [[ "$FAIL" -ne 0 ]]; then
  exit 1
fi
echo "pack-session tests passed"
