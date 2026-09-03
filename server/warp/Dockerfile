# Cloudflare WARP egress for «Скрыть свой IP».
#
# Proven layout from a long-lived production VPS: wgcf account → wireproxy
# (userspace WG, SOCKS5) → tun2socks TUN. Kernel wg-quick/warp0 leaked RAM
# and needed a client TUN restart on every Hide-IP flip. This path stays
# ~100–130 MiB and Hide-IP is an ip-rule change only.
FROM debian:bookworm-slim

ARG WGCF_VERSION=2.2.26
ARG WIREPROXY_VERSION=1.1.2
ARG TUN2SOCKS_VERSION=2.5.2

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
    bash ca-certificates curl iptables iproute2 wireguard-tools jq conntrack unzip \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /usr/local/bin/wgcf \
      "https://github.com/ViRb3/wgcf/releases/download/v${WGCF_VERSION}/wgcf_${WGCF_VERSION}_linux_amd64" \
 && chmod +x /usr/local/bin/wgcf \
 && curl -fsSL -o /tmp/wireproxy.tar.gz \
      "https://github.com/windtf/wireproxy/releases/download/v${WIREPROXY_VERSION}/wireproxy_linux_amd64.tar.gz" \
 && tar -xzf /tmp/wireproxy.tar.gz -C /tmp \
 && mv /tmp/wireproxy /usr/local/bin/wireproxy \
 && chmod +x /usr/local/bin/wireproxy \
 && rm -f /tmp/wireproxy.tar.gz \
 && curl -fsSL -o /tmp/tun2socks.zip \
      "https://github.com/xjasonlyu/tun2socks/releases/download/v${TUN2SOCKS_VERSION}/tun2socks-linux-amd64.zip" \
 && unzip -qo /tmp/tun2socks.zip -d /tmp \
 && T2S="$(find /tmp -maxdepth 2 -type f -name 'tun2socks*' ! -name '*.zip' | head -1)" \
 && test -n "$T2S" \
 && mv "$T2S" /usr/local/bin/tun2socks \
 && chmod +x /usr/local/bin/tun2socks \
 && rm -f /tmp/tun2socks.zip

ENV GOMEMLIMIT=256MiB
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
