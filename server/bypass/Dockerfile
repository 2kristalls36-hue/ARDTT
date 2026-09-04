# Builds RAW/WRAP Path B listener (`-listen-raw`) for ARDTT.
# Sources are a slim RAW-only fork of qWDTT / SpaceNeuroX (GPL-3.0).
FROM golang:1.25-bookworm AS build
WORKDIR /src
COPY wdtt-server/ /src/wdtt/
WORKDIR /src/wdtt/server
RUN go build -o /out/wdtt-server .

COPY sync/ /src/bypass-sync/
RUN cd /src/bypass-sync && go build -o /out/bypass-sync .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      bash iproute2 iptables ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /out/wdtt-server /usr/local/bin/wdtt-server
COPY --from=build /out/bypass-sync /usr/local/bin/bypass-sync
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh /usr/local/bin/wdtt-server /usr/local/bin/bypass-sync
ENTRYPOINT ["/entrypoint.sh"]
