package vn.camhub.device.api;

import java.util.List;
import java.util.Optional;

/**
 * Cổng giao tiếp thiết bị (SADD 3.6 ports and adapters). Bản hiện thực mặc định gọi Device Gateway (ONVIF);
 * có thể thay bằng adapter SDK hãng mà không sửa nghiệp vụ.
 */
public interface DeviceControlPort {

    /** Địa chỉ và tài khoản thiết bị; mật khẩu chỉ ở dạng rõ trong bộ nhớ khi gọi Gateway (INV-17). */
    record DeviceAddress(String host, int port, String username, String password, String proxy) {

        /** Thiết bị trong mạng Gateway thấy trực tiếp (không qua đường hầm địa điểm). */
        public DeviceAddress(String host, int port, String username, String password) {
            this(host, port, username, password, null);
        }

        @Override
        public String toString() {
            return "DeviceAddress[" + host + ":" + port + ", user=" + username + "]";
        }
    }

    record DiscoveredDevice(String host, int port, String manufacturer, String model, String macAddress,
            String hardware, List<String> scopes) {
    }

    record StreamProfile(String token, String name, String type, String codec, int width, int height, int fps,
            int bitrateKbps, String rtspUrl, boolean bFrames) {
    }

    record Capabilities(boolean ptz, boolean audioIn, boolean audioOut, boolean imaging, boolean events,
            boolean edgeRecording, boolean motionDetection, boolean privacyMask) {
    }

    record DeviceInfo(String manufacturer, String model, String firmware, String serialNumber, String macAddress,
            Capabilities capabilities, List<StreamProfile> profiles) {
    }

    /** Dò WS-Discovery trong LAN; {@code targets} (IP hoặc CIDR) để dò unicast khi mạng chặn multicast. */
    List<DiscoveredDevice> discover(List<String> targets, int timeoutSeconds);

    DeviceInfo describe(DeviceAddress address);

    // ---------------------------------------------------------------- PTZ (US-12, US-13)

    record Preset(String token, String name) {
    }

    /** Di chuyển liên tục; pan/tilt/zoom trong [-1, 1]; camera tự dừng sau {@code timeoutMs}. */
    void ptzMove(DeviceAddress a, String profileToken, double pan, double tilt, double zoom, int timeoutMs);

    void ptzStop(DeviceAddress a, String profileToken);

    List<Preset> presets(DeviceAddress a, String profileToken);

    void gotoPreset(DeviceAddress a, String profileToken, String presetToken);

    /** Lưu vị trí hiện tại thành preset trên camera; trả về token camera cấp. */
    String setPreset(DeviceAddress a, String profileToken, String name);

    void removePreset(DeviceAddress a, String profileToken, String presetToken);

    /** Ảnh chụp JPEG từ camera (US-14, ảnh cảnh báo). */
    byte[] snapshot(DeviceAddress a, String profileToken);

    // ---------------------------------------------------------------- encoder (US-23, US-29)

    record Resolution(int width, int height) {
    }

    record IntRange(int min, int max) {
    }

    /** Khả năng encoder camera báo (US-29: chỉ hiển thị tham số được hỗ trợ). */
    record EncoderOptions(List<String> codecs, List<Resolution> resolutions, IntRange fpsRange, IntRange bitrateRange,
            IntRange govLengthRange) {
    }

    record EncoderConfig(String token, String name, String codec, int width, int height, int fps, int bitrateKbps,
            Integer govLength, boolean bFrames, EncoderOptions options) {
    }

    /** Thay đổi một cấu hình encoder; trường null giữ nguyên. */
    record EncoderChange(String configurationToken, String codec, Integer width, Integer height, Integer fps,
            Integer bitrateKbps, Integer govLength) {
    }

    List<EncoderConfig> encoders(DeviceAddress a);

    void setEncoder(DeviceAddress a, EncoderChange change);

    // ---------------------------------------------------------------- vùng chuyển động, vùng che (US-26)

    /** Tọa độ chuẩn hóa 0..1, gốc trên-trái. */
    record Point(double x, double y) {
    }

    record Grid(int columns, int rows) {
    }

    record MotionZones(boolean supported, String mode, Grid grid, List<List<Boolean>> cells,
            List<List<Point>> polygons, Integer sensitivity, Integer maxPolygons) {
    }

    record PrivacyMask(String token, List<Point> points, boolean enabled) {
    }

    record PrivacyMasks(boolean supported, Integer maxMasks, Integer maxPoints, List<PrivacyMask> items) {
    }

    record ZonesConfig(MotionZones motion, PrivacyMasks masks) {
    }

    /** {@code mode} CELL (dùng {@code cells}) hoặc POLYGON (dùng {@code polygons}); sensitivity 0..100 hoặc null. */
    record MotionZonesChange(String mode, List<List<Boolean>> cells, List<List<Point>> polygons, Integer sensitivity) {
    }

    ZonesConfig zones(DeviceAddress a);

    void setMotionZones(DeviceAddress a, MotionZonesChange change);

    /** Thay toàn bộ vùng che. */
    void setPrivacyMasks(DeviceAddress a, List<PrivacyMask> masks);

    // ---------------------------------------------------------------- xem lại từ thiết bị (US-22, Profile G)

    record RecordingRange(java.time.Instant start, java.time.Instant end) {
    }

    record DeviceRecording(String recordingToken, String videoSourceToken, String trackToken,
            List<RecordingRange> ranges) {
    }

    record RecordingSearch(boolean supported, String reason, List<DeviceRecording> recordings) {
    }

    /** Tìm đoạn video lưu trên thiết bị giao với [from, to); {@code videoSourceToken} lọc theo kênh đầu ghi. */
    RecordingSearch searchRecordings(DeviceAddress a, java.time.Instant from, java.time.Instant to,
            String videoSourceToken);

    /** URI RTSP phát lại từ {@code start} (ONVIF GetReplayUri, kèm starttime kiểu Hikvision). */
    String replayUri(DeviceAddress a, String recordingToken, java.time.Instant start);

    // ---------------------------------------------------------------- mạng và thời gian (US-25)

    /** {@code clockDriftSeconds}: đồng hồ thiết bị trừ đồng hồ máy chủ (dương = thiết bị chạy nhanh). */
    record NetworkInfo(String ipAddress, Integer prefixLength, String gateway, List<String> dns, String macAddress,
            String hostname, List<String> ntpServers, boolean ntpFromDhcp, boolean fromDhcp, String timeMode,
            String timezone, String deviceTime, double clockDriftSeconds) {
    }

    record NetworkChange(boolean dhcp, String address, Integer prefixLength, String gateway, List<String> dns) {
    }

    record NetworkSetResult(boolean rebootNeeded, String newHost) {
    }

    /** {@code mode} NTP hoặc MANUAL; {@code utc} dạng ISO cho MANUAL; {@code timezone} dạng POSIX TZ. */
    record TimeChange(String mode, List<String> ntpServers, String utc, String timezone) {
    }

    NetworkInfo network(DeviceAddress a);

    NetworkSetResult setNetwork(DeviceAddress a, NetworkChange change);

    void setTime(DeviceAddress a, TimeChange change);

    // ---------------------------------------------------------------- hình ảnh (US-24)

    record Range(double min, double max) {
    }

    record ImagingOptions(Range brightness, Range contrast, List<String> irModes, boolean wdr) {
    }

    /** Giá trị hiện tại; null là camera không hỗ trợ tham số đó. {@code irMode}: AUTO, DAY, NIGHT. */
    record ImagingSettings(String videoSourceToken, Double brightness, Double contrast, String irMode, Boolean wdr,
            ImagingOptions options) {
    }

    /** Thay đổi; trường null giữ nguyên. */
    record ImagingChange(Double brightness, Double contrast, String irMode, Boolean wdr) {
    }

    ImagingSettings imaging(DeviceAddress a);

    void setImaging(DeviceAddress a, String videoSourceToken, ImagingChange change);

    // ---------------------------------------------------------------- đầu ghi (US-03), bảo trì (US-28)

    record RecorderChannel(int channel, String name, String videoSourceToken, String profileToken,
            String subProfileToken, boolean hasSignal, Resolution resolution, String rtspUrl, String subRtspUrl) {
    }

    record RecorderInfo(String manufacturer, String model, int channelCount, List<RecorderChannel> channels) {
    }

    /** Danh sách kênh của đầu ghi NVR/DVR. */
    RecorderInfo recorderChannels(DeviceAddress a);

    /** Đổi mật khẩu tài khoản trên thiết bị (ONVIF SetUser). */
    void changePassword(DeviceAddress a, String username, String newPassword);

    /** Khởi động lại thiết bị (ONVIF SystemReboot). */
    void reboot(DeviceAddress a);

    // ---------------------------------------------------------------- Đàm thoại (US-15)

    /** Phiên backchannel đã mở tới loa camera; trình duyệt gửi PCM 16 bit mono {@code sampleRate} Hz qua WebSocket. */
    record TalkSession(String sessionId, String codec, int expiresInSeconds, int sampleRate) {
    }

    /** {@code state}: READY, ACTIVE, ENDED, FAILED; {@code durationSeconds}: thời gian nối WebSocket. */
    record TalkStatus(String sessionId, String state, java.time.Instant startedAt, java.time.Instant endedAt,
            double durationSeconds, double audioSeconds, String error) {
    }

    /** Mở kênh âm thanh tới loa camera (ONVIF backchannel); camera đang có người nói thì lỗi mã BUSY. */
    TalkSession openTalk(DeviceAddress a);

    /** Trạng thái phiên; rỗng khi Gateway không còn giữ phiên (đã quá hạn giữ hoặc Gateway khởi động lại). */
    Optional<TalkStatus> talkStatus(String sessionId);

    /** Kết thúc phiên (nếu còn mở) và trả trạng thái cuối. */
    Optional<TalkStatus> closeTalk(String sessionId);

    /**
     * Gateway không phản hồi hoặc lỗi nội bộ. Khác {@link DeviceException}: circuit breaker "gateway" đếm lỗi này,
     * còn lỗi do thiết bị (sai mật khẩu, camera tắt) thì không.
     */
    class GatewayUnavailableException extends RuntimeException {
        public GatewayUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Lỗi từ thiết bị, với mã ổn định để giao diện hiển thị (AUTH_FAILED, UNREACHABLE, NOT_ONVIF...). */
    class DeviceException extends RuntimeException {
        private final String code;

        public DeviceException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
