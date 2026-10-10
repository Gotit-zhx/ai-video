"""Deterministic, relative-scale short-sleeve thin-shell geometry prototype.

This is a bounded garment shape family, not recovered measurements or a cloth
simulation.  A front and a back structured panel each include the torso and both
sleeves. Sleeve roots reuse torso vertices; the two panels reuse seam vertices.
The neck, hem and two cuffs remain actual mesh boundaries. No intersecting tube
assembly, floating collar or caps are used. Only the Python standard library is
required. Coordinates: +Y up, +Z front, +X the wearer's left.
"""

from __future__ import annotations

from collections import defaultdict
import math
from numbers import Real


SCHEMA_VERSION = "garment-mesh/0.1.0"
GEOMETRY_VERSION = "structured-panels/0.1.0"

# All lengths use the same arbitrary relative unit. They are not metres.
DEFAULT_PARAMS = {
    "torso_width": 1.0,
    "body_length": 1.25,
    "depth": 0.28,
    "sleeve_length": 0.42,
    "sleeve_drop": 0.18,
    "neck_width": 0.34,
    "neck_depth": 0.18,
    "back_neck_depth": 0.07,
    "cuff_width": 0.34,
}
PARAM_BOUNDS = {
    "torso_width": (0.65, 1.5),
    "body_length": (0.8, 1.8),
    "depth": (0.14, 0.55),
    "sleeve_length": (0.15, 0.7),
    "sleeve_drop": (0.02, 0.4),
    "neck_width": (0.2, 0.55),
    "neck_depth": (0.08, 0.35),
    "back_neck_depth": (0.02, 0.16),
    "cuff_width": (0.22, 0.52),
}
PARAM_REGIONS = {
    "torso_width": ["torso", "left_sleeve", "right_sleeve"],
    "body_length": ["torso", "left_sleeve", "right_sleeve"],
    "depth": ["torso", "left_sleeve", "right_sleeve"],
    "sleeve_length": ["torso", "left_sleeve", "right_sleeve"],
    "sleeve_drop": ["torso", "left_sleeve", "right_sleeve"],
    "neck_width": ["torso", "neck"],
    "neck_depth": ["front", "neck"],
    "back_neck_depth": ["back", "neck"],
    "cuff_width": ["torso", "left_sleeve", "right_sleeve"],
}

# Fixed topology for every valid parameter set. Rows above the armpit are also
# the sampling stations along the shared sleeve roots.
_SHOULDER_COLS = 6
_NECK_COLS = 16
_LOWER_ROWS = 12
_UPPER_ROWS = 8
_SLEEVE_COLS = 8


def validate_params(params: dict | None = None) -> dict:
    """Return complete finite parameters, rejecting unknown/out-of-range input.

    Coupled constraints keep necks above the armpit and cuffs above the hem.
    Values are rejected, never silently clipped, so fit histories remain honest.
    """
    if params is None:
        params = {}
    if not isinstance(params, dict):
        raise ValueError("params must be a dictionary")
    unknown = set(params) - set(DEFAULT_PARAMS)
    if unknown:
        raise ValueError("unknown parameters: " + ", ".join(sorted(map(str, unknown))))
    result = DEFAULT_PARAMS.copy()
    for key, value in params.items():
        if isinstance(value, bool) or not isinstance(value, Real):
            raise ValueError(f"{key} must be a finite number")
        try:
            value = float(value)
        except (OverflowError, ValueError) as error:
            raise ValueError(f"{key} must be a finite number") from error
        lower, upper = PARAM_BOUNDS[key]
        if not math.isfinite(value) or not lower <= value <= upper:
            raise ValueError(f"{key} must be in [{lower}, {upper}]")
        result[key] = value
    width, length = result["torso_width"], result["body_length"]
    if result["neck_width"] > 0.62 * width:
        raise ValueError("neck_width must be <= 0.62 * torso_width")
    if max(result["neck_depth"], result["back_neck_depth"]) > 0.40 * width:
        raise ValueError("neck depths must be <= 0.40 * torso_width")
    if result["cuff_width"] > 0.58 * width:
        raise ValueError("cuff_width must be <= 0.58 * torso_width")
    if result["depth"] > 0.55 * width:
        raise ValueError("depth must be <= 0.55 * torso_width")
    armpit_y = length - 0.48 * width
    if armpit_y < 0.20 * length:
        raise ValueError("body_length must leave at least 20% below the armpit")
    cuff_bottom = (length - 0.27 * width - result["sleeve_drop"]
                   - 0.5 * result["cuff_width"])
    if cuff_bottom < 0.10 * length:
        raise ValueError("sleeve_drop/cuff_width must keep cuff above 10% body_length")
    return result


def _lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def _distance_to_segment(x: float, y: float, segment: tuple) -> float:
    ax, ay, bx, by = segment
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(x - ax - t * dx, y - ay - t * dy)


def _panel_depth(x: float, y: float, p: dict, segments: list) -> float:
    # Smooth inverse-power distance blend: zero precisely at sewing edges and
    # positive at sleeve roots, neck centre, hem centre and cuff centres.
    distances = [_distance_to_segment(x, y, segment) for segment in segments]
    if min(distances) < 1e-12:
        return 0.0
    distance = sum(d ** -4 for d in distances) ** -0.25
    width = p["torso_width"]
    arc = min(math.pi / 2.0, math.pi * distance / width)
    return 0.5 * p["depth"] * math.sqrt(math.sin(arc))


def _panel(p: dict, neck_depth: float) -> dict:
    width, length = p["torso_width"], p["body_length"]
    half_width, half_neck = width / 2.0, p["neck_width"] / 2.0
    shoulder_y, armpit_y = length - 0.06 * width, length - 0.48 * width
    root_center_y = (shoulder_y + armpit_y) / 2.0
    cuff_top = root_center_y - p["sleeve_drop"] + p["cuff_width"] / 2.0
    cuff_bottom = root_center_y - p["sleeve_drop"] - p["cuff_width"] / 2.0
    cuff_x = half_width + p["sleeve_length"]
    segments = []
    for side in (-1, 1):
        segments.extend([
            (side * 0.49 * width, 0.0, side * half_width, armpit_y),
            (side * half_width, armpit_y, side * cuff_x, cuff_bottom),
            (side * cuff_x, cuff_top, side * half_width, shoulder_y),
            (side * half_width, shoulder_y, side * half_neck, length),
        ])

    columns = []
    for a, b, count in [(-half_width, -half_neck, _SHOULDER_COLS),
                        (-half_neck, half_neck, _NECK_COLS),
                        (half_neck, half_width, _SHOULDER_COLS)]:
        columns.extend(_lerp(a, b, i / count) for i in range(count))
    columns.append(half_width)
    ncols, nrows = len(columns), _LOWER_ROWS + _UPPER_ROWS + 1
    coords, triangles = [], []
    seams, groups = set(), defaultdict(set)

    def add_vertex(x: float, y: float, labels: list[str], seam=False) -> int:
        index = len(coords)
        coords.append((x, y, 0.0 if seam else _panel_depth(x, y, p, segments)))
        for label in labels:
            groups[label].add(index)
        if seam:
            seams.add(index)
        return index

    def add_quad(a: int, b: int, c: int, d: int) -> None:
        # Input traverses the quad perimeter. Ensure positive projected area for
        # both the wearer-left and wearer-right sleeves.
        for triangle in [(a, b, c), (a, c, d)]:
            pa, pb, pc = (coords[index] for index in triangle)
            signed = ((pb[0] - pa[0]) * (pc[1] - pa[1])
                      - (pb[1] - pa[1]) * (pc[0] - pa[0]))
            triangles.append(triangle if signed > 0 else tuple(reversed(triangle)))

    body = []
    neck_start, neck_end = _SHOULDER_COLS, _SHOULDER_COLS + _NECK_COLS
    for row in range(nrows):
        row_ids = []
        for col, x_top in enumerate(columns):
            if abs(x_top) <= half_neck + 1e-12:
                u = max(-1.0, min(1.0, x_top / half_neck))
                top_y = length - neck_depth * math.sqrt(max(0.0, 1.0 - u * u))
            else:
                top_y = _lerp(length, shoulder_y, (abs(x_top) - half_neck) / (half_width - half_neck))
            if row <= _LOWER_ROWS:
                t = row / _LOWER_ROWS
                x, y = x_top * _lerp(0.98, 1.0, t), armpit_y * t
            else:
                t = (row - _LOWER_ROWS) / _UPPER_ROWS
                x, y = x_top, _lerp(armpit_y, top_y, t)
            is_side_seam = col in (0, ncols - 1) and row <= _LOWER_ROWS
            is_shoulder_seam = row == nrows - 1 and (col <= neck_start or col >= neck_end)
            labels = ["torso"]
            if row == 0:
                labels.append("hem")
            if row == nrows - 1 and neck_start <= col <= neck_end:
                labels.append("neck")
            if 0.3 * length <= y <= 0.72 * length and abs(x) <= 0.32 * width:
                labels.append("chest")
            row_ids.append(add_vertex(x, y, labels, is_side_seam or is_shoulder_seam))
        body.append(row_ids)
    for row in range(nrows - 1):
        for col in range(ncols - 1):
            add_quad(body[row][col], body[row][col + 1], body[row + 1][col + 1], body[row + 1][col])

    for side, name in [(1, "left"), (-1, "right")]:
        body_col = ncols - 1 if side == 1 else 0
        root = [body[_LOWER_ROWS + j][body_col] for j in range(_UPPER_ROWS + 1)]
        groups[f"{name}_sleeve_root"].update(root)
        groups[f"{name}_sleeve"].update(root)
        sleeve = [root]
        for col in range(1, _SLEEVE_COLS + 1):
            u = col / _SLEEVE_COLS
            ids = []
            for row in range(_UPPER_ROWS + 1):
                v = row / _UPPER_ROWS
                x = side * _lerp(half_width, cuff_x, u)
                y = _lerp(_lerp(armpit_y, shoulder_y, v), _lerp(cuff_bottom, cuff_top, v), u)
                labels = [f"{name}_sleeve"]
                if col == _SLEEVE_COLS:
                    labels.append(f"{name}_cuff")
                ids.append(add_vertex(x, y, labels, row in (0, _UPPER_ROWS)))
            sleeve.append(ids)
        for col in range(_SLEEVE_COLS):
            for row in range(_UPPER_ROWS):
                add_quad(sleeve[col][row], sleeve[col + 1][row], sleeve[col + 1][row + 1], sleeve[col][row + 1])
    return {"vertices": coords, "faces": triangles, "seams": seams, "groups": groups}


def _ordered_openings(faces: list, groups: dict) -> dict:
    edge_faces = defaultdict(list)
    for a, b, c in faces:
        for u, v in ((a, b), (b, c), (c, a)):
            edge_faces[tuple(sorted((u, v)))].append((u, v))
    next_vertex = {}
    for incident in edge_faces.values():
        if len(incident) == 1:
            u, v = incident[0]
            if u in next_vertex:
                raise RuntimeError("generator produced a branched boundary")
            next_vertex[u] = v
    loops, unseen = [], set(next_vertex)
    while unseen:
        start = min(unseen)
        loop, current = [], start
        while current in unseen:
            unseen.remove(current)
            loop.append(current)
            current = next_vertex[current]
        if current != start:
            raise RuntimeError("generator produced an open boundary chain")
        loops.append(loop)
    result = {}
    for name in ("neck", "hem", "left_cuff", "right_cuff"):
        expected = groups[name]
        matches = [loop for loop in loops if set(loop) == expected]
        if len(matches) != 1:
            raise RuntimeError(f"generator boundary mismatch for {name}")
        result[name] = matches[0]
    if len(loops) != 4:
        raise RuntimeError("generator produced an unexpected opening")
    return result


def build_mesh(params: dict | None = None) -> dict:
    """Build a JSON-serializable thin shell with four ordered opening loops.

    Faces point outwards. Opening loops follow their incident outward face edge
    direction; indices do not repeat the first vertex at the end. Semantic groups
    overlap intentionally: sleeve-root vertices belong to torso and sleeve.
    The result makes no reference-match or independent-acceptance claim.
    """
    p = validate_params(params)
    panels = [_panel(p, p["neck_depth"]), _panel(p, p["back_neck_depth"])]
    vertices, faces, groups = [], [], defaultdict(set)
    front_ids = {}
    for panel_index, panel in enumerate(panels):
        ids = {}
        for index, (x, y, z) in enumerate(panel["vertices"]):
            if panel_index == 1 and index in panel["seams"]:
                global_id = front_ids[index]
            else:
                global_id = len(vertices)
                vertices.append([float(x), float(y), float(z if panel_index == 0 else -z)])
            ids[index] = global_id
        if panel_index == 0:
            front_ids = ids
        groups["front" if panel_index == 0 else "back"].update(ids.values())
        groups["seams"].update(ids[index] for index in panel["seams"])
        for name, members in panel["groups"].items():
            groups[name].update(ids[index] for index in members)
        for face in panel["faces"]:
            mapped = [ids[index] for index in face]
            faces.append(mapped if panel_index == 0 else list(reversed(mapped)))
    openings = _ordered_openings(faces, groups)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "geometryVersion": GEOMETRY_VERSION,
        "units": "relative; no physical scale inferred",
        "coordinateSystem": {"up": "+Y", "front": "+Z", "wearerLeft": "+X"},
        "representation": "single-layer-thin-shell",
        "vertices": vertices,
        "faces": faces,
        "openings": openings,
        "semantic_regions": {name: sorted(members) for name, members in sorted(groups.items())},
        "params": p,
        "parameterBounds": {name: list(bounds) for name, bounds in PARAM_BOUNDS.items()},
        "parameterRegions": {name: list(regions) for name, regions in PARAM_REGIONS.items()},
        "parameterInfluence": "Conservative affected regions. Sleeve edits also affect torso depth through the seam distance blend.",
        "limitations": [
            "A structured short-sleeve shape prior; not a reconstruction quality verdict.",
            "Zero material thickness; no collar ribbing, hems, inner walls, UVs or cloth simulation.",
            "Unobserved depth and back shape remain assumptions unless independently measured.",
        ],
    }
