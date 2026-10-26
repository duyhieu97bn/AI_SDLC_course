"""CamHub Media Worker.

Nhận công việc từ exchange ``jobs`` (ExportRequested, SnapshotRequested) và trả kết quả về ``job.results``.
Bộ xử lý công việc được thêm từ S5 (US-17 xuất clip); S0 chỉ dựng khung và kiểm tra FFmpeg.
"""

__version__ = "0.1.0"
