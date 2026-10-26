"""Ghi ma trận tương thích thiết bị × năng lực ra Markdown (docs/compat/report.md)."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

PASSED = "ĐẠT"
FAILED = "LỖI"
UNSUPPORTED = "KHÔNG HỖ TRỢ"
SKIPPED = "BỎ QUA"

# Tên hàm test → cột trong bảng (theo thứ tự hiển thị)
CAPABILITIES: dict[str, str] = {
    "test_info": "Thông tin",
    "test_stream_profiles": "Profile + RTSP",
    "test_snapshot": "Ảnh chụp",
    "test_ptz": "PTZ",
    "test_presets": "Preset",
    "test_imaging": "Hình ảnh",
    "test_encoder": "Bộ mã hóa",
    "test_network": "Mạng/NTP",
    "test_events": "Sự kiện chuyển động",
}


@dataclass
class DeviceRow:
    name: str
    kind: str
    model: str = ""
    firmware: str = ""
    note: str = ""
    results: dict[str, str] = field(default_factory=dict)
    details: dict[str, str] = field(default_factory=dict)


def classify(when: str, outcome: str, reason: str) -> str:
    if outcome == "passed":
        return PASSED
    if outcome == "failed":
        return FAILED
    return UNSUPPORTED if reason.startswith(UNSUPPORTED) else SKIPPED


def render(rows: list[DeviceRow], generated: datetime | None = None) -> str:
    moment = (generated or datetime.now(UTC)).strftime("%Y-%m-%d %H:%M UTC")
    headers = ["Thiết bị", "Loại", "Model / firmware", *CAPABILITIES.values()]
    lines = [
        "# Ma trận tương thích thiết bị (TS-05.4)",
        "",
        f"Sinh tự động bởi `device-gateway/tests/compat` lúc {moment}. Không sửa tay.",
        "",
        f"Ký hiệu: **{PASSED}**, **{FAILED}**, **{UNSUPPORTED}** (thiết bị không khai báo năng lực), "
        f"**{SKIPPED}** (không kết nối được, thiếu tài khoản, hoặc phép thử chỉ chạy với bộ giả lập).",
        "",
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    for row in rows:
        model = " / ".join(x for x in (row.model, row.firmware) if x) or "—"
        cells = [row.results.get(cap, SKIPPED) for cap in CAPABILITIES]
        lines.append("| " + " | ".join([row.name, row.kind, model, *cells]) + " |")
    notes = [(row.name, row.note) for row in rows if row.note]
    errors = [(row.name, CAPABILITIES[cap], msg) for row in rows for cap, msg in row.details.items()]
    if notes or errors:
        lines += ["", "## Ghi chú", ""]
        lines += [f"- **{name}**: {note}" for name, note in notes]
        lines += [f"- **{name}** – {cap}: {msg}" for name, cap, msg in errors]
    return "\n".join(lines) + "\n"


def write_report(rows: list[DeviceRow], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render(rows), encoding="utf-8", newline="\n")
