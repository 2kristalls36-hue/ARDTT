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

# Host IP of the running container's published provision port.
# Empty HostIp is Docker's all-interfaces publish. No container → empty.
_running_provision_host_ip() {
  local name="${ARDTT_CONTAINER_NAME:-ardtt}"
  command -v docker >/dev/null 2>&1 || return 0
  docker inspect "$name" >/dev/null 2>&1 || return 0
  docker inspect -f '{{json .HostConfig.PortBindings}}' "$name" 2>/dev/null \
    | python3 -c '
import json, sys
raw = sys.stdin.read()
if not raw.strip():
    raise SystemExit(0)
try:
    data = json.loads(raw)
except Exception:
    raise SystemExit(0)
binds = (data or {}).get("9100/tcp") or []
if not binds:
    raise SystemExit(0)
ip = (binds[0] or {}).get("HostIp")
print("0.0.0.0" if ip is None or ip == "" else ip)
' || true
}

# public / private / empty from ARDTT_PROVISION_BIND or ARDTT_PROVISION_PUBLIC.
# ARDTT_PROVISION_LISTEN is the in-container address and is not a publish signal.
_recorded_provision_publish() {
  local f bind pub saw_private=0
  for f in "${INSTALL_DIR}/.env" "${INSTALL_DIR}/current/.env"; do
    [ -f "$f" ] || continue
    bind="$(env_file_val "$f" ARDTT_PROVISION_BIND)"
    if [ -n "$bind" ]; then
      case "$bind" in
        127.*|localhost|::1) saw_private=1 ;;
        *) printf public; return 0 ;;
      esac
      continue
    fi
    pub="$(env_file_val "$f" ARDTT_PROVISION_PUBLIC)"
    if [ -n "$pub" ]; then
      case "$pub" in
        1|true|yes|on|TRUE|YES|ON) printf public; return 0 ;;
        *) saw_private=1 ;;
      esac
    fi
  done
  if [ "$saw_private" = 1 ]; then
    printf private
  fi
}

# Stacks before the host-bind default published "PORT:9100/tcp" with no host IP.
_legacy_compose_publishes_all() {
  local f
  for f in "${INSTALL_DIR}/current/docker-compose.yml" "${INSTALL_DIR}/previous/docker-compose.yml"; do
    [ -f "$f" ] || continue
    if python3 - "$f" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for spec in re.findall(r"""["']([^"']*:9100/tcp)["']""", text):
    # ${VAR:-default} contains a colon. A host IP or ARDTT_PROVISION_BIND
    # means the publish address is chosen elsewhere; a bare PORT:9100/tcp
    # is the pre-1.0.54 all-interfaces form.
    if "ARDTT_PROVISION_BIND" in spec:
        continue
    if re.search(r"127(?:\.\d+){3}", spec):
        continue
    raise SystemExit(0)
raise SystemExit(1)
PY
    then
      return 0
    fi
  done
  return 1
}

# public or private. Caller flag is handled by inherit_provision_publish.
resolve_inherited_provision_publish() {
  local ip mode=""
  ip="$(_running_provision_host_ip || true)"
  if [ -n "$ip" ]; then
    case "$ip" in
      127.*|localhost|::1) ;;
      *) printf public; return 0 ;;
    esac
  fi
  mode="$(_recorded_provision_publish || true)"
  if [ "$mode" = "public" ]; then
    printf public
    return 0
  fi
  if _legacy_compose_publishes_all; then
    printf public
    return 0
  fi
  printf private
}

# Phone deploy does not pass ARDTT_PROVISION_PUBLIC. Keep an already reachable
# provision/telemetry publish; a fresh install stays on 127.0.0.1.
inherit_provision_publish() {
  if [ -n "${ARDTT_PROVISION_PUBLIC+x}" ]; then
    return 0
  fi
  local mode
  mode="$(resolve_inherited_provision_publish)"
  if [ "$mode" = "public" ]; then
    ARDTT_PROVISION_PUBLIC=1
    echo "ARDTT_INFO|provision/telemetry остаются доступны снаружи: так порт был опубликован до обновления"
  else
    ARDTT_PROVISION_PUBLIC=0
  fi
  export ARDTT_PROVISION_PUBLIC
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
