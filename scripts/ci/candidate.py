#!/usr/bin/env python3
import argparse
import json
import os
import re
import subprocess
from pathlib import Path
import build
import release


def main(action):
    sha=release.run("git","rev-parse","HEAD");output=release.ROOT/"target/release"
    if action=="publish":
        value=release.validate_manifest(json.loads((output/"release.json").read_text()))
        if value["validation"]["containerSmoke"]!="passed":raise ValueError("Container smoke did not pass")
        tag="candidate-"+sha
        result=subprocess.run(["gh","release","view",tag,"--repo",release.REPOSITORY],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if result.returncode==0:raise RuntimeError("Candidate already published; do not overwrite immutable assets")
        notes=output/"notes.md";notes.write_text("Verified candidate for "+value["version"]+". Source tree: `"+value["source"]["tree"]+"`. Production uses these exact image digests.\n")
        subprocess.run(["gh","release","create",tag,"--repo",release.REPOSITORY,"--target",sha,"--prerelease","--latest=false",
                        "--title",value["version"]+" candidate","--notes-file",str(notes),str(output/"release.json"),str(output/"source.tar.gz")],check=True)
        return
    branch=os.environ["GITHUB_REF"].removeprefix("refs/heads/")
    match=re.fullmatch(r"(?:release|hotfix)/([0-9]+(?:\.[0-9]+){2,3})(?:-[A-Za-z0-9._-]+)?",branch)
    if not match:raise ValueError("Only versioned release/hotfix branches may publish")
    # Prevent reruns from rebuilding/overwriting a candidate for the same SHA.
    if subprocess.run(["gh","release","view","candidate-"+sha,"--repo",release.REPOSITORY],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
        raise RuntimeError("Candidate already exists; promote it or create a new reviewed commit")
    versions=[tag for tag in release.run('git','tag','--points-at','origin/main-tree').splitlines() if release.VERSION.fullmatch(tag)]
    if len(versions)!=1:raise ValueError('Production HEAD must have one formal version before preparing its successor')
    baseline=None
    if versions:
        tag=versions[0]
        if release.VERSION.fullmatch(tag):
            folder=release.ROOT/"target/baseline";folder.mkdir(parents=True,exist_ok=True)
            result=subprocess.run(["gh","release","download",tag,"--repo",release.REPOSITORY,"--pattern","release.json","--dir",str(folder)],capture_output=True)
            if result.returncode==0:
                baseline=json.loads((folder/"release.json").read_text());release.validate_manifest(baseline)
                subprocess.run(["gh","attestation","verify",str(folder/"release.json"),"--repo",release.REPOSITORY,
                    "--signer-workflow",release.REPOSITORY+"/.github/workflows/candidate.yml","--source-digest",baseline["source"]["commit"],"--deny-self-hosted-runners"],check=True)
            elif os.environ.get('INITIAL_ADOPTION')!='true':
                raise ValueError('Finish publishing the current production receipt before building its successor')
    build.build("v"+match[1],baseline)


if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("action",choices=("build","publish"));main(p.parse_args().action)
