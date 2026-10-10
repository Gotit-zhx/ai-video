"""Preservation and actual-surface checks for the optional chest repair."""
from copy import deepcopy
import json
from pathlib import Path
import unittest

import numpy as np

from geometry_v2 import build_mesh as v2
from geometry_v3 import apply_chest_relaxation, build_mesh, measure_chest_trough, sample_front_depth
from sleeve_detail import audit_geometry


BASE = Path(__file__).resolve().parent.parent / "output/garment-upgrade-20260911/real-after.json"


class ChestRelaxationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = json.loads(BASE.read_text())
        cls.p = cls.base["params"]
        cls.mesh = build_mesh({**cls.p, "chest_relaxation": 1})

    def test_zero_strength_preserves_v2_surface_at_both_resolutions(self):
        for resolution in ("fit", "preview"):
            base, candidate = v2(self.p, resolution, "numpy"), build_mesh(self.p, resolution)
            for key in ("vertices", "faces", "openings", "semantic_regions", "face_regions"):
                self.assertEqual(candidate[key], base[key])

    def test_input_and_all_boundaries_sleeves_back_and_center_are_unchanged(self):
        before = deepcopy(self.base)
        result, report = apply_chest_relaxation(self.base, 1)
        self.assertEqual(self.base, before)
        old, new = np.asarray(self.base["vertices"]), np.asarray(result["vertices"])
        np.testing.assert_array_equal(old[:, :2], new[:, :2])
        groups = self.base["semantic_regions"]
        ids = set(groups["left_sleeve"]) | set(groups["right_sleeve"]) | set(groups["back"])
        ids |= {i for loop in self.base["openings"].values() for i in loop}
        ids |= set(np.flatnonzero(abs(old[:, 0]) < 1e-12))
        np.testing.assert_array_equal(old[sorted(ids)], new[sorted(ids)])
        self.assertGreater(report["affectedVertexCount"], 0)
        self.assertTrue(np.all(new[:, 2] >= old[:, 2]))
        self.assertLess(report["maximumDisplacement"], .045 * self.p["torso_width"])

    def test_actual_triangle_trough_is_reduced_by_over_sixty_percent(self):
        for resolution in ("fit", "preview"):
            old = measure_chest_trough(v2(self.p, resolution, "numpy"))
            new = measure_chest_trough(build_mesh({**self.p, "chest_relaxation": 1}, resolution))
            self.assertGreater(old["maximumTroughDepth"], .015)
            self.assertLess(new["maximumTroughDepth"], old["maximumTroughDepth"] * .40)
            self.assertEqual(new["sections"][0]["troughDepth"], 0)

    def test_actual_cross_sections_do_not_gain_lateral_bumps(self):
        xs = np.linspace(0, .46 * self.p["torso_width"], 101)
        for y in np.linspace(.70, .90, 9):
            zs = sample_front_depth(self.mesh, np.stack([xs, np.full_like(xs, y)], axis=1))
            # Tiny discretization errors are tolerated; old per-column gating
            # created rises of ~0.0005 per sample and is rejected by this bound.
            self.assertLess(float(np.diff(zs).max()), 2e-5)

    def test_same_single_shell_and_no_new_invalid_faces(self):
        self.assertEqual(self.mesh["faces"], self.base["faces"])
        self.assertEqual(self.mesh["openings"], self.base["openings"])
        audit = audit_geometry(self.mesh)
        for key in ("degenerateFaces", "duplicateOrReverseFaces", "nonManifoldEdges", "inconsistentSharedEdgeWinding"):
            self.assertEqual(audit[key], 0)
        self.assertEqual(len(audit["boundaryLoops"]), 4)

    def test_strength_scales_the_same_bounded_correction(self):
        old = v2(self.p, "preview", "numpy")
        half = build_mesh({**self.p, "chest_relaxation": .5})
        np.testing.assert_allclose(np.asarray(half["vertices"]) - old["vertices"],
                                   .5 * (np.asarray(self.mesh["vertices"]) - old["vertices"]), atol=1e-14, rtol=0)

    def test_invalid_strength_is_rejected(self):
        for strength in (True, -.1, 1.1, float("nan"), "1"):
            with self.subTest(strength=strength), self.assertRaises(ValueError):
                build_mesh({**self.p, "chest_relaxation": strength})


class ChestSupportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = json.loads(BASE.read_text())
        cls.p = cls.base['params']
        cls.mesh = build_mesh({**cls.p, 'chest_relaxation': 1, 'chest_support': 1})

    def test_central_chest_is_supported_at_both_resolutions(self):
        for resolution in ('fit', 'preview'):
            mesh = build_mesh({**self.p, 'chest_support': 1}, resolution)
            # Fixed world stations on this garment: the upper chest must not
            # remain recessed relative to the waist, as in the old mesh.
            waist, chest = sample_front_depth(mesh, [[0, .5], [0, .85]])
            self.assertGreater(chest, waist * .97)
            self.assertGreater(chest, .235)

    def test_supported_cross_sections_are_single_convex_lobes(self):
        xs = np.linspace(0, .28, 81)
        for y in (.65, .76, .85, .95):
            zs = sample_front_depth(self.mesh, np.column_stack([xs, np.full_like(xs, y)]))
            self.assertLess(float(np.diff(zs).max()), 2e-5)
            # A pinched centre / two local bumps would violate monotonicity.
        # Check geometry symmetry at vertices: triangle diagonals can differ
        # across the centre and slightly perturb interpolated sample depths.
        from scipy.spatial import cKDTree
        vertices = np.asarray(self.mesh['vertices'])
        mirrored = vertices * [-1, 1, 1]
        self.assertLess(float(cKDTree(vertices).query(mirrored)[0].max()), 1e-12)

    def test_boundaries_sleeves_back_and_topology_preserved(self):
        old, new = np.asarray(v2(self.p, 'preview', 'numpy')['vertices']), np.asarray(self.mesh['vertices'])
        groups = self.base['semantic_regions']
        ids = set(groups['back']) | set(groups['left_sleeve']) | set(groups['right_sleeve'])
        ids |= {i for loop in self.base['openings'].values() for i in loop}
        np.testing.assert_array_equal(old[sorted(ids)], new[sorted(ids)])
        np.testing.assert_array_equal(old[:, :2], new[:, :2])
        self.assertEqual(self.base['faces'], self.mesh['faces'])
        audit = audit_geometry(self.mesh)
        self.assertEqual(audit['degenerateFaces'], 0)
        self.assertEqual(audit['nonManifoldEdges'], 0)

    def test_invalid_support_rejected(self):
        for value in (True, -.1, 1.1, float('nan'), '1', None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                build_mesh({**self.p, 'chest_support': value})


class BackDrapeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p = json.loads(BASE.read_text())['params']
        cls.before = build_mesh({**cls.p, 'chest_support': 1})
        cls.after = build_mesh({**cls.p, 'chest_support': 1, 'back_drape': 1})

    def test_back_only_moves_inward_and_front_support_stays(self):
        old, new = np.asarray(self.before['vertices']), np.asarray(self.after['vertices'])
        np.testing.assert_array_equal(old[:, :2], new[:, :2])
        self.assertTrue(np.all(new[:, 2] >= old[:, 2]))
        ids = self.before['semantic_regions']['front']
        np.testing.assert_array_equal(old[ids], new[ids])
        self.assertGreater(self.after['backDrape']['maximumInwardDisplacement'], .07)

    def test_no_sealed_or_disconnected_hem(self):
        self.assertEqual(self.before['faces'], self.after['faces'])
        self.assertEqual(self.before['openings'], self.after['openings'])
        audit = audit_geometry(self.after)
        self.assertEqual(len(audit['boundaryLoops']), 4)
        for key in ('degenerateFaces', 'nonManifoldEdges', 'inconsistentSharedEdgeWinding'):
            self.assertEqual(audit[key], 0)
        from evaluator import evaluate_mesh
        self.assertTrue(evaluate_mesh(self.after)['implementedStructuralChecksPassed'])

    def test_sleeves_and_upper_openings_unchanged(self):
        groups = self.before['semantic_regions']
        ids = set(groups['left_sleeve']) | set(groups['right_sleeve'])
        for loop in self.before['openings'].values():
            if max(self.before['vertices'][i][1] for i in loop) > .1:
                ids.update(loop)
        np.testing.assert_array_equal(np.asarray(self.before['vertices'])[sorted(ids)],
                                      np.asarray(self.after['vertices'])[sorted(ids)])

    def test_invalid_back_strength_rejected(self):
        for value in (True, -.1, 1.1, float('nan'), '1', None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                build_mesh({**self.p, 'back_drape': value})

    def test_fitted_transverse_back_ridge_is_rejected(self):
        from run_gpu_repair import back_profile_curvature, supported_chest_guard
        params = {**self.p, 'chest_support': 1, 'chest_relaxation': 1, 'back_drape': 1}
        initial = build_mesh(params)
        # Regression: this real search direction improved silhouette scores,
        # but introduced a transverse ridge around the armpit height.
        bad = build_mesh({**params, 'depth': .40179394218250714,
                          'chest_depth': .8554552143967367,
                          'waist_depth': 1.2739899352026065,
                          'body_lean': -.03472311524387055})
        bound = np.abs(back_profile_curvature(initial)) * 1.15 + .0002 * self.p['torso_width']
        self.assertTrue(np.all(np.abs(back_profile_curvature(initial)) <= bound))
        self.assertFalse(np.all(np.abs(back_profile_curvature(bad)) <= bound))
        self.assertTrue(supported_chest_guard(initial))
        self.assertFalse(supported_chest_guard(v2(self.p)))


if __name__ == "__main__":
    unittest.main()
