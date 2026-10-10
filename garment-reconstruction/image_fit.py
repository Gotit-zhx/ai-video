"""Small, reproducible silhouette experiment; not a production reconstruction service.

Camera calibration and target segmentation are immutable during each fit. The
software rasterizer is used for the optimization experiment only. A separate
Three.js viewer is used to inspect the produced geometry in an actual browser.
"""
from __future__ import annotations

import hashlib
import json
import math
import time
from pathlib import Path

import cv2
import numpy as np
from scipy.optimize import minimize


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, allow_nan=False).encode()).hexdigest()


def project(vertices, camera):
    vertices = np.asarray(vertices, dtype=float)
    yaw, pitch = np.radians([camera.get("yaw", 0), camera.get("pitch", 0)])
    x = np.cos(yaw) * vertices[:, 0] - np.sin(yaw) * vertices[:, 2]
    depth = np.sin(yaw) * vertices[:, 0] + np.cos(yaw) * vertices[:, 2]
    y = np.cos(pitch) * vertices[:, 1] - np.sin(pitch) * depth
    return np.column_stack((x * camera["scale"] + camera["cx"], -y * camera["scale"] + camera["cy"]))


def render_mask(mesh, camera, size):
    xy = project(mesh["vertices"], camera)
    faces = np.asarray(mesh["faces"], dtype=int)
    # Subpixel coordinates reduce discontinuity without changing frozen cameras.
    triangles = np.rint(xy[faces] * 16).astype(np.int32)
    mask = np.zeros((size, size), dtype=np.uint8)
    for triangle in triangles:
        cv2.fillConvexPoly(mask, triangle, 255, lineType=cv2.LINE_8, shift=4)
    return mask


def silhouette_metrics(target, rendered):
    t, r = target > 0, rendered > 0
    union = np.logical_or(t, r).sum()
    iou = float(np.logical_and(t, r).sum() / union) if union else 0.0
    k = np.ones((3, 3), np.uint8)
    tb = t & ~cv2.erode(t.astype(np.uint8), k).astype(bool)
    rb = r & ~cv2.erode(r.astype(np.uint8), k).astype(bool)
    if not tb.any() or not rb.any():
        return {"iou": iou, "boundary_p95_bbox": None, "passed_candidate_threshold": False}
    dt = cv2.distanceTransform((~tb).astype(np.uint8), cv2.DIST_L2, cv2.DIST_MASK_PRECISE)
    dr = cv2.distanceTransform((~rb).astype(np.uint8), cv2.DIST_L2, cv2.DIST_MASK_PRECISE)
    ys, xs = np.where(t)
    diagonal = float(np.hypot(xs.max() - xs.min(), ys.max() - ys.min()))
    error = float(np.percentile(np.concatenate([dt[rb], dr[tb]]), 95) / max(diagonal, 1))
    return {"iou": iou, "boundary_p95_bbox": error,
            "passed_candidate_threshold": bool(iou >= .95 and error <= .015)}


def segment_reference(path, size):
    """Specific cream-garment/white-background heuristic, saved for visual audit.

    Does not claim generic segmentation or measured ground truth. Neutral/dark
    garments and busy backgrounds require a different validated segmenter.
    """
    source = Path(path).read_bytes()
    image = cv2.imdecode(np.frombuffer(source, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError(f"Cannot decode {path}")
    image = cv2.resize(image, (size, size), interpolation=cv2.INTER_AREA)
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    mask = ((lab[:, :, 2] >= 134) & (lab[:, :, 0] < 254)).astype(np.uint8) * 255
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask)
    if count < 2:
        raise ValueError("No foreground segmented; stop instead of fabricating a mask")
    mask = np.where(labels == 1 + np.argmax(stats[1:, cv2.CC_STAT_AREA]), 255, 0).astype(np.uint8)
    # Fill internal shadows as foreground: this is the external silhouette only.
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cv2.drawContours(mask, contours, -1, 255, cv2.FILLED)
    return image, mask, {"sha256": hashlib.sha256(source).hexdigest(),
                        "segmentation": "cream_lab_b_ge134_l_lt254_largest_component_fill_external_v1",
                        "provenance": "AI-generated target view, not independent photograph"}


def reference_camera(mask, body_length, yaw):
    """One-off approximate orthographic registration, frozen before fitting.

    This explicit assumption is NOT recovered physical camera calibration.
    """
    ys, xs = np.where(mask > 0)
    return {"yaw": yaw, "pitch": 0.0, "scale": float((ys.max() - ys.min()) / body_length),
            "cx": float((xs.min() + xs.max()) / 2), "cy": float(ys.max()),
            "calibration": "bbox-height orthographic assumption, frozen v1"}


def fit(build_mesh, start, names, bounds, targets, cameras, size, max_evaluations=700):
    started = time.monotonic()
    best = {"loss": float("inf"), "params": dict(start)}
    history = []
    count = 0
    infeasible = []
    cached = {}
    # Signed distances let small subpixel improvements guide a raster objective.
    fields = []
    for target in targets:
        t = (target > 0).astype(np.uint8)
        fields.append(cv2.distanceTransform(1 - t, cv2.DIST_L2, 5) - cv2.distanceTransform(t, cv2.DIST_L2, 5))

    def objective(values):
        nonlocal count
        key = tuple(round(float(v), 7) for v in values)
        if key in cached:
            return cached[key]
        params = {**start, **dict(zip(names, map(float, values)))}
        count += 1
        try:
            mesh = build_mesh(params)
        except ValueError as error:
            infeasible.append({"evaluation": count, "reason": str(error), "params": params})
            cached[key] = 1e6
            return 1e6
        losses = []
        for target, camera, field in zip(targets, cameras, fields):
            rendered = render_mask(mesh, camera, size)
            mismatch = (target > 0) != (rendered > 0)
            # Error is area plus target-boundary distance; all views equal weight.
            losses.append(float(np.sum(mismatch * (1 + np.abs(field))) / max(np.count_nonzero(target), 1)))
        loss = float(np.mean(losses))
        if loss < best["loss"]:
            best.update(loss=loss, params=params)
            history.append({"evaluation": count, "loss": loss, "params": params})
        cached[key] = loss
        return loss

    initial_values = np.asarray([start[n] for n in names], dtype=float)
    initial_loss = objective(initial_values)
    optimized = minimize(objective, initial_values, method="Powell", bounds=bounds,
                         options={"maxfev": max_evaluations - 1, "xtol": .0005, "ftol": .00005})
    result = build_mesh(best["params"])
    return result, {"initial_loss": initial_loss, "best_loss": best["loss"], "params": best["params"],
                    "evaluations": count, "optimizer_message": str(optimized.message),
                    "elapsed_seconds": time.monotonic() - started, "improvements": history,
                    "infeasible_candidates": infeasible,
                    "camera_hash": digest(cameras), "max_evaluations": max_evaluations,
                    "method": "bounded Powell / software triangle silhouette rasterization"}


def overlay(reference, target, rendered):
    base = reference.copy() if reference is not None else np.full((*target.shape, 3), 246, np.uint8)
    contours, _ = cv2.findContours(target, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cv2.drawContours(base, contours, -1, (60, 160, 20), 1)
    contours, _ = cv2.findContours(rendered, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cv2.drawContours(base, contours, -1, (40, 40, 220), 1)
    return base
