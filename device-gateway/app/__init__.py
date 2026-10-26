"""CamHub Device Gateway.

Module con (SADD 3.3.2): discovery, capabilities, control, events, edge_playback.
Không truy cập PostgreSQL; nhận lệnh qua REST từ Core và đẩy sự kiện vào RabbitMQ.
"""

__version__ = "0.1.0"
