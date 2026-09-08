# shellcheck shell=bash
# Shared helpers for install.sh (sourced).

env_file_val() {
  local file="$1" key="$2" v=""
  [ -f "$file" ] || return 0
  v="$(grep -E "^${key}=" "$file" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\"' | tr -d "'" | tr -d '[:space:]')"
  if [ -z "$v" ] && [[ "$key" == ARDTT_* ]]; then
    v="$(grep -E "^NVPN_${key#ARDTT_}=" "$file" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\"' | tr -d "'" | tr -d '[:space:]')"
  fi
  printf '%s' "$v"
}

env_set_key() {
  local file="$1" key="$2" val="$3"
  [ -f "$file" ] || return 0
  if grep -qE "^${key}=" "$file" 2>/dev/null; then
    sed -i "s|^${key}=.*|${key}=${val}|" "$file"
  else
    printf '%s=%s\n' "$key" "$val" >> "$file"
  fi
}

host_arch() {
  local m
  m="$(uname -m 2>/dev/null || true)"
  case "$m" in
    x86_64|amd64) echo amd64 ;;
    aarch64|arm64) echo arm64 ;;
    *) echo "$m" ;;
  esac
}

mem_total_mb() {
  awk '/MemTotal:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0
}

mem_avail_mb() {
  awk '/MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0
}

disk_avail_mb() {
  local path="${1:-/}"
  df -Pm "$path" 2>/dev/null | awk 'NR==2 {print $4}'
}

json_get() {
  local file="$1" key="$2"
  [ -f "$file" ] || return 0
  if command -v jq >/dev/null 2>&1; then
    jq -r --arg k "$key" '.[$k] // empty' "$file" 2>/dev/null || true
  else
    python3 - "$file" "$key" <<'PY' 2>/dev/null || true
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
print(d.get(sys.argv[2],"") or "")
PY
  fi
}

sha256_file() {
  local f="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$f" | awk '{print $1}'
  else
    sha256sum <"$f" | awk '{print $1}'
  fi
}

compose_bin() {
  if [ -n "${ARDTT_COMPOSE_BIN:-}" ] && [ -x "$ARDTT_COMPOSE_BIN" ]; then
    printf '%s' "$ARDTT_COMPOSE_BIN"
    return 0
  fi
  if [ -x "${INSTALL_DIR:-/opt/ardtt}/bin/docker-compose" ]; then
    printf '%s' "${INSTALL_DIR}/bin/docker-compose"
    return 0
  fi
  if docker compose version >/dev/null 2>&1; then
    printf '%s' "docker compose"
    return 0
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    printf '%s' "docker-compose"
    return 0
  fi
  return 1
}
