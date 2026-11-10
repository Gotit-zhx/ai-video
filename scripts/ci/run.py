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


def frontend_selection(required):
    import scope
    required=set(required);catalog=scope.catalog()
    if 'all-frontend' in required:
        selected=catalog['all-frontend']['files']
    elif 'finance-frontend' in required:selected=catalog['finance-frontend']['files']
    elif required=={'frontend-assets'}:selected=[]
    else:raise ValueError('Unknown frontend suite selection')
    tests=[ROOT/p for p in selected if p.startswith(('frontend/test/','frontend/tests/'))]
    qa=[ROOT/p for p in selected if p.startswith('scripts/qa/test-')]
    return tests,qa


def frontend(required=None):
    required=required or ['all-frontend']
    env = dict(os.environ, JSDOM_MODULE=str(ROOT / "scripts/ci/qa/node_modules/jsdom/lib/api.js"))
    tests,qa=frontend_selection(required)
    reports=ROOT/"target/ci-reports/frontend";reports.mkdir(parents=True,exist_ok=True)
    cases=[];returncode=0
    if tests:
        completed=subprocess.run(["node", "--experimental-vm-modules", "--test",
            "--test-reporter=spec","--test-reporter-destination=stdout",
            "--test-reporter=junit","--test-reporter-destination="+str(reports/"junit.xml"),
            *map(str, tests)], cwd=ROOT, env=env)
        cases=list(ET.parse(reports/"junit.xml").getroot().iter("testcase"));returncode=completed.returncode
        if not cases:raise RuntimeError('Node test reports are missing')
    skips=[c.attrib.get("name") for c in cases if c.find("skipped") is not None]
    failures=[c.attrib.get("name") for c in cases if c.find("failure") is not None or c.find("error") is not None]
    summary={"tests":len(cases),"unexpectedSkips":len(skips),"failures":failures,"skipped":skips,
             "result":"passed" if not (skips or failures or returncode) else "failed", "requiredSuites":required}
    (reports/"summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2)+"\n")
    if summary["result"]!="passed":raise RuntimeError("Frontend tests failed, were skipped or produced no reports")
    excluded=json.loads((ROOT/"scripts/ci/optional-tests.json").read_text())["excludedQaScripts"]
    summary.update(uiSuites=[],excludedSuites=excluded,result="running")
    try:
        for test in qa:
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
    if a.scope == "frontend":
        plan=json.loads(os.environ["CI_PLAN"]) if os.environ.get("CI_PLAN") else {}
        frontend([s for s in plan.get("requiredSuites",["all-frontend"]) if s in {"all-frontend","finance-frontend","frontend-assets"}])
    else: subprocess.run([sys.executable, "scripts/ci/java.py", a.scope], cwd=ROOT, check=True)
