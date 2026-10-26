# Tunnel Server (ADR-10, TS-06.2): chisel server chạy sau Nginx (location /tunnel/), chỉ nhận đường hầm ngược.
# Image chisel chính thức là "scratch" (không có shell), nên đóng gói lại trên Alpine để chạy script đồng bộ
# tệp xác thực (xem entrypoint.sh: luôn thêm một tài khoản "lính gác" để chisel không bao giờ chạy ở chế độ mở).
FROM jpillora/chisel:1.12.1 AS chisel

FROM alpine:3.22
RUN apk add --no-cache jq \
    && adduser -S -D -H -u 10001 camhub \
    && mkdir -p /run/chisel && chown 10001 /run/chisel
COPY --from=chisel /ko-app/chisel /usr/local/bin/chisel
COPY entrypoint.sh /usr/local/bin/tunnel-entrypoint
USER 10001
# 8080: WebSocket từ Nginx. Cổng ngược (SOCKS5 21000-21999, TCP 22000-23999) khai báo ở docker-compose (expose)
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["/bin/sh", "/usr/local/bin/tunnel-entrypoint"]
