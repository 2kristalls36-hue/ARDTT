# Builds wdtt-server with -listen-raw for Path B (RAW over VK TURN).
# Upstream origin: WDTT — https://github.com/amurcanov/proxy-turn-vk-android (GPL-3.0).
# Sources are vendored under ./wdtt-server (WDTT Server v2 + Path B RAW listener).
FROM golang:1.25-bookworm AS build
RUN apt-get update && apt-get install -y --no-install-recommends patch ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY wdtt-server/ /src/wdtt/
WORKDIR /src/wdtt/server
# Align RAW subnet with nonameVPN provision (10.9.0.0/24, .1 = gateway).
COPY patches/raw-subnet.patch /tmp/raw-subnet.patch
RUN patch -p1 < /tmp/raw-subnet.patch
RUN go build -o /out/wdtt-server .

COPY sync/ /src/bypass-sync/
WORKDIR /src/bypass-sync
RUN go build -o /out/bypass-sync .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      bash iproute2 iptables ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /out/wdtt-server /usr/local/bin/wdtt-server
COPY --from=build /out/bypass-sync /usr/local/bin/bypass-sync
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh /usr/local/bin/wdtt-server /usr/local/bin/bypass-sync
ENTRYPOINT ["/entrypoint.sh"]
