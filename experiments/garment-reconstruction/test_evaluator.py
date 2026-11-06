"""Executable falsification tests for the independent structural PoC evaluator.

The fixture is an intentionally simple cylinder with four holes. It proves
topology checks, not that a valid T-shirt has been reconstructed.
"""

import copy
import importlib.util
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


MODULE = Path(__file__).with_name("evaluator.py")
spec = importlib.util.spec_from_file_location("garment_evaluator", MODULE)
evaluator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluator)


def topology_fixture():
    count = 16
    vertices = [[0.7 * math.cos(2*math.pi*j/count), -1 + 0.5*r,
                 0.25 * math.sin(2*math.pi*j/count)] for r in range(5) for j in range(count)]
    faces = []
    windows = {}
    for r in range(4):
        for j in range(count):
            a, b = r*count+j, r*count+(j+1) % count
            d, c = (r+1)*count+j, (r+1)*count+(j+1) % count
            if r == 1 and j in (0, 8):
                windows["left_cuff" if j == 0 else "right_cuff"] = [a, b, c, d]
                continue
            faces.extend([[a, b, c], [a, c, d]])
    regions = {
        "torso": list(range(len(vertices))),
        "chest": list(range(count, 4*count)),
        "left_sleeve": [r*count+j for r in range(1, 4) for j in (15, 0, 1, 2)],
        "right_sleeve": [r*count+j for r in range(1, 4) for j in (7, 8, 9, 10)],
    }
    return {"vertices": vertices, "faces": faces,
            "openings": {"neck": list(range(4*count, 5*count)), "hem": list(range(count)), **windows},
            "semantic_regions": regions}


class EvaluatorTests(unittest.TestCase):
    def assert_rejected(self, mesh, check):
        result = evaluator.evaluate_mesh(mesh)
        self.assertIn(check, result["failedChecks"], result)
        self.assertFalse(result["implementedStructuralChecksPassed"])
        self.assertEqual(result["decision"], "rejected_by_structural_checks")
        self.assertFalse(result["completeGarmentAcceptancePassed"])
        return result

    def test_four_actual_openings_pass_only_implemented_checks(self):
        result = evaluator.evaluate_mesh(topology_fixture())
        self.assertTrue(result["implementedStructuralChecksPassed"], result)
        self.assertEqual(result["checks"]["boundary_loops"]["metrics"]["actualLoopCount"], 4)
        self.assertEqual(result["decision"], "partial_checks_only")
        self.assertFalse(result["completeGarmentAcceptancePassed"])
        self.assertEqual(result["garmentAcceptance"], "unevaluated")
        for name in ("self_intersections", "sleeve_body_intersections", "material_thickness_inner_walls",
                     "reference_similarity", "physical_dimensions"):
            self.assertEqual(result["checks"][name]["status"], "unevaluated")

    def test_cap_neck_even_with_unchanged_claim_is_rejected(self):
        mesh = topology_fixture()
        ring = mesh["openings"]["neck"]
        center = len(mesh["vertices"])
        mesh["vertices"].append([0, 1, 0])
        mesh["faces"].extend([[ring[i], ring[(i+1) % len(ring)], center] for i in range(len(ring))])
        result = self.assert_rejected(mesh, "actual_openings_match")
        self.assertEqual(result["checks"]["boundary_loops"]["metrics"]["actualLoopCount"], 3)

    def test_delete_sleeve_faces_is_rejected(self):
        mesh = topology_fixture()
        sleeve = set(mesh["semantic_regions"]["left_sleeve"])
        before = len(mesh["faces"])
        mesh["faces"] = [face for face in mesh["faces"] if not set(face) <= sleeve]
        self.assertLess(len(mesh["faces"]), before)
        self.assert_rejected(mesh, "actual_openings_match")

    def test_unwelded_seam_with_coincident_vertices_is_rejected(self):
        mesh = topology_fixture()
        face = mesh["faces"][30]
        for position, vertex in enumerate(list(face)):
            face[position] = len(mesh["vertices"])
            mesh["vertices"].append(list(mesh["vertices"][vertex]))
        result = self.assert_rejected(mesh, "connected_surface")
        self.assertIn("actual_openings_match", result["failedChecks"])

    def test_honest_number_of_holes_does_not_hide_wrong_ring(self):
        mesh = topology_fixture()
        mesh["openings"]["neck"] = list(range(16, 32))
        result = self.assert_rejected(mesh, "actual_openings_match")
        self.assertEqual(result["checks"]["boundary_loops"]["status"], "passed")

    def test_duplicates_of_one_opening_cannot_stand_in_for_four(self):
        mesh = topology_fixture()
        mesh["openings"]["left_cuff"] = mesh["openings"]["right_cuff"][:]
        self.assert_rejected(mesh, "actual_openings_match")

    def test_reversed_loop_order_is_allowed(self):
        mesh = topology_fixture()
        for ring in mesh["openings"].values():
            ring.reverse()
            ring.append(ring[0])
        self.assertTrue(evaluator.evaluate_mesh(mesh)["implementedStructuralChecksPassed"])

    def test_reversed_triangle_is_detected(self):
        mesh = topology_fixture()
        mesh["faces"][20].reverse()
        self.assert_rejected(mesh, "edge_orientation")

    def test_duplicate_triangle_and_nonmanifold_edge_are_detected(self):
        mesh = topology_fixture()
        mesh["faces"].append(mesh["faces"][20][:])
        result = self.assert_rejected(mesh, "duplicate_faces")
        self.assertIn("manifold_edges", result["failedChecks"])

    def test_degenerate_triangle_is_detected(self):
        mesh = topology_fixture()
        mesh["faces"][20][1] = mesh["faces"][20][0]
        self.assert_rejected(mesh, "degenerate_faces")

    def test_extra_disconnected_triangle_is_detected(self):
        mesh = topology_fixture()
        index = len(mesh["vertices"])
        mesh["vertices"].extend([[2, 0, 0], [2, 0.1, 0], [2, 0, 0.1]])
        mesh["faces"].append([index, index+1, index+2])
        self.assert_rejected(mesh, "connected_surface")

    def test_collapsed_depth_is_detected(self):
        mesh = topology_fixture()
        for v in mesh["vertices"]:
            v[2] *= 0.001
        self.assert_rejected(mesh, "torso_collapse_experimental")

    def test_collapsed_width_is_detected(self):
        mesh = topology_fixture()
        for v in mesh["vertices"]:
            v[0] *= 0.001
        self.assert_rejected(mesh, "torso_collapse_experimental")

    def test_semantics_cannot_be_deleted_to_skip_required_check(self):
        mesh = topology_fixture()
        del mesh["semantic_regions"]["left_sleeve"]
        self.assert_rejected(mesh, "semantic_regions")

    def test_nonfinite_and_invalid_indices_reject_before_geometry(self):
        for value in (float("nan"), float("inf"), True, "0", 10**400):
            mesh = topology_fixture()
            mesh["vertices"][0][0] = value
            self.assert_rejected(mesh, "finite_vertices")
        for value in (-1, 999, 1.0, True):
            mesh = topology_fixture()
            mesh["faces"][0][0] = value
            self.assert_rejected(mesh, "face_indices")

    def test_extreme_finite_coordinates_do_not_pass_or_emit_infinity(self):
        for value in (1e308, 10**160):
            mesh = topology_fixture()
            mesh["vertices"][0][0] = value
            report = self.assert_rejected(mesh, "numerical_scale")
            json.dumps(report, allow_nan=False)

    def test_uniform_scale_does_not_change_pass(self):
        for scale in (1e-4, 1, 1e4):
            mesh = topology_fixture()
            mesh["vertices"] = [[x*scale for x in v] for v in mesh["vertices"]]
            self.assertTrue(evaluator.evaluate_mesh(mesh)["implementedStructuralChecksPassed"])

    def test_evaluation_is_read_only_and_deterministic(self):
        mesh = topology_fixture()
        original = copy.deepcopy(mesh)
        first = evaluator.evaluate_mesh(mesh)
        self.assertEqual(first, evaluator.evaluate_mesh(mesh))
        self.assertEqual(mesh, original)

    def test_cli_saves_partial_report_and_rejects_negative(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, output = Path(temporary)/"mesh.json", Path(temporary)/"report.json"
            source.write_text(json.dumps(topology_fixture()))
            command = [sys.executable, str(MODULE), str(source), "--output", str(output)]
            run = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            report = json.loads(output.read_text())
            self.assertEqual(len(report["inputSha256"]), 64)
            self.assertFalse(report["completeGarmentAcceptancePassed"])
            source.write_text('{"vertices": []}')
            run = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(run.returncode, 1, run.stderr)
            self.assertFalse(json.loads(output.read_text())["completeGarmentAcceptancePassed"])


class GeneratedGarmentIntegrationTests(unittest.TestCase):
    """Check the actual PoC generator, with surgical geometry-only mutations."""

    assert_rejected = EvaluatorTests.assert_rejected

    def actual_mesh(self):
        spec = importlib.util.spec_from_file_location("poc_geometry_for_evaluator", MODULE.with_name("geometry.py"))
        geometry = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(geometry)
        return geometry.build_mesh({})

    def test_actual_default_has_four_real_openings_and_partial_result(self):
        report = evaluator.evaluate_mesh(self.actual_mesh())
        self.assertTrue(report["implementedStructuralChecksPassed"], report)
        self.assertEqual(report["checks"]["boundary_loops"]["metrics"]["actualLoopCount"], 4)
        self.assertEqual(report["decision"], "partial_checks_only")

    def test_actual_neck_cap_is_rejected_by_actual_boundary_matching(self):
        mesh = self.actual_mesh()
        ring = mesh["openings"]["neck"]
        center = len(mesh["vertices"])
        mesh["vertices"].append([sum(mesh["vertices"][v][axis] for v in ring)/len(ring) for axis in range(3)])
        mesh["faces"].extend([[ring[i], ring[(i+1) % len(ring)], center] for i in range(len(ring))])
        result = self.assert_rejected(mesh, "actual_openings_match")
        self.assertEqual(result["checks"]["face_indices"]["status"], "passed")
        self.assertEqual(result["checks"]["boundary_loops"]["metrics"]["actualLoopCount"], 3)

    def test_actual_missing_sleeve_is_rejected_without_bad_indices(self):
        mesh = self.actual_mesh()
        sleeve = set(mesh["semantic_regions"]["left_sleeve"])
        before = len(mesh["faces"])
        mesh["faces"] = [face for face in mesh["faces"] if not set(face) <= sleeve]
        self.assertLess(len(mesh["faces"]), before)
        result = self.assert_rejected(mesh, "actual_openings_match")
        self.assertEqual(result["checks"]["face_indices"]["status"], "passed")
        self.assertEqual(result["checks"]["degenerate_faces"]["status"], "passed")

    def test_actual_detached_sleeve_seam_is_rejected_with_same_positions(self):
        mesh = self.actual_mesh()
        sleeve = set(mesh["semantic_regions"]["left_sleeve"])
        root = sleeve.intersection(mesh["semantic_regions"]["torso"])
        copies = {}
        for vertex in sorted(root):
            copies[vertex] = len(mesh["vertices"])
            mesh["vertices"].append(mesh["vertices"][vertex][:])
        replaced = 0
        for face in mesh["faces"]:
            if set(face) <= sleeve:
                for i, vertex in enumerate(face):
                    if vertex in copies:
                        face[i] = copies[vertex]
                        replaced += 1
        self.assertGreater(replaced, 0)
        result = self.assert_rejected(mesh, "connected_surface")
        self.assertIn("actual_openings_match", result["failedChecks"])
        for name in ("face_indices", "degenerate_faces", "edge_orientation"):
            self.assertEqual(result["checks"][name]["status"], "passed")

    def test_actual_flattened_depth_is_rejected_by_named_experimental_check(self):
        mesh = self.actual_mesh()
        for vertex in mesh["vertices"]:
            vertex[2] *= 0.01
        result = self.assert_rejected(mesh, "torso_collapse_experimental")
        self.assertEqual(result["checks"]["actual_openings_match"]["status"], "passed")


if __name__ == "__main__":
    unittest.main(verbosity=2)
