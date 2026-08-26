# Placeholder: wireproxy + tun2socks / tun-warp (hide-IP egress).
# No auto-restart on OOM — see docs/ARCHITECTURE.md
FROM alpine:3.20
RUN apk add --no-cache bash
ENV GOMEMLIMIT=400MiB
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
