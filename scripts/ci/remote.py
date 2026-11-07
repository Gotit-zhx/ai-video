#!/usr/bin/env python3
"""Pinned-host SSH transport. Secrets enter files/stdin, never command output."""
import json
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path


class Remote:
    def __enter__(self):
        self.temp=tempfile.TemporaryDirectory(prefix="heyi-ssh-")
        root=Path(self.temp.name);self.key=root/"key";self.hosts=root/"known_hosts"
        self.key.write_text(os.environ["HEYI_SSH_KEY"].strip()+"\n");self.key.chmod(0o600)
        self.hosts.write_text(os.environ["HEYI_KNOWN_HOSTS"].strip()+"\n");self.hosts.chmod(0o600)
        host=os.environ["HEYI_HOST"];user=os.environ["HEYI_USER"]
        if not re.fullmatch(r"[A-Za-z0-9.-]+",host) or user not in {"heyi-deploy","heyi-monitor"}:raise ValueError("Invalid SSH destination")
        self.destination=user+"@"+host
        return self
    def __exit__(self,*_):self.temp.cleanup()
    def call(self,*args,payload=None,assets=None,timeout=900):
        if not all(re.fullmatch(r"[A-Za-z0-9._-]+",a) for a in args):raise ValueError("Invalid command argument")
        data=json.dumps(payload).encode() if payload else b""
        if assets is not None:
            if args[0]!='stage' or set(assets)!={'release.json','source.tar.gz'} or set(payload)!={'pullToken'}:
                raise ValueError('Assets are only accepted by stage')
            sizes={name:len(value) for name,value in assets.items()}
            if not 0<sizes['release.json']<=1024*1024 or not 0<sizes['source.tar.gz']<=512*1024**2:
                raise ValueError('Invalid upload size bounds')
            data=json.dumps({**payload,'assetSizes':sizes}).encode()+b'\n'+assets['release.json']+assets['source.tar.gz']
        result=subprocess.run(["ssh","-T","-i",str(self.key),"-o","BatchMode=yes","-o","IdentitiesOnly=yes",
            "-o","StrictHostKeyChecking=yes","-o","UserKnownHostsFile="+str(self.hosts),"-o","ConnectTimeout=15",
            "-o","ServerAliveInterval=15","-o","ServerAliveCountMax=3",self.destination," ".join(args)],
            input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
        value=json.loads(result.stdout)
        if result.returncode and not (args[0]=='receipt' and isinstance(value,dict) and value.get('phase') in {'aborted','rolled_back','recovered','needs_attention'}):
            error=value.get('error','Unknown') if isinstance(value,dict) else 'Unknown'
            if not re.fullmatch(r'[A-Za-z]{1,60}',error):error='Unknown'
            raise RuntimeError("Restricted SSH operation failed: "+error)
        return value


def candidate_assets(version):
    """Download on the hosted runner; the server independently verifies bytes."""
    from release import REPOSITORY,validate_manifest
    import hashlib
    manifest=Path('target/promotion/release.json').read_bytes()
    value=validate_manifest(json.loads(manifest))
    if value['version']!=version:raise ValueError('Promotion manifest version differs')
    with tempfile.TemporaryDirectory(prefix='heyi-candidate-') as temporary:
        subprocess.run(['gh','release','download','candidate-'+value['source']['commit'],'--repo',REPOSITORY,
            '--pattern','source.tar.gz','--dir',temporary],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=180)
        file=Path(temporary)/'source.tar.gz'
        if file.stat().st_size!=value['sourceArchiveBytes'] or file.stat().st_size>512*1024**2:
            raise ValueError('Candidate source size differs')
        source=file.read_bytes()
    if hashlib.sha256(source).hexdigest()!=value['sourceArchiveSha256']:raise ValueError('Candidate source checksum differs')
    return {'release.json':manifest,'source.tar.gz':source}


def deploy(version,rollback=False,prepare_only=False):
    if not re.fullmatch(r"v\d+(?:\.\d+){2,3}",version):raise ValueError("Invalid version")
    # Re-run attempts use different IDs, duplicate polls/submissions use one ID.
    import hashlib
    operation=hashlib.sha256((os.environ["GITHUB_RUN_ID"]+":"+os.environ["GITHUB_RUN_ATTEMPT"]+":"+version).encode()).hexdigest()[:32]
    with Remote() as remote:
        baseline=remote.call('status')['version'] if not prepare_only else None
        if not rollback:remote.call("stage",version,payload={"pullToken":os.environ["GH_TOKEN"]},assets=candidate_assets(version))
        if prepare_only:
            output=Path("target/deployment");output.mkdir(parents=True,exist_ok=True)
            result={"version":version,"phase":"prepared","productionChanged":False}
            (output/"receipt.json").write_text(json.dumps(result)+"\n")
            print(json.dumps(result));return
        if not baseline:raise RuntimeError("Initial adoption is not accepted yet")
        result=remote.call("rollback" if rollback else "deploy",version,baseline,operation)
        deadline=time.monotonic()+1200
        while time.monotonic()<deadline:
            time.sleep(10)
            result=remote.call("receipt",operation,timeout=30)
            if result and result["phase"] in {"succeeded","rolled_back","aborted","recovered","needs_attention"}:break
        else:raise RuntimeError("Executor still running; inspect operation "+operation)
        output=Path("target/deployment");output.mkdir(parents=True,exist_ok=True)
        (output/"receipt.json").write_text(json.dumps(result,sort_keys=True,indent=2)+"\n")
        print(json.dumps(result))
        if result["phase"]!="succeeded":raise RuntimeError("Deployment did not succeed")


if __name__=="__main__":
    import argparse
    p=argparse.ArgumentParser();p.add_argument("version");mode=p.add_mutually_exclusive_group();mode.add_argument("--rollback",action="store_true");mode.add_argument("--prepare-only",action="store_true")
    a=p.parse_args();deploy(a.version,a.rollback,a.prepare_only)
