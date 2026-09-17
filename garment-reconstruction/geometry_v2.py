"""Version 2 structured garment surface; relative units, no cloth simulation.

The shoulder and sleeve outline share a Hermite tangent. Sleeve openings are
planar and by default normal to the sleeve axis. Body width, depth by height and
centre-line lean are independent geometric controls. Sleeve parameters never
move torso vertices. Front/back sewing edges and sleeve roots share actual
indices. This kernel does not issue reconstruction or acceptance verdicts.
"""

from __future__ import annotations

from collections import defaultdict
import math
from numbers import Real


SCHEMA_VERSION = "garment-mesh/0.1.0"
GEOMETRY_VERSION = "structured-panels/0.2.1"
DEFAULT_PARAMS = {
    "torso_width": 1.0, "body_length": 1.25, "depth": 0.28,
    "sleeve_length": 0.42, "sleeve_drop": 0.18,
    "neck_width": 0.34, "neck_depth": 0.18, "back_neck_depth": 0.07,
    "cuff_width": 0.34, "cuff_tilt": 0.0,
    "hem_flare": 0.03, "body_taper": 0.02,
    "chest_depth": 1.0, "waist_depth": 0.90, "hem_depth": 0.88,
    "shoulder_drop": 0.13, "body_lean": 0.0, "neck_setback": 0.0,
}
PARAM_BOUNDS = {
    "torso_width": (0.60, 1.5), "body_length": (0.8, 1.9), "depth": (0.12, 0.65),
    "sleeve_length": (0.08, 0.80), "sleeve_drop": (0.02, 0.60),
    "neck_width": (0.18, 0.58), "neck_depth": (0.05, 0.38),
    "back_neck_depth": (0.015, 0.20), "cuff_width": (0.18, 0.65),
    "cuff_tilt": (-0.50, 0.50), "hem_flare": (-0.10, 0.28),
    "body_taper": (-0.16, 0.22), "chest_depth": (0.50, 1.65),
    "waist_depth": (0.50, 1.65), "hem_depth": (0.50, 1.65),
    "shoulder_drop": (0.06, 0.24), "body_lean": (-0.20, 0.20),
    "neck_setback": (-0.15, 0.15),
}
PARAM_UNITS = {name: "relative_length" for name in DEFAULT_PARAMS}
PARAM_UNITS.update({name: "dimensionless_multiplier" for name in
                    ("chest_depth", "waist_depth", "hem_depth")})
PARAM_UNITS.update({name: "fraction_of_torso_width" for name in
                    ("hem_flare", "body_taper", "shoulder_drop")})
PARAM_UNITS["cuff_tilt"] = "radians_relative_to_axis_normal"
_SLEEVE_PARAMS = {"sleeve_length", "sleeve_drop", "cuff_width", "cuff_tilt"}
_RESOLUTIONS = {
    # shoulder columns, neck columns, lower rows, upper/root rows, sleeve columns
    "fit": (4, 12, 10, 8, 8),
    "preview": (10, 28, 26, 18, 18),
}


def _smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3.0 - 2.0 * t)


def _lerp(a, b, t):
    return a + (b - a) * t


def _hermite(a, b, da, db, t):
    t2, t3 = t * t, t * t * t
    return ((2 * t3 - 3 * t2 + 1) * a + (t3 - 2 * t2 + t) * da
            + (-2 * t3 + 3 * t2) * b + (t3 - t2) * db)


def _structure(p):
    width, length = p["torso_width"], p["body_length"]
    shoulder_y = length - p["shoulder_drop"] * width
    armpit_y = shoulder_y - 0.53 * width
    root_center = (shoulder_y + armpit_y) / 2.0
    angle = math.atan2(p["sleeve_drop"], p["sleeve_length"]) + p["cuff_tilt"]
    nx, ny = math.sin(angle), math.cos(angle)
    cuff_center = (0.5 * width + p["sleeve_length"], root_center - p["sleeve_drop"])
    half_cuff = 0.5 * p["cuff_width"]
    return {
        "shoulder_y": shoulder_y, "armpit_y": armpit_y,
        "root_center": root_center, "cuff_angle": angle,
        "cuff_top": (cuff_center[0] + half_cuff * nx, cuff_center[1] + half_cuff * ny),
        "cuff_bottom": (cuff_center[0] - half_cuff * nx, cuff_center[1] - half_cuff * ny),
    }


def validate_params(params=None):
    if params is None:
        params = {}
    if not isinstance(params, dict):
        raise ValueError("params must be a dictionary")
    unknown = set(params) - set(DEFAULT_PARAMS)
    if unknown:
        raise ValueError("unknown parameters: " + ", ".join(sorted(map(str, unknown))))
    p = DEFAULT_PARAMS.copy()
    for name, value in params.items():
        if isinstance(value, bool) or not isinstance(value, Real):
            raise ValueError(f"{name} must be a finite number")
        try:
            value = float(value)
        except (OverflowError, ValueError) as error:
            raise ValueError(f"{name} must be a finite number") from error
        lo, hi = PARAM_BOUNDS[name]
        if not math.isfinite(value) or not lo <= value <= hi:
            raise ValueError(f"{name} must be in [{lo}, {hi}]")
        p[name] = value
    width, length = p["torso_width"], p["body_length"]
    st = _structure(p)
    if p["neck_width"] > 0.72 * width:
        raise ValueError("neck_width must leave shoulders (<= 0.72 * torso_width)")
    if max(p["neck_depth"], p["back_neck_depth"]) >= length - st["armpit_y"] - 0.03 * width:
        raise ValueError("neck must remain above the armpit")
    if st["armpit_y"] < 0.12 * length:
        raise ValueError("body_length/shoulder_drop must leave a lower body")
    if not 0.015 <= st["cuff_angle"] <= 1.48:
        raise ValueError("cuff_tilt must leave the cuff top outwards and upwards")
    if st["cuff_bottom"][0] <= 0.501 * width:
        raise ValueError("sleeve_length/cuff_tilt must keep lower cuff outside the root")
    if st["cuff_bottom"][1] < 0.03 * length:
        raise ValueError("short sleeve cuff must remain above the hem")
    return p


def _width_factor(y, p, st):
    t = max(0.0, min(1.0, y / st["armpit_y"]))
    if t < 0.5:
        return _lerp(1.0 + p["hem_flare"], 1.0 - p["body_taper"], _smooth(2.0 * t))
    return _lerp(1.0 - p["body_taper"], 1.0, _smooth(2.0 * t - 1.0))


def _depth_factor(y, p):
    t = y / p["body_length"]
    if t < 0.42:
        return _lerp(p["hem_depth"], p["waist_depth"], _smooth(t / 0.42))
    return _lerp(p["waist_depth"], p["chest_depth"], _smooth((t - 0.42) / 0.32))


def _center_z(y, p):
    return p["body_lean"] * _smooth(y / p["body_length"])


def _shoulder(x, p, st):
    start, end = 0.5 * p["neck_width"], 0.5 * p["torso_width"]
    span = end - start
    drop = p["body_length"] - st["shoulder_y"]
    slope = -min(1.15, 2.3 * drop / span)
    y = _hermite(p["body_length"], st["shoulder_y"], -0.08 * span,
                 slope * span, (abs(x) - start) / span)
    return y, slope


def _segment_distance(x, y, segment):
    ax, ay, bx, by = segment
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(x - ax - t * dx, y - ay - t * dy)


def _body_seams(p, st):
    # Fixed analytical sampling is independent of output mesh resolution and of
    # all sleeve parameters. No sleeve edit leaks into the body's depth field.
    curves = []
    for side in (-1, 1):
        points = []
        for i in range(25):
            y = st["armpit_y"] * i / 24.0
            points.append((side * 0.5 * p["torso_width"] * _width_factor(y, p, st), y))
        curves.append([(*a, *b) for a, b in zip(points, points[1:])])
        points = []
        for i in range(25):
            x = _lerp(0.5 * p["neck_width"], 0.5 * p["torso_width"], i / 24.0)
            points.append((side * x, _shoulder(x, p, st)[0]))
        curves.append([(*a, *b) for a, b in zip(points, points[1:])])
    return curves


def _body_depth(x, y, p, seams):
    distances = [min(_segment_distance(x, y, segment) for segment in curve) for curve in seams]
    if min(distances) < 1e-12:
        return 0.0
    distance = sum(d ** -6 for d in distances) ** (-1.0 / 6.0)
    arc = min(math.pi / 2.0, math.pi * distance / p["torso_width"])
    return 0.5 * p["depth"] * _depth_factor(y, p) * math.sqrt(math.sin(arc))


def _body_depth_many(points, p, seams):
    """Same depth field as _body_depth, batched on the CPU (NumPy).

    Chunking bounds temporary storage on small machines. This is deliberately
    independent of GPU/OS and preserves the scalar implementation as an oracle.
    """
    import numpy as np

    points = np.asarray(points, dtype=np.float64).reshape(-1, 2)
    segments = np.asarray(seams, dtype=np.float64)
    starts = segments[:, :, :2]
    delta = segments[:, :, 2:] - starts
    length_sq = np.sum(delta * delta, axis=-1)
    result = np.empty(len(points), dtype=np.float64)
    for offset in range(0, len(points), 512):
        chunk = points[offset:offset + 512]
        relative = chunk[:, None, None, :] - starts[None, :, :, :]
        t = np.clip(np.sum(relative * delta, axis=-1) / length_sq, 0.0, 1.0)
        remainder = relative - t[:, :, :, None] * delta
        distances = np.hypot(remainder[:, :, :, 0], remainder[:, :, :, 1]).min(axis=2)
        interior = distances.min(axis=1) >= 1e-12
        depth = np.zeros(len(chunk), dtype=np.float64)
        if interior.any():
            distance = np.sum(distances[interior] ** -6, axis=1) ** (-1.0 / 6.0)
            arc = np.minimum(math.pi / 2.0, math.pi * distance / p["torso_width"])
            # Retain scalar height interpolation to use the exact same branch
            # and polynomial as the reference implementation.
            factors = np.asarray([_depth_factor(y, p) for _, y in chunk[interior]])
            depth[interior] = .5 * p["depth"] * factors * np.sqrt(np.sin(arc))
        result[offset:offset + len(chunk)] = depth
    return result


def _panel(p, neck_depth, resolution, depth_backend="scalar"):
    shoulder_cols, neck_cols, lower_rows, upper_rows, sleeve_cols = _RESOLUTIONS[resolution]
    width, length = p["torso_width"], p["body_length"]
    half_width, half_neck = width / 2.0, p["neck_width"] / 2.0
    st = _structure(p)
    seams_geometry = _body_seams(p, st)
    coords, triangles, triangle_regions = [], [], []
    sewn, groups = set(), defaultdict(set)

    def vertex(x, y, amplitude, center, labels, seam=False):
        index = len(coords)
        coords.append((x, y, 0.0 if seam else amplitude, center))
        for label in labels:
            groups[label].add(index)
        if seam:
            sewn.add(index)
        return index

    def quad(a, b, c, d, region, side=1):
        for triangle in ((a, b, c), (a, c, d)):
            if side == -1:
                triangle = tuple(reversed(triangle))
            pa, pb, pc = (coords[index] for index in triangle)
            area = ((pb[0] - pa[0]) * (pc[1] - pa[1])
                    - (pb[1] - pa[1]) * (pc[0] - pa[0]))
            if area <= 1e-12 * width * length:
                raise ValueError("parameters fold the projected surface; reduce cuff tilt/drop or increase sleeve length")
            triangles.append(triangle)
            triangle_regions.append(region)

    columns = []
    for a, b, count in ((-half_width, -half_neck, shoulder_cols),
                        (-half_neck, half_neck, neck_cols),
                        (half_neck, half_width, shoulder_cols)):
        columns.extend(_lerp(a, b, i / count) for i in range(count))
    columns.append(half_width)
    ncols, nrows = len(columns), lower_rows + upper_rows + 1
    neck_start, neck_end = shoulder_cols, shoulder_cols + neck_cols
    body = []
    for row in range(nrows):
        ids = []
        for col, x_top in enumerate(columns):
            if neck_start <= col <= neck_end:
                u = max(-1.0, min(1.0, x_top / half_neck))
                top_y = length - neck_depth * math.sqrt(max(0.0, 1.0 - u * u))
            else:
                top_y = _shoulder(x_top, p, st)[0]
            if row <= lower_rows:
                y = st["armpit_y"] * row / lower_rows
                x = x_top * _width_factor(y, p, st)
            else:
                y = _lerp(st["armpit_y"], top_y, (row - lower_rows) / upper_rows)
                x = x_top
            seam = ((col in (0, ncols - 1) and row <= lower_rows)
                    or (row == nrows - 1 and (col <= neck_start or col >= neck_end)))
            labels = ["torso"]
            if row == 0:
                labels.append("hem")
            if row == nrows - 1 and neck_start <= col <= neck_end:
                labels.append("neck")
            if 0.30 * length <= y <= 0.72 * length and abs(x) <= 0.32 * width:
                labels.append("chest")
            amplitude = (0.0 if seam or depth_backend == "numpy"
                         else _body_depth(x, y, p, seams_geometry))
            ids.append(vertex(x, y, amplitude, _center_z(y, p), labels, seam))
        body.append(ids)
    if depth_backend == "numpy":
        pending = [index for row in body for index in row if index not in sewn]
        depths = _body_depth_many([coords[index][:2] for index in pending], p, seams_geometry)
        for index, amplitude in zip(pending, depths):
            x, y, _, center = coords[index]
            coords[index] = (x, y, float(amplitude), center)
    for row in range(nrows - 1):
        for col in range(ncols - 1):
            quad(body[row][col], body[row][col + 1], body[row + 1][col + 1], body[row + 1][col], "torso")

    shoulder_slope = _shoulder(half_width, p, st)[1]
    axis_slope = -p["sleeve_drop"] / p["sleeve_length"]
    top_dx, bottom_dx = st["cuff_top"][0] - half_width, st["cuff_bottom"][0] - half_width
    # Underarm is allowed a seam corner. The upper shoulder has an exact common
    # dy/dx tangent, including when the sleeve axis drops steeply.
    top_start_dy = shoulder_slope * top_dx
    bottom_start_dy = min(-0.10 * width, st["cuff_bottom"][1] - st["armpit_y"])
    cuff_amplitude = 0.5 * p["depth"] * 0.82 * (p["cuff_width"] / (0.4 * width)) ** 0.35

    def sleeve_xy(u, v):
        xb, xt = half_width + bottom_dx * u, half_width + top_dx * u
        yb = _hermite(st["armpit_y"], st["cuff_bottom"][1], bottom_start_dy, axis_slope * bottom_dx, u)
        yt = _hermite(st["shoulder_y"], st["cuff_top"][1], top_start_dy, axis_slope * top_dx, u)
        return _lerp(xb, xt, v), _lerp(yb, yt, v)

    for side, name in ((1, "left"), (-1, "right")):
        body_col = ncols - 1 if side == 1 else 0
        root = [body[lower_rows + row][body_col] for row in range(upper_rows + 1)]
        groups[name + "_sleeve_root"].update(root)
        groups[name + "_sleeve"].update(root)
        root_data = []
        for row in range(upper_rows + 1):
            v = row / upper_rows
            root_y = _lerp(st["armpit_y"], st["shoulder_y"], v)
            cross = math.sqrt(max(0.0, math.sin(math.pi * v)))
            root_z = coords[root[row]][2]
            if row in (0, upper_rows):
                root_data.append((root_y, 0.0, 0.0, 0.0))
                continue
            root_amplitude = root_z / cross
            x_eps, y_eps = sleeve_xy(1e-4, v)
            derivative = (_body_depth(x_eps, y_eps, p, seams_geometry) - root_z) / (1e-4 * cross)
            # Bounded log-Hermite amplitude is positive even for very short,
            # wide sleeves. The tangent limiter is a geometric prior, not cloth.
            log_tangent = max(-1.5, min(1.5, derivative / root_amplitude))
            root_data.append((root_y, cross, root_amplitude, log_tangent))
        sleeve = [root]
        for col in range(1, sleeve_cols + 1):
            u = col / sleeve_cols
            ids = []
            for row in range(upper_rows + 1):
                v = row / upper_rows
                x, y = sleeve_xy(u, v)
                root_y, cross, root_amplitude, log_tangent = root_data[row]
                seam = row in (0, upper_rows)
                amplitude = 0.0 if seam else cross * math.exp(_hermite(
                    math.log(root_amplitude), math.log(cuff_amplitude), log_tangent, 0.0, u))
                center = _lerp(_center_z(root_y, p), _center_z(st["root_center"], p), _smooth(u))
                labels = [name + "_sleeve"]
                if col == sleeve_cols:
                    labels.append(name + "_cuff")
                ids.append(vertex(side * x, y, amplitude, center, labels, seam))
            sleeve.append(ids)
        for col in range(sleeve_cols):
            for row in range(upper_rows):
                quad(sleeve[col][row], sleeve[col + 1][row], sleeve[col + 1][row + 1],
                     sleeve[col][row + 1], name + "_sleeve", side)
    return {"vertices": coords, "faces": triangles, "face_regions": triangle_regions,
            "seams": sewn, "groups": groups}


def _openings(faces, groups):
    edges = defaultdict(list)
    for a, b, c in faces:
        for u, v in ((a, b), (b, c), (c, a)):
            edges[tuple(sorted((u, v)))].append((u, v))
    next_vertex = {}
    for uses in edges.values():
        if len(uses) == 1:
            u, v = uses[0]
            if u in next_vertex:
                raise RuntimeError("branched boundary")
            next_vertex[u] = v
    unseen, loops = set(next_vertex), []
    while unseen:
        start = current = min(unseen)
        loop = []
        while current in unseen:
            unseen.remove(current)
            loop.append(current)
            current = next_vertex[current]
        if current != start:
            raise RuntimeError("non-cyclic boundary")
        loops.append(loop)
    result = {}
    for name in ("neck", "hem", "left_cuff", "right_cuff"):
        matches = [loop for loop in loops if set(loop) == groups[name]]
        if len(matches) != 1:
            raise RuntimeError("opening mismatch: " + name)
        result[name] = matches[0]
    if len(loops) != 4:
        raise RuntimeError("unexpected openings")
    return result


def build_mesh(params=None, resolution="preview", depth_backend="scalar"):
    """Return JSON lists. Each resolution has stable faces/opening indices.

    ``resolution='fit'`` uses fewer samples of the same continuous geometry.
    ``preview`` supplies a denser final surface. Compare geometry at matching
    sampling stations; coarse and dense rasterizations are not pixel-identical.
    ``depth_backend='numpy'`` batches the same depth field without changing the
    topology; scalar remains the dependency-free reference/default.
    """
    if resolution not in _RESOLUTIONS:
        raise ValueError("resolution must be 'fit' or 'preview'")
    if depth_backend not in ("scalar", "numpy"):
        raise ValueError("depth_backend must be 'scalar' or 'numpy'")
    p = validate_params(params)
    panels = [_panel(p, p["neck_depth"], resolution, depth_backend),
              _panel(p, p["back_neck_depth"], resolution, depth_backend)]
    vertices, faces = [], []
    regions, face_regions = defaultdict(set), defaultdict(list)
    front_ids = {}
    for panel_index, panel in enumerate(panels):
        ids = {}
        for index, (x, y, amplitude, center) in enumerate(panel["vertices"]):
            if panel_index == 1 and index in panel["seams"]:
                global_id = front_ids[index]
            else:
                global_id = len(vertices)
                vertices.append([float(x), float(y), float(center + (amplitude if panel_index == 0 else -amplitude))])
            ids[index] = global_id
        if panel_index == 0:
            front_ids = ids
        panel_name = "front" if panel_index == 0 else "back"
        regions[panel_name].update(ids.values())
        regions["seams"].update(ids[index] for index in panel["seams"])
        for name, members in panel["groups"].items():
            regions[name].update(ids[index] for index in members)
        for local_index, face in enumerate(panel["faces"]):
            face_index = len(faces)
            mapped = [ids[index] for index in face]
            faces.append(mapped if panel_index == 0 else list(reversed(mapped)))
            face_regions[panel_name].append(face_index)
            face_regions[panel["face_regions"][local_index]].append(face_index)
    # A local change to the observed neck position, independent of camera and
    # opening width/depth. Apply once to logical vertices after seam welding.
    # The same continuous field is used at both mesh resolutions. All sleeve
    # vertices (including shared roots) are explicitly outside its support.
    sleeve_vertices = regions["left_sleeve"] | regions["right_sleeve"]
    half_neck, half_body = 0.5 * p["neck_width"], 0.5 * p["torso_width"]
    shoulder_support = 0.85 * (half_body - half_neck)
    for index, point in enumerate(vertices):
        if index in sleeve_vertices:
            continue
        x, y, _ = point
        horizontal = 1.0 - _smooth((abs(x) - half_neck) / shoulder_support)
        vertical = _smooth((y - (p["body_length"] - 0.18)) / 0.18)
        point[2] += p["neck_setback"] * horizontal * vertical
    return {
        "schemaVersion": SCHEMA_VERSION, "geometryVersion": GEOMETRY_VERSION,
        "units": "relative; no physical scale inferred", "resolution": resolution,
        "coordinateSystem": {"up": "+Y", "front": "+Z", "wearerLeft": "+X"},
        "representation": "single-layer-thin-shell", "vertices": vertices, "faces": faces,
        "openings": _openings(faces, regions),
        "semantic_regions": {name: sorted(ids) for name, ids in sorted(regions.items())},
        "face_regions": dict(sorted(face_regions.items())), "params": p,
        "parameterBounds": {name: list(bounds) for name, bounds in PARAM_BOUNDS.items()},
        "parameterUnits": dict(PARAM_UNITS),
        "parameterRegions": {name: (["torso", "neck"] if name == "neck_setback"
                                    else ["left_sleeve", "right_sleeve"] if name in _SLEEVE_PARAMS
                                    else ["torso", "left_sleeve", "right_sleeve"]) for name in p},
        "limitations": [
            "A bounded shape family; reference match and independent acceptance are separate.",
            "Zero material thickness; no collar ribbing, inner walls, UVs or cloth simulation.",
            "Sleeve amplitude uses a bounded tangent prior; no calibrated cloth mechanics.",
            "Body depth profiles and lean are actual geometry but remain assumptions without measurements.",
        ],
    }
