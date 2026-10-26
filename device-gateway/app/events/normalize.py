"""Chuẩn hóa thông điệp sự kiện ONVIF thành trạng thái chuyển động ``{state, occurredAt}``.

Hỗ trợ các topic phổ biến:

- ``tns1:RuleEngine/CellMotionDetector/Motion`` với ``IsMotion`` (Profile S/T chuẩn)
- ``tns1:VideoSource/MotionAlarm`` với ``State``
- ``tns1:RuleEngine/MotionRegionDetector/Motion`` với ``State``/``IsMotion``
- biến thể hãng có chữ "motion" trong topic (ví dụ ``tnsaxis:VideoSource/MotionDetection`` với ``motion``,
  ``tns1:RuleEngine/VideoMotionDetection`` với ``active``)
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime

from app.onvif.client import RawEvent
from app.onvif.soap import bool_text

# Tên trường dữ liệu mang trạng thái, theo thứ tự ưu tiên (so sánh không phân biệt hoa thường)
_STATE_ITEMS = ("ismotion", "state", "motion", "motionactive", "active", "logicalstate", "alarm", "value")
# Topic có chữ "motion" nhưng không phải trạng thái chuyển động
_EXCLUDED = ("configuration", "tamper")


@dataclass(frozen=True)
class MotionEvent:
    state: bool
    occurred_at: str
    topic: str
    source_key: str


def normalize_topic(topic: str) -> str:
    """Bỏ tiền tố namespace từng đoạn: ``tns1:RuleEngine/CellMotionDetector/Motion`` → ``RuleEngine/...``."""
    return "/".join(seg.strip().rsplit(":", 1)[-1] for seg in topic.strip().split("/") if seg.strip())


def is_motion_topic(topic: str) -> bool:
    lowered = normalize_topic(topic).lower()
    return "motion" in lowered and not any(word in lowered for word in _EXCLUDED)


def format_instant(moment: datetime) -> str:
    return moment.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_instant(raw: str | None) -> datetime | None:
    if not raw:
        return None
    try:
        moment = datetime.fromisoformat(raw.strip())
    except ValueError:
        return None
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


def normalize_motion(event: RawEvent, now: datetime | None = None) -> MotionEvent | None:
    """Trả ``MotionEvent`` nếu là sự kiện chuyển động có trạng thái đọc được, ngược lại ``None``."""
    if not is_motion_topic(event.topic):
        return None
    items = {name.lower(): value for name, value in event.data.items()}
    state = None
    for name in _STATE_ITEMS:
        if name in items:
            state = bool_text(items[name])
            if state is not None:
                break
    if state is None:
        return None
    occurred = parse_instant(event.utc_time) or now or datetime.now(UTC)
    source = ",".join(f"{k}={v}" for k, v in sorted(event.source.items()))
    return MotionEvent(
        state=state,
        occurred_at=format_instant(occurred),
        topic=event.topic.strip(),
        source_key=f"{normalize_topic(event.topic)}|{source}",
    )
