#!/usr/bin/env bash
# Capture device state via ADB. Fail-soft per command; never abort the whole snapshot.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/common.sh"

OUT=""
SERIAL="${ANDROID_SERIAL:-}"
ADB_BIN="${ADB:-adb}"
CATALOG="$HERE/capture-commands.json"

usage() {
  cat <<'EOF'
Usage: capture-state.sh --out DIR [--serial SERIAL] [--adb PATH] [--catalog JSON]
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT=$2; shift 2 ;;
    --serial) SERIAL=$2; shift 2 ;;
    --adb) ADB_BIN=$2; shift 2 ;;
    --catalog) CATALOG=$2; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) ardtt_die "неизвестный аргумент: $1" ;;
  esac
done

[[ -n "$OUT" ]] || ardtt_die "--out обязателен"
[[ -f "$CATALOG" ]] || ardtt_die "нет каталога команд: $CATALOG"
[[ -x "$ADB_BIN" || -n "$(command -v "$ADB_BIN" 2>/dev/null || true)" ]] || ardtt_die "adb не найден: $ADB_BIN"
mkdir -p "$OUT/dumps" "$OUT/meta"

INDEX="$OUT/capture-index.json"
python3 - "$ADB_BIN" "$SERIAL" "$CATALOG" "$OUT" <<'PY'
import json, os, subprocess, sys, time
from pathlib import Path

adb, serial, catalog_path, out = sys.argv[1:5]
out_p = Path(out)
commands = json.loads(Path(catalog_path).read_text(encoding="utf-8"))
results = []
base = [adb]
if serial:
    base += ["-s", serial]

for spec in commands:
    cid = spec["id"]
    timeout = int(spec.get("timeoutSec", 20))
    args = spec.get("args") or []
    stdout_path = out_p / "dumps" / f"{cid}.txt"
    stderr_path = out_p / "dumps" / f"{cid}.stderr.txt"
    cmd = base + args
    started = time.time()
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            timeout=timeout,
            check=False,
        )
        stdout_path.write_bytes(proc.stdout)
        stderr_path.write_bytes(proc.stderr)
        code = proc.returncode
        status = "ok" if code == 0 else "unavailable"
        err = None
    except subprocess.TimeoutExpired as exc:
        stdout_path.write_bytes(exc.stdout or b"")
        stderr_path.write_bytes((exc.stderr or b"") + b"\nTIMEOUT\n")
        code = 124
        status = "timeout"
        err = f"timeout after {timeout}s"
    except FileNotFoundError:
        code = 127
        status = "unavailable"
        err = "adb missing"
        stdout_path.write_text("", encoding="utf-8")
        stderr_path.write_text(err + "\n", encoding="utf-8")
    elapsed_ms = int((time.time() - started) * 1000)
    rec = {
        "id": cid,
        "status": status,
        "exitCode": code,
        "timeoutSec": timeout,
        "elapsedMs": elapsed_ms,
        "optional": bool(spec.get("optional")),
        "stdout": str(stdout_path.relative_to(out_p).as_posix()),
        "stderr": str(stderr_path.relative_to(out_p).as_posix()),
        "command": args,
    }
    if err:
        rec["error"] = err
    results.append(rec)
    print(f"{cid}\t{status}\t{code}", flush=True)

index = {
    "schema": "ardtt-lab-capture/v1",
    "serialPresent": bool(serial),
    "adb": adb,
    "results": results,
}
(out_p / "capture-index.json").write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
hard_fail = [r for r in results if r["status"] == "unavailable" and not r["optional"] and r["exitCode"] == 127 and r["id"] == "missing"]
# never abort: index is the source of truth
print(json.dumps({"captured": len(results), "ok": sum(1 for r in results if r["status"] == "ok")}))
PY
