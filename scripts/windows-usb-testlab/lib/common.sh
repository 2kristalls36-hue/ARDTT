#!/usr/bin/env bash
# Shared helpers for ARDTT USB lab bash scripts.
set -euo pipefail

_ARDTT_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_ARDTT_LAB_DIR="$(cd "$_ARDTT_LIB_DIR/.." && pwd)"

ardtt_lab_root() {
  printf '%s\n' "$_ARDTT_LAB_DIR"
}

ardtt_load_versions() {
  local file
  file="$(ardtt_lab_root)/versions.env"
  # shellcheck disable=SC1090
  set -a
  # shellcheck disable=SC1091
  source "$file"
  set +a
}

ardtt_utc_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

ardtt_log() {
  local level=$1
  shift
  printf '%s [%s] %s\n' "$(ardtt_utc_now)" "$level" "$*"
}

ardtt_die() {
  ardtt_log ERROR "$*"
  exit 1
}

ardtt_require_cmd() {
  local name=$1
  command -v "$name" >/dev/null 2>&1 || ardtt_die "нужна команда: $name"
}

ardtt_sha256_file() {
  local path=$1
  sha256sum -- "$path" | awk '{print $1}'
}

ardtt_native_ok() {
  local code=$1
  local label=$2
  if [[ "$code" -ne 0 ]]; then
    ardtt_die "команда завершилась с кодом ${code}: ${label}"
  fi
}

ardtt_free_bytes() {
  local path=$1
  df -B1 --output=avail "$path" | awk 'NR==2 {print $1}'
}

ardtt_json_write() {
  python3 - "$@" <<'PY'
import json, sys
path = sys.argv[1]
obj = json.loads(sys.argv[2])
with open(path, "w", encoding="utf-8") as fh:
    json.dump(obj, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
PY
}

ardtt_prepend_path() {
  local dir=$1
  case ":$PATH:" in
    *":$dir:"*) ;;
    *) PATH="$dir${PATH:+:$PATH}"; export PATH ;;
  esac
}
