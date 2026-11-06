#!/usr/bin/env python3
"""Loopback job runner: Python geometry/optimizer + real browser WebGPU scores.

GPU errors invalidate an entire stage. The runner may restart that stage on the
CPU; it never compares scores from different rasterizers in one search trace.
"""
from __future__ import annotations

import argparse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import time
from urllib.parse import parse_qs, urlparse
import uuid

import cv2
import numpy as np

from fit_v2 import ScoreBackendError

ROOT = Path(__file__).resolve().parents[2]


class GpuBroker:
    def __init__(self, timeout=45):
        self.condition = threading.Condition()
        self.timeout = timeout
        self.pending = None
        self.response = None
        self.serial = 0
        self.state = {"state": "idle", "jobId": None}
        self.history = []
        self.adapter = None

    def update(self, **values):
        with self.condition:
            self.state.update(values)
            self.condition.notify_all()

    def status(self):
        with self.condition:
            return dict(self.state)

    def request(self, task):
        started = time.monotonic()
        with self.condition:
            if self.pending is not None:
                raise ScoreBackendError("A previous GPU request is still active")
            self.serial += 1
            request_id = self.serial
            self.pending = {"id": request_id, **task}
            self.response = None
            self.condition.notify_all()
            while self.response is None:
                remaining = self.timeout - (time.monotonic() - started)
                if remaining <= 0:
                    self.pending = None
                    self.condition.notify_all()
                    raise ScoreBackendError("Browser GPU response timed out; stage must restart on CPU")
                self.condition.wait(remaining)
            response = self.response
            self.pending = self.response = None
            self.condition.notify_all()
        elapsed = time.monotonic() - started
        self.history.append({"id": request_id, "type": task["type"], "seconds": elapsed,
                             "candidateCount": len(task.get("candidates", [])), "ok": response.get("ok") is True})
        if response.get("ok") is not True:
            raise ScoreBackendError(str(response.get("error", "GPU task failed")))
        if task["type"] == "configure":
            self.adapter = response.get("adapter")
        return response

    def next_task(self, job_id):
        with self.condition:
            if job_id != self.state.get("jobId"):
                raise ValueError("Unknown job")
            self.condition.wait_for(lambda: (self.pending is not None and self.response is None)
                                    or self.state["state"] != "running", timeout=10)
            if self.pending is not None and self.response is None:
                return dict(self.pending)
            return {"type": "finished" if self.state["state"] != "running" else "idle", "status": dict(self.state)}

    def submit(self, value):
        with self.condition:
            if (value.get("jobId") != self.state.get("jobId") or self.pending is None
                    or value.get("id") != self.pending["id"] or self.response is not None):
                raise ValueError("Stale, duplicate or unknown GPU result")
            self.response = value
            self.condition.notify_all()

    def start(self):
        with self.condition:
            if self.state["state"] == "running":
                raise ValueError("A fitting job is already running")
            job_id = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
            self.pending = self.response = None
            self.history = []
            self.adapter = None
            self.state = {"state": "running", "jobId": job_id, "phase": "starting", "progress": 0}
        threading.Thread(target=self._run, args=(job_id,), daemon=True).start()
        return self.status()

    def _run(self, job_id):
        try:
            from run_gpu_repair import run_repair
            output = ROOT / "output/garment-gpu-repair-runs" / job_id
            result = run_repair(self, output)
            self.update(state="completed", phase="finished", progress=1, **result)
        except Exception as error:
            self.update(state="failed", phase="failed", error=f"{type(error).__name__}: {error}")
        finally:
            with self.condition:
                self.pending = None
                self.condition.notify_all()


class RemoteGpuScorer:
    def __init__(self, broker, mesh, targets, cameras, size):
        self.broker = broker
        self.count = len(targets)
        self.indices = mesh["faces"]
        self.vertex_count = len(mesh["vertices"])
        self.measurements = []
        weights, masks, areas = [], [], []
        for target in targets:
            t = (target > 0).astype(np.uint8)
            field = cv2.distanceTransform(1 - t, cv2.DIST_L2, 5) - cv2.distanceTransform(t, cv2.DIST_L2, 5)
            masks.append(t.ravel().tolist())
            weights.append((1 + np.abs(field)).astype(np.float32).ravel().tolist())
            areas.append(max(int(t.sum()), 1))
        broker.request({"type": "configure", "config": {"width": size, "height": size,
                        "cameras": cameras, "targetMasks": masks, "weights": weights,
                        "targetAreas": areas, "indices": np.asarray(mesh["faces"]).ravel().tolist(),
                        "positions": np.asarray(mesh["vertices"]).ravel().tolist()}})

    def __call__(self, meshes):
        if any(mesh["faces"] != self.indices or len(mesh["vertices"]) != self.vertex_count for mesh in meshes):
            raise ScoreBackendError("Candidate topology changed within the configured GPU stage")
        result = self.broker.request({"type": "evaluate", "candidates": [
            {"positions": np.asarray(mesh["vertices"]).ravel().tolist()} for mesh in meshes]})
        rows = result.get("results")
        if not isinstance(rows, list) or len(rows) != len(meshes):
            raise ScoreBackendError("GPU returned an incomplete candidate batch")
        losses = []
        for row in rows:
            scores = row.get("scores", [])
            if len(scores) != self.count:
                raise ScoreBackendError("GPU returned an incomplete view batch")
            values = [score.get("weightedLoss") for score in scores]
            if any(not isinstance(v, (int, float)) or not np.isfinite(v) or v < 0 for v in values):
                raise ScoreBackendError("GPU returned invalid weighted losses")
            losses.append(values)
            self.measurements.append(row.get("timings", {}))
        return losses


def handler_class(broker):
    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(ROOT), **kwargs)

        def log_message(self, *_):
            pass

        def respond(self, data, code=200):
            raw = json.dumps(data, allow_nan=False, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(raw)

        def allowed_origin(self):
            host = self.headers.get("Host", "")
            expected = {f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"}
            return host in expected and self.headers.get("Origin", f"http://{host}") == f"http://{host}"

        def do_GET(self):
            path = urlparse(self.path)
            if path.path.startswith("/api/"):
                if not self.allowed_origin():
                    return self.respond({"error": "Origin rejected"}, 403)
                try:
                    if path.path == "/api/gpu-fit/status":
                        return self.respond(broker.status())
                    if path.path == "/api/gpu-fit/task":
                        return self.respond(broker.next_task(parse_qs(path.query).get("jobId", [None])[0]))
                    return self.respond({"error": "Not found"}, 404)
                except ValueError as error:
                    return self.respond({"error": str(error)}, 409)
            # Serve only this prototype and its artifacts, never project secrets.
            translated = Path(self.translate_path(self.path)).resolve()
            allowed = translated.is_relative_to(ROOT / "experiments/garment-reconstruction")
            if translated.is_relative_to(ROOT / "output"):
                first = translated.relative_to(ROOT / "output").parts[0]
                allowed = first.startswith(("garment-", "shirt-"))
            if not allowed or not translated.is_file() or translated.suffix.lower() not in {".html", ".js", ".json", ".png", ".jpg", ".webp", ".css"}:
                return self.respond({"error": "Not found"}, 404)
            return super().do_GET()

        def do_POST(self):
            if not self.allowed_origin() or self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                return self.respond({"error": "Origin or content type rejected"}, 403)
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 2_000_000:
                    return self.respond({"error": "Invalid result size"}, 413)
                value = json.loads(self.rfile.read(size))
                if not isinstance(value, dict):
                    raise ValueError("Expected object")
                if self.path == "/api/gpu-fit/start":
                    return self.respond(broker.start())
                if self.path == "/api/gpu-fit/result":
                    broker.submit(value)
                    return self.respond({"ok": True})
                return self.respond({"error": "Not found"}, 404)
            except (ValueError, json.JSONDecodeError) as error:
                return self.respond({"error": str(error)}, 409)
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8768)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler_class(GpuBroker()))
    print(f"GPU fitting ready: http://127.0.0.1:{args.port}/experiments/garment-reconstruction/gpu-fit.html", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
