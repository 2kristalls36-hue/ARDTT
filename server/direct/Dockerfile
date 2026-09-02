# Builds AmneziaWG 2.0 userspace (amneziawg-go) + tools (awg) + config sync.
# Fetch via codeload tarballs: git+https from some VPS IPs prompts for GitHub
# credentials ("could not read Username") even though the repos are public.
FROM golang:1.25-bookworm AS go-build
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN curl -fsSL https://codeload.github.com/amnezia-vpn/amneziawg-go/tar.gz/refs/heads/master \
    | tar -xz \
 && mv amneziawg-go-master amneziawg-go \
 && cd amneziawg-go && go build -o /out/amneziawg-go .
COPY sync/ /src/direct-sync/
WORKDIR /src/direct-sync
RUN go build -o /out/direct-sync .

FROM debian:bookworm-slim AS tools-build
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential ca-certificates curl make \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN curl -fsSL https://codeload.github.com/amnezia-vpn/amneziawg-tools/tar.gz/refs/heads/master \
    | tar -xz \
 && mv amneziawg-tools-master amneziawg-tools \
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
