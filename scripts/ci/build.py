#!/usr/bin/env python3
"""Build each changed component once and record registry + config identities."""
import argparse
import gzip
import json
import os
import re
import subprocess
import tempfile
import urllib.parse
from pathlib import Path
import release

ROOT=release.ROOT


def run(*args): return subprocess.check_output(args,cwd=ROOT).decode().strip()


def package_info(component):
    package=urllib.parse.quote(release.REPOSITORY.split('/')[1]+'/'+component,safe='')
    result=subprocess.run(['gh','api','users/'+release.REPOSITORY.split('/')[0]+'/packages/container/'+package],capture_output=True,text=True)
    value=json.loads(result.stdout or '{}')
    if result.returncode and str(value.get('status'))=='404':return None
    if result.returncode:raise RuntimeError('Cannot verify registry package permissions')
    return value


def initialize_package(component):
    # A new package can inherit public visibility. Its first push contains NO
    # application files, so visibility can be set before real image publishing.
    with tempfile.TemporaryDirectory(prefix='heyi-registry-bootstrap-') as directory:
        (Path(directory)/'Dockerfile').write_text('FROM scratch\nLABEL org.opencontainers.image.source="https://github.com/'+release.REPOSITORY+'"\nLABEL org.heyi.registry-bootstrap="true"\n')
        subprocess.run(['docker','buildx','build','--platform=linux/amd64','--provenance=false','--push',
            '--tag','ghcr.io/'+release.REPOSITORY.lower()+'/'+component+':registry-bootstrap',directory],check=True)


def ensure_private_packages():
    rejected=[]
    for component in ('app','frontend'):
        value=package_info(component)
        if value is None:
            initialize_package(component);value=package_info(component) or {}
        linked=(value.get('repository') or {}).get('full_name','')
        print(json.dumps({'package':component,'visibility':value.get('visibility'),'repository':linked}))
        if value.get('visibility')!='private' or linked.lower()!=release.REPOSITORY.lower():rejected.append(component)
    if rejected:raise RuntimeError('Set these packages to Private and link this repository before retrying: '+','.join(rejected)+'; no application image was built')


def image_metadata(repository,digest):
    raw=json.loads(run("docker","buildx","imagetools","inspect","--raw",repository+"@"+digest))
    if "manifests" in raw:
        selected=[m for m in raw["manifests"] if m.get("platform")=={"architecture":"amd64","os":"linux"}]
        if len(selected)!=1:raise ValueError("Expected one linux/amd64 platform")
        raw=json.loads(run("docker","buildx","imagetools","inspect","--raw",repository+"@"+selected[0]["digest"]))
    subprocess.run(["docker","pull","--platform=linux/amd64",repository+"@"+digest],check=True)
    info=json.loads(run("docker","image","inspect",repository+"@"+digest))[0]
    if info["Id"]!=raw["config"]["digest"]:raise ValueError("Registry/config mismatch")
    return {"repository":repository,"manifestDigest":digest,"configDigest":info["Id"],"platform":"linux/amd64",
            "compressedBytes":sum(i["size"] for i in raw["layers"])+raw["config"]["size"],"unpackedBytes":info["Size"]}


def prepare_reused_smoke_images(images,changed):
    # The privileged smoke process does not inherit the publisher's registry login.
    # Cache every reused private digest while that authenticated client is available.
    for name,image in images.items():
        if name in changed:continue
        actual=image_metadata(image['repository'],image['manifestDigest'])
        if any(actual[key]!=image[key] for key in ('repository','manifestDigest','configDigest','platform')):
            raise ValueError('Reused smoke image differs from the verified baseline')


def frontend_recipe_digest():
    import scope
    return scope.file_digest(['frontend/Dockerfile','frontend/package.py','frontend/nginx.conf','scripts/ci/build.py'])


def build(version,baseline=None,validation=None):
    if not release.VERSION.fullmatch(version):raise ValueError("Invalid version")
    import evidence
    import java
    import runtime_recipe
    if not isinstance(validation,evidence.VerifiedEvidence):raise ValueError('Candidate requires externally qualified CI evidence')
    compiled=java.load_compiled(ROOT/'target/compiled')
    source=release.inventory();ref=os.environ["GITHUB_REF"]
    match=re.fullmatch(r"refs/heads/(release|hotfix)/([0-9]+(?:\.[0-9]+){2,3})(?:-[A-Za-z0-9._-]+)?",ref)
    if not match or "v"+match[2]!=version:raise ValueError("Candidate branch/version mismatch")
    source["ref"]=ref
    if baseline:
        release.validate_manifest(baseline)
        if baseline["source"]["inputs"]["unsupported"]!=source["inputs"]["unsupported"]:raise ValueError("Unsupported runtime service changes")
    elif "unsupported" in release.changed("origin/main-tree"):
        raise ValueError("Initial adoption includes unsupported runtime changes")
    ensure_private_packages()
    output=ROOT/"target/release";output.mkdir(parents=True,exist_ok=True)
    images={};changed=[]
    recipes={'app':compiled['buildRecipeDigest'],'frontend':frontend_recipe_digest()}
    for component in ("app","frontend"):
        if baseline and baseline["source"]["inputs"][component]==source["inputs"][component] and baseline["images"][component].get("buildRecipeDigest")==recipes[component] and not (component=="app" and os.environ.get("HEYI_RUNTIME_BOOTSTRAP")=="true"):
            images[component]=baseline["images"][component];continue
        repository=f"ghcr.io/{release.REPOSITORY.lower()}/{component}"
        tag=repository+":git-"+source["commit"]
        if subprocess.run(['docker','buildx','imagetools','inspect',tag],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
            raise RuntimeError('Candidate image already exists; do not overwrite it; use a new reviewed commit')
        metadata=output/(component+"-build.json")
        args=["docker","buildx","build","--platform=linux/amd64","--provenance=false","--push",
              "--tag",tag,"--metadata-file",str(metadata),
              "--label","org.opencontainers.image.source=https://github.com/"+release.REPOSITORY,
              "--label","org.opencontainers.image.revision="+source["commit"],
              "--label","org.opencontainers.image.version="+version,
              "--file","Dockerfile" if component=="app" else "frontend/Dockerfile",
              "." if component=="app" else "frontend"]
        extra=['--cache-from','type=registry,ref='+repository+':buildcache',
               '--cache-to','type=registry,ref='+repository+':buildcache,mode=max,image-manifest=true,oci-mediatypes=true']
        runtime=None
        if component=='app':
            extra+=['--build-arg','JAR_SOURCE=compiled']
            if os.environ.get('HEYI_RUNTIME_BOOTSTRAP')=='true':
                if validation.get('profile')!='full':raise ValueError('Runtime qualification requires full CI')
                runtime,recipe_digest=runtime_recipe.bootstrap_runtime(source)
                extra+=['--build-arg','RUNTIME_BASE='+runtime]
            elif os.environ.get('HEYI_RUNTIME_CACHE_ENABLED')=='true':
                runtime,recipe_digest=runtime_recipe.qualified_runtime(baseline)
                extra+=['--build-arg','RUNTIME_BASE='+runtime]
        args[-1:-1]=extra
        subprocess.run(args,cwd=ROOT,check=True)
        digest=json.loads(metadata.read_text())["containerimage.digest"]
        info=image_metadata(repository,digest);info.update(sourceCommit=source["commit"],sourceRef=ref,buildRecipeDigest=recipes[component])
        if component=='app':
            info.update(compiledArtifactSha256=compiled['compiledArtifactSha256'],buildRecipeDigest=compiled['buildRecipeDigest'])
            if runtime:info.update(runtimeBaseDigest=runtime.rsplit('@',1)[1],runtimeRecipeDigest=recipe_digest)
        visibility=package_info(component) or {}
        if visibility.get('visibility')!='private' or (visibility.get('repository') or {}).get('full_name','').lower()!=release.REPOSITORY.lower():
            raise RuntimeError('Release images must be private and linked to this repository')
        if component=="frontend":
            container="heyi-ci-assets-"+source["commit"][:12]
            subprocess.run(["docker","create","--name",container,"--entrypoint","true",tag],check=True,stdout=subprocess.DEVNULL)
            try:subprocess.run(["docker","cp",container+":/usr/share/nginx/html/asset-manifest.json",str(output/"asset-manifest.json")],check=True)
            finally:subprocess.run(["docker","rm","-v",container],check=True,stdout=subprocess.DEVNULL)
            assets=(output/"asset-manifest.json").read_bytes();public=json.loads(assets)
            info.update(assetManifestSha256=release.sha(assets),smokeAssets={p:public[p]["sha256"] for p in ["index.html","app.js","finance-workspace.js","finance-charges.js"]})
        images[component]=info;changed.append(component)
    prepare_reused_smoke_images(images,changed)
    source_tar=subprocess.check_output(["git","archive","--format=tar",source["commit"]],cwd=ROOT)
    source_archive=gzip.compress(source_tar,mtime=0)
    (output/"source.tar.gz").write_bytes(source_archive)
    manifest={"schemaVersion":1,"repository":release.REPOSITORY,"version":version,"source":source,
              "images":images,"sourceArchiveSha256":release.sha(source_archive),"sourceArchiveBytes":len(source_archive),"sourceUnpackedBytes":len(source_tar),
              "validation":{"result":"passed","unexpectedSkips":0,"ciRunId":validation["runId"],
                            "workflow":".github/workflows/ci.yml","containerSmoke":"pending",
                            **({"reusedValidationFrom":baseline['version']} if not changed and baseline else {})}}
    if os.environ.get('HEYI_MANIFEST_V2_ENABLED')=='true':
        manifest['schemaVersion']=2
        manifest['validation'].update({k:validation[k] for k in ['profile','policyDigest','toolchainDigest','suiteInputDigests','requiredSuites']})
        manifest['validation']['evidence']=[{k:validation[k] for k in ['runId','runAttempt','artifactId','artifactDigest','workflow','head','tree']}]
    release.validate_manifest(manifest, require_smoke=False)
    (output/"release.json").write_text(json.dumps(manifest,sort_keys=True,indent=2)+"\n")
    with open(os.environ["GITHUB_OUTPUT"],"a") as f:
        f.write('runtime_digest='+images['app'].get('runtimeBaseDigest','')+'\n')
        for name,image in images.items():
            f.write(f'{name}_digest={image["manifestDigest"]}\n{name}_changed={str(name in changed).lower()}\n')
    return manifest


if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("version");p.add_argument("--baseline",type=Path)
    a=p.parse_args();build(a.version,json.loads(a.baseline.read_text()) if a.baseline else None)
