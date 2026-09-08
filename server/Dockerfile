# syntax=docker/dockerfile:1
# Unified ARDTT image: provision + direct + bypass + dns + warp + telemetry.
# Built in CI. VPS install docker-loads this image; it does not build or pull.
# Upstream inputs are pinned in third-party.lock.json (commit SHA + checksums).

FROM golang:1.22-alpine AS provision-build
WORKDIR /src
COPY provision/go.mod provision/go.sum ./
RUN go mod download
COPY provision/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/provision .

FROM golang:1.25-bookworm AS direct-build
ARG AMNEZIAWG_GO_COMMIT=b5928efb6ca19f0153958460c3d141f04abc5c2e
ARG AMNEZIAWG_GO_SHA256=10bf7458e090bf52f87df27adcd3904a60e4d17fab844d9415e46379147f1ab4
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN curl -fsSL "https://codeload.github.com/amnezia-vpn/amneziawg-go/tar.gz/${AMNEZIAWG_GO_COMMIT}" \
      -o /tmp/amneziawg-go.tar.gz \
 && echo "${AMNEZIAWG_GO_SHA256}  /tmp/amneziawg-go.tar.gz" | sha256sum -c - \
 && mkdir amneziawg-go && tar -xzf /tmp/amneziawg-go.tar.gz -C amneziawg-go --strip-components=1 \
 && rm -f /tmp/amneziawg-go.tar.gz \
 && cd amneziawg-go && go build -trimpath -ldflags="-s -w" -o /out/amneziawg-go .
COPY direct/sync/ /src/direct-sync/
WORKDIR /src/direct-sync
RUN go build -trimpath -ldflags="-s -w" -o /out/direct-sync .

FROM debian:bookworm-slim AS awg-tools
ARG AMNEZIAWG_TOOLS_COMMIT=ee0f0a9aa34ff0a0da4b3433b9512781cfe02843
ARG AMNEZIAWG_TOOLS_SHA256=22438f231d39ea27e4bdc69707ec3103357f3cecc9ad3eecba18a61e5b39c9b9
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential ca-certificates curl make \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN curl -fsSL "https://codeload.github.com/amnezia-vpn/amneziawg-tools/tar.gz/${AMNEZIAWG_TOOLS_COMMIT}" \
      -o /tmp/amneziawg-tools.tar.gz \
 && echo "${AMNEZIAWG_TOOLS_SHA256}  /tmp/amneziawg-tools.tar.gz" | sha256sum -c - \
 && mkdir amneziawg-tools && tar -xzf /tmp/amneziawg-tools.tar.gz -C amneziawg-tools --strip-components=1 \
 && rm -f /tmp/amneziawg-tools.tar.gz \
 && make -C amneziawg-tools/src -j"$(nproc)"

FROM golang:1.25-bookworm AS bypass-build
WORKDIR /src
COPY bypass/wdtt-server/ /src/wdtt/
WORKDIR /src/wdtt/server
RUN go build -trimpath -ldflags="-s -w" -o /out/wdtt-server .
COPY bypass/sync/ /src/bypass-sync/
RUN cd /src/bypass-sync && go build -trimpath -ldflags="-s -w" -o /out/bypass-sync .

FROM debian:bookworm-slim
ARG TARGETARCH=amd64
ARG WGCF_VERSION=2.2.26
ARG WIREPROXY_VERSION=1.1.2
ARG TUN2SOCKS_VERSION=2.5.2
ARG WGCF_SHA256_AMD64=b49e7c52307df1f0a9ccd13ad12f87bf7ee7092df4e189f064d81860ec6f4bf5
ARG WGCF_SHA256_ARM64=cbda1a8e4e1144c49ed9a8a2d99f3d5a70e6115ebca62ae3d354d8b074a4ae73
ARG WIREPROXY_SHA256_AMD64=b7dcff8f6e9d3410364e432aff24154eaa8db8206e0c6faac35d6c6ab06dac51
ARG WIREPROXY_SHA256_ARM64=aa234db9ef0b2774cb04a79f391bd41c2ededd815fc1a1c720cfdc693c70898d
ARG TUN2SOCKS_SHA256_AMD64=4aa7737009a9f06b9f4957c4fc12932ae0cd2039471d2ae4e5d4666ffeb40a2e
ARG TUN2SOCKS_SHA256_ARM64=b83bc4961b33aa3ec0399c801a97c2c47766462991ffa488a4cbaee1ab3e0bff
ARG ARDTT_DEPLOY_VERSION=dev
ARG ARDTT_GIT_COMMIT=unknown

RUN apt-get update && apt-get install -y --no-install-recommends \
      bash ca-certificates conntrack curl dnsmasq iproute2 iptables jq \
      python3 python3-pip unzip \
 && rm -rf /var/lib/apt/lists/*

COPY telemetry-upload/requirements.txt /opt/ardtt/telemetry/requirements.txt
RUN pip3 install --break-system-packages --no-cache-dir -r /opt/ardtt/telemetry/requirements.txt \
 && rm -rf /root/.cache/pip

RUN set -e; \
    case "${TARGETARCH}" in \
      amd64) wp_arch=amd64; t2s_arch=amd64; wgcf_arch=amd64; \
             wgcf_sha="${WGCF_SHA256_AMD64}"; wp_sha="${WIREPROXY_SHA256_AMD64}"; t2s_sha="${TUN2SOCKS_SHA256_AMD64}" ;; \
      arm64) wp_arch=arm64; t2s_arch=arm64; wgcf_arch=arm64; \
             wgcf_sha="${WGCF_SHA256_ARM64}"; wp_sha="${WIREPROXY_SHA256_ARM64}"; t2s_sha="${TUN2SOCKS_SHA256_ARM64}" ;; \
      *) echo "unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/wgcf \
      "https://github.com/ViRb3/wgcf/releases/download/v${WGCF_VERSION}/wgcf_${WGCF_VERSION}_linux_${wgcf_arch}"; \
    echo "${wgcf_sha}  /tmp/wgcf" | sha256sum -c -; \
    mv /tmp/wgcf /usr/local/bin/wgcf; \
    chmod +x /usr/local/bin/wgcf; \
    curl -fsSL -o /tmp/wireproxy.tar.gz \
      "https://github.com/windtf/wireproxy/releases/download/v${WIREPROXY_VERSION}/wireproxy_linux_${wp_arch}.tar.gz"; \
    echo "${wp_sha}  /tmp/wireproxy.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/wireproxy.tar.gz -C /tmp; \
    mv /tmp/wireproxy /usr/local/bin/wireproxy; \
    chmod +x /usr/local/bin/wireproxy; \
    rm -f /tmp/wireproxy.tar.gz; \
    curl -fsSL -o /tmp/tun2socks.zip \
      "https://github.com/xjasonlyu/tun2socks/releases/download/v${TUN2SOCKS_VERSION}/tun2socks-linux-${t2s_arch}.zip"; \
    echo "${t2s_sha}  /tmp/tun2socks.zip" | sha256sum -c -; \
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
COPY netns-guard.sh /opt/ardtt/netns-guard.sh
COPY ready.sh /opt/ardtt/ready.sh
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh /cascade-entrypoint.sh /opt/ardtt/ready.sh \
      /opt/ardtt/netns-guard.sh \
      /opt/ardtt/direct.sh /opt/ardtt/bypass.sh /opt/ardtt/dns.sh /opt/ardtt/warp.sh \
      /usr/local/bin/provision /usr/local/bin/amneziawg-go /usr/local/bin/direct-sync \
      /usr/local/bin/awg /usr/local/bin/wdtt-server /usr/local/bin/bypass-sync

LABEL org.opencontainers.image.title="ardtt-server" \
      org.opencontainers.image.source="https://github.com/2kristalls36-hue/ARDTT" \
      com.ardtt.owner="ardtt" \
      com.ardtt.deploy-version="${ARDTT_DEPLOY_VERSION}" \
      com.ardtt.git-commit="${ARDTT_GIT_COMMIT}"

ENV GOMEMLIMIT=256MiB \
    ARDTT_DATA=/data \
    ARDTT_WDTT_CONFIG=/data/wdtt \
    ARDTT_WARP_STATE=/data/warp \
    TELEMETRY_LOG_ROOT=/var/logs/app \
    TELEMETRY_LISTEN=0.0.0.0:9200 \
    ARDTT_DIRECT_LISTEN_PORT=51820 \
    ARDTT_BYPASS_LISTEN_PORT=56003 \
    ARDTT_PROVISION_LISTEN=0.0.0.0:9100

VOLUME ["/data"]
EXPOSE 9100 9200 51820/udp 56003/udp
ENTRYPOINT ["/entrypoint.sh"]
