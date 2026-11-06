#!/usr/bin/env python3
"""Upgrade experiment. The v1 reference masks, cameras and thresholds stay fixed."""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import shutil
import subprocess
import sys
import time
from pathlib import Path

import cv2
import numpy as np

from geometry_v2 import DEFAULT_PARAMS, PARAM_BOUNDS, build_mesh
from fit_v2 import fit_stage
from image_fit import digest, render_mask, silhouette_metrics, overlay
from run_validation import collage
import run_validation

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "output/garment-validation-20260911"
OUT = ROOT / "output/garment-upgrade-new-run"


def save(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n")


def checker(script, mesh_name, output_name):
    destination = OUT / output_name
    if destination.exists():
        destination.unlink()
    result = subprocess.run([sys.executable, str(Path(__file__).with_name(script)), str(OUT / mesh_name),
                             "--output", str(destination)], text=True, capture_output=True)
    if not destination.exists():
        raise RuntimeError(f"{script} did not produce evidence: {result.stderr}")
    return json.loads(destination.read_text())


def scale_cameras(cameras, size):
    return [{k: value * size / 160 if k in ["scale", "cx", "cy"] else value
             for k, value in c.items()} for c in cameras]


def main():
    global OUT
    parser = argparse.ArgumentParser()
    parser.add_argument("--coarse-budget", type=int, default=1800)
    parser.add_argument("--volume-budget", type=int, default=1000)
    parser.add_argument("--refine-budget", type=int, default=2000)
    parser.add_argument("--initial-params", type=Path)
    parser.add_argument("--output-dir", type=Path, default=OUT)
    parser.add_argument("--depth-backend", choices=("scalar", "numpy"), default="numpy")
    args = parser.parse_args()
    OUT = args.output_dir.resolve()
    if OUT.exists() and any(OUT.iterdir()):
        parser.error("Output directory must be empty; choose --output-dir to preserve earlier model evidence")
    OUT.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    source_hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in Path(__file__).parent.glob("*.py")}
    reference = json.loads((BASE / "reference-manifest.json").read_text())
    for ref in reference["references"]:
        actual = ROOT / "output/shirt-four-view-20260911" / f"{ref['view']}.png"
        if hashlib.sha256(actual.read_bytes()).hexdigest() != ref["sha256"]:
            raise ValueError("Reference changed since v1; not a like-for-like upgrade")
    assert digest(reference["cameras"]) == reference["cameraHash"]
    # Baseline is the previous fitted model, not a deliberately worse initializer.
    shutil.copyfile(BASE / "real-after.json", OUT / "real-before.json")
    shutil.copyfile(BASE / "reference-manifest.json", OUT / "reference-manifest.json")
    before = json.loads((OUT / "real-before.json").read_text())
    targets = [cv2.imread(str(BASE / f"real-{view}-target-mask.png"), 0) for view in ["front", "back", "right"]]
    if any(t is None or t.shape != (512, 512) for t in targets):
        raise ValueError("Frozen 512px target masks are missing")
    cameras = reference["cameras"]
    evidence_hashes = {f"real-{view}-target-mask.png": hashlib.sha256((BASE / f"real-{view}-target-mask.png").read_bytes()).hexdigest()
                       for view in ["front", "back", "right"]}
    low_build = lambda params: build_mesh(params, resolution="fit", depth_backend=args.depth_backend)
    preview_build = lambda params: build_mesh(params, depth_backend=args.depth_backend)
    params = {**DEFAULT_PARAMS, "torso_width": .74, "body_length": 1.24, "depth": .38,
              "sleeve_length": .27, "sleeve_drop": .22, "neck_width": .32,
              "neck_depth": .14, "back_neck_depth": .045, "cuff_width": .26, "body_lean": -.04,
              "chest_depth": 1.0, "waist_depth": 1.3, "hem_depth": 1.55}
    if args.initial_params:
        params.update(json.loads(args.initial_params.read_text()))
    # An external silhouette cannot identify neck anatomy. These explicit,
    # uncertain image landmarks come from visual inspection of this unchanged
    # AI target, not a physical measurement or a general automatic segmenter.
    native_scale = reference["cameras"][0]["scale"] * 1024 / 160
    collar = {"referenceSha256": reference["references"][0]["sha256"],
              "source": "assistant visual annotation of AI-generated front.png, 1024px",
              "left": [424, 100], "right": [623, 100], "frontLowest": [522, 169],
              "estimatedPixelUncertainty": 12, "physicalMeasurement": False,
              "neckWidth": 199 / native_scale, "frontNeckDepth": 69 / native_scale,
              "fitPolicy": "Keep these two relative geometric dimensions fixed; do not shrink the neck for silhouette score."}
    params.update(neck_width=collar["neckWidth"], neck_depth=collar["frontNeckDepth"])
    save("collar-observations.json", collar)
    save("initial-params-used.json", params)
    save("v2-initial.json", preview_build(params))
    stages = []
    plans = [
        ("shoulder_sleeve_outline", ["torso_width", "body_length", "sleeve_length", "sleeve_drop", "cuff_width",
                                    "cuff_tilt", "shoulder_drop", "back_neck_depth", "hem_flare", "body_taper"],
         [0, 1], 160, args.coarse_budget),
        ("independent_volume_profile", ["depth", "chest_depth", "waist_depth", "hem_depth", "body_lean", "neck_setback"],
         [2], 160, args.volume_budget),
        ("all_views_refinement", ["torso_width", "body_length", "sleeve_length", "sleeve_drop", "cuff_width", "cuff_tilt",
                                  "shoulder_drop", "back_neck_depth", "hem_flare", "body_taper",
                                  "depth", "chest_depth", "waist_depth", "hem_depth", "body_lean", "neck_setback"],
         [0, 1, 2], 224, args.refine_budget),
    ]
    for stage, names, indices, size, budget in plans:
        print(f"Running {stage}: {len(names)} parameters, budget {budget}", flush=True)
        downsampled = [cv2.resize(targets[i], (size, size), interpolation=cv2.INTER_NEAREST) for i in indices]
        stage_cameras = [scale_cameras(cameras, size)[i] for i in indices]
        params, log = fit_stage(low_build, params, names, [PARAM_BOUNDS[n] for n in names],
                                downsampled, stage_cameras, size, budget, stage)
        save(f"{stage}-trace.json", log)
        save(f"{stage}-mesh.json", preview_build(params))
        stages.append({k: value for k, value in log.items() if k != "trace"})
        print(f"{stage}: loss {log['initial_loss']:.5f} -> {log['best_loss']:.5f}; {log['evaluations']} evaluations", flush=True)
    after = preview_build(params)
    save("real-after.json", after)
    structure = checker("evaluator.py", "real-after.json", "real-after-structure.json")
    collision = checker("collision_check.py", "real-after.json", "real-after-collision.json") if Path(__file__).with_name("collision_check.py").exists() else None
    high_cameras = scale_cameras(cameras, 512)
    views, panels = [], []
    for view, target, camera in zip(["front", "back", "right"], targets, high_cameras):
        first, last = render_mask(before, camera, 512), render_mask(after, camera, 512)
        record = {"view": view, "before": silhouette_metrics(target, first), "after": silhouette_metrics(target, last), "camera": camera}
        views.append(record)
        raw = cv2.imread(str(ROOT / f"output/shirt-four-view-20260911/{view}.png"))
        background = cv2.resize(raw, (512, 512), interpolation=cv2.INTER_AREA)
        row = []
        for label, mask in [("before", first), ("after", last)]:
            pic = overlay(background, target, mask)
            cv2.imwrite(str(OUT / f"real-{view}-{label}-mask.png"), mask)
            cv2.imwrite(str(OUT / f"real-{view}-{label}-overlay.png"), pic)
            row.append((f"{view} / {'v1' if label == 'before' else 'v2'}: IoU {record[label]['iou']:.3f}", pic))
        shutil.copyfile(BASE / f"real-{view}-target-mask.png", OUT / f"real-{view}-target-mask.png")
        panels.append(row)
    run_validation.OUT = OUT
    collage(panels, "Geometry upgrade — identical target masks and cameras", "real-comparison.png")
    # The v1 score is recomputed from its original mesh with the same cameras.
    original = json.loads((BASE / "summary.json").read_text())
    for actual, prior in zip(views, original["product_target"]["views"]):
        if abs(actual["before"]["iou"] - prior["after"]["iou"]) > 1e-12:
            raise RuntimeError("Baseline raster/metric changed; comparison is invalid")
    summary = {
        "version": 2, "status": "GEOMETRY_UPGRADE_EXPERIMENT", "real_garment_accepted": False,
        "baseline": {"path": str(BASE), "mesh_sha256": hashlib.sha256((BASE / "real-after.json").read_bytes()).hexdigest()},
        "product_target": {"scope": "Same existing AI-generated target views, frozen v1 approximate cameras and 512px masks",
                           "fit": {"params": params, "stages": stages, "evaluations": sum(s["evaluations"] for s in stages)},
                           "views": views, "structural": {"implemented_checks_passed": structure["implementedStructuralChecksPassed"],
                                                            "failed_checks": structure["failedChecks"], "garment_acceptance": structure["garmentAcceptance"]},
                           "all_outline_candidate_thresholds_passed": all(v["after"]["passed_candidate_threshold"] for v in views)},
        "collision": collision,
        "collar_observations": collar,
        "improvements": ["curved shoulders", "cuffs follow sleeve direction", "height-dependent torso depth", "independent torso/sleeve controls"],
        "unimplemented": ["independent real-view benchmark", "calibrated physical dimensions", "fabric microstructure", "material thickness",
                          "production queue and UV/print export acceptance"],
        "evidence": {"camera_hash": reference["cameraHash"], "mask_sha256": evidence_hashes, "source_sha256": source_hashes,
                     "decision": "No real garment acceptance; scalar outline metrics and limited collision checks only"},
        "runtime": {"elapsed_seconds": time.monotonic() - started, "python": sys.version.split()[0], "platform": platform.platform(),
                    "external_ai_calls": 0, "evaluation_size": 512, "depth_backend": args.depth_backend},
    }
    save("summary.json", summary)
    print(json.dumps({"output": str(OUT), "views": views, "structural_failed": structure["failedChecks"],
                      "elapsed_seconds": summary["runtime"]["elapsed_seconds"]}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
