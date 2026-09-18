# Build Stage
FROM golang:alpine AS builder
WORKDIR /app
RUN apk add --no-cache ca-certificates tzdata
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -trimpath -o /app/tokeng-server ./cmd/tokeng-server

# Runtime Stage
FROM alpine:latest
RUN apk add --no-cache ca-certificates tzdata
WORKDIR /app
COPY --from=builder /app/tokeng-server /app/tokeng-server
EXPOSE 8088
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD ["/app/tokeng-server", "-healthcheck"]
ENTRYPOINT ["/app/tokeng-server"]
