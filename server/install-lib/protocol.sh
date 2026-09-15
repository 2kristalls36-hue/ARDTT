# shellcheck shell=bash
# Dual control protocol: JSONL protocol=2 plus legacy ARDTT_* for APK ≤0.5.264.
# If ardttctl is missing (old unpack / unit tests), fall back to echo.

ardtt_ctl_bin() {
  local c
  for c in \
    "${SCRIPT_DIR:-}/ardttctl" \
    "${PKG_DIR:-}/ardttctl" \
    "${INSTALL_DIR:-}/current/ardttctl" \
    "${SELF_DIR:-}/ardttctl"; do
    if [ -n "$c" ] && [ -f "$c" ]; then
      chmod 755 "$c" 2>/dev/null || true
      if [ -x "$c" ]; then
        printf '%s' "$c"
        return 0
      fi
    fi
  done
  return 1
}

ardtt_emit() {
  local bin
  bin="$(ardtt_ctl_bin)" || return 1
  "$bin" emit "$@"
}

ardtt_state_set() {
  local bin
  bin="$(ardtt_ctl_bin)" || return 0
  ARDTT_INSTALL_DIR="${INSTALL_DIR:-/opt/ardtt}" "$bin" state set "$@" 2>/dev/null || true
}

prog() {
  if ardtt_emit progress --frac "$1" --message "$2"; then
    return 0
  fi
  echo "ARDTT_PROGRESS|$1|$2"
}

die() {
  local code=""
  if [ "${1:-}" = "--code" ]; then
    code="$2"
    shift 2
  fi
  if [ -n "$code" ]; then
    if ! ardtt_emit error --code "$code" --message "$*"; then
      echo "ARDTT_ERROR|code=${code}|$*" >&2
    fi
  else
    if ! ardtt_emit error --message "$*"; then
      echo "ARDTT_ERROR|$*" >&2
    fi
  fi
  exit 1
}

emit_done() {
  local payload="$1"
  local bin args=() p k v parts
  if bin="$(ardtt_ctl_bin)"; then
    args=(emit done --no-legacy)
    IFS='|' read -ra parts <<< "$payload"
    for p in "${parts[@]}"; do
      [ -n "$p" ] || continue
      k="${p%%=*}"
      v="${p#*=}"
      [ -n "$k" ] || continue
      args+=("--${k}" "$v")
    done
    "$bin" "${args[@]}" || true
  fi
  echo "ARDTT_DONE|$payload"
}
