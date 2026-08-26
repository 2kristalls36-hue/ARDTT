# Builds WDTT server (qWDTT / SpaceNeuroX) with -listen-raw for Path B.
# Source: https://github.com/SpaceNeuroX/proxy-turn-vk-android (GPL-3.0)
FROM golang:1.25-bookworm AS build
RUN apt-get update && apt-get install -y --no-install-recommends patch git ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
ARG QWDTT_REF=main
RUN git clone --depth 1 --branch "${QWDTT_REF}" https://github.com/SpaceNeuroX/proxy-turn-vk-android.git qwdtt
WORKDIR /src/qwdtt/server
# Align RAW subnet with nonameVPN provision (10.9.0.0/24, .1 = gateway).
COPY patches/raw-subnet.patch /tmp/raw-subnet.patch
RUN patch -p1 < /tmp/raw-subnet.patch
RUN go build -o /out/wdtt-server .

COPY sync/ /src/bypass-sync/
WORKDIR /src/bypass-sync
RUN go build -o /out/bypass-sync .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      bash iproute2 iptables iptables-nft ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /out/wdtt-server /usr/local/bin/wdtt-server
COPY --from=build /out/bypass-sync /usr/local/bin/bypass-sync
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh /usr/local/bin/wdtt-server /usr/local/bin/bypass-sync
ENTRYPOINT ["/entrypoint.sh"]
