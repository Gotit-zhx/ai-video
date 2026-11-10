#!/usr/bin/env python3
"""Canonical runtime identity and fail-closed qualification of immutable bases."""
import hashlib
import json
import re
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
