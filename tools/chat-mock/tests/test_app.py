from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from chat_mock.app import app

TOKEN = "123456:ABC-test"


@pytest.fixture
def client() -> Iterator[TestClient]:
    c = TestClient(app)
    c.delete("/messages")
    yield c


def test_telegram_send_message(client: TestClient) -> None:
    res = client.post(
        f"/bot{TOKEN}/sendMessage",
        json={"chat_id": 4242, "text": "<b>Cảnh báo</b>", "parse_mode": "HTML", "disable_web_page_preview": True},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["ok"] is True
    assert body["result"]["message_id"] >= 1
    assert body["result"]["chat"]["id"] == 4242

    msgs = client.get("/messages", params={"channel": "telegram", "chat": "4242"}).json()
    assert len(msgs) == 1
    assert msgs[0]["text"] == "<b>Cảnh báo</b>"
    assert msgs[0]["parse_mode"] == "HTML"
    assert msgs[0]["token"] == TOKEN
    assert msgs[0]["has_photo"] is False


def test_telegram_send_photo_multipart(client: TestClient) -> None:
    jpeg = b"\xff\xd8\xff" + b"x" * 997
    res = client.post(
        f"/bot{TOKEN}/sendPhoto",
        data={"chat_id": "-1001", "caption": "Phát hiện chuyển động"},
        files={"photo": ("snap.jpg", jpeg, "image/jpeg")},
    )
    assert res.status_code == 200
    assert res.json()["result"]["caption"] == "Phát hiện chuyển động"
    m = client.get("/messages", params={"chat": "-1001"}).json()[0]
    assert m["has_photo"] is True
    assert m["photo_size"] == 1000
    assert m["photo_content_type"] == "image/jpeg"
    assert m["text"] == "Phát hiện chuyển động"


@pytest.mark.parametrize(
    ("token", "chat", "status", "description"),
    [
        (TOKEN, "fail", 400, "Bad Request: chat not found"),
        (TOKEN, "flood", 429, "Too Many Requests: retry after 1"),
        ("123:invalid", "1", 401, "Unauthorized"),
    ],
)
def test_telegram_errors(client: TestClient, token: str, chat: str, status: int, description: str) -> None:
    res = client.post(f"/bot{token}/sendMessage", json={"chat_id": chat, "text": "x"})
    assert res.status_code == status
    assert res.json()["ok"] is False
    assert res.json()["error_code"] == status
    assert res.json()["description"] == description
    assert client.get("/messages").json() == []


def test_telegram_photo_missing(client: TestClient) -> None:
    res = client.post(f"/bot{TOKEN}/sendPhoto", data={"chat_id": "1"})
    assert res.status_code == 400


def test_zalo_send(client: TestClient) -> None:
    res = client.post(
        "/v3.0/oa/message/cs",
        headers={"access_token": "zalo-tok"},
        json={"recipient": {"user_id": "u-1"}, "message": {"text": "Camera mất kết nối"}},
    )
    body = res.json()
    assert body["error"] == 0
    assert body["message"] == "Success"
    assert body["data"]["message_id"]
    m = client.get("/messages", params={"channel": "zalo", "chat": "u-1"}).json()[0]
    assert m["text"] == "Camera mất kết nối"
    assert m["token"] == "zalo-tok"


def test_zalo_errors(client: TestClient) -> None:
    payload = {"recipient": {"user_id": "fail"}, "message": {"text": "x"}}
    res = client.post("/v3.0/oa/message/cs", headers={"access_token": "t"}, json=payload)
    assert res.status_code == 200
    assert res.json() == {"error": -230, "message": "User has not interacted with the OA"}
    assert client.post("/v3.0/oa/message/cs", json=payload).json()["error"] == -216


def test_messages_newest_first_and_clear(client: TestClient) -> None:
    for i in range(3):
        client.post(f"/bot{TOKEN}/sendMessage", json={"chat_id": 1, "text": f"m{i}"})
    assert [m["text"] for m in client.get("/messages").json()] == ["m2", "m1", "m0"]
    assert client.delete("/messages").json() == {"deleted": 3}
    assert client.get("/messages").json() == []
