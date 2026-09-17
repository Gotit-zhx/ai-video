"""Fit a structurally relaxed chest with browser GPU losses and CPU final gates."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import time

import cv2
import numpy as np

from fit_v2 import fit_stage, ScoreBackendError
from gpu_fit_server import RemoteGpuScorer
from image_fit import render_mask, silhouette_metrics

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "output/garment-upgrade-20260911"
MULTI = ROOT / "output/garment-multiview-20260911"


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n")


def read_targets():
    manifest = json.loads((MULTI / "reference-manifest.json").read_text())
    refs = manifest["references"]
    result = []
    for view in ("front", "back", "right", "left"):
        path = MULTI / "left-target-mask.png" if view == "left" else BASE / f"real-{view}-target-mask.png"
        source = ROOT / refs[view]["sourcePath"].lstrip("/")
        for p, expected in ((path, refs[view]["maskSha256"]), (source, refs[view]["sourceSha256"])):
            if hashlib.sha256(p.read_bytes()).hexdigest() != expected:
                raise ValueError("Frozen target changed: " + str(p))
        mask = cv2.imread(str(path), 0)
        if mask is None or mask.shape != (512, 512):
            raise ValueError("Missing frozen target mask")
        result.append({"id": view, "mask": mask, "camera": refs[view]["camera"], "mask_sha256": refs[view]["maskSha256"]})
    return result


def fixed_metrics(mesh, refs):
    return {ref["id"]: silhouette_metrics(ref["mask"], render_mask(mesh, ref["camera"], 512)) for ref in refs}


def passes_nonregression(baseline, candidate):
    # Preserve the old quality criteria, allowing only one tenth of a percent
    # IoU raster fluctuation and .0005 normalized boundary movement per view.
    for view, old in baseline.items():
        new = candidate[view]
        if new["iou"] < old["iou"] - .001 or new["boundary_p95_bbox"] is None:
            return False
        if new["boundary_p95_bbox"] > old["boundary_p95_bbox"] + .0005:
            return False
        if old["passed_candidate_threshold"] and not new["passed_candidate_threshold"]:
            return False
    return True


def supported_chest_guard(mesh):
    """A chest may not be optimized back into the old recessed centre line."""
    from geometry_v3 import sample_front_depth
    p = mesh['params']
    waist, chest = sample_front_depth(mesh, [[0, .405 * p['body_length']], [0, .688 * p['body_length']]])
    return bool(chest >= waist * .965)


def back_profile_curvature(mesh):
    """Actual rear triangle sections, used to reject new transverse ridges."""
    from geometry_v3 import sample_front_depth
    rear = {**mesh, 'face_regions': {**mesh['face_regions'], 'front': mesh['face_regions']['back']}}
    p = mesh['params']
    ys = np.linspace(.40 * p['body_length'], .84 * p['body_length'], 65)
    return np.asarray([np.diff(sample_front_depth(rear, [[fraction * p['torso_width'], y] for y in ys]), n=2)
                       for fraction in (0, .15, .30, .40)])


def run_repair(broker, output):
    from geometry_v3 import build_mesh, measure_chest_trough
    from validate_gpu_repair import validate_candidate

    started = time.monotonic()
    output.mkdir(parents=True, exist_ok=False)
    baseline_bytes = (BASE / "real-after.json").read_bytes()
    baseline = json.loads(baseline_bytes)
    base_hash = hashlib.sha256(baseline_bytes).hexdigest()
    initial = {**baseline["params"], "chest_relaxation": 1.0, "chest_support": 1.0, "back_drape": 1.0}
    refs = read_targets()
    save(output / "initial-params.json", initial)
    source_names = ("geometry_v3.py", "geometry_v2.py", "fit_v2.py", "image_fit.py", "run_gpu_repair.py",
                    "gpu_fit_server.py", "gpu-silhouette.js", "gpu-fit-client.js", "validate_gpu_repair.py")
    sources = {name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest() for name in source_names}
    size = 256
    cameras = [{**ref["camera"], "id": ref["id"], **{key: ref["camera"][key] * size / 512
                for key in ("scale", "cx", "cy")}} for ref in refs]
    targets = [cv2.resize(ref["mask"], (size, size), interpolation=cv2.INTER_NEAREST) for ref in refs]
    builder = lambda p: build_mesh(p, resolution="fit", depth_backend="numpy")
    # Chest repair is a geometric constraint, not a silhouette-only free knob:
    # the front silhouette cannot observe the interior axillary groove.
    names = ["depth", "chest_depth", "waist_depth", "body_lean"]
    spans = [.018, .10, .10, .012]
    from geometry_v2 import PARAM_BOUNDS
    bounds = [(max(PARAM_BOUNDS[n][0], initial[n] - d), min(PARAM_BOUNDS[n][1], initial[n] + d))
              for n, d in zip(names, spans)]
    budget = 240
    backend, fallback_reason, scorer = "webgpu", None, None
    def progress(value):
        broker.update(phase="fitting", backend=backend, progress=.08 + .64 * value["evaluations"] / budget, **value)
    broker.update(phase="connecting_gpu", backend=backend, progress=.03)
    try:
        scorer = RemoteGpuScorer(broker, builder(initial), targets, cameras, size)
        params, trace = fit_stage(builder, initial, names, bounds, targets, cameras, size,
                                 budget, "chest_volume", score_meshes=scorer, on_progress=progress)
    except ScoreBackendError as error:
        # Restart from the same initial geometry; GPU and OpenCV edge rules differ.
        fallback_reason, backend = str(error), "cpu_fallback"
        broker.update(phase="restarting_cpu", backend=backend, fallbackReason=fallback_reason)
        params, trace = fit_stage(builder, initial, names, bounds, targets, cameras, size,
                                 budget, "chest_volume_cpu_restart", on_progress=progress)
    save(output / "fit-trace.json", trace)
    broker.update(phase="independent_validation", progress=.75)
    baseline_metrics = fixed_metrics(baseline, refs)
    supported_metrics = fixed_metrics(build_mesh(initial), refs)
    supported_curvature = np.abs(back_profile_curvature(build_mesh(initial)))
    baseline_trough = measure_chest_trough(baseline)["maximumTroughDepth"]
    # GPU scores drive candidate search. Final selection retains an independently
    # scored candidate only if it preserves all four frozen view constraints.
    candidates = [("structural_repair_start", initial)]
    improvements = [entry for entry in trace["trace"] if entry.get("best_so_far")]
    candidates.extend((f"fit_evaluation_{entry['evaluation']}", entry["params"]) for entry in improvements[-12:])
    decisions, eligible = [], []
    seen = set()
    for label, values in candidates:
        key = json.dumps(values, sort_keys=True)
        if key in seen:
            continue
        seen.add(key)
        mesh = build_mesh(values, resolution="preview", depth_backend="numpy")
        metrics = fixed_metrics(mesh, refs)
        # The user requested a supported chest: the old flattened side outline
        # must not veto that structural change. Keep original scores as evidence,
        # but reject search refinements that degrade the supported starting mesh.
        view_pass = passes_nonregression(supported_metrics, metrics)
        trough = measure_chest_trough(mesh)["maximumTroughDepth"]
        chest_pass = trough <= baseline_trough * .5 and supported_chest_guard(mesh)
        from evaluator import evaluate_mesh
        structure = evaluate_mesh(mesh)
        structure_pass = structure['implementedStructuralChecksPassed']
        back_pass = bool(np.all(np.abs(back_profile_curvature(mesh)) <= supported_curvature * 1.15 + .0002 * initial['torso_width']))
        passes = view_pass and chest_pass and structure_pass and back_pass
        decision = {"candidate": label, "params": values, "metrics": metrics,
                    "passes_frozen_view_guard": view_pass, "passes_chest_repair_guard": chest_pass,
                    "passes_structure_guard": structure_pass, "structureFailures": structure['failedChecks'],
                    "passes_back_smoothness_guard": back_pass,
                    "maximumTroughDepth": trough}
        decisions.append(decision)
        if passes:
            eligible.append((sum(m["iou"] for m in metrics.values()), label, values, mesh))
    save(output / "final-selection.json", {"baseline": baseline_metrics, "supportedBaseline": supported_metrics, "candidates": decisions,
              "policy": "Preserve supported starting shape during search; original reference scores remain reported, not redefined"})
    if not eligible:
        raise ValueError("No chest-repair candidate preserved the frozen four-view constraints; original remains active")
    _, selected, params, final = max(eligible, key=lambda row: row[0])
    mesh_path = output / "mesh.json"
    save(mesh_path, final)
    validation = validate_candidate(mesh_path, output)
    chest_evidence = json.loads((output / "validation/chest-sections.json").read_text())
    chest_before = chest_evidence.get("baseline", {}).get("maximumTroughDepth")
    chest_after = chest_evidence.get("candidate", {}).get("maximumTroughDepth")
    broker.update(phase="publishing_result", progress=.96)
    unchanged = all(hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest() == expected
                    for name, expected in sources.items())
    if not unchanged or hashlib.sha256((BASE / "real-after.json").read_bytes()).hexdigest() != base_hash:
        raise ValueError("Source or baseline changed during fitting; candidate retained for review, not activated")
    measurements = scorer.measurements if scorer else []
    summary = {"status": "GPU_DRIVEN_CHEST_REPAIR", "backend": backend, "fallbackReason": fallback_reason,
               "baseMeshSha256": base_hash, "meshSha256": hashlib.sha256(mesh_path.read_bytes()).hexdigest(),
               "elapsedSeconds": time.monotonic() - started, "gpuAdapter": broker.adapter,
               "gpuEvaluatedCandidates": len(measurements), "gpuRequests": broker.history,
               "gpuMeasurements": measurements, "fitEvaluations": trace["evaluations"],
               "initialLoss": trace["initial_loss"], "bestLoss": trace["best_loss"],
               "selectedCandidate": selected, "params": params, "sourceSha256": sources,
               "chestSupport": final.get("chestSupport"), "backDrape": final.get("backDrape"),
               "chest": {"beforeMaximumTrough": chest_before, "afterMaximumTrough": chest_after,
                         "measurement": "independent fixed-world triangle sections",
                         "relativeReduction": 1 - chest_after / chest_before if chest_before and chest_after is not None else None},
               "qualityChecks": validation, "realGarmentAccepted": False,
               "limits": ["Approximate cameras and AI references remain unchanged.",
                          "GPU computes candidate weighted losses; Python builds meshes and updates parameters.",
                          "Final four-view and topology checks use the original CPU protocol."]}
    save(output / "fit-summary.json", summary)
    # Only independently accepted structural checks may become the default preview.
    if (validation.get("structure", {}).get("status") != "passed"
            or validation.get("collision", {}).get("status") != "passed"
            or validation.get("aperture", {}).get("actualOpeningsNondegenerate") is not True
            or chest_before is None or chest_after is None or chest_after > chest_before * .5):
        raise ValueError("Independent structural validation rejected candidate; original remains active")
    relative = "/" + output.relative_to(ROOT).as_posix()
    latest = {"schemaVersion": 1, "jobId": output.name, "meshUrl": relative + "/mesh.json",
              "modelLabel": "胸腔支撑 · GPU 拟合",
              "reportUrl": relative + "/repair-validation.json", "fitUrl": relative + "/fit-summary.json",
              "meshSha256": summary["meshSha256"], "backend": backend}
    temp = ROOT / "output/garment-gpu-repair-latest.tmp"
    save(temp, latest)
    temp.replace(ROOT / "output/garment-gpu-repair-latest.json")
    return {"resultUrl": "/garment-reconstruction/viewer.html?version=2&model=gpu-repaired",
            "reportUrl": latest["reportUrl"], "fitUrl": latest["fitUrl"], "backend": backend,
            "selectedCandidate": selected, "elapsedSeconds": summary["elapsedSeconds"]}
