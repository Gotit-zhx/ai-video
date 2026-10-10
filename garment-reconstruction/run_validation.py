#!/usr/bin/env python3
"""Bounded feasibility experiment, including its failures and missing evidence."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import platform
import subprocess
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import scipy
from PIL import Image, ImageDraw, ImageFont

from geometry import build_mesh, DEFAULT_PARAMS, PARAM_BOUNDS
from image_fit import digest, fit, render_mask, silhouette_metrics, segment_reference, reference_camera, overlay

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "output/garment-validation-20260911"
REFERENCE = ROOT / "output/shirt-four-view-20260911"


def write(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n")


def structural(name, mesh):
    write(f"{name}.json", mesh)
    cmd = [sys.executable, str(Path(__file__).with_name("evaluator.py")), str(OUT / f"{name}.json"),
           "--output", str(OUT / f"{name}-structure.json")]
    completed = subprocess.run(cmd, text=True, capture_output=True)
    path = OUT / f"{name}-structure.json"
    if not path.exists():
        raise RuntimeError(f"Evaluator did not produce evidence: {completed.stderr}")
    report = json.loads(path.read_text())
    return {"implemented_checks_passed": report["implementedStructuralChecksPassed"],
            "failed_checks": report["failedChecks"], "garment_acceptance": report["garmentAcceptance"],
            "report": path.name}


def evaluate_views(prefix, before, after, targets, cameras, view_names, images=None):
    records, panels = [], []
    for index, (target, camera, view) in enumerate(zip(targets, cameras, view_names)):
        size = target.shape[0]
        mb, ma = render_mask(before, camera, size), render_mask(after, camera, size)
        record = {"view": view, "before": silhouette_metrics(target, mb), "after": silhouette_metrics(target, ma), "camera": camera}
        records.append(record)
        background = images[index] if images is not None else None
        cv2.imwrite(str(OUT / f"{prefix}-{view}-target-mask.png"), target)
        cv2.imwrite(str(OUT / f"{prefix}-{view}-before-mask.png"), mb)
        cv2.imwrite(str(OUT / f"{prefix}-{view}-after-mask.png"), ma)
        row = []
        for label, rendered in [("Before", mb), ("After", ma)]:
            panel = overlay(background, target, rendered)
            cv2.imwrite(str(OUT / f"{prefix}-{view}-{label.lower()}-overlay.png"), panel)
            row.append((f"{view} / {label}: IoU {record[label.lower()]['iou']:.3f}", panel))
        panels.append(row)
    return records, panels


def collage(rows, title, filename):
    cell = 384
    result = Image.new("RGB", (cell * 2 + 48, len(rows) * (cell + 36) + 116), "#f4f4f2")
    draw = ImageDraw.Draw(result)
    fontpath = "/System/Library/Fonts/STHeiti Light.ttc"
    font = ImageFont.truetype(fontpath, 19) if Path(fontpath).exists() else ImageFont.load_default()
    draw.text((20, 18), title, font=font, fill="#202020")
    draw.text((20, 50), "Green: target outline   Red: actual mesh projection", font=font, fill="#555555")
    for row_i, row in enumerate(rows):
        for col_i, (label, array) in enumerate(row):
            x, y = 16 + col_i * (cell + 16), 94 + row_i * (cell + 36)
            draw.text((x, y), label, font=font, fill="#222222")
            panel = Image.fromarray(cv2.cvtColor(array, cv2.COLOR_BGR2RGB)).resize((cell, cell), Image.Resampling.LANCZOS)
            result.paste(panel, (x, y + 28))
    result.save(OUT / filename)


def defect_experiments(mesh, cameras, size):
    records = []
    baseline = [render_mask(mesh, c, size) for c in cameras]
    flat = copy.deepcopy(mesh)
    for vertex in flat["vertices"]:
        vertex[2] *= .025
    capped = copy.deepcopy(mesh)
    loop = capped["openings"]["neck"]
    center = np.asarray([capped["vertices"][i] for i in loop]).mean(axis=0).tolist()
    center_index = len(capped["vertices"])
    capped["vertices"].append(center)
    # Use actual directed boundary edges to cap with consistent winding, so a
    # rejection cannot be attributed merely to reversed cap normals.
    directed = {}
    for a, b, c in capped["faces"]:
        for u, v in [(a, b), (b, c), (c, a)]:
            key = tuple(sorted((u, v)))
            directed.setdefault(key, []).append((u, v))
    for i, a in enumerate(loop):
        b = loop[(i + 1) % len(loop)]
        uses = directed[tuple(sorted((a, b)))]
        u, v = uses[0]
        capped["faces"].append([v, u, center_index])
    for name, mutated, label in [("defect-flat", flat, "depth collapsed to 2.5%"), ("defect-neck-cap", capped, "neck opening capped")]:
        evidence = structural(name, mutated)
        metrics = [silhouette_metrics(t, render_mask(mutated, c, size)) for t, c in zip(baseline, cameras)]
        records.append({"name": name, "mutation": label, "structural": evidence, "silhouette_vs_valid_model": metrics})
    return records


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--max-evaluations", type=int, default=750)
    parser.add_argument("--fit-size", type=int, default=160)
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    names = ["torso_width", "body_length", "depth", "sleeve_length", "sleeve_drop"]
    bounds = [PARAM_BOUNDS[n] for n in names]
    initial = dict(DEFAULT_PARAMS)
    before = build_mesh(initial)
    write("synthetic-before.json", before)
    base_structure = structural("base", before)
    if not base_structure["implemented_checks_passed"]:
        raise RuntimeError(f"Baseline rejected by independent structural evaluator: {base_structure}")
    target_params = {**initial, "torso_width": .88, "body_length": 1.40, "depth": .36,
                     "sleeve_length": .34, "sleeve_drop": .32}
    for n in names:
        target_params[n] = float(np.clip(target_params[n], *PARAM_BOUNDS[n]))
    target = build_mesh(target_params)
    write("synthetic-target.json", target)
    cameras = [{"yaw": yaw, "pitch": 0, "scale": args.fit_size / 2.4,
                "cx": args.fit_size / 2, "cy": args.fit_size * .86} for yaw in [0, 90]]
    targets = [render_mask(target, c, args.fit_size) for c in cameras]
    print("Fitting controlled synthetic target from fixed front/side masks", flush=True)
    after, log = fit(build_mesh, initial, names, bounds, targets, cameras, args.fit_size, args.max_evaluations)
    write("synthetic-fit-log.json", log)
    synthetic_structure = structural("synthetic-after", after)
    evaluate_size = 512
    high_cameras = [{k: v * evaluate_size / args.fit_size if k in ["scale", "cx", "cy"] else v for k, v in c.items()} for c in cameras]
    high_cameras.append({**high_cameras[0], "yaw": 35, "pitch": 12})
    high_targets = [render_mask(target, c, evaluate_size) for c in high_cameras]
    synthetic_metrics, synthetic_panels = evaluate_views("synthetic", before, after, high_targets, high_cameras, ["front", "side", "heldout35"])
    collage(synthetic_panels, "Controlled target — same model family; not real-world proof", "synthetic-comparison.png")
    defects = defect_experiments(after, high_cameras, evaluate_size)
    write("defects-summary.json", defects)
    print("Controlled fit complete; fitting existing AI-generated product targets", flush=True)
    reference_meta, real_targets, real_images, real_cameras = [], [], [], []
    for view, yaw in [("front", 0), ("back", 180), ("right", 90)]:
        image, mask, meta = segment_reference(REFERENCE / f"{view}.png", args.fit_size)
        meta["view"] = view
        reference_meta.append(meta)
        real_targets.append(mask)
        real_images.append(image)
        real_cameras.append(reference_camera(mask, initial["body_length"], yaw))
    # A frozen calibration is saved before any candidates are scored.
    write("reference-manifest.json", {"references": reference_meta, "cameras": real_cameras,
                                      "cameraHash": digest(real_cameras), "type": "DESIGN_MATCH",
                                      "cameraStatus": "approximate_orthographic_not_physically_calibrated"})
    write("real-before.json", before)
    real_after, real_log = fit(build_mesh, initial, names, bounds, real_targets, real_cameras, args.fit_size, args.max_evaluations)
    write("real-fit-log.json", real_log)
    real_structure = structural("real-after", real_after)
    real_high_targets, real_high_images = [], []
    for view in ["front", "back", "right"]:
        image, mask, _ = segment_reference(REFERENCE / f"{view}.png", evaluate_size)
        real_high_images.append(image)
        real_high_targets.append(mask)
    real_high_cameras = [{k: v * evaluate_size / args.fit_size if k in ["scale", "cx", "cy"] else v for k, v in c.items()} for c in real_cameras]
    real_metrics, real_panels = evaluate_views("real", before, real_after, real_high_targets, real_high_cameras, ["front", "back", "right"], real_high_images)
    collage(real_panels, "Product target experiment — AI views / approximate cameras", "real-comparison.png")
    sources = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in Path(__file__).parent.glob("*.py")}
    summary = {
        "status": "FEASIBILITY_EXPERIMENT_ONLY", "real_garment_accepted": False,
        "synthetic": {"scope": "same-family synthetic masks; pipeline sanity check, not independent real reconstruction",
                      "target_params": target_params, "fit": log, "structural": synthetic_structure, "views": synthetic_metrics},
        "product_target": {"scope": "existing AI-generated views, heuristic masks and approximate fixed cameras",
                           "fit": real_log, "structural": real_structure, "views": real_metrics,
                           "all_outline_candidate_thresholds_passed": all(v["after"]["passed_candidate_threshold"] for v in real_metrics)},
        "defects": defects, "base_structural": base_structure,
        "unimplemented": ["independent real multi-view benchmark", "automatic physical camera calibration", "general cloth self-intersection validation",
                          "detailed neck/sleeve anatomy acceptance", "real fabric reconstruction", "UV/print/export acceptance", "production queue integration"],
        "runtime": {"python": sys.version.split()[0], "platform": platform.platform(), "numpy": np.__version__, "scipy": scipy.__version__,
                    "opencv": cv2.__version__, "fit_size": args.fit_size, "evaluation_size": evaluate_size,
                    "elapsed_seconds": time.monotonic() - started, "external_ai_calls": 0, "source_sha256": sources},
    }
    write("summary.json", summary)
    print(json.dumps({"result": str(OUT / "summary.json"), "real_all_outline_thresholds": summary["product_target"]["all_outline_candidate_thresholds_passed"],
                      "synthetic_ious": [v["after"]["iou"] for v in synthetic_metrics], "real_ious": [v["after"]["iou"] for v in real_metrics],
                      "elapsed_seconds": summary["runtime"]["elapsed_seconds"]}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
