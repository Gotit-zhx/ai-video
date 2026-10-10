"""Optional local front-chest relaxation over the unchanged v2 topology.

The finite side-seam distance field has an endpoint at the armpit. Combined
with a thinner chest height profile, it can create an unsupported horizontal
trough in the front flanks. This module detects that trough, then smoothly
shares relaxation across the flanks within their upper/lower depth envelope.
It adds no anatomy-shaped central bulge and preserves every opening and sleeve.
"""
from __future__ import annotations

from copy import deepcopy
from numbers import Real
import math

import numpy as np

from geometry_v2 import (
    DEFAULT_PARAMS as V2_DEFAULT_PARAMS, PARAM_BOUNDS as V2_PARAM_BOUNDS,
    _body_depth_many, _body_seams, _center_z, _structure, _width_factor,
    build_mesh as build_v2,
)

GEOMETRY_VERSION = "structured-panels/chest-support-0.4.0"
DEFAULT_PARAMS = {**V2_DEFAULT_PARAMS, "chest_relaxation": 0.0, "chest_support": 0.0, "back_drape": 0.0}
PARAM_BOUNDS = {**V2_PARAM_BOUNDS, "chest_relaxation": (0.0, 1.0), "chest_support": (0.0, 1.0), "back_drape": (0.0, 1.0)}


def apply_back_drape(mesh, strength=1.0):
    """Remove the inflated waist/back by an inward elliptical drape envelope.

    Back hem vertices may move in Z: freezing the inflated hem would force the
    lower back to balloon again. Neck and sleeves remain untouched. The same
    four indexed openings are retained, including the reshaped open hem.
    """
    if isinstance(strength, bool) or not isinstance(strength, Real) or not math.isfinite(float(strength)) or not 0 <= strength <= 1:
        raise ValueError("back_drape must be finite and in [0, 1]")
    out = deepcopy(mesh)
    p, groups = mesh['params'], mesh['semantic_regions']
    st, width = _structure(p), p['torso_width']
    protected = set(groups['front']) | set(groups['left_sleeve']) | set(groups['right_sleeve'])
    # Only the lower opening is intentionally reshaped. Identify it by height,
    # rather than relying on a particular naming convention for the loop.
    for loop in mesh['openings'].values():
        if max(mesh['vertices'][i][1] for i in loop) > .05 * p['body_length']:
            protected.update(loop)
    ids = sorted((set(groups['back']) & set(groups['torso'])) - protected)
    vertices = np.asarray(mesh['vertices'], dtype=float)
    x, y = vertices[ids, 0], vertices[ids, 1]
    half_width = np.array([.5 * width * (_width_factor(yy, p, st) if yy <= st['armpit_y'] else 1) for yy in y])
    ellipse = np.sqrt(np.maximum(0, 1 - (x / half_width) ** 2))
    center = np.array([_center_z(yy, p) for yy in y])
    target = center - .5 * p['depth'] * .94 * ellipse
    gap = np.maximum(target - vertices[ids, 2], 0)
    top = 1 - smooth((y - st['armpit_y']) / (p['body_length'] - p['back_neck_depth'] - st['armpit_y']))
    edge = 1 - smooth((np.abs(x) / half_width - .85) / .15)
    delta = gap * gap / (gap + .001 * width) * top * edge * strength
    for i, dz in zip(ids, delta):
        out['vertices'][i][2] += float(dz)
    report = {'strength': float(strength), 'affectedVertexCount': int(np.count_nonzero(delta > 1e-12)),
              'maximumInwardDisplacement': float(delta.max()), 'direction': 'inward only on back',
              'hemReshaped': True, 'physicalSimulation': False, 'referenceAccuracyClaim': False}
    out['backDrape'] = report
    return out, report


def apply_chest_support(mesh, strength=1.0):
    """Drape the front over a broad elliptical torso support (a design prior).

    Unlike trough relaxation this deliberately moves the central chest and
    changes side silhouettes. No physical body dimensions are claimed. Existing
    sewing boundaries and openings remain fixed; this is not a cloth simulator.
    """
    if isinstance(strength, bool) or not isinstance(strength, Real) or not math.isfinite(float(strength)) or not 0 <= strength <= 1:
        raise ValueError("chest_support must be finite and in [0, 1]")
    out = deepcopy(mesh)
    p, groups = mesh["params"], mesh["semantic_regions"]
    st, width = _structure(p), p["torso_width"]
    protected = set(groups["left_sleeve"]) | set(groups["right_sleeve"]) | set(groups["back"])
    protected |= {i for loop in mesh["openings"].values() for i in loop}
    ids = sorted((set(groups["front"]) & set(groups["torso"])) - protected)
    vertices = np.asarray(mesh["vertices"], dtype=float)
    x, y = vertices[ids, 0], vertices[ids, 1]
    lower, upper = .27 * p["body_length"], p["body_length"] - p["neck_depth"]
    rise = smooth((y - lower) / (.22 * p["body_length"]))
    fall = 1 - smooth((y - (st["armpit_y"] + .08 * width)) /
                       (upper - st["armpit_y"] - .08 * width))
    half_width = np.array([.5 * width * (_width_factor(yy, p, st) if yy <= st["armpit_y"] else 1) for yy in y])
    lateral = np.sqrt(np.maximum(0, 1 - (x / half_width) ** 2))
    edge_fade = 1 - smooth((np.abs(x) / half_width - .82) / .18)
    # One continuous rib-cage volume, not two breast-shaped local bumps.
    depth = .5 * p["depth"] * (1.38 - .16 * ((y - st["armpit_y"]) / (.42 * p["body_length"])) ** 2)
    target = np.array([_center_z(yy, p) for yy in y]) + depth * lateral
    gap = np.maximum(target - vertices[ids, 2], 0)
    correction = gap * gap / (gap + .001 * width) * rise * fall * edge_fade * strength
    for i, dz in zip(ids, correction):
        out["vertices"][i][2] += float(dz)
    report = {"strength": float(strength), "affectedVertexCount": int(np.count_nonzero(correction > 1e-12)),
              "maximumDisplacement": float(correction.max()), "centralChestCanMove": True,
              "support": "broad elliptical rib cage with smooth waist and collar transitions",
              "physicalSimulation": False, "referenceAccuracyClaim": False}
    out["chestSupport"] = report
    return out, report


def smooth(x):
    x = np.clip(x, 0, 1)
    return x * x * (3 - 2 * x)


def chest_field(params, x, y):
    """Return bounded positive correction and measurable geometric evidence.

    Uses the unchanged v2 analytical field as donor data. Zero correction when
    the front-flank neighborhood lacks a meaningful depression.
    No renderer, camera, image, hidden dimensions, or material settings enter it.
    """
    p, st = params, _structure(params)
    width = p["torso_width"]
    y0 = st["armpit_y"] - .22 * width
    y1 = min(st["armpit_y"] + .27 * width,
             p["body_length"] - p["neck_depth"] - .04 * width)
    x, y = np.asarray(x, dtype=float), np.asarray(y, dtype=float)
    if y1 <= y0 + .10 * width:
        return np.zeros_like(x), {"supported": False, "reason": "No safe band below neckline"}
    profile_y = np.linspace(y0, y1, 25)
    safe_half_width = min(.5 * width * (_width_factor(yy, p, st) if yy <= st["armpit_y"] else 1)
                          for yy in profile_y)
    # Cache the repeated structured-grid column positions before batch depth.
    unique_x, inverse = np.unique(x, return_inverse=True)
    coordinates = np.stack(np.meshgrid(unique_x, profile_y, indexing="ij"), axis=-1).reshape(-1, 2)
    depths = _body_depth_many(coordinates, p, _body_seams(p, st)).reshape(len(unique_x), len(profile_y))
    depths += np.array([_center_z(yy, p) for yy in profile_y])[None, :]
    # A fixed set of detector stations makes activation independent of output
    # resolution. Selecting each column separately introduced two narrow ridges;
    # share the detected correction with a smooth lateral ramp instead.
    detector_x = np.linspace(0, safe_half_width * .98, 33)
    detector_points = np.stack(np.meshgrid(detector_x, profile_y, indexing="ij"), axis=-1).reshape(-1, 2)
    detector_depth = _body_depth_many(detector_points, p, _body_seams(p, st)).reshape(len(detector_x), len(profile_y))
    detector_depth += np.array([_center_z(yy, p) for yy in profile_y])[None, :]
    trough = np.minimum(detector_depth[:, 0], detector_depth[:, -1]) - detector_depth.min(axis=1)
    activation = smooth(max(float(trough.max()), 0) / (.008 * width))
    t = np.clip((y - y0) / (y1 - y0), 0, 1)
    donor = depths[inverse, 0] * (1 - smooth(t)) + depths[inverse, -1] * smooth(t)
    current = _body_depth_many(np.stack([x, y], axis=1), p, _body_seams(p, st))
    current += np.array([_center_z(yy, p) for yy in y])
    gap = np.maximum(donor - current, 0)
    # Smooth positive part and compact C1 fade. Never exceed the donor envelope.
    positive = gap * gap / (gap + .0003 * width)
    vertical = np.sin(np.pi * t) ** 2
    lateral = 1 - smooth((np.abs(x) / safe_half_width - .78) / .20)
    # Preserve the centerline exactly. Unlike a per-column threshold, this C1
    # sharing ramp avoids lateral ridges while staying inside the donor envelope.
    sharing = smooth(np.abs(x) / safe_half_width)
    correction = positive * vertical * lateral * sharing * activation
    correction[(y <= y0) | (y >= y1)] = 0
    return correction, {
        "supported": True, "armpitY": st["armpit_y"], "bandY": [y0, y1],
        "safeHalfWidth": safe_half_width, "uniqueProfileCount": len(unique_x),
        "maximumBaselineTrough": float(trough.max()) if len(trough) else 0,
        "maximumFullStrengthCorrection": float(correction.max()) if len(correction) else 0,
        "profileSampleCount": len(profile_y),
    }


def apply_chest_relaxation(mesh, strength=1.0):
    """Copy a v2 mesh, edit only interior front-torso Z, and return report."""
    if isinstance(strength, bool) or not isinstance(strength, Real) or not math.isfinite(float(strength)) or not 0 <= strength <= 1:
        raise ValueError("chest_relaxation must be finite and in [0, 1]")
    out = deepcopy(mesh)
    p, groups = mesh["params"], mesh["semantic_regions"]
    protected = set(groups["left_sleeve"]) | set(groups["right_sleeve"]) | set(groups["back"])
    protected |= {i for loop in mesh["openings"].values() for i in loop}
    candidates = sorted((set(groups["front"]) & set(groups["torso"])) - protected)
    v = np.asarray(mesh["vertices"])
    st, width = _structure(p), p["torso_width"]
    candidates = [i for i in candidates if st["armpit_y"] - .22 * width < v[i, 1] < st["armpit_y"] + .27 * width]
    correction, diagnostic = chest_field(p, v[candidates, 0], v[candidates, 1])
    moved = []
    for i, dz in zip(candidates, correction * float(strength)):
        if dz > 1e-14:
            out["vertices"][i][2] += float(dz)
            moved.append(i)
    return out, {**diagnostic, "strength": float(strength), "affectedVertexIds": moved,
                 "affectedVertexCount": len(moved),
                 "maximumDisplacement": float(correction.max() * strength) if len(correction) else 0,
                 "direction": "+Z only; existing front surface moves outward inside its local donor envelope",
                 "protectedVertexCount": len(protected), "baseTopologyUnchanged": True,
                 "centralBulgeAdded": False,
                 "referenceStatus": "Reference images support a continuously draped front; this is a geometric regularity prior, not a calibrated depth reconstruction"}


def sample_front_depth(mesh, points):
    """Barycentrically sample Nx2 XY points on actual front torso triangles."""
    face_ids = sorted(set(mesh["face_regions"]["front"]) & set(mesh["face_regions"]["torso"]))
    triangles = np.asarray(mesh["vertices"])[np.asarray(mesh["faces"])[face_ids]]
    a, b, c = triangles[:, 0], triangles[:, 1], triangles[:, 2]
    den = (b[:, 1] - c[:, 1]) * (a[:, 0] - c[:, 0]) + (c[:, 0] - b[:, 0]) * (a[:, 1] - c[:, 1])
    values = []
    for x, y in points:
        w0 = ((b[:, 1] - c[:, 1]) * (x - c[:, 0]) + (c[:, 0] - b[:, 0]) * (y - c[:, 1])) / den
        w1 = ((c[:, 1] - a[:, 1]) * (x - c[:, 0]) + (a[:, 0] - c[:, 0]) * (y - c[:, 1])) / den
        w2 = 1 - w0 - w1
        candidates = np.flatnonzero((w0 >= -1e-8) & (w1 >= -1e-8) & (w2 >= -1e-8))
        if not len(candidates):
            raise ValueError("Chest measurement station falls outside front torso")
        i = candidates[0]
        values.append(float(w0[i] * a[i, 2] + w1[i] * b[i, 2] + w2[i] * c[i, 2]))
    return np.asarray(values)


def measure_chest_trough(mesh, samples=65):
    """Measure depth below both local endpoints on actual front triangles.

    This measures the acknowledged seam-field artifact, not reference accuracy.
    Same parameter-relative band/stations must be used before and after.
    """
    p, st = mesh["params"], _structure(mesh["params"])
    width = p["torso_width"]
    y0 = st["armpit_y"] - .22 * width
    y1 = min(st["armpit_y"] + .27 * width, p["body_length"] - p["neck_depth"] - .04 * width)
    records = []
    ys = np.linspace(y0, y1, samples)
    for fraction in (0, .18, .30, .35, .41):
        x = fraction * width
        zs = sample_front_depth(mesh, np.stack([np.full_like(ys, x), ys], axis=1)).tolist()
        trough = max(0.0, min(zs[0], zs[-1]) - min(zs))
        records.append({"xFractionOfTorsoWidth": fraction, "x": x,
                        "lowerEndpointZ": zs[0], "upperEndpointZ": zs[-1],
                        "minimumZ": min(zs), "minimumAtY": float(ys[int(np.argmin(zs))]),
                        "troughDepth": trough, "profileZ": zs})
    return {"metric": "maximum sampled depth below both local longitudinal endpoints",
            "source": "barycentric sampling of actual front torso triangles",
            "bandY": [y0, y1], "sampleY": ys.tolist(), "sections": records,
            "maximumTroughDepth": max(r["troughDepth"] for r in records),
            "meanTroughDepth": float(np.mean([r["troughDepth"] for r in records])),
            "referenceAccuracyClaim": False}


def build_mesh(params=None, resolution="preview", depth_backend="numpy"):
    params = dict(params or {})
    strength = params.pop("chest_relaxation", 0.0)
    support = params.pop("chest_support", 0.0)
    back_drape = params.pop("back_drape", 0.0)
    base = build_v2(params, resolution, depth_backend)
    mesh, report = apply_chest_relaxation(base, strength)
    if support != 0 or isinstance(support, bool):
        mesh, _ = apply_chest_support(mesh, support)
    if back_drape != 0 or isinstance(back_drape, bool):
        mesh, _ = apply_back_drape(mesh, back_drape)
    mesh["params"]["back_drape"] = float(back_drape)
    mesh["parameterBounds"]["back_drape"] = [0, 1]
    mesh["parameterUnits"]["back_drape"] = "dimensionless_drape_strength"
    mesh["parameterRegions"]["back_drape"] = ["back", "torso"]
    mesh["params"]["chest_support"] = float(support)
    mesh["parameterBounds"]["chest_support"] = [0, 1]
    mesh["parameterUnits"]["chest_support"] = "dimensionless_support_strength"
    mesh["parameterRegions"]["chest_support"] = ["front", "torso"]
    mesh["geometryVersion"] = GEOMETRY_VERSION
    mesh["params"]["chest_relaxation"] = float(strength)
    mesh["parameterBounds"]["chest_relaxation"] = [0, 1]
    mesh["parameterUnits"]["chest_relaxation"] = "dimensionless_relaxation_strength"
    mesh["parameterRegions"]["chest_relaxation"] = ["front", "torso"]
    mesh["chestRelaxation"] = report
    mesh["limitations"].append("Front-chest relaxation removes a seam-field trough using a bounded geometric prior; silhouette agreement cannot certify this interior surface.")
    return mesh
