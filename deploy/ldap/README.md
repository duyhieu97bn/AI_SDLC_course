# Thư mục LDAP/AD giả lập (dev, US-44)

Dịch vụ `ldap` (profile `sim`) chạy OpenLDAP 2.6 (`bitnamilegacy/openldap:2.6.10`) với cấu trúc giống AD, chỉ trong mạng
`backend` (không publish cổng). **Chỉ dùng cho dev/kiểm thử.**

| Mục | Giá trị |
|---|---|
| URL | `ldap://ldap:389` (hỗ trợ StartTLS) hoặc `ldaps://ldap:636` (chứng chỉ tự ký CN=ldap, CA tại `/opt/bitnami/openldap/share/certs/ca.crt` trong container) |
| Base DN | `dc=camhub,dc=local` |
| Bind DN (tài khoản dịch vụ) | `cn=svc-camhub,ou=service,dc=camhub,dc=local`, mật khẩu trong `secrets/ldap_bind_password` |
| Quản trị | `cn=admin,dc=camhub,dc=local`, mật khẩu trong `secrets/ldap_admin_password` |
| Người dùng | base `ou=people,dc=camhub,dc=local`, lọc `(&(objectClass=inetOrgPerson)(uid={0}))`; thuộc tính `uid`, `cn` (tên hiển thị), `mail`, `memberOf` |
| Nhóm | base `ou=groups,dc=camhub,dc=local`, `(objectClass=groupOfNames)`, thành viên ở `member` (DN người dùng); người dùng có `memberOf` (overlay memberof) |
| Vô hiệu hóa | thuộc tính vận hành `pwdAccountLockedTime` (ppolicy) có giá trị `000001010000Z`; phải xin tường minh trong danh sách thuộc tính. Tài khoản bị khóa bind sẽ nhận `Invalid credentials (49)` |

Bí mật do `./scripts/dev.sh init` sinh (như các bí mật khác). Đăng nhập ẩn danh bị tắt; mọi tài khoản đã bind đọc được
thư mục (trừ `userPassword`).

## Người dùng thử

Mật khẩu chung **`AdPass-2026!`** – chỉ dùng cho dev, không bao giờ dùng ở môi trường thật.

| uid | cn | mail | Nhóm |
|---|---|---|---|
| `huong.ad` | Nguyễn Thu Hương | huong.ad@camhub.local | `CamHub-SiteMgr-HN` |
| `quang.ad` | Trần Quang | quang.ad@camhub.local | `CamHub-Operators` |
| `tuan.ad` | Lê Tuấn | tuan.ad@camhub.local | `CamHub-Operators` |

Dữ liệu nạp từ [seed.ldif](seed.ldif) bởi [init.sh](init.sh) khi container được tạo lần đầu; dữ liệu nằm trong container
(không có volume): `docker compose --profile sim up -d --force-recreate ldap` là về trạng thái ban đầu.

## Vô hiệu hóa / bật lại người dùng (US-44 AC2)

Chạy trong thư mục `deploy/`:

```bash
# Vô hiệu hóa tuan.ad
printf 'dn: uid=tuan.ad,ou=people,dc=camhub,dc=local\nchangetype: modify\nadd: pwdAccountLockedTime\npwdAccountLockedTime: 000001010000Z\n' \
  | docker compose exec -T ldap ldapmodify -H ldap://localhost:389 -D cn=admin,dc=camhub,dc=local -y /run/secrets/ldap_admin_password

# Bật lại
printf 'dn: uid=tuan.ad,ou=people,dc=camhub,dc=local\nchangetype: modify\ndelete: pwdAccountLockedTime\n' \
  | docker compose exec -T ldap ldapmodify -H ldap://localhost:389 -D cn=admin,dc=camhub,dc=local -y /run/secrets/ldap_admin_password
```

## Kiểm tra nhanh

```bash
# Người dùng đang hoạt động kèm nhóm
docker compose exec ldap ldapsearch -LLL -H ldap://localhost:389 \
  -D cn=svc-camhub,ou=service,dc=camhub,dc=local -y /run/secrets/ldap_bind_password \
  -b ou=people,dc=camhub,dc=local '(&(objectClass=inetOrgPerson)(!(pwdAccountLockedTime=*)))' uid cn mail memberOf

# Người dùng bị vô hiệu hóa
docker compose exec ldap ldapsearch -LLL -H ldap://localhost:389 \
  -D cn=svc-camhub,ou=service,dc=camhub,dc=local -y /run/secrets/ldap_bind_password \
  -b ou=people,dc=camhub,dc=local '(pwdAccountLockedTime=*)' uid pwdAccountLockedTime
```

`ldapsearch` in `cn` tiếng Việt dạng base64 (`cn:: ...`) theo chuẩn LDIF; thư viện LDAP trả chuỗi UTF-8 bình thường.
