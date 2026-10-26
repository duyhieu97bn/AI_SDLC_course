#!/bin/bash
# Bọc entrypoint của image bitnamilegacy/openldap: sinh chứng chỉ TLS tự ký (CN=ldap) cho StartTLS/LDAPS 636
# trước khi slapd được cấu hình, rồi chạy entrypoint gốc. Chỉ dùng cho dev (profile sim).
set -o errexit -o nounset -o pipefail

cert_dir="$(dirname "${LDAP_TLS_CERT_FILE:?}")"
if [[ ! -s "$LDAP_TLS_CERT_FILE" || ! -s "$LDAP_TLS_KEY_FILE" ]]; then
  mkdir -p "$cert_dir"
  openssl req -x509 -nodes -newkey rsa:2048 -days 825 -sha256 \
    -keyout "$LDAP_TLS_KEY_FILE" -out "$LDAP_TLS_CERT_FILE" \
    -subj "/CN=ldap" -addext "subjectAltName=DNS:ldap,DNS:localhost" 2>/dev/null
  # Chứng chỉ tự ký: CA chính là nó
  cp "$LDAP_TLS_CERT_FILE" "$LDAP_TLS_CA_FILE"
  chmod 600 "$LDAP_TLS_KEY_FILE"
  echo "camhub-ldap: đã tạo chứng chỉ TLS tự ký tại $cert_dir"
fi

exec /opt/bitnami/scripts/openldap/entrypoint.sh "$@"
