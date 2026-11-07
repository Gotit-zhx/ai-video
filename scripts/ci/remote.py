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
    def call(self,*args,payload=None,timeout=600):
        if not all(re.fullmatch(r"[A-Za-z0-9._-]+",a) for a in args):raise ValueError("Invalid command argument")
        result=subprocess.run(["ssh","-T","-i",str(self.key),"-o","BatchMode=yes","-o","IdentitiesOnly=yes",
            "-o","StrictHostKeyChecking=yes","-o","UserKnownHostsFile="+str(self.hosts),"-o","ConnectTimeout=15",
            "-o","ServerAliveInterval=15","-o","ServerAliveCountMax=3",self.destination," ".join(args)],
            input=json.dumps(payload).encode() if payload else b"",stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
        if result.returncode:raise RuntimeError("Restricted SSH operation failed")
        return json.loads(result.stdout)


def deploy(version,rollback=False,prepare_only=False):
    if not re.fullmatch(r"v\d+(?:\.\d+){2,3}",version):raise ValueError("Invalid version")
    # Re-run attempts use different IDs, duplicate polls/submissions use one ID.
    import hashlib
    operation=hashlib.sha256((os.environ["GITHUB_RUN_ID"]+":"+os.environ["GITHUB_RUN_ATTEMPT"]+":"+version).encode()).hexdigest()[:32]
    with Remote() as remote:
        if not rollback:remote.call("stage",version,payload={"pullToken":os.environ["GH_TOKEN"]})
        if prepare_only:
            output=Path("target/deployment");output.mkdir(parents=True,exist_ok=True)
            result={"version":version,"phase":"prepared","productionChanged":False}
            (output/"receipt.json").write_text(json.dumps(result)+"\n")
            print(json.dumps(result));return
        baseline=remote.call("status")["version"]
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
