#!/usr/bin/env python3
"""Check a frozen v2 garment from ten directions without refitting geometry.

Projection availability, reference agreement and physical reconstruction are
separate outcomes. Oblique AI top/bottom images cannot certify axial views.
"""
from __future__ import annotations

import hashlib
import json
import math
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np

from geometry import DEFAULT_PARAMS as V1_DEFAULTS
from image_fit import digest, project, reference_camera, render_mask, segment_reference, silhouette_metrics, overlay

ROOT = Path(__file__).resolve().parent.parent
UPGRADE = ROOT / "output/garment-upgrade-20260911"
OUT = ROOT / "output/garment-multiview-20260911"
SIZE = 512
VIEWS = [
    ("front", "正面", 0, 0), ("back", "背面", 180, 0),
    ("right", "右侧", 90, 0), ("left", "左侧", 270, 0),
    ("top", "俯视", 0, 90), ("bottom", "仰视", 0, -90),
    ("front-right", "右前斜角", 45, 12), ("back-right", "右后斜角", 135, 12),
    ("back-left", "左后斜角", 225, 12), ("front-left", "左前斜角", 315, 12),
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n")


def webpath(path):
    return "/" + path.relative_to(ROOT).as_posix()


def standard_camera(yaw, pitch):
    # Common center/scale across all ten directions; not fitted to a reference.
    scale = SIZE * .43
    return {"yaw": yaw, "pitch": pitch, "scale": scale, "cx": SIZE / 2,
            "cy": SIZE / 2 + .65 * math.cos(math.radians(pitch)) * scale,
            "calibration": "fixed model inspection camera, not reference calibration"}


def projection_check(mesh, camera, mask):
    xy = project(mesh["vertices"], camera)
    finite = bool(np.isfinite(xy).all())
    clipped = bool(not finite or np.any(xy < 1) or np.any(xy > mask.shape[0] - 2))
    pixels = int(np.count_nonzero(mask))
    return {"status": "renderable" if finite and not clipped and pixels else "failed",
            "foregroundPixels": pixels, "clipped": clipped, "finiteProjection": finite,
            "geometryAcceptance": "unevaluated",
            "scope": "finite, nonempty, in-frame projection; does not prove correct shape or visible openings"}


def reference_check(metrics, comparable, reason, path=None, camera=None, used=False):
    # Incomparable or missing evidence never becomes a pass via rasterization.
    status = "unevaluated"
    if comparable and metrics is not None:
        status = "passed" if metrics["passed_candidate_threshold"] else "failed"
    return {"status": status, "reason": reason, "metrics": metrics if comparable else None,
            "referencePath": webpath(path) if path else None, "camera": camera,
            "usedInGeometryFit": used, "physicalReconstructionAccepted": False}


def coverage(views):
    axes = {"front", "back", "right", "left", "top", "bottom"}
    by_id = {v["id"]: v for v in views}
    return {"requiredAxes": sorted(axes),
            "passed": [v["id"] for v in views if v["referenceCheck"]["status"] == "passed"],
            "failed": [v["id"] for v in views if v["referenceCheck"]["status"] == "failed"],
            "unevaluated": [v["id"] for v in views if v["referenceCheck"]["status"] == "unevaluated"],
            "allSixReferenceThresholdsPassed": all(
                i in by_id and by_id[i]["referenceCheck"]["status"] == "passed" for i in axes),
            "physicalReconstructionAccepted": False}


def check_process(script, mesh_path, output_name):
    path = OUT / output_name
    path.unlink(missing_ok=True)
    result = subprocess.run([sys.executable, str(Path(__file__).with_name(script)), str(mesh_path),
                             "--output", str(path)], capture_output=True, text=True)
    if result.returncode not in (0, 1) or not path.exists():
        raise RuntimeError(f"{script} did not produce a valid report: {result.stderr}")
    report = json.loads(path.read_text())
    if report.get("inputSha256", report.get("meshSourceSha256")) != sha(mesh_path):
        raise RuntimeError("Check report does not match frozen mesh")
    return report


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    mesh_path = UPGRADE / "real-after.json"
    frozen_sha = sha(mesh_path)
    prior = json.loads((UPGRADE / "summary.json").read_text())
    if prior["collision"]["inputSha256"] != frozen_sha:
        raise ValueError("Upgrade summary is stale; regenerate it before validating another mesh")
    mesh = json.loads(mesh_path.read_text())
    structure = check_process("evaluator.py", mesh_path, "structure-report.json")
    collision = check_process("collision_check.py", mesh_path, "collision-report.json")
    aperture_report = check_process("aperture_check.py", mesh_path, "aperture-report.json")
    if not structure["implementedStructuralChecksPassed"]:
        raise ValueError("Mesh failed structural checks; see structure-report.json")

    reference_inputs = {}
    # Copy frozen scoring inputs; these are unchanged from the geometry fit.
    old = {v["view"]: v for v in prior["product_target"]["views"]}
    original_references = {r["view"]: r for r in json.loads((UPGRADE / "reference-manifest.json").read_text())["references"]}
    for view in ("front", "back", "right"):
        source = ROOT / "output/shirt-four-view-20260911" / f"{view}.png"
        mask_path = UPGRADE / f"real-{view}-target-mask.png"
        if sha(mask_path) != prior["evidence"]["mask_sha256"][mask_path.name]:
            raise ValueError("Frozen reference mask changed: " + view)
        if sha(source) != original_references[view]["sha256"]:
            raise ValueError("Reference image changed since fitting: " + view)
        reference_inputs[view] = {"sourcePath": webpath(source), "sourceSha256": sha(source),
                                  "maskSha256": sha(mask_path), "camera": old[view]["camera"],
                                  "policy": "reuse frozen v1/v2 camera, mask and thresholds"}

    left_path = ROOT / "output/shirt-four-view-20260911/left.png"
    _, low_left, _ = segment_reference(left_path, 160)
    left_image, left_mask, left_meta = segment_reference(left_path, SIZE)
    low_camera = reference_camera(low_left, V1_DEFAULTS["body_length"], 270)
    left_camera = {k: v * SIZE / 160 if k in ("scale", "cx", "cy") else v for k, v in low_camera.items()}
    left_camera["calibration"] = "v1 bbox-height protocol with fixed original body-length assumption; new AI left view"
    cv2.imwrite(str(OUT / "left-target-mask.png"), left_mask)
    reference_inputs["left"] = {"sourcePath": webpath(left_path), "sourceSha256": left_meta["sha256"],
                                "maskSha256": sha(OUT / "left-target-mask.png"), "camera": left_camera,
                                "assumedBodyLength": V1_DEFAULTS["body_length"],
                                "policy": "same v1 reference-only bbox protocol; no camera or geometry optimization"}
    for view in ("top", "bottom"):
        path = ROOT / "output/shirt-top-bottom-20260911" / f"{view}.png"
        reference_inputs[view] = {"sourcePath": webpath(path), "sourceSha256": sha(path),
                                  "camera": None, "policy": "oblique AI reference, not comparable to axial inspection camera"}
    manifest = {"meshSha256": frozen_sha, "geometryRefitted": False, "references": reference_inputs,
                "inspectionViews": [{"id": v, "camera": standard_camera(y, p)} for v, _, y, p in VIEWS],
                "thresholds": {"iouMin": .95, "boundaryP95Max": .015}}
    # Written before scores are computed, with no per-view alignment to candidate.
    write("reference-manifest.json", manifest)

    views = []
    for view, label, yaw, pitch in VIEWS:
        inspect_camera = standard_camera(yaw, pitch)
        mask = render_mask(mesh, inspect_camera, SIZE)
        cv2.imwrite(str(OUT / f"{view}-mask.png"), mask)
        overlay_path = None
        if view in old or view == "left":
            source = ROOT / reference_inputs[view]["sourcePath"].lstrip("/")
            if view == "left":
                image, target, camera = left_image, left_mask, left_camera
                reason = "新增未参与几何拟合的 AI 左侧参考；相机沿用 v1 近似协议，非实拍验收。"
            else:
                image = cv2.resize(cv2.imread(str(source)), (SIZE, SIZE), interpolation=cv2.INTER_AREA)
                target = cv2.imread(str(UPGRADE / f"real-{view}-target-mask.png"), 0)
                camera = old[view]["camera"]
                reason = "沿用此前参考、掩膜、相机和阈值；该视角已参与几何拟合。"
            rendered = render_mask(mesh, camera, SIZE)
            metrics = silhouette_metrics(target, rendered)
            if view in old and metrics != old[view]["after"]:
                raise RuntimeError("Frozen reference scores changed: " + view)
            overlay_path = OUT / f"{view}-overlay.png"
            cv2.imwrite(str(overlay_path), overlay(image, target, rendered))
            cv2.imwrite(str(OUT / f"{view}-reference-render-mask.png"), rendered)
            ref = reference_check(metrics, True, reason, source, camera, view in old)
        elif view in ("top", "bottom"):
            path = ROOT / reference_inputs[view]["sourcePath"].lstrip("/")
            ref = reference_check(None, False, "已有图片为斜俯视/斜仰视，不能作为正上/正下轮廓真值；仅补检模型结构。", path)
        else:
            ref = reference_check(None, False, "没有对应角度的独立参考；仅检查模型渲染与结构，不能判断还原通过。")
        views.append({"id": view, "label": label, "yaw": yaw, "pitch": pitch,
                      "inspectionCamera": inspect_camera, "referenceCheck": ref,
                      "projectionCheck": projection_check(mesh, inspect_camera, mask),
                      "assets": {"mask": webpath(OUT / f"{view}-mask.png"),
                                 "overlay": webpath(overlay_path) if overlay_path else None}})

    aperture_path = OUT / "aperture-report.json"
    aperture = {"status": aperture_report["status"], "reportPath": webpath(aperture_path),
                "actualOpeningsNondegenerate": aperture_report["allActualOpeningsNumericallyNondegenerate"],
                "scope": "实际四开口的三维边界及投影描述；不验证背景可见、领边还原或料厚。"}
    summary = {"schemaVersion": 1, "status": "MULTIVIEW_VALIDATION", "realGarmentAccepted": False,
               "mesh": {"path": webpath(mesh_path), "sha256": frozen_sha}, "geometryRefitted": False,
               "thresholds": manifest["thresholds"], "views": views, "coverage": coverage(views),
               "structure": {"status": "passed", "reportPath": webpath(OUT / "structure-report.json")},
               "collision": {"status": collision["scopeStatus"], "reportPath": webpath(OUT / "collision-report.json"),
                             "scope": collision["scope"]}, "aperture": aperture,
               "detailChecks": [
                   {"feature": "large_folds", "label": "大褶皱", "status": "unevaluated", "reason": "当前几何仅拟合外轮廓和体积，未提取或拟合参考褶皱。"},
                   {"feature": "sleeve_seams", "label": "袖子缝线", "status": "unevaluated", "reason": "袖根共享顶点只证明连接；未还原参考缝线的可见形状。"},
                   {"feature": "collar_hem_binding", "label": "领边与下摆包边", "status": "unevaluated", "reason": "边界开放，尚无真实包边、内壁或车线。"},
                   {"feature": "fabric_microstructure", "label": "细布纹", "status": "unevaluated", "reason": "没有参考驱动的法线、凹凸或布料材质。"}],
               "limitations": ["参考图均为 AI 生成，未有独立实拍或物理相机标定。",
                               "正背右已参与拟合，新增左侧只作为 AI 视角补检。",
                               "上下参考为斜视，正上下及四斜角没有可直接评分的参考。",
                               "渲染正常、四开口存在及限定范围无穿插，均不等于服装还原通过。",
                               "未验证布料厚度、领边内壁、褶皱、材料或真实尺寸。"],
               "evidence": {"priorSummarySha256": sha(UPGRADE / "summary.json"),
                            "referenceManifestSha256": sha(OUT / "reference-manifest.json"),
                            "sourceSha256": {name: sha(Path(__file__).with_name(name)) for name in
                                             ("run_multiview.py", "image_fit.py", "evaluator.py", "collision_check.py", "aperture_check.py")}}}
    if sha(mesh_path) != frozen_sha:
        raise RuntimeError("Validation must never alter geometry")
    write("summary.json", summary)
    print(json.dumps({"output": str(OUT), "coverage": summary["coverage"],
                      "left": next(v["referenceCheck"]["metrics"] for v in views if v["id"] == "left"),
                      "projectionFailures": [v["id"] for v in views if v["projectionCheck"]["status"] != "renderable"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
