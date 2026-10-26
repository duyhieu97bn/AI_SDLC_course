FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
COPY pyproject.toml ./
COPY chat_mock ./chat_mock
RUN pip install --no-cache-dir . \
    && useradd --system --uid 10001 camhub
USER camhub
# 8026: Telegram Bot API + Zalo OA API giả lập và /messages để kiểm tra
EXPOSE 8026
HEALTHCHECK --interval=15s --timeout=3s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8026/health').status==200 else 1)"
CMD ["python", "-m", "chat_mock"]
