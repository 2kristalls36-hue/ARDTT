# Cloudflare WARP egress for «Скрыть свой IP» (wgcf + kernel WireGuard).
# host network + NET_ADMIN: policy-routes selected VPN clients via warp0
# without stealing the VPS default route.
FROM debian:bookworm-slim

ARG WGCF_VERSION=2.2.26

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      bash ca-certificates curl iptables iproute2 wireguard-tools jq conntrack \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /usr/local/bin/wgcf \
      "https://github.com/ViRb3/wgcf/releases/download/v${WGCF_VERSION}/wgcf_${WGCF_VERSION}_linux_amd64" \
 && chmod +x /usr/local/bin/wgcf

ENV GOMEMLIMIT=400MiB
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
