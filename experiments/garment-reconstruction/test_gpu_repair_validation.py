import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

import validate_gpu_repair as validator


def two_surfaces(trough=0):
    """An analytic front trough and flat back, independent of garment builders."""
    ys = [0, validator.CHEST_BAND[0], sum(validator.CHEST_BAND) / 2, validator.CHEST_BAND[1], 1.3]
    vertices, faces = [], []
    for front in (True, False):
        offset = len(vertices)
        for index, y in enumerate(ys):
            z = .2 - (trough if index == 2 else 0) if front else -.2
            vertices.extend([[-.4, y, z], [.4, y, z]])
        for row in range(len(ys) - 1):
            a = offset + row * 2
            faces.extend([[a, a + 1, a + 2], [a + 1, a + 3, a + 2]])
    return {"vertices": vertices, "faces": faces}


class GpuRepairValidationTests(unittest.TestCase):
    def test_fixed_world_measurement_detects_an_actual_front_trough(self):
        flat = validator.fixed_chest_measurements(two_surfaces())
        trough = validator.fixed_chest_measurements(two_surfaces(.1))
        self.assertEqual(flat["status"], "measured")
        self.assertAlmostEqual(flat["maximumTroughDepth"], 0)
        self.assertAlmostEqual(trough["maximumTroughDepth"], .1)
        self.assertFalse(trough["physicalReconstructionAccepted"])

    def test_candidate_parameters_and_labels_cannot_move_measurement_stations(self):
        mesh = two_surfaces(.1)
        expected = validator.fixed_chest_measurements(mesh)
        mesh.update(params={"torso_width": 999, "body_length": .001}, semantic_regions={"chest": []})
        self.assertEqual(validator.fixed_chest_measurements(mesh), expected)
        moved = copy.deepcopy(mesh)
        moved["vertices"] = (np.asarray(moved["vertices"]) + [0, 2, 0]).tolist()
        result = validator.fixed_chest_measurements(moved)
        self.assertEqual(result["status"], "partially_unevaluated")
        self.assertIsNone(result["maximumTroughDepth"])

    def test_multiple_surface_hits_are_not_reduced_to_a_successful_measurement(self):
        mesh = two_surfaces(.1)
        extra = two_surfaces()
        offset = len(mesh["vertices"])
        mesh["vertices"].extend((np.asarray(extra["vertices"]) + [0, 0, .1]).tolist())
        mesh["faces"].extend((np.asarray(extra["faces"]) + offset).tolist())
        result = validator.fixed_chest_measurements(mesh)
        self.assertIsNone(result["maximumTroughDepth"])

    def test_existing_job_validation_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary)
            mesh = out / "mesh.json"
            mesh.write_text("{}")
            prior = out / "validation"
            prior.mkdir()
            report = prior / "structure-report.json"
            report.write_text("retained evidence")
            with patch.object(validator, "frozen_inputs", return_value=({}, {})):
                with self.assertRaises(FileExistsError):
                    validator.validate_candidate(mesh, out)
            self.assertEqual(report.read_text(), "retained evidence")
            self.assertEqual(mesh.read_text(), "{}")

    def test_checker_success_with_wrong_candidate_hash_is_unverified(self):
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary)
            report = out / "structure.json"
            def complete(*args, **kwargs):
                report.write_text(json.dumps({"implementedStructuralChecksPassed": True, "inputSha256": "wrong"}))
                return subprocess.CompletedProcess(args[0], 0, "", "")
            with patch.object(validator.subprocess, "run", side_effect=complete):
                _, execution = validator.run_checker("evaluator.py", out / "mesh.json", report, "correct")
            self.assertFalse(execution["reportVerified"])

    def test_changed_frozen_manifest_is_rejected(self):
        with patch.object(validator, "sha", return_value="changed"):
            with self.assertRaisesRegex(ValueError, "Frozen reference"):
                validator.frozen_inputs()

    def test_invalid_mesh_retains_failed_and_unevaluated_states(self):
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary)
            mesh = out / "mesh.json"
            mesh.write_text('{"vertices":[],"faces":[]}')
            result = validator.validate_candidate(mesh, out)
            self.assertEqual(result["structure"]["status"], "failed")
            self.assertFalse(result["realGarmentAccepted"])
            self.assertTrue(all(v["referenceCheck"]["status"] == "unevaluated" for v in result["views"]))
            self.assertTrue(all(v["projectionCheck"]["status"] == "failed" for v in result["views"]))
            self.assertFalse(result["coverage"]["allSixReferenceThresholdsPassed"])
            self.assertTrue((out / "repair-validation.json").exists())


if __name__ == "__main__":
    unittest.main()
