#!/usr/bin/env python3
import argparse
import json
import os
import re
import subprocess
from pathlib import Path
import build
import release
import evidence
import scope
import java
import io
import zipfile


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


def qualify(expected_head,run_id,attempt,download=False,baseline=None):
    head=release.run('git','rev-parse','HEAD')
    if not re.fullmatch('[a-f0-9]{40}',expected_head) or expected_head!=head:raise ValueError('Candidate expected head differs')
    ref=os.environ.get('GITHUB_REF','')
    if not re.fullmatch(r'refs/heads/(release|hotfix)/[A-Za-z0-9._-]+',ref):raise ValueError('Untrusted candidate ref')
    remote=release.run('git','ls-remote','origin',ref).split()
    if len(remote)!=2 or remote[0]!=head:raise ValueError('Candidate branch moved')
    paths=release.run('git','diff','--name-only','origin/main-tree','HEAD').splitlines()
    context=scope.full_baseline_context()
    if os.environ.get('HEYI_MANIFEST_V2_ENABLED')=='true':
        if baseline is None:
            versions=[tag for tag in release.run('git','tag','--points-at','origin/main-tree').splitlines() if release.VERSION.fullmatch(tag)]
            if len(versions)!=1:raise ValueError('Production requires a unique formal baseline')
            baseline=baseline_for(versions[0])
        context['firstV2']=baseline.get('schemaVersion')!=2
    context.update(forceFull=os.environ.get('HEYI_FAST_CI_ENABLED')!='true',firstV2=context.get('firstV2',False) or os.environ.get('HEYI_V2_BOOTSTRAPPED')!='true')
    plan=scope.classify_changed_paths(paths,context)
    if plan['unsupported']:raise ValueError('Unsupported runtime component changes')
    request=scope.inputs(plan['requiredSuites'])
    verified=evidence.fetch_verified(run_id,attempt,request)
    if os.environ.get('HEYI_MANIFEST_V2_ENABLED')!='true' and verified.get('profile')!='full':raise ValueError('v1 requires qualified full CI')
    if download:download_compiled(verified)
    return verified


def check_compiled_proof(verified,metadata):
    proof=verified.get('build',{})
    if any(not re.fullmatch('[a-f0-9]{64}',proof.get(k,'')) or proof[k]!=metadata.get(k) for k in ['archiveSha256','compiledArtifactSha256','buildRecipeDigest']):
        raise ValueError('Compiled output not covered by validation')


def download_compiled(verified):
    prefix='repos/'+release.REPOSITORY
    artifacts=evidence.api(f"{prefix}/actions/runs/{verified['runId']}/artifacts?per_page=100")
    matches=[a for a in artifacts['artifacts'] if a['name']==f"compiled-{verified['runAttempt']}"]
    if len(matches)!=1 or matches[0].get('expired') is not False:raise ValueError('Missing compiled artifact')
    artifact=matches[0]
    if artifact.get('size_in_bytes',0)>512*1024**2:raise ValueError('Oversized compiled artifact')
    if str(artifact.get('workflow_run',{}).get('id'))!=str(verified['runId']) or artifact.get('workflow_run',{}).get('head_sha')!=verified['head']:
        raise ValueError('Compiled artifact source differs')
    raw=evidence.api(f"{prefix}/actions/artifacts/{artifact['id']}/zip",raw=True)
    if artifact.get('digest')!='sha256:'+release.sha(raw):raise ValueError('Compiled API artifact digest differs')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        if set(archive.namelist())!={'build.json','compiled.tar.gz'} or len(archive.namelist())!=2 or sum(i.file_size for i in archive.infolist())>512*1024**2:
            raise ValueError('Invalid compiled artifact members')
        metadata=json.loads(archive.read('build.json'));check_compiled_proof(verified,metadata)
        compiled=archive.read('compiled.tar.gz')
        if release.sha(compiled)!=metadata['archiveSha256']:raise ValueError('Compiled archive digest differs')
        target=release.ROOT/'target/compiled';target.mkdir(parents=True,exist_ok=True)
        (target/'build.json').write_text(json.dumps(metadata));(target/'compiled.tar.gz').write_bytes(compiled)
    java.load_compiled(target)
    return metadata


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
    verified=qualify(os.environ['EXPECTED_HEAD'],os.environ['VALIDATION_RUN'],os.environ['VALIDATION_ATTEMPT'],download=True,baseline=baseline)
    build.build("v"+match[1],baseline,verified)


if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("action",choices=("build","publish","qualify"));args=p.parse_args()
    if args.action=='qualify':
        value=qualify(os.environ['EXPECTED_HEAD'],os.environ['VALIDATION_RUN'],os.environ['VALIDATION_ATTEMPT'])
        print(json.dumps({'qualified':True,'runId':value['runId'],'runAttempt':value['runAttempt'],'tree':value['tree']}))
    else:main(args.action)
