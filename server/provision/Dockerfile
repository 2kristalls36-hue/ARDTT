FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/provision .

FROM alpine:3.20
# curl used by /v1/egress-ip (bind to warp0 for Hide-IP / WARP public IP).
RUN apk add --no-cache ca-certificates curl
COPY --from=build /out/provision /usr/local/bin/provision
VOLUME ["/data"]
EXPOSE 9100
ENTRYPOINT ["provision"]
CMD ["-cmd", "serve", "-data", "/data"]
