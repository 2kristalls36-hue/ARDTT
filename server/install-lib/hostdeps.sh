# shellcheck shell=bash
# Host packages the bundled Docker Engine cannot run without.
#
# The release archive is the only software payload — with one deliberate
# exception: the `iptables` binary. dockerd needs it to create bridge networks
# and publish ports, minimal Debian 13 / Ubuntu 26.04 cloud images ship without
# it, and it cannot be vendored sanely (it must match the host's netfilter
# userspace). So when Docker has to be installed from the package and iptables
# is missing, it is taken from the distribution's own signed repository through
# the native package manager. Nothing else is ever installed from the network.
# ARDTT_INSTALL_IPTABLES=0 forbids even that and fails with the manual command.

hostdeps_pkg_manager() {
  if command -v apt-get >/dev/null 2>&1; then echo apt
  elif command -v dnf >/dev/null 2>&1; then echo dnf
  elif command -v yum >/dev/null 2>&1; then echo yum
  elif command -v apk >/dev/null 2>&1; then echo apk
  elif command -v zypper >/dev/null 2>&1; then echo zypper
  else echo ""
  fi
}

hostdeps_install_iptables() {
  case "$1" in
    apt)
      export DEBIAN_FRONTEND=noninteractive
      apt-get update -qq && apt-get install -y -qq --no-install-recommends iptables
      ;;
    dnf) dnf install -y iptables-nft || dnf install -y iptables ;;
    yum) yum install -y iptables-nft || yum install -y iptables ;;
    apk) apk add --no-cache iptables ;;
    zypper) zypper --non-interactive install iptables ;;
    *) return 1 ;;
  esac
}

# ensure_host_iptables: no-op when iptables is present; otherwise install it
# from the distro repository (or fail with IPTABLES_MISSING and the manual hint).
ensure_host_iptables() {
  command -v iptables >/dev/null 2>&1 && return 0
  local hint="Debian/Ubuntu: apt install iptables · RHEL/Fedora: dnf install iptables-nft · Alpine: apk add iptables"
  if [ "${ARDTT_INSTALL_IPTABLES:-1}" = "0" ]; then
    die --code IPTABLES_MISSING "Нужен iptables на хосте — без него Docker не поднимет сети. ARDTT_INSTALL_IPTABLES=0 запрещает ставить его из репозитория дистрибутива: поставьте вручную (${hint}) и повторите."
  fi
  local pm log
  pm="$(hostdeps_pkg_manager)"
  [ -n "$pm" ] || die --code IPTABLES_MISSING "Нужен iptables на хосте, а пакетный менеджер не распознан (apt/dnf/yum/apk/zypper). Поставьте iptables вручную (${hint}) и повторите."
  prog 0.15 "Установка iptables из репозитория дистрибутива (${pm}) — нужен Docker Engine"
  log="$(mktemp)"
  if ! hostdeps_install_iptables "$pm" >"$log" 2>&1; then
    tail -n 5 "$log" | sed "s/^/ARDTT_INFO|${pm}: /"
    rm -f "$log"
    die --code IPTABLES_MISSING "Не удалось поставить iptables через ${pm} (нет доступа к репозиториям дистрибутива?). Поставьте вручную (${hint}) и повторите."
  fi
  rm -f "$log"
  hash -r 2>/dev/null || true
  command -v iptables >/dev/null 2>&1 || die --code IPTABLES_MISSING "${pm} завершился успешно, но iptables не появился в PATH. Поставьте вручную (${hint}) и повторите."
  echo "ARDTT_INFO|iptables поставлен из репозитория дистрибутива (${pm}): $(iptables --version 2>/dev/null | head -1)"
}
