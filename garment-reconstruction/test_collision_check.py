"""Falsification tests: actual crossing points, not AABB overlap alone."""
import copy
import importlib.util
import itertools
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import numpy as np

HERE = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE/(name+".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


collision = load("collision_check")


def three_triangle_mesh(left):
    torso = [[0, 0, 0], [2, 0, 0], [0, 2, 0]]
    right = [[5, 0, 0], [6, 0, 0], [5, 1, 0]]
    return {"vertices": torso+left+right, "faces": [[0, 1, 2], [3, 4, 5], [6, 7, 8]],
            "semantic_regions": {"torso": [0, 1, 2], "left_sleeve": [3, 4, 5], "right_sleeve": [6, 7, 8]}}


def fold_left_through_torso(mesh):
    """Keep the root fixed; turn distal sleeve inward and through front surface."""
    result = copy.deepcopy(mesh)
    root = set(mesh["semantic_regions"]["left_sleeve_root"])
    root_x = max(mesh["vertices"][vertex][0] for vertex in root)
    length = max(mesh["vertices"][vertex][0] for vertex in mesh["semantic_regions"]["left_sleeve"])-root_x
    for index in mesh["semantic_regions"]["left_sleeve"]:
        if index in root:
            continue
        x, y, z = mesh["vertices"][index]
        t = (x-root_x)/length
        result["vertices"][index] = [root_x-.95*(x-root_x), y-.1*t, z+.42*t]
    return result


class TriangleIntersectionTests(unittest.TestCase):
    a = np.array([[0, 0, 0], [2, 0, 0], [0, 2, 0]], dtype=float)
    b = np.array([[.5, .5, -1], [.5, .5, 1], [1.5, .5, 0]], dtype=float)

    def test_proper_crossing_has_actual_points_on_both_triangles(self):
        result = collision.triangle_intersection(self.a, self.b)
        self.assertEqual(result["kind"], "proper_crossing")
        self.assertAlmostEqual(result["segmentLength"], 1)
        for point in np.asarray(result["points"]):
            for triangle in (self.a, self.b):
                normal = np.cross(triangle[1]-triangle[0], triangle[2]-triangle[0])
                self.assertAlmostEqual(float(np.dot(point-triangle[0], normal)), 0)
                self.assertGreaterEqual(collision._barycentric(point, triangle).min(), -1e-8)

    def test_triangle_order_and_winding_do_not_change_crossing(self):
        for a_order in itertools.permutations(range(3)):
            for b_order in itertools.permutations(range(3)):
                result = collision.triangle_intersection(self.a[list(a_order)], self.b[list(b_order)])
                self.assertEqual(result["kind"], "proper_crossing")
                self.assertAlmostEqual(result["segmentLength"], 1)
        self.assertEqual(collision.triangle_intersection(self.b, self.a)["kind"], "proper_crossing")

    def test_overlapping_aabbs_are_not_an_intersection(self):
        # Bounding boxes overlap in x/y/z but the vertical triangle misses A.
        b = [[1.4, 1.4, -1], [1.4, 1.4, 1], [1.8, 1.4, 0]]
        self.assertEqual(collision.triangle_intersection(self.a, b)["kind"], "disjoint")

    def test_parallel_offset_triangles_are_disjoint(self):
        self.assertEqual(collision.triangle_intersection(self.a, self.a+[0, 0, .1])["kind"], "disjoint")

    def test_coplanar_overlap_is_measured(self):
        b = [[.5, .5, 0], [1.5, .5, 0], [.5, 1.5, 0]]
        result = collision.triangle_intersection(self.a, b)
        self.assertEqual(result["kind"], "coplanar_overlap")
        self.assertAlmostEqual(result["projectedOverlapArea"], .5)
        self.assertEqual(len(result["points"]), 3)

    def test_coplanar_overlap_works_off_xy_plane(self):
        a = self.a[:, [2, 0, 1]]
        b = np.array([[.5, .5, 0], [1.5, .5, 0], [.5, 1.5, 0]])[:, [2, 0, 1]]
        result = collision.triangle_intersection(a, b)
        self.assertEqual(result["kind"], "coplanar_overlap")
        self.assertTrue(np.allclose(np.asarray(result["points"])[:, 0], 0))

    def test_coplanar_aabb_false_positive_is_disjoint(self):
        b = [[1.5, 1.5, 0], [2.5, 1.5, 0], [1.5, 2.5, 0]]
        self.assertEqual(collision.triangle_intersection(self.a, b)["kind"], "disjoint")

    def test_edge_or_point_contact_is_not_silently_passed(self):
        for b in ([[2, 0, 0], [3, 0, 0], [2, 1, 0]],
                  [[0, 0, 0], [2, 0, 0], [0, -2, 0]]):
            self.assertEqual(collision.triangle_intersection(self.a, b)["kind"], "contact")

    def test_degenerate_triangle_is_unevaluated(self):
        self.assertEqual(collision.triangle_intersection(self.a, [[0, 0, 0]]*3)["kind"], "unevaluated")


class ScopedMeshTests(unittest.TestCase):
    def test_broad_phase_false_positive_is_really_tested_and_rejected_as_collision(self):
        mesh = three_triangle_mesh([[1.4, 1.4, -1], [1.4, 1.4, 1], [1.8, 1.4, 0]])
        result = collision.check_mesh(mesh)
        self.assertEqual(result["testedPairs"], 1)
        self.assertEqual(result["scopeStatus"], "passed")
        self.assertEqual(result["detectedIntersectionPairs"], 0)
        self.assertFalse(result["completeClothCollisionValidation"])

    def test_actual_crossing_face_ids_and_points_are_reported(self):
        mesh = three_triangle_mesh(TriangleIntersectionTests.b.tolist())
        result = collision.check_mesh(mesh)
        self.assertEqual(result["scopeStatus"], "failed")
        self.assertEqual(result["detectedIntersectionPairs"], 1)
        found = result["examples"][0]
        self.assertEqual((found["sleeveFace"], found["torsoFace"]), (1, 0))
        self.assertEqual(found["kind"], "proper_crossing")
        self.assertTrue(all(abs(point[2]) < 1e-8 for point in found["intersectionPoints"]))

    def test_coplanar_positive_overlap_fails_but_contacts_stay_unknown(self):
        overlap = collision.check_mesh(three_triangle_mesh([[.5, .5, 0], [1.5, .5, 0], [.5, 1.5, 0]]))
        self.assertEqual(overlap["scopeStatus"], "failed")
        contact = collision.check_mesh(three_triangle_mesh([[2, 0, 0], [3, 0, 0], [2, 1, 0]]))
        self.assertEqual(contact["scopeStatus"], "unevaluated")
        self.assertGreater(contact["unresolvedPairs"], 0)

    def test_shared_index_vertex_is_the_only_adjacency_exemption(self):
        mesh = three_triangle_mesh([[0, 0, 0], [0, 0, 1], [1, 0, 0]])
        mesh["faces"][1][0] = 0
        mesh["semantic_regions"]["left_sleeve"] = [0, 4, 5]
        result = collision.check_mesh(mesh)
        self.assertEqual(result["regions"]["left_sleeve"]["sharedVertexExemptions"], 1)
        self.assertEqual(result["testedPairs"], 0)
        self.assertIn("sharing an indexed vertex", " ".join(result["limitations"]))

    def test_hash_determinism_and_read_only_input(self):
        mesh = three_triangle_mesh(TriangleIntersectionTests.b.tolist())
        original = copy.deepcopy(mesh)
        first = collision.check_mesh(mesh)
        self.assertEqual(first, collision.check_mesh(mesh))
        self.assertEqual(mesh, original)
        mesh["vertices"][3][0] += .1
        self.assertNotEqual(first["meshCanonicalSha256"], collision.check_mesh(mesh)["meshCanonicalSha256"])

    def test_uniform_scale_translation_keeps_crossing_result(self):
        for scale in (1e-4, 1, 10000):
            mesh = three_triangle_mesh(TriangleIntersectionTests.b.tolist())
            mesh["vertices"] = (np.asarray(mesh["vertices"])*scale+np.array([3, 7, -2])*scale).tolist()
            result = collision.check_mesh(mesh)
            self.assertEqual(result["scopeStatus"], "failed")
            self.assertEqual(result["detectedIntersectionPairs"], 1)

    def test_cli_outputs_hashes_and_nonzero_on_crossing(self):
        with tempfile.TemporaryDirectory() as folder:
            source, output = Path(folder)/"mesh.json", Path(folder)/"collision.json"
            source.write_text(json.dumps(three_triangle_mesh(TriangleIntersectionTests.b.tolist())))
            command = [sys.executable, str(HERE/"collision_check.py"), str(source), "--output", str(output)]
            run = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(run.returncode, 1, run.stderr)
            report = json.loads(output.read_text())
            self.assertEqual(len(report["inputSha256"]), 64)
            self.assertEqual(len(report["meshCanonicalSha256"]), 64)
            self.assertEqual(report["scopeStatus"], "failed")


class ActualGarmentTests(unittest.TestCase):
    def check_normal_and_fold(self, module, **kwargs):
        mesh = load(module).build_mesh({}, **kwargs)
        normal = collision.check_mesh(mesh)
        self.assertEqual(normal["scopeStatus"], "passed", normal)
        self.assertEqual(normal["detectedIntersectionPairs"], 0)
        folded = fold_left_through_torso(mesh)
        self.assertEqual(mesh["faces"], folded["faces"])
        self.assertEqual(mesh["openings"], folded["openings"])
        for vertex in mesh["semantic_regions"]["left_sleeve_root"]:
            self.assertEqual(mesh["vertices"][vertex], folded["vertices"][vertex])
        result = collision.check_mesh(folded)
        self.assertEqual(result["scopeStatus"], "failed", result)
        self.assertGreater(result["regions"]["left_sleeve"]["properCrossings"], 0)
        self.assertEqual(result["regions"]["right_sleeve"]["properCrossings"], 0)
        crossing = next(example for example in result["examples"] if example["kind"] == "proper_crossing")
        point = np.mean(crossing["intersectionPoints"], axis=0)
        for face_index in (crossing["sleeveFace"], crossing["torsoFace"]):
            triangle = np.asarray([folded["vertices"][v] for v in folded["faces"][face_index]])
            normal = np.cross(triangle[1]-triangle[0], triangle[2]-triangle[0])
            self.assertLess(abs(np.dot(point-triangle[0], normal))/np.linalg.norm(normal), 1e-7)
            self.assertGreater(collision._barycentric(point, triangle).min(), 0)

    def test_actual_v1_normal_and_real_fold_through_body(self):
        self.check_normal_and_fold("geometry")

    @unittest.skipUnless((HERE/"geometry_v2.py").exists(), "geometry_v2 is still being implemented")
    def test_actual_v2_normal_and_real_fold_through_body(self):
        for resolution in ("fit", "preview"):
            self.check_normal_and_fold("geometry_v2", resolution=resolution)

    def test_budget_exhaustion_never_passes(self):
        mesh = load("geometry").build_mesh({})
        result = collision.check_mesh(mesh, max_pairs=1)
        self.assertEqual(result["testedPairs"], 1)
        self.assertTrue(result["pairBudgetExhausted"])
        self.assertEqual(result["scopeStatus"], "unevaluated")
        self.assertFalse(result["allScopedCandidatePairsEvaluated"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
