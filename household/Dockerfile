FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 DATA_DIR=/data
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home homecare && mkdir /data && chown homecare:homecare /data
COPY --chown=homecare:homecare . .
USER homecare
EXPOSE 8787
CMD ["gunicorn", "--bind", "0.0.0.0:8787", "--workers", "1", "--threads", "4", "--timeout", "45", "server:app"]
