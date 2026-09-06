#!/usr/bin/env bash
# Collect an ADB snapshot for operator-whitelist (БС) checks.
# Run on the machine that has the phone on USB debugging — not USB tethering.
set -euo pipefail

PACKAGE="${ARDTT_PACKAGE:-com.ardtt.app}"
VPS_HOST="${ARDTT_VPS_HOST:-45.129.2.3}"
VPS_PORT="${ARDTT_VPS_PORT:-9100}"
PHASE="unknown"
OUT=""
SERIAL="${ANDROID_SERIAL:-}"

usage() {
  cat <<'EOF'
Usage: device-bs-dump.sh [--phase underlay|tunnel|unknown] [--out DIR] [--serial SERIAL]

Captures phone state for ARDTT operator-whitelist (БС) verification.
Must run on a host that can see the device in `adb devices` (USB debugging
or a self-hosted Cursor worker on that host). USB tethering/RNDIS invalidates
the test: the phone then uses the computer's open internet.

Environment:
  ANDROID_SERIAL     device serial (or --serial)
  ARDTT_PACKAGE      default com.ardtt.app
  ARDTT_VPS_HOST     default 45.129.2.3
  ARDTT_VPS_PORT     default 9100

  --self-test        classify fixtures; no device required
EOF
}

adb_bin() {
  if [[ -n "$SERIAL" ]]; then
    adb -s "$SERIAL" "$@"
  else
    adb "$@"
  fi
}

# Trim CR from adb shell (Android uses CRLF).
sh_out() {
  adb_bin shell "$@" | tr -d '\r'
}

tcp_ok() {
  local host="$1" port="$2"
  local rc
  rc="$(sh_out "toybox timeout 2 sh -c 'echo | toybox nc -w 1 ${host} ${port} >/dev/null 2>&1'; echo \$?")" || rc="1"
  rc="$(echo "$rc" | tail -n 1 | tr -d '[:space:]')"
  [[ "$rc" == "0" ]]
}

bool_from() {
  case "${1:-}" in
    1|true|TRUE|yes|enabled|on) echo 1 ;;
    *) echo 0 ;;
  esac
}

# wifi tethering yandex cloudflare vps vpn -> class
classify_snapshot() {
  local wifi="$1" tethering="$2" yandex="$3" cloudflare="$4" vps="$5" vpn="$6"
  if [[ "$tethering" == "1" ]]; then
    echo "invalid_usb_tether"
    return
  fi
  if [[ "$wifi" == "1" && "$vpn" != "1" ]]; then
    echo "not_bs_wifi_on"
    return
  fi
  if [[ "$vpn" == "1" ]]; then
    if [[ "$cloudflare" == "1" || "$yandex" == "1" ]]; then
      echo "tunnel_up_default_route_via_vpn"
    else
      echo "tunnel_up_but_no_internet"
    fi
    return
  fi
  if [[ "$yandex" == "1" && "$cloudflare" == "0" ]]; then
    echo "looks_like_whitelist"
    return
  fi
  if [[ "$yandex" == "1" || "$cloudflare" == "1" ]]; then
    if [[ "$vps" == "1" ]]; then
      echo "open_internet_vps_up"
    else
      echo "open_or_partial_vps_down"
    fi
    return
  fi
  echo "no_network"
}

self_test() {
  local fail=0
  check() {
    local got
    got="$(classify_snapshot "$1" "$2" "$3" "$4" "$5" "$6")"
    if [[ "$got" != "$7" ]]; then
      echo "FAIL wifi=$1 tether=$2 y=$3 cf=$4 vps=$5 vpn=$6 → $got (want $7)" >&2
      fail=1
    fi
  }
  check 0 0 1 0 1 0 looks_like_whitelist
  check 0 0 1 0 0 0 looks_like_whitelist
  check 0 1 1 0 1 0 invalid_usb_tether
  check 1 0 1 1 1 0 not_bs_wifi_on
  check 0 0 1 1 1 0 open_internet_vps_up
  check 0 0 1 1 0 0 open_or_partial_vps_down
  check 0 0 0 0 0 0 no_network
  check 0 0 1 1 1 1 tunnel_up_default_route_via_vpn
  check 0 0 0 0 0 1 tunnel_up_but_no_internet
  check 1 0 1 0 1 1 tunnel_up_default_route_via_vpn
  if [[ "$fail" -ne 0 ]]; then
    echo "self-test failed" >&2
    exit 1
  fi
  echo "self-test ok"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --phase) PHASE="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --serial) SERIAL="${2:-}"; shift 2 ;;
    --self-test) self_test; exit 0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown arg: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$PHASE" in
  underlay|tunnel|unknown) ;;
  *) echo "invalid --phase $PHASE (use underlay|tunnel|unknown)" >&2; exit 2 ;;
esac

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not on PATH. Install platform-tools on the machine that has the phone." >&2
  exit 1
fi

adb_bin start-server >/dev/null
mapfile -t DEVICES < <(adb_bin devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "no authorized adb device. Plug USB, enable USB debugging, accept the RSA prompt." >&2
  adb_bin devices -l >&2 || true
  exit 1
fi
if [[ -z "$SERIAL" && ${#DEVICES[@]} -gt 1 ]]; then
  echo "several devices; pass --serial or ANDROID_SERIAL" >&2
  adb_bin devices -l >&2
  exit 1
fi
if [[ -z "$SERIAL" ]]; then
  SERIAL="${DEVICES[0]}"
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
if [[ -z "$OUT" ]]; then
  OUT="$(pwd)/device-bs-dump-${PHASE}-${STAMP}"
fi
mkdir -p "$OUT"

{
  echo "serial=$SERIAL"
  adb_bin devices -l
} >"$OUT/devices.txt"

{
  for key in \
    ro.product.manufacturer ro.product.model ro.build.version.release \
    ro.build.version.sdk gsm.sim.operator.alpha gsm.operator.alpha \
    gsm.sim.operator.numeric gsm.operator.numeric \
    persist.sys.usb.config sys.usb.config sys.usb.state; do
    printf '%s=%s\n' "$key" "$(sh_out getprop "$key")"
  done
} >"$OUT/getprop.txt"

USB_CONFIG="$(sh_out getprop sys.usb.config)"
TETHERING=0
if echo "$USB_CONFIG" | grep -qi 'rndis'; then
  TETHERING=1
fi
if sh_out dumpsys tethering 2>/dev/null | grep -qiE 'rndis|Tethered.*usb'; then
  TETHERING=1
fi
echo "$USB_CONFIG" >"$OUT/usb-config.txt"
sh_out dumpsys tethering >"$OUT/tethering.txt" 2>/dev/null || true

WIFI_RAW="$(sh_out settings get global wifi_on || true)"
WIFI="$(bool_from "$WIFI_RAW")"
echo "wifi_on=$WIFI_RAW" >"$OUT/wifi.txt"
sh_out dumpsys wifi 2>/dev/null | grep -E 'Wi-Fi is|mWifiEnabled|curState=' | head -n 40 >>"$OUT/wifi.txt" || true

sh_out dumpsys connectivity >"$OUT/connectivity.txt" 2>/dev/null || true
sh_out dumpsys vpn >"$OUT/vpn.txt" 2>/dev/null || true
sh_out dumpsys activity services "$PACKAGE" >"$OUT/services.txt" 2>/dev/null || true
sh_out dumpsys package "$PACKAGE" >"$OUT/package.txt" 2>/dev/null || true

VPN=0
if grep -qiE 'VpnService|com\.ardtt\.app|INTERFACE is UP|NetworkAgentInfo.*VPN' \
  "$OUT/vpn.txt" "$OUT/services.txt" "$OUT/connectivity.txt" 2>/dev/null; then
  if grep -qiE 'VpnTunnelService|established|CONNECTED|is up' \
    "$OUT/vpn.txt" "$OUT/services.txt" 2>/dev/null; then
    VPN=1
  fi
fi
# dumpsys vpn prints "VPN is connected" / package when a session exists.
if grep -qiE 'VPN is (on|connected)|package: com\.ardtt\.app' "$OUT/vpn.txt" 2>/dev/null; then
  VPN=1
fi

YANDEX=0
CLOUDFLARE=0
VPS=0
{
  echo "phase=$PHASE"
  echo "note=adb shell uses the default route; with tunnel UP this is via VPN, not underlay"
  if tcp_ok 77.88.8.8 443; then YANDEX=1; echo "yandex_443=ok"; else echo "yandex_443=fail"; fi
  if tcp_ok 1.1.1.1 443; then CLOUDFLARE=1; echo "cloudflare_443=ok"; else echo "cloudflare_443=fail"; fi
  if tcp_ok "$VPS_HOST" "$VPS_PORT"; then VPS=1; echo "vps_${VPS_HOST}_${VPS_PORT}=ok"; else echo "vps_${VPS_HOST}_${VPS_PORT}=fail"; fi
} >"$OUT/probe-default-route.txt"

CLASS="$(classify_snapshot "$WIFI" "$TETHERING" "$YANDEX" "$CLOUDFLARE" "$VPS" "$VPN")"

adb_bin logcat -d -t 5000 \
  -s ConnMgr:V VpnTunnel:V BypassSession:V BypassGo:V BypassBackend:V \
     DirectBackend:V EgressIp:V IpApi:V TunFdBridge:V CallHash:V VkLogin:V \
  >"$OUT/logcat-ardtt.txt" 2>/dev/null || true

adb_bin exec-out screencap -p >"$OUT/screenshot.png" 2>/dev/null || rm -f "$OUT/screenshot.png"

VERSION_NAME="$(grep -m1 'versionName=' "$OUT/package.txt" | head -n 1 | sed 's/.*versionName=//' | awk '{print $1}' || true)"
VERSION_CODE="$(grep -m1 'versionCode=' "$OUT/package.txt" | head -n 1 | sed 's/.*versionCode=//' | awk '{print $1}' || true)"

{
  echo "stamp=$STAMP"
  echo "phase=$PHASE"
  echo "serial=$SERIAL"
  echo "package=$PACKAGE"
  echo "versionName=${VERSION_NAME:-unknown}"
  echo "versionCode=${VERSION_CODE:-unknown}"
  echo "usb_config=$USB_CONFIG"
  echo "tethering=$TETHERING"
  echo "wifi_on=$WIFI"
  echo "vpn=$VPN"
  echo "yandex_443=$YANDEX"
  echo "cloudflare_443=$CLOUDFLARE"
  echo "vps_${VPS_HOST}_${VPS_PORT}=$VPS"
  echo "class=$CLASS"
  echo
  case "$CLASS" in
    looks_like_whitelist)
      echo "underlay looks like operator whitelist (Yandex up, Cloudflare down)."
      echo "Connect should pick Bypass. Capture --phase tunnel after the session is up."
      ;;
    invalid_usb_tether)
      echo "USB tethering/RNDIS is on. Disable the USB modem; keep USB debugging only."
      ;;
    not_bs_wifi_on)
      echo "Wi-Fi is on and the tunnel is down. Auto uses Direct on Wi-Fi; turn Wi-Fi off for a БС test."
      ;;
    tunnel_up_default_route_via_vpn)
      echo "Tunnel appears up; default-route TCP is not the underlay probe."
      echo "If Cloudflare failed before Connect and works now, Path B is doing its job."
      ;;
    tunnel_up_but_no_internet)
      echo "VPN session looks present but default-route TCP failed. Check Path B / workers / hash."
      ;;
    open_internet_vps_up)
      echo "Open internet, VPS TCP up: Auto would pick Direct. Not a whitelist underlay."
      ;;
    open_or_partial_vps_down)
      echo "Internet without VPS TCP: Auto would pick Bypass (OpenNeedBypass), not whitelist."
      ;;
    no_network)
      echo "No TCP to Yandex, Cloudflare, or VPS from default route."
      ;;
  esac
} | tee "$OUT/summary.txt"

echo "wrote $OUT"
