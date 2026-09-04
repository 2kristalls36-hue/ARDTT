#!/bin/bash
# dnsmasq on VPN gateways:
#   Direct (awg0)  → 10.8.0.1
#   Bypass RAW     → 10.9.0.1
# Upstream: 1.1.1.1 / 1.0.0.1 via host main routing (not WARP).
set -euo pipefail

CONF_DIR="${ARDTT_DNS_CONF_DIR:-/tmp/ardtt-dnsmasq}"
CONF="${CONF_DIR}/dnsmasq.conf"
PID_FILE="${CONF_DIR}/dnsmasq.pid"
TMPL="${ARDTT_DNS_TMPL:-/etc/ardtt/dnsmasq.conf.tmpl}"
UPSTREAMS="${ARDTT_DNS_UPSTREAM:-1.1.1.1 1.0.0.1}"
POLL_SEC="${ARDTT_DNS_POLL_SEC:-5}"

mkdir -p "${CONF_DIR}"

echo "[dns] gateway resolver (dnsmasq); upstream=${UPSTREAMS}"

iface_has_addr() {
  local iface="$1" addr="$2"
  ip -4 -o addr show dev "${iface}" 2>/dev/null | grep -q " ${addr}/"
}

build_conf() {
  local out="$1"
  {
    cat "${TMPL}"
    echo
    # Direct AmneziaWG
    if iface_has_addr awg0 10.8.0.1; then
      echo "interface=awg0"
      echo "listen-address=10.8.0.1"
    fi
    # Bypass RAW
    if iface_has_addr wdttraw0 10.9.0.1; then
      echo "interface=wdttraw0"
      echo "listen-address=10.9.0.1"
    fi
    # Cascade hop (exit node DNS, right before internet / WARP)
    if iface_has_addr cascade0 10.10.0.2; then
      echo "interface=cascade0"
      echo "listen-address=10.10.0.2"
    fi
    local s
    for s in ${UPSTREAMS}; do
      echo "server=${s}"
    done
  } >"${out}"
}

conf_fingerprint() {
  # Only care about listen lines + servers for reload decisions.
  grep -E '^(interface|listen-address|server)=' "$1" 2>/dev/null | sort | md5sum | awk '{print $1}'
}

start_or_reload() {
  local fp new_fp
  build_conf "${CONF}.new"
  if ! grep -q '^listen-address=' "${CONF}.new"; then
    echo "[dns] waiting for awg0/wdttraw0/cascade0 addresses…"
    rm -f "${CONF}.new"
    return 1
  fi
  new_fp="$(conf_fingerprint "${CONF}.new")"
  if [[ -f "${CONF}" ]]; then
    fp="$(conf_fingerprint "${CONF}")"
  else
    fp=""
  fi
  mv "${CONF}.new" "${CONF}"

  if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
    if [[ "${fp}" == "${new_fp}" ]]; then
      return 0
    fi
    echo "[dns] config changed — restarting dnsmasq"
    grep -E '^(interface|listen-address|server)=' "${CONF}" | sed 's/^/[dns] /' || true
    kill "$(cat "${PID_FILE}")" 2>/dev/null || true
    sleep 0.5
  else
    echo "[dns] starting dnsmasq"
    grep -E '^(interface|listen-address|server)=' "${CONF}" | sed 's/^/[dns] /' || true
  fi

  # Drop stale pid
  rm -f "${PID_FILE}"
  dnsmasq --conf-file="${CONF}" --pid-file="${PID_FILE}" --log-facility=- \
    && echo "[dns] dnsmasq up" \
    || { echo "[dns] dnsmasq failed to start" >&2; return 1; }
}

# Ensure DNS replies from gateway aren't policy-routed oddly; local delivery uses table local.
while true; do
  start_or_reload || true
  sleep "${POLL_SEC}"
  # If dnsmasq died, loop will restart it.
  if [[ -f "${PID_FILE}" ]] && ! kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
    echo "[dns] dnsmasq died — will restart"
    rm -f "${PID_FILE}"
  fi
done
