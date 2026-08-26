# Placeholder until qWDTT/wdtt -listen-raw is packaged.
FROM alpine:3.20
RUN apk add --no-cache bash
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
