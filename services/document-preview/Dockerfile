ARG PYTHON_BASE=python@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a
FROM ${PYTHON_BASE}

RUN sed -i 's|http://deb.debian.org/debian|https://mirrors.aliyun.com/debian|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends libreoffice-impress python3-uno fonts-noto-cjk fonts-liberation \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 preview \
    && useradd --uid 10001 --gid preview --no-create-home --home-dir /tmp --shell /usr/sbin/nologin preview

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 LANG=C.UTF-8 LC_ALL=C.UTF-8
WORKDIR /app
COPY --chown=10001:10001 server.py convert_worker.py /app/
USER 10001:10001
EXPOSE 8092
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request,json; assert json.load(urllib.request.urlopen('http://127.0.0.1:8092/health', timeout=2)) == {'status':'UP'}"
ENTRYPOINT ["python", "/app/server.py"]
