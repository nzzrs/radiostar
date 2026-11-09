FROM python:3.12-slim

ARG UID=1000
ARG GID=1000

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    RADIOSTAR_AUTH_MODE=required \
    RADIOSTAR_HOST=0.0.0.0 \
    RADIOSTAR_DB_PATH=/app/server/data/auth.sqlite \
    RADIOSTAR_CACHE_PATH=/app/server/data/text_cache.sqlite

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

COPY server/requirements.txt /tmp/requirements.txt
RUN pip install --index-url https://download.pytorch.org/whl/cpu torch==2.15.0 torchaudio==2.11.0 torchvision==0.29.2 \
    && pip install -r /tmp/requirements.txt

RUN groupadd -g "${GID}" radiostar 2>/dev/null || true \
    && useradd -m -u "${UID}" -g "${GID}" radiostar \
    && mkdir -p /app/server/data \
    && chown -R radiostar:radiostar /app /usr/local/lib/python3.12/site-packages/laion_clap

COPY --chown=radiostar:radiostar server /app/server

USER radiostar

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8787/health', timeout=3)"

CMD ["python", "-m", "server"]
