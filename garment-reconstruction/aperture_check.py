#!/usr/bin/env python3
"""Read-only geometry measurements of actual mesh boundary rings.

The existing topology evaluator validates declarations against face edges; this
module then traverses those face edges, not the declared ordering. Measurements
use relative model units. A projected boundary polygon is not a visibility test.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path

import numpy as np

from evaluator import evaluate_mesh


VERSION = "actual-boundary-aperture-geometry/1"
NUMERICAL_RELATIVE_TOLERANCE = 128 * np.finfo(float).eps
DEFAULT_VIEWS = [
    {"name": "front", "yaw": 0, "pitch": 0},
    {"name": "back", "yaw": 180, "pitch": 0},
    {"name": "right", "yaw": 90, "pitch": 0},
    {"name": "left", "yaw": 270, "pitch": 0},
    {"name": "top", "yaw": 0, "pitch": 90},
    {"name": "bottom", "yaw": 0, "pitch": -90},
    {"name": "front-right", "yaw": 45, "pitch": 12},
    {"name": "back-right", "yaw": 135, "pitch": 12},
    {"name": "back-left", "yaw": 225, "pitch": 12},
    {"name": "front-left", "yaw": 315, "pitch": 12},
]
BOUNDARY_CHECKS = (
    "input_shape", "finite_vertices", "face_indices", "numerical_scale",
    "manifold_edges", "edge_orientation", "manifold_vertices", "boundary_loops",
    "opening_declarations", "actual_openings_match",
)
LIMITATIONS = [
    "This measures existing model boundaries, not reference-image or real garment reconstruction accuracy.",
    "Named openings are assigned only after their edge sets match actual face boundaries; anatomical labels are not independently established.",
    "Lengths and areas use relative model units, without physical calibration or fit thresholds.",
    "The numerical degeneracy test detects fewer than two independent spatial dimensions or a collapsed edge; it does not establish correct garment anatomy.",
    "Best-fit planes and area vectors summarize potentially nonplanar rings; no physical cap surface is constructed.",
    "Normal signs follow actual directed face-boundary winding, not an inferred outward-facing opening or collar orientation.",
    "Projected polygons ignore all faces and occlusion. Their areas do not measure visible background or prove that a camera sees through an opening.",
    "Absolute signed area is algebraic and may cancel for a self-crossing projection; it is not a polygon union area.",
    "Boundary self-contact, complete surface self-intersection, fabric thickness, inner walls, and actual collar construction are not evaluated here.",
    "Strict top/bottom projections describe the mesh only; oblique reference images cannot certify these axial silhouettes.",
]


def camera_basis(yaw, pitch):
    """Orthonormal right/up/toward-camera axes; +Y up, yaw 0 at +Z."""
    yaw, pitch = np.radians([yaw, pitch])
    right = np.array([np.cos(yaw), 0.0, -np.sin(yaw)])
    up = np.array([-np.sin(pitch)*np.sin(yaw), np.cos(pitch),
                   -np.sin(pitch)*np.cos(yaw)])
    toward = np.array([np.cos(pitch)*np.sin(yaw), np.sin(pitch),
                       np.cos(pitch)*np.cos(yaw)])
    return right, up, toward


def _proper_crossings(points, epsilon):
    """Count strict nonadjacent segment crossings; collinear contact excluded."""
    def cross(a, b):
        return float(a[0]*b[1]-a[1]*b[0])

    count, size = 0, len(points)
    for i in range(size):
        a, b = points[i], points[(i+1) % size]
        for j in range(i+2, size):
            if (j+1) % size == i:
                continue
            c, d = points[j], points[(j+1) % size]
            ab, cd = b-a, d-c
            first = [cross(ab, c-a), cross(ab, d-a)]
            second = [cross(cd, a-c), cross(cd, b-c)]
            tolerances = [epsilon*np.linalg.norm(ab), epsilon*np.linalg.norm(cd)]
            if all(min(values) < -tol and max(values) > tol
                   for values, tol in zip((first, second), tolerances)):
                count += 1
    return count


def measure_ring(points, views=None):
    """Measure an ordered 3-D polygon, without asserting physical validity."""
    points = np.asarray(points, dtype=float)
    if points.ndim != 2 or points.shape[1] != 3 or len(points) < 3 or not np.isfinite(points).all():
        raise ValueError("Expected at least three finite 3-D boundary points")
    views = DEFAULT_VIEWS if views is None else views
    center = points.mean(axis=0)
    centered = points-center
    diagonal = float(np.linalg.norm(np.ptp(points, axis=0)))
    epsilon = diagonal*NUMERICAL_RELATIVE_TOLERANCE
    _, singular, vt = np.linalg.svd(centered, full_matrices=False)
    principal_rms = singular/np.sqrt(len(points))
    edge_lengths = np.linalg.norm(np.roll(points, -1, axis=0)-points, axis=1)
    area_vector = np.sum(np.cross(centered, np.roll(centered, -1, axis=0)), axis=0)/2
    area_norm = float(np.linalg.norm(area_vector))
    rank = int(np.sum(principal_rms > epsilon)) if diagonal > 0 else 0
    collapsed_edges = np.where(edge_lengths <= epsilon)[0].tolist()
    nondegenerate = rank >= 2 and not collapsed_edges
    normal, normal_source = None, None
    if rank >= 2:
        normal = vt[-1].copy()
        if area_norm > diagonal*epsilon:
            if np.dot(normal, area_vector) < 0:
                normal = -normal
            normal_source = "best_fit_plane_aligned_with_face_boundary_area_vector"
        else:
            if normal[np.argmax(np.abs(normal))] < 0:
                normal = -normal
            normal_source = "best_fit_plane_canonical_sign_area_vector_near_zero"
    residuals = centered @ normal if normal is not None else None
    projections = {}
    for view in views:
        right, up, toward = camera_basis(view["yaw"], view["pitch"])
        polygon = np.stack([centered @ right, centered @ up], axis=1)
        projected_singular = np.linalg.svd(polygon, compute_uv=False)/np.sqrt(len(points))
        signed_area = float(np.sum(polygon[:, 0]*np.roll(polygon[:, 1], -1)
                                   - polygon[:, 1]*np.roll(polygon[:, 0], -1))/2)
        crossings = _proper_crossings(polygon, epsilon)
        projections[view["name"]] = {
            "yawDegrees": view["yaw"], "pitchDegrees": view["pitch"],
            "towardCamera": toward.tolist(), "screenRight": right.tolist(), "screenUp": up.tolist(),
            "centerOnScreen": [float(center @ right), float(center @ up)],
            "boundaryRelativeToCenter2D": polygon.tolist(),
            "width": float(np.ptp(polygon[:, 0])), "height": float(np.ptp(polygon[:, 1])),
            "perimeter": float(np.linalg.norm(np.roll(polygon, -1, axis=0)-polygon, axis=1).sum()),
            "signedArea": signed_area, "absoluteSignedArea": abs(signed_area),
            "spatialProjectionRank": int(np.sum(projected_singular > epsilon)),
            "properNonadjacentEdgeCrossings": crossings,
            "crossingTestScope": "strict crossings only; excludes collinear overlap and touching",
            "normalTowardCameraDot": float(normal @ toward) if normal is not None else None,
            "visibleBackgroundArea": None,
            "visibilityStatus": "unevaluated_requires_occlusion_test",
        }
    return {
        "vertexCount": len(points),
        "numericalDegeneracyStatus": "passed" if nondegenerate else "failed",
        "numericalLengthTolerance": epsilon,
        "spatialRank": rank,
        "collapsedBoundaryEdgeIndices": collapsed_edges,
        "centerVertexMean": center.tolist(),
        "axisAlignedExtentXYZ": np.ptp(points, axis=0).tolist(),
        "perimeter3D": float(edge_lengths.sum()),
        "minimumEdgeLength3D": float(edge_lengths.min()),
        "maximumEdgeLength3D": float(edge_lengths.max()),
        "principalRmsLengths": principal_rms.tolist(),
        "areaVector": area_vector.tolist(), "areaVectorMagnitude": area_norm,
        "bestFitPlane": {
            "normal": normal.tolist() if normal is not None else None,
            "normalConvention": normal_source,
            "rmsResidual": float(np.sqrt(np.mean(residuals**2))) if residuals is not None else None,
            "maxAbsoluteResidual": float(np.abs(residuals).max()) if residuals is not None else None,
            "rmsResidualOverRingDiagonal": float(np.sqrt(np.mean(residuals**2))/diagonal)
            if residuals is not None else None,
            "absoluteSignedProjectedArea": float(abs(area_vector @ normal)) if normal is not None else None,
            "planeFitPhysicalAcceptance": "unevaluated_no_physical_threshold",
        },
        "projections": projections,
    }


def actual_boundary_rings(mesh):
    """Traverse single-use directed face edges, retaining actual face winding.

    Call only after the existing evaluator has validated the boundary contract.
    Declarations provide labels through equal edge sets, never traversal order.
    """
    uses = defaultdict(list)
    for a, b, c in mesh["faces"]:
        for u, v in ((a, b), (b, c), (c, a)):
            uses[tuple(sorted((u, v)))].append((u, v))
    directed = [edges[0] for edges in uses.values() if len(edges) == 1]
    following = dict(directed)
    if len(following) != len(directed):
        raise ValueError("Boundary edges do not have unique outgoing directions")
    remaining, rings = set(following), []
    while remaining:
        first = min(remaining)
        ring, current = [], first
        while current in remaining:
            ring.append(current)
            remaining.remove(current)
            current = following[current]
        if current != first:
            raise ValueError("Boundary traversal did not close")
        edges = {tuple(sorted((ring[i], ring[(i+1) % len(ring)]))) for i in range(len(ring))}
        rings.append((ring, edges))
    result = {}
    for name, declared in mesh["openings"].items():
        ring = declared[:-1] if declared[0] == declared[-1] else declared
        edges = {tuple(sorted((ring[i], ring[(i+1) % len(ring)]))) for i in range(len(ring))}
        matches = [actual for actual, actual_edges in rings if actual_edges == edges]
        if len(matches) != 1:
            raise ValueError("Declared opening does not identify one actual boundary: " + name)
        result[name] = matches[0]
    return result


def evaluate_apertures(mesh, views=None):
    topology = evaluate_mesh(mesh)
    selected = {name: topology["checks"].get(name, {"status": "unevaluated"}) for name in BOUNDARY_CHECKS}
    verified = all(check["status"] == "passed" for check in selected.values())
    report = {
        "schemaVersion": 1, "evaluatorVersion": VERSION,
        "status": "partial_geometry_checks_only" if verified else "boundary_validation_failed",
        "meshUnits": mesh.get("units") if isinstance(mesh, dict) else None,
        "coordinateConvention": "+Y up; front at +Z; positive yaw toward +X; positive pitch above",
        "sourceBoundaryEvaluator": topology["evaluatorVersion"],
        "boundaryValidation": selected, "boundaryDeclarationsVerified": verified,
        "openings": {}, "failedOpeningDegeneracyChecks": [],
        "allActualOpeningsNumericallyNondegenerate": None,
        "completeGarmentAcceptancePassed": False,
        "numericalTolerancePolicy": "128 * float64 epsilon * each ring bounding-box diagonal; numerical only",
        "limitations": LIMITATIONS,
    }
    if not verified:
        return report
    vertices = np.asarray(mesh["vertices"], dtype=float)
    for name, ring in actual_boundary_rings(mesh).items():
        measurement = measure_ring(vertices[ring], views)
        measurement["actualBoundaryVertexIndices"] = ring
        report["openings"][name] = measurement
        if measurement["numericalDegeneracyStatus"] == "failed":
            report["failedOpeningDegeneracyChecks"].append(name)
    report["allActualOpeningsNumericallyNondegenerate"] = not report["failedOpeningDegeneracyChecks"]
    if report["failedOpeningDegeneracyChecks"]:
        report["status"] = "opening_numerical_degeneracy_failed"
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mesh", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-sha256", help="Refuse measurement if the source bytes differ")
    args = parser.parse_args()
    if args.mesh.resolve() == args.output.resolve():
        parser.error("Output must not replace the mesh")
    source = args.mesh.read_bytes()
    source_hash = hashlib.sha256(source).hexdigest()
    if args.expected_sha256 and args.expected_sha256 != source_hash:
        parser.error("Mesh SHA-256 does not match the frozen source")
    report = evaluate_apertures(json.loads(source))
    report["meshSourcePath"] = str(args.mesh.resolve())
    report["meshSourceSha256"] = source_hash
    report["sourceCodeSha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n")
    print(json.dumps({"status": report["status"],
                      "allActualOpeningsNumericallyNondegenerate": report["allActualOpeningsNumericallyNondegenerate"],
                      "output": str(args.output)}, ensure_ascii=False))
    return 0 if report["status"] == "partial_geometry_checks_only" else 1


if __name__ == "__main__":
    raise SystemExit(main())
