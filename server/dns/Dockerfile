# Gateway DNS for VPN clients (Direct 10.8.0.1, Bypass 10.9.0.1).
# Upstream Cloudflare via host main table — never through WARP/tun2socks.
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      dnsmasq iproute2 ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY entrypoint.sh /entrypoint.sh
COPY dnsmasq.conf.tmpl /etc/ardtt/dnsmasq.conf.tmpl
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
