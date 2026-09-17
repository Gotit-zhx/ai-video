"""Regression for invalid coordinate searches derailing later shape parameters."""
import copy
import unittest

from fit_v2 import fit_stage
from image_fit import render_mask, silhouette_metrics


def coupled_rectangle(params):
    x, y = params["width"], params["height"]
    if not (0 <= x <= 1 and 0 <= y <= 1) or abs(x - y) > .12:
        raise ValueError("Narrow coupled feasible interval")
    return {"vertices": [[0, 0, 0], [.2 + x, 0, 0], [.2 + x, .2 + y, 0], [0, .2 + y, 0]],
            "faces": [[0, 1, 2], [0, 2, 3]]}


class FeasibleFitTests(unittest.TestCase):
    def test_narrow_feasible_interval_does_not_poison_later_coordinates(self):
        camera = {"yaw": 0, "pitch": 0, "scale": 42, "cx": 4, "cy": 58}
        cameras = [camera]
        frozen = copy.deepcopy(cameras)
        target = render_mask(coupled_rectangle({"width": .72, "height": .72}), camera, 64)
        start = {"width": .20, "height": .20}
        before = silhouette_metrics(target, render_mask(coupled_rectangle(start), camera, 64))["iou"]
        params, log = fit_stage(coupled_rectangle, start, ["width", "height"], [(0, 1), (0, 1)],
                                [target], cameras, 64, max_evaluations=600)
        after = silhouette_metrics(target, render_mask(coupled_rectangle(params), camera, 64))["iou"]
        self.assertGreater(after, .95)
        self.assertGreater(after, before + .5)
        self.assertEqual(cameras, frozen)
        self.assertLessEqual(log["evaluations"], 600)
        self.assertTrue(any(not step["geometry_valid"] for step in log["trace"]))
        self.assertTrue(all(step["geometry_valid"] for step in log["trace"] if step["best_so_far"]))

    def test_budget_keeps_a_valid_measured_candidate(self):
        camera = {"yaw": 0, "scale": 30, "cx": 3, "cy": 40}
        target = render_mask(coupled_rectangle({"width": .6, "height": .6}), camera, 48)
        result, log = fit_stage(coupled_rectangle, {"width": .2, "height": .2}, ["width", "height"],
                                [(0, 1), (0, 1)], [target], [camera], 48, max_evaluations=10)
        coupled_rectangle(result)
        self.assertEqual(log["evaluations"], 10)
        self.assertLessEqual(log["best_loss"], log["initial_loss"])


if __name__ == "__main__":
    unittest.main()
