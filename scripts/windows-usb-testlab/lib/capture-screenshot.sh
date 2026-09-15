#!/usr/bin/env bash
# Pull a PNG via device file (PowerShell 5.1-safe method, also used on Linux).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/common.sh"

OUT=""
SERIAL="${ANDROID_SERIAL:-}"
ADB_BIN="${ADB:-adb}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT=$2; shift 2 ;;
    --serial) SERIAL=$2; shift 2 ;;
    --adb) ADB_BIN=$2; shift 2 ;;
    *) ardtt_die "неизвестный аргумент: $1" ;;
  esac
done
[[ -n "$OUT" ]] || ardtt_die "--out обязателен"

adb_args=()
if [[ -n "$SERIAL" ]]; then
  adb_args+=(-s "$SERIAL")
fi

stamp="$(date -u +%Y%m%dT%H%M%S)"
remote="/data/local/tmp/ardtt-screencap-${stamp}-$$.png"
"$ADB_BIN" "${adb_args[@]}" shell screencap -p "$remote"
mkdir -p "$(dirname "$OUT")"
"$ADB_BIN" "${adb_args[@]}" pull "$remote" "$OUT" >/dev/null
"$ADB_BIN" "${adb_args[@]}" shell rm -f "$remote" >/dev/null || true
[[ -s "$OUT" ]] || ardtt_die "пустой PNG: $OUT"
python3 - "$OUT" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
data = p.read_bytes()
if not data.startswith(b"\x89PNG\r\n\x1a\n"):
    raise SystemExit(f"not a PNG: {p} header={data[:16]!r}")
PY
printf '%s\n' "$OUT"
