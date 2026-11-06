"""Analytic geometry tests and a frozen-mesh integration check.

The existing evaluator already tests false opening declarations and capped holes.
These tests focus on new measurements, projection ambiguity, and source freezing.
"""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import numpy as np

from aperture_check import actual_boundary_rings, evaluate_apertures, measure_ring


FROZEN_MESH = Path(__file__).resolve().parents[2] / "output/garment-upgrade-20260911/real-after.json"
FROZEN_SHA = "4f318e80f2b02c6dda6b33e43b3a0a4a9046b0354ea7323c19745fffb0082c68"


class ApertureGeometryTests(unittest.TestCase):
    def rectangle(self):
        # Width 4, height 2; tilted unit Y axis (.0, .6, .8).
        return np.array([[-2, -.6, -.8], [2, -.6, -.8],
                         [2, .6, .8], [-2, .6, .8]]) + [7, 11, -3]

    def test_tilted_rectangle_has_analytic_area_normal_and_projections(self):
        result = measure_ring(self.rectangle())
        np.testing.assert_allclose(result["centerVertexMean"], [7, 11, -3])
        self.assertAlmostEqual(result["perimeter3D"], 12)
        self.assertAlmostEqual(result["areaVectorMagnitude"], 8)
        np.testing.assert_allclose(result["bestFitPlane"]["normal"], [0, -.8, .6], atol=1e-14)
        self.assertAlmostEqual(result["bestFitPlane"]["rmsResidual"], 0)
        self.assertAlmostEqual(result["projections"]["front"]["absoluteSignedArea"], 4.8)
        self.assertAlmostEqual(result["projections"]["top"]["absoluteSignedArea"], 6.4)
        self.assertAlmostEqual(result["projections"]["bottom"]["signedArea"], 6.4)

    def test_edge_on_projection_does_not_mean_collapsed_3d_opening(self):
        result = measure_ring(self.rectangle())
        self.assertEqual(result["numericalDegeneracyStatus"], "passed")
        side = result["projections"]["right"]
        self.assertEqual(side["spatialProjectionRank"], 1)
        self.assertAlmostEqual(side["absoluteSignedArea"], 0)
        self.assertIsNone(side["visibleBackgroundArea"])
        self.assertEqual(side["visibilityStatus"], "unevaluated_requires_occlusion_test")

    def test_nonplanar_residual_is_measured_without_fit_threshold(self):
        points = [[-2, -1, .2], [2, -1, -.2], [2, 1, .2], [-2, 1, -.2]]
        result = measure_ring(points, [])
        self.assertEqual(result["spatialRank"], 3)
        self.assertEqual(result["numericalDegeneracyStatus"], "passed")
        self.assertAlmostEqual(result["bestFitPlane"]["rmsResidual"], .2)
        self.assertAlmostEqual(result["bestFitPlane"]["maxAbsoluteResidual"], .2)
        self.assertEqual(result["bestFitPlane"]["planeFitPhysicalAcceptance"], "unevaluated_no_physical_threshold")

    def test_line_and_zero_length_edge_are_numerically_degenerate(self):
        line = measure_ring([[0, 0, 0], [1, 0, 0], [2, 0, 0]], [])
        self.assertEqual(line["numericalDegeneracyStatus"], "failed")
        self.assertIsNone(line["bestFitPlane"]["normal"])
        repeated = measure_ring([[0, 0, 0], [1, 0, 0], [1, 0, 0], [0, 1, 0]], [])
        self.assertEqual(repeated["spatialRank"], 2)
        self.assertEqual(repeated["numericalDegeneracyStatus"], "failed")
        self.assertEqual(repeated["collapsedBoundaryEdgeIndices"], [1])

    def test_measurements_scale_as_lengths_and_areas(self):
        original = measure_ring(self.rectangle(), [])
        for scale in [1e-6, 1e6]:
            result = measure_ring(self.rectangle()*scale, [])
            self.assertEqual(result["numericalDegeneracyStatus"], "passed")
            self.assertAlmostEqual(result["perimeter3D"]/scale, original["perimeter3D"])
            self.assertAlmostEqual(result["areaVectorMagnitude"]/scale**2, original["areaVectorMagnitude"])

    def test_crossing_projection_reports_algebraic_cancellation(self):
        points = [[-1, -1, 0], [1, 1, 0], [-1, 1, 0], [1, -1, 0]]
        result = measure_ring(points, [{"name": "front", "yaw": 0, "pitch": 0}])
        front = result["projections"]["front"]
        self.assertEqual(front["properNonadjacentEdgeCrossings"], 1)
        self.assertEqual(front["absoluteSignedArea"], 0)
        self.assertEqual(front["spatialProjectionRank"], 2)
        self.assertIsNone(front["visibleBackgroundArea"])


@unittest.skipUnless(FROZEN_MESH.exists(), "Frozen task artifact not present")
class FrozenApertureIntegrationTests(unittest.TestCase):
    def test_actual_face_order_and_frozen_model_measurements(self):
        source = FROZEN_MESH.read_bytes()
        self.assertEqual(hashlib.sha256(source).hexdigest(), FROZEN_SHA)
        mesh = json.loads(source)
        reversed_claims = copy.deepcopy(mesh)
        for name, ring in mesh["openings"].items():
            reversed_claims["openings"][name] = list(reversed(ring))
        self.assertEqual(actual_boundary_rings(mesh), actual_boundary_rings(reversed_claims))
        report = evaluate_apertures(reversed_claims, [])
        self.assertTrue(report["boundaryDeclarationsVerified"])
        self.assertTrue(report["allActualOpeningsNumericallyNondegenerate"])
        self.assertFalse(report["completeGarmentAcceptancePassed"])
        self.assertEqual({key: value["vertexCount"] for key, value in report["openings"].items()},
                         {"neck": 56, "hem": 96, "left_cuff": 36, "right_cuff": 36})
        self.assertEqual(FROZEN_MESH.read_bytes(), source)

    def test_hash_mismatch_prevents_report_and_preserves_source(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)/"report.json"
            run = subprocess.run([sys.executable, str(Path(__file__).with_name("aperture_check.py")),
                                  str(FROZEN_MESH), "--output", str(output), "--expected-sha256", "0"*64],
                                 capture_output=True, text=True)
            self.assertEqual(run.returncode, 2)
            self.assertIn("frozen source", run.stderr)
            self.assertFalse(output.exists())
            self.assertEqual(hashlib.sha256(FROZEN_MESH.read_bytes()).hexdigest(), FROZEN_SHA)


if __name__ == "__main__":
    unittest.main()
