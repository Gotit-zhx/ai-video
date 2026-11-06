"""Generator contract regressions; independent acceptance lives in evaluator.py."""

import json
import math
import unittest
from collections import Counter, defaultdict

from geometry import DEFAULT_PARAMS, build_mesh


class GeometryContractTests(unittest.TestCase):
    def test_shared_sleeve_roots_and_four_actual_openings(self):
        mesh = build_mesh({})
        edges = Counter()
        directions = defaultdict(list)
        used = set()
        for face in mesh["faces"]:
            used.update(face)
            for a, b in zip(face, face[1:] + face[:1]):
                edge = tuple(sorted((a, b)))
                edges[edge] += 1
                directions[edge].append((a, b))
        self.assertEqual(used, set(range(len(mesh["vertices"]))))
        self.assertTrue(all(count in (1, 2) for count in edges.values()))
        self.assertTrue(all(len(ds) == 1 or ds[0] == tuple(reversed(ds[1])) for ds in directions.values()))
        actual = {edge for edge, count in edges.items() if count == 1}
        declared = set()
        for loop in mesh["openings"].values():
            self.assertEqual(len(loop), len(set(loop)))
            declared.update(tuple(sorted(edge)) for edge in zip(loop, loop[1:] + loop[:1]))
        self.assertEqual(actual, declared)
        # Connected orientable genus-zero shell with four holes has chi = -2.
        self.assertEqual(len(mesh["vertices"]) - len(edges) + len(mesh["faces"]), -2)
        regions = {name: set(ids) for name, ids in mesh["semantic_regions"].items()}
        for name in ("left", "right"):
            self.assertEqual(regions[f"{name}_sleeve_root"], regions["torso"] & regions[f"{name}_sleeve"])
            self.assertGreater(len(regions[f"{name}_sleeve_root"]), 4)

    def test_deterministic_serialization_and_topology_during_fit(self):
        first = build_mesh({})
        self.assertEqual(json.dumps(first, allow_nan=False), json.dumps(build_mesh({}), allow_nan=False))
        changed = build_mesh({"neck_depth": 0.27, "sleeve_length": 0.60, "sleeve_drop": 0.26})
        self.assertEqual(first["faces"], changed["faces"])
        self.assertEqual(first["openings"], changed["openings"])
        self.assertNotEqual(first["vertices"], changed["vertices"])

    def test_front_neck_depth_does_not_move_back(self):
        original, changed = build_mesh({}), build_mesh({"neck_depth": 0.26})
        for index in original["semantic_regions"]["back"]:
            self.assertEqual(original["vertices"][index], changed["vertices"][index])

    def test_invalid_inputs_rejected_not_clipped(self):
        for invalid in ({"depth": float("nan")}, {"depth": 10 ** 999}, {"body_length": True},
                        {"arbitrary_code": 1}, {4: 1},
                        {"sleeve_length": 9}, {"torso_width": 0.65, "neck_width": 0.55}):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                build_mesh(invalid)

    def test_valid_extremes_have_positive_projected_face_areas(self):
        # Every panel is a graph over a simple 2D domain. A strictly positive
        # projected triangle prevents local foldovers and zero-area triangles.
        for params in ({}, {"torso_width": 0.8, "body_length": 1.5, "depth": 0.14},
                       {"torso_width": 1.3, "body_length": 1.6, "neck_depth": 0.35,
                        "neck_width": 0.55, "cuff_width": 0.50, "depth": 0.5}):
            mesh = build_mesh(params)
            for face in mesh["faces"]:
                a, b, c = (mesh["vertices"][index] for index in face)
                area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
                self.assertGreater(abs(area), 1e-10)
            self.assertTrue(all(math.isfinite(v) for point in mesh["vertices"] for v in point))


if __name__ == "__main__":
    unittest.main()
