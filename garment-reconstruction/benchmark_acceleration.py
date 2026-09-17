#!/usr/bin/env python3
"""Run the same frozen fitting job sequentially on scalar and batched CPU paths.

Outputs are isolated from the reviewed model; no external generation is called.
The comparison includes final independent checks and uses the old rasterizer.
"""
import argparse
import hashlib
import json
import platform
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np


ROOT = Path(__file__).resolve().parent.parent
SOURCES = ("geometry_v2.py", "fit_v2.py", "image_fit.py", "run_upgrade.py",
           "evaluator.py", "collision_check.py", "geometry.py", "run_validation.py")


def source_hashes():
    return {name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
            for name in SOURCES}


def masks_equal_at(left, right):
    a, b = cv2.imread(str(left), 0), cv2.imread(str(right), 0)
    return a is not None and b is not None and a.shape == (512, 512) and np.array_equal(a, b)


def comparison_passed(result):
    return all(result[key] for key in (
        "all_candidate_traces_identical", "final_reference_masks_pixel_identical",
        "final_topology_identical", "all_sources_unchanged_during_run",
        "initial_params_unchanged_during_run", "frozen_evidence_identical")) and result["final_vertex_max_abs_error"] <= 1e-12


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path,
                        default=ROOT / "output/garment-gpu-assessment-20260911/cpu-comparison")
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if output.exists() and any(output.iterdir()):
        raise SystemExit("Use an empty output directory to preserve previous benchmark evidence")
    output.mkdir(parents=True, exist_ok=True)
    params = ROOT / "output/garment-upgrade-20260911/resume-params.json"
    sources_before = source_hashes()
    params_before = hashlib.sha256(params.read_bytes()).hexdigest()
    runs = {}
    for backend in ("scalar", "numpy"):
        destination = output / backend
        command = [sys.executable, str(Path(__file__).with_name("run_upgrade.py")),
                   "--initial-params", str(params), "--coarse-budget", "800",
                   "--volume-budget", "900", "--refine-budget", "1200",
                   "--depth-backend", backend, "--output-dir", str(destination)]
        print(f"Starting {backend} full fit", flush=True)
        subprocess.run(command, check=True)
        runs[backend] = json.loads((destination / "summary.json").read_text())
    scalar, fast = runs["scalar"], runs["numpy"]
    trace_identical = True
    for stage in scalar["product_target"]["fit"]["stages"]:
        filename = stage["stage"] + "-trace.json"
        left = json.loads((output / "scalar" / filename).read_text())
        right = json.loads((output / "numpy" / filename).read_text())
        trace_identical &= left["trace"] == right["trace"]
    masks_equal = all(masks_equal_at(output / "scalar" / f"real-{v}-after-mask.png",
                                    output / "numpy" / f"real-{v}-after-mask.png")
                      for v in ("front", "back", "right"))
    left_mesh = json.loads((output / "scalar/real-after.json").read_text())
    right_mesh = json.loads((output / "numpy/real-after.json").read_text())
    max_error = float(np.max(np.abs(np.asarray(left_mesh["vertices"]) - right_mesh["vertices"])))
    result = {
        "scope": "Full frozen v2 fit, scalar vs NumPy; CPU paths on same machine, sequential",
        "platform": platform.platform(), "python": sys.version.split()[0],
        "source_sha256": sources_before,
        "all_sources_unchanged_during_run": sources_before == source_hashes() and all(
            value["evidence"]["source_sha256"][name] == expected
            for value in runs.values() for name, expected in sources_before.items()),
        "initial_params_sha256": params_before,
        "initial_params_unchanged_during_run": params_before == hashlib.sha256(params.read_bytes()).hexdigest(),
        "frozen_evidence_identical": all(scalar["evidence"][key] == fast["evidence"][key]
                                         for key in ("camera_hash", "mask_sha256")),
        "runs": {key: {"seconds": value["runtime"]["elapsed_seconds"],
                       "evaluations": value["product_target"]["fit"]["evaluations"],
                       "views": value["product_target"]["views"]}
                 for key, value in runs.items()},
        "elapsed_speed_ratio": scalar["runtime"]["elapsed_seconds"] / fast["runtime"]["elapsed_seconds"],
        "all_candidate_traces_identical": trace_identical,
        "final_reference_masks_pixel_identical": masks_equal,
        "final_vertex_max_abs_error": max_error,
        "final_topology_identical": left_mesh["faces"] == right_mesh["faces"],
        "external_ai_calls": 0, "gpu_used_for_this_comparison": False,
        "limits": ["One paired full run; thermal/load variability remains.",
                   "Windows not physically tested here; NumPy backend contains no OS-specific calls.",
                   "Speed improvement preserves current shape; it does not fix unmodeled detail."]}
    (output / "comparison.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k != "runs"}, ensure_ascii=False), flush=True)
    if not comparison_passed(result):
        raise SystemExit("Equivalence requires review; comparison evidence saved")


if __name__ == "__main__":
    main()
