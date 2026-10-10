import unittest

import numpy as np

from run_multiview import coverage, projection_check, reference_check, standard_camera


class MultiViewEvidenceTests(unittest.TestCase):
    def test_missing_or_oblique_reference_cannot_pass_even_with_perfect_score(self):
        metrics = {"iou": 1., "boundary_p95_bbox": 0., "passed_candidate_threshold": True}
        result = reference_check(metrics, False, "oblique")
        self.assertEqual(result["status"], "unevaluated")
        self.assertIsNone(result["metrics"])
        self.assertFalse(result["physicalReconstructionAccepted"])

    def test_three_passed_axes_do_not_complete_six_view_gate(self):
        views = [{"id": v, "referenceCheck": {"status": "passed"}} for v in ("front", "back", "right")]
        self.assertFalse(coverage(views)["allSixReferenceThresholdsPassed"])

    def test_uncalibrated_axes_remain_unaccepted(self):
        views = [{"id": v, "referenceCheck": {"status": "passed" if v not in ("top", "bottom") else "unevaluated"}}
                 for v in ("front", "back", "right", "left", "top", "bottom")]
        self.assertFalse(coverage(views)["allSixReferenceThresholdsPassed"])
        self.assertFalse(coverage(views)["physicalReconstructionAccepted"])

    def test_empty_or_clipped_projection_is_not_renderable(self):
        camera = {"yaw": 0, "pitch": 0, "scale": 1, "cx": 0, "cy": 0}
        mesh = {"vertices": [[100, 0, 0], [102, 0, 0], [101, 1, 0]]}
        report = projection_check(mesh, camera, np.zeros((32, 32), np.uint8))
        self.assertEqual(report["status"], "failed")
        self.assertTrue(report["clipped"])

    def test_axial_projection_preserves_depth_with_opposite_orientation(self):
        from image_fit import project
        vertices = [[0, .65, -.2], [0, .65, .2]]
        top = project(vertices, standard_camera(0, 90))
        bottom = project(vertices, standard_camera(0, -90))
        self.assertGreater(top[1, 1], top[0, 1])
        self.assertLess(bottom[1, 1], bottom[0, 1])
        np.testing.assert_allclose(top[:, 1], 512 - bottom[:, 1])


if __name__ == "__main__":
    unittest.main()
