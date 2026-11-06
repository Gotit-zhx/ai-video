#!/usr/bin/env python3
"""Build each changed component once and record registry + config identities."""
import argparse
import gzip
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path
import release

ROOT=release.ROOT


def run(*args): return subprocess.check_output(args,cwd=ROOT).decode().strip()


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


def build(version,baseline=None):
    if not release.VERSION.fullmatch(version):raise ValueError("Invalid version")
    source=release.inventory();ref=os.environ["GITHUB_REF"]
    match=re.fullmatch(r"refs/heads/(release|hotfix)/([0-9]+(?:\.[0-9]+){2,3})(?:-[A-Za-z0-9._-]+)?",ref)
    if not match or "v"+match[2]!=version:raise ValueError("Candidate branch/version mismatch")
    source["ref"]=ref
    if baseline:
        release.validate_manifest(baseline)
        if baseline["source"]["inputs"]["unsupported"]!=source["inputs"]["unsupported"]:raise ValueError("Unsupported runtime service changes")
    elif "unsupported" in release.changed("origin/main-tree"):
        raise ValueError("Initial adoption includes unsupported runtime changes")
    output=ROOT/"target/release";output.mkdir(parents=True,exist_ok=True)
    images={};changed=[]
    for component in ("app","frontend"):
        if baseline and baseline["source"]["inputs"][component]==source["inputs"][component]:
            images[component]=baseline["images"][component];continue
        repository=f"ghcr.io/{release.REPOSITORY.lower()}/{component}"
        tag=repository+":git-"+source["commit"]
        metadata=output/(component+"-build.json")
        args=["docker","buildx","build","--platform=linux/amd64","--provenance=false","--push",
              "--tag",tag,"--metadata-file",str(metadata),
              "--label","org.opencontainers.image.source=https://github.com/"+release.REPOSITORY,
              "--label","org.opencontainers.image.revision="+source["commit"],
              "--label","org.opencontainers.image.version="+version,
              "--file","Dockerfile" if component=="app" else "frontend/Dockerfile",
              "." if component=="app" else "frontend"]
        subprocess.run(args,cwd=ROOT,check=True)
        digest=json.loads(metadata.read_text())["containerimage.digest"]
        info=image_metadata(repository,digest);info.update(sourceCommit=source["commit"],sourceRef=ref)
        if component=="frontend":
            container="heyi-ci-assets-"+source["commit"][:12]
            subprocess.run(["docker","create","--name",container,"--entrypoint","true",tag],check=True,stdout=subprocess.DEVNULL)
            try:subprocess.run(["docker","cp",container+":/usr/share/nginx/html/asset-manifest.json",str(output/"asset-manifest.json")],check=True)
            finally:subprocess.run(["docker","rm","-v",container],check=True,stdout=subprocess.DEVNULL)
            assets=(output/"asset-manifest.json").read_bytes();public=json.loads(assets)
            info.update(assetManifestSha256=release.sha(assets),smokeAssets={p:public[p]["sha256"] for p in ["index.html","app.js","finance-workspace.js","finance-charges.js"]})
        images[component]=info;changed.append(component)
    source_archive=gzip.compress(subprocess.check_output(["git","archive","--format=tar",source["commit"]],cwd=ROOT),mtime=0)
    (output/"source.tar.gz").write_bytes(source_archive)
    manifest={"schemaVersion":1,"repository":release.REPOSITORY,"version":version,"source":source,
              "images":images,"sourceArchiveSha256":release.sha(source_archive),
              "validation":{"result":"passed","unexpectedSkips":0,"ciRunId":os.environ["GITHUB_RUN_ID"],
                            "workflow":".github/workflows/ci.yml","containerSmoke":"pending"}}
    release.validate_manifest(manifest, require_smoke=False)
    (output/"release.json").write_text(json.dumps(manifest,sort_keys=True,indent=2)+"\n")
    with open(os.environ["GITHUB_OUTPUT"],"a") as f:
        for name,image in images.items():
            f.write(f'{name}_digest={image["manifestDigest"]}\n{name}_changed={str(name in changed).lower()}\n')
    return manifest


if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("version");p.add_argument("--baseline",type=Path)
    a=p.parse_args();build(a.version,json.loads(a.baseline.read_text()) if a.baseline else None)
