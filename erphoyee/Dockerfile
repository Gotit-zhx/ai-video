FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir --disable-pip-version-check -r requirements.txt && useradd --uid 10001 --create-home erphoyee
COPY server.py connectors.py ./
COPY public ./public
USER 10001:10001
ENV ERP_BIND=0.0.0.0 ERP_DATA_DIR=/data PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PORT=8137
EXPOSE 8137
CMD ["python", "server.py"]
