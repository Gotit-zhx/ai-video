#!/usr/bin/env python3
"""Independent diagnostic projections of the layered sleeve detail candidate.

Base silhouette scores cannot validate seams, hidden structure or fabric detail.
This script neither changes the candidate nor grants one-to-one reconstruction.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import cv2

from image_fit import render_mask, silhouette_metrics, overlay
from run_multiview import ROOT, VIEWS, SIZE, sha, webpath, standard_camera, projection_check

OUT = ROOT / "output/garment-detail-20260911"
MULTI = ROOT / "output/garment-multiview-20260911"


def main():
    mesh_path = OUT / "sleeve-detail.json"
    base_path = ROOT / "output/garment-upgrade-20260911/real-after.json"
    mesh, base = (json.loads(p.read_text()) for p in (mesh_path, base_path))
    frozen_sha, base_sha = sha(mesh_path), sha(base_path)
    multi = json.loads((MULTI / "summary.json").read_text())
    if multi["mesh"]["sha256"] != base_sha:
        raise ValueError("Base multiview report is stale")
    source_manifest = json.loads((MULTI / "reference-manifest.json").read_text())
    prefix_unchanged = (mesh["vertices"][:len(base["vertices"])] == base["vertices"]
                        and mesh["faces"][:len(base["faces"])] == base["faces"])
    protected_regions = {}
    for region in ("torso", "neck", "hem", "left_sleeve_root", "right_sleeve_root"):
        ids = base["semantic_regions"][region]
        protected_regions[region] = all(mesh["vertices"][i] == base["vertices"][i] for i in ids)
    moved_base = [i for i, p in enumerate(base["vertices"]) if mesh["vertices"][i] != p]
    structure_path = OUT / "detail-full-structure.json"
    structure_path.unlink(missing_ok=True)
    completed = subprocess.run([sys.executable, str(Path(__file__).with_name("evaluator.py")),
                                str(mesh_path), "--output", str(structure_path)], capture_output=True, text=True)
    if completed.returncode not in (0, 1) or not structure_path.exists():
        raise RuntimeError(completed.stderr)
    structure = json.loads(structure_path.read_text())
    if structure["inputSha256"] != frozen_sha:
        raise ValueError("Structural report hash mismatch")
    prior_by_id = {v["id"]: v for v in multi["views"]}
    records = []
    for view, label, yaw, pitch in VIEWS:
        inspect_camera = standard_camera(yaw, pitch)
        mask = render_mask(mesh, inspect_camera, SIZE)
        cv2.imwrite(str(OUT / f"detail-{view}-mask.png"), mask)
        old_ref = prior_by_id[view]["referenceCheck"]
        ref = {"status": "unevaluated", "reason": old_ref["reason"], "metrics": None,
               "detailFidelityStatus": "unevaluated", "referencePath": old_ref["referencePath"]}
        overlay_path = None
        if old_ref["metrics"] is not None:
            source = ROOT / old_ref["referencePath"].lstrip("/")
            if sha(source) != source_manifest["references"][view]["sourceSha256"]:
                raise ValueError("Reference changed: " + view)
            target_path = (MULTI / "left-target-mask.png" if view == "left" else
                           ROOT / "output/garment-upgrade-20260911" / f"real-{view}-target-mask.png")
            if sha(target_path) != source_manifest["references"][view]["maskSha256"]:
                raise ValueError("Reference mask changed: " + view)
            target = cv2.imread(str(target_path), 0)
            rendered = render_mask(mesh, old_ref["camera"], SIZE)
            metrics = silhouette_metrics(target, rendered)
            ref.update(status="passed" if metrics["passed_candidate_threshold"] else "failed", metrics=metrics,
                       camera=old_ref["camera"], baseMetrics=old_ref["metrics"],
                       reason="细节候选独立重算的外轮廓分数；不验证缝线、翻边或内部细节还原。")
            image = cv2.resize(cv2.imread(str(source)), (SIZE, SIZE), interpolation=cv2.INTER_AREA)
            overlay_path = OUT / f"detail-{view}-overlay.png"
            cv2.imwrite(str(overlay_path), overlay(image, target, rendered))
        records.append({"id": view, "label": label, "yaw": yaw, "pitch": pitch,
                        "inspectionCamera": inspect_camera, "projectionCheck": projection_check(mesh, inspect_camera, mask),
                        "referenceCheck": ref, "assets": {"mask": webpath(OUT / f"detail-{view}-mask.png"),
                                                              "overlay": webpath(overlay_path) if overlay_path else None}})
    result = {"schemaVersion": 1, "status": "SLEEVE_DETAIL_CANDIDATE_UNVERIFIED",
              "mesh": {"path": webpath(mesh_path), "sha256": frozen_sha}, "baseMeshSha256": base_sha,
              "baseVertexAndFacePrefixPreserved": prefix_unchanged,
              "protectedRegionsPreserved": protected_regions, "movedBaseVertexCount": len(moved_base),
              "movedBaseVertexIndices": moved_base,
              "oneToOneReconstructionAccepted": False, "realGarmentAccepted": False,
              "views": records,
              "structure": {"implementedChecksPassed": structure["implementedStructuralChecksPassed"],
                            "failedChecks": structure["failedChecks"], "reportPath": webpath(structure_path),
                            "scope": "原单壳结构检查真实运行于完整候选，失败项保留；独立装饰层不是已缝合单壳。"},
              "newLayerCollisionStatus": "unevaluated",
              "limitations": ["新细节需要独立局部形状验证，外轮廓分数不能替代。",
                              "可见接缝/窄折边提供款式依据，布厚、折回深度及隐藏路径为推断。",
                              "新增层之间以及与原布面的接触/穿插未完成服装语义验收。",
                              "没有逐针、纱线、物理尺寸或完整内部结构的一比一证据。"],
              "evidence": {"baseMultiviewSummarySha256": sha(MULTI / "summary.json"),
                           "sourceSha256": {n: sha(Path(__file__).with_name(n)) for n in
                                            ("validate_sleeve_detail.py", "image_fit.py", "evaluator.py", "run_multiview.py")}}}
    if sha(mesh_path) != frozen_sha or sha(base_path) != base_sha:
        raise RuntimeError("Validation changed input mesh")
    (OUT / "detail-validation.json").write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n")
    print(json.dumps({"meshSha256": frozen_sha, "basePrefixUnchanged": prefix_unchanged,
                      "structuralFailedChecks": result["structure"]["failedChecks"],
                      "referenceScores": {v["id"]: v["referenceCheck"]["metrics"] for v in records
                                          if v["referenceCheck"]["metrics"] is not None}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
