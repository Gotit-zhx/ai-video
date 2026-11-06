"""Fetch only this repository's attested Gitflow candidates at immutable digests."""
import json
import os
import re
import shutil
import tempfile
import time
from pathlib import Path
from .host import command, sha, inspect, GIB
from .state import atomic, read
try:
    from .release_contract import validate_manifest, VERSION, REPOSITORY
except ImportError:  # repository development; installer copies the same module
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]/"scripts/ci"))
    from release import validate_manifest, VERSION, REPOSITORY

WORKFLOW = REPOSITORY+"/.github/workflows/candidate.yml"


class Artifacts:
    def __init__(self, root): self.root=Path(root)

    def path(self, version):
        if not VERSION.fullmatch(version): raise ValueError("Invalid formal version")
        return self.root/"releases"/version

    def load(self, version):
        directory=self.path(version); target=validate_manifest(read(directory/"release.json",{}))
        receipt=read(directory/"verified.json",{})
        if receipt.get("manifestSha256")!=sha((directory/"release.json").read_bytes()):
            raise RuntimeError("Manifest has no matching trusted verification receipt")
        return target

    def prepare(self, version, token):
        self.path(version)
        if not token or len(token)>10000 or "\n" in token: raise ValueError("Missing short-lived package read credential")
        env=dict(os.environ,GH_TOKEN=token,GH_PROMPT_DISABLED="1")
        def api(path): return json.loads(command("gh","api","repos/"+REPOSITORY+"/"+path,env=env))
        head=api("git/ref/heads/main-tree")["object"]["sha"]
        ref=api("git/ref/tags/"+version)["object"]
        if ref["type"]=="tag": ref=api("git/tags/"+ref["sha"])["object"]
        if ref["type"]!="commit" or ref["sha"]!=head: raise ValueError("Formal tag is not current production head")
        commit=api("git/commits/"+head)
        if len(commit["parents"])!=2: raise ValueError("Formal tag is not a Gitflow merge")
        candidate=commit["parents"][1]["sha"]
        staging_root=self.root/"staging"; staging_root.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="prepare-",dir=staging_root) as temporary:
            folder=Path(temporary)
            command("gh","release","download","candidate-"+candidate,"--repo",REPOSITORY,
                    "--pattern","release.json","--pattern","source.tar.gz","--dir",str(folder),env=env,timeout=180)
            manifest=folder/"release.json"
            if manifest.stat().st_size>1024*1024: raise ValueError("Manifest too large")
            target=validate_manifest(json.loads(manifest.read_text()))
            if target["version"]!=version or target["source"]["commit"]!=candidate or target["source"]["tree"]!=commit["tree"]["sha"]:
                raise ValueError("Candidate source/version differs from formal merge")
            source_ref=target["source"]["ref"]
            if not re.fullmatch(r"refs/heads/(release|hotfix)/[A-Za-z0-9._-]+",source_ref): raise ValueError("Untrusted candidate branch")
            verify=["--repo",REPOSITORY,"--signer-workflow",WORKFLOW,"--deny-self-hosted-runners"]
            command("gh","attestation","verify",str(manifest),*verify,"--source-digest",candidate,"--source-ref",source_ref,env=env,timeout=120)
            source=folder/"source.tar.gz"
            if source.stat().st_size>512*1024*1024 or sha(source.read_bytes())!=target["sourceArchiveSha256"]: raise ValueError("Source checksum/size failed")
            budget=sum(i["compressedBytes"]+i["unpackedBytes"] for i in target["images"].values())
            if shutil.disk_usage(self.root).free<14*GIB+budget: raise RuntimeError("Insufficient pull/unpack budget plus 14 GiB reserve")
            docker=folder/"docker";docker.mkdir(mode=0o700);env["DOCKER_CONFIG"]=str(docker)
            # This token exists only for this prefetch command and is removed
            # before the independent systemd executor is started.
            command("docker","login","ghcr.io","--username",REPOSITORY.split('/')[0],"--password-stdin",data=token.encode(),env=env)
            for name,image in target["images"].items():
                ref=image["repository"]+"@"+image["manifestDigest"]
                command("gh","attestation","verify","oci://"+ref,*verify,"--source-digest",image["sourceCommit"],
                        "--source-ref",image["sourceRef"],env=env,timeout=120)
                command("docker","pull","--platform=linux/amd64",ref,env=env,timeout=300)
                info=inspect(ref)
                if info["Id"]!=image["configDigest"] or info["Architecture"]!="amd64" or info["Os"]!="linux": raise ValueError("Pulled image differs")
            destination=self.path(version)
            if destination.exists():
                if (destination/"release.json").read_bytes()!=manifest.read_bytes(): raise ValueError("Immutable version already has different artifacts")
            else:
                destination.mkdir(parents=True,mode=0o700)
                shutil.copyfile(manifest,destination/"release.json")
                shutil.copyfile(source,destination/"source.tar.gz")
            atomic(destination/"verified.json",{"verifiedAt":int(time.time()),"manifestSha256":sha(manifest.read_bytes()),
                   "productionCommit":head,"candidateCommit":candidate,"workflow":WORKFLOW})
        return target
