"""Chuẩn hóa topic chuyển động ONVIF và các biến thể hãng."""

from __future__ import annotations

import pytest

from app.events.normalize import normalize_motion, normalize_topic
from app.onvif.client import RawEvent


def event(topic: str, data: dict[str, str], utc: str | None = "2026-10-23T01:02:03Z") -> RawEvent:
    return RawEvent(topic=topic, utc_time=utc, property_operation="Changed", source={"Src": "vs0"}, data=data)


@pytest.mark.parametrize(
    ("topic", "data", "state"),
    [
        ("tns1:RuleEngine/CellMotionDetector/Motion", {"IsMotion": "true"}, True),
        ("tns1:RuleEngine/CellMotionDetector/Motion", {"IsMotion": "false"}, False),
        ("tns1:VideoSource/MotionAlarm", {"State": "true"}, True),
        ("tns1:RuleEngine/MotionRegionDetector/Motion", {"State": "1"}, True),
        ("tnsaxis:VideoSource/MotionDetection", {"motion": "1"}, True),
        ("tns1:RuleEngine/VideoMotionDetection", {"active": "0"}, False),
        (" tns1:VideoSource/MotionAlarm ", {"state": "TRUE"}, True),
    ],
)
def test_motion_variants(topic: str, data: dict[str, str], state: bool) -> None:
    result = normalize_motion(event(topic, data))
    assert result is not None
    assert result.state is state
    assert result.occurred_at == "2026-10-23T01:02:03.000Z"


@pytest.mark.parametrize(
    ("topic", "data"),
    [
        ("tns1:VideoSource/GlobalSceneChange/ImagingService", {"State": "true"}),
        ("tns1:Device/Trigger/DigitalInput", {"LogicalState": "true"}),
        ("tns1:RuleEngine/CellMotionDetector/Motion", {"Other": "x"}),
        ("tns1:RuleEngine/CellMotionDetector/Motion", {"IsMotion": "maybe"}),
        ("tns1:VideoSource/MotionDetection/Configuration", {"State": "true"}),
    ],
)
def test_non_motion_or_unreadable_is_ignored(topic: str, data: dict[str, str]) -> None:
    assert normalize_motion(event(topic, data)) is None


def test_topic_prefixes_are_stripped() -> None:
    assert normalize_topic("tns1:RuleEngine/tns1:CellMotionDetector/Motion") == "RuleEngine/CellMotionDetector/Motion"


def test_missing_or_bad_utc_time_falls_back_to_now() -> None:
    result = normalize_motion(event("tns1:VideoSource/MotionAlarm", {"State": "true"}, utc="bad"))
    assert result is not None and result.occurred_at.endswith("Z")


def test_source_key_distinguishes_video_sources() -> None:
    a = normalize_motion(event("tns1:VideoSource/MotionAlarm", {"State": "true"}))
    b = normalize_motion(RawEvent("tns1:VideoSource/MotionAlarm", None, "Changed", {"Src": "vs1"}, {"State": "true"}))
    assert a is not None and b is not None and a.source_key != b.source_key
