# shellcheck shell=bash
# Identify this ARDTT instance by recorded IDs and labels, not name prefixes.

OWNER_LABEL="com.ardtt.owner"
INSTANCE_LABEL="com.ardtt.instance"

instance_file() { printf '%s' "${INSTALL_DIR}/instance.json"; }
pending_instance_file() { printf '%s' "${INSTALL_DIR}/instance.pending.json"; }

load_instance() {
  local f
  f="$(instance_file)"
  [ -f "$f" ] || return 1
  INSTANCE_ID="$(json_get "$f" instanceId)"
  COMPOSE_PROJECT="$(json_get "$f" composeProject)"
  ARDTT_CONTAINER_NAME="$(json_get "$f" containerName)"
  ARDTT_NETWORK_NAME="$(json_get "$f" networkName)"
  ARDTT_BRIDGE_SUBNET="$(json_get "$f" bridgeSubnet)"
  PREV_IMAGE_ID="$(json_get "$f" imageId)"
  PREV_IMAGE_TAG="$(json_get "$f" imageTag)"
  [ -n "$INSTANCE_ID" ]
}

load_pending_instance() {
  local f
  f="$(pending_instance_file)"
  [ -f "$f" ] || return 1
  INSTANCE_ID="$(json_get "$f" instanceId)"
  COMPOSE_PROJECT="$(json_get "$f" composeProject)"
  ARDTT_CONTAINER_NAME="$(json_get "$f" containerName)"
  ARDTT_NETWORK_NAME="$(json_get "$f" networkName)"
  ARDTT_BRIDGE_SUBNET="$(json_get "$f" bridgeSubnet)"
  PREV_IMAGE_ID="$(json_get "$f" imageId)"
  PREV_IMAGE_TAG="$(json_get "$f" imageTag)"
  [ -n "$INSTANCE_ID" ]
}

# Failed first install used to write current/.env before instance.json.
# Uninstall still accepts either source so a restarting container is not left.
load_instance_from_env() {
  local envf
  for envf in "${INSTALL_DIR}/current/.env" "${INSTALL_DIR}/.env"; do
    [ -f "$envf" ] || continue
    INSTANCE_ID="$(env_file_val "$envf" ARDTT_INSTANCE_ID)"
    COMPOSE_PROJECT="$(env_file_val "$envf" COMPOSE_PROJECT_NAME)"
    ARDTT_CONTAINER_NAME="$(env_file_val "$envf" ARDTT_CONTAINER_NAME)"
    ARDTT_NETWORK_NAME="$(env_file_val "$envf" ARDTT_NETWORK_NAME)"
    ARDTT_BRIDGE_SUBNET="$(env_file_val "$envf" ARDTT_BRIDGE_SUBNET)"
    PREV_IMAGE_TAG="$(env_file_val "$envf" ARDTT_IMAGE)"
    if [ -n "$INSTANCE_ID" ] && [ -n "${ARDTT_CONTAINER_NAME:-}" ]; then
      return 0
    fi
  done
  return 1
}

write_instance() {
  mkdir -p "$INSTALL_DIR"
  python3 - "$INSTALL_DIR/instance.json" <<PY
import json,sys,time
path=sys.argv[1]
data={
  "instanceId": "${INSTANCE_ID}",
  "composeProject": "${COMPOSE_PROJECT}",
  "containerName": "${ARDTT_CONTAINER_NAME}",
  "networkName": "${ARDTT_NETWORK_NAME}",
  "bridgeSubnet": "${ARDTT_BRIDGE_SUBNET}",
  "imageId": "${LOADED_IMAGE_ID:-${PKG_IMAGE_ID:-}}",
  "imageTag": "${ARDTT_IMAGE:-}",
  "deployVersion": "${DEPLOY_VERSION}",
  "role": "${ROLE}",
  "updatedAt": int(time.time()),
}
open(path,"w",encoding="utf-8").write(json.dumps(data,indent=2)+"\n")
PY
  chmod 600 "$INSTALL_DIR/instance.json" 2>/dev/null || true
}

write_pending_instance() {
  mkdir -p "$INSTALL_DIR"
  python3 - "$(pending_instance_file)" <<PY
import json,sys,time
path=sys.argv[1]
data={
  "instanceId": "${INSTANCE_ID}",
  "composeProject": "${COMPOSE_PROJECT}",
  "containerName": "${ARDTT_CONTAINER_NAME}",
  "networkName": "${ARDTT_NETWORK_NAME}",
  "bridgeSubnet": "${ARDTT_BRIDGE_SUBNET}",
  "imageId": "${LOADED_IMAGE_ID:-${PKG_IMAGE_ID:-}}",
  "imageTag": "${ARDTT_IMAGE:-}",
  "deployVersion": "${DEPLOY_VERSION}",
  "role": "${ROLE}",
  "pending": True,
  "updatedAt": int(time.time()),
}
open(path,"w",encoding="utf-8").write(json.dumps(data,indent=2)+"\n")
PY
  chmod 600 "$(pending_instance_file)" 2>/dev/null || true
}

clear_pending_instance() {
  rm -f "$(pending_instance_file)"
}

snapshot_confirmed_metadata() {
  local dest="$1"
  mkdir -p "$dest"
  if [ -f "$(instance_file)" ]; then
    cp -a "$(instance_file)" "$dest/instance.json"
  fi
  if [ -f "$INSTALL_DIR/.env" ]; then
    cp -a "$INSTALL_DIR/.env" "$dest/root.env"
  fi
  if [ -f "$INSTALL_DIR/DEPLOY_VERSION" ]; then
    cp -a "$INSTALL_DIR/DEPLOY_VERSION" "$dest/DEPLOY_VERSION"
  fi
}

new_instance_id() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 8
  else
    python3 -c 'import secrets; print(secrets.token_hex(8))'
  fi
}

ensure_instance() {
  if load_instance; then
    return 0
  fi
  INSTANCE_ID="${ARDTT_INSTANCE_ID:-$(new_instance_id)}"
  COMPOSE_PROJECT="${ARDTT_COMPOSE_PROJECT:-ardtt${INSTANCE_ID}}"
  ARDTT_NETWORK_NAME="${ARDTT_NETWORK_NAME:-ardtt-${INSTANCE_ID}}"
  choose_container_name
}

docker_object_label() {
  local kind="$1" name="$2" key="$3"
  docker inspect -f "{{index .Config.Labels \"${key}\"}}" "$name" 2>/dev/null \
    || docker inspect -f "{{index .Labels \"${key}\"}}" "$name" 2>/dev/null \
    || true
}

object_owned_by_us() {
  local kind="$1" name="$2"
  local owner inst
  owner="$(docker_object_label "$kind" "$name" "$OWNER_LABEL")"
  inst="$(docker_object_label "$kind" "$name" "$INSTANCE_LABEL")"
  [ "$owner" = "ardtt" ] && [ -n "$INSTANCE_ID" ] && [ "$inst" = "$INSTANCE_ID" ]
}

choose_container_name() {
  local want="${ARDTT_CONTAINER_NAME:-ardtt}"
  # Dry-run never creates a container; do not inspect the host Docker store.
  if [ "${ARDTT_DRY_RUN:-0}" = "1" ] || ! command -v docker >/dev/null 2>&1; then
    ARDTT_CONTAINER_NAME="$want"
    return 0
  fi
  if ! docker inspect "$want" >/dev/null 2>&1; then
    ARDTT_CONTAINER_NAME="$want"
    return 0
  fi
  if object_owned_by_us container "$want"; then
    ARDTT_CONTAINER_NAME="$want"
    return 0
  fi
  if container_looks_like_legacy_ardtt "$want"; then
    ARDTT_CONTAINER_NAME="$want"
    return 0
  fi
  echo "ARDTT_WARN|имя контейнера ${want} занято чужим объектом — используем ardtt-${INSTANCE_ID}"
  ARDTT_CONTAINER_NAME="ardtt-${INSTANCE_ID}"
  if docker inspect "$ARDTT_CONTAINER_NAME" >/dev/null 2>&1 && ! object_owned_by_us container "$ARDTT_CONTAINER_NAME"; then
    die "Имя контейнера ${ARDTT_CONTAINER_NAME} занято чужим объектом — не удаляем его"
  fi
}

container_looks_like_legacy_ardtt() {
  local name="$1" mounts img
  docker inspect "$name" >/dev/null 2>&1 || return 1
  mounts="$(docker inspect -f '{{range .Mounts}}{{.Source}} {{end}}' "$name" 2>/dev/null || true)"
  echo "$mounts" | grep -q "${INSTALL_DIR}/stack/data\|${INSTALL_DIR}/data" || return 1
  img="$(docker inspect -f '{{.Config.Image}}' "$name" 2>/dev/null || true)"
  echo "$img" | grep -Eq 'ardtt|stack-ardtt' || return 1
  return 0
}

list_owned_containers() {
  command -v docker >/dev/null 2>&1 || return 0
  docker ps -a --filter "label=${OWNER_LABEL}=ardtt" --filter "label=${INSTANCE_LABEL}=${INSTANCE_ID}" --format '{{.ID}}' 2>/dev/null || true
}

list_owned_networks() {
  docker network ls --filter "label=${OWNER_LABEL}=ardtt" --filter "label=${INSTANCE_LABEL}=${INSTANCE_ID}" --format '{{.ID}}' 2>/dev/null || true
}

remove_owned_networks() {
  local id
  for id in $(list_owned_networks); do
    docker network rm "$id" >/dev/null 2>&1 || true
  done
}

stop_owned_stack() {
  local dir="$1" proj="${COMPOSE_PROJECT:-}"
  if [ -f "$dir/docker-compose.yml" ] && [ -n "$proj" ]; then
    (
      cd "$dir"
      compose_up_cmd down --remove-orphans || true
    ) >/dev/null 2>&1 || true
  fi
  local id
  for id in $(list_owned_containers); do
    docker stop "$id" >/dev/null 2>&1 || true
    docker rm -f "$id" >/dev/null 2>&1 || true
  done
}
