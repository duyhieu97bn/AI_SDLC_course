"""Middleware X-Internal-Token."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.events.publisher import MemorySink
from app.main import create_app
from app.settings import Settings


def _client(token: str) -> TestClient:
    return TestClient(create_app(Settings(internal_token=token), sink=MemorySink()))


def test_missing_and_wrong_token_are_rejected_before_body_validation() -> None:
    with _client("dung-token") as client:
        for headers in ({}, {"X-Internal-Token": "sai-token"}, {"X-Internal-Token": ""}):
            res = client.post("/v1/devices/info", json={"bad": "body"}, headers=headers)
            assert res.status_code == 401
            assert res.json()["code"] == "GATEWAY_UNAUTHORIZED"


def test_correct_token_passes() -> None:
    with _client("dung-token") as client:
        res = client.get("/v1/events/subscriptions", headers={"X-Internal-Token": "dung-token"})
        assert res.status_code == 200
        assert res.json() == []


def test_health_and_metrics_are_not_protected() -> None:
    with _client("dung-token") as client:
        assert client.get("/health").status_code == 200
        metrics = client.get("/metrics").text
        assert "camhub_gateway_event_subscriptions_active" in metrics


def test_empty_token_disables_check() -> None:
    with _client("") as client:
        assert client.get("/v1/events/subscriptions").status_code == 200
