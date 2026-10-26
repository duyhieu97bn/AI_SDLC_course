import json
import logging

from fastapi.testclient import TestClient

from app.logging_config import JsonFormatter
from app.main import app

client = TestClient(app)


def test_health_is_up() -> None:
    res = client.get("/health")
    assert res.status_code == 200
    assert res.json()["status"] == "UP"


def test_metrics_exposed_for_prometheus() -> None:
    res = client.get("/metrics")
    assert res.status_code == 200
    assert "python_info" in res.text


def test_log_line_is_json_with_correlation_id() -> None:
    record = logging.LogRecord("x", logging.INFO, __file__, 1, "Đã kết nối %s", ("cam-1",), None)
    record.correlation_id = "abc-12345"
    line = json.loads(JsonFormatter("gw").format(record))
    assert line["message"] == "Đã kết nối cam-1"
    assert line["correlationId"] == "abc-12345"
    assert line["app"] == "gw"
