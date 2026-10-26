#!/bin/bash
# Khởi tạo thư mục LDAP dev (US-44), chạy một lần khi dữ liệu còn trống (/docker-entrypoint-initdb.d của image
# bitnamilegacy/openldap). Cấu hình: quyền đọc, chính sách mật khẩu mặc định (ppolicy), overlay memberOf như AD,
# rồi nạp seed.ldif với hash mật khẩu sinh từ bí mật.
set -o errexit -o nounset -o pipefail

. /opt/bitnami/scripts/libopenldap.sh
eval "$(ldap_env)"

base="dc=camhub,dc=local"
svc_dn="cn=svc-camhub,ou=service,$base"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ldap_start_bg

# Người đã đăng nhập (kể cả tài khoản dịch vụ) đọc được thư mục như "Authenticated Users" của AD; mật khẩu chỉ dùng
# để xác thực, người dùng tự đổi được của mình. memberOf trên người dùng do overlay tự duy trì từ nhóm groupOfNames.
cat > "$work/config.ldif" <<EOF
dn: olcDatabase={2}mdb,cn=config
changetype: modify
replace: olcAccess
olcAccess: {0}to attrs=userPassword by self write by anonymous auth by * none
olcAccess: {1}to * by self read by users read by * none

dn: olcOverlay={0}ppolicy,olcDatabase={2}mdb,cn=config
changetype: modify
replace: olcPPolicyDefault
olcPPolicyDefault: cn=default,ou=policies,$base
EOF
ldapmodify -Q -Y EXTERNAL -H "ldapi:///" -f "$work/config.ldif"

ldap_load_module "/opt/bitnami/openldap/lib/openldap" "memberof.so"
cat > "$work/memberof.ldif" <<EOF
dn: olcOverlay=memberof,olcDatabase={2}mdb,cn=config
objectClass: olcOverlayConfig
objectClass: olcMemberOf
olcOverlay: memberof
olcMemberOfRefInt: TRUE
olcMemberOfDangling: ignore
olcMemberOfGroupOC: groupOfNames
olcMemberOfMemberAD: member
olcMemberOfMemberOfAD: memberOf
EOF
ldapadd -Q -Y EXTERNAL -H "ldapi:///" -f "$work/memberof.ldif"

bind_hash="$(slappasswd -n -s "$(< /run/secrets/ldap_bind_password)")"
user_hash="$(slappasswd -n -s 'AdPass-2026!')"
sed -e "s|@BIND_PASSWORD_HASH@|$bind_hash|" -e "s|@USER_PASSWORD_HASH@|$user_hash|" \
  /camhub-ldap/seed.ldif > "$work/seed.ldif"
ldapadd -H "ldapi:///" -D "$LDAP_ADMIN_DN" -w "$LDAP_ADMIN_PASSWORD" -f "$work/seed.ldif" > /dev/null

ldap_stop
info "CamHub: đã nạp thư mục dev $base (tài khoản dịch vụ $svc_dn)"
