#!/usr/bin/env python3
"""Validate one GPU repair candidate against frozen CPU evidence, without refitting.

Each invocation owns a new output_dir/validation directory. Existing job mesh and
trace files are preserved; a previous validation is never silently overwritten.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

import cv2
import numpy as np

from image_fit import overlay, project, render_mask, silhouette_metrics
from run_multiview import VIEWS, coverage, projection_check

ROOT = Path(__file__).resolve().parent.parent
UPGRADE = ROOT / "output/garment-upgrade-20260911"
MULTIVIEW = ROOT / "output/garment-multiview-20260911"
SIZE = 512
BASELINE_SHA = "4f318e80f2b02c6dda6b33e43b3a0a4a9046b0354ea7323c19745fffb0082c68"
MANIFEST_SHA = "81f281dcfe5d81752fb6f3348ffea6283af9a4b4b1c010b5af305143edbdf617"
THRESHOLDS = {"iouMin": .95, "boundaryP95Max": .015}
# World coordinates frozen from the original v2 mesh, never candidate params.
BASE_LENGTH, BASE_WIDTH = 1.2348586742679608, .6789917005803934
CHEST_BAND = (.6100352973706493, .942741230655042)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def asset_path(path):
    path = Path(path).resolve()
    return "/" + path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else str(path)


def write(path, value):
    with Path(path).open("x") as stream:
        stream.write(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n")


def frozen_inputs():
    manifest_path = MULTIVIEW / "reference-manifest.json"
    if sha(manifest_path) != MANIFEST_SHA or sha(UPGRADE / "real-after.json") != BASELINE_SHA:
        raise ValueError("Frozen reference manifest or baseline mesh changed")
    manifest = json.loads(manifest_path.read_text())
    if manifest["thresholds"] != THRESHOLDS:
        raise ValueError("Frozen thresholds changed")
    inputs = {}
    for name, ref in manifest["references"].items():
        source = ROOT / ref["sourcePath"].lstrip("/")
        if sha(source) != ref["sourceSha256"]:
            raise ValueError("Frozen reference source changed: " + name)
        item = {"source": source, "reference": ref}
        if name in ("front", "back", "right", "left"):
            mask = MULTIVIEW / "left-target-mask.png" if name == "left" else UPGRADE / f"real-{name}-target-mask.png"
            if sha(mask) != ref["maskSha256"]:
                raise ValueError("Frozen reference mask changed: " + name)
            item["mask"] = cv2.imread(str(mask), cv2.IMREAD_GRAYSCALE)
            item["image"] = cv2.resize(cv2.imread(str(source)), (SIZE, SIZE), interpolation=cv2.INTER_AREA)
            if item["mask"] is None or item["mask"].shape != (SIZE, SIZE):
                raise ValueError("Invalid frozen reference mask: " + name)
        inputs[name] = item
    return manifest, inputs


def run_checker(script, mesh_path, report_path, expected_sha):
    command = [sys.executable, str(Path(__file__).with_name(script)), str(mesh_path), "--output", str(report_path)]
    if script == "aperture_check.py":
        command.extend(["--expected-sha256", expected_sha])
    error, result = None, None
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=180)
    except (OSError, subprocess.TimeoutExpired) as exc:
        error = f"{type(exc).__name__}: {exc}"
    report = None
    if report_path.exists():
        try:
            report = json.loads(report_path.read_text())
        except (ValueError, OSError) as exc:
            error = f"Invalid checker report: {exc}"
    observed = report.get("inputSha256", report.get("meshSourceSha256")) if isinstance(report, dict) else None
    verified = bool(result is not None and result.returncode in (0, 1) and observed == expected_sha)
    if not verified:
        error = error or "Checker crashed, rejected input, or did not identify the frozen candidate"
    execution = {"script": script, "returnCode": result.returncode if result else None,
                 "inputSha256": expected_sha, "reportInputSha256": observed,
                 "reportVerified": verified, "error": error,
                 "stderr": result.stderr[-6000:] if result and result.stderr else None,
                 "reportSha256": sha(report_path) if report_path.exists() else None}
    write(report_path.with_name(report_path.stem + "-execution.json"), execution)
    return report or {}, execution


def triangle_data(mesh):
    vertices = np.asarray(mesh["vertices"], dtype=float)
    faces = np.asarray(mesh["faces"])
    if (vertices.ndim != 2 or vertices.shape[1] != 3 or not len(vertices)
            or not np.isfinite(vertices).all() or faces.ndim != 2 or faces.shape[1] != 3
            or not len(faces) or not np.issubdtype(faces.dtype, np.integer)
            or faces.min() < 0 or faces.max() >= len(vertices)):
        raise ValueError("Finite vertices and valid triangle indices are required")
    return vertices[faces]


def fixed_chest_measurements(mesh):
    """Intersect actual triangles in a fixed world ROI; ignore relabelable groups."""
    triangles = triangle_data(mesh)
    a, b, c = triangles[:, 0], triangles[:, 1], triangles[:, 2]
    denominator = (b[:, 1] - c[:, 1]) * (a[:, 0] - c[:, 0]) + (c[:, 0] - b[:, 0]) * (a[:, 1] - c[:, 1])
    usable = np.abs(denominator) > 1e-14
    a, b, c, denominator = a[usable], b[usable], c[usable], denominator[usable]

    def sample(x, y):
        w0 = ((b[:, 1] - c[:, 1]) * (x - c[:, 0]) + (c[:, 0] - b[:, 0]) * (y - c[:, 1])) / denominator
        w1 = ((c[:, 1] - a[:, 1]) * (x - c[:, 0]) + (a[:, 0] - c[:, 0]) * (y - c[:, 1])) / denominator
        w2 = 1 - w0 - w1
        inside = (w0 >= -1e-9) & (w1 >= -1e-9) & (w2 >= -1e-9)
        values = np.sort((w0 * a[:, 2] + w1 * b[:, 2] + w2 * c[:, 2])[inside])
        values = values[(values >= -BASE_WIDTH) & (values <= BASE_WIDTH)]
        values = values[np.r_[True, np.diff(values) > 1e-7]] if len(values) else values
        return {"x": float(x), "y": float(y), "intersectionCount": len(values),
                "status": "measured" if len(values) == 2 else "unevaluated",
                "frontZ": float(values[-1]) if len(values) else None,
                "backZ": float(values[0]) if len(values) else None}

    xs = np.array([-.41, -.35, -.30, -.18, 0, .18, .30, .35, .41]) * BASE_WIDTH
    ys = np.linspace(*CHEST_BAND, 65)
    profiles = []
    for x in xs:
        samples = [sample(x, y) for y in ys]
        valid = all(s["status"] == "measured" for s in samples)
        zs = [s["frontZ"] for s in samples]
        profiles.append({"x": float(x), "samples": samples, "status": "measured" if valid else "unevaluated",
                         "troughDepth": float(max(0, min(zs[0], zs[-1]) - min(zs))) if valid else None})
    sections = []
    for fraction in (.42, .55, .65, .75, .82):
        samples = [sample(x, fraction * BASE_LENGTH) for x in (-.25 * BASE_WIDTH, 0, .25 * BASE_WIDTH)]
        valid = all(s["status"] == "measured" for s in samples)
        crown = samples[1]["frontZ"] - (samples[0]["frontZ"] + samples[2]["frontZ"]) / 2 if valid else None
        sections.append({"baselineHeightFraction": fraction, "y": fraction * BASE_LENGTH,
                         "status": "measured" if valid else "unevaluated", "samples": samples,
                         "centerDepth": samples[1]["frontZ"] - samples[1]["backZ"] if valid else None,
                         "frontCrownOverBaselineWidth": crown / BASE_WIDTH if valid else None})
    complete = all(p["status"] == "measured" for p in profiles)
    return {"status": "measured" if complete else "partially_unevaluated", "fixedWorldCoordinates": True,
            "baselineMeshSha256": BASELINE_SHA, "baselineWidth": BASE_WIDTH, "baselineLength": BASE_LENGTH,
            "bandY": list(CHEST_BAND), "zRange": [-BASE_WIDTH, BASE_WIDTH], "profiles": profiles,
            "crossSections": sections, "maximumTroughDepth": max(p["troughDepth"] for p in profiles) if complete else None,
            "physicalReconstructionAccepted": False,
            "scope": "All actual triangles; fixed world X/Y samples and Z range; no semantic regions or candidate parameters.",
            "limitations": ["Front/back extrema do not verify surface visibility or physical cloth thickness.",
                            "Missing or more than two distinct surface intersections remain unevaluated.",
                            "Trough depth and cross-section crown are diagnostics without a physical acceptance threshold."]}


def validate_candidate(mesh_path, output_dir):
    mesh_path, output_dir = Path(mesh_path).resolve(), Path(output_dir).resolve()
    raw = mesh_path.read_bytes()
    expected_sha = hashlib.sha256(raw).hexdigest()
    manifest, inputs = frozen_inputs()
    sources = {name: sha(Path(__file__).with_name(name)) for name in
               ("validate_gpu_repair.py", "run_multiview.py", "image_fit.py", "evaluator.py", "collision_check.py", "aperture_check.py")}
    output_dir.mkdir(parents=True, exist_ok=True)
    summary_path, evidence_dir = output_dir / "repair-validation.json", output_dir / "validation"
    if summary_path.exists():
        raise FileExistsError("Existing validation belongs to a previous run; use a new job directory")
    evidence_dir.mkdir(exist_ok=False)
    snapshot = evidence_dir / "input-mesh.json"
    snapshot.write_bytes(raw)
    write(evidence_dir / "reference-manifest.json", {**manifest, "validationRefittedGeometry": False,
          "frozenBaselineMeshSha256": manifest["meshSha256"], "candidateMeshSha256": expected_sha})
    reports, executions = {}, {}
    for kind, script in (("structure", "evaluator.py"), ("collision", "collision_check.py"), ("aperture", "aperture_check.py")):
        reports[kind], executions[kind] = run_checker(script, snapshot, evidence_dir / f"{kind}-report.json", expected_sha)
    mesh, rendering_error = None, None
    try:
        mesh = json.loads(raw)
        triangle_data(mesh)
        for camera in [v["camera"] for v in manifest["inspectionViews"]] + [inputs[v]["reference"]["camera"] for v in ("front", "back", "right", "left")]:
            xy = project(mesh["vertices"], camera)
            if not np.isfinite(xy).all() or np.max(np.abs(xy)) * 16 >= np.iinfo(np.int32).max - 1:
                raise ValueError("Projection would overflow the authoritative CPU rasterizer")
    except (ValueError, TypeError, KeyError, IndexError, OverflowError) as exc:
        rendering_error = f"Invalid render input: {exc}"
    cameras = {v["id"]: v["camera"] for v in manifest["inspectionViews"]}
    views = []
    for view, label, yaw, pitch in VIEWS:
        camera = cameras[view]
        assets = {"mask": None, "overlay": None, "referenceRenderMask": None}
        projection = {"status": "failed", "reason": rendering_error, "geometryAcceptance": "unevaluated"}
        ref_input = inputs.get(view)
        ref = {"status": "unevaluated", "metrics": None,
               "referencePath": asset_path(ref_input["source"]) if ref_input else None,
               "camera": ref_input["reference"]["camera"] if ref_input else None,
               "usedInGeometryFit": None, "physicalReconstructionAccepted": False,
               "reason": "没有对应角度的独立参考；投影可用不等于还原通过。"}
        if view in ("top", "bottom"):
            ref["reason"] = "现有 AI 图片是斜俯视/斜仰视，无法校验正上/正下参考一致性。"
        if rendering_error is None:
            mask = render_mask(mesh, camera, SIZE)
            mask_path = evidence_dir / f"{view}-mask.png"
            if not cv2.imwrite(str(mask_path), mask):
                raise OSError("Could not write projection mask")
            assets["mask"] = asset_path(mask_path)
            projection = projection_check(mesh, camera, mask)
        if view in ("front", "back", "right", "left"):
            ref["reason"] = rendering_error or "冻结参考、相机、掩膜和阈值；候选经原 CPU 外轮廓协议重新评分。"
            if rendering_error is None:
                rendered = render_mask(mesh, ref["camera"], SIZE)
                metrics = silhouette_metrics(ref_input["mask"], rendered)
                ref.update(metrics=metrics, status="passed" if metrics["passed_candidate_threshold"] else "failed")
                for key, name, pixels in (("overlay", f"{view}-overlay.png", overlay(ref_input["image"], ref_input["mask"], rendered)),
                                          ("referenceRenderMask", f"{view}-reference-render-mask.png", rendered)):
                    path = evidence_dir / name
                    if not cv2.imwrite(str(path), pixels):
                        raise OSError("Could not write scoring evidence")
                    assets[key] = asset_path(path)
        views.append({"id": view, "label": label, "yaw": yaw, "pitch": pitch, "inspectionCamera": camera,
                      "referenceCheck": ref, "projectionCheck": projection, "assets": assets})

    chest = {"status": "unevaluated", "reason": rendering_error, "physicalReconstructionAccepted": False}
    if rendering_error is None:
        candidate_chest = fixed_chest_measurements(mesh)
        baseline_chest = fixed_chest_measurements(json.loads((UPGRADE / "real-after.json").read_bytes()))
        old, new = baseline_chest["maximumTroughDepth"], candidate_chest["maximumTroughDepth"]
        chest = {"status": candidate_chest["status"], "baseline": baseline_chest, "candidate": candidate_chest,
                 "maximumTroughDepthChange": new - old if old is not None and new is not None else None,
                 "physicalReconstructionAccepted": False}
    write(evidence_dir / "chest-sections.json", chest)
    structure, collision, aperture = (reports[k] for k in ("structure", "collision", "aperture"))
    summary = {"schemaVersion": 1, "status": "GPU_REPAIR_VALIDATION", "realGarmentAccepted": False,
               "mesh": {"path": asset_path(mesh_path), "sha256": expected_sha},
               "validationRefittedGeometry": False, "thresholds": THRESHOLDS, "views": views, "coverage": coverage(views),
               "structure": {"status": "passed" if executions["structure"]["reportVerified"] and structure.get("implementedStructuralChecksPassed") else "failed",
                             "failedChecks": structure.get("failedChecks", []), "reportPath": asset_path(evidence_dir / "structure-report.json"),
                             "executionPath": asset_path(evidence_dir / "structure-report-execution.json")},
               "collision": {"status": collision.get("scopeStatus", "unevaluated") if executions["collision"]["reportVerified"] else "unevaluated",
                             "scope": collision.get("scope"), "reportPath": asset_path(evidence_dir / "collision-report.json"),
                             "executionPath": asset_path(evidence_dir / "collision-report-execution.json")},
               "aperture": {"status": aperture.get("status", "unevaluated") if executions["aperture"]["reportVerified"] else "unevaluated",
                            "actualOpeningsNondegenerate": aperture.get("allActualOpeningsNumericallyNondegenerate") if executions["aperture"]["reportVerified"] else None,
                            "reportPath": asset_path(evidence_dir / "aperture-report.json"),
                            "executionPath": asset_path(evidence_dir / "aperture-report-execution.json"),
                            "scope": "实际边界的几何及投影检查；未验证遮挡、内壁、布厚或参考还原。"},
               "chest": {"status": chest["status"], "reportPath": asset_path(evidence_dir / "chest-sections.json"),
                         "maximumTroughDepthChange": chest.get("maximumTroughDepthChange"), "physicalReconstructionAccepted": False},
               "limitations": ["参考图是 AI 生成，无独立实拍或物理标定；所有结果均不授予真实服装还原通过。",
                               "候选是否用过某个参考拟合不能由验收器确认，因此 usedInGeometryFit 为 null。",
                               "上下轴及四斜角的参考一致性仍未评估；外轮廓不验证胸部内表面、褶皱或细节。",
                               "穿插检查只覆盖袖子与躯干的部分三角形对，未完成全布面自交和包容检查。"],
               "evidence": {"frozenReferenceManifestSha256": MANIFEST_SHA, "baselineMeshSha256": BASELINE_SHA,
                            "inputSnapshotPath": asset_path(snapshot), "inputSnapshotSha256": sha(snapshot),
                            "checkerExecutions": executions,
                            "sourceSha256": sources}}
    # Recheck live sources after all scoring; never publish evidence for changed bytes.
    if sha(mesh_path) != expected_sha or sha(snapshot) != expected_sha:
        raise ValueError("Candidate changed during validation")
    if any(sha(Path(__file__).with_name(name)) != expected for name, expected in sources.items()):
        raise ValueError("Validation implementation changed while the checks were running")
    frozen_inputs()
    write(summary_path, summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mesh", type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    summary = validate_candidate(args.mesh, args.output_dir)
    print(json.dumps({"report": str(args.output_dir / "repair-validation.json"), "coverage": summary["coverage"],
                      "structure": summary["structure"]["status"], "collision": summary["collision"]["status"],
                      "chest": summary["chest"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
