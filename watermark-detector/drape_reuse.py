"""Bounded, offline reuse of one AI photograph with one visible print.

The generated photograph is immutable. SIFT locates a print from its exact
submitted guide or verified UV-bound native artwork; no placement is guessed.
"""
import base64
import hashlib
import io
import json
import math
import re
import time
import cv2
import numpy as np
from PIL import Image

ALGORITHM = 'drape-reuse-v1'
MASK_REVISION = 'U2NET_THREE_VIEW_SLEEVE_HEM_V2'
NUMBER_ENCODING = 'DECIMAL_STRING_V1'
COORDINATE_FIELDS = {'sourceCanvasToMasterH': (3, 3), 'textureToSourceCanvasH': (3, 3),
                     'textureToMasterH': (3, 3), 'targetCorners': (4, 2)}
LIMIT = 8 * 1024 * 1024
MAX_SIDE = 1536
TIME_LIMIT = 30
cv2.setNumThreads(2)

class DrapeError(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code

def require(condition, code, message):
    if not condition:
        raise DrapeError(code, message)

def check_time(started):
    require(time.monotonic()-started < TIME_LIMIT, 'LOCAL_TIME_LIMIT', '本地场景处理超时，素材已保留，不会新增生图调用')

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def freeze_mapping_numbers(mapping):
    # JSON database codecs can move a numeric double by 1 ULP during a round
    # trip. Decimal strings preserve the exact 17-digit round-trip value. The
    # matrices are still decoded to doubles before geometry is evaluated.
    result = dict(mapping)
    result['numberEncoding'] = NUMBER_ENCODING
    for field in COORDINATE_FIELDS:
        result[field] = [[format(value, '.17g') for value in row] for row in mapping[field]]
    return result

def validate_mapping_numbers(mapping):
    encoded = 'numberEncoding' in mapping
    require(not encoded or mapping['numberEncoding'] == NUMBER_ENCODING,
            'MAPPING_INVALID', '场景映射数字编码版本不受支持')
    for field, (rows, columns) in COORDINATE_FIELDS.items():
        values = mapping.get(field)
        require(isinstance(values, list) and len(values) == rows
                and all(isinstance(row, list) and len(row) == columns for row in values),
                'MAPPING_INVALID', '场景映射坐标格式无效')
        for row in values:
            for value in row:
                if encoded:
                    require(isinstance(value, str) and len(value) <= 32,
                            'MAPPING_INVALID', '固定映射坐标必须保留精确数字字符串')
                    try:
                        number = float(value)
                    except (ValueError, OverflowError):
                        raise DrapeError('MAPPING_INVALID', '场景映射坐标不是有效数字') from None
                    require(math.isfinite(number) and format(number, '.17g') == value,
                            'MAPPING_INVALID', '场景映射坐标不是规范的有限数字')
                else:
                    # Preserve valid archives made before decimal-string
                    # encoding; changed legacy checksums still fail closed.
                    try:
                        finite = type(value) in (int, float) and math.isfinite(value)
                    except OverflowError:
                        finite = False
                    require(finite,
                            'MAPPING_INVALID', '旧版场景映射坐标无效')

def mapping_checksum(mapping):
    # Integrity check for accidental changes in the authenticated, tenant-bound
    # archive pipeline. This is deliberately not an authorization boundary.
    validate_mapping_numbers(mapping)
    content = {k: v for k, v in mapping.items() if k != 'mappingSha256'}
    try:
        raw = json.dumps(content, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode()
    except (ValueError, TypeError):
        raise DrapeError('MAPPING_INVALID', '场景映射格式无效') from None
    return sha(raw)

def decode(value, name, mode='RGB', formats=('PNG',)):
    require(isinstance(value, str) and 16 <= len(value) <= ((LIMIT+2)//3)*4,
            'IMAGE_SIZE_INVALID', name+'图片编码或大小无效')
    try:
        raw = base64.b64decode(value, validate=True)
        require(12 <= len(raw) <= LIMIT, 'IMAGE_SIZE_INVALID', name+'图片大小无效')
        signature = ('PNG' if raw.startswith(b'\x89PNG\r\n\x1a\n') else
                     'JPEG' if raw.startswith(b'\xff\xd8\xff') else
                     'WEBP' if raw[:4] == b'RIFF' and raw[8:12] == b'WEBP' else None)
        require(signature in formats, 'PNG_REQUIRED' if formats == ('PNG',) else 'IMAGE_FORMAT_UNSUPPORTED',
                name+'须为已归档 '+('/'.join(formats)))
        with Image.open(io.BytesIO(raw)) as image:
            require(image.format == signature and not getattr(image, 'is_animated', False)
                    and 16 <= image.width <= MAX_SIDE and 16 <= image.height <= MAX_SIDE,
                    'IMAGE_DIMENSIONS_INVALID', name+'尺寸须为16至1536像素且非动画')
            pixels = np.asarray(image.convert(mode)).copy()
        return raw, pixels
    except DrapeError:
        raise
    except (ValueError, OSError, TypeError):
        raise DrapeError('IMAGE_DECODE_FAILED', name+'图片无法解码') from None

def png(pixels):
    out = io.BytesIO()
    Image.fromarray(pixels).save(out, format='PNG')
    raw = out.getvalue()
    require(len(raw) <= LIMIT, 'OUTPUT_TOO_LARGE', '本地处理图片超过归档上限')
    return raw

def encoded(raw):
    return base64.b64encode(raw).decode()

def largest(mask):
    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8), 8)
    require(n > 1, 'GARMENT_MASK_UNCERTAIN', '没有可靠的连续白色衣服区域')
    return labels == (1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA])))

def homography(value):
    try:
        if any(isinstance(number, (bool, np.bool_)) for row in value for number in row):
            raise ValueError('Boolean coordinate')
        h = np.asarray(value, np.float64)
        valid = h.shape == (3, 3) and np.isfinite(h).all() and abs(np.linalg.det(h)) > 1e-10
    except (ValueError, TypeError):
        valid = False
    require(valid, 'MAPPING_INVALID', '场景印花映射无效')
    return h

def project(h, points):
    result = cv2.perspectiveTransform(np.asarray(points, np.float32)[None], h)[0]
    require(np.isfinite(result).all(), 'MAPPING_INVALID', '场景印花映射发散')
    return result

UNIT = [[0, 0], [1, 0], [1, 1], [0, 1]]

def validate_quad(corners, shape):
    height, width = shape
    area = float(cv2.contourArea(corners.astype(np.float32), oriented=True))
    require(cv2.isContourConvex(corners.astype(np.float32)) and area > 0
            and (corners[:, 0] >= 3).all() and (corners[:, 0] < width-3).all()
            and (corners[:, 1] >= 3).all() and (corners[:, 1] < height-3).all(),
            'PRINT_GEOMETRY_UNCERTAIN', '印花映射翻转、越界或不连续，未猜测目标位置')
    require(.001 <= area/(width*height) <= .5,
            'PRINT_TOO_LARGE', '印花映射覆盖大半画面，无法可靠确认商品位置')
    edges = np.linalg.norm(corners-np.roll(corners, 1, axis=0), axis=1)
    require(min(edges) >= 18 and max(edges)/min(edges) <= 3,
            'PRINT_GEOMETRY_UNCERTAIN', '印花区域过细或透视变形过大')

def visible_print_slot(uv, ids):
    # UV blue is a 1-based index into the full frozen renderPrintIds list.
    # Hidden back/side positions may precede the one visible front print.
    slots = np.unique(uv[:, :, 2][uv[:, :, 3] > 0])
    require(len(slots) == 1, 'MULTIPLE_PRINTS_UNSUPPORTED', '目前只支持一个可见印花位置')
    slot = int(slots[0])
    require(1 <= slot <= len(ids), 'UV_SLOT_INVALID', '可见印花位置与固定印花列表不一致')
    return slot

def guide_artwork_binding(guide, uv, art):
    """Verify native artwork against the submitted guide at its frozen UV pixels.

    The guide may alias fine texture. Recreate its point samples to prove that
    the high-resolution fallback is the same submitted print, not another art.
    """
    ys, xs = np.where(uv[:, :, 3] > 250)
    step = max(1, (len(xs)+11999)//12000)
    ys, xs = ys[::step], xs[::step]
    x = uv[ys, xs, 0]/255.*(art.shape[1]-1)
    y = (1-uv[ys, xs, 1]/255.)*(art.shape[0]-1)
    xi, yi = x.astype(int), y.astype(int)
    alpha = np.zeros(len(x)); rgb = np.zeros((len(x), 3))
    for dy in (0, 1):
        for dx in (0, 1):
            pixel = art[np.minimum(art.shape[0]-1, yi+dy), np.minimum(art.shape[1]-1, xi+dx)].astype(float)
            weight = ((x-xi) if dx else 1-(x-xi))*((y-yi) if dy else 1-(y-yi))
            a = pixel[:, 3]/255*weight
            alpha += a; rgb += pixel[:, :3]*a[:, None]
    solid = alpha > .95
    require(int(solid.sum()) >= 80, 'GUIDE_ARTWORK_MISMATCH', '高清印花缺少足够实心像素，无法核对定位图绑定')
    expected = rgb[solid]/alpha[solid, None]
    gx = np.clip((xs[solid]+.5)*guide.shape[1]/uv.shape[1]-.5, 0, guide.shape[1]-1)
    gy = np.clip((ys[solid]+.5)*guide.shape[0]/uv.shape[0]-.5, 0, guide.shape[0]-1)
    observed = cv2.remap(guide.astype(np.float32), gx.astype(np.float32)[None], gy.astype(np.float32)[None], cv2.INTER_LINEAR)[0]
    gain = float((observed*expected).sum()/max(1, (expected*expected).sum()))
    error = np.abs(observed-expected*gain)
    correlation = float(np.corrcoef(observed.ravel(), expected.ravel())[0, 1]) if expected.std() > 10 and observed.std() > 10 else 0.
    require(.5 <= gain <= 1.1 and correlation >= .97 and float(error.mean()) <= 12,
            'GUIDE_ARTWORK_MISMATCH', '高清印花与已提交定位图的UV像素不一致，未使用其他图案推测位置')
    return {'solidPixels': int(solid.sum()), 'correlation': correlation,
            'meanAbsoluteError': float(error.mean()), 'lightingGain': gain}

def track_candidates(master, source, source_to_canvas, texture_to_guide, detector, target_k, target_d, target_scale, started):
    matcher = cv2.BFMatcher(cv2.NORM_L2)
    candidates = []
    cv2.setRNGSeed(739)
    for size in (96, 160, 256, 384):
        check_time(started)
        scale = size/max(source.shape[:2])
        ref = cv2.resize(source, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_CUBIC)
        sk, sd = detector.detectAndCompute(cv2.cvtColor(ref, cv2.COLOR_RGB2GRAY), None)
        if sd is None or len(sd) < 12:
            continue
        reverse = matcher.knnMatch(target_d, sd, k=1)
        good = [p[0] for p in matcher.knnMatch(sd, target_d, k=2) if len(p) == 2 and p[0].distance < .72*p[1].distance
                and reverse[p[0].trainIdx][0].trainIdx == p[0].queryIdx]
        if len(good) < 12:
            continue
        src = np.float32([sk[m.queryIdx].pt for m in good]); dst = np.float32([target_k[m.trainIdx].pt for m in good])
        h, keep = cv2.findHomography(src, dst, cv2.RANSAC, 3, maxIters=10000, confidence=.999)
        if h is None:
            continue
        chosen = keep.ravel() > 0; n = int(chosen.sum()); s = src[chosen]; d = dst[chosen]
        if n < 12 or n/len(good) < .60:
            continue
        error = np.linalg.norm(project(h, s)-d, axis=1)
        coverage = cv2.contourArea(cv2.convexHull(s))/(ref.shape[0]*ref.shape[1])
        quadrants = {(int(x >= ref.shape[1]/2), int(y >= ref.shape[0]/2)) for x, y in s}
        if float(np.median(error)) > 2 or coverage < .12 or len(quadrants) < 3:
            continue
        rc = np.float32([[0, 0], [ref.shape[1]-1, 0], [ref.shape[1]-1, ref.shape[0]-1], [0, ref.shape[0]-1]])
        corners = project(h, rc)/target_scale
        try:
            validate_quad(corners, master.shape[:2])
        except DrapeError:
            continue
        deviations = []; rng = np.random.default_rng(739)
        for _ in range(20):
            subset = rng.choice(n, max(4, int(n*.8)), replace=False)
            boot, _ = cv2.findHomography(s[subset], d[subset], 0)
            if boot is not None:
                deviations.extend(np.linalg.norm(project(boot, rc)/target_scale-corners, axis=1).tolist())
        if not deviations or float(np.percentile(deviations, 95)) > 6:
            continue
        # Resize pixel centres exactly, then bind source pixels back to the same
        # frozen guide canvas. Native artwork uses its full texture extent.
        rx, ry = ref.shape[1]/source.shape[1], ref.shape[0]/source.shape[0]
        source_to_ref = np.array([[rx, 0, (rx-1)/2], [0, ry, (ry-1)/2], [0, 0, 1.]])
        canvas_h = np.diag([1/target_scale, 1/target_scale, 1])@h@source_to_ref@np.linalg.inv(source_to_canvas)
        texture_h = canvas_h@texture_to_guide
        actual = project(texture_h, UNIT)
        validate_quad(actual, master.shape[:2])
        candidates.append({'matrix': canvas_h, 'textureMatrix': texture_h, 'corners': actual, 'inliers': n,
                           'matches': len(good), 'medianErrorPx': float(np.median(error))/target_scale,
                           'bootstrapCornerP95Px': float(np.percentile(deviations, 95)), 'sourceScale': size})
    return candidates

def consistent_candidates(candidates):
    if len(candidates) < 3:
        return None, []
    best = max(candidates, key=lambda c: c['inliers'])
    agreement = [c for c in candidates if float(np.linalg.norm(c['corners']-best['corners'], axis=1).max()) <= 4]
    return best, agreement

def track(master, guide, uv, art, started):
    alpha = uv[:, :, 3] > 0
    ys, xs = np.where(alpha)
    require(len(xs) >= 80, 'NO_VISIBLE_PRINT', '没有可追踪的可见印花位置')
    largest_area = int(largest(alpha).sum())
    require(largest_area >= alpha.sum()*.99, 'UV_FRAGMENTED', '印花位置跨越多个分离或遮挡区域')
    sx, sy = guide.shape[1]/uv.shape[1], guide.shape[0]/uv.shape[0]
    box = [int(np.floor(xs.min()*sx)), int(np.floor(ys.min()*sy)),
           int(np.ceil((xs.max()+1)*sx)), int(np.ceil((ys.max()+1)*sy))]
    left, top, right, bottom = box
    crop = guide[top:bottom, left:right]
    # The source UV rectangle describes the full artwork canvas, including
    # transparent padding. Its fraction of the guide is not evidence that
    # the actual ink cannot be tracked or safely removed. Validate the real
    # target geometry and garment containment below instead; feature inputs
    # remain bounded to 384px regardless of this source rectangle's size.
    require(min(crop.shape[:2]) >= 20,
            'SOURCE_PRINT_UNSUITABLE', '来源印花定位区域分辨率过低，无法可靠追踪')
    # Validate that the UV itself has one locally planar mapping. Quantized UV
    # pixels are fitted with subpixel source-canvas coordinates.
    step = max(1, len(xs)//1200)
    texture = np.column_stack((uv[ys[::step], xs[::step], 0]/255., 1-uv[ys[::step], xs[::step], 1]/255.)).astype(np.float32)
    source_points = np.column_stack(((xs[::step]+.5)*sx-.5, (ys[::step]+.5)*sy-.5)).astype(np.float32)
    texture_to_guide, _ = cv2.findHomography(texture, source_points, 0)
    texture_to_guide = homography(texture_to_guide)
    uv_error = np.linalg.norm(project(texture_to_guide, texture)-source_points, axis=1)
    require(float(np.percentile(uv_error, 95)) <= 1.5, 'NON_PLANAR_UV', '现位置图不能用单一透视准确表示，已停止自动追踪')
    # Repetitive scene texture can consume 7k responses before the print is retained.
    # Keep a bounded larger pool; all geometric and multi-scale checks still apply.
    detector = cv2.SIFT_create(nfeatures=24000, contrastThreshold=.012, edgeThreshold=15)
    target_scale = min(1., 1024/max(master.shape[:2]))
    target = cv2.resize(master, None, fx=target_scale, fy=target_scale, interpolation=cv2.INTER_AREA) if target_scale < 1 else master
    target_k, target_d = detector.detectAndCompute(cv2.cvtColor(target, cv2.COLOR_RGB2GRAY), None)
    require(target_d is not None, 'FEATURE_TRACKING_FAILED', '母图没有足够可核对的印花特征')
    source_to_canvas = np.array([[1., 0, left], [0, 1., top], [0, 0, 1.]])
    candidates = track_candidates(master, crop, source_to_canvas, texture_to_guide, detector, target_k, target_d, target_scale, started)
    best, agreement = consistent_candidates(candidates)
    reference = 'EXACT_SUBMITTED_GUIDE'; binding = None
    if len(agreement) < 3:
        # Resizing an already tiny guide cannot recover its lost texture. Use
        # properly downsampled native art only after proving the UV binding.
        binding = guide_artwork_binding(guide, uv, art)
        a = art[:, :, 3:4].astype(np.float32)/255
        white = np.rint(art[:, :, :3]*a+255*(1-a)).astype(np.uint8)
        art_to_canvas = texture_to_guide@np.diag([1/(art.shape[1]-1), 1/(art.shape[0]-1), 1.])
        candidates = track_candidates(master, white, art_to_canvas, texture_to_guide, detector, target_k, target_d, target_scale, started)
        best, agreement = consistent_candidates(candidates)
        reference = 'UV_BOUND_NATIVE_ARTWORK'
    require(len(candidates) >= 3, 'FEATURE_TRACKING_FAILED', '印花特征匹配不足，不能自动确定位置；未重新生图')
    require(len(agreement) >= 3, 'FEATURE_TRACKING_UNSTABLE', '多尺度印花定位不一致，不能自动推测位置')
    return best, box, texture_to_guide, {'inliers': best['inliers'], 'matches': best['matches'],
            'medianErrorPx': best['medianErrorPx'], 'bootstrapCornerP95Px': best['bootstrapCornerP95Px'],
            'agreeingScales': len(agreement), 'uvFitP95Px': float(np.percentile(uv_error, 95)),
            'reference': reference, 'guideArtworkBinding': binding}

def u2net_view_mask(rgb):
    from garment_foreground import segment
    raw = png(rgb)
    result = segment({'data': encoded(raw)})
    _, rgba = decode(result.get('data'), '前景', 'RGBA')
    require(result.get('sourceSha256') == sha(raw) and rgba.shape[:2] == rgb.shape[:2]
            and np.array_equal(rgba[:, :, :3], rgb), 'SEGMENTATION_BINDING_INVALID', '服装分割与原图不一致')
    return rgba[:, :, 3] > 210

def garment_mask_consensus(base, rotated, context):
    """Recover omitted cloth only with agreement from two aligned model views.

    This is not dilation or color-only flood fill. Large view disagreement is
    rejected instead of allowing an unbounded neutral wall into the garment.
    """
    require(base.dtype == np.bool_ and rotated.dtype == np.bool_ and context.dtype == np.bool_
            and base.ndim == 2 and base.shape == rotated.shape == context.shape,
            'SEGMENTATION_BINDING_INVALID', '服装多视角遮罩尺寸不一致')
    votes = base.astype(np.uint8) + rotated.astype(np.uint8) + context.astype(np.uint8)
    consensus = largest(votes >= 2)
    count = max(1, int(base.sum()))
    added = int((consensus & ~base).sum())
    removed = int((base & ~consensus).sum())
    agreement = float((base & consensus).sum()/max(1, (base | consensus).sum()))
    require(.10 < consensus.mean() < .85 and added <= count*.15 and removed <= count*.10 and agreement > .80,
            'GARMENT_MASK_UNCERTAIN', '服装多视角分割差异过大，无法确认完整衣服边界，未扩大换色区域')
    return consensus

def u2net_mask(rgb):
    # U2Net saliency can exclude bright sleeve patches in the original view.
    # Rotate and change model context to gather separate alignment evidence;
    # use the already installed model, never download another model at runtime.
    base = u2net_view_mask(rgb)
    rotated = u2net_view_mask(rgb[::-1, ::-1].copy())[::-1, ::-1]
    height, width = rgb.shape[:2]
    pad = max(2, round(min(height, width)*.10))
    padded = cv2.copyMakeBorder(rgb, pad, pad, pad, pad, cv2.BORDER_REFLECT_101)
    padded_shape = padded.shape[:2]
    if max(padded_shape) > MAX_SIDE:
        scale = MAX_SIDE/max(padded_shape)
        padded = cv2.resize(padded, (round(padded_shape[1]*scale), round(padded_shape[0]*scale)), interpolation=cv2.INTER_AREA)
    context = u2net_view_mask(padded)
    if context.shape != padded_shape:
        context = cv2.resize(context.astype(np.uint8), (padded_shape[1], padded_shape[0]), interpolation=cv2.INTER_NEAREST) > 0
    context = context[pad:pad+height, pad:pad+width]
    return garment_mask_consensus(base, rotated, context)

def garment_mask(rgb, candidate):
    ch = rgb.astype(np.int16)
    neutral = (ch.mean(2) > 160) & (ch.max(2)-ch.min(2) < 24)
    neutral = cv2.morphologyEx(neutral.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8)) > 0
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))
    # Color alone can join the collar to a white wall/book behind it. U2Net is
    # independent evidence: refine only its narrow edge band, never let a
    # neutral-colored background become a certain foreground seed.
    support = cv2.dilate(candidate.astype(np.uint8), kernel) > 0
    seed = largest(neutral & candidate)
    outer = (cv2.dilate(seed.astype(np.uint8), kernel) > 0) & support
    inner = cv2.erode(seed.astype(np.uint8), kernel) > 0
    gc = np.full(seed.shape, cv2.GC_BGD, np.uint8); gc[outer] = cv2.GC_PR_BGD; gc[seed] = cv2.GC_PR_FGD; gc[inner] = cv2.GC_FGD
    cv2.setRNGSeed(739)
    cv2.grabCut(cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), gc, None, np.zeros((1, 65)), np.zeros((1, 65)), 5, cv2.GC_INIT_WITH_MASK)
    mask = largest((gc == cv2.GC_FGD) | (gc == cv2.GC_PR_FGD))
    iou = float((candidate & mask).sum()/max(1, (candidate | mask).sum()))
    require(.10 < mask.mean() < .85 and iou > .92, 'GARMENT_MASK_UNCERTAIN', '白色衣服分割不一致，无法保证椅子和环境不被换色')
    border = np.concatenate((mask[0], mask[-1], mask[:, 0], mask[:, -1]))
    require(not border.any(), 'GARMENT_CROPPED', '衣服触及图像边缘，不能可靠复用完整服装')
    cloth = ch[cv2.erode(mask.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0]
    require(len(cloth) >= 500 and np.median(cloth.mean(1)) > 175
            and float((cloth.max(1)-cloth.min(1) < 30).mean()) > .95,
            'WHITE_MASTER_REQUIRED', '当前复用仅支持去花后的中性白色衣服母图')
    return mask, iou

def nearest_color(rgb, known):
    require(known.any(), 'GARMENT_MASK_UNCERTAIN', '缺少可信的内外边缘像素')
    _, labels = cv2.distanceTransformWithLabels((~known).astype(np.uint8), cv2.DIST_L2, 5, labelType=cv2.DIST_LABEL_PIXEL)
    lookup = np.zeros((int(labels.max())+1, 3), np.float32); lookup[labels[known]] = rgb[known]
    return lookup[labels]

def edge_alpha(rgb, mask):
    smooth = cv2.morphologyEx(mask.astype(np.uint8), cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))) > 0
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))
    core = cv2.erode(smooth.astype(np.uint8), k) > 0; outer = cv2.dilate(smooth.astype(np.uint8), k) > 0
    fg = nearest_color(rgb, core); bg = nearest_color(rgb, ~outer); delta = fg-bg; norm = (delta*delta).sum(2)
    alpha = np.clip(((rgb.astype(np.float32)-bg)*delta).sum(2)/np.maximum(norm, 1), 0, 1)
    alpha[norm < 400] = smooth[norm < 400]; alpha[core] = 1; alpha[~outer] = 0
    alpha[cv2.dilate(smooth.astype(np.uint8), np.ones((3, 3), np.uint8)) == 0] = 0
    return np.rint(alpha*255).astype(np.uint8)

def recover_sleeve_fold(rgb, mask):
    """Recover a small neutral sleeve hem lost beside a dark edge badge.

    A broad dilation would also dye the chair/background and the badge itself.
    Extrapolate only a measured straight sleeve hem with a dark badge above it.
    """
    height, width = mask.shape
    contours, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return mask
    hull = np.zeros(mask.shape, np.uint8)
    cv2.drawContours(hull, [cv2.convexHull(max(contours, key=cv2.contourArea))], -1, 255, -1)
    scale = min(height, width)/1024
    channels = rgb.astype(np.int16)
    neutral = (channels.mean(2) > 145) & (channels.max(2)-channels.min(2) < 16)
    recovered = mask.copy()
    # A hem running behind a sewn-on badge can extend beyond the segmentation
    # silhouette. Continue only a measured, nearly straight sleeve edge, and
    # only when a dark badge sits directly above the missing neutral strip.
    yy, xx = np.indices(mask.shape)
    for mirrored in (False, True):
        view_mask = mask[:, ::-1] if mirrored else mask
        view_rgb = rgb[:, ::-1] if mirrored else rgb
        vx, vy, vw, vh = cv2.boundingRect(max(cv2.findContours(
            view_mask.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)[0],
            key=cv2.contourArea))
        xs = np.arange(vx+round(vw*.07), vx+round(vw*.16))
        lower = []
        for column in xs:
            rows = np.flatnonzero(view_mask[vy+round(vh*.40):vy+round(vh*.75), column])
            if len(rows) == 0:
                break
            lower.append(vy+round(vh*.40)+int(rows[-1]))
        if len(lower) != len(xs) or len(xs) < 12:
            continue
        slope, intercept = np.polyfit(xs, lower, 1)
        if not .3 < slope < 1.2 or np.sqrt(np.mean((np.polyval((slope, intercept), xs)-lower)**2)) > 2*scale:
            continue
        anchor = xs[0]
        line = slope*xx+intercept
        near_hem = (xx >= vx+round(vw*.02)) & (xx < anchor) & (yy >= line-16*scale) & (yy <= line+2*scale)
        dark_above = (view_rgb.astype(np.int16).mean(2) < 70) & (hull[:, ::-1] > 0 if mirrored else hull > 0)
        badge_zone = dark_above[max(0,round(line[0,anchor])-65):round(line[0,anchor])-30,
                               max(0,anchor-round(vw*.03)):anchor]
        if badge_zone.size == 0 or badge_zone.sum() < 100*scale*scale:
            continue
        view_neutral = neutral[:, ::-1] if mirrored else neutral
        strip = near_hem & view_neutral & ~view_mask
        count, labels, stats, _ = cv2.connectedComponentsWithStats(strip.astype(np.uint8))
        for index in range(1, count):
            _, _, sw, sh, area = map(int, stats[index])
            if 60*scale*scale <= area <= 500*scale*scale and sw <= 75*scale and sh <= 30*scale:
                addition = labels == index
                if mirrored:
                    recovered[:, ::-1] |= addition
                else:
                    recovered |= addition
    # A small repair is acceptable; widespread disagreement remains a stop.
    require(int((recovered & ~mask).sum()) <= max(400, int(mask.sum()*.002)),
            'GARMENT_MASK_UNCERTAIN', '袖口折边与背景边界不清，未扩大换色区域')
    return recovered

def cleanup(rgb, mask, started):
    x, y, w, h = cv2.boundingRect(mask.astype(np.uint8)); pad = 6
    x0, y0, x1, y1 = max(0, x-pad), max(0, y-pad), min(rgb.shape[1], x+w+pad), min(rgb.shape[0], y+h+pad)
    m = mask[y0:y1, x0:x1]; source = rgb[y0:y1, x0:x1]
    field = cv2.inpaint(source, m.astype(np.uint8)*255, 4, cv2.INPAINT_TELEA).astype(np.float32)
    yy, xx = np.indices(m.shape); parity = (xx+yy) % 2
    for iteration in range(900):
        delta = 0
        for p in (0, 1):
            average = (np.roll(field, 1, 0)+np.roll(field, -1, 0)+np.roll(field, 1, 1)+np.roll(field, -1, 1))*.25
            selected = m & (parity == p); change = (average-field)*1.75
            if iteration % 50 == 0:
                delta = max(delta, float(np.abs(change[selected]).max(initial=0)))
            field[selected] += change[selected]
        if iteration % 50 == 0:
            check_time(started)
            if iteration > 50 and delta < .015:
                break
    require(iteration < 899, 'CLEANUP_NOT_CONVERGED', '局部去花光照重建未收敛，未继续套版')
    output = rgb.copy(); output[y0:y1, x0:x1][m] = np.rint(np.clip(field[m], 0, 255)).astype(np.uint8)
    return output, iteration+1

def validate_artwork(rgba):
    alpha = rgba[:, :, 3]
    require(int((alpha < 8).sum()) >= alpha.size*.03 and int((alpha > 20).sum()) >= 64,
            'TRANSPARENT_ARTWORK_REQUIRED', '印花须有真实透明背景和可见内容')

def prepare(body):
    started = time.monotonic()
    require(isinstance(body, dict), 'INPUT_INVALID', '场景处理参数无效')
    ids = body.get('printIds')
    require(isinstance(ids, list) and 1 <= len(ids) <= 255
            and all(isinstance(value, str) and 1 <= len(value.strip()) <= 128 for value in ids)
            and len(set(ids)) == len(ids), 'PRINT_IDS_INVALID', '缺少有效且唯一的固定印花列表')
    master_raw, master = decode(body.get('master'), '母图', formats=('PNG', 'JPEG', 'WEBP'))
    guide_raw, guide = decode(body.get('guide'), '定位图')
    uv_raw, uv = decode(body.get('uv'), 'UV', 'RGBA'); art_raw, art = decode(body.get('artwork'), '原印花', 'RGBA')
    slot = visible_print_slot(uv, ids)
    validate_artwork(art)
    require(min(master.shape[:2]) >= 256 and min(guide.shape[:2]) >= 256, 'IMAGE_DIMENSIONS_INVALID', '母图与定位图分辨率过低')
    best, box, texture_to_guide, tracking = track(master, guide, uv, art, started)
    texture_h = best['textureMatrix']; corners = project(texture_h, UNIT)
    h, w = master.shape[:2]; ah, aw = art.shape[:2]
    old_alpha = cv2.warpPerspective(art[:, :, 3], texture_h@np.diag([1/(aw-1), 1/(ah-1), 1]), (w, h))
    remove = cv2.dilate((old_alpha > 8).astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9))) > 0
    require(100 <= int(remove.sum()) <= int(h*w*.4), 'CLEANUP_TOO_LARGE', '旧印花覆盖范围无法可靠修复，未修改衣服')
    candidate = u2net_mask(master); check_time(started)
    reliable = cv2.erode(candidate.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0
    require(not np.any(remove & ~reliable), 'PRINT_OUTSIDE_GARMENT', '追踪印花不完全位于可靠衣服内部')
    # Use actual garment containment instead of an arbitrary chest rectangle.
    # Keep enough observed cloth to reconstruct lighting and validate recoloring.
    require(int(remove.sum()) < int(candidate.sum())*.6,
            'CLEANUP_TOO_LARGE', '可参考的原始布料不足，无法可靠恢复印花覆盖处')
    blank, iterations = cleanup(master, remove, started)
    require(np.array_equal(blank[~remove], master[~remove]), 'PROTECTED_PIXELS_CHANGED', '局部去花修改了保护区域')
    mask, iou = garment_mask(blank, candidate)
    require(not np.any(remove & ~mask), 'PRINT_OUTSIDE_GARMENT', '去花区域与衣服遮罩不一致')
    # Gross residuals are a stop condition, not a claim of perfect restoration.
    values = blank[remove].astype(np.int16)
    require(float(((values.max(1)-values.min(1)) > 35).mean()) < .005,
            'PRINT_RESIDUE_DETECTED', '去花后仍有明显彩色残留，保留母图并停止自动套版')
    mask = recover_sleeve_fold(blank, mask)
    matte = edge_alpha(blank, mask); blank_raw = png(blank); alpha_raw = png(matte); remove_raw = png(remove.astype(np.uint8)*255)
    mapping = {'algorithm': ALGORITHM, 'maskRevision': MASK_REVISION, 'width': w, 'height': h, 'printId': ids[slot-1], 'printSlot': slot,
               'sourceSha256': sha(master_raw), 'guideSha256': sha(guide_raw), 'uvSha256': sha(uv_raw), 'artworkSha256': sha(art_raw),
               'blankSha256': sha(blank_raw), 'alphaSha256': sha(alpha_raw), 'cleanupMaskSha256': sha(remove_raw),
               'cleanupMask': encoded(remove_raw), 'sourceUvBox': box, 'sourceGuideWidth': guide.shape[1], 'sourceGuideHeight': guide.shape[0],
               'sourceCanvasToMasterH': best['matrix'].tolist(), 'textureToSourceCanvasH': texture_to_guide.tolist(),
               'textureToMasterH': texture_h.tolist(), 'targetCorners': corners.tolist()}
    mapping = freeze_mapping_numbers(mapping)
    mapping['mappingSha256'] = mapping_checksum(mapping)
    report = {'method': ALGORITHM, 'status': 'LOCAL_DRAPE_PREPARED', 'tracking': tracking, 'garmentMaskAgreement': iou,
              'maskRevision': MASK_REVISION, 'garmentMaskPixels': int(mask.sum()),
              'garmentMaskCoverage': float(mask.mean()),
              'width': w, 'height': h, 'printId': ids[slot-1], 'printSlot': slot,
              'cleanupPixels': int(remove.sum()), 'cleanupIterations': iterations, 'outsideCleanupChangedPixels': 0,
              'modelCalls': 0, 'passed': True, 'productionReady': False,
              'limitation': '仅验证单处可追踪印花；去花使用平滑光照重建，不能恢复未知织纹、隐藏褶皱或证明真实印刷尺寸。',
              'elapsedMs': round((time.monotonic()-started)*1000)}
    check_time(started)
    return {'blank': encoded(blank_raw), 'alpha': encoded(alpha_raw), 'mapping': mapping, 'report': report,
            'sourceSha256': sha(master_raw), 'guideSha256': sha(guide_raw), 'uvSha256': sha(uv_raw),
            'artworkSha256': sha(art_raw), 'algorithm': ALGORITHM}

def recolor(blank, alpha, target):
    core = cv2.erode((alpha >= .98).astype(np.uint8), np.ones((5, 5), np.uint8)) > 0
    require(int(core.sum()) >= 500, 'GARMENT_MASK_UNCERTAIN', '可信布料区域不足，无法换色')
    foreground = nearest_color(blank, core); foreground[alpha >= .98] = blank[alpha >= .98]
    lab = cv2.cvtColor(foreground.astype(np.float32)/255, cv2.COLOR_RGB2LAB)
    reference_l = float(np.median(lab[:, :, 0][core]))
    target_lab = cv2.cvtColor(np.asarray(target, np.float32).reshape(1, 1, 3)/255, cv2.COLOR_RGB2LAB)[0, 0]
    new_l = np.clip(target_lab[0]+(lab[:, :, 0]-reference_l)*.85, 1, 99.5)
    scale = np.clip(new_l/max(float(target_lab[0]), 8), 0, 1.15)
    updated = np.stack((new_l, scale*target_lab[1], scale*target_lab[2]), axis=2).astype(np.float32)
    colored = cv2.cvtColor(updated, cv2.COLOR_LAB2RGB)*255
    output = np.clip(np.rint(blank.astype(np.float32)+alpha[:, :, None]*(colored-foreground)), 0, 255).astype(np.uint8)
    require(np.array_equal(output[alpha == 0], blank[alpha == 0]), 'PROTECTED_PIXELS_CHANGED', '换色修改了环境像素')
    return output

def render(body):
    started = time.monotonic()
    require(isinstance(body, dict), 'INPUT_INVALID', '场景渲染参数无效')
    mapping = body.get('mapping')
    require(isinstance(mapping, dict) and mapping.get('algorithm') == ALGORITHM,
            'MAPPING_INVALID', '缺少固定的场景印花映射')
    require(len(json.dumps(mapping)) <= 180000, 'MAPPING_INVALID', '场景映射数据过大')
    require(isinstance(mapping.get('mappingSha256'), str) and mapping['mappingSha256'] == mapping_checksum(mapping),
            'MAPPING_CHECKSUM_INVALID', '场景映射、位置或遮罩指纹不匹配，请恢复固定配置')
    master_raw, master = decode(body.get('master'), '母图', formats=('PNG', 'JPEG', 'WEBP'))
    blank_raw, blank = decode(body.get('blank'), '去花母图')
    alpha_raw, alpha_pixels = decode(body.get('alpha'), '衣服遮罩', 'L'); art_raw, art = decode(body.get('artwork'), '新印花', 'RGBA')
    validate_artwork(art)
    remove_raw, remove = decode(mapping.get('cleanupMask'), '去花遮罩', 'L')
    for field, raw in [('sourceSha256', master_raw), ('blankSha256', blank_raw), ('alphaSha256', alpha_raw), ('cleanupMaskSha256', remove_raw)]:
        require(mapping.get(field) == sha(raw), 'ASSET_BINDING_CHANGED', '场景图片或遮罩与固定指纹不一致')
    shape = master.shape[:2]
    require(blank.shape[:2] == shape and alpha_pixels.shape == shape and remove.shape == shape
            and mapping.get('width') == shape[1] and mapping.get('height') == shape[0],
            'ASSET_DIMENSIONS_CHANGED', '场景图片或遮罩尺寸不一致')
    require(np.array_equal(master[remove == 0], blank[remove == 0]), 'PROTECTED_PIXELS_CHANGED', '去花母图超出固定修补范围')
    h = homography(mapping.get('textureToMasterH')); corners = project(h, UNIT); validate_quad(corners, shape)
    canvas_h = homography(mapping.get('sourceCanvasToMasterH'))
    uv_h = homography(mapping.get('textureToSourceCanvasH'))
    require(float(np.linalg.norm(project(canvas_h@uv_h, UNIT)-corners, axis=1).max()) < .02,
            'MAPPING_INVALID', '场景透视与冻结的UV对应不一致')
    alpha = alpha_pixels.astype(np.float32)/255
    require(.10 < float((alpha > .5).mean()) < .85 and not (alpha[0].any() or alpha[-1].any() or alpha[:, 0].any() or alpha[:, -1].any()),
            'GARMENT_MASK_UNCERTAIN', '固定衣服遮罩面积或边界无效')
    require(np.isin(remove, [0, 255]).all() and 100 <= int((remove > 0).sum()) <= min(int(shape[0]*shape[1]*.4), int((alpha >= .98).sum()*.6))
            and not np.any((remove > 0) & (alpha < .98)),
            'CLEANUP_MASK_INVALID', '固定去花遮罩超出可信衣服内部或面积范围')
    original = body.get('originalColor')
    require(type(original) is bool, 'COLOR_MODE_INVALID', '缺少明确的原色或SKU换色标记')
    target = body.get('targetRgb')
    if not original:
        require(isinstance(target, str) and re.fullmatch(r'#[a-fA-F0-9]{6}', target),
                'SKU_COLOR_REQUIRED', '缺少上游已验证的SKU布色，不能仅凭颜色名称染色')
        color = [int(target[i:i+2], 16) for i in (1, 3, 5)]
        base = recolor(blank, alpha, color)
    else:
        base = blank.copy()
    height, width = shape; ah, aw = art.shape[:2]
    left = max(0, int(np.floor(corners[:, 0].min()))-2); top = max(0, int(np.floor(corners[:, 1].min()))-2)
    right = min(width, int(np.ceil(corners[:, 0].max()))+3); bottom = min(height, int(np.ceil(corners[:, 1].max()))+3)
    rw, rh = right-left, bottom-top
    supersample = max(1, min(4, int((1760000/max(1, rw*rh))**.5)))
    warp = np.diag([supersample, supersample, 1])@np.array([[1, 0, -left], [0, 1, -top], [0, 0, 1.]])@h@np.diag([1/(aw-1), 1/(ah-1), 1])
    texture_alpha = art[:, :, 3].astype(np.float32)/255; premult = art[:, :, :3].astype(np.float32)*texture_alpha[:, :, None]
    ink_alpha = cv2.resize(cv2.warpPerspective(texture_alpha, warp, (rw*supersample, rh*supersample)), (rw, rh), interpolation=cv2.INTER_AREA)
    ink = cv2.resize(cv2.warpPerspective(premult, warp, (rw*supersample, rh*supersample)), (rw, rh), interpolation=cv2.INTER_AREA)
    garment = alpha[top:bottom, left:right]
    visible = ink_alpha > .1
    require(int(visible.sum()) >= 20, 'PRINT_NOT_VISIBLE', '新印花透明度过低或缩放后不可见，未继续套版')
    require(not np.any((ink_alpha > .01) & (garment < .98)),
            'PRINT_OUTSIDE_GARMENT', '新印花越过衣服，未裁切后假装完成')
    light = blank[top:bottom, left:right].astype(np.float32).mean(2)
    shade = np.clip(light/max(1, float(np.median(light[visible]))), .65, 1.2)
    ink *= shade[:, :, None]
    output = base.copy(); region = base[top:bottom, left:right]
    output[top:bottom, left:right] = np.clip(np.rint(region*(1-ink_alpha[:, :, None])+ink), 0, 255).astype(np.uint8)
    require(np.array_equal(output[alpha == 0], master[alpha == 0]), 'PROTECTED_PIXELS_CHANGED', '合成结果修改了服装外环境')
    result = png(output); check_time(started)
    return {'data': encoded(result), 'algorithm': ALGORITHM, 'report': {'method': ALGORITHM, 'status': 'LOCAL_DRAPE_RENDERED',
            'sourceSha256': sha(master_raw), 'blankSha256': sha(blank_raw), 'alphaSha256': sha(alpha_raw),
            'artworkSha256': sha(art_raw), 'outputSha256': sha(result), 'printId': mapping.get('printId'),
            'width': width, 'height': height,
            'targetRgb': None if original else target.lower(), 'originalColor': original, 'modelCalls': 0,
            'outsideGarmentChangedPixels': 0, 'printSampling': 'PREMULTIPLIED_RGBA_4X', 'passed': True, 'productionReady': False,
            'limitation': '单处近似平面印花的本地透视套版；保留场景像素，不代表实物色号、织纹或印刷尺寸已验收。',
            'elapsedMs': round((time.monotonic()-started)*1000)}}
