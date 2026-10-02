#!/usr/bin/env bash
# Установщик сервера логов: разбор параметров, профиль сертификата, шаблоны
# nginx и systemd. Ничего не ставит и не трогает систему; nginx -t выполняется,
# только если nginx есть на машине.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INSTALLER="$ROOT/server/telemetry-upload/install-logserver.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fail=0
err() {
  echo "FAIL: $*" >&2
  fail=1
}

bash -n "$INSTALLER" || err "bash -n"
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -x "$INSTALLER" || err "shellcheck"
fi

# shellcheck disable=SC1090
. "$INSTALLER"

# --- адреса и параметры
for ip in 1.2.3.4 191.44.108.121 255.255.255.255 0.0.0.0; do
  is_ipv4 "$ip" || err "is_ipv4 $ip"
done
for ip in 256.1.1.1 1.2.3 01.2.3.4 1.2.3.4.5 a.b.c.d ""; do
  ! is_ipv4 "$ip" || err "is_ipv4 must reject '$ip'"
done
for ip in 10.0.0.1 127.0.0.1 172.16.0.1 172.31.255.255 192.168.1.1 100.64.0.1 169.254.1.1; do
  is_private_ipv4 "$ip" || err "is_private_ipv4 $ip"
done
for ip in 191.44.108.121 8.8.8.8 172.32.0.1 100.128.0.1 192.169.0.1; do
  ! is_private_ipv4 "$ip" || err "is_private_ipv4 must reject $ip"
done
is_hostname logs.example.com || err "is_hostname logs.example.com"
! is_hostname "bad host" || err "is_hostname must reject spaces"
! is_hostname "-bad.example" || err "is_hostname must reject leading dash"

check_params() { # REF PORT [REPO]: код возврата validate_params в подоболочке
  (
    ARDTT_REF="$1"
    LOG_PORT="$2"
    ARDTT_REPO="${3:-2kristalls36-hue/ARDTT}"
    validate_params
  ) >/dev/null 2>&1
}
for ref in main refs/heads/claude/stoic-bell-lmsapw refs/tags/v0.5.271 v0.5.271 0123456789abcdef0123456789abcdef01234567; do
  check_params "$ref" 443 || err "validate_params must accept ref $ref"
done
for ref in "" "-x" "a b" 'a;b' '../x' 'a/../b' '$(id)'; do
  ! check_params "$ref" 443 || err "validate_params must reject ref '$ref'"
done
check_params main 8443 || err "validate_params port 8443"
for port in 0 65536 abc "" 9199 "44 3"; do
  ! check_params main "$port" || err "validate_params must reject port '$port'"
done
! check_params main 443 "not a repo" || err "validate_params must reject repo"

# --- профиль сертификата: как у прежнего (RSA 2048, CA:TRUE, SAN, 10 лет)
[ "$(cert_san 203.0.113.7)" = "IP:203.0.113.7,IP:127.0.0.1,DNS:localhost" ] || err "cert_san ip: $(cert_san 203.0.113.7)"
[ "$(cert_san logs.example.com)" = "DNS:logs.example.com,IP:127.0.0.1,DNS:localhost" ] || err "cert_san dns"
[ "$(cert_san 127.0.0.1)" = "IP:127.0.0.1,DNS:localhost" ] || err "cert_san loopback"

mkdir -p "$TMP/tls"
generate_cert 203.0.113.7 "$TMP/tls"
cert="$TMP/tls/cert.pem"
key="$TMP/tls/key.pem"
text="$(openssl x509 -in "$cert" -noout -text)"
grep -q 'Public-Key: (2048 bit)' <<<"$text" || err "cert must be RSA 2048"
grep -q 'Signature Algorithm: sha256WithRSAEncryption' <<<"$text" || err "cert must use sha256"
grep -q 'CA:TRUE' <<<"$text" || err "cert must be CA:TRUE"
grep -q 'IP Address:203.0.113.7, IP Address:127.0.0.1, DNS:localhost' <<<"$text" || err "cert SAN"
grep -q 'Subject Key Identifier' <<<"$text" || err "cert SKI"
grep -q 'Authority Key Identifier' <<<"$text" || err "cert AKI"
[ "$(cert_cn "$cert")" = "203.0.113.7" ] || err "cert CN '$(cert_cn "$cert")'"
openssl x509 -in "$cert" -noout -checkend $((3650 * 86400 - 3600)) >/dev/null \
  || err "cert must be valid for ~3650 days"
! openssl x509 -in "$cert" -noout -checkend $((3652 * 86400)) >/dev/null \
  || err "cert validity must not exceed ~3650 days"
openssl verify -CAfile "$cert" "$cert" >/dev/null 2>&1 || err "cert must verify against itself"
cert_key_match "$cert" "$key" || err "cert/key mismatch"
cert_has_host "$cert" 203.0.113.7 || err "cert_has_host ip"
cert_has_host "$cert" localhost || err "cert_has_host localhost"
! cert_has_host "$cert" 203.0.113.70 || err "cert_has_host must not match a prefix"
[ "$(stat -c '%a' "$key")" = "600" ] || err "key mode $(stat -c '%a' "$key")"
mkdir -p "$TMP/tls2"
generate_cert logs.example.com "$TMP/tls2"
cert_has_host "$TMP/tls2/cert.pem" logs.example.com || err "cert_has_host dns"
! cert_key_match "$cert" "$TMP/tls2/key.pem" || err "cert_key_match must reject a foreign key"

# --- systemd
unit="$(render_unit)"
for want in 'User=ardtt-logs' 'EnvironmentFile=/etc/ardtt-logs/env' 'Restart=always' \
  'NoNewPrivileges=true' 'ProtectSystem=strict' 'ReadWritePaths=/var/logs/app' 'PrivateTmp=true' 'ProtectHome=true' \
  'ExecStart=/opt/ardtt-logs/venv/bin/gunicorn --bind 127.0.0.1:9199 --workers 2 --timeout 120 app:app'; do
  grep -qxF "$want" <<<"$unit" || err "unit lacks: $want"
done

# --- nginx
conf4="$(render_nginx_conf 203.0.113.7 443 0 /etc/ardtt-logs/tls/cert.pem /etc/ardtt-logs/tls/key.pem)"
conf6="$(render_nginx_conf 203.0.113.7 8443 1 /etc/ardtt-logs/tls/cert.pem /etc/ardtt-logs/tls/key.pem)"
grep -q 'listen 443 ssl default_server;' <<<"$conf4" || err "nginx listen"
! grep -q 'listen \[::\]' <<<"$conf4" || err "nginx must not listen on IPv6 when disabled"
grep -q 'listen \[::\]:8443 ssl default_server;' <<<"$conf6" || err "nginx ipv6 listen"
for want in 'server_name 203.0.113.7 127.0.0.1 localhost;' 'ssl_protocols       TLSv1.2 TLSv1.3;' \
  'client_max_body_size 25m;' 'proxy_read_timeout    120s;' 'location = /api/upload-log {' 'rate=10r/m' 'burst=20' \
  'proxy_set_header X-Forwarded-For   $remote_addr;' 'proxy_set_header X-Forwarded-Proto $scheme;'; do
  grep -qF "$want" <<<"$conf4" || err "nginx conf lacks: $want"
done
if command -v nginx >/dev/null 2>&1; then
  variants="4"
  if ipv6_available; then
    variants="4 6"
  else
    echo "SKIP: в ядре нет IPv6, nginx -t для варианта с [::] пропущен"
  fi
  for variant in $variants; do
    mkdir -p "$TMP/nginx$variant/body" "$TMP/nginx$variant/proxy" "$TMP/nginx$variant/fcgi" "$TMP/nginx$variant/uwsgi" "$TMP/nginx$variant/scgi"
    render_nginx_conf 203.0.113.7 8443 "$([ "$variant" = 6 ] && echo 1 || echo 0)" "$cert" "$key" >"$TMP/nginx$variant/site.conf"
    cat >"$TMP/nginx$variant/nginx.conf" <<EOF
pid $TMP/nginx$variant/nginx.pid;
error_log $TMP/nginx$variant/error.log;
events {}
http {
  access_log off;
  client_body_temp_path $TMP/nginx$variant/body;
  proxy_temp_path $TMP/nginx$variant/proxy;
  fastcgi_temp_path $TMP/nginx$variant/fcgi;
  uwsgi_temp_path $TMP/nginx$variant/uwsgi;
  scgi_temp_path $TMP/nginx$variant/scgi;
  include $TMP/nginx$variant/site.conf;
}
EOF
    nginx -t -p "$TMP/nginx$variant" -e "$TMP/nginx$variant/error.log" -c "$TMP/nginx$variant/nginx.conf" >"$TMP/nginx$variant/t.out" 2>&1 \
      || {
        cat "$TMP/nginx$variant/t.out" >&2
        err "nginx -t (variant $variant)"
      }
  done
else
  echo "SKIP: nginx не установлен, nginx -t пропущен"
fi

[ "$fail" = 0 ] || exit 1
echo "OK: test-install-logserver"
