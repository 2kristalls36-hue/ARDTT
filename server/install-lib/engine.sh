# shellcheck shell=bash
# Install Docker Engine from vendor/docker.tgz in the ARDTT package.
# Never fetches a network Engine installer or apt/dnf. Leaves a working Engine alone.

ARDTT_ENGINE_LIB="${ARDTT_ENGINE_LIB:-/usr/local/lib/ardtt-docker}"
ARDTT_ENGINE_BINDIR="${ARDTT_ENGINE_BINDIR:-/usr/local/bin}"
ARDTT_ENGINE_MARKER="# ARDTT-bundled-docker"

docker_info_ok() {
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1
}

engine_tarball() {
  local dir="${PKG_DIR:-}"
  if [ -n "$dir" ] && [ -f "$dir/vendor/docker.tgz" ]; then
    printf '%s' "$dir/vendor/docker.tgz"
    return 0
  fi
  return 1
}

engine_expected_sha() {
  local lock="${PKG_DIR:-}/third-party.lock.json" arch
  arch="$(host_arch)"
  [ -f "$lock" ] || return 1
  python3 - "$lock" "$arch" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
arch = sys.argv[2]
print(((d.get("dockerEngineStatic") or {}).get("sha256") or {}).get(arch) or "")
PY
}

engine_unit_is_ours() {
  local unit="/etc/systemd/system/docker.service"
  [ -f "$unit" ] || return 1
  grep -q "$ARDTT_ENGINE_MARKER" "$unit"
}

engine_refuse_foreign() {
  if command -v podman >/dev/null 2>&1; then
    die --code UNSUPPORTED_RUNTIME "На хосте podman. Это не чистый VPS, чужой runtime не трогаем."
  fi
  if command -v kubelet >/dev/null 2>&1; then
    die --code UNSUPPORTED_RUNTIME "На хосте kubelet. Это не чистый VPS."
  fi
  if [ -S /run/containerd/containerd.sock ]; then
    die --code UNSUPPORTED_RUNTIME "На хосте containerd без Docker. Чужой runtime не трогаем."
  fi
}

write_engine_units() {
  local bindir="$1" lib="${ARDTT_ENGINE_LIB}"
  mkdir -p "$lib" /etc/systemd/system /run/ardtt-containerd
  cat > "$lib/containerd.toml" <<EOF
version = 2
root = "/var/lib/ardtt-containerd"
state = "/run/ardtt-containerd"
[grpc]
  address = "/run/ardtt-containerd/containerd.sock"
EOF
  cat > /etc/systemd/system/ardtt-containerd.service <<EOF
[Unit]
Description=ARDTT bundled containerd
Documentation=https://github.com/2kristalls36-hue/ARDTT
After=network-online.target
Wants=network-online.target

[Service]
Type=notify
ExecStart=${bindir}/containerd --config ${lib}/containerd.toml
Restart=on-failure
LimitNOFILE=1048576
LimitNPROC=infinity
TasksMax=infinity
Delegate=yes
KillMode=process
OOMScoreAdjust=-999

[Install]
WantedBy=multi-user.target
EOF
  cat > /etc/systemd/system/docker.service <<EOF
${ARDTT_ENGINE_MARKER}
[Unit]
Description=ARDTT bundled Docker Engine
Documentation=https://github.com/2kristalls36-hue/ARDTT
After=network-online.target ardtt-containerd.service
Requires=ardtt-containerd.service
Wants=network-online.target

[Service]
Type=notify
ExecStart=${bindir}/dockerd --host=unix:///var/run/docker.sock --containerd=/run/ardtt-containerd/containerd.sock
ExecReload=/bin/kill -s HUP \$MAINPID
TimeoutStartSec=0
Restart=on-failure
LimitNOFILE=1048576
LimitNPROC=infinity
LimitCORE=infinity
TasksMax=infinity
Delegate=yes
KillMode=process
OOMScoreAdjust=-500

[Install]
WantedBy=multi-user.target
EOF
}

install_engine_binaries() {
  local tarfile="$1" tmp expect got
  [ -f "$tarfile" ] || die --code DOCKER_MISSING "В архиве нет vendor/docker.tgz — Docker Engine должен быть в едином пакете ARDTT."
  expect="$(engine_expected_sha || true)"
  [ -n "$expect" ] || die --code DOCKER_MISSING "Нет закреплённой SHA-256 Docker Engine в third-party.lock.json."
  got="$(sha256_file "$tarfile")"
  got="$(printf '%s' "$got" | tr 'A-F' 'a-f')"
  expect="$(printf '%s' "$expect" | tr 'A-F' 'a-f' | tr -d '[:space:]')"
  if [ "$got" != "$expect" ]; then
    die --code DOCKER_MISSING "SHA-256 vendor/docker.tgz не совпала с lock (ожидали ${expect}, получили ${got})."
  fi
  tmp="$(mktemp -d)"
  tar -xzf "$tarfile" -C "$tmp"
  [ -x "$tmp/docker/dockerd" ] || die --code DOCKER_MISSING "vendor/docker.tgz без dockerd."
  mkdir -p "$ARDTT_ENGINE_LIB" "$ARDTT_ENGINE_BINDIR"
  cp -a "$tmp/docker/." "$ARDTT_ENGINE_LIB/"
  rm -rf "$tmp"
  chmod 755 "$ARDTT_ENGINE_LIB"/*
  local b
  for b in docker dockerd containerd runc containerd-shim-runc-v2 docker-proxy docker-init ctr; do
    [ -e "$ARDTT_ENGINE_LIB/$b" ] || continue
    ln -sfn "$ARDTT_ENGINE_LIB/$b" "$ARDTT_ENGINE_BINDIR/$b"
  done
  hash -r 2>/dev/null || true
  echo "ARDTT_INFO|Docker Engine из пакета → $ARDTT_ENGINE_LIB"
}

start_bundled_engine() {
  command -v systemctl >/dev/null 2>&1 || die --code DOCKER_NOT_RUNNING "Нет systemd — нечем запустить dockerd из пакета."
  export PATH="${ARDTT_ENGINE_BINDIR}:${PATH}"
  hash -r 2>/dev/null || true
  modprobe overlay 2>/dev/null || true
  modprobe br_netfilter 2>/dev/null || true
  systemctl daemon-reload
  systemctl enable ardtt-containerd.service docker.service >/dev/null 2>&1 || true
  systemctl start ardtt-containerd.service
  systemctl start docker.service
  local i
  for i in $(seq 1 40); do
    if docker_info_ok; then
      return 0
    fi
    sleep 1
  done
  local info
  info="$(docker info 2>&1 || true)"
  die --code DOCKER_NOT_RUNNING "dockerd из пакета не ответил за 40 с. ${info}"
}

ensure_docker_engine() {
  if docker_info_ok; then
    return 0
  fi
  local info=""
  if command -v docker >/dev/null 2>&1; then
    info="$(docker info 2>&1 || true)"
    if printf '%s' "$info" | grep -qiE 'permission denied|access denied'; then
      die --code DOCKER_ACCESS_DENIED "Нет доступа к Docker. Запустите установку от root или в группе docker. Существующий Engine не переустанавливаем."
    fi
    if engine_unit_is_ours; then
      echo "ARDTT_INFO|перезапуск своего bundled dockerd"
      start_bundled_engine
      return 0
    fi
    die --code DOCKER_NOT_RUNNING "Docker CLI есть, демон не отвечает. Установщик не подменяет чужой Engine. Запустите службу docker и повторите."
  fi
  engine_refuse_foreign
  local tarfile
  tarfile="$(engine_tarball)" || die --code DOCKER_MISSING "В архиве нет vendor/docker.tgz. Docker Engine ставится из пакета, без сетевого установщика Engine."
  if [ -f /lib/systemd/system/docker.service ] || [ -f /usr/lib/systemd/system/docker.service ] \
    || [ -f /lib/systemd/system/docker.socket ] || [ -f /usr/lib/systemd/system/docker.socket ]; then
    die --code DOCKER_NOT_RUNNING "На хосте есть unit docker.service, но нет рабочего CLI. Чужой Engine не трогаем."
  fi
  command -v iptables >/dev/null 2>&1 || die --code IPTABLES_MISSING "Нужен iptables на хосте — без него Docker не поднимет сети. Поставьте пакет дистрибутива (Debian/Ubuntu: apt install iptables; RHEL/Fedora: dnf install iptables-nft) и повторите. Пакет не ставит его из сети."
  prog 0.16 "Установка Docker Engine из архива ARDTT"
  install_engine_binaries "$tarfile"
  write_engine_units "$ARDTT_ENGINE_LIB"
  start_bundled_engine
}
