# Trạng thái Phase S9 – Kiểm thử, GA

> Cập nhật: 26/10/2026 · Kế hoạch: [phase-09](phase-09-S9-kiem-thu-ga.md)

| ID | Trạng thái | Bằng chứng |
| --- | --- | --- |
| US-07 Nhập camera từ file | Xong | `CameraImportIT`: CSV có dòng thiếu IP, sai khu vực, ô có dấu `;` trong ngoặc kép → xem trước báo lỗi đúng dòng, nhập 2/4, mật khẩu không trả về trình duyệt. e2e `s9.spec.js`: tệp 3 dòng → nhập 2, bỏ 1, có báo cáo từng dòng. Hỗ trợ `.csv` (`,` hoặc `;`, có BOM) và `.xlsx` |
| US-15 Âm thanh hai chiều | Xong (giả lập) | Xem mục "US-15" bên dưới |
| TS-08 Kiểm thử tải, bảo mật | Xong phần làm được ở dev | Tải: [load-report.md](../../docs/test/load-report.md). Bảo mật: [security-review.md](../../docs/test/security-review.md). Khôi phục: [restore-drill-02.md](../../docs/runbook/restore-drill-02.md). Còn kiểm thử xâm nhập độc lập và đo trên staging |

## US-15 Âm thanh hai chiều

**Kiến trúc.** Khác kế hoạch (US-15.1 "bật backchannel trên MediaMTX"): MediaMTX 1.12 không chuyển tiếp backchannel, nên âm thanh đi:

1. Trình duyệt: micro hạ xuống PCM 8 kHz.
2. WebSocket `/talk/ws/{mã phiên}` qua Nginx tới Device Gateway.
3. Gateway mở RTSP backchannel theo ONVIF Streaming mục 5.3 (track `a=sendonly`, G.711, RTP xen kẽ trên TCP) tới loa camera.

Hệ quả:

- Đi được qua đường hầm SOCKS5 của địa điểm.
- Không cần sửa `deploy/mediamtx/mediamtx.yml`.

**Phân chia việc.**

| Thành phần | Việc |
| --- | --- |
| Core `TalkService` | Kiểm tra `TALK_AUDIO` và năng lực `audioOut` của camera, xin phiên từ Gateway |
| Core `TalkAuditService` | Ghi nhật ký `TALK_AUDIO` kèm `durationSeconds`, `audioSeconds` đúng một lần (khóa trong Redis). Trình duyệt đóng ngang thì lượt quét 15 giây ghi khi Gateway báo phiên đã kết thúc |
| Gateway | Một người nói mỗi camera (`BUSY`); mã phiên 192 bit dùng một lần; hết hạn 30 giây nếu chưa nối; tối đa 5 phút |
| Web `TalkButton` | Nhấn giữ chuột/chạm hoặc giữ phím Space |
| Bộ giả lập | `SIM_TALK_PORT` dựng máy chủ RTSP backchannel; số gói nhận được xem ở `/sim/talk`; dịch vụ `onvif-sim-talk` |

**Kiểm thử.**

| Loại | Bằng chứng |
| --- | --- |
| Gateway (7 test) | `test_talk.py`: giá trị chuẩn G.711, chọn track SDP, 1 giây âm thanh → 50 gói RTP μ-law tới bộ giả lập (Digest), `BUSY`, mã phiên dùng lại bị đóng 4404 |
| Core (2 test) | `TalkIT`: Operator nói 4,2 giây → nhật ký có thời lượng; Viewer 403; camera không loa 409; người khác không kết thúc được phiên; quét định kỳ ghi đúng một lần |
| Web | `PcmResampler.spec.ts` (48 kHz và 44,1 kHz → 8 kHz không lệch cao độ) |
| e2e | Chrome với micro giả, giữ nút 2,5 giây → bộ giả lập nhận > 80 gói G.711, nhật ký `TALK_AUDIO` > 1,5 giây |

**Chưa kiểm với camera thật có loa.** Camera Hikvision/Dahua có thể đòi AAC, hoặc dùng API riêng (ISAPI two-way audio) thay vì ONVIF backchannel.

## Lỗi phát hiện và đã sửa trong S9

| Nơi | Lỗi | Sửa |
| --- | --- | --- |
| Web | **Màn hình đen khi mở địa chỉ gốc `/` lúc chưa đăng nhập:** route `/` chuyển tiếp tới `landingPage`, mặc định là `/` khi chưa đăng nhập, nên lặp vô hạn (tràn ngăn xếp, ứng dụng không dựng được). e2e không bắt được vì luôn mở `/login` trước | Bỏ qua `landingPage = '/'`; thêm e2e US-30 mở `/` khi chưa đăng nhập |
| Web | **Nhập tệp:** nhập xong thì hộp thoại tự đóng, người dùng không thấy báo cáo từng dòng | Giữ hộp thoại mở, chỉ làm mới danh sách |
| Web | **Đổi camera liên tiếp trong một ô xem:** phiên WebRTC khởi động chậm của camera trước ghi đè camera sau, ô báo "đang phát" nhưng không có hình. Lộ ra khi e2e kế thừa bố cục đã lưu | Ô xem dựng lại khi đổi camera (`key` theo camera) |
| Web | **Nút đàm thoại kẹt "Đang kết nối":** khi trình duyệt chặn micro hoặc mở phiên lỗi. Micro còn bật khi mở phiên lỗi | Dọn tập trung một chỗ, dùng `Promise.allSettled` |
| Bảo mật | S-01..S-06 trong [security-review.md](../../docs/test/security-review.md) | Nâng Spring Boot 3.4.4 → 3.5.16 và bản vá thư viện; chặn giả mạo IP nhật ký; thêm CSP; header cho `/assets/`; ẩn source map; sửa chặn CSV injection |
| Test | e2e phụ thuộc dữ liệu do test trước để lại (bố cục đã lưu, kết quả tìm kiếm có camera mới) | Sửa test |
| Test | `OutboxIT` chập chờn do lượt quét outbox định kỳ chạy song song | Sửa test |

Kiểm thử ngày 26/10/2026:

- Core 107 test đạt sau khi nâng Spring Boot (một test chập chờn, đã sửa và chạy lại đạt).
- Gateway 204 test.
- Web 20 test.
- e2e S1–S9 đạt sau khi sửa (lần chạy toàn bộ cuối: xem load-report mục 6).

## Điều kiện ra S9 (GA) – còn thiếu

| Điều kiện | Trạng thái |
| --- | --- |
| NFR-01..04 dưới tải 500 camera / 100 người dùng | Đạt phần API (100 người dùng, 1 giờ) trên laptop dev. 500 luồng ghi đồng thời và 50 camera thật cần máy staging (D-01, D-04) |
| Không còn lỗi bảo mật nghiêm trọng/cao | Đạt trong phạm vi tự rà soát. Cần báo cáo kiểm thử xâm nhập độc lập |
| UAT với người dùng pilot ≥ 4/5, ký M3 | Chưa: cần pilot |
| BQ-02 | PO quyết định thêm S10 hay không. US-15 đã xong nên không cần dời sang phiên bản sau |
