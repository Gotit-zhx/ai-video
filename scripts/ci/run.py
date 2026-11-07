#!/usr/bin/env python3
"""One local/CI command per validation scope."""
import argparse
import os
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def frontend():
    env = dict(os.environ, JSDOM_MODULE=str(ROOT / "scripts/ci/qa/node_modules/jsdom/lib/api.js"))
    tests = sorted(set((ROOT / "frontend/test").glob("*.test.cjs")) | set((ROOT / "frontend/test").glob("*.test.mjs")) | set((ROOT / "frontend/tests").glob("*.test.mjs")))
    reports=ROOT/"target/ci-reports/frontend";reports.mkdir(parents=True,exist_ok=True)
    completed=subprocess.run(["node", "--experimental-vm-modules", "--test",
        "--test-reporter=spec","--test-reporter-destination=stdout",
        "--test-reporter=junit","--test-reporter-destination="+str(reports/"junit.xml"),
        *map(str, tests)], cwd=ROOT, env=env)
    cases=list(ET.parse(reports/"junit.xml").getroot().iter("testcase"))
    skips=[c.attrib.get("name") for c in cases if c.find("skipped") is not None]
    failures=[c.attrib.get("name") for c in cases if c.find("failure") is not None or c.find("error") is not None]
    summary={"tests":len(cases),"unexpectedSkips":len(skips),"failures":failures,"skipped":skips,
             "result":"passed" if cases and not (skips or failures or completed.returncode) else "failed"}
    (reports/"summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2)+"\n")
    if summary["result"]!="passed":raise RuntimeError("Frontend tests failed, were skipped or produced no reports")
    excluded=json.loads((ROOT/"scripts/ci/optional-tests.json").read_text())["excludedQaScripts"]
    summary.update(uiSuites=[],excludedSuites=excluded,result="running")
    try:
        for test in sorted((ROOT / "scripts/qa").glob("test-*.mjs")):
            if test.name in excluded: continue
            result=subprocess.run(["node",str(test)],cwd=ROOT,env=env)
            summary["uiSuites"].append({"name":test.name,"result":"passed" if result.returncode==0 else "failed"})
            if result.returncode:raise RuntimeError("UI regression failed: "+test.name)
        subprocess.run([sys.executable, "scripts/ci/frontend.py"], cwd=ROOT, check=True)
        summary.update(result="passed",assetReferences="passed")
    except Exception:
        summary["result"]="failed"
        raise
    finally:
        (reports/"summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2)+"\n")


if __name__ == "__main__":
    p = argparse.ArgumentParser(); p.add_argument("scope", choices=("frontend", "unit", "integration"))
    a = p.parse_args()
    if a.scope == "frontend": frontend()
    else: subprocess.run([sys.executable, "scripts/ci/java.py", a.scope], cwd=ROOT, check=True)
