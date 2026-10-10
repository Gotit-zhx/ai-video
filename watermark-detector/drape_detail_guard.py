"""Independent local evidence for small template garment labels.

The image model may describe a missing side tag as present. This checker only
accepts dark label pixels that are actually attached to the generated garment;
background books, chair edges and text cannot count.
"""
import base64
import hashlib
import io
import cv2
import numpy as np
from PIL import Image

VERSION = "DRAPE_DETAIL_GUARD_V1"


def _decode(data):
    raw = base64.b64decode(data, validate=True)
    if len(raw) > 8 * 1024 * 1024:
        raise ValueError("Image exceeds detail-check limit")
    with Image.open(io.BytesIO(raw)) as image:
        if image.width < 256 or image.height < 256 or image.width > 1536 or image.height > 1536:
            raise ValueError("Invalid detail-check image dimensions")
        pixels = np.asarray(image.convert("RGB")).copy()
    return raw, pixels


def _components(rgb, roi, garment=None):
    height, width = rgb.shape[:2]
    x0, y0, x1, y1 = (round(v * size) for v, size in zip(
        roi, (width, height, width, height)))
    crop = rgb[y0:y1, x0:x1]
    # Black woven labels are nearly neutral and high-contrast against white cloth.
    dark = (crop.max(axis=2) < 110).astype(np.uint8)
    if garment is not None:
        dark &= garment[y0:y1, x0:x1].astype(np.uint8)
    count, _, stats, _ = cv2.connectedComponentsWithStats(dark, 8)
    return [
        {"x": x0 + int(x), "y": y0 + int(y), "width": int(w),
         "height": int(h), "darkPixels": int(area)}
        for x, y, w, h, area in stats[1:count]
        if area >= (18 if garment is None else 45)
        and w >= (3 if garment is None else 7)
        and h >= (6 if garment is None else 12)
        and .20 <= w / h <= 3.5
    ]


def _edge_labels(rgb, garment):
    """Count narrow dark tags only at the right garment edge, never in its print."""
    height, width = garment.shape
    edge = np.zeros_like(garment, np.uint8)
    for y in range(round(height * .62), round(height * .92)):
        xs = np.flatnonzero(garment[y])
        if len(xs) < width * .12:
            continue
        right = int(xs.max())
        edge[y, max(0, right-round(width*.025)):min(width, right+round(width*.012))] = 1
    # Measure the *whole* dark component before edge filtering: clipping a
    # large shirt print to this strip would otherwise look like a small tag.
    dark = (rgb.max(axis=2) < 110).astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(dark, 8)
    isolated = [
        {"x": int(x), "y": int(y), "width": int(w), "height": int(h),
         "darkPixels": int(area)}
        for index, (x, y, w, h, area) in enumerate(stats[1:count], 1)
        if area >= 35 and np.any(edge[y:y+h, x:x+w] & (labels[y:y+h, x:x+w] == index))
        and .004 * width <= w <= .03 * width
        and .012 * height <= h <= .10 * height and w / h <= .85
    ]
    if isolated:
        return isolated
    # A dark woven tag may merge with a black chair behind the garment. Its
    # full dark component then spans the chair and fails the size filter above.
    # Look only for several pale glyph strokes in a narrow strip immediately
    # outside the segmented cloth edge. A solid chair edge or background text
    # farther away cannot supply this evidence.
    ink = np.zeros((height, width), np.uint8)
    for y in range(round(height * .62), round(height * .92)):
        xs = np.flatnonzero(garment[y])
        if len(xs) < width * .12:
            continue
        right = int(xs.max())
        for x in range(right + 1, min(width, right + round(width * .028))):
            pixel = rgb[y, x]
            if (pixel.min() > 145 and pixel.max() - pixel.min() < 35
                    and np.mean(rgb[max(0, y-3):y+4, max(0, x-3):x+4]) < 120):
                ink[y, x] = 1
    count, _, glyphs, _ = cv2.connectedComponentsWithStats(ink, 8)
    strokes = [(int(x), int(y), int(w), int(h), int(area))
               for x, y, w, h, area in glyphs[1:count]
               if 2 <= area <= 90 and w <= width * .025 and h <= height * .025]
    for start in range(round(height * .62), round(height * .87)):
        nearby = [s for s in strokes if start <= s[1] < start + round(height * .07)]
        if (len(nearby) >= 6 and sum(s[4] for s in nearby) >= 60
                and max(s[0]+s[2] for s in nearby)-min(s[0] for s in nearby) <= width * .025):
            return [{"x": min(s[0] for s in nearby), "y": start,
                     "width": max(s[0]+s[2] for s in nearby)-min(s[0] for s in nearby),
                     "height": round(height * .07), "glyphPixels": sum(s[4] for s in nearby)}]
    return []


def inspect(guide_raw, master_raw):
    from drape_reuse import u2net_mask
    guide = np.asarray(Image.open(io.BytesIO(guide_raw)).convert("RGB")).copy()
    master = np.asarray(Image.open(io.BytesIO(master_raw)).convert("RGB")).copy()
    expected_sleeve = _components(guide, (.04, .38, .18, .62))
    expected_side = _components(guide, (.72, .69, .83, .89))
    garment = u2net_mask(master)
    ys, xs = np.where(garment)
    if len(xs) < master.shape[0] * master.shape[1] * .08:
        raise ValueError("Garment segmentation is incomplete")
    margins = {"left": int(xs.min()), "top": int(ys.min()),
               "right": int(master.shape[1] - 1 - xs.max()),
               "bottom": int(master.shape[0] - 1 - ys.max())}
    visibility = all(margins[edge] >= max(12, round(master.shape[axis] * .018))
                     for edge, axis in (("left", 1), ("right", 1), ("top", 0), ("bottom", 0)))
    actual_sleeve = _components(master, (.03, .36, .27, .66), garment)
    actual_side = _edge_labels(master, garment)
    sleeve_ok = not expected_sleeve or bool(actual_sleeve)
    side_ok = not expected_side or bool(actual_side)
    # A template with no badge in a region has nothing to copy there; the
    # independent model verdict still checks that none was invented.
    return {"status": "PASSED" if sleeve_ok and side_ok else "REJECTED",
            "geometryFullyVisible": bool(visibility), "borderMargins": margins,
            "sleevePresent": sleeve_ok, "sidePresent": side_ok,
            "templateSleeveRequired": bool(expected_sleeve), "templateSideRequired": bool(expected_side),
            "templateSleeve": expected_sleeve, "templateSide": expected_side,
            "outputSleeve": actual_sleeve, "outputSide": actual_side}


def check(body):
    guide_raw, _ = _decode(body["guide"])
    master_raw, _ = _decode(body["master"])
    result = inspect(guide_raw, master_raw)
    return {"algorithm": VERSION, "guideSha256": hashlib.sha256(guide_raw).hexdigest(),
            "masterSha256": hashlib.sha256(master_raw).hexdigest(), **result}
