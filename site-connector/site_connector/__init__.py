"""CamHub Site Connector.

Chạy tại mạng của địa điểm, chỉ mở kết nối ra ngoài (WSS cổng 443) tới máy chủ CamHub,
để MediaMTX và Device Gateway truy cập camera sau NAT (ADR-10, US-24): kích hoạt bằng mã một lần,
heartbeat 30 giây, giữ chisel client theo cấu hình Core gửi và dò camera ONVIF khi được giao việc.
"""

__version__ = "0.1.0"
