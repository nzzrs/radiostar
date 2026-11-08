FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

COPY server/requirements.txt /tmp/requirements.txt
RUN pip install --index-url https://download.pytorch.org/whl/cpu torch==2.15.0 torchaudio==2.11.0 torchvision==0.29.2 \
    && pip install -r /tmp/requirements.txt

COPY server /app/server

EXPOSE 8787

CMD ["python", "server/app.py"]
