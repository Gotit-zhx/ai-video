"""Local U2Net foreground evidence for cloth color only, never production geometry."""
import base64, hashlib, io, os
from pathlib import Path
import numpy as np
from PIL import Image
MODEL_SHA = '309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8'
VERSION = 'u2netp-cloth-evidence-v2'
SESSION = None

def segment(body):
    global SESSION
    raw = base64.b64decode(body['data'], validate=True)
    if not raw or len(raw) > 9_000_000: raise ValueError('Image exceeds limit')
    with Image.open(io.BytesIO(raw)) as source:
        if source.width * source.height > 16_000_000: raise ValueError('Image exceeds pixel limit')
        image = source.convert('RGB')
    if SESSION is None:
        import onnxruntime as ort
        model = Path(os.environ.get('GARMENT_FOREGROUND_MODEL', '/models/u2netp.onnx'))
        if hashlib.sha256(model.read_bytes()).hexdigest() != MODEL_SHA: raise ValueError('Model checksum mismatch')
        options = ort.SessionOptions(); options.intra_op_num_threads = 2; options.inter_op_num_threads = 1
        SESSION = ort.InferenceSession(str(model), sess_options=options, providers=['CPUExecutionProvider'])
    a = np.asarray(image.resize((320,320), Image.Resampling.LANCZOS), dtype=np.float32)
    a /= max(float(a.max()),1)
    a = (a-np.array([.485,.456,.406],dtype=np.float32))/np.array([.229,.224,.225],dtype=np.float32)
    pred = SESSION.run(None,{SESSION.get_inputs()[0].name:a.transpose(2,0,1)[None]})[0][0,0]
    span = float(pred.max()-pred.min())
    if not np.isfinite(pred).all() or span < 1e-6: raise ValueError('No reliable foreground')
    pred = (pred-pred.min())/span
    mask = np.asarray(Image.fromarray((pred*255).astype('uint8')).resize(image.size,Image.Resampling.BILINEAR)) > 210
    mask = recover_cloth_region(image, mask)
    # Preserve every original RGB pixel. Only alpha changes; Java independently checks
    # single-component area, dominant cloth, regional consistency and SKU label.
    image = image.convert('RGBA'); image.putalpha(Image.fromarray(mask.astype('uint8')*255))
    out=io.BytesIO(); image.save(out,format='PNG')
    return {'data':base64.b64encode(out.getvalue()).decode(), 'sourceSha256':hashlib.sha256(raw).hexdigest(),
            'algorithm':VERSION, 'modelSha256':MODEL_SHA, 'geometryEvidence':False}

def recover_cloth_region(image, mask):
    """Color evidence only: recover broad fabric when saliency selected a small graphic.

    Never recolors pixels or chooses an RGB from the SKU label. Border-connected
    backgrounds and small decorative regions are not eligible fallback evidence.
    """
    import cv2
    if float(mask.mean()) >= .28:
        return mask
    rgb = np.asarray(image.convert('RGB'))
    small = cv2.resize(rgb, (256, 256), interpolation=cv2.INTER_AREA)
    lab = cv2.cvtColor(small, cv2.COLOR_RGB2LAB)
    # Fixed seed makes archived evidence repeatable.
    cv2.setRNGSeed(739)
    _, labels, centers = cv2.kmeans(lab.astype(np.float32).reshape(-1, 3), 8, None,
        (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 40, .2), 1, cv2.KMEANS_PP_CENTERS)
    labels = labels.reshape(256, 256)
    best = None
    for center in centers:
        # Include fabric shadows, while excluding unrelated ink by chroma.
        distance = np.linalg.norm(centers[:, 1:] - center[1:], axis=1)
        eligible = (distance < 13) & (np.abs(centers[:, 0] - center[0]) < 48)
        candidate = eligible[labels].astype(np.uint8)
        count, components, stats, _ = cv2.connectedComponentsWithStats(candidate, 8)
        for component in range(1, count):
            x, y, w, h, area = stats[component]
            region = components == component
            share = area / 65536
            border = np.concatenate((region[0], region[-1], region[:, 0], region[:, -1])).mean()
            if share < .32 or share > .85 or w < 140 or h < 150 or border > .08:
                continue
            # Cloth must surround the small salient graphic, not be a disconnected wall.
            salient = cv2.resize(mask.astype(np.uint8), (256, 256), interpolation=cv2.INTER_NEAREST) > 0
            expanded = cv2.dilate(region.astype(np.uint8), np.ones((25,25), np.uint8)) > 0
            contact = float((expanded & salient).sum()) / max(1, int(salient.sum()))
            if contact < .12:
                continue
            if best is None or area > best[0]: best = (area, region)
    if best is None:
        return mask
    return cv2.resize(best[1].astype(np.uint8), image.size, interpolation=cv2.INTER_NEAREST) > 0
