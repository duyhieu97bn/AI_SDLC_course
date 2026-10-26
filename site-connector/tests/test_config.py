from pathlib import Path

import pytest

from site_connector.__main__ import main
from site_connector.config import ConnectorConfig


def test_rejects_non_tls_server(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CAMHUB_SERVER", "http://camhub.local")
    assert main(["--check"]) == 2


def test_accepts_https_server(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.setenv("CAMHUB_SERVER", "https://camhub.example.vn/")
    assert main(["--check"]) == 0
    assert "https://camhub.example.vn" in capsys.readouterr().out


def test_reads_config_file(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    env = tmp_path / "connector.env"
    env.write_text("CAMHUB_SERVER=https://cam.example.vn\nCAMHUB_DISCOVERY_TARGETS=10.0.0.5, 10.0.1.0/30\n")
    assert main(["--config", str(env), "--check"]) == 0
    assert "https://cam.example.vn" in capsys.readouterr().out
    config = ConnectorConfig(_env_file=str(env))  # type: ignore[call-arg]
    assert config.discovery_target_list() == ["10.0.0.5", "10.0.1.0/30"]


def test_healthcheck_without_heartbeat_fails(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CAMHUB_SERVER", "https://camhub.example.vn")
    monkeypatch.setenv("CAMHUB_STATE_DIR", str(tmp_path))
    assert main(["--healthcheck"]) == 1
