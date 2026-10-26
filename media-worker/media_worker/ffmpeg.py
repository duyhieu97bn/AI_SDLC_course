import shutil
import subprocess  # noqa: S404 - chỉ gọi ffmpeg với tham số do mã tạo, không qua shell


def ffmpeg_version(binary: str = "ffmpeg") -> str | None:
    """Trả về dòng phiên bản FFmpeg, hoặc ``None`` nếu không có FFmpeg trên máy."""
    path = shutil.which(binary)
    if path is None:
        return None
    out = subprocess.run([path, "-hide_banner", "-version"], capture_output=True, text=True, timeout=10, check=False)  # noqa: S603
    first = out.stdout.splitlines()[0] if out.stdout else ""
    return first or None
