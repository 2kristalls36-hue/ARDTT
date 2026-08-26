# Builds AmneziaWG 2.0 userspace (amneziawg-go) + tools (awg) + config sync.
FROM golang:1.25-bookworm AS go-build
WORKDIR /src
RUN git clone --depth 1 https://github.com/amnezia-vpn/amneziawg-go.git \
 && cd amneziawg-go && go build -o /out/amneziawg-go .
COPY sync/ /src/direct-sync/
WORKDIR /src/direct-sync
RUN go build -o /out/direct-sync .

FROM debian:bookworm-slim AS tools-build
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential git ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN git clone --depth 1 https://github.com/amnezia-vpn/amneziawg-tools.git \
 && make -C amneziawg-tools/src -j"$(nproc)"

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      bash iproute2 iptables ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=go-build /out/amneziawg-go /usr/local/bin/amneziawg-go
COPY --from=go-build /out/direct-sync /usr/local/bin/direct-sync
COPY --from=tools-build /src/amneziawg-tools/src/wg /usr/local/bin/awg
RUN ln -sf /usr/local/bin/awg /usr/local/bin/wg
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh /usr/local/bin/amneziawg-go /usr/local/bin/direct-sync /usr/local/bin/awg
ENTRYPOINT ["/entrypoint.sh"]
