"""Offline candidate detection only. Never edits an input image or calls a remote model."""
import base64
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from PIL import Image, ImageOps
ENGINE = os.environ.get('WATERMARK_DETECTOR_ENGINE', 'local-overlay')
TOKEN = os.environ.get('WATERMARK_DETECTOR_TOKEN', '')
if len(TOKEN) < 24:
    raise RuntimeError('A detector internal token of at least 24 characters is required')
if ENGINE == 'local-overlay':
    from local_overlay import detect_overlay, MODEL_SHA, VERSION
    CLASSES = {0: 'possible_overlay'}
    CONFIRMED_ABSENCE = {e['sha256'] for e in json.loads(Path(__file__).with_name('references').joinpath('confirmed-absence.json').read_text())['entries']}
elif ENGINE == 'yolo':
    from ultralytics import YOLO
    import torch
    MODEL_PATH = os.environ.get('WATERMARK_MODEL_PATH', '/models/yolo11x-train28-best.pt')
    MODEL_SHA = hashlib.sha256(open(MODEL_PATH, 'rb').read()).hexdigest()
    torch.set_num_threads(int(os.environ.get('WATERMARK_CPU_THREADS', '4')))
    MODEL = YOLO(MODEL_PATH)
    VERSION = 'joycaption-watermark-yolo11x'
    CLASSES = MODEL.names
    CONFIRMED_ABSENCE = set()
else:
    raise RuntimeError('Unknown detector engine')
LOCK = threading.Lock()
Image.MAX_IMAGE_PIXELS = 40_000_000

def detect(body):
    started = time.monotonic()
    raw = base64.b64decode(body['data'], validate=True)
    if len(raw) > 9_000_000:
        raise ValueError('Image exceeds 9 MB')
    source_sha = hashlib.sha256(raw).hexdigest()
    confirmed_absent = source_sha in CONFIRMED_ABSENCE
    with Image.open(io.BytesIO(raw)) as image:
        image = ImageOps.exif_transpose(image).convert('RGB')
        width, height = image.size
        with LOCK:
            if confirmed_absent:
                detections = []
            elif ENGINE == 'local-overlay':
                detections = detect_overlay(image)
            else:
                result = MODEL.predict(image, imgsz=1024, conf=0.15, iou=0.5,
                                       augment=True, device='cpu', verbose=False)[0]
                detections = []
                for box in result.boxes:
                    x1, y1, x2, y2 = box.xyxy[0].tolist()
                    detections.append({'x':max(0,x1/width), 'y':max(0,y1/height),
                        'width':min(width,x2)/width-max(0,x1/width),
                        'height':min(height,y2)/height-max(0,y1/height),
                        'confidence':float(box.conf[0]), 'label':MODEL.names[int(box.cls[0])]})
    return {'status':'SUSPECTED' if detections else 'NO_CANDIDATE',
            'detections':detections, 'sourceSha256':source_sha,
            'modelSha256':MODEL_SHA, 'model':VERSION, 'requiresReview':not confirmed_absent, 'canAutoPass':confirmed_absent,
            'confirmedAbsentSourceSha256':source_sha if confirmed_absent else None,
            'absenceBasis':'USER_CONFIRMED_EXACT_IMAGE' if confirmed_absent else None,
            'regionsAreRemovalMasks':False, 'scoreType':'template_correlation' if ENGINE == 'local-overlay' else 'model',
            'threshold':0.68 if ENGINE == 'local-overlay' else 0.15, 'width':width, 'height':height,
            'latencyMs':round((time.monotonic()-started)*1000)}

class Handler(BaseHTTPRequestHandler):
    def respond(self, code, body):
        data=json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path != '/health':
            return self.respond(404, {'error':'not found'})
        self.respond(200, {'status':'UP','modelSha256':MODEL_SHA,'classes':CLASSES, 'engine':ENGINE})

    def do_POST(self):
        if self.path not in ['/detect','/compose','/garment-foreground','/drape-prepare','/drape-render']:
            return self.respond(404, {'error':'not found'})
        if not hmac.compare_digest(self.headers.get('X-Internal-Token',''),TOKEN):
            return self.respond(401, {'error':'unauthorized'})
        try:
            length=int(self.headers.get('Content-Length','0'))
            limit = 60_000_000 if self.path in ['/drape-prepare','/drape-render'] else (25_000_000 if self.path=='/compose' else 12_500_000)
            if not 0 < length <= limit:
                return self.respond(413, {'error':'request too large'})
            body=json.loads(self.rfile.read(length))
            if self.path in ['/drape-prepare','/drape-render']:
                from drape_reuse import prepare, render
                with LOCK: result=(prepare if self.path=='/drape-prepare' else render)(body)
                self.respond(200,result)
            elif self.path=='/garment-foreground':
                from garment_foreground import segment
                with LOCK: result=segment(body)
                self.respond(200,result)
            elif self.path=='/compose':
                from local_composite import composite
                with LOCK: result=composite(body)
                self.respond(200,result)
            else:
                self.respond(200, detect(body))
        except Exception as error:
            if self.path in ['/drape-prepare','/drape-render']:
                is_drape_error=type(error).__module__=='drape_reuse' and type(error).__name__=='DrapeError'
                return self.respond(422, {'error':'local drape processing stopped',
                    'code':error.code if is_drape_error else 'LOCAL_DRAPE_FAILED',
                    'message':str(error) if is_drape_error else '本地场景处理未完成，已保留素材，未调用生图模型',
                    'retryable':False,'modelCalls':0})
            self.respond(422, {'error':'image detection failed','type':type(error).__name__})

    def log_message(self, *_):
        pass

if __name__ == '__main__':
    ThreadingHTTPServer(('0.0.0.0',8091),Handler).serve_forever()
