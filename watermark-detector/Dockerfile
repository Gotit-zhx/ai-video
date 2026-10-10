FROM python:3.11-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
RUN pip install --no-cache-dir onnxruntime==1.22.1 opencv-python-headless==4.12.0.88
COPY server.py local_overlay.py reference_overlay.py local_composite.py ./
COPY garment_foreground.py drape_reuse.py drape_detail_guard.py ./
COPY references ./references
RUN useradd --system --uid 10002 detector && mkdir -p /tmp/yolo && chown detector /tmp/yolo
ENV WATERMARK_DETECTOR_ENGINE=yolo YOLO_CONFIG_DIR=/tmp/yolo MPLCONFIGDIR=/tmp/yolo YOLO_AUTOINSTALL=false
USER detector
EXPOSE 8091
CMD ["python", "server.py"]
