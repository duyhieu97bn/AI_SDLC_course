# Site Connector (ADR-10): chạy tại địa điểm, chỉ mở kết nối ra ngoài HTTPS/WSS 443.
# chisel lấy từ image chính thức cùng phiên bản với Tunnel Server (deploy/tunnel/Dockerfile).
FROM jpillora/chisel:1.12.1 AS chisel

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
COPY --from=chisel /ko-app/chisel /usr/local/bin/chisel
COPY pyproject.toml ./
COPY site_connector ./site_connector
RUN pip install --no-cache-dir . \
    && useradd --system --uid 10001 camhub \
    && mkdir -p /var/lib/camhub-connector \
    && chown 10001:10001 /var/lib/camhub-connector \
    && chmod 700 /var/lib/camhub-connector
USER camhub
# Thông tin đăng nhập sau kích hoạt (giữ qua các lần khởi động lại)
VOLUME ["/var/lib/camhub-connector"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD ["camhub-connector", "--healthcheck"]
ENTRYPOINT ["camhub-connector"]
