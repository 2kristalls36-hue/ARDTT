# syntax=docker/dockerfile:1
# Unified ARDTT image: provision + direct + bypass + dns + warp + telemetry.
# One netns so TUN / iptables / ip rules stay off the VPS host by default.

FROM golang:1.22-alpine AS provision-build
WORKDIR /src
COPY provision/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/provision .

FROM golang:1.25-bookworm AS direct-build
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN curl -fsSL https://codeload.github.com/amnezia-vpn/amneziawg-go/tar.gz/refs/heads/master \
    | tar -xz \
 && mv amneziawg-go-master amneziawg-go \
 && cd amneziawg-go && go build -o /out/amneziawg-go .
COPY direct/sync/ /src/direct-sync/
WORKDIR /src/direct-sync
RUN go build -o /out/direct-sync .

FROM debian:bookworm-slim AS awg-tools
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential ca-certificates curl make \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN curl -fsSL https://codeload.github.com/amnezia-vpn/amneziawg-tools/tar.gz/refs/heads/master \
    | tar -xz \
 && mv amneziawg-tools-master amneziawg-tools \
 && make -C amneziawg-tools/src -j"$(nproc)"

FROM golang:1.25-bookworm AS bypass-build
WORKDIR /src
COPY bypass/wdtt-server/ /src/wdtt/
WORKDIR /src/wdtt/server
RUN go build -o /out/wdtt-server .
COPY bypass/sync/ /src/bypass-sync/
RUN cd /src/bypass-sync && go build -o /out/bypass-sync .

FROM debian:bookworm-slim
ARG TARGETARCH=amd64
ARG WGCF_VERSION=2.2.26
ARG WIREPROXY_VERSION=1.1.2
ARG TUN2SOCKS_VERSION=2.5.2

RUN apt-get update && apt-get install -y --no-install-recommends \
      bash ca-certificates conntrack curl dnsmasq iproute2 iptables jq \
      python3 python3-pip unzip \
 && rm -rf /var/lib/apt/lists/*

COPY telemetry-upload/requirements.txt /opt/ardtt/telemetry/requirements.txt
RUN pip3 install --break-system-packages --no-cache-dir -r /opt/ardtt/telemetry/requirements.txt \
 && rm -rf /root/.cache/pip

RUN set -e; \
    case "${TARGETARCH}" in \
      amd64) wp_arch=amd64; t2s_arch=amd64; wgcf_arch=amd64 ;; \
      arm64) wp_arch=arm64; t2s_arch=arm64; wgcf_arch=arm64 ;; \
      *) echo "unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /usr/local/bin/wgcf \
      "https://github.com/ViRb3/wgcf/releases/download/v${WGCF_VERSION}/wgcf_${WGCF_VERSION}_linux_${wgcf_arch}"; \
    chmod +x /usr/local/bin/wgcf; \
    curl -fsSL -o /tmp/wireproxy.tar.gz \
      "https://github.com/windtf/wireproxy/releases/download/v${WIREPROXY_VERSION}/wireproxy_linux_${wp_arch}.tar.gz"; \
    tar -xzf /tmp/wireproxy.tar.gz -C /tmp; \
    mv /tmp/wireproxy /usr/local/bin/wireproxy; \
    chmod +x /usr/local/bin/wireproxy; \
    rm -f /tmp/wireproxy.tar.gz; \
    curl -fsSL -o /tmp/tun2socks.zip \
      "https://github.com/xjasonlyu/tun2socks/releases/download/v${TUN2SOCKS_VERSION}/tun2socks-linux-${t2s_arch}.zip"; \
    unzip -qo /tmp/tun2socks.zip -d /tmp; \
    T2S="$(find /tmp -maxdepth 2 -type f -name 'tun2socks*' ! -name '*.zip' | head -1)"; \
    test -n "$T2S"; \
    mv "$T2S" /usr/local/bin/tun2socks; \
    chmod +x /usr/local/bin/tun2socks; \
    rm -f /tmp/tun2socks.zip

COPY --from=provision-build /out/provision /usr/local/bin/provision
COPY --from=direct-build /out/amneziawg-go /usr/local/bin/amneziawg-go
COPY --from=direct-build /out/direct-sync /usr/local/bin/direct-sync
COPY --from=awg-tools /src/amneziawg-tools/src/wg /usr/local/bin/awg
COPY --from=bypass-build /out/wdtt-server /usr/local/bin/wdtt-server
COPY --from=bypass-build /out/bypass-sync /usr/local/bin/bypass-sync
RUN ln -sf /usr/local/bin/awg /usr/local/bin/wg

COPY direct/entrypoint.sh /opt/ardtt/direct.sh
COPY direct/cascade-entrypoint.sh /cascade-entrypoint.sh
COPY bypass/entrypoint.sh /opt/ardtt/bypass.sh
COPY dns/entrypoint.sh /opt/ardtt/dns.sh
COPY dns/dnsmasq.conf.tmpl /etc/ardtt/dnsmasq.conf.tmpl
COPY warp/entrypoint.sh /opt/ardtt/warp.sh
COPY telemetry-upload/app.py /opt/ardtt/telemetry/app.py
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh /cascade-entrypoint.sh \
      /opt/ardtt/direct.sh /opt/ardtt/bypass.sh /opt/ardtt/dns.sh /opt/ardtt/warp.sh \
      /usr/local/bin/provision /usr/local/bin/amneziawg-go /usr/local/bin/direct-sync \
      /usr/local/bin/awg /usr/local/bin/wdtt-server /usr/local/bin/bypass-sync

ENV GOMEMLIMIT=256MiB \
    ARDTT_DATA=/data \
    ARDTT_WDTT_CONFIG=/data/wdtt \
    ARDTT_WARP_STATE=/data/warp \
    TELEMETRY_LOG_ROOT=/var/logs/app \
    TELEMETRY_LISTEN=0.0.0.0:9200

VOLUME ["/data"]
EXPOSE 9100 9200 51820/udp 56003/udp
ENTRYPOINT ["/entrypoint.sh"]
