# Task chi tiết theo từng item backlog và thông tin từng phase (sprint).
# Task: (vai trò, công việc, file/thư mục, giờ). Vai trò: BE (Java), PY (Python), FE (Vue), QA, OPS (DevOps).

C = "core/src/main/java/vn/camhub"
CT = "core/src/test/java/vn/camhub"
MIG = "core/src/main/resources/db/migration"
GW = "device-gateway/app"
WEB = "web/src"
E2E = "tests/e2e/features"

TASKS = {
    # ---------- S0 ----------
    "TS-01": [
        ("OPS", "Tạo monorepo theo SADD 3.8, README, .editorconfig, .gitignore", "/ (gốc repo)", 3),
        ("BE", "Gradle multi-module, Spring Boot 3 + Java 21 skeleton, 11 package module rỗng", "core/build.gradle.kts, core/src/main/java/vn/camhub/*", 8),
        ("BE", "Rule kiến trúc: cấm phụ thuộc vòng, chỉ gọi qua package api", f"{CT}/ArchitectureTest.java", 4),
        ("PY", "pyproject, ruff, mypy, pytest cho device-gateway, media-worker, site-connector", "device-gateway/pyproject.toml, media-worker/pyproject.toml, site-connector/pyproject.toml", 4),
        ("FE", "Vite + Vue 3 + TypeScript + PrimeVue + Pinia + Vue Router + vue-i18n + Vitest + ESLint", "web/package.json, web/vite.config.ts, web/src/main.ts", 6),
        ("FE", "Port design token và layout từ design/assets/styles.css sang theme PrimeVue", f"{WEB}/styles/tokens.css, {WEB}/layouts/AppLayout.vue", 6),
        ("OPS", "Pipeline CI: build, test, lint, kiểm tra kiến trúc, OWASP Dependency-Check, pip-audit, npm audit", ".github/workflows/ci.yml", 8),
    ],
    "TS-02": [
        ("OPS", "docker-compose cho 10 dịch vụ, network, volume, Docker secret", "deploy/docker-compose.yml, deploy/.env.example", 8),
        ("OPS", "Nginx: TLS tự ký cho dev, định tuyến /api, /ws, /media, SPA fallback", "deploy/nginx/nginx.conf", 4),
        ("OPS", "Cấu hình MediaMTX cơ bản, PostgreSQL, Redis, RabbitMQ, MinIO", "deploy/mediamtx/mediamtx.yml, deploy/rabbitmq/definitions.json", 4),
        ("OPS", "Healthcheck mọi container, script up/down/reset", "deploy/scripts/dev.sh", 3),
        ("QA", "Smoke test sau khi dựng môi trường", "tests/smoke/smoke.sh", 2),
    ],
    "TS-03": [
        ("PY", "Bộ giả lập RTSP (FFmpeg phát file mẫu thành N luồng)", "tools/rtsp-sim/", 6),
        ("BE", "Endpoint /internal/media/authorize (bản PoC: kiểm tra token HMAC)", f"{C}/liveview/web/MediaAuthController.java", 4),
        ("FE", "Player WHEP + hls.js dự phòng, đo first frame", f"{WEB}/player/WhepPlayer.ts, {WEB}/player/HlsFallback.ts", 8),
        ("OPS", "Bật ghi fMP4 60 giây, hook runOnRecordSegmentComplete gọi Core", "deploy/mediamtx/mediamtx.yml", 4),
        ("PY", "Thử chuyển mã H.265 → H.264 theo yêu cầu, đo CPU mỗi phiên", "tools/transcode-poc/", 4),
        ("QA", "Đo first frame, độ trễ, CPU/RAM với 50 camera thật + 450 luồng giả lập; viết báo cáo", "docs/poc/mediamtx-report.md", 10),
    ],
    "TS-04": [
        ("BE", "OrganizationContext, filter đặt app.org_id cho mỗi giao dịch", f"{C}/platform/tenancy/OrganizationContext.java, TenantFilter.java", 6),
        ("BE", "Migration nền: bảng organization, outbox_event, chính sách RLS mẫu", f"{MIG}/V0_1__baseline.sql", 6),
        ("BE", "Transactional outbox + publisher sang RabbitMQ (retry, dead-letter)", f"{C}/platform/outbox/OutboxEvent.java, OutboxPublisher.java", 8),
        ("BE", "CryptoService AES-256-GCM, khóa chính từ Docker secret, khóa theo Tổ chức", f"{C}/platform/crypto/CryptoService.java", 6),
        ("BE", "AuditableEvent và listener chung (module audit dùng ở S2)", f"{C}/platform/audit/AuditableEvent.java", 2),
        ("QA", "Test cách ly Tổ chức (truy vấn quên lọc vẫn bị RLS chặn), test outbox khi broker dừng", f"{CT}/platform/TenancyIT.java, OutboxIT.java", 5),
    ],
    "TS-07": [
        ("OPS", "Prometheus scrape Core, MediaMTX, RabbitMQ, PostgreSQL; Grafana dashboard", "deploy/prometheus/prometheus.yml, deploy/grafana/dashboards/", 6),
        ("BE", "Micrometer, log JSON có correlation id", "core/src/main/resources/logback-spring.xml", 3),
        ("OPS", "Loki thu log container; rule cảnh báo container dừng", "deploy/loki/, deploy/prometheus/alerts.yml", 3),
    ],
    # ---------- S1 ----------
    "TS-05": [
        ("PY", "FastAPI app, mTLS với Core, cấu trúc module", f"{GW}/main.py, {GW}/security.py", 6),
        ("PY", "WS-Discovery trong LAN", f"{GW}/discovery/ws_discovery.py", 6),
        ("PY", "Đọc capabilities: profiles, encoder options, PTZ, imaging, events", f"{GW}/capabilities/reader.py", 8),
        ("PY", "Bộ kiểm thử tương thích chạy trên danh sách camera mẫu (D-04)", "device-gateway/tests/compat/", 8),
        ("BE", "DeviceControlPort và adapter GatewayClient (REST + mTLS, Resilience4j)", f"{C}/device/api/DeviceControlPort.java, {C}/device/infrastructure/GatewayClient.java", 6),
    ],
    "US-30": [
        ("BE", "Entity User, Session; migration bảng user, session", f"{C}/identity/domain/User.java, {MIG}/V1_1__identity.sql", 5),
        ("BE", "Đăng nhập, băm Argon2id, khóa sau 5 lần sai, JWT 15 phút + refresh cookie xoay vòng", f"{C}/identity/application/LoginService.java, {C}/identity/web/AuthController.java", 10),
        ("FE", "Màn hình đăng nhập theo design/login.html, interceptor refresh token", f"{WEB}/views/LoginView.vue, {WEB}/services/http.ts", 6),
        ("QA", "Feature file và test cho 3 kịch bản", f"{E2E}/US-30_dang_nhap.feature", 4),
    ],
    "US-05": [
        ("BE", "Entity Site, Zone, CameraGroup, CameraLocationHistory; migration", f"{C}/organization/domain/, {MIG}/V1_2__organization.sql", 6),
        ("BE", "API cây phân cấp, lọc, POST /cameras/{id}/move (giữ video, ghi MOVE_CAMERA)", f"{C}/organization/web/SiteController.java, {C}/organization/application/MoveCameraService.java", 8),
        ("FE", "Cây địa điểm, kéo thả camera, modal chuyển khu vực", f"{WEB}/components/CameraTree.vue, {WEB}/views/CamerasView.vue", 8),
        ("QA", "Test lọc 500 camera ≤ 1 giây (dữ liệu giả), test chuyển camera", f"{E2E}/US-05_to_chuc_camera.feature", 4),
    ],
    "US-32": [
        ("BE", "Role, Permission, RoleAssignment; seed 5 vai trò mặc định; migration", f"{C}/access/domain/, {MIG}/V1_3__access.sql", 6),
        ("BE", "PermissionEvaluator theo phạm vi + cache Redis có version (SADD 3.11.1); @PreAuthorize", f"{C}/access/application/PermissionEvaluator.java", 10),
        ("BE", "API gán quyền; Site Manager chỉ trong địa điểm của mình (INV-27)", f"{C}/access/web/AssignmentController.java", 6),
        ("FE", "Màn hình người dùng, modal gán quyền theo design/users.html", f"{WEB}/views/UsersView.vue, {WEB}/components/AssignRoleDialog.vue", 8),
        ("QA", "Test phạm vi, thu hồi quyền ≤ 60 giây, Site Manager tạo người dùng", f"{E2E}/US-32_phan_quyen.feature", 5),
    ],
    "US-41": [
        ("FE", "Khung vue-i18n (vi, en), chuyển ngôn ngữ trong hồ sơ, breakpoint responsive 360px", f"{WEB}/i18n/vi.json, {WEB}/i18n/en.json, {WEB}/layouts/AppLayout.vue", 6),
        ("QA", "Kiểm tra cuộn ngang ở 360px cho mỗi màn hình mới (đưa vào DoD)", "tests/e2e/responsive.spec.ts", 2),
    ],
    # ---------- S2 ----------
    "US-33": [
        ("BE", "Bảng audit_log phân vùng theo tháng, trigger chặn UPDATE/DELETE, quyền DB chỉ INSERT/SELECT", f"{MIG}/V2_1__audit.sql", 5),
        ("BE", "Listener AuditableEvent → audit_log; API tra cứu, xuất CSV", f"{C}/audit/application/AuditRecorder.java, {C}/audit/web/AuditController.java", 6),
        ("FE", "Màn hình nhật ký theo design/audit.html", f"{WEB}/views/AuditView.vue", 4),
        ("QA", "Test không sửa/xóa được nhật ký", f"{E2E}/US-33_nhat_ky.feature", 3),
    ],
    "US-31": [
        ("BE", "TOTP, mã dự phòng (RecoveryCode), bắt buộc 2FA theo Role.requireTwoFactor", f"{C}/identity/application/TwoFactorService.java", 8),
        ("FE", "Bước nhập mã 2FA, thiết lập 2FA (QR), cài đặt bắt buộc 2FA ở trang Hệ thống", f"{WEB}/views/LoginView.vue, {WEB}/views/system/SecurityTab.vue", 6),
        ("QA", "Test bắt buộc 2FA, mã dự phòng dùng một lần", f"{E2E}/US-31_2fa.feature", 3),
    ],
    "US-02": [
        ("BE", "Entity Camera, StreamProfile, DeviceCapabilities; mật khẩu mã hóa; migration", f"{C}/device/domain/Camera.java, {MIG}/V2_2__device.sql", 6),
        ("BE", "API test-connection, tạo camera; tạo path trên MediaMTX", f"{C}/device/web/CameraController.java, {C}/device/infrastructure/MediaMtxClient.java", 6),
        ("PY", "Endpoint kiểm tra kết nối ONVIF/RTSP, lấy ảnh xem trước", f"{GW}/control/connection.py", 5),
        ("FE", "Modal thêm thủ công (ONVIF/RTSP), kiểm tra kết nối", f"{WEB}/components/AddCameraManualDialog.vue", 5),
        ("QA", "Test 2 kịch bản", f"{E2E}/US-02_them_thu_cong.feature", 3),
    ],
    "US-01": [
        ("BE", "API POST /sites/{id}/discovery, đánh dấu thiết bị đã thêm theo MAC, thêm hàng loạt", f"{C}/device/application/DiscoveryService.java", 6),
        ("PY", "Dò tìm theo địa điểm, timeout 30 giây, trả DiscoveredDevice", f"{GW}/discovery/service.py", 4),
        ("FE", "Wizard dò tìm 4 bước theo design/cameras.html", f"{WEB}/components/DiscoveryWizard.vue", 8),
        ("QA", "Test 3 kịch bản với camera thật và giả lập", f"{E2E}/US-01_do_tim.feature", 4),
    ],
    "US-04": [
        ("PY", "Theo dõi kết nối định kỳ, phát DeviceOnline/DeviceOffline lên RabbitMQ", f"{GW}/events/health_monitor.py", 5),
        ("BE", "Cập nhật trạng thái, lastOfflineAt; đẩy WebSocket /topic/org/{id}/camera-status", f"{C}/device/application/CameraStatusService.java, {C}/platform/ws/WebSocketConfig.java", 6),
        ("FE", "Badge trạng thái realtime trong danh sách camera và cây", f"{WEB}/stores/cameraStatus.ts", 4),
        ("QA", "Test cập nhật ≤ 60 giây", f"{E2E}/US-04_trang_thai.feature", 2),
    ],
    # ---------- S3 ----------
    "US-08": [
        ("BE", "POST /cameras/{id}/live-session: kiểm quyền, cấp token HMAC 60 giây; /internal/media/authorize hoàn chỉnh", f"{C}/liveview/application/LiveSessionService.java", 8),
        ("FE", "Hoàn thiện WhepPlayer, tự chuyển HLS khi WebRTC thất bại", f"{WEB}/player/WhepPlayer.ts", 6),
        ("OPS", "Cấu hình chuyển mã theo yêu cầu ({camera}_h264), giới hạn số phiên", "deploy/mediamtx/mediamtx.yml", 4),
        ("QA", "Ma trận 4 trình duyệt, test không có quyền, test HLS dự phòng", f"{E2E}/US-08_xem_truc_tiep.feature", 6),
    ],
    "US-09": [
        ("FE", "Lưới 1/4/9/16, kéo thả từ cây, nhấp đúp phóng to, luồng phụ cho lưới lớn", f"{WEB}/views/LiveView.vue, {WEB}/components/VideoGrid.vue, {WEB}/components/VideoTile.vue", 10),
        ("QA", "Test 3 kịch bản", f"{E2E}/US-09_luoi.feature", 3),
    ],
    "US-10": [
        ("BE", "ViewLayout, LayoutTile; API CRUD; loại camera mất quyền (INV-13); migration", f"{C}/liveview/domain/ViewLayout.java, {MIG}/V3_1__liveview.sql", 5),
        ("FE", "Lưu/mở bố cục, mở bố cục gần nhất khi đăng nhập", f"{WEB}/stores/layouts.ts", 3),
        ("QA", "Test 2 kịch bản", f"{E2E}/US-10_bo_cuc.feature", 2),
    ],
    "US-12": [
        ("PY", "Lệnh PTZ ContinuousMove/Stop/Zoom", f"{GW}/control/ptz.py", 5),
        ("BE", "API PTZ, khóa Redis theo Role.rank (Lua script), thông báo WebSocket khi mất khóa", f"{C}/liveview/application/PtzLockService.java", 6),
        ("FE", "Bảng PTZ, kéo chuột trên khung hình, phím mũi tên", f"{WEB}/components/PtzPanel.vue", 5),
        ("QA", "Test tranh chấp điều khiển", f"{E2E}/US-12_ptz.feature", 3),
    ],
    "US-13": [
        ("PY", "GetPresets/SetPreset/GotoPreset", f"{GW}/control/ptz.py", 3),
        ("BE", "PtzPreset (source DEVICE/CAMHUB), API", f"{C}/device/domain/PtzPreset.java", 3),
        ("FE", "Danh sách preset, lưu vị trí", f"{WEB}/components/PtzPanel.vue", 2),
        ("QA", "Test lưu và gọi preset", f"{E2E}/US-13_preset.feature", 1),
    ],
    "US-29": [
        ("PY", "Chuẩn hóa options của camera thành DeviceCapabilities (codec, độ phân giải, fps, bitrate, imaging, số vùng che)", f"{GW}/capabilities/normalizer.py", 6),
        ("BE", "Lưu và trả capabilities; validate thay đổi theo capabilities", f"{C}/configuration/application/CapabilityValidator.java", 4),
        ("QA", "Test với 5 hãng từ bộ kiểm thử tương thích", "device-gateway/tests/compat/test_capabilities.py", 3),
    ],
    # ---------- S4 ----------
    "US-16": [
        ("BE", "RecordingPolicy, WeeklySchedule; điều khiển ghi trên MediaMTX theo lịch/sự kiện; migration", f"{C}/recording/domain/RecordingPolicy.java, {MIG}/V4_1__recording.sql", 8),
        ("FE", "Tab Ghi hình: chế độ, trước/sau sự kiện, lịch tuần", f"{WEB}/views/camera/RecordingTab.vue", 5),
        ("QA", "Test ghi theo sự kiện, áp cho nhiều camera", f"{E2E}/US-16_che_do_ghi.feature", 3),
    ],
    "US-17": [
        ("BE", "Nhận hook đoạn ghi → recording_segment (phân vùng tháng); API timeline; ghi VIEW_PLAYBACK", f"{C}/recording/web/TimelineController.java, {C}/recording/application/SegmentIndexer.java", 8),
        ("FE", "Timeline thu phóng 24 giờ – 10 phút, lịch có dữ liệu, nhảy tới đoạn gần nhất", f"{WEB}/components/Timeline.vue, {WEB}/views/PlaybackView.vue", 10),
        ("QA", "Test phát ≤ 3 giây, không có dữ liệu, hiển thị timeline", f"{E2E}/US-17_timeline.feature", 4),
    ],
    "US-18": [
        ("FE", "Tốc độ 0,5x–16x, từng khung hình, phím tắt", f"{WEB}/player/PlaybackControls.vue", 5),
        ("QA", "Test phím tắt", f"{E2E}/US-18_dieu_khien_phat.feature", 2),
    ],
    "US-21": [
        ("BE", "RetentionPolicy, CameraRetentionOverride, EvidenceHold; migration", f"{C}/recording/domain/, {MIG}/V4_2__retention.sql", 5),
        ("BE", "Job xóa 02:00 theo SADD 3.11.2 (bỏ qua đoạn giữ lại, xóa lô 500)", f"{C}/recording/application/RetentionPurgeJob.java", 6),
        ("FE", "Cài đặt lưu trữ mặc định, ghi đè theo camera, modal giữ lại", f"{WEB}/views/system/StorageTab.vue, {WEB}/components/EvidenceHoldDialog.vue", 5),
        ("QA", "Test ghi đè, giữ lại, quyền HOLD_EVIDENCE", f"{E2E}/US-21_luu_tru.feature", 4),
    ],
    "US-23": [
        ("PY", "Ghi cấu hình encoder (SetVideoEncoderConfiguration), trả lỗi theo tham số", f"{GW}/control/encoder.py", 5),
        ("BE", "API GET/PUT /cameras/{id}/config/stream, kết quả từng tham số, CHANGE_CONFIG", f"{C}/configuration/web/CameraConfigController.java", 5),
        ("FE", "Tab Luồng video và bảng kết quả áp dụng", f"{WEB}/views/camera/StreamTab.vue", 4),
        ("QA", "Test áp dụng thành công và một phần", f"{E2E}/US-23_luong_video.feature", 2),
    ],
    # ---------- S5 ----------
    "US-20": [
        ("BE", "ExportJob + state machine; POST /exports (≤ 60 phút, EXPORT_VIDEO); phát ExportRequested", f"{C}/export/domain/ExportJob.java, {C}/export/web/ExportController.java, {MIG}/V5_1__export.sql", 8),
        ("PY", "Media Worker: nhận việc, FFmpeg ghép/cắt/mã H.264, tải lên MinIO, phát kết quả", "media-worker/app/export_job.py", 8),
        ("BE", "Job hết hạn 24 giờ; URL MinIO ký 5 phút", f"{C}/export/application/ExportExpiryJob.java", 3),
        ("FE", "Modal xuất clip, danh sách clip đã xuất, thông báo WebSocket", f"{WEB}/components/ExportDialog.vue", 5),
        ("QA", "Test 4 kịch bản", f"{E2E}/US-20_xuat_clip.feature", 4),
    ],
    "US-26": [
        ("PY", "Ghi vùng chuyển động (cell/polygon), độ nhạy, vùng che", f"{GW}/control/zones.py", 8),
        ("BE", "MotionZone, PrivacyMask; validate giới hạn số vùng theo capabilities", f"{C}/configuration/domain/MotionZone.java", 4),
        ("FE", "Công cụ vẽ vùng (lưới ô, đa giác) theo design/camera-detail.html", f"{WEB}/components/ZoneEditor.vue", 8),
        ("QA", "Test vùng che trên video ghi, giới hạn số vùng", f"{E2E}/US-26_vung.feature", 3),
    ],
    "US-34": [
        ("BE", "AlertRule, Alert, AlertStatusChange; quy tắc CAMERA_OFFLINE/SITE_OFFLINE; tự RECOVERED; migration", f"{C}/alerting/domain/, {MIG}/V5_2__alerting.sql", 8),
        ("BE", "Notification web + email (SMTP), thử lại 3 lần", f"{C}/notification/application/NotificationDispatcher.java", 5),
        ("FE", "Danh sách cảnh báo cơ bản", f"{WEB}/views/AlertsView.vue", 4),
        ("QA", "Test thời gian chờ, gộp địa điểm, tự phục hồi", f"{E2E}/US-34_mat_ket_noi.feature", 3),
    ],
    "US-35": [
        ("BE", "StorageVolume, job đo dung lượng, ước tính số ngày, quy tắc STORAGE_THRESHOLD", f"{C}/recording/application/StorageMonitorJob.java", 4),
        ("QA", "Test vượt ngưỡng", f"{E2E}/US-35_luu_tru_canh_bao.feature", 1),
    ],
    "US-39": [
        ("BE", "GET /system/overview tổng hợp từ các module", f"{C}/platform/web/SystemOverviewController.java", 3),
        ("FE", "Trang Tổng quan theo design/dashboard.html", f"{WEB}/views/DashboardView.vue", 5),
        ("QA", "Test hiển thị chỉ số", f"{E2E}/US-39_tong_quan.feature", 1),
    ],
    "US-40": [
        ("OPS", "pgBackRest: bản đầy đủ hằng ngày, WAL liên tục, giữ 30 bản; sao chép MinIO hằng giờ", "deploy/backup/", 8),
        ("OPS", "Runbook khôi phục và diễn tập lần đầu", "docs/runbook/restore.md", 6),
        ("BE", "GET /system/backups (chỉ xem)", f"{C}/platform/web/BackupStatusController.java", 2),
        ("FE", "Tab Sao lưu chỉ xem", f"{WEB}/views/system/BackupTab.vue", 2),
        ("QA", "Kiểm chứng RTO ≤ 4 giờ, RPO ≤ 1 giờ trong diễn tập", "docs/runbook/restore-drill-01.md", 3),
    ],
    # ---------- S6 ----------
    "TS-06": [
        ("OPS", "Tunnel Server (chisel) sau Nginx, xác thực theo Connector", "deploy/tunnel/", 6),
        ("PY", "Site Connector: client đường hầm, dò tìm WS-Discovery tại chỗ, báo băng thông", "site-connector/app/", 12),
        ("PY", "Device Gateway định tuyến lệnh ONVIF qua đường hầm", f"{GW}/transport/tunnel_router.py", 4),
        ("QA", "Test địa điểm chỉ mở cổng 443 ra ngoài", "tests/integration/tunnel_test.md", 4),
    ],
    "US-06": [
        ("BE", "Connector: mã kích hoạt 1 lần, hết hạn 24 giờ, trạng thái, lastSeenAt", f"{C}/device/domain/Connector.java, {C}/device/web/ConnectorController.java", 6),
        ("FE", "Tab Bộ kết nối, modal tạo (mã + lệnh cài)", f"{WEB}/views/system/ConnectorsTab.vue", 4),
        ("QA", "Test kích hoạt và mã hết hạn", f"{E2E}/US-06_bo_ket_noi.feature", 2),
    ],
    "US-03": [
        ("PY", "Đọc danh sách kênh của NVR/DVR", f"{GW}/discovery/recorder_channels.py", 5),
        ("BE", "Recorder, tạo camera theo kênh, trạng thái theo đầu ghi", f"{C}/device/domain/Recorder.java", 5),
        ("FE", "Modal thêm đầu ghi, chọn kênh", f"{WEB}/components/AddRecorderDialog.vue", 4),
        ("QA", "Test 2 kịch bản", f"{E2E}/US-03_dau_ghi.feature", 2),
    ],
    "US-11": [
        ("FE", "Đo getStats, chuyển luồng có trễ 5 giây, quay lại khi ≥ 1,5 lần bitrate trong 30 giây; chọn thủ công", f"{WEB}/player/AdaptiveStream.ts", 8),
        ("QA", "Test với giới hạn băng thông (tc/netem)", f"{E2E}/US-11_chuyen_luong.feature", 3),
    ],
    "US-14": [
        ("FE", "Chụp ảnh PNG, zoom số 8x, toàn màn hình", f"{WEB}/components/VideoTile.vue", 4),
        ("QA", "Test chụp ảnh", f"{E2E}/US-14_chup_anh.feature", 1),
    ],
    "US-28": [
        ("PY", "SetUser (đổi mật khẩu), SystemReboot", f"{GW}/control/maintenance.py", 3),
        ("BE", "API đổi mật khẩu hàng loạt (cập nhật mật khẩu đã lưu khi thành công), khởi động lại", f"{C}/device/application/MaintenanceService.java", 4),
        ("FE", "Modal đổi mật khẩu, xác nhận khởi động lại", f"{WEB}/components/ChangeCameraPasswordDialog.vue", 2),
        ("QA", "Test 2 kịch bản", f"{E2E}/US-28_bao_tri.feature", 2),
    ],
    # ---------- S7 ----------
    "US-36": [
        ("PY", "Đăng ký PullPoint với từng camera, chuẩn hóa MotionDetected", f"{GW}/events/pullpoint.py", 8),
        ("BE", "Đánh giá quy tắc MOTION theo lịch, chống spam Redis SET NX EX, yêu cầu ảnh sự kiện", f"{C}/alerting/application/MotionRuleEvaluator.java", 6),
        ("PY", "Media Worker chụp ảnh sự kiện lên MinIO", "media-worker/app/snapshot_job.py", 3),
        ("BE", "Kênh Telegram, Zalo; liên kết kênh nhận của người dùng (UserChannelBinding)", f"{C}/notification/infrastructure/TelegramSender.java, ZaloSender.java", 8),
        ("FE", "Tab Quy tắc cảnh báo, Kênh nhận của tôi, Kênh thông báo (Hệ thống)", f"{WEB}/views/alerts/RulesTab.vue, {WEB}/views/alerts/ChannelsTab.vue", 6),
        ("QA", "Test 4 kịch bản", f"{E2E}/US-36_canh_bao_chuyen_dong.feature", 4),
    ],
    "US-37": [
        ("BE", "acknowledge, reassign (INV-28, AlertHandover), close bắt buộc outcome", f"{C}/alerting/application/AlertWorkflowService.java", 6),
        ("FE", "Panel chi tiết cảnh báo, modal chuyển giao, modal đóng", f"{WEB}/views/alerts/AlertDetail.vue", 5),
        ("QA", "Test 3 kịch bản", f"{E2E}/US-37_xu_ly_canh_bao.feature", 3),
    ],
    "US-38": [
        ("FE", "Nút Xem lại tại thời điểm cảnh báo trừ 10 giây", f"{WEB}/views/alerts/AlertDetail.vue", 2),
        ("QA", "Test mở xem lại", f"{E2E}/US-38_xem_lai_tu_canh_bao.feature", 1),
    ],
    "US-42": [
        ("FE", "Chuông + toast + âm báo, nhận /user/queue/alerts", f"{WEB}/components/NotificationBell.vue", 4),
        ("QA", "Test cảnh báo mới", f"{E2E}/US-42_trung_tam_thong_bao.feature", 1),
    ],
    "US-22": [
        ("PY", "Tìm kiếm và lấy URL phát lại trên thiết bị (ONVIF Profile G)", f"{GW}/edge_playback/search.py", 8),
        ("BE", "Timeline nguồn DEVICE; phát qua MediaMTX path tạm", f"{C}/recording/application/EdgePlaybackService.java", 6),
        ("FE", "Chọn nguồn CamHub/Thiết bị, vô hiệu khi không hỗ trợ", f"{WEB}/views/PlaybackView.vue", 3),
        ("QA", "Test với camera Profile G và đầu ghi", f"{E2E}/US-22_xem_lai_thiet_bi.feature", 3),
    ],
    "US-24": [
        ("PY", "Imaging service: sáng, tương phản, IR, WDR", f"{GW}/control/imaging.py", 3),
        ("FE", "Tab Hình ảnh có xem trước, khôi phục giá trị trước", f"{WEB}/views/camera/ImageTab.vue", 3),
        ("QA", "Test khôi phục", f"{E2E}/US-24_hinh_anh.feature", 1),
    ],
    # ---------- S8 ----------
    "US-19": [
        ("FE", "Chế độ đồng bộ tối đa 4 camera, timeline chung, bù lệch ≤ 1 giây", f"{WEB}/views/PlaybackView.vue, {WEB}/player/SyncController.ts", 8),
        ("QA", "Test đồng bộ và giới hạn 4", f"{E2E}/US-19_dong_bo.feature", 2),
    ],
    "US-25": [
        ("PY", "SetNetworkInterfaces, SetNTP, SetSystemDateAndTime", f"{GW}/control/network.py", 3),
        ("BE", "Cập nhật địa chỉ sau khi đổi IP; đánh dấu lệch giờ > 5 giây", f"{C}/configuration/application/NetworkConfigService.java", 3),
        ("FE", "Tab Mạng & thời gian, cảnh báo đổi IP", f"{WEB}/views/camera/NetworkTab.vue", 2),
        ("QA", "Test 2 kịch bản", f"{E2E}/US-25_mang.feature", 1),
    ],
    "US-27": [
        ("BE", "ConfigTemplate, TemplateApplication; áp song song, kết quả SUCCESS/PARTIAL/FAILED; migration", f"{C}/configuration/application/TemplateApplyService.java, {MIG}/V8_1__templates.sql", 8),
        ("FE", "Trang Cấu hình mẫu theo design/templates.html", f"{WEB}/views/TemplatesView.vue", 5),
        ("QA", "Test áp mẫu 4 camera", f"{E2E}/US-27_cau_hinh_mau.feature", 2),
    ],
    "US-43": [
        ("BE", "Tạo/sao chép/sửa/xóa vai trò tùy chỉnh, MANAGE_ROLES, INV-20 đến INV-22", f"{C}/access/application/RoleService.java", 6),
        ("FE", "Tab Vai trò, bảng quyền", f"{WEB}/views/users/RolesTab.vue", 5),
        ("QA", "Test 3 kịch bản", f"{E2E}/US-43_vai_tro.feature", 2),
    ],
    "US-44": [
        ("BE", "DirectoryConnection, DirectoryGroupMapping; LDAPS bind (Spring Security LDAP); migration", f"{C}/identity/infrastructure/LdapAuthenticator.java, {MIG}/V8_2__directory.sql", 8),
        ("BE", "Job đồng bộ mỗi giờ: tạo/cập nhật/vô hiệu hóa người dùng, cập nhật RoleAssignment (INV-26)", f"{C}/identity/application/DirectorySyncJob.java", 6),
        ("FE", "Tab LDAP/AD, kiểm tra kết nối, ánh xạ nhóm", f"{WEB}/views/users/LdapTab.vue", 5),
        ("QA", "Test với AD thử nghiệm (Samba AD hoặc OpenLDAP)", f"{E2E}/US-44_ldap.feature", 4),
    ],
    # ---------- S9 ----------
    "US-07": [
        ("BE", "Nhập file .xlsx/.csv, xem trước kèm lỗi, báo cáo kết quả", f"{C}/device/application/CameraImportService.java", 5),
        ("FE", "Modal nhập từ file", f"{WEB}/components/ImportCamerasDialog.vue", 3),
        ("QA", "Test bỏ qua dòng lỗi", f"{E2E}/US-07_nhap_file.feature", 1),
    ],
    "US-15": [
        ("OPS", "Bật luồng âm thanh hai chiều trên MediaMTX (backchannel)", "deploy/mediamtx/mediamtx.yml", 4),
        ("FE", "Nhấn giữ để nói (getUserMedia → WebRTC)", f"{WEB}/components/TalkButton.vue", 6),
        ("BE", "Ghi TALK_AUDIO kèm thời lượng", f"{C}/liveview/application/TalkAuditService.java", 2),
        ("QA", "Test với camera có loa", f"{E2E}/US-15_dam_thoai.feature", 2),
    ],
    "TS-08": [
        ("QA", "Kịch bản Gatling cho API chính; giả lập 500 luồng RTSP", "tests/load/", 14),
        ("QA", "Chạy kiểm thử tải 1 giờ, báo cáo NFR-01 đến NFR-04", "docs/test/load-report.md", 6),
        ("OPS", "Hỗ trợ kiểm thử xâm nhập (môi trường, tài khoản), diễn tập khôi phục lần 2", "docs/runbook/restore-drill-02.md", 8),
        ("BE", "Sửa lỗi bảo mật và hiệu năng phát hiện", "(theo báo cáo)", 16),
    ],
}

# Thông tin phase: ngày, điều kiện vào/ra, demo, rủi ro, lịch tuần
PHASES = {
    "S0": dict(slug="phase-00-S0-poc-nen-tang", name="PoC và nền tảng kỹ thuật", start="26/10/2026", end="06/11/2026",
               entry=["Đã chốt công nghệ (SADD frontmatter)", "Có 50 camera thật của ít nhất 3 hãng để PoC (D-04)", "Có máy chủ dev/staging (D-01)"],
               exit=["CI xanh trên main cho cả 4 thành phần", "docker compose up chạy được toàn bộ dịch vụ", "Báo cáo PoC MediaMTX kết luận đạt/không đạt NFR-01; nếu không đạt, họp quyết định lại ADR-03 trước S1", "RLS và outbox có test tích hợp chạy trong CI"],
               demo=["Chạy docker compose up trên máy mới", "Mở player PoC, phát 20 luồng WHEP và hiển thị số đo first frame", "Trình bày báo cáo PoC"],
               risks=["PoC MediaMTX không đạt hiệu năng (AR-01): phương án dự phòng là thử go2rtc hoặc tách nhiều media node", "Chưa có đủ camera thật: dùng giả lập RTSP, nhưng chưa kiểm chứng được ONVIF"],
               weeks=["TS-01, TS-02 (ngày 1–4); bắt đầu TS-04", "TS-03 (PoC đo trên môi trường TS-02), TS-04, TS-07; viết báo cáo PoC"]),
    "S1": dict(slug="phase-01-S1-dang-nhap-phan-quyen", name="Đăng nhập, cây địa điểm, phân quyền", start="09/11/2026", end="20/11/2026",
               entry=["Phase S0 đạt điều kiện ra", "PO duyệt màn hình login, users, cameras trong design/"],
               exit=["Đăng nhập cục bộ, khóa tài khoản, phiên 12 giờ chạy trên staging", "Cây Địa điểm → Khu vực → Nhóm, chuyển camera ghi lịch sử", "Phân quyền theo phạm vi có hiệu lực ≤ 60 giây", "Khung i18n vi/en và layout responsive dùng chung"],
               demo=["Admin đăng nhập, tạo địa điểm Kho Bắc Ninh và 3 khu vực", "Tạo Site Manager Hương, gán phạm vi Chi nhánh Hà Nội; Hương chỉ thấy địa điểm của mình", "Đổi ngôn ngữ sang English"],
               risks=["PermissionEvaluator là nền cho mọi API; sai thiết kế sẽ tốn công sửa ở các sprint sau: review kiến trúc trước khi merge"],
               weeks=["US-30, US-05 (domain + migration), TS-05", "US-32, US-41; hoàn thiện giao diện US-05"]),
    "S2": dict(slug="phase-02-S2-them-camera-trang-thai", name="2FA, nhật ký, thêm camera và trạng thái", start="23/11/2026", end="04/12/2026",
               entry=["S1 đạt điều kiện ra", "Bộ kiểm thử tương thích (TS-05) đã chạy trên camera mẫu"],
               exit=["2FA bắt buộc theo vai trò", "Nhật ký không sửa/xóa được ở mức DB", "Thêm camera thủ công và bằng dò tìm trong LAN", "Trạng thái camera cập nhật realtime ≤ 60 giây"],
               demo=["Minh (Technician) bị yêu cầu thiết lập 2FA", "Dò tìm Kho Bắc Ninh, thêm 3 camera, 1 camera sai mật khẩu", "Rút dây mạng một camera, trạng thái đổi trong 60 giây; xem nhật ký"],
               risks=["Một số hãng không trả lời WS-Discovery: hướng dẫn dùng thêm thủ công (US-02)"],
               weeks=["US-33, US-31, US-02", "US-01, US-04"]),
    "S3": dict(slug="phase-03-S3-xem-truc-tiep", name="Xem trực tiếp, bố cục, PTZ", start="07/12/2026", end="18/12/2026",
               entry=["S2 đạt điều kiện ra", "Kết luận PoC MediaMTX đã áp dụng vào cấu hình"],
               exit=["Xem trực tiếp trên 4 trình duyệt ≤ 3 giây, HLS dự phòng", "Lưới 1/4/9/16, bố cục lưu theo người dùng", "PTZ, preset, khóa điều khiển theo thứ hạng", "Tùy chọn cấu hình chỉ theo capabilities"],
               demo=["Operator mở bố cục Ca đêm, lưới 9, nhấp đúp phóng to", "Hai người cùng điều khiển PTZ, người thứ hạng thấp bị từ chối", "Viewer không có quyền bị chặn ở API (403)"],
               risks=["Safari iOS hạn chế WebRTC/codec: ưu tiên kiểm thử sớm trong sprint"],
               weeks=["US-08, US-29, bắt đầu US-09", "US-09, US-10, US-12, US-13"]),
    "S4": dict(slug="phase-04-S4-ghi-hinh-xem-lai", name="Ghi hình, timeline, lưu trữ, cấu hình luồng", start="21/12/2026", end="01/01/2027",
               entry=["S3 đạt điều kiện ra"],
               exit=["Ghi liên tục/lịch/sự kiện", "Timeline và phát lại ≤ 3 giây", "Job xóa quá hạn chạy đúng và bỏ qua đoạn giữ lại", "Cấu hình luồng video trả kết quả theo từng tham số"],
               demo=["Đặt ghi theo sự kiện, tạo chuyển động, xem lại đúng đoạn có ghi trước 10 giây", "Đánh dấu giữ lại một đoạn, chạy job xóa với thời gian lưu 1 ngày, đoạn giữ lại còn nguyên"],
               risks=["Nghỉ Tết Dương lịch 01/01/2027 làm giảm khoảng 1 ngày công", "Thời gian lưu mặc định chưa chốt (OQ-04): dùng 30 ngày làm cấu hình"],
               weeks=["US-16, US-23, US-21 (domain)", "US-17, US-18, US-21 (job + giao diện)"]),
    "S5": dict(slug="phase-05-S5-xuat-clip-canh-bao-alpha", name="Xuất clip, vùng, cảnh báo hệ thống, tổng quan, sao lưu – hoàn tất Alpha", start="04/01/2027", end="15/01/2027",
               entry=["S4 đạt điều kiện ra"],
               exit=["Toàn bộ 26 story Must chạy trên staging (mốc M1 – Alpha)", "Xuất clip MP4 qua Media Worker", "Cảnh báo mất kết nối và dung lượng gửi qua web, email", "Diễn tập khôi phục lần 1 đạt RTO ≤ 4 giờ"],
               demo=["Kịch bản SC-03: trích xuất bằng chứng từ 2 camera, xuất clip, xem nhật ký", "Kịch bản SC-04: camera mất kết nối, cảnh báo, khởi động lại", "Trang Tổng quan"],
               risks=["Sprint đã ở 31/30 điểm: nếu trễ, chuyển US-39 sang S6 nhưng vẫn tính là Must của Alpha"],
               weeks=["US-20, US-34", "US-26, US-35, US-39, US-40"]),
    "S6": dict(slug="phase-06-S6-ket-noi-dia-diem", name="Bộ kết nối địa điểm, đầu ghi, chất lượng luồng", start="18/01/2027", end="29/01/2027",
               entry=["Alpha (M1) được PO chấp nhận", "Chọn được địa điểm pilot có camera của ít nhất 3 hãng"],
               exit=["Camera sau NAT kết nối qua cổng 443", "Thêm đầu ghi NVR/DVR", "Tự chuyển luồng khi mạng yếu", "Đổi mật khẩu hàng loạt, khởi động lại camera"],
               demo=["Kích hoạt bộ kết nối tại một chi nhánh không có IP tĩnh, dò tìm camera qua đường hầm", "Giới hạn băng thông, ô video tự hạ chất lượng"],
               risks=["Băng thông qua đường hầm không đủ (AR-06): đo thực tế tại địa điểm pilot", "Trước Tết Nguyên đán: chốt bản Beta để triển khai pilot sau Tết"],
               weeks=["TS-06, US-06", "US-03, US-11, US-14, US-28"]),
    "S7": dict(slug="phase-07-S7-canh-bao-da-kenh", name="Cảnh báo đa kênh và xử lý; xem lại từ thiết bị", start="15/02/2027", end="26/02/2027",
               entry=["S6 đạt điều kiện ra", "Đã có Telegram bot; Zalo OA đã xác thực (OQ-P02), nếu chưa có thì làm Zalo sau cùng"],
               exit=["Cảnh báo chuyển động theo lịch, chống spam, gửi web/email/Telegram/Zalo", "Nhận, chuyển giao, đóng cảnh báo", "Xem lại video trên thẻ nhớ/đầu ghi"],
               demo=["Kịch bản SC-02: cảnh báo đêm qua Telegram, mở xem lại, PTZ, chuyển giao, đóng"],
               risks=["Nghỉ Tết Nguyên đán (dự kiến 01/02–12/02/2027) đã được tính bằng cách lùi S7; cần xác nhận lịch nghỉ chính thức", "Zalo OA chưa sẵn sàng (BQ-03)"],
               weeks=["US-36 (Gateway PullPoint + đánh giá quy tắc), US-42", "US-36 (kênh), US-37, US-38, US-22, US-24"]),
    "S8": dict(slug="phase-08-S8-pilot-ldap-vai-tro", name="Đồng bộ nhiều camera, cấu hình mẫu, vai trò tùy chỉnh, LDAP/AD – pilot", start="01/03/2027", end="12/03/2027",
               entry=["S7 đạt điều kiện ra", "IT cấp tài khoản dịch vụ LDAP chỉ đọc (D-06)"],
               exit=["Toàn bộ story Should chạy trên staging (mốc M2 – Beta)", "Pilot 20–50 camera tại 1 địa điểm bắt đầu chạy ổn định", "Đăng nhập AD và ánh xạ nhóm"],
               demo=["Kịch bản SC-01: lắp địa điểm mới, áp cấu hình mẫu cho 12 camera", "Nhân viên đăng nhập bằng tài khoản AD; vô hiệu hóa trong AD làm mất quyền ở lần đồng bộ kế tiếp"],
               risks=["Pilot cần chạy ổn định 2 tuần (PRD M2) nhưng S9 chỉ có 2 tuần: theo dõi pilot song song với S9"],
               weeks=["US-44, US-43", "US-19, US-25, US-27; triển khai pilot"]),
    "S9": dict(slug="phase-09-S9-kiem-thu-ga", name="Story Could, kiểm thử tải và bảo mật, UAT – GA", start="15/03/2027", end="26/03/2027",
               entry=["Beta (M2) được PO chấp nhận", "Pilot đang chạy, có kênh thu lỗi"],
               exit=["Đạt tiêu chí nghiệm thu BRS mục 7 (toàn bộ BR-01 đến BR-40)", "NFR-01 đến NFR-04 đạt dưới tải 500 camera/100 người dùng", "Không còn lỗi bảo mật nghiêm trọng/cao", "Người dùng pilot đánh giá ≥ 4/5; ký phê duyệt (mốc M3 – GA)"],
               demo=["Trình bày báo cáo kiểm thử tải và kiểm thử xâm nhập", "UAT theo các kịch bản SC-01 đến SC-04 với người dùng pilot"],
               risks=["Chỉ còn khoảng 14 điểm dự phòng cho sửa lỗi pilot (BQ-02): đề xuất thêm S10 hoặc chuyển US-15 sang phiên bản sau"],
               weeks=["US-07, US-15, TS-08 (chuẩn bị, chạy tải)", "TS-08 (kiểm thử xâm nhập, sửa lỗi), UAT, phê duyệt"]),
}

ROLES = {"BE": "Backend Java (2 người)", "PY": "Python – Device Gateway, Media Worker, Site Connector (1 người)",
         "FE": "Frontend Vue (2 người)", "QA": "Kiểm thử (1 người)", "OPS": "DevOps (bán thời gian, kiêm PY hoặc BE)"}
