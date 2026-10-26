# Trạng thái Phase S5 – Xuất clip, cảnh báo, Alpha

> Cập nhật: 25/10/2026 · Kế hoạch: [phase-05-S5-xuat-clip-canh-bao-alpha.md](phase-05-S5-xuat-clip-canh-bao-alpha.md)

## Item

| ID | Trạng thái | Bằng chứng |
| --- | --- | --- |
| US-20 Xuất clip | Xong | `ExportIT` (5 test: thành công + outbox + nhật ký, > 60 phút, không quyền, hết hạn 24 giờ, không có video); e2e `s5.spec.js` tải MP4 thật (hộp `ftyp`) từ Media Worker → MinIO; liên kết ký 5 phút |
| US-26 Vùng chuyển động, vùng che | Xong (giả lập) | `ZoneConfigIT` (4 test giới hạn theo khả năng camera); Gateway `zones.py` + 18 test; e2e vẽ 4 đa giác, vùng thứ 5 bị chặn, lưu vùng che. **Chưa thử camera thật** (XML MotionRegionDetector viết theo đặc tả) |
| US-34 Cảnh báo mất kết nối | Xong | `AlertingIT`: chờ 5 phút, gộp 7 camera thành 1 SITE_OFFLINE, tự phục hồi; thông báo web + email (Mailpit ở dev), thử lại 3 lần |
| US-35 Cảnh báo dung lượng | Xong | `AlertingIT` (85% WARNING kèm GB còn lại, 95% nâng CRITICAL, dưới ngưỡng phục hồi); e2e hạ ngưỡng → cảnh báo hiện ngay qua WebSocket, email tới Admin |
| US-39 Tổng quan | Xong | `GET /system/overview`; e2e "X / Y" và số cảnh báo mở khớp trang Cảnh báo |
| US-40 Sao lưu và khôi phục | Xong | pgBackRest (đầy đủ hằng ngày, diff hằng giờ, WAL liên tục, giữ 30 bản), `mc mirror` MinIO hằng giờ; [runbook](../../docs/runbook/restore.md); [diễn tập 01](../../docs/runbook/restore-drill-01.md): PITR 15 giây, 53 bảng khớp; tab Sao lưu chỉ xem |

## Điều kiện ra

- [x] Core: 80 test tích hợp đạt (cùng S0–S4), ArchitectureTest có module mới `system`
- [x] e2e S5 đạt 6/6
- [ ] Alpha với người dùng thật: chưa có (môi trường dev)

## Thay đổi so với kế hoạch / cần lưu ý

- **Realtime trước S5 không hoạt động qua Nginx.** Có 2 lỗi: `/ws` bị chuyển hướng 301, và Core từ chối Origin vì thiếu `X-Forwarded-Proto`/host:cổng. Đã sửa `deploy/nginx/nginx.conf`. Trang Xuất clip và Cảnh báo có thêm cơ chế hỏi lại định kỳ phòng khi WebSocket gián đoạn.
- **Tổng quan đặt ở module mới `system`**, không ở `platform/web` như kế hoạch, vì `platform` không được phụ thuộc module nghiệp vụ (ArchitectureTest).
- **Danh sách cảnh báo mới ở mức cơ bản.** Nhận xử lý, chuyển giao, đóng có kết quả (US-37) và xem lại từ cảnh báo (US-38) làm ở S7.
- **Khôi phục** chỉ đội vận hành làm theo runbook. Kho sao lưu ở dev nằm cùng máy Docker; production cần `repo2` off-site có mã hóa.
