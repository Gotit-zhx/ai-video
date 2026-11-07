#!/usr/bin/env python3
import argparse
import json
import os
import re
import subprocess
from pathlib import Path
import build
import release


def baseline_for(tag):
    folder=release.ROOT/'target/baseline';folder.mkdir(parents=True,exist_ok=True)
    result=subprocess.run(['gh','release','download',tag,'--repo',release.REPOSITORY,'--pattern','release.json','--dir',str(folder)],capture_output=True)
    if result.returncode:
        if os.environ.get('INITIAL_ADOPTION')!='true':
            raise ValueError('Finish publishing the current production receipt before building its successor')
        parents=release.run('git','show','-s','--format=%P',tag+'^{commit}').split()
        if len(parents)!=2:raise ValueError('Initial baseline is not a formal candidate merge')
        folder=folder/'qualified-candidate';folder.mkdir()
        subprocess.run(['gh','release','download','candidate-'+parents[1],'--repo',release.REPOSITORY,
            '--pattern','release.json','--dir',str(folder)],check=True,capture_output=True)
    manifest=folder/'release.json';baseline=release.validate_manifest(json.loads(manifest.read_text()))
    # Even before the first host acceptance, reuse only the exact candidate
    # bound to the current formal tag, with its original attested image inputs.
    release.check_tag(tag,baseline)
    subprocess.run(['gh','attestation','verify',str(manifest),'--repo',release.REPOSITORY,
        '--signer-workflow',release.REPOSITORY+'/.github/workflows/candidate.yml',
        '--source-digest',baseline['source']['commit'],'--source-ref',baseline['source']['ref'],
        '--deny-self-hosted-runners'],check=True)
    return baseline


def main(action):
    sha=release.run("git","rev-parse","HEAD");output=release.ROOT/"target/release"
    if action=="publish":
        value=release.validate_manifest(json.loads((output/"release.json").read_text()))
        if value["validation"]["containerSmoke"]!="passed":raise ValueError("Container smoke did not pass")
        import sys,tempfile
        sys.path.insert(0,str(release.ROOT/'ops/heyi-ops'))
        from heyi_ops.oci import export_archive,validate_archive
        for name,image in value['images'].items():
            if image['sourceCommit']!=value['source']['commit']:continue
            with tempfile.TemporaryDirectory(prefix='heyi-oci-validation-') as temporary:
                archive=Path(temporary)/'image.tar';export_archive(image,archive,os.environ['GH_TOKEN'])
                validate_archive(archive,image)
            print(json.dumps({'component':name,'ociTransport':'verified'}))
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
            baseline=baseline_for(tag)
    build.build("v"+match[1],baseline)


if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("action",choices=("build","publish"));main(p.parse_args().action)
