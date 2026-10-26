# PostgreSQL của CamHub kèm pgBackRest (US-40.1, SADD 3.12.3).
# Một image dùng cho hai dịch vụ:
#   postgres – archive_command đẩy WAL liên tục vào kho pgBackRest
#   backup   – lịch sao lưu (crond): bản đầy đủ hằng ngày, bản differential hằng giờ, sao chép MinIO
# Build context là thư mục deploy/ (xem deploy/.dockerignore: chỉ đưa postgres/ và backup/ vào).
# Giữ đúng major 16 như image cũ để dùng lại volume pgdata hiện có.
FROM postgres:16-alpine

# mc (MinIO Client) là file tĩnh, lấy từ chính image MinIO đang chạy để cùng phiên bản
COPY --from=bitnamilegacy/minio:2025.7.23 /opt/bitnami/minio-client/bin/mc /usr/local/bin/mc

RUN apk add --no-cache pgbackrest jq \
 && mkdir -p /var/lib/pgbackrest /var/log/pgbackrest /backup-status /miniobackup \
 && chown postgres:postgres /var/lib/pgbackrest /var/log/pgbackrest /backup-status /miniobackup \
 && chmod 750 /var/lib/pgbackrest

COPY backup/pgbackrest.conf /etc/pgbackrest/pgbackrest.conf
COPY backup/camhub-backup.sh /usr/local/bin/camhub-backup
# Phòng khi checkout trên Windows đổi sang CRLF
RUN sed -i 's/\r$//' /etc/pgbackrest/pgbackrest.conf /usr/local/bin/camhub-backup \
 && chmod 644 /etc/pgbackrest/pgbackrest.conf \
 && chmod 755 /usr/local/bin/camhub-backup /usr/local/bin/mc
