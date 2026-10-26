import os

import uvicorn


def main() -> None:
    uvicorn.run(
        "chat_mock.app:app",
        host=os.environ.get("CHAT_MOCK_HOST", "0.0.0.0"),  # noqa: S104 - chạy trong container
        port=int(os.environ.get("CHAT_MOCK_PORT", "8026")),
        log_level=os.environ.get("CHAT_MOCK_LOG_LEVEL", "info"),
    )


if __name__ == "__main__":
    main()
