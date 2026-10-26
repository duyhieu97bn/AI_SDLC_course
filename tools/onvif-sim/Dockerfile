FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
COPY pyproject.toml ./
COPY onvif_sim ./onvif_sim
RUN pip install --no-cache-dir . \
    && useradd --system --uid 10001 camhub
USER camhub
# 8080: dịch vụ ONVIF (SOAP), ảnh chụp, /sim/*; 3702/udp: WS-Discovery
EXPOSE 8080 3702/udp
HEALTHCHECK --interval=15s --timeout=3s --retries=3 \
    CMD python -c "import os,urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:%s/health' % os.environ.get('SIM_HTTP_PORT','8080')).status==200 else 1)"
CMD ["python", "-m", "onvif_sim"]
