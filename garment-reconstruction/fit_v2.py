"""Staged bounded geometry fitting with fixed evidence and an explicit trace.

This changes shape parameters only. It neither changes reference images/cameras
nor treats a low fitting loss as garment acceptance.
"""
from __future__ import annotations

import time

import cv2
import numpy as np
from scipy.optimize import minimize_scalar

from image_fit import digest, render_mask


class ScoreBackendError(Exception):
    """A scorer failure must abort/restart a stage, never masquerade as bad geometry."""


def fit_stage(build_mesh, start, names, bounds, targets, cameras, size,
              max_evaluations=1500, stage="geometry", score_meshes=None, on_progress=None):
    if max_evaluations < 2:
        raise ValueError("A stage needs at least two candidate evaluations")
    before = digest(cameras)
    started = time.monotonic()
    trace, cache = [], {}
    best = {"loss": float("inf"), "params": dict(start)}
    class BudgetReached(Exception):
        pass
    fields = []
    for target in targets:
        t = (target > 0).astype(np.uint8)
        fields.append(cv2.distanceTransform(1 - t, cv2.DIST_L2, 5)
                      - cv2.distanceTransform(t, cv2.DIST_L2, 5))

    def cpu_score(meshes):
        result = []
        for mesh in meshes:
            losses = []
            for target, camera, field in zip(targets, cameras, fields):
                actual = render_mask(mesh, camera, size) > 0
                mismatch = (target > 0) != actual
                losses.append(float(np.sum(mismatch * (1 + np.abs(field)))
                                    / max(np.count_nonzero(target), 1)))
            result.append(losses)
        return result

    def evaluate_batch(vectors):
        keys = [tuple(round(float(v), 8) for v in values) for values in vectors]
        pending, meshes, seen = [], [], set(cache)
        exhausted = False
        for key, values in zip(keys, vectors):
            if key in seen:
                continue
            if len(trace) + len(pending) >= max_evaluations:
                exhausted = True
                break
            seen.add(key)
            params = {**start, **dict(zip(names, map(float, values)))}
            entry = {"evaluation": len(trace) + len(pending) + 1, "params": params}
            try:
                mesh = build_mesh(params)
                entry["geometry_valid"] = True
                meshes.append(mesh)
            except (ValueError, RuntimeError) as error:
                entry.update(loss=1e6, geometry_valid=False, error=str(error))
            pending.append((key, entry))
        scores = (score_meshes or cpu_score)(meshes) if meshes else []
        if (len(scores) != len(meshes) or any(len(row) != len(targets) or
                any(not np.isfinite(value) or value < 0 for value in row) for row in scores)):
            raise ScoreBackendError("Scorer returned missing, non-finite or negative per-view losses")
        valid = iter(scores)
        for key, entry in pending:
            if entry["geometry_valid"]:
                losses = list(map(float, next(valid)))
                entry.update(loss=float(np.mean(losses)), view_losses=losses)
            loss = entry["loss"]
            entry["best_so_far"] = bool(loss < best["loss"] and entry["geometry_valid"])
            if entry["best_so_far"]:
                best.update(loss=loss, params=entry["params"])
            trace.append(entry)
            cache[key] = loss
        if on_progress and pending:
            on_progress({"stage": stage, "evaluations": len(trace), "budget": max_evaluations,
                         "best_loss": best["loss"]})
        if exhausted:
            raise BudgetReached()
        return [cache[key] for key in keys]

    def evaluate(values):
        return evaluate_batch([values])[0]

    initial = np.asarray([start[n] for n in names])
    initial_loss = evaluate(initial)
    if not trace[0]["geometry_valid"]:
        raise ValueError(f"Invalid starting geometry: {trace[0]['error']}")
    cursor = initial.copy()
    cursor_loss = initial_loss
    sweeps = []
    reason = "Coordinate sweep limit reached"
    # A bounded line optimizer can search only invalid values when a coupled
    # feasible interval is narrow. Always keep and evaluate the current feasible
    # point, then accept only a measured improvement. An invalid line result is
    # never carried into the next parameter's starting vector.
    try:
        for sweep in range(7):
            previous = cursor_loss
            for coordinate, (lower, upper) in enumerate(bounds):
                center = float(cursor[coordinate])
                span = upper - lower
                probes = sorted(set([center, *np.linspace(lower, upper, 7).tolist(),
                                     *[float(np.clip(center + delta * span, lower, upper))
                                       for delta in [-.2, -.07, -.02, .02, .07, .2]]]))
                trials = []
                for value in probes:
                    trial = cursor.copy()
                    trial[coordinate] = value
                    trials.append(trial)
                line = list(zip(evaluate_batch(trials), probes))
                loss, value = min(line)
                candidate = cursor.copy()
                candidate[coordinate] = value
                position = probes.index(value)
                lo = probes[max(0, position - 1)]
                hi = probes[min(len(probes) - 1, position + 1)]
                if hi - lo > 1e-6:
                    def scalar(test_value):
                        trial = cursor.copy()
                        trial[coordinate] = test_value
                        return evaluate(trial)
                    optimum = minimize_scalar(scalar, bounds=(lo, hi), method="bounded",
                                              options={"maxiter": 15, "xatol": .00015})
                    if optimum.fun < loss:
                        loss = float(optimum.fun)
                        candidate[coordinate] = float(optimum.x)
                if loss < cursor_loss:
                    cursor, cursor_loss = candidate, loss
            sweeps.append({"sweep": sweep + 1, "before_loss": previous, "after_loss": cursor_loss})
            if previous - cursor_loss < 1e-5:
                reason = "No measured improvement in a full feasible coordinate sweep"
                break
    except BudgetReached:
        reason = "Fixed stage evaluation budget reached; best feasible candidate retained"
    if digest(cameras) != before:
        raise RuntimeError("Fitting modified the frozen cameras")
    return best["params"], {
        "stage": stage, "parameter_names": names, "bounds": bounds,
        "initial_loss": initial_loss, "best_loss": best["loss"], "params": best["params"],
        "evaluations": len(trace), "max_evaluations": max_evaluations,
        "optimizer_message": reason, "elapsed_seconds": time.monotonic() - started,
        "camera_hash": before, "trace": trace, "fit_size": size,
        "method": "feasible coordinate sweeps with bounded scalar refinement", "sweeps": sweeps,
        "score_backend": "external_batch" if score_meshes else "opencv_cpu",
        "evaluation_note": "Each unique attempted parameter vector is recorded; cached repeats are not rerendered.",
    }
