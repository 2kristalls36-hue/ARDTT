#!/bin/bash
# Refuse host-wide dataplane mutation when this process shares the VPS netns.
# Isolated Compose gives the container its own namespace; docker0/cni0 then
# do not exist here. Hostnet / nsenter-to-host would see them.
ardtt_in_host_netns() {
  ip link show docker0 >/dev/null 2>&1 && return 0
  ip link show cni0 >/dev/null 2>&1 && return 0
  [ "${ARDTT_NETWORK_MODE:-isolated}" = "hostnet" ] && return 0
  [ "${ARDTT_NETWORK_MODE:-}" = "host" ] && return 0
  return 1
}

ardtt_require_container_netns() {
  if ardtt_in_host_netns; then
    echo "[ardtt] refusing dataplane changes: this looks like the host netns (docker0/cni0 or ARDTT_NETWORK_MODE=hostnet)" >&2
    return 1
  fi
  return 0
}
