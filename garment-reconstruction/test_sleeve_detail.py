"""Tests preserve the frozen shell and certify only the claimed local detail."""
from copy import deepcopy
import json
import unittest

import numpy as np

from sleeve_detail import DEFAULT_BASE, SleeveSampler, audit_geometry, build_sleeve_detail, detail_only, unit


class SleeveDetailTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = json.loads(DEFAULT_BASE.read_text())
        cls.before = deepcopy(cls.base)
        cls.mesh, cls.report = build_sleeve_detail(cls.base)

    def test_input_and_original_surface_untouched(self):
        self.assertEqual(self.base, self.before)
        self.assertEqual(self.mesh["vertices"][:len(self.base["vertices"])], self.base["vertices"])
        self.assertEqual(self.mesh["faces"][:len(self.base["faces"])], self.base["faces"])
        for region in ("torso", "neck", "left_sleeve_root", "right_sleeve_root"):
            for i in self.base["semantic_regions"][region]:
                self.assertEqual(self.mesh["vertices"][i], self.base["vertices"][i])

    def test_four_real_open_boundaries_without_cuff_caps(self):
        audit = audit_geometry(self.mesh)
        self.assertEqual(len(audit["boundaryLoops"]), 4)
        self.assertTrue(audit["boundaryVerticesAllDegreeTwo"])
        found = {frozenset(x) for x in audit["boundaryLoops"]}
        expected = {frozenset(x) for x in self.mesh["boundaryOpenings"].values()}
        self.assertEqual(found, expected)
        self.assertEqual({frozenset(x) for x in self.mesh["openings"].values()}, found)
        for name, rim in self.mesh["visibleCuffRims"].items():
            self.assertEqual(rim, self.base["openings"][name])
            self.assertNotIn(frozenset(rim), found)

    def test_cuff_aperture_loses_less_than_five_percent_area(self):
        v = np.asarray(self.mesh["vertices"])
        for side in ("left", "right"):
            outer = v[self.mesh["visibleCuffRims"][side + "_cuff"]]
            inner = v[self.mesh["semantic_regions"][side + "_cuff_inner_rim"]]
            axis = unit(np.cross(outer[1] - outer[0], outer[-1] - outer[0]))
            def area(points):
                return abs(np.sum(np.cross(points, np.roll(points, -1, axis=0)), axis=0) @ axis) / 2
            ratio = area(inner) / area(outer)
            self.assertGreater(ratio, .95)
            self.assertLess(ratio, 1)
            # A genuine narrow finite thickness, not duplicate reverse faces.
            thickness = np.linalg.norm(outer - inner, axis=1)
            self.assertGreater(float(thickness.min()), .001)
            self.assertLess(float(thickness.max()), .003)

    def test_no_degenerate_duplicate_or_inconsistently_wound_faces(self):
        audit = self.report["implementedChecks"]
        self.assertTrue(audit["finite"])
        for key in ("degenerateFaces", "duplicateOrReverseFaces", "nonManifoldEdges", "inconsistentSharedEdgeWinding"):
            self.assertEqual(audit[key], 0, key)

    def test_separate_ribbons_are_closed_and_real_low_relief(self):
        for layer in self.mesh["detailLayers"]:
            if not layer["id"].endswith("seam"):
                continue
            start, end = layer["faceRange"]
            fragment = {"vertices": self.mesh["vertices"], "faces": self.mesh["faces"][start:end]}
            self.assertEqual(audit_geometry(fragment)["boundaryLoops"], [])
            # Every six-point cross-section has real width and finite height.
            start_v, end_v = layer["vertexRange"]
            points = np.asarray(self.mesh["vertices"])[start_v:end_v].reshape(-1, 6, 3)
            distances = np.linalg.norm(points[:, 2] - points[:, 5], axis=1)
            self.assertGreater(float(distances.min()), .0005)
            self.assertLess(float(distances.max()), .002)

    def test_cuff_band_has_uniform_plane_depth_without_underarm_hook(self):
        v = np.asarray(self.mesh["vertices"])
        depth = self.report["detailParameters"]["innerFoldDepthAlongCuffNormal"]
        height = self.base["params"]["torso_width"] * .0012
        for side in ("left", "right"):
            outer = v[self.mesh["visibleCuffRims"][side + "_cuff"]]
            center = outer.mean(axis=0)
            axis = unit(np.cross(outer[1] - outer[0], outer[-1] - outer[0]))
            root = v[self.base["semantic_regions"][side + "_sleeve_root"]].mean(axis=0)
            if (center - root) @ axis < 0:
                axis = -axis
            layer = next(x for x in self.mesh["detailLayers"] if x["id"] == side + "_cuff_hem_seam")
            a, b = layer["vertexRange"]
            ridge = v[a:b].reshape(-1, 6, 3)[:, 2]
            distance = (ridge - center) @ axis
            self.assertLess(float(np.max(np.abs(distance + depth))), height * 1.01)

    def test_ai_inner_junction_is_closed_and_supported_by_existing_wall(self):
        v = np.asarray(self.mesh["vertices"])
        for side in ("left", "right"):
            layer = next(x for x in self.mesh["detailLayers"] if x["id"] == side + "_inner_longitudinal_hem_junction")
            a, b = layer["faceRange"]
            self.assertEqual(audit_geometry({"vertices": self.mesh["vertices"], "faces": self.mesh["faces"][a:b]})["boundaryLoops"], [])
            a, b = layer["vertexRange"]
            top = v[a:b:2]
            support = np.asarray(layer["supportPoints"])
            normals = np.asarray(layer["supportNormals"])
            heights = np.sum((top - support) * normals, axis=1)
            self.assertGreater(float(heights.min()), 0)
            self.assertLessEqual(float(heights.max()), layer["maximumSurfaceRelief"] * 1.000001)
            # Independently locate every support point on a pre-existing fold
            # triangle. This catches a ribbon sampled from the exterior shell.
            fold = next(x for x in self.mesh["detailLayers"] if x["id"] == side + "_cuff_fold")
            fa, fb = fold["faceRange"]
            tris = v[np.asarray(self.mesh["faces"][fa:fb])]
            e0, e1 = tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0]
            n = unit(np.cross(e0, e1))
            d00, d01, d11 = np.sum(e0 * e0, axis=1), np.sum(e0 * e1, axis=1), np.sum(e1 * e1, axis=1)
            denom = d00 * d11 - d01 * d01
            for point in support:
                relative = point - tris[:, 0]
                plane_dist = abs(np.sum(relative * n, axis=1))
                d20, d21 = np.sum(relative * e0, axis=1), np.sum(relative * e1, axis=1)
                x = (d11 * d20 - d01 * d21) / denom
                y = (d00 * d21 - d01 * d20) / denom
                self.assertTrue(np.any((plane_dist < 1e-9) & (x >= -1e-8) & (y >= -1e-8) & (x + y <= 1 + 1e-8)))
            self.assertIn("AI-generated", layer["source"]["evidenceClass"])
            self.assertEqual(layer["innerFoldFractionRange"], [.10, .90])

    def test_ai_supplement_preserves_previous_detail_geometry_and_outer_masks(self):
        from image_fit import render_mask
        previous = {"vertices": self.mesh["vertices"][:self.mesh["preSupplementVertexCount"]],
                    "faces": self.mesh["faces"][:self.mesh["preSupplementFaceCount"]]}
        snapshot = DEFAULT_BASE.parent.parent / "garment-detail-20260911/sleeve-detail-before-ai-supplement.json"
        if snapshot.exists():
            old = json.loads(snapshot.read_text())
            self.assertEqual(previous["vertices"], old["vertices"])
            self.assertEqual(previous["faces"], old["faces"])
        for yaw in (0, 90, 180, 270):
            camera = {"yaw": yaw, "pitch": 0, "scale": 130, "cx": 128, "cy": 215}
            self.assertTrue(np.array_equal(render_mask(previous, camera, 256), render_mask(self.mesh, camera, 256)))

    def test_disclosures_and_export_indices(self):
        self.assertFalse(self.report["oneToOneReconstructionAccepted"])
        self.assertFalse(self.report["oldSingleShellAcceptanceApplicable"])
        layer_mesh = detail_only(self.mesh)
        for face in layer_mesh["faces"]:
            self.assertTrue(all(0 <= i < len(layer_mesh["vertices"]) for i in face))
        self.assertEqual(len(layer_mesh["faces"]), len(self.mesh["faces"]) - len(self.base["faces"]))

    def test_sampler_rejects_modified_or_wrong_base(self):
        altered = deepcopy(self.base)
        altered["vertices"][10][0] += .001
        with self.assertRaises(ValueError):
            SleeveSampler(altered, "left")


if __name__ == "__main__":
    unittest.main()
