package vn.camhub.device.domain;

/**
 * Một luồng của camera (StreamProfile, class diagram mục 3). {@code rtspUrl} không chứa tài khoản.
 *
 * @param bFrames luồng có B-frame: WebRTC của MediaMTX không phát được, phải đi qua chuyển mã hoặc HLS (báo cáo PoC)
 */
public record StreamInfo(String type, String profileToken, String codec, Integer width, Integer height, Integer fps,
        Integer bitrateKbps, String rtspUrl, boolean bFrames) {
}
