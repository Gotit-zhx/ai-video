"""Run the pinned upstream watch skill, preserving its exact frame timestamps.

The human report rounds timestamps to whole seconds. This thin adapter records
the selected frames returned by watch's own scene/dedup engine without sampling
or modifying them, then writes a machine-readable manifest beside its report.
"""
import importlib.util
import json
from pathlib import Path
import sys

script, source, output = map(Path, sys.argv[1:4])
sys.path.insert(0, str(script.resolve().parent))
spec = importlib.util.spec_from_file_location("watch_skill", script)
watch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(watch)
selected = {}
original = watch.extract_scene_or_uniform
metadata = watch.get_metadata

def capture(*args, **kwargs):
    frames, details = original(*args, **kwargs)
    selected["frames"] = frames
    selected["selection"] = details
    return frames, details

def capture_metadata(*args, **kwargs):
    result = metadata(*args, **kwargs)
    selected["video"] = result
    return result

watch.extract_scene_or_uniform = capture
watch.get_metadata = capture_metadata
sys.argv = [str(script), str(source), "--detail", "balanced", "--max-frames", "30",
            "--resolution", "512", "--no-whisper", "--out-dir", str(output)]
code = watch.main()
if code:
    raise SystemExit(code)
if not selected.get("frames"):
    raise SystemExit("watch did not select any frames")
import importlib.util as _util
_motion_spec = _util.spec_from_file_location("watch_motion", Path(__file__).with_name("watch-motion.py"))
_motion = _util.module_from_spec(_motion_spec)
_motion_spec.loader.exec_module(_motion)
for index, frame in enumerate(selected["frames"]):
    frame["motion"] = _motion.motion_sheet(source, output / f"motion-{index:03d}.jpg",
                                            float(frame["timestamp_seconds"]),
                                            float(selected["video"]["duration_seconds"]))
(output / "watch-frames.json").write_text(json.dumps(selected, default=str), encoding="utf-8")
