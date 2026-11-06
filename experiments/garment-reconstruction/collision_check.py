#!/usr/bin/env python3
"""Finite, scoped sleeve-versus-torso surface collision experiment.

Broad phase uses AABBs; rejection requires an actual triangle intersection.
Shared-index vertices are the only adjacency exemption. Coplanar positive-area
overlap is checked in 2D; point/edge contact remains explicitly unevaluated.
This is not a complete cloth collision or containment solver.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np


VERSION = "sleeve-torso-triangle-check/0.1.0"
RELATIVE_EPSILON = 1e-8
ANGULAR_EPSILON = 1e-10


def _cross2(a, b):
    return float(a[0] * b[1] - a[1] * b[0])


def _barycentric(point, triangle):
    a, b, c = triangle
    u, v, w = b-a, c-a, point-a
    uu, uv, vv, wu, wv = np.dot(u, u), np.dot(u, v), np.dot(v, v), np.dot(w, u), np.dot(w, v)
    determinant = uu*vv - uv*uv
    if determinant <= 0:
        return np.array([-math.inf, -math.inf, -math.inf])
    s, t = (vv*wu-uv*wv)/determinant, (uu*wv-uv*wu)/determinant
    return np.array([1-s-t, s, t])


def _deduplicate(points, epsilon):
    result = []
    for point in points:
        if not any(np.linalg.norm(point-old) <= epsilon for old in result):
            result.append(np.asarray(point, dtype=float))
    return result


def _coplanar_overlap(a, b, normal, epsilon):
    drop = int(np.argmax(np.abs(normal)))
    axes = [axis for axis in range(3) if axis != drop]
    pa, pb = a[:, axes], b[:, axes]
    orientation = 1.0 if _cross2(pb[1]-pb[0], pb[2]-pb[0]) >= 0 else -1.0
    polygon = [point.copy() for point in pa]
    # Clip one convex triangle by the other's three half planes.
    for index in range(3):
        start, end = pb[index], pb[(index+1) % 3]
        edge = end-start
        tolerance = epsilon * np.linalg.norm(edge)
        if not polygon:
            break
        clipped = []
        previous = polygon[-1]
        d_previous = orientation * _cross2(edge, previous-start)
        for current in polygon:
            d_current = orientation * _cross2(edge, current-start)
            inside_previous, inside_current = d_previous >= -tolerance, d_current >= -tolerance
            if inside_current != inside_previous:
                denominator = d_previous-d_current
                if denominator != 0:
                    clipped.append(previous + (d_previous/denominator)*(current-previous))
            if inside_current:
                clipped.append(current)
            previous, d_previous = current, d_current
        polygon = clipped
    if not polygon:
        return {"kind": "disjoint", "points": []}
    area = abs(sum(_cross2(polygon[i], polygon[(i+1) % len(polygon)]) for i in range(len(polygon))))/2
    edge_scale = max(np.linalg.norm(a[(i+1) % 3]-a[i]) for i in range(3))
    edge_scale = max(edge_scale, *(np.linalg.norm(b[(i+1) % 3]-b[i]) for i in range(3)))
    points = []
    for point in polygon:
        three = np.zeros(3)
        three[axes] = point
        three[drop] = a[0, drop] - np.dot(normal[axes], point-a[0, axes])/normal[drop]
        points.append(three)
    points = _deduplicate(points, epsilon)
    return {"kind": "coplanar_overlap" if area > epsilon*edge_scale else "contact",
            "points": [point.tolist() for point in points], "projectedOverlapArea": float(area),
            "projectedAxes": axes}


def triangle_intersection(triangle_a, triangle_b, epsilon=1e-8):
    """Return measured intersection points, separating crossings and contact.

    Coordinates should use a reasonable common scale. The mesh entry point
    normalizes by its bounding-box diagonal before calling this function.
    """
    a, b = np.asarray(triangle_a, dtype=float), np.asarray(triangle_b, dtype=float)
    if a.shape != (3, 3) or b.shape != (3, 3) or not np.isfinite(a).all() or not np.isfinite(b).all():
        return {"kind": "unevaluated", "reason": "invalid_triangle", "points": []}
    if not math.isfinite(epsilon) or epsilon <= 0:
        raise ValueError("epsilon must be finite and positive")
    na, nb = np.cross(a[1]-a[0], a[2]-a[0]), np.cross(b[1]-b[0], b[2]-b[0])
    la, lb = np.linalg.norm(na), np.linalg.norm(nb)
    if min(la, lb) <= epsilon*epsilon:
        return {"kind": "unevaluated", "reason": "degenerate_triangle", "points": []}
    na, nb = na/la, nb/lb
    db, da = (b-a[0]) @ na, (a-b[0]) @ nb
    if any(np.all(distances > epsilon) or np.all(distances < -epsilon) for distances in (da, db)):
        return {"kind": "disjoint", "points": []}
    cross_norm = np.linalg.norm(np.cross(na, nb))
    if cross_norm <= ANGULAR_EPSILON:
        if np.max(np.abs(db)) <= epsilon and np.max(np.abs(da)) <= epsilon:
            return _coplanar_overlap(a, b, na, epsilon)
        return {"kind": "unevaluated", "reason": "near_parallel_within_tolerance", "points": []}
    points = []
    edge_scale = max(np.linalg.norm(t[(i+1) % 3]-t[i]) for t in (a, b) for i in range(3))
    bary_tolerance = epsilon / max(edge_scale, epsilon)
    for source, destination, distances in ((a, b, da), (b, a, db)):
        for index in range(3):
            p, q = source[index], source[(index+1) % 3]
            dp, dq = distances[index], distances[(index+1) % 3]
            candidates = []
            if abs(dp) <= epsilon:
                candidates.append(p)
            if dp*dq < 0:
                candidates.append(p+(dp/(dp-dq))*(q-p))
            for point in candidates:
                if np.min(_barycentric(point, destination)) >= -bary_tolerance:
                    points.append(point)
    points = _deduplicate(points, epsilon)
    if not points:
        return {"kind": "disjoint", "points": []}
    if len(points) == 1:
        return {"kind": "contact", "points": [points[0].tolist()]}
    direction = np.cross(na, nb)
    direction /= np.linalg.norm(direction)
    points.sort(key=lambda point: float(np.dot(point, direction)))
    start, end = points[0], points[-1]
    midpoint = (start+end)/2
    length = float(np.linalg.norm(end-start))
    interior_a, interior_b = _barycentric(midpoint, a), _barycentric(midpoint, b)
    proper = length > epsilon and np.min(interior_a) > bary_tolerance and np.min(interior_b) > bary_tolerance
    return {"kind": "proper_crossing" if proper else "contact",
            "points": [start.tolist(), end.tolist()], "segmentLength": length,
            "midpointBarycentricA": interior_a.tolist(), "midpointBarycentricB": interior_b.tolist()}


def check_mesh(mesh, max_pairs=250000, max_examples=100):
    if not isinstance(max_pairs, int) or isinstance(max_pairs, bool) or max_pairs <= 0:
        raise ValueError("max_pairs must be a positive integer")
    if not isinstance(max_examples, int) or isinstance(max_examples, bool) or max_examples <= 0:
        raise ValueError("max_examples must be a positive integer")
    canonical = json.dumps(mesh, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    vertices = np.asarray(mesh["vertices"], dtype=float)
    raw_faces = np.asarray(mesh["faces"])
    if (vertices.ndim != 2 or vertices.shape[1] != 3 or len(vertices) < 3
            or not np.isfinite(vertices).all() or raw_faces.ndim != 2 or raw_faces.shape[1] != 3
            or raw_faces.dtype.kind not in "iu" or not len(raw_faces)):
        raise ValueError("Invalid vertices or triangle indices")
    faces = raw_faces.astype(np.int64)
    if np.min(faces) < 0 or np.max(faces) >= len(vertices):
        raise ValueError("Triangle index out of range")
    lower, upper = vertices.min(axis=0), vertices.max(axis=0)
    scale = float(np.linalg.norm(upper-lower))
    if not math.isfinite(scale) or scale <= 0:
        raise ValueError("Invalid numerical scale")
    normalized = (vertices-lower)/scale
    triangles = normalized[faces]
    mins, maxs = triangles.min(axis=1), triangles.max(axis=1)
    regions = mesh.get("semantic_regions")
    if not isinstance(regions, dict):
        raise ValueError("Missing semantic_regions")
    groups = {}
    for name in ("torso", "left_sleeve", "right_sleeve"):
        values = regions.get(name)
        if not isinstance(values, list) or not values or not all(type(v) is int and 0 <= v < len(vertices) for v in values):
            raise ValueError("Invalid semantic region: " + name)
        selected = np.zeros(len(vertices), dtype=bool)
        selected[values] = True
        groups[name] = np.flatnonzero(selected[faces].all(axis=1))
        if not len(groups[name]):
            raise ValueError("No complete triangles in semantic region: " + name)
    torso = groups["torso"]
    for name in ("left_sleeve", "right_sleeve"):
        if np.intersect1d(torso, groups[name]).size:
            raise ValueError("Ambiguous torso/sleeve triangle ownership: " + name)
    torso_order = torso[np.argsort(mins[torso, 0], kind="stable")]
    torso_min_x = mins[torso_order, 0]
    stats = {name: {"sleeveFaces": len(groups[name]), "aabbCandidates": 0, "sharedVertexExemptions": 0,
                    "testedPairs": 0, "properCrossings": 0, "coplanarOverlaps": 0,
                    "unresolvedContacts": 0, "unevaluatedPairs": 0} for name in ("left_sleeve", "right_sleeve")}
    examples, tested, truncated = [], 0, False
    for name in ("left_sleeve", "right_sleeve"):
        for sleeve_id in groups[name]:
            extent = int(np.searchsorted(torso_min_x, maxs[sleeve_id, 0]+RELATIVE_EPSILON, side="right"))
            possible = torso_order[:extent]
            possible = possible[np.all(maxs[possible] >= mins[sleeve_id]-RELATIVE_EPSILON, axis=1)
                                & np.all(mins[possible] <= maxs[sleeve_id]+RELATIVE_EPSILON, axis=1)]
            stats[name]["aabbCandidates"] += len(possible)
            sleeve_vertices = set(map(int, faces[sleeve_id]))
            for torso_id in possible:
                if sleeve_vertices.intersection(map(int, faces[torso_id])):
                    stats[name]["sharedVertexExemptions"] += 1
                    continue
                if tested >= max_pairs:
                    truncated = True
                    break
                result = triangle_intersection(triangles[sleeve_id], triangles[torso_id], RELATIVE_EPSILON)
                tested += 1
                stats[name]["testedPairs"] += 1
                kind = result["kind"]
                counter = {"proper_crossing": "properCrossings", "coplanar_overlap": "coplanarOverlaps",
                           "contact": "unresolvedContacts", "unevaluated": "unevaluatedPairs"}.get(kind)
                if counter:
                    stats[name][counter] += 1
                    if len(examples) < max_examples:
                        points = [(np.asarray(point)*scale+lower).tolist() for point in result.pop("points", [])]
                        examples.append({"region": name, "sleeveFace": int(sleeve_id), "torsoFace": int(torso_id),
                                         **result, "intersectionPoints": points,
                                         "pointUnits": "input_mesh_relative_units", "otherMetricUnits": "bbox_diagonal_normalized"})
            if truncated:
                break
        if truncated:
            break
    collisions = sum(v["properCrossings"]+v["coplanarOverlaps"] for v in stats.values())
    unknown = sum(v["unresolvedContacts"]+v["unevaluatedPairs"] for v in stats.values())
    status = "failed" if collisions else "unevaluated" if unknown or truncated else "passed"
    return {"schemaVersion": 1, "checkerVersion": VERSION,
            "meshCanonicalSha256": hashlib.sha256(canonical).hexdigest(),
            "scope": "left/right_sleeve triangles versus torso triangles; non-shared-index vertices only",
            "scopeStatus": status, "testedPairs": tested, "detectedIntersectionPairs": collisions,
            "unresolvedPairs": unknown, "maxPairs": max_pairs, "pairBudgetExhausted": truncated,
            "allScopedCandidatePairsEvaluated": not truncated and not unknown,
            "epsilonOverBoundingBoxDiagonal": RELATIVE_EPSILON,
            "boundingBoxDiagonal": scale, "regions": stats, "examples": examples,
            "examplesTruncated": collisions+unknown > len(examples),
            "completeClothCollisionValidation": False, "garmentAcceptance": "unevaluated",
            "limitations": [
                "Semantic labels select scope; this does not verify correct anatomical labelling.",
                "Any pair sharing an indexed vertex is exempt; even that local neighborhood can fold.",
                "Positive-area coplanar overlap is detected; edge/point-only contact needs contact semantics and remains unevaluated.",
                "Does not test sleeve/sleeve, torso/torso, sleeve self-intersections, volume containment, fabric thickness or dynamics.",
                "No intersecting surface pair is not proof of no interpenetration: an entire surface may be contained inside another.",
            ]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mesh", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--max-pairs", type=int, default=250000)
    args = parser.parse_args(argv)
    try:
        raw = args.mesh.read_bytes()
        report = check_mesh(json.loads(raw), max_pairs=args.max_pairs)
        report["inputSha256"] = hashlib.sha256(raw).hexdigest()
    except (OSError, ValueError, TypeError, KeyError, IndexError, OverflowError) as error:
        report = {"schemaVersion": 1, "checkerVersion": VERSION, "scopeStatus": "unevaluated",
                  "error": str(error), "completeClothCollisionValidation": False,
                  "garmentAcceptance": "unevaluated"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False)+"\n")
    print(json.dumps({key: report.get(key) for key in ("scopeStatus", "testedPairs", "detectedIntersectionPairs", "unresolvedPairs")}))
    return 0 if report["scopeStatus"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
