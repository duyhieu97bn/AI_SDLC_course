package vn.camhub.device.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** API công khai của module device cho liveview, recording, alerting... (gọi trong ngữ cảnh Tổ chức). */
public interface DeviceApi {

    /**
     * Thông tin phát luồng của camera.
     *
     * @param needsTranscode luồng chính không phát được qua WebRTC (H.265 hoặc có B-frame) → dùng path {@code _h264}
     */
    record CameraMedia(UUID cameraId, String name, UUID siteId, String mediaPath, boolean hasSub, String mainCodec,
            String subCodec, boolean needsTranscode, boolean subNeedsTranscode, boolean ptz, boolean onvif,
            String status, boolean recording, boolean edgeRecording) {
    }

    Optional<CameraMedia> media(UUID cameraId);

    /** Trạng thái kết nối của camera (cảnh báo mất kết nối US-34, Tổng quan US-39). */
    /** {@code motionDetection}: camera báo hỗ trợ phát hiện chuyển động (US-36 AC4). */
    record CameraState(UUID cameraId, String name, UUID siteId, UUID zoneId, String status,
            java.time.Instant statusSince, boolean motionDetection) {
    }

    /** Mọi camera của Tổ chức hiện tại. */
    List<CameraState> states();

    Optional<CameraState> state(UUID cameraId);

    record PathOwner(UUID cameraId, UUID organizationId) {
    }

    /** Camera sở hữu path MediaMTX (kể cả {@code -sub}, {@code _h264}); tra được trước khi biết Tổ chức. */
    Optional<PathOwner> cameraOfPath(String path);

    /** Tổ chức của camera, tra được trước khi biết Tổ chức (xử lý sự kiện thiết bị từ RabbitMQ). */
    Optional<UUID> organizationOf(UUID cameraId);

    // ---------------------------------------------------------------- điều khiển thiết bị theo camera

    void ptzMove(UUID cameraId, double pan, double tilt, double zoom);

    void ptzStop(UUID cameraId);

    List<DeviceControlPort.Preset> presets(UUID cameraId);

    void gotoPreset(UUID cameraId, String presetToken);

    String setPreset(UUID cameraId, String name);

    void removePreset(UUID cameraId, String presetToken);

    byte[] snapshot(UUID cameraId);

    /** Bật/tắt ghi trên MediaMTX cho luồng chính (US-16); bật ghi thì MediaMTX kéo luồng liên tục. */
    void setRecording(UUID cameraId, boolean on);

    /** Camera (id) của Tổ chức hiện tại, để module khác duyệt (lập lịch ghi...). */
    List<UUID> cameraIds();

    // ---------------------------------------------------------------- cấu hình luồng (US-23)

    List<DeviceControlPort.EncoderConfig> encoders(UUID cameraId);

    DeviceControlPort.ZonesConfig zones(UUID cameraId);

    DeviceControlPort.ImagingSettings imaging(UUID cameraId);

    /** Mạng, nguồn giờ và độ lệch đồng hồ (lưu lại để đánh dấu camera lệch giờ, US-25 AC2). */
    DeviceControlPort.NetworkInfo network(UUID cameraId);

    /** Đổi IP; thành công thì cập nhật địa chỉ đã lưu của camera và path MediaMTX (US-25 AC1). */
    DeviceControlPort.NetworkSetResult setNetwork(UUID cameraId, DeviceControlPort.NetworkChange change);

    void setTime(UUID cameraId, DeviceControlPort.TimeChange change);

    /** Video lưu trên thẻ nhớ/đầu ghi (US-22); kênh đầu ghi tự lọc theo nguồn video của kênh. */
    DeviceControlPort.RecordingSearch edgeRecordings(UUID cameraId, java.time.Instant from, java.time.Instant to);

    /** Mở luồng phát lại từ thiết bị thành path tạm {@code edge-...} trên MediaMTX (sống 30 phút); trả về path. */
    String openEdgeStream(UUID cameraId, String recordingToken, java.time.Instant start);

    void setImaging(UUID cameraId, String videoSourceToken, DeviceControlPort.ImagingChange change);

    void setMotionZones(UUID cameraId, DeviceControlPort.MotionZonesChange change);

    void setPrivacyMasks(UUID cameraId, List<DeviceControlPort.PrivacyMask> masks);

    void setEncoder(UUID cameraId, DeviceControlPort.EncoderChange change);
}
