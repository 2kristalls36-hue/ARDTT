#!/usr/bin/env bash
# Build a shareable zip from a session directory. Original files stay untouched.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$HERE/common.sh"
ardtt_require_cmd python3
ardtt_require_cmd zip
ardtt_require_cmd unzip

SESSION=""
OUTPUT_DIR=""
ALIASES=""
INCLUDE_RAW=0

usage() {
  cat <<'EOF'
Usage: pack-session.sh --session DIR --output-dir DIR [--aliases aliases.json] [--include-raw]
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --session) SESSION=$2; shift 2 ;;
    --output-dir) OUTPUT_DIR=$2; shift 2 ;;
    --aliases) ALIASES=$2; shift 2 ;;
    --include-raw) INCLUDE_RAW=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) ardtt_die "неизвестный аргумент: $1" ;;
  esac
done

[[ -n "$SESSION" && -d "$SESSION" ]] || ardtt_die "--session должен быть существующим каталогом"
[[ -n "$OUTPUT_DIR" ]] || ardtt_die "--output-dir обязателен"
mkdir -p "$OUTPUT_DIR"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
SHARE="$OUTPUT_DIR/share"
rm -rf "$SHARE"
mkdir -p "$SHARE"

python3 - "$SESSION" "$SHARE" "$ALIASES" "$HERE/redact.py" <<'PY'
import json, os, shutil, sys
from pathlib import Path

src = Path(sys.argv[1])
dst = Path(sys.argv[2])
aliases_path = sys.argv[3]
redact_path = sys.argv[4]

sys.path.insert(0, str(Path(redact_path).parent))
import redact

aliases = redact.load_aliases(aliases_path or None)
TEXT_SUFFIXES = {
    ".txt", ".log", ".json", ".jsonl", ".md", ".csv", ".properties", ".xml", ".prop",
}

def is_text(path: Path) -> bool:
    if path.suffix.lower() in TEXT_SUFFIXES:
        return True
    if path.name in {"session.json", "timeline.jsonl", "verdict.md", "manifest.json"}:
        return True
    return False

files = []
for root, dirnames, filenames in os.walk(src):
    dirnames[:] = [d for d in dirnames if d not in {".git"}]
    for name in filenames:
        path = Path(root) / name
        rel = path.relative_to(src).as_posix()
        out = dst / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        if is_text(path):
            raw = path.read_text(encoding="utf-8", errors="replace")
            out.write_text(redact.redact_text(raw, aliases=aliases, max_chars=10**9), encoding="utf-8")
        else:
            # Binary screenshots stay local-only unless they are PNG pulled by the lab.
            # Copy PNG/JPG as-is; they should not contain tokens.
            shutil.copy2(path, out)
        files.append(rel)

(dst / "_file_list.json").write_text(json.dumps(files, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

python3 - "$SHARE" <<'PY'
import hashlib, json, os, sys
from pathlib import Path
root = Path(sys.argv[1])
items = []
for dirpath, _, filenames in os.walk(root):
    for name in filenames:
        if name in {"_file_list.json", "manifest.json"}:
            continue
        path = Path(dirpath) / name
        rel = path.relative_to(root).as_posix()
        h = hashlib.sha256(path.read_bytes()).hexdigest()
        items.append({"path": rel, "bytes": path.stat().st_size, "sha256": h})
items.sort(key=lambda x: x["path"])
manifest = {
    "schema": "ardtt-lab-session-manifest/v1",
    "fileCount": len(items),
    "files": items,
}
(root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"files": len(items)}, ensure_ascii=False))
PY

ARCHIVE="$OUTPUT_DIR/ardtt-session-${STAMP}.zip"
(
  cd "$SHARE"
  zip -r -q "$ARCHIVE" .
)

if [[ "$INCLUDE_RAW" -eq 1 ]]; then
  ardtt_log WARN "include-raw: исходные файлы остаются только в $SESSION, в zip их нет"
fi

ardtt_log INFO "shareable archive: $ARCHIVE"
printf '%s\n' "$ARCHIVE"
