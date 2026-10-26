import pytest
from fastapi.testclient import TestClient

from media_worker import main


def test_health_down_without_ffmpeg(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(main, "ffmpeg_version", lambda: None)
    res = TestClient(main.app).get("/health")
    assert res.status_code == 503
    assert res.json()["status"] == "DOWN"


def test_health_up_with_ffmpeg(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(main, "ffmpeg_version", lambda: "ffmpeg version 7.1")
    res = TestClient(main.app).get("/health")
    assert res.status_code == 200
    assert res.json()["ffmpeg"] == "ffmpeg version 7.1"
