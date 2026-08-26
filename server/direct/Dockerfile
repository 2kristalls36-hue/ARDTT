# Placeholder until AmneziaWG 2.0 image is wired in.
# Keeps host networking / capabilities documented for compose.
FROM alpine:3.20
RUN apk add --no-cache bash
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
