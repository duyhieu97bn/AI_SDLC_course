"""Tham số hóa bộ kiểm thử tương thích theo danh sách thiết bị và ghi báo cáo ma trận."""

from __future__ import annotations

import os
import socket
from collections.abc import Generator
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pytest
import yaml

from tests.compat.report import CAPABILITIES, DeviceRow, classify, write_report
from tests.conftest import REPO_ROOT, RunningSim

HERE = Path(__file__).parent
SIMULATORS = [
    {"id": "sim-ptz", "name": "ONVIF sim (PTZ, B-frame, lệch giờ)", "fixture": "sim"},
    {"id": "sim-fixed", "name": "ONVIF sim (cố định, MotionAlarm)", "fixture": "sim_fixed"},
]


def camera_file() -> Path:
    override = os.environ.get("CAMHUB_COMPAT_CAMERAS")
    if override:
        return Path(override)
    local = HERE / "cameras.yaml"
    return local if local.exists() else HERE / "cameras.example.yaml"


def load_cameras() -> list[dict[str, Any]]:
    path = camera_file()
    if not path.exists():
        return []
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    cameras = []
    for index, cam in enumerate(data.get("cameras") or []):
        cam = dict(cam)
        cam["id"] = f"cam{index + 1}-" + "".join(c if c.isalnum() else "-" for c in str(cam["name"])).strip("-")
        cameras.append(cam)
    return cameras


@dataclass
class DeviceCase:
    id: str
    name: str
    host: str
    port: int
    username: str
    password: str
    sim: RunningSim | None = None
    allow_write: bool = False
    cache: dict[str, Any] = field(default_factory=dict)

    @property
    def is_sim(self) -> bool:
        return self.sim is not None

    @property
    def camera_id(self) -> str:
        return f"compat-{self.id}"

    def device(self) -> dict[str, Any]:
        return {"host": self.host, "port": self.port, "username": self.username, "password": self.password}


ROWS: dict[str, DeviceRow] = {}
CASES: dict[str, DeviceCase] = {}


def _row(spec: dict[str, Any]) -> DeviceRow:
    if spec["id"] not in ROWS:
        ROWS[spec["id"]] = DeviceRow(name=spec["name"], kind="Giả lập" if "fixture" in spec else "Camera thật")
    return ROWS[spec["id"]]


def pytest_generate_tests(metafunc: pytest.Metafunc) -> None:
    if "device_case" in metafunc.fixturenames:
        specs = SIMULATORS + load_cameras()
        metafunc.parametrize("device_case", specs, ids=[s["id"] for s in specs], indirect=True)


def _reachable(host: str, port: int, timeout: float = 2.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


@pytest.fixture
def device_case(request: pytest.FixtureRequest) -> DeviceCase:
    spec: dict[str, Any] = request.param
    row = _row(spec)
    if spec["id"] in CASES:
        return CASES[spec["id"]]
    if "fixture" in spec:
        sim: RunningSim = request.getfixturevalue(spec["fixture"])
        case = DeviceCase(spec["id"], spec["name"], "127.0.0.1", sim.port, sim.config.user, sim.config.password, sim)
        case.allow_write = True
    else:
        username = os.environ.get(str(spec.get("username_env", "")), "")
        password = os.environ.get(str(spec.get("password_env", "")), "")
        host, port = str(spec["host"]), int(spec.get("port", 80))
        if not username:
            row.note = f"thiếu biến môi trường {spec.get('username_env')}/{spec.get('password_env')}"
            pytest.skip(f"{row.note}")
        if not _reachable(host, port):
            row.note = f"không kết nối được {host}:{port}"
            pytest.skip(row.note)
        case = DeviceCase(spec["id"], spec["name"], host, port, username, password)
        case.allow_write = bool(spec.get("allow_write", False))
    CASES[spec["id"]] = case
    return case


@pytest.hookimpl(wrapper=True)
def pytest_runtest_makereport(item: pytest.Item, call: pytest.CallInfo[None]) -> Generator[None, Any, Any]:
    report = yield
    callspec = getattr(item, "callspec", None)
    name = getattr(item, "originalname", item.name)
    if callspec is None or name not in CAPABILITIES or "device_case" not in callspec.params:
        return report
    if report.when == "call" or (report.when == "setup" and report.outcome != "passed"):
        row = _row(callspec.params["device_case"])
        reason = ""
        if report.skipped and isinstance(report.longrepr, tuple):
            reason = str(report.longrepr[2]).removeprefix("Skipped: ")
        row.results[name] = classify(report.when, report.outcome, reason)
        if report.failed:
            message = str(getattr(call.excinfo, "value", "")) if call.excinfo else ""
            row.details[name] = (message.splitlines() or ["lỗi"])[0][:200]
    return report


def pytest_sessionfinish(session: pytest.Session, exitstatus: int) -> None:
    if not ROWS:
        return
    path = Path(os.environ.get("CAMHUB_COMPAT_REPORT", REPO_ROOT / "docs" / "compat" / "report.md"))
    write_report(list(ROWS.values()), path)
