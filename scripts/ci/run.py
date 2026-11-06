#!/usr/bin/env python3
"""One local/CI command per validation scope."""
import argparse
import os
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def frontend():
    env = dict(os.environ, JSDOM_MODULE=str(ROOT / "scripts/ci/qa/node_modules/jsdom/lib/api.js"))
    tests = sorted(set((ROOT / "frontend/test").glob("*.test.cjs")) | set((ROOT / "frontend/test").glob("*.test.mjs")) | set((ROOT / "frontend/tests").glob("*.test.mjs")))
    subprocess.run(["node", "--experimental-vm-modules", "--test", *map(str, tests)], cwd=ROOT, env=env, check=True)
    excluded=json.loads((ROOT/"scripts/ci/optional-tests.json").read_text())["excludedQaScripts"]
    for test in sorted((ROOT / "scripts/qa").glob("test-*.mjs")):
        if test.name in excluded: continue
        subprocess.run(["node", str(test)], cwd=ROOT, env=env, check=True)
    subprocess.run([sys.executable, "scripts/ci/frontend.py"], cwd=ROOT, check=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(); p.add_argument("scope", choices=("frontend", "unit", "integration"))
    a = p.parse_args()
    if a.scope == "frontend": frontend()
    else: subprocess.run([sys.executable, "scripts/ci/java.py", a.scope], cwd=ROOT, check=True)
