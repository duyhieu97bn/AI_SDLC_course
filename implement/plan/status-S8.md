# Trạng thái Phase S8 – Pilot, LDAP, vai trò

> Cập nhật: 26/10/2026 · Kế hoạch: [phase-08](phase-08-S8-pilot-ldap-vai-tro.md)

| ID | Trạng thái | Bằng chứng |
| --- | --- | --- |
| US-43 Vai trò tùy chỉnh | Xong | `RoleTemplateIT`; e2e `s8.spec.js`: sao chép Operator thành "Bảo vệ ca đêm", bỏ VIEW_PLAYBACK; vai trò hệ thống chỉ có nút Sao chép; vai trò chưa gán thì xóa được |
| US-44 Đăng nhập LDAP/AD | Xong (OpenLDAP dev) | `DirectoryIT` chạy OpenLDAP thật bằng Testcontainers: AC1 đăng nhập lần đầu → tạo tài khoản và vai trò theo nhóm; AC2 tài khoản bị khóa trên thư mục → vô hiệu khi đồng bộ; AC3 thư mục lỗi → báo lỗi, không khóa tài khoản cục bộ. e2e: Hương (`CamHub-SiteMgr-HN`) đăng nhập → Site Manager phạm vi Chi nhánh Hà Nội. Thư mục dev: [deploy/ldap](../../deploy/ldap/README.md) |
| US-27 Cấu hình mẫu | Xong | `RoleTemplateIT`; e2e áp mẫu "Kho – ghi theo sự kiện" cho camera ONVIF và RTSP: kết quả từng camera (RTSP bỏ qua phần hình ảnh → "Một phần") |
| US-25 Mạng và thời gian | Xong | `NetworkConfigIT`; e2e `onvif-sim-2` lệch 7 giây được đánh dấu, đổi IP báo mất kết nối tạm thời, chuyển NTP thì hết lệch |
| US-19 Xem lại đồng bộ | Xong | `SyncController.spec.ts` (tối đa 4 camera, kéo camera lệch > 0,4 giây về cùng mốc); e2e 4 camera phát cùng mốc, lệch ≤ 1 giây |

Kiểm thử ngày 26/10/2026: Core 105 test (unit + IT) đạt; e2e S8 5/5 đạt.

## Lỗi phát hiện và đã sửa trong S8

- **Ô đăng nhập chặn tên tài khoản AD.** Ô nhập là `type="email"` nên trình duyệt không gửi `huong.ad` (API vẫn nhận). Đã đổi thành ô văn bản "Email hoặc tên đăng nhập".
- **Xem lại đồng bộ: chọn camera thứ 5.** Có thông báo "tối đa 4" nhưng ô vẫn được tích, vì trạng thái không đổi nên Vue không vẽ lại. Nay bỏ tích ô ngay khi từ chối.
- **Đo lệch giờ thiết bị lệch 0,5 giây.** Giờ ONVIF chỉ chính xác tới giây (thiết bị cắt phần lẻ), nên độ lệch đo được thấp hơn thật trung bình 0,5 giây. Hệ quả là `?starttime=` khi xem lại từ thiết bị có lúc lệch 1 giây. Gateway nay cộng bù 0,5 giây; hai test Gateway chập chờn trước đây nay ổn định.

## Còn mở

- **Điều kiện ra cần môi trường thật.** Mốc M2 cần staging và pilot 20–50 camera tại một địa điểm. Môi trường dev chỉ có bộ giả lập.
- **Đăng nhập AD thật (D-06).** Mới kiểm với OpenLDAP. Với Active Directory thật cần kiểm thêm:
  - bộ lọc `sAMAccountName`;
  - cờ `userAccountControl` (đã hỗ trợ trong mã);
  - nhóm lồng nhau, hiện **chưa** hỗ trợ.
