import os
import stat
from pathlib import Path

import pytest

from site_connector import state

CREDS = state.Credentials(
    connector_id="c1",
    secret="s3cret",
    tunnel_url="https://camhub.example.vn/tunnel/",
    tunnel_user="tu",
    tunnel_password="tp",
    activation_code_sha256=state.code_hash("ABCD-1234"),
)


def test_missing_file_is_empty_state(tmp_path: Path) -> None:
    assert state.load(tmp_path / "credentials.json") == state.StoredState(credentials=None)


def test_round_trip_and_private_permissions(tmp_path: Path) -> None:
    path = tmp_path / "sub" / "credentials.json"
    state.save_credentials(path, CREDS)
    assert state.load(path).credentials == CREDS
    if os.name == "posix":
        assert stat.S_IMODE(path.stat().st_mode) == 0o600


def test_revoked_keeps_only_code_hash(tmp_path: Path) -> None:
    path = tmp_path / "credentials.json"
    state.save_credentials(path, CREDS)
    state.mark_revoked(path, CREDS)
    loaded = state.load(path)
    assert loaded.credentials is None
    assert loaded.revoked_code_sha256 == state.code_hash(" ABCD-1234 ")
    assert "s3cret" not in path.read_text()


def test_corrupt_file_raises(tmp_path: Path) -> None:
    path = tmp_path / "credentials.json"
    path.write_text("{not json")
    with pytest.raises(ValueError, match="Không đọc được"):
        state.load(path)
