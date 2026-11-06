#!/usr/bin/env python3
"""Promote the tested tree and publish the server receipt without new commits."""
import argparse
import json
import subprocess
from pathlib import Path
import release


def main(action,version):
    if not release.VERSION.fullmatch(version):raise ValueError("Invalid formal version")
    if action=="validate":
        parents=release.run("git","show","-s","--format=%P",version+"^{commit}").split()
        if len(parents)!=2:raise ValueError("Not a no-ff release merge")
        path=release.ROOT/"target/promotion";path.mkdir(parents=True,exist_ok=True)
        subprocess.run(["gh","release","download","candidate-"+parents[1],"--repo",release.REPOSITORY,"--pattern","release.json","--dir",str(path)],check=True)
        manifest=path/"release.json";value=json.loads(manifest.read_text())
        release.check_tag(version,value)
        subprocess.run(["gh","attestation","verify",str(manifest),"--repo",release.REPOSITORY,
            "--signer-workflow",release.REPOSITORY+"/.github/workflows/candidate.yml","--source-digest",parents[1],
            "--source-ref",value["source"]["ref"],"--deny-self-hosted-runners"],check=True)
        if value["validation"]["containerSmoke"]!="passed":raise ValueError("Container smoke evidence missing")
    else:
        root=release.ROOT/"target/receipt"
        manifests=list(root.rglob("release.json"));receipts=list(root.rglob("receipt.json"))
        if len(manifests)!=1 or len(receipts)!=1:raise ValueError("Missing/ambiguous deployment receipt")
        value=json.loads(receipts[0].read_text());manifest=release.validate_manifest(json.loads(manifests[0].read_text()))
        if value["phase"]!="succeeded" or value["version"]!=version or manifest["version"]!=version:raise ValueError("Receipt did not accept this version")
        notes=root/"notes.md";notes.write_text("Deployed and verified by heyi-ops. Operation: `"+value["id"]+"`. See the attached immutable manifest and acceptance receipt.\n")
        existing=subprocess.run(["gh","release","view",version,"--repo",release.REPOSITORY],capture_output=True)
        if existing.returncode==0:raise RuntimeError("Formal Release already exists; retain its original acceptance evidence")
        subprocess.run(["gh","release","create",version,"--repo",release.REPOSITORY,"--verify-tag","--title",version,
                        "--notes-file",str(notes),str(manifests[0]),str(receipts[0])],check=True)


if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("action",choices=("validate","publish"));p.add_argument("version");a=p.parse_args();main(a.action,a.version)
