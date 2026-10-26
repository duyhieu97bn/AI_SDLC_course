from site_connector.api import Forward, TunnelConfig
from site_connector.tunnel import build_command, build_remotes, parse_state

CONFIG = TunnelConfig(
    config_version="v1",
    socks_port=21001,
    forwards=(Forward(port=22001, host="site-camera", target_port=8554), Forward(22002, "fe80::1", 554)),
)


def test_remotes_listen_on_all_interfaces() -> None:
    # "R:<port>:socks" mặc định nghe 127.0.0.1 trên Tunnel Server -> phải ghi rõ 0.0.0.0
    assert build_remotes(CONFIG) == [
        "R:0.0.0.0:21001:socks",
        "R:0.0.0.0:22001:site-camera:8554",
        "R:0.0.0.0:22002:[fe80::1]:554",
    ]


def test_command_does_not_contain_password() -> None:
    cmd = build_command("chisel", "https://camhub/tunnel/", CONFIG, tls_insecure=True)
    assert cmd[:2] == ["chisel", "client"]
    assert "--tls-skip-verify" in cmd
    assert cmd[-4] == "https://camhub/tunnel/"
    assert not any("--auth" in c for c in cmd)


def test_command_with_ca_and_fingerprint() -> None:
    cmd = build_command("chisel", "https://x/tunnel/", CONFIG, ca_file="/ca.pem", fingerprint="abc=")
    assert cmd[cmd.index("--tls-ca") + 1] == "/ca.pem"
    assert cmd[cmd.index("--fingerprint") + 1] == "abc="
    assert "--tls-skip-verify" not in cmd


def test_parse_state_from_chisel_log() -> None:
    assert parse_state("2026/10/24 04:24:17 client: Connected (Latency 522.029µs)") is True
    assert parse_state("2026/10/24 04:24:23 client: Disconnected") is False
    assert parse_state("client: Connection error: server: access to 'R:0.0.0.0:21001' denied") is False
    assert parse_state("client: Retrying in 2s...") is False
    assert parse_state("client: tun: proxy#R:21001=>socks: Listening") is None
