import asyncio
import json
from typing import Any

from media_worker.consumer import ExportProcessor, Outcome, SnapshotProcessor, to_message
from media_worker.export_job import ExportError, ExportRequest, ExportResult
from media_worker.snapshot_job import SnapshotError, SnapshotRequest, SnapshotResult

EXPORT_ID = "7f1c3a52-5b8e-4c1e-9a43-2f6f0a7d9b11"
ORG_ID = "0b6c1e2d-3f4a-4b5c-8d6e-7f8091a2b3c4"


def body(**overrides: Any) -> bytes:
    data: dict[str, Any] = {
        "exportId": EXPORT_ID,
        "organizationId": ORG_ID,
        "cameraId": "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f",
        "mediaPath": "cam-" + "0" * 32,
        "start": "2026-10-24T06:58:00Z",
        "durationSeconds": 30,
        "bucket": "camhub-exports",
        "objectKey": f"{ORG_ID}/{EXPORT_ID}.mp4",
    }
    data.update(overrides)
    return json.dumps(data).encode()


class Recorder:
    def __init__(self) -> None:
        self.messages: list[tuple[str, dict[str, Any]]] = []

    async def __call__(self, routing_key: str, payload: dict[str, Any]) -> None:
        self.messages.append((routing_key, payload))


async def ok_handler(req: ExportRequest) -> ExportResult:
    return ExportResult(req.bucket, req.object_key, 1234, 30.0, "ab" * 32)


def test_completed_flow() -> None:
    pub = Recorder()
    outcome = asyncio.run(ExportProcessor(ok_handler).process(body(), "m1", pub))
    assert outcome is Outcome.ACK
    assert [k for k, _ in pub.messages] == ["export.progress", "export.completed"]
    assert pub.messages[0][1] == {"exportId": EXPORT_ID, "organizationId": ORG_ID, "state": "PROCESSING"}
    assert pub.messages[1][1] == {
        "exportId": EXPORT_ID,
        "organizationId": ORG_ID,
        "objectKey": f"{ORG_ID}/{EXPORT_ID}.mp4",
        "bucket": "camhub-exports",
        "sizeBytes": 1234,
        "durationSeconds": 30.0,
        "sha256": "ab" * 32,
    }


def test_export_error_publishes_failed() -> None:
    async def handler(req: ExportRequest) -> ExportResult:
        raise ExportError("NO_RECORDING", "Không có bản ghi")

    pub = Recorder()
    assert asyncio.run(ExportProcessor(handler).process(body(), "m1", pub)) is Outcome.ACK
    assert pub.messages[-1] == (
        "export.failed",
        {"exportId": EXPORT_ID, "organizationId": ORG_ID, "error": "Không có bản ghi", "code": "NO_RECORDING"},
    )


def test_unexpected_error_is_internal() -> None:
    async def handler(req: ExportRequest) -> ExportResult:
        raise RuntimeError("boom")

    pub = Recorder()
    asyncio.run(ExportProcessor(handler).process(body(), "m1", pub))
    assert pub.messages[-1][0] == "export.failed"
    assert pub.messages[-1][1]["code"] == "INTERNAL"


def test_duplicate_message_is_skipped() -> None:
    calls = 0

    async def handler(req: ExportRequest) -> ExportResult:
        nonlocal calls
        calls += 1
        return await ok_handler(req)

    proc = ExportProcessor(handler)
    pub = Recorder()

    async def run() -> None:
        assert await proc.process(body(), "m1", pub) is Outcome.ACK
        assert await proc.process(body(), "m1", pub) is Outcome.ACK

    asyncio.run(run())
    assert calls == 1
    assert len(pub.messages) == 2


def test_invalid_payload_with_ids_reports_failed() -> None:
    pub = Recorder()
    outcome = asyncio.run(ExportProcessor(ok_handler).process(body(durationSeconds=7200), "m1", pub))
    assert outcome is Outcome.ACK
    assert pub.messages == [
        (
            "export.failed",
            {
                "exportId": EXPORT_ID,
                "organizationId": ORG_ID,
                "error": "Yêu cầu xuất clip không hợp lệ",
                "code": "INTERNAL",
            },
        )
    ]


def test_garbage_payload_is_rejected() -> None:
    pub = Recorder()
    assert asyncio.run(ExportProcessor(ok_handler).process(b"{oops", None, pub)) is Outcome.REJECT
    assert pub.messages == []


def test_message_properties() -> None:
    msg = to_message("export.completed", {"exportId": EXPORT_ID})
    assert msg.content_type == "application/json"
    assert msg.delivery_mode is not None and msg.delivery_mode.value == 2
    assert msg.message_id and len(msg.message_id) == 36
    assert json.loads(msg.body) == {"exportId": EXPORT_ID}


# ---------------------------------------------------------------- Chụp ảnh sự kiện (US-36.3)

SNAPSHOT_ID = "5a1d2c3b-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
CAMERA_ID = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f"


def snap_body(**overrides: Any) -> bytes:
    data: dict[str, Any] = {
        "snapshotId": SNAPSHOT_ID,
        "organizationId": ORG_ID,
        "cameraId": CAMERA_ID,
        "mediaPath": "cam-" + "0" * 32,
        "at": "2026-10-25T03:15:00Z",
        "bucket": "camhub-snapshots",
        "objectKey": f"{ORG_ID}/{CAMERA_ID}/{SNAPSHOT_ID}.jpg",
    }
    data.update(overrides)
    return json.dumps(data).encode()


async def snap_ok(req: SnapshotRequest) -> SnapshotResult:
    return SnapshotResult(req.bucket, req.object_key, 2048)


def test_snapshot_created_flow() -> None:
    pub = Recorder()
    assert asyncio.run(SnapshotProcessor(snap_ok).process(snap_body(), "s1", pub)) is Outcome.ACK
    assert pub.messages == [
        (
            "snapshot.created",
            {
                "snapshotId": SNAPSHOT_ID,
                "organizationId": ORG_ID,
                "cameraId": CAMERA_ID,
                "bucket": "camhub-snapshots",
                "objectKey": f"{ORG_ID}/{CAMERA_ID}/{SNAPSHOT_ID}.jpg",
                "sizeBytes": 2048,
            },
        )
    ]


def test_snapshot_error_publishes_failed() -> None:
    async def handler(req: SnapshotRequest) -> SnapshotResult:
        raise SnapshotError("STREAM_UNAVAILABLE", "Camera không phản hồi")

    pub = Recorder()
    assert asyncio.run(SnapshotProcessor(handler).process(snap_body(), "s1", pub)) is Outcome.ACK
    assert pub.messages == [
        (
            "snapshot.failed",
            {
                "snapshotId": SNAPSHOT_ID,
                "organizationId": ORG_ID,
                "cameraId": CAMERA_ID,
                "code": "STREAM_UNAVAILABLE",
                "error": "Camera không phản hồi",
            },
        )
    ]


def test_snapshot_unexpected_error_is_internal() -> None:
    async def handler(req: SnapshotRequest) -> SnapshotResult:
        raise RuntimeError("boom")

    pub = Recorder()
    asyncio.run(SnapshotProcessor(handler).process(snap_body(), "s1", pub))
    assert pub.messages[-1][0] == "snapshot.failed"
    assert pub.messages[-1][1]["code"] == "INTERNAL"


def test_snapshot_duplicate_is_skipped() -> None:
    calls = 0

    async def handler(req: SnapshotRequest) -> SnapshotResult:
        nonlocal calls
        calls += 1
        return await snap_ok(req)

    proc = SnapshotProcessor(handler)
    pub = Recorder()

    async def run() -> None:
        assert await proc.process(snap_body(), None, pub) is Outcome.ACK
        assert await proc.process(snap_body(), None, pub) is Outcome.ACK  # cùng snapshotId

    asyncio.run(run())
    assert calls == 1
    assert len(pub.messages) == 1


def test_snapshot_invalid_payload() -> None:
    pub = Recorder()
    proc = SnapshotProcessor(snap_ok)
    assert asyncio.run(proc.process(snap_body(mediaPath="x"), "s1", pub)) is Outcome.ACK
    assert pub.messages[0][0] == "snapshot.failed"
    assert pub.messages[0][1]["code"] == "INTERNAL"
    assert pub.messages[0][1]["cameraId"] == CAMERA_ID
    assert asyncio.run(proc.process(b"{oops", "s2", pub)) is Outcome.REJECT
    assert len(pub.messages) == 1


def test_snapshot_message_type() -> None:
    assert to_message("snapshot.created", {}).type == "SnapshotCreated"
    assert to_message("snapshot.failed", {}).type == "SnapshotFailed"
