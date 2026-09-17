#!/usr/bin/env python3
"""Independent, read-only PoC mesh checks; never a complete garment approval.

Only the Python standard library is required. Exit 0 means implemented structural
checks passed, not that likeness, intersections, or garment fidelity were proven.
"""

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path


VERSION = "garment-mesh-poc-evaluator/1"
OPENING_NAMES = {"neck", "hem", "left_cuff", "right_cuff"}
EXPERIMENTAL_THRESHOLDS = {
    "triangleAreaOverBoundingBoxDiagonalSquared": 1e-10,
    "minTorsoDepthOverWidth": 0.08,
    "maxTorsoDepthOverWidth": 1.25,
}


def _integer(value):
    return isinstance(value, int) and not isinstance(value, bool)


def _finite_number(value):
    try:
        return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)
    except OverflowError:
        return False


def _edge(a, b):
    return (a, b) if a < b else (b, a)


def _components(nodes, adjacency):
    remaining = set(nodes)
    result = []
    while remaining:
        start = min(remaining)
        remaining.remove(start)
        component, pending = {start}, [start]
        while pending:
            for other in adjacency.get(pending.pop(), ()):
                if other in remaining:
                    remaining.remove(other)
                    component.add(other)
                    pending.append(other)
        result.append(component)
    return result


def evaluate_mesh(mesh):
    """Compute diagnostics from actual vertices/faces, without trusting claims."""
    checks = {}

    def record(name, status, metrics=None, reason=None):
        checks[name] = {"status": status, "metrics": metrics or {}}
        if reason:
            checks[name]["reason"] = reason

    def check(name, passed, metrics=None, reason=None):
        record(name, "passed" if passed else "failed", metrics, reason)

    def finish():
        failed = [key for key, value in checks.items() if value["status"] == "failed"]
        for key, reason in (
            ("self_intersections", "Triangle intersection and cloth contact classification are not implemented."),
            ("sleeve_body_intersections", "Sleeve folding into or adhering to the torso is not tested by topology or shared-root checks."),
            ("material_thickness_inner_walls", "Open boundary rings do not prove fabric thickness, inner walls or folded hems."),
            ("reference_similarity", "No independent reference/camera/annotation evidence was evaluated."),
            ("physical_dimensions", "No calibrated measurement or scan was supplied to this evaluator."),
        ):
            record(key, "unevaluated", reason=reason)
        return {
            "schemaVersion": 1,
            "evaluatorVersion": VERSION,
            "thresholds": dict(EXPERIMENTAL_THRESHOLDS),
            "thresholdStatus": "experimental_poc_not_calibrated_on_real_garments",
            "checks": checks,
            "failedChecks": failed,
            "implementedStructuralChecksPassed": not failed,
            "decision": "rejected_by_structural_checks" if failed else "partial_checks_only",
            "garmentAcceptance": "unevaluated",
            "completeGarmentAcceptancePassed": False,
            "limitations": [
                "Topology and declared semantic consistency do not prove correct garment anatomy.",
                "Torso ratio is an experimental collapse heuristic, not physical accuracy.",
                "Unmeasured intersections, reference similarity and dimensions are never inferred from a label.",
            ],
        }

    if not isinstance(mesh, dict):
        check("input_shape", False, reason="Expected a JSON object.")
        return finish()
    vertices, faces = mesh.get("vertices"), mesh.get("faces")
    shape_ok = (isinstance(vertices, list) and len(vertices) >= 4
                and all(isinstance(v, list) and len(v) == 3 for v in vertices)
                and isinstance(faces, list) and len(faces) > 0
                and all(isinstance(f, list) and len(f) == 3 for f in faces))
    check("input_shape", shape_ok)
    if not shape_ok:
        return finish()
    finite = all(_finite_number(x) for v in vertices for x in v)
    indices = all(_integer(x) and 0 <= x < len(vertices) for f in faces for x in f)
    check("finite_vertices", finite)
    check("face_indices", indices, {"vertices": len(vertices), "faces": len(faces)})
    if not finite or not indices:
        return finish()

    extents = [max(v[a] for v in vertices) - min(v[a] for v in vertices) for a in range(3)]
    diagonal_squared = sum(x * x for x in extents)
    check("numerical_scale", _finite_number(diagonal_squared) and diagonal_squared > 0,
          reason="Bounding dimensions must permit finite nonzero scale-normalized calculations.")
    if not _finite_number(diagonal_squared) or diagonal_squared <= 0:
        return finish()
    area_tolerance = diagonal_squared * EXPERIMENTAL_THRESHOLDS["triangleAreaOverBoundingBoxDiagonalSquared"]
    degenerate, areas = [], []
    edge_faces = defaultdict(list)
    vertex_faces = defaultdict(list)
    for i, (a, b, c) in enumerate(faces):
        ab = [vertices[b][j] - vertices[a][j] for j in range(3)]
        ac = [vertices[c][j] - vertices[a][j] for j in range(3)]
        cross = [ab[1]*ac[2]-ab[2]*ac[1], ab[2]*ac[0]-ab[0]*ac[2], ab[0]*ac[1]-ab[1]*ac[0]]
        area = math.hypot(*cross) / 2
        areas.append(area)
        if len({a, b, c}) != 3 or area <= area_tolerance:
            degenerate.append(i)
        for vertex in {a, b, c}:
            vertex_faces[vertex].append(i)
        for u, v in ((a, b), (b, c), (c, a)):
            edge_faces[_edge(u, v)].append((i, u, v))
    check("degenerate_faces", not degenerate,
          {"count": len(degenerate), "sampleFaces": degenerate[:20], "areaTolerance": area_tolerance})
    duplicate_count = sum(count - 1 for count in Counter(tuple(sorted(f)) for f in faces).values() if count > 1)
    check("duplicate_faces", duplicate_count == 0, {"count": duplicate_count})
    unused = sorted(set(range(len(vertices))) - set(vertex_faces))
    check("unused_vertices", not unused, {"count": len(unused), "sampleVertices": unused[:20]})

    nonmanifold = [edge for edge, uses in edge_faces.items() if len(uses) > 2]
    wrong_direction = [edge for edge, uses in edge_faces.items()
                       if len(uses) == 2 and uses[0][1:] == uses[1][1:]]
    check("manifold_edges", not nonmanifold, {"count": len(nonmanifold), "sampleEdges": nonmanifold[:20]})
    check("edge_orientation", not wrong_direction,
          {"inconsistentSharedEdges": len(wrong_direction), "sampleEdges": wrong_direction[:20]})

    face_adjacency = defaultdict(set)
    vertex_fan_adjacency = defaultdict(lambda: defaultdict(set))
    for (u, v), uses in edge_faces.items():
        ids = [use[0] for use in uses]
        for j in ids[1:]:
            i = ids[0]
            if i != j:
                face_adjacency[i].add(j)
                face_adjacency[j].add(i)
                for vertex in (u, v):
                    vertex_fan_adjacency[vertex][i].add(j)
                    vertex_fan_adjacency[vertex][j].add(i)
    components = _components(range(len(faces)), face_adjacency)
    check("connected_surface", len(components) == 1,
          {"faceComponents": len(components), "componentFaceCounts": sorted(map(len, components), reverse=True)})
    split_fans = [v for v, incident in vertex_faces.items()
                  if len(_components(incident, vertex_fan_adjacency[v])) != 1]
    check("manifold_vertices", not split_fans,
          {"splitVertexFans": len(split_fans), "sampleVertices": split_fans[:20]})

    boundary_edges = {edge for edge, uses in edge_faces.items() if len(uses) == 1}
    boundary_adjacency = defaultdict(set)
    for u, v in boundary_edges:
        boundary_adjacency[u].add(v)
        boundary_adjacency[v].add(u)
    bad_boundary = [v for v, neighbors in boundary_adjacency.items() if len(neighbors) != 2]
    boundary_components = _components(boundary_adjacency, boundary_adjacency)
    actual_loops = [set(edge for edge in boundary_edges if edge[0] in group) for group in boundary_components]
    check("boundary_loops", not bad_boundary and len(actual_loops) == 4,
          {"actualLoopCount": len(actual_loops), "boundaryEdgeCount": len(boundary_edges),
           "branchedOrOpenVertices": bad_boundary[:20], "loopEdgeCounts": sorted(map(len, actual_loops))},
          "This PoC contract is one assembled short-sleeve shell with exactly four specified openings.")

    openings = mesh.get("openings")
    declaration_errors, declared = [], {}
    if not isinstance(openings, dict) or set(openings) != OPENING_NAMES:
        declaration_errors.append("Expected exactly neck, hem, left_cuff and right_cuff.")
    else:
        for name, ring in openings.items():
            if isinstance(ring, list) and len(ring) > 1 and ring[0] == ring[-1]:
                ring = ring[:-1]
            if (not isinstance(ring, list) or len(ring) < 3
                    or not all(_integer(v) and 0 <= v < len(vertices) for v in ring)
                    or len(set(ring)) != len(ring)):
                declaration_errors.append("Invalid vertex ring: " + name)
                continue
            declared[name] = {_edge(ring[i], ring[(i+1) % len(ring)]) for i in range(len(ring))}
    check("opening_declarations", not declaration_errors, {"errors": declaration_errors})
    unmatched, claimed = [], set()
    for name, ring_edges in declared.items():
        if sum(ring_edges == actual for actual in actual_loops) != 1 or claimed.intersection(ring_edges):
            unmatched.append(name)
        claimed.update(ring_edges)
    check("actual_openings_match", not declaration_errors and not unmatched
          and claimed == boundary_edges and not bad_boundary,
          {"unmatchedOpenings": unmatched, "undeclaredBoundaryEdges": len(boundary_edges - claimed),
           "declaredEdgesNotBoundary": len(claimed - boundary_edges)},
          "Exact boundary edge sets are rebuilt from faces; declarations alone cannot pass.")

    regions = mesh.get("semantic_regions")
    region_errors = []
    region_sets = {}
    if not isinstance(regions, dict):
        region_errors.append("semantic_regions must be an object.")
    else:
        for name, values in regions.items():
            if (not isinstance(values, list) or not all(_integer(v) and 0 <= v < len(vertices) for v in values)):
                region_errors.append("Invalid vertex group: " + str(name))
            else:
                region_sets[name] = set(values)
    missing = [name for name in ("torso", "chest", "left_sleeve", "right_sleeve") if not region_sets.get(name)]
    check("semantic_regions", not region_errors and not missing,
          {"errors": region_errors, "missingRequiredRegions": missing},
          "These names are the fixed PoC contract, not proof that vertices were correctly labelled.")
    group = region_sets.get("chest") or region_sets.get("torso")
    if group:
        width = max(vertices[v][0] for v in group) - min(vertices[v][0] for v in group)
        depth = max(vertices[v][2] for v in group) - min(vertices[v][2] for v in group)
        ratio = depth / width if width > 0 else None
        passed = (ratio is not None and EXPERIMENTAL_THRESHOLDS["minTorsoDepthOverWidth"] <= ratio
                  <= EXPERIMENTAL_THRESHOLDS["maxTorsoDepthOverWidth"])
        check("torso_collapse_experimental", passed,
              {"region": "chest" if region_sets.get("chest") else "torso", "widthX": width,
               "depthZ": depth, "depthOverWidth": ratio},
              "Experimental bounding-range heuristic; not a local volume or physical thickness proof.")
    else:
        record("torso_collapse_experimental", "unevaluated", reason="No usable torso/chest region.")
    if all(name in region_sets for name in ("torso", "left_sleeve", "right_sleeve")):
        attachment = {}
        for side in ("left", "right"):
            sleeve = region_sets[side + "_sleeve"]
            common = sleeve.intersection(region_sets["torso"])
            attachment[side] = sum(1 for (u, v), uses in edge_faces.items()
                                   if len(uses) == 2 and u in common and v in common)
        check("shared_sleeve_root_edges", all(count > 0 for count in attachment.values()),
              {"sharedInteriorEdgeCounts": attachment},
              "Checks actual shared indexed edges; full sleeve-root anatomical placement remains unverified.")
    else:
        record("shared_sleeve_root_edges", "unevaluated", reason="Sleeve/torso groups unavailable.")
    return finish()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mesh", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        raw = args.mesh.read_bytes()
        mesh = json.loads(raw)
        report = evaluate_mesh(mesh)
        report["inputSha256"] = hashlib.sha256(raw).hexdigest()
    except (OSError, ValueError, TypeError, OverflowError) as error:
        report = {"schemaVersion": 1, "evaluatorVersion": VERSION, "decision": "input_error",
                  "errorType": type(error).__name__, "completeGarmentAcceptancePassed": False,
                  "garmentAcceptance": "unevaluated"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False) + "\n")
    print(json.dumps({key: report.get(key) for key in
                      ("decision", "failedChecks", "completeGarmentAcceptancePassed")}, ensure_ascii=False))
    return 0 if report.get("implementedStructuralChecksPassed") else 1


if __name__ == "__main__":
    raise SystemExit(main())
