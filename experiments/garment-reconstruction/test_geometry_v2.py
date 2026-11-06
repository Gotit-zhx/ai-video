import json
import math
import unittest
from collections import Counter

from geometry_v2 import DEFAULT_PARAMS, build_mesh, _structure, _shoulder


class GeometryV2Tests(unittest.TestCase):
    def test_four_openings_and_shared_root_topology_at_both_resolutions(self):
        for resolution in ("fit", "preview"):
            mesh = build_mesh({}, resolution)
            edges = Counter(tuple(sorted((f[i], f[(i + 1) % 3]))) for f in mesh["faces"] for i in range(3))
            self.assertTrue(all(count in (1, 2) for count in edges.values()))
            boundary = {edge for edge, count in edges.items() if count == 1}
            expected = {tuple(sorted((ring[i], ring[(i + 1) % len(ring)])))
                        for ring in mesh["openings"].values() for i in range(len(ring))}
            self.assertEqual(boundary, expected)
            self.assertEqual(len(mesh["vertices"]) - len(edges) + len(mesh["faces"]), -2)
            groups = {name: set(ids) for name, ids in mesh["semantic_regions"].items()}
            for side in ("left", "right"):
                self.assertEqual(groups["torso"] & groups[side + "_sleeve"], groups[side + "_sleeve_root"])

    def test_sleeve_parameters_leave_every_torso_vertex_unchanged(self):
        original = build_mesh({}, "fit")
        for change in ({"sleeve_length": 0.55}, {"sleeve_drop": 0.28},
                       {"cuff_tilt": 0.15}, {"cuff_width": 0.45}):
            changed = build_mesh(change, "fit")
            self.assertEqual(original["faces"], changed["faces"])
            for index in original["semantic_regions"]["torso"]:
                self.assertEqual(original["vertices"][index], changed["vertices"][index])

    def test_cuff_plane_is_normal_to_axis_even_with_body_lean(self):
        p = {**DEFAULT_PARAMS, "body_lean": 0.12, "sleeve_drop": 0.30, "neck_setback": -0.11}
        mesh = build_mesh(p, "fit")
        st = _structure(p)
        center = (0.5 * p["torso_width"] + p["sleeve_length"], st["root_center"] - p["sleeve_drop"])
        for index in mesh["openings"]["left_cuff"]:
            x, y, _ = mesh["vertices"][index]
            dot = (x - center[0]) * p["sleeve_length"] - (y - center[1]) * p["sleeve_drop"]
            self.assertAlmostEqual(dot, 0.0, places=12)

    def test_neck_setback_is_local_and_preserves_xy_sleeves_and_topology(self):
        for resolution in ("fit", "preview"):
            original = build_mesh({}, resolution)
            changed = build_mesh({"neck_setback": -0.10}, resolution)
            self.assertEqual(original["faces"], changed["faces"])
            self.assertEqual(original["openings"], changed["openings"])
            sleeves = set(original["semantic_regions"]["left_sleeve"]) | set(original["semantic_regions"]["right_sleeve"])
            changed_indices = []
            for index, (before, after) in enumerate(zip(original["vertices"], changed["vertices"])):
                self.assertEqual(before[:2], after[:2])
                if index in sleeves or before[1] <= DEFAULT_PARAMS["body_length"] - 0.18:
                    self.assertEqual(before, after)
                if before != after:
                    changed_indices.append(index)
                    self.assertLess(after[2], before[2])
                    self.assertLessEqual(before[2] - after[2], 0.10 + 1e-12)
            self.assertTrue(changed_indices)
            self.assertTrue(set(changed_indices) <= set(original["semantic_regions"]["torso"]))

    def test_body_profile_and_lean_change_actual_surface(self):
        original = build_mesh({}, "fit")
        changed = build_mesh({"hem_depth": 1.4, "waist_depth": 1.2, "body_lean": 0.15}, "fit")
        self.assertNotEqual(original["vertices"], changed["vertices"])
        neck = changed["openings"]["neck"]
        mean_neck_z = sum(changed["vertices"][i][2] for i in neck) / len(neck)
        self.assertGreater(mean_neck_z, 0.09)
        hem = changed["openings"]["hem"]
        hem_depth = max(changed["vertices"][i][2] for i in hem) - min(changed["vertices"][i][2] for i in hem)
        self.assertGreater(hem_depth, 0.35)

    def test_shoulder_curve_has_prescribed_continuous_root_tangent(self):
        p, st = DEFAULT_PARAMS, _structure(DEFAULT_PARAMS)
        x = p["torso_width"] / 2.0
        y, slope = _shoulder(x, p, st)
        delta = 1e-6
        numerical = (y - _shoulder(x - delta, p, st)[0]) / delta
        self.assertAlmostEqual(slope, numerical, places=5)

    def test_serialization_is_deterministic_and_bad_inputs_rejected(self):
        self.assertEqual(json.dumps(build_mesh({}, "fit"), allow_nan=False),
                         json.dumps(build_mesh({}, "fit"), allow_nan=False))
        for params in ({"depth": float("nan")}, {"body_lean": True}, {"foo": 2},
                       {"sleeve_length": 0.08, "sleeve_drop": 0.50, "cuff_width": 0.60}):
            with self.subTest(params=params), self.assertRaises(ValueError):
                build_mesh(params, "fit")


if __name__ == "__main__":
    unittest.main()
