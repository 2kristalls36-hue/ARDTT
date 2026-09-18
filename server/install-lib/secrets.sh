# shellcheck shell=bash
# Host-side secrets for provision/telemetry. Files are 0600 under data/.
# Never write tokens into .env or compose environment interpolation.

ENSURE_SECRET_CREATED=0

ensure_secret_file() {
  local path="$1"
  ENSURE_SECRET_CREATED=0
  mkdir -p "$(dirname "$path")"
  if [ -s "$path" ]; then
    chmod 600 "$path" 2>/dev/null || true
    return 0
  fi
  python3 - "$path" <<'PY'
import os, sys
path = sys.argv[1]
token = os.urandom(32).hex()
tmp = path + ".tmp"
fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.write(fd, (token + "\n").encode())
os.close(fd)
os.replace(tmp, path)
os.chmod(path, 0o600)
PY
  ENSURE_SECRET_CREATED=1
}

read_secret_file() {
  local path="$1"
  [ -s "$path" ] || return 1
  tr -d '[:space:]' <"$path"
}

host_publish_bind() {
  case "${ARDTT_PROVISION_PUBLIC:-0}" in
    1|true|yes|on|TRUE|YES|ON) printf '0.0.0.0' ;;
    *) printf '127.0.0.1' ;;
  esac
}

ensure_install_secrets() {
  local data="${1:-${INSTALL_DIR}/data}"
  ADMIN_TOKEN_NEW=0
  CASCADE_SECRET_NEW=0
  TELEMETRY_TOKEN_NEW=0
  ADMIN_TOKEN=""
  CASCADE_SECRET=""
  TELEMETRY_TOKEN=""
  mkdir -p "$data"
  chmod 700 "$data" 2>/dev/null || true

  if [ -n "${ARDTT_ADMIN_TOKEN:-}" ]; then
    printf '%s\n' "$ARDTT_ADMIN_TOKEN" >"$data/admin.token"
    chmod 600 "$data/admin.token"
    ADMIN_TOKEN="$ARDTT_ADMIN_TOKEN"
  else
    ensure_secret_file "$data/admin.token"
    [ "$ENSURE_SECRET_CREATED" = 1 ] && ADMIN_TOKEN_NEW=1
    ADMIN_TOKEN="$(read_secret_file "$data/admin.token")"
  fi

  if [ -n "${ARDTT_TELEMETRY_TOKEN:-}" ]; then
    printf '%s\n' "$ARDTT_TELEMETRY_TOKEN" >"$data/telemetry.token"
    chmod 600 "$data/telemetry.token"
    TELEMETRY_TOKEN="$ARDTT_TELEMETRY_TOKEN"
  else
    ensure_secret_file "$data/telemetry.token"
    [ "$ENSURE_SECRET_CREATED" = 1 ] && TELEMETRY_TOKEN_NEW=1
    TELEMETRY_TOKEN="$(read_secret_file "$data/telemetry.token")"
  fi

  if [ -n "${ARDTT_CASCADE_SECRET:-}" ]; then
    printf '%s\n' "$ARDTT_CASCADE_SECRET" >"$data/cascade.secret"
    chmod 600 "$data/cascade.secret"
    CASCADE_SECRET="$ARDTT_CASCADE_SECRET"
  elif [ "${ROLE:-entry}" = "exit" ] || [ "${CASCADE_ENABLED:-0}" = "1" ]; then
    ensure_secret_file "$data/cascade.secret"
    [ "$ENSURE_SECRET_CREATED" = 1 ] && CASCADE_SECRET_NEW=1
    CASCADE_SECRET="$(read_secret_file "$data/cascade.secret" || true)"
  fi
}

read_provision_cert_fp() {
  local port="${1:-9100}"
  python3 - "$port" <<'PY' 2>/dev/null || true
import json, sys, urllib.request
port = sys.argv[1]
url = f"http://127.0.0.1:{port}/health"
try:
    with urllib.request.urlopen(url, timeout=3) as r:
        data = json.load(r)
    fp = (data or {}).get("provisionCertFp") or ""
    if isinstance(fp, str):
        sys.stdout.write(fp.strip())
except Exception:
    pass
PY
}

done_secret_fields() {
  local extra=""
  if [ "${ADMIN_TOKEN_NEW:-0}" = 1 ] && [ -n "${ADMIN_TOKEN:-}" ]; then
    extra="${extra}|admin_token=${ADMIN_TOKEN}"
  fi
  if [ "${CASCADE_SECRET_NEW:-0}" = 1 ] && [ -n "${CASCADE_SECRET:-}" ]; then
    extra="${extra}|cascade_secret=${CASCADE_SECRET}"
  fi
  if [ "${TELEMETRY_TOKEN_NEW:-0}" = 1 ] && [ -n "${TELEMETRY_TOKEN:-}" ]; then
    extra="${extra}|telemetry_token=${TELEMETRY_TOKEN}"
  fi
  if [ -n "${PROVISION_CERT_FP:-}" ]; then
    extra="${extra}|provision_cert_fp=${PROVISION_CERT_FP}"
  fi
  printf '%s' "$extra"
}
