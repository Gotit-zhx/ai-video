#!/usr/bin/env python3
"""Canonical runtime identity and fail-closed qualification of immutable bases."""
import hashlib
import json
import re
import os
import subprocess
from pathlib import Path
import release


def recipe_digest(recipe):
    required={'jre','platform','bomSha256'}
    allowed=required|{'dockerfileSha256','toolchainSha256'}
    if not required.issubset(recipe) or set(recipe)-allowed:raise ValueError('Invalid runtime recipe keys')
    if recipe['platform']!='linux/amd64' or not re.fullmatch('sha256:[a-f0-9]{64}',recipe['jre']):
        raise ValueError('Runtime needs immutable amd64 JRE')
    if any(not re.fullmatch('[a-f0-9]{64}',v) for k,v in recipe.items() if k not in {'jre','platform'}):
        raise ValueError('Invalid runtime checksum')
    return hashlib.sha256(json.dumps(recipe,sort_keys=True,separators=(',',':')).encode()).hexdigest()


def configured_recipe():
    value=json.loads((release.ROOT/'src/main/docker/runtime.json').read_text())
    if not value.get('bomSha256'):raise ValueError('Runtime BOM has not been qualified; retain full build')
    lock=(release.ROOT/'src/main/docker/runtime-packages.lock').read_bytes()
    if release.sha(lock)!=value['bomSha256']:raise ValueError('Package lock differs from accepted BOM')
    recipe={k:value[k] for k in ('jre','platform','bomSha256')}
    recipe['dockerfileSha256']=release.sha((release.ROOT/'src/main/docker/runtime.Dockerfile').read_bytes())
    recipe['toolchainSha256']=release.sha((release.ROOT/'scripts/ci/runtime.json').read_bytes())
    return recipe


def qualified_runtime(baseline):
    """Caller supplies a baseline already verified with GitHub attestation."""
    recipe=configured_recipe();digest=recipe_digest(recipe)
    app=(baseline or {}).get('images',{}).get('app',{})
    if app.get('runtimeRecipeDigest')!=digest or not release.DIGEST.fullmatch(app.get('runtimeBaseDigest','')):
        raise ValueError('No qualified runtime for the exact recipe; bootstrap separately')
    return app['repository']+'@'+app['runtimeBaseDigest'],digest


def bootstrap_runtime(source):
    """Create one immutable runtime; candidate smoke/attestation qualifies its use."""
    import os
    import subprocess
    recipe=configured_recipe();digest=recipe_digest(recipe)
    repository='ghcr.io/'+release.REPOSITORY.lower()+'/app'
    tag=repository+':runtime-git-'+source['commit']
    if subprocess.run(['docker','buildx','imagetools','inspect',tag],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
        raise ValueError('Runtime tag exists; immutable runtime cannot be replaced')
    output=release.ROOT/'target/release/runtime-build.json'
    subprocess.run(['docker','buildx','build','--platform=linux/amd64','--provenance=false','--push',
                    '--tag',tag,'--metadata-file',str(output),'--label','org.opencontainers.image.source=https://github.com/'+release.REPOSITORY,
                    '--label','org.opencontainers.image.revision='+source['commit'],
                    '--label','org.heyi.runtime-recipe='+digest,
                    '--cache-from','type=registry,ref='+repository+':runtime-buildcache',
                    '--cache-to','type=registry,ref='+repository+':runtime-buildcache,mode=max,image-manifest=true,oci-mediatypes=true',
                    '--file','src/main/docker/runtime.Dockerfile','.'],cwd=release.ROOT,check=True)
    image_digest=json.loads(output.read_text())['containerimage.digest']
    if not release.DIGEST.fullmatch(image_digest):raise ValueError('Invalid runtime output digest')
    return repository+'@'+image_digest,digest


def ci_runtime_allowed(environment):
    return (environment.get('GITHUB_REPOSITORY')==release.REPOSITORY
            and environment.get('GITHUB_EVENT_NAME') in {'push','workflow_dispatch'}
            and bool(re.fullmatch(r'refs/heads/(develop|(?:release|hotfix)/[A-Za-z0-9._-]+)',environment.get('GITHUB_REF',''))))


def prepare_ci_runtime(output):
    """Trusted read-only package job; unqualified or unavailable bases use pinned cold CI."""
    if not ci_runtime_allowed(os.environ):raise ValueError('Runtime preparation is unavailable to untrusted jobs')
    import candidate
    try:
        versions=[tag for tag in release.run('git','tag','--points-at','origin/main-tree').splitlines() if release.VERSION.fullmatch(tag)]
        if len(versions)!=1:raise ValueError('Missing unique formal baseline')
        baseline=candidate.baseline_for(versions[0])
        reference,digest=qualified_runtime(baseline)
        subprocess.run(['docker','pull','--platform=linux/amd64',reference],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=180)
        info=json.loads(subprocess.check_output(['docker','image','inspect',reference]))[0]
        if info['Config'].get('Labels',{}).get('org.heyi.runtime-recipe')!=digest:raise ValueError('Runtime recipe label differs')
        # Only dependency-only runtime images may enter Actions artifacts.
        subprocess.run(['docker','run','--rm','--platform=linux/amd64','--network','none','--read-only','--entrypoint','sh',reference,
                        '-c','test ! -e /app/app.jar'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=30)
    except (ValueError,KeyError,subprocess.CalledProcessError,subprocess.TimeoutExpired):
        print(json.dumps({'runtimePreparation':'cold_fallback','reason':'qualified_runtime_unavailable'}));return None
    local='heyi-ci-qualified-runtime:'+digest
    output.mkdir(parents=True,exist_ok=True)
    subprocess.run(['docker','tag',reference,local],check=True)
    archive=output/'runtime.tar'
    subprocess.run(['docker','image','save','--output',str(archive),local],check=True,timeout=180)
    return dict(runId=os.environ['GITHUB_RUN_ID'],runAttempt=os.environ['GITHUB_RUN_ATTEMPT'],head=os.environ['GITHUB_SHA'],
                recipeDigest=digest,runtimeRef=reference,configDigest=info['Id'],archiveSha256=release.sha(archive.read_bytes()))


def load_ci_runtime(folder,proof):
    """Proof comes from needs.prepare-runtime.outputs, never from the downloaded payload."""
    if any(str(proof.get(k))!=os.environ.get(e) for k,e in [('runId','GITHUB_RUN_ID'),('runAttempt','GITHUB_RUN_ATTEMPT'),('head','GITHUB_SHA')]):
        raise ValueError('Runtime artifact belongs to another run, attempt or head')
    digest=recipe_digest(configured_recipe())
    if (proof.get('recipeDigest')!=digest or not release.DIGEST.fullmatch(proof.get('configDigest',''))
        or not re.fullmatch(r'ghcr.io/gotit-zhx/ai-video/app@sha256:[a-f0-9]{64}',proof.get('runtimeRef',''))):
        raise ValueError('Runtime preparation identity differs')
    archive=folder/'runtime.tar'
    if not archive.is_file() or archive.is_symlink() or archive.stat().st_size>2*1024**3 or release.sha(archive.read_bytes())!=proof.get('archiveSha256'):
        raise ValueError('Runtime artifact checksum differs')
    subprocess.run(['docker','image','load','--input',str(archive)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=180)
    info=json.loads(subprocess.check_output(['docker','image','inspect',proof['configDigest']]))[0]
    if info['Id']!=proof['configDigest'] or info['Config'].get('Labels',{}).get('org.heyi.runtime-recipe')!=digest:
        raise ValueError('Loaded runtime identity differs')
    local='heyi-ci-qualified-runtime:'+digest
    subprocess.run(['docker','tag',proof['configDigest'],local],check=True)
    return local


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['prepare-ci']);args=parser.parse_args()
    proof=prepare_ci_runtime(release.ROOT/'target/ci-runtime')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'],'a') as output:
            output.write('proof='+json.dumps(proof or {},separators=(',',':'))+'\n')
            output.write('available='+str(bool(proof)).lower()+'\n')
