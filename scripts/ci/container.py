#!/usr/bin/env python3
"""Build both complete images locally and exercise the production smoke contract.

Only disposable, uniquely labelled images/containers are created. No registry
credentials, production settings, source business data or external egress.
"""
import json
import secrets
import subprocess
import tempfile
from pathlib import Path
import release
import smoke


def runtime_probe(image):
    """Exercise the immutable image without network, credentials or business volumes."""
    script="""set -eu
    test -s /app/app.jar
    java -version
    test -s /usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc
    fc-match 'DejaVu Sans' | head -1
    ffmpeg -hide_banner -loglevel error -f lavfi -i 'color=c=blue:s=160x120:d=0.1' -vf 'drawtext=fontfile=/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc:text=验收:fontcolor=white:x=10:y=10' -frames:v 1 -threads 1 /tmp/probe.png
    test -s /tmp/probe.png
    """
    subprocess.run(['docker','run','--rm','--platform','linux/amd64','--network','none','--read-only','--tmpfs','/tmp:rw,size=32m',
                    '--label','org.heyi.owner=ci-runtime-probe','--entrypoint','sh',image,'-c',script],check=True,timeout=60)


def main(legacy_gate=False, compiled=False):
    owner="heyi-ci-full-"+secrets.token_hex(8)
    images={name:owner+"-"+name for name in ("app","frontend")}
    container=owner+"-assets"
    output=release.ROOT/"target/ci-reports/container"
    output.mkdir(parents=True,exist_ok=True)
    result={"result":"failed"}
    try:
        for name,tag in images.items():
            extra=["--build-arg","JAR_SOURCE=compiled"] if name=="app" and compiled else []
            subprocess.run(["docker","buildx","build",*extra,"--platform=linux/amd64","--load","--provenance=false",
                "--label","org.heyi.owner="+owner,"--tag",tag,"--file",
                "Dockerfile" if name=="app" else "frontend/Dockerfile",
                "." if name=="app" else "frontend"],cwd=release.ROOT,check=True)
        runtime_probe(images['app'])
        with tempfile.TemporaryDirectory(prefix=owner) as temporary:
            root=Path(temporary)
            subprocess.run(["docker","create","--label","org.heyi.owner="+owner,"--name",container,images["frontend"]],check=True,stdout=subprocess.DEVNULL)
            subprocess.run(["docker","cp",container+":/usr/share/nginx/html/asset-manifest.json",str(root/"assets.json")],check=True)
            assets=json.loads((root/"assets.json").read_text())
            manifest=root/"release.json"
            manifest.write_text(json.dumps({"images":{"frontend":{"smokeAssets":{p:assets[p]["sha256"] for p in
                ["index.html","app.js","finance-workspace.js","finance-charges.js"]}}},"validation":{}}))
            smoke.smoke(manifest,images,legacy_gate)
            result={"result":"passed","containerSmoke":"passed","runtimeMediaFonts":"passed","noProductionAccess":True,'legacyIngressFixture':'passed' if legacy_gate else 'not_run'}
    finally:
        (output/"summary.json").write_text(json.dumps(result,indent=2)+"\n")
        # Never prune a shared Docker daemon or remove an image another task owns.
        for name in [container,*images.values()]:
            inspected=subprocess.run(["docker","inspect",name],capture_output=True,text=True)
            if inspected.returncode:continue
            info=json.loads(inspected.stdout)[0]
            if info["Config"].get("Labels",{}).get("org.heyi.owner")!=owner:continue
            command=["docker","rm","-v",name] if name==container else ["docker","image","rm",name]
            subprocess.run(command,check=True,stdout=subprocess.DEVNULL)


if __name__=="__main__":
    import argparse
    p=argparse.ArgumentParser();p.add_argument('--legacy-gate',action='store_true');p.add_argument('--compiled',action='store_true');p.add_argument('--probe',type=Path)
    args=p.parse_args()
    if args.probe:
        app=json.loads(args.probe.read_text())['images']['app'];runtime_probe(app['repository']+'@'+app['manifestDigest'])
    else:main(args.legacy_gate,args.compiled)
