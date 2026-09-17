#!/usr/bin/env python3
"""Small, source-labelled sleeve detail PoC on a frozen v2.1 mesh.

The outer garment is never displaced. Cuffs gain a shared-edge rounded fold
and a short real inner wall; separate closed strips represent the visible
drop-shoulder seam and the narrow cuff hem. These strips are not sewn into the
garment, so this composite is explicitly not an accepted single-shell mesh.
All dimensions are relative modelling assumptions, not measured fabric sizes.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from copy import deepcopy
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

from geometry_v2 import _panel, build_mesh

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_BASE = ROOT / "output/garment-upgrade-20260911/real-after.json"
DEFAULT_OUT = ROOT / "output/garment-detail-20260911"
DEFAULT_SUPPLEMENT = ROOT / "output/garment-reference-supplement-20260911/cuff-interior.png"


def unit(a):
    a = np.asarray(a, dtype=float)
    return a / np.maximum(np.linalg.norm(a, axis=-1, keepdims=True), 1e-15)


def vertex_normals(vertices, faces):
    v, f = np.asarray(vertices), np.asarray(faces)
    n = np.zeros_like(v)
    face_n = np.cross(v[f[:, 1]] - v[f[:, 0]], v[f[:, 2]] - v[f[:, 0]])
    for j in range(3):
        np.add.at(n, f[:, j], face_n)
    return unit(n)


class SleeveSampler:
    """Exact indexing for the declared v2 structured grid; never guesses IDs."""
    def __init__(self, base, side):
        expected = build_mesh(base["params"], base["resolution"])
        if expected["vertices"] != base["vertices"] or expected["faces"] != base["faces"]:
            raise ValueError("Expected unchanged structured-panels/0.2.1 vertices and faces")
        panels = [_panel(base["params"], base["params"][k], base["resolution"])
                  for k in ("neck_depth", "back_neck_depth")]
        maps = [dict(enumerate(range(len(panels[0]["vertices"]))))]
        next_id = len(panels[0]["vertices"])
        back = {}
        for i in range(len(panels[1]["vertices"])):
            if i in panels[1]["seams"]:
                back[i] = i
            else:
                back[i] = next_id
                next_id += 1
        maps.append(back)
        grids = []
        for panel, mapping in zip(panels, maps):
            root = sorted(panel["groups"][side + "_sleeve_root"])
            extra = sorted(panel["groups"][side + "_sleeve"] - set(root))
            rows = len(root)
            local = [root] + [extra[i:i + rows] for i in range(0, len(extra), rows)]
            grids.append(np.array([[mapping[i] for i in col] for col in local]))
        self.grids, self.v = grids, np.asarray(base["vertices"])
        self.n = vertex_normals(self.v, base["faces"])
        self.side = side

    def sample(self, u, q):
        """q in [0,2): front from underarm to top, back to underarm."""
        q %= 2
        panel = 0 if q <= 1 else 1
        v = q if panel == 0 else 2 - q
        g = self.grids[panel]
        a, b = np.clip(u, 0, 1) * (g.shape[0] - 1), v * (g.shape[1] - 1)
        i, j = min(int(a), g.shape[0] - 2), min(int(b), g.shape[1] - 2)
        du, dv = a - i, b - j
        # Actual mesh quad diagonal is lower left -> upper right.
        if du >= dv:
            ids = [g[i, j], g[i + 1, j], g[i + 1, j + 1]]
            w = np.array([1 - du, du - dv, dv])
        else:
            ids = [g[i, j], g[i + 1, j + 1], g[i, j + 1]]
            w = np.array([1 - dv, du, dv - du])
        return w @ self.v[ids], unit(w @ self.n[ids])

    def u_speed(self, u, q):
        # A wide secant avoids amplifying triangle-diagonal derivative changes
        # into a sawtooth hem path on the frozen low-resolution sleeve grid.
        lo, hi = max(0, u - .12), min(1, u + .12)
        return np.linalg.norm(self.sample(hi, q)[0] - self.sample(lo, q)[0]) / (hi - lo)

    def cuff_station(self, depth, q):
        """Intersect sleeve surface with a plane parallel to the cuff rim."""
        lo, hi = 0.0, 1.0
        for _ in range(26):
            mid = (lo + hi) / 2
            distance = (self.sample(mid, q)[0] - self.cuff_center) @ self.cuff_axis
            if distance < -depth:
                lo = mid
            else:
                hi = mid
        return (lo + hi) / 2


def audit_geometry(mesh):
    v, f = np.asarray(mesh["vertices"]), np.asarray(mesh["faces"])
    areas = np.linalg.norm(np.cross(v[f[:, 1]] - v[f[:, 0]], v[f[:, 2]] - v[f[:, 0]]), axis=1) / 2
    keys = [tuple(sorted(x)) for x in f.tolist()]
    edges = defaultdict(list)
    for tri in f:
        for a, b in zip(tri, np.roll(tri, -1)):
            edges[tuple(sorted((int(a), int(b))))].append((int(a), int(b)))
    boundaries = [edge for edge, uses in edges.items() if len(uses) == 1]
    boundary_adj = defaultdict(set)
    for a, b in boundaries:
        boundary_adj[a].add(b)
        boundary_adj[b].add(a)
    unseen, loops = set(boundary_adj), []
    while unseen:
        stack, component = [next(iter(unseen))], set()
        while stack:
            i = stack.pop()
            if i in component:
                continue
            component.add(i)
            stack.extend(boundary_adj[i] - component)
        unseen -= component
        loops.append(sorted(component))
    return {"vertices": len(v), "faces": len(f), "finite": bool(np.isfinite(v).all()),
            "degenerateFaces": int(np.count_nonzero(areas <= 1e-12)),
            "minimumTriangleArea": float(areas.min()),
            "duplicateOrReverseFaces": len(keys) - len(set(keys)),
            "nonManifoldEdges": sum(len(uses) > 2 for uses in edges.values()),
            "inconsistentSharedEdgeWinding": sum(len(uses) == 2 and uses[0] == uses[1] for uses in edges.values()),
            "boundaryLoops": loops,
            "boundaryVerticesAllDegreeTwo": all(len(x) == 2 for x in boundary_adj.values())}


def build_sleeve_detail(base_mesh, supplement_path=DEFAULT_SUPPLEMENT):
    mesh = deepcopy(base_mesh)
    vertices, faces = mesh["vertices"], mesh["faces"]
    base_nv, base_nf = len(vertices), len(faces)
    width = base_mesh["params"]["torso_width"]
    thickness, fold_width = .0030 * width, .045 * width
    layers = []
    terminal_loops = {}
    base_normals = vertex_normals(np.asarray(vertices), faces)

    def vertex(p):
        vertices.append(np.asarray(p).tolist())
        return len(vertices) - 1

    def connect(a, b, reverse=False):
        for j in range(len(a)):
            k = (j + 1) % len(a)
            tris = [[a[j], b[j], b[k]], [a[j], b[k], a[k]]]
            faces.extend([t[::-1] if reverse else t for t in tris])

    def layer(name, start_v, start_f, meaning, source, construction):
        ids = list(range(start_f, len(faces)))
        mesh["face_regions"][name] = ids
        mesh["semantic_regions"][name] = list(range(start_v, len(vertices)))
        layers.append({"id": name, "faceRange": [start_f, len(faces)],
                       "vertexRange": [start_v, len(vertices)], "meaning": meaning,
                       "evidence": source, "construction": construction})

    samplers, inner_wall_grids = {}, {}
    for side in ("left", "right"):
        sampler = samplers[side] = SleeveSampler(base_mesh, side)
        loop = base_mesh["openings"][side + "_cuff"]
        start_v, start_f = len(vertices), len(faces)
        cuff = np.asarray(base_mesh["vertices"])[loop]
        radial = base_normals[loop]
        # The cuff is planar, with an outward normal selected along the sleeve.
        axis = unit(np.cross(cuff[1] - cuff[0], cuff[-1] - cuff[0]))
        out_hint = cuff.mean(axis=0) - np.asarray(base_mesh["vertices"])[base_mesh["semantic_regions"][side + "_sleeve_root"]].mean(axis=0)
        if np.dot(axis, out_hint) < 0:
            axis = -axis
        sampler.cuff_axis, sampler.cuff_center = axis, cuff.mean(axis=0)
        # Use plane-radial directions so the folded rim remains a narrow annulus.
        radial = unit(radial - np.outer(radial @ axis, axis))
        prev = loop
        for theta in np.linspace(0, np.pi, 7)[1:]:
            ps = cuff - .5 * thickness * (1 - np.cos(theta)) * radial + .5 * thickness * np.sin(theta) * axis
            ring = [vertex(p) for p in ps]
            connect(prev, ring)
            prev = ring
        inner_rim = prev[:]
        wall_rings = [inner_rim]
        # Follow the actual sleeve surface inward; inner boundary is disclosed.
        point_q = {}
        grid0, grid1 = sampler.grids
        rows = grid0.shape[1] - 1
        for j in range(rows + 1):
            point_q[int(grid0[-1, j])] = j / rows
            point_q[int(grid1[-1, j])] = (2 - j / rows) % 2
        # An actual plane parallel to the cuff gives a uniform fold depth.
        # Neither a local inverse derivative (hooks) nor a constant parameter
        # station (wedge-shaped band on short underarms) gives this geometry.
        for t in np.linspace(0, 1, 5)[1:]:
            ring = []
            for idx in loop:
                q = point_q[idx]
                p, n = sampler.sample(sampler.cuff_station(t * fold_width, q), q)
                ring.append(vertex(p - thickness * n))
            connect(prev, ring)
            prev = ring
            wall_rings.append(ring)
        inner_wall_grids[side] = {"rings": wall_rings,
                                  "q": [point_q[idx] for idx in loop]}
        terminal_loops[side + "_cuff"] = prev[:]
        mesh["semantic_regions"][side + "_cuff_inner_rim"] = inner_rim
        layer(side + "_cuff_fold", start_v, start_f,
              "Real rounded cuff edge and short turned-in inner wall; no cap",
              "Narrow hem visible in all four views; inner lip visible in left/right",
              "Shared original cuff boundary; thickness and hidden wall length inferred")

    def closed_strip(side, name, center_u, band_width, height, meaning, source):
        sampler = samplers[side]
        start_v, start_f = len(vertices), len(faces)
        rings = []
        # Tiny closed three-dimensional ribbon, not a texture or reverse face.
        profile = [(-.5, .10), (-.38, .58), (0, 1), (.38, .58), (.5, .10), (0, .035)]
        for q in np.linspace(0, 2, 144, endpoint=False):
            u = center_u(q)
            du = band_width / sampler.u_speed(u, q)
            ring = []
            for lateral, h in profile:
                p, n = sampler.sample(u + lateral * du, q)
                ring.append(vertex(p + height * h * n))
            rings.append(ring)
        for i in range(len(rings)):
            # Choose orientation from geometry; signed volume fixes globally.
            connect(rings[i], rings[(i + 1) % len(rings)])
        vv, ff = np.asarray(vertices), np.asarray(faces[start_f:])
        volume6 = np.sum(np.einsum("ij,ij->i", vv[ff[:, 0]], np.cross(vv[ff[:, 1]], vv[ff[:, 2]])))
        if volume6 < 0:
            faces[start_f:] = [f[::-1] for f in faces[start_f:]]
        layer(name, start_v, start_f, meaning, source,
              "Separate closed raised ribbon; not sewn or Boolean-unioned to the base")

    for side in ("left", "right"):
        # Dropped shoulder: seam descends along the upper sleeve, returns near
        # the underarm root. Hidden arc location is a bounded modelling prior.
        def seam_u(q):
            v = q if q <= 1 else 2 - q
            return .035 + .245 * np.sin(np.pi * v / 2) ** 2
        sampler = samplers[side]
        # Smooth only the seam path on the unchanged sleeve surface. A dense
        # convolution in circumferential coordinate removes the ridge's sharp
        # peak over the frozen grid's top seam without inventing cloth folds.
        qs = np.linspace(0, 2, 144, endpoint=False)
        path_y = np.array([sampler.sample(seam_u(q), q)[0][1] for q in qs])
        weights = np.exp(-np.arange(-6, 7) ** 2 / (2 * 2.3 ** 2))
        weights /= weights.sum()
        smooth_y = sum(w * np.roll(path_y, k) for k, w in zip(range(-6, 7), weights))
        corrected_u = []
        for q, target_y in zip(qs, smooth_y):
            center = seam_u(q)
            lo, hi = max(.008, center - .03), min(.6, center + .03)
            for _ in range(24):
                mid = (lo + hi) / 2
                if sampler.sample(mid, q)[0][1] > target_y:
                    lo = mid
                else:
                    hi = mid
            corrected_u.append((lo + hi) / 2)
        def smooth_seam_u(q):
            return float(np.interp(q % 2, np.r_[qs, 2], np.r_[corrected_u, corrected_u[0]]))
        closed_strip(side, side + "_drop_shoulder_seam", smooth_seam_u, .008 * width,
                     .0023 * width, "Low raised dropped-shoulder joining seam",
                     "Visible sloped front/back seam and curved left/right sleeve-top seam; hidden continuation inferred")
        def cuff_u(q, sampler=sampler):
            return sampler.cuff_station(fold_width, q)
        closed_strip(side, side + "_cuff_hem_seam", cuff_u, .0038 * width,
                     .0012 * width, "Narrow low relief hem stitch/turn line",
                     "Narrow parallel cuff band is visible; exact stitch count and height unknown")

    pre_supplement_nv, pre_supplement_nf = len(vertices), len(faces)
    inner_normals = vertex_normals(np.asarray(vertices), faces)
    supplementary_source = {
        "path": str(Path(supplement_path)),
        "sha256": hashlib.sha256(Path(supplement_path).read_bytes()).hexdigest(),
        "evidenceClass": "AI-generated design reference; not a measured real garment",
        "observedFeature": "Central inner longitudinal seam meets narrow circumferential cuff hem around image (485,720)",
        "adoptedGeometry": "Short inner longitudinal seam crossing a local hem seam on the existing turned-in cuff wall",
        "excluded": "Unverified root/underarm structure and the rejected underarm-junction crop",
        "parametersDriven": {"innerFoldFractionRange": [.10, .90], "crossingFoldFraction": .62,
                             "junctionWidth": .018 * width, "longitudinalSeamWidth": .0028 * width,
                             "reliefHeight": .0012 * width,
                             "parameterStatus": "Relative low-relief modelling choices guided by the AI image; no physical scale"},
    }

    def inner_sample(side, t, q):
        """Barycentric sample on actual existing inner-wall triangles."""
        record = inner_wall_grids[side]
        rings = record["rings"]
        count = len(record["q"])
        mapping = {int(round(angle * count / 2)) % count: j for j, angle in enumerate(record["q"])}
        angular = (q % 2) * count / 2
        low = int(angular) % count
        a, b = mapping[low], mapping[(low + 1) % count]
        frac_q = angular - int(angular)
        position = np.clip(t, 0, 1) * (len(rings) - 1)
        row = min(int(position), len(rings) - 2)
        frac_t = position - row
        aa, ab, ba, bb = rings[row][a], rings[row][b], rings[row + 1][a], rings[row + 1][b]
        if b == (a + 1) % count:
            if frac_t >= frac_q:
                ids, w = [aa, ba, bb], [1 - frac_t, frac_t - frac_q, frac_q]
            else:
                ids, w = [aa, bb, ab], [1 - frac_q, frac_t, frac_q - frac_t]
        else:
            if frac_t + frac_q <= 1:
                ids, w = [aa, ba, ab], [1 - frac_t - frac_q, frac_t, frac_q]
            else:
                ids, w = [ba, bb, ab], [1 - frac_q, frac_t + frac_q - 1, 1 - frac_t]
        return np.asarray(w) @ np.asarray(vertices)[ids], unit(np.asarray(w) @ inner_normals[ids])

    for side in ("left", "right"):
        start_v, start_f = len(vertices), len(faces)
        # Raised height field on one tiny closed patch gives a clean intersection
        # without overlapping longitudinal/transverse tubes or giant stitches.
        q_speed = np.linalg.norm(inner_sample(side, .5, .002)[0] - inner_sample(side, .5, -.002)[0]) / .004
        half_width = .009 * width
        half_q = half_width / q_speed
        rows, cols = 25, 25
        clearance, relief = .00008 * width, .0012 * width
        top_grid, bottom_grid, support_points, support_normals = [], [], [], []
        for r, t in enumerate(np.linspace(.10, .90, rows)):
            top_row, bottom_row = [], []
            # Smooth fade to a shallow closed termination; no triangular tip.
            longitudinal_fade = np.sin(np.pi * r / (rows - 1)) ** .5
            for c, s in enumerate(np.linspace(-1, 1, cols)):
                q = s * half_q
                point, normal = inner_sample(side, float(t), float(q))
                support_points.append(point.tolist())
                support_normals.append(normal.tolist())
                long = np.exp(-(s / .155) ** 2) * longitudinal_fade
                transverse = np.exp(-((t - .62) / .045) ** 2) * np.cos(np.pi * s / 2) ** 2
                bump = relief * (long + transverse - long * transverse)
                top_row.append(vertex(point + (clearance + bump) * normal))
                bottom_row.append(vertex(point + clearance * .35 * normal))
            top_grid.append(top_row)
            bottom_grid.append(bottom_row)
        for r in range(rows - 1):
            for c in range(cols - 1):
                a, b, d, e = top_grid[r][c], top_grid[r + 1][c], top_grid[r][c + 1], top_grid[r + 1][c + 1]
                faces.extend([[a, b, e], [a, e, d]])
                a, b, d, e = bottom_grid[r][c], bottom_grid[r + 1][c], bottom_grid[r][c + 1], bottom_grid[r + 1][c + 1]
                faces.extend([[a, e, b], [a, d, e]])
        perimeter = [(0, c) for c in range(cols)] + [(r, cols - 1) for r in range(1, rows)] + [(rows - 1, c) for c in range(cols - 2, -1, -1)] + [(r, 0) for r in range(rows - 2, 0, -1)]
        top_edge = [top_grid[r][c] for r, c in perimeter]
        bottom_edge = [bottom_grid[r][c] for r, c in perimeter]
        connect(top_edge, bottom_edge, reverse=True)
        vv, ff = np.asarray(vertices), np.asarray(faces[start_f:])
        volume6 = np.sum(np.einsum("ij,ij->i", vv[ff[:, 0]], np.cross(vv[ff[:, 1]], vv[ff[:, 2]])))
        if volume6 < 0:
            faces[start_f:] = [f[::-1] for f in faces[start_f:]]
        layer(side + "_inner_longitudinal_hem_junction", start_v, start_f,
              "Small real low-relief inner longitudinal seam / cuff hem seam intersection",
              "AI cuff-interior supplement: visible central longitudinal seam meets inner hem; design assumption only",
              "One closed shallow patch on barycentrically sampled existing inner wall; short smooth ends; not sewn into shell")
        layers[-1]["source"] = deepcopy(supplementary_source)
        layers[-1]["supportPoints"] = support_points
        layers[-1]["supportNormals"] = support_normals
        layers[-1]["innerFoldFractionRange"] = [.10, .90]
        layers[-1]["crossingFoldFraction"] = .62
        layers[-1]["maximumSurfaceRelief"] = clearance + relief

    mesh["geometryVersion"] = "sleeve-detail-poc/0.2.0"
    mesh["representation"] = "shared-cuff-fold-shell-plus-unsewn-detail-strips"
    mesh["baseVertexCount"], mesh["baseFaceCount"] = base_nv, base_nf
    mesh["preSupplementVertexCount"], mesh["preSupplementFaceCount"] = pre_supplement_nv, pre_supplement_nf
    mesh["supplementaryReferences"] = [supplementary_source]
    mesh["detailLayers"] = layers
    mesh["visibleCuffRims"] = {key: mesh["openings"][key][:] for key in ("left_cuff", "right_cuff")}
    mesh["openings"] = {"neck": mesh["openings"]["neck"], "hem": mesh["openings"]["hem"], **terminal_loops}
    mesh["boundaryOpenings"] = deepcopy(mesh["openings"])
    mesh["openingSemantics"] = "openings contains true boundary loops: neck/hem and folded cuff inner-wall terminations; visibleCuffRims separately preserves the outer cuff edge indices"
    mesh["limitations"] = [
        "Not a 1:1 reconstruction. Four AI images do not determine hidden structure or physical dimensions.",
        "Base body, neckline, sleeve volume and every original vertex/face are unchanged.",
        "Only cuff edge has local thickness. Inner wall ends after a short assumed fold depth; garment is not a complete two-sided fabric solid.",
        "Shoulder and cuff seam strips are separate closed components, not sewn or unioned into the garment. Old single-shell acceptance is not applicable.",
        "Drop shoulder path is an approximate reference-guided shape; hidden underarm continuation, depth and thickness are inferred.",
        "Natural folds, exact front/back fullness, rib knit, thread topology and fabric mechanics are not reconstructed.",
        "Inner longitudinal/hem junction is an AI-reference design assumption confined to the short cuff fold; it does not establish real hidden sewing or underarm structure.",
    ]
    checks = audit_geometry(mesh)
    report = {"status": "limited-detail-prototype", "oneToOneReconstructionAccepted": False,
              "oldSingleShellAcceptanceApplicable": False,
              "originalVerticesExactlyPreserved": mesh["vertices"][:base_nv] == base_mesh["vertices"],
              "originalFacesExactlyPreserved": mesh["faces"][:base_nf] == base_mesh["faces"],
              "sharedSleeveRootsPreserved": True,
              "fourTrueBoundaryLoops": len(checks["boundaryLoops"]) == 4,
              "visibleOuterCuffRimsExactlyPreserved": all(mesh["visibleCuffRims"][key] == base_mesh["openings"][key] for key in mesh["visibleCuffRims"]),
              "detailParameters": {"units": "relative/inferred", "foldThickness": thickness,
                                   "innerFoldDepthAlongCuffNormal": fold_width,
                                   "foldTermination": "intersection with plane parallel to the outer cuff rim",
                                   "shoulderSeamWidth": .008 * width,
                                   "shoulderSeamHeight": .0023 * width},
              "implementedChecks": checks, "layers": layers, "supplementaryReferences": [supplementary_source],
              "limitations": mesh["limitations"]}
    return mesh, report


def detail_only(mesh):
    first = mesh["baseFaceCount"]
    selected_faces = mesh["faces"][first:]
    ids = sorted({i for face in selected_faces for i in face})
    remap = {i: j for j, i in enumerate(ids)}
    return {"schemaVersion": mesh["schemaVersion"], "geometryVersion": mesh["geometryVersion"],
            "representation": "isolated-visible-detail-components", "units": mesh["units"],
            "vertices": [mesh["vertices"][i] for i in ids],
            "faces": [[remap[i] for i in f] for f in selected_faces],
            "sourceVertexIndices": ids, "openings": {}, "limitations": mesh["limitations"]}


def render_shaded(mesh, yaw=0, pitch=0, size=900, crop=None, diagnostic=False):
    """Deterministic z-buffer QA; same material, actual triangle depth, no lines."""
    v, f = np.asarray(mesh["vertices"]), np.asarray(mesh["faces"])
    y, p = np.radians([yaw, pitch])
    right = np.array([np.cos(y), 0, -np.sin(y)])
    up = np.array([-np.sin(y) * np.sin(p), np.cos(p), -np.cos(y) * np.sin(p)])
    toward = np.cross(right, up)
    xyz = np.stack([v @ right, v @ up, v @ toward], axis=1)
    if crop is None:
        crop = [-.77, .77, -.04, 1.30]
    xmin, xmax, ymin, ymax = crop
    scale = min((size - 30) / (xmax - xmin), (size - 30) / (ymax - ymin))
    x = (xyz[:, 0] - (xmin + xmax) / 2) * scale + size / 2
    yy = size / 2 - (xyz[:, 1] - (ymin + ymax) / 2) * scale
    screen = np.stack([x, yy], axis=1)
    zbuf = np.full((size, size), -np.inf)
    rgb = np.full((size, size, 3), [238, 239, 241], dtype=np.uint8)
    vn = vertex_normals(v, f)
    # Light at upper screen left: neutral clay makes low relief inspectable.
    light = unit(-right * .5 + up * .75 + toward * .6)
    face_light = np.sum(vn * light, axis=1)
    for fi, tri in enumerate(f):
        a, b, c = screen[tri]
        minx, maxx = max(0, int(np.floor(min(a[0], b[0], c[0])))), min(size - 1, int(np.ceil(max(a[0], b[0], c[0]))))
        miny, maxy = max(0, int(np.floor(min(a[1], b[1], c[1])))), min(size - 1, int(np.ceil(max(a[1], b[1], c[1]))))
        if minx > maxx or miny > maxy:
            continue
        den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(den) < 1e-10:
            continue
        xx, yyy = np.meshgrid(np.arange(minx, maxx + 1) + .5, np.arange(miny, maxy + 1) + .5)
        w0 = ((b[1] - c[1]) * (xx - c[0]) + (c[0] - b[0]) * (yyy - c[1])) / den
        w1 = ((c[1] - a[1]) * (xx - c[0]) + (a[0] - c[0]) * (yyy - c[1])) / den
        w2 = 1 - w0 - w1
        zz = w0 * xyz[tri[0], 2] + w1 * xyz[tri[1], 2] + w2 * xyz[tri[2], 2]
        buf = zbuf[miny:maxy + 1, minx:maxx + 1]
        mask = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6) & (zz > buf)
        if not mask.any():
            continue
        shade = np.clip(.49 + .42 * (w0 * face_light[tri[0]] + w1 * face_light[tri[1]] + w2 * face_light[tri[2]]), .20, .96)
        material = np.array([206, 197, 176])
        if diagnostic and fi >= mesh.get("baseFaceCount", len(f)):
            material = np.array([101, 178, 213])
        colors = (shade[..., None] * material).astype(np.uint8)
        rgb[miny:maxy + 1, minx:maxx + 1][mask] = colors[mask]
        buf[mask] = zz[mask]
    return rgb


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, default=DEFAULT_BASE)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--no-render", action="store_true")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    base_bytes = args.base.read_bytes()
    mesh, report = build_sleeve_detail(json.loads(base_bytes))
    report["baseMeshPath"] = str(args.base)
    report["baseMeshSha256"] = hashlib.sha256(base_bytes).hexdigest()
    observations_path = args.output_dir / "sleeve-reference-observations.json"
    if observations_path.exists():
        observation_bytes = observations_path.read_bytes()
        report["referenceObservations"] = {"path": observations_path.name,
                                           "sha256": hashlib.sha256(observation_bytes).hexdigest(),
                                           "sources": json.loads(observation_bytes)["sources"],
                                           "use": "Approximate visible seam/hem shape; no physical thickness or hidden-structure calibration"}
    for name, data in (("sleeve-detail.json", mesh), ("detail-layers.json", detail_only(mesh))):
        data_bytes = (json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode()
        (args.output_dir / name).write_bytes(data_bytes)
        if name == "sleeve-detail.json":
            report["meshSha256"] = hashlib.sha256(data_bytes).hexdigest()
    if not args.no_render:
        views = [("front", 0, 0), ("back", 180, 0), ("left", 270, 0), ("right", 90, 0),
                 ("front-oblique", 45, 12), ("under-cuff", 90, -35)]
        for name, yaw, pitch in views:
            rgb = render_shaded(mesh, yaw, pitch)
            cv2.imwrite(str(args.output_dir / ("qa-" + name + ".png")), cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
        # Exact same orthographic detail camera for baseline and added geometry.
        crop = [.20, .68, .36, 1.18]
        for label, target in (("base", json.loads(base_bytes)), ("detail", mesh)):
            rgb = render_shaded(target, 0, 0, crop=crop)
            cv2.imwrite(str(args.output_dir / ("qa-sleeve-front-" + label + ".png")), cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
        rgb = render_shaded(mesh, 0, 0, crop=crop, diagnostic=True)
        cv2.imwrite(str(args.output_dir / "qa-sleeve-front-layers.png"), cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
    (args.output_dir / "sleeve-detail-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k in ("status", "originalVerticesExactlyPreserved", "originalFacesExactlyPreserved", "meshSha256")}, ensure_ascii=False))
    print(json.dumps({k: v for k, v in report["implementedChecks"].items() if k != "boundaryLoops"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
