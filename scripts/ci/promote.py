#!/usr/bin/env python3
"""Promote the tested tree and publish the server receipt without new commits."""
import argparse
import json
import re
import subprocess
import tempfile
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
        if not re.fullmatch(r'[a-f0-9]{32}',value['id']):raise ValueError('Invalid operation identity')
        notes=root/"notes.md";notes.write_text("Deployed and verified by heyi-ops. Operation: `"+value["id"]+"`. See the attached immutable manifest and acceptance receipt.\n")
        existing=subprocess.run(["gh","release","view",version,"--repo",release.REPOSITORY,'--json','assets'],capture_output=True)
        if existing.returncode==0:
            with tempfile.TemporaryDirectory(prefix='heyi-original-receipt-') as temporary:
                subprocess.run(['gh','release','download',version,'--repo',release.REPOSITORY,'--pattern','release.json','--dir',temporary],check=True)
                if (Path(temporary)/'release.json').read_bytes()!=manifests[0].read_bytes():raise RuntimeError('Original formal manifest differs; do not overwrite it')
                name='receipt-'+value['id']+'.json';new=root/name;new.write_bytes(receipts[0].read_bytes())
                assets={a['name'] for a in json.loads(existing.stdout)['assets']}
                if name in assets:
                    subprocess.run(['gh','release','download',version,'--repo',release.REPOSITORY,'--pattern',name,'--dir',temporary],check=True)
                    if (Path(temporary)/name).read_bytes()!=new.read_bytes():raise RuntimeError('Receipt identity already has different content')
                else:subprocess.run(['gh','release','upload',version,'--repo',release.REPOSITORY,str(new)],check=True)
            return
        subprocess.run(["gh","release","create",version,"--repo",release.REPOSITORY,"--verify-tag","--latest=true","--title",version,
                        "--notes-file",str(notes),str(manifests[0]),str(receipts[0])],check=True)


if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("action",choices=("validate","publish"));p.add_argument("version");a=p.parse_args();main(a.action,a.version)
