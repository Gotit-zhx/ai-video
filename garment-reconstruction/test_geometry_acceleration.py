"""Compare the accelerated field to the retained scalar geometric oracle."""
import copy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np

from benchmark_acceleration import comparison_passed, masks_equal_at
from geometry_v2 import build_mesh
from image_fit import render_mask


class AccelerationTests(unittest.TestCase):
    def test_mesh_topology_coordinates_and_multiview_masks_are_preserved(self):
        cases = [{}, {"body_lean": -.07, "neck_setback": .035, "chest_depth": 1.2},
                 {"sleeve_length": .32, "sleeve_drop": .23, "cuff_tilt": .12,
                  "hem_flare": .12, "waist_depth": 1.4}]
        for resolution in ("fit", "preview"):
            for params in cases:
                with self.subTest(resolution=resolution, params=params):
                    frozen = copy.deepcopy(params)
                    old = build_mesh(params, resolution, "scalar")
                    new = build_mesh(params, resolution, "numpy")
                    self.assertEqual(params, frozen)
                    self.assertEqual({k: v for k, v in old.items() if k != "vertices"},
                                     {k: v for k, v in new.items() if k != "vertices"})
                    np.testing.assert_allclose(new["vertices"], old["vertices"], rtol=0, atol=1e-12)
                    for yaw, pitch in ((0, 0), (90, 0), (180, 0), (270, 0),
                                       (0, 90), (0, -90), (45, 12), (225, 12)):
                        camera = {"yaw": yaw, "pitch": pitch, "scale": 160, "cx": 256, "cy": 350}
                        np.testing.assert_array_equal(render_mask(old, camera, 512),
                                                      render_mask(new, camera, 512))

    def test_invalid_parameters_are_still_rejected(self):
        for backend in ("scalar", "numpy"):
            for params in ({"depth": float("nan")}, {"sleeve_length": -.1},
                           {"torso_width": .6, "neck_width": .58}):
                with self.subTest(backend=backend, params=params), self.assertRaises(ValueError):
                    build_mesh(params, depth_backend=backend)
        with self.assertRaisesRegex(ValueError, "depth_backend"):
            build_mesh(depth_backend="cuda")

    def test_benchmark_cannot_pass_missing_masks_or_failed_topology(self):
        with tempfile.TemporaryDirectory() as tmp:
            left, right = Path(tmp) / "a.png", Path(tmp) / "b.png"
            # Existing undecodable files exercise decode failure without missing-file warnings.
            left.write_bytes(b"invalid image")
            right.write_bytes(b"invalid image")
            self.assertFalse(masks_equal_at(left, right))
            mask = np.zeros((512, 512), dtype=np.uint8)
            cv2.imwrite(str(left), mask)
            cv2.imwrite(str(right), mask)
            self.assertTrue(masks_equal_at(left, right))
        result = {key: True for key in ("all_candidate_traces_identical", "final_reference_masks_pixel_identical",
                  "final_topology_identical", "all_sources_unchanged_during_run",
                  "initial_params_unchanged_during_run", "frozen_evidence_identical")}
        result["final_vertex_max_abs_error"] = 0.0
        self.assertTrue(comparison_passed(result))
        for key in list(result):
            if key != "final_vertex_max_abs_error":
                self.assertFalse(comparison_passed({**result, key: False}))

    def test_fit_refuses_to_overwrite_reviewed_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            artifact = Path(tmp) / "reviewed.json"
            artifact.write_text("keep this model")
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("run_upgrade.py")),
                                     "--output-dir", tmp], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Output directory must be empty", result.stderr)
            self.assertEqual(artifact.read_text(), "keep this model")
            self.assertEqual(list(Path(tmp).iterdir()), [artifact])


if __name__ == "__main__":
    unittest.main()
