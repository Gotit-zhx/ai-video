"""Batch scorer integration, restart semantics and real broker result matching."""
import threading
import unittest

import cv2
import numpy as np

from fit_v2 import fit_stage, ScoreBackendError
from gpu_fit_server import GpuBroker
from image_fit import render_mask
from run_gpu_repair import passes_nonregression
from test_fit_v2 import coupled_rectangle


class BatchFitTests(unittest.TestCase):
    def test_external_batch_scores_actually_drive_the_same_search(self):
        camera = {"yaw": 0, "scale": 40, "cx": 3, "cy": 55}
        target = render_mask(coupled_rectangle({"width": .6, "height": .6}), camera, 64)
        t = (target > 0).astype(np.uint8)
        field = cv2.distanceTransform(1 - t, cv2.DIST_L2, 5) - cv2.distanceTransform(t, cv2.DIST_L2, 5)
        batches = []
        def score(meshes):
            batches.append(len(meshes))
            return [[float(np.sum(((render_mask(m, camera, 64) > 0) != (t > 0)) *
                                  (1 + np.abs(field))) / np.count_nonzero(t))] for m in meshes]
        args = (coupled_rectangle, {"width": .2, "height": .2}, ["width", "height"],
                [(0, 1), (0, 1)], [target], [camera], 64)
        baseline, old = fit_stage(*args, max_evaluations=180)
        actual, new = fit_stage(*args, max_evaluations=180, score_meshes=score)
        self.assertEqual(actual, baseline)
        self.assertEqual(new["trace"], old["trace"])
        self.assertGreater(max(batches), 1)
        self.assertLess(new["best_loss"], new["initial_loss"])

    def test_bad_backend_score_cannot_be_recorded_as_invalid_geometry(self):
        camera = {"scale": 30, "cx": 3, "cy": 50}
        target = np.ones((64, 64), dtype=np.uint8)
        args = (coupled_rectangle, {"width": .2, "height": .2}, ["width"], [(0, 1)], [target], [camera], 64)
        for result in ([], [[float("nan")]], [[-1]]):
            with self.subTest(result=result), self.assertRaises(ScoreBackendError):
                fit_stage(*args, score_meshes=lambda meshes: result)

    def test_timeout_and_stale_response_cannot_finish_another_task(self):
        broker = GpuBroker(timeout=.02)
        broker.update(jobId="test", state="running")
        with self.assertRaises(ScoreBackendError):
            broker.request({"type": "configure"})
        with self.assertRaises(ValueError):
            broker.submit({"jobId": "test", "id": 1, "ok": True})
        self.assertIsNone(broker.pending)
        result = []
        broker.timeout = 2
        worker = threading.Thread(target=lambda: result.append(broker.request({"type": "evaluate", "candidates": []})))
        worker.start()
        task = broker.next_task("test")
        self.assertEqual(task["id"], 2)
        with self.assertRaises(ValueError):
            broker.submit({"jobId": "different", "id": 2, "ok": True})
        broker.submit({"jobId": "test", "id": 2, "ok": True, "results": []})
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(result[0]["results"], [])

    def test_final_guard_does_not_hide_previously_passing_view_failure(self):
        before = {"front": {"iou": .9501, "boundary_p95_bbox": .0149,
                              "passed_candidate_threshold": True}}
        after = {"front": {"iou": .9499, "boundary_p95_bbox": .0149,
                             "passed_candidate_threshold": False}}
        self.assertFalse(passes_nonregression(before, after))
        self.assertTrue(passes_nonregression(before, before))


if __name__ == "__main__":
    unittest.main()
