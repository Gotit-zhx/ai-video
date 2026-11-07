"""Fetch only this repository's attested Gitflow candidates at immutable digests."""
import json
import contextlib
import os
import re
import signal
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from .host import command, sha, inspect, image_config_digest, GIB
from .state import atomic, read
try:
    from .release_contract import validate_manifest, VERSION, REPOSITORY
except ImportError:  # repository development; installer copies the same module
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]/"scripts/ci"))
    from release import validate_manifest, VERSION, REPOSITORY

WORKFLOW = REPOSITORY+"/.github/workflows/candidate.yml"


def process_identity(pid):
    try:return Path('/proc/'+str(int(pid))+'/stat').read_text().rsplit(')',1)[1].split()[19]
    except (FileNotFoundError,ProcessLookupError,PermissionError,ValueError,TypeError,IndexError):return None


class Artifacts:
    def __init__(self, root): self.root=Path(root)

    @staticmethod
    def receive_assets(folder, stream, sizes, advertised, trailing=False):
        """Untrusted bytes have fixed names and must match GitHub's asset sizes."""
        if sizes != {name:advertised[name] for name in ('release.json','source.tar.gz')}:
            raise ValueError('Uploaded asset sizes differ from the candidate')
        for name in ('release.json','source.tar.gz'):
            remaining=sizes[name]
            with (folder/name).open('xb') as output:
                while remaining:
                    block=stream.read(min(1024*1024,remaining))
                    if not block:raise ValueError('Truncated candidate upload')
                    output.write(block);remaining-=len(block)
        if not trailing and stream.read(1):raise ValueError('Unexpected trailing upload data')

    @staticmethod
    def cached(image):
        local=subprocess.run(['docker','image','inspect',image['repository']+'@'+image['manifestDigest']],capture_output=True,timeout=15)
        if local.returncode:return False
        info=json.loads(local.stdout)[0]
        if image_config_digest(info)!=image['configDigest'] or info['Architecture']!='amd64' or info['Os']!='linux':
            raise ValueError('Cached image differs from the release manifest')
        return True

    def cleanup_staging(self):
        """Caller holds the shared lock; remove only dead, explicitly owned prepares."""
        removed=[];planned=[]
        for directory in (self.root/'staging').glob('prepare-*'):
            if directory.is_symlink() or not directory.is_dir():continue
            try:owner=read(directory/'.heyi-prepare.json',{})
            except (ValueError,OSError):continue
            if owner.get('owner')!='heyi-ops.prepare' or not owner.get('processStart') or not VERSION.fullmatch(owner.get('version','')):continue
            if process_identity(owner.get('pid',0))==owner['processStart']:continue
            if {p.name for p in directory.iterdir()}-{'release.json','source.tar.gz','app.oci.tar','frontend.oci.tar','docker','.heyi-prepare.json'}:continue
            entries=list(directory.rglob('*'))
            if any(p.is_symlink() for p in entries):continue
            if any(str(p.relative_to(directory)) not in ({'docker'} if p.is_dir() else {'release.json','source.tar.gz','app.oci.tar','frontend.oci.tar','.heyi-prepare.json','docker/config.json'}) for p in entries):continue
            docker=directory/'docker'
            if docker.exists() and (not docker.is_dir() or {p.name for p in docker.iterdir()}-{'config.json'}):continue
            planned.append(directory)
        if planned:atomic(self.root/'staging-cleanup-plan.json',{'createdAt':int(time.time()),'owner':'heyi-ops.prepare','paths':[str(p) for p in planned]})
        for directory in planned:
            shutil.rmtree(directory);removed.append(directory.name)
        if removed:atomic(self.root/'staging-cleanup.json',{'createdAt':int(time.time()),'removed':removed})
        return removed

    @contextlib.contextmanager
    def workspace(self,version):
        self.cleanup_staging()
        staging=self.root/'staging';staging.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='prepare-',dir=staging) as temporary:
            folder=Path(temporary)
            atomic(folder/'.heyi-prepare.json',{'owner':'heyi-ops.prepare','version':version,'pid':os.getpid(),
                'processStart':process_identity(os.getpid()),'createdAt':int(time.time())})
            def interrupted(*_):raise InterruptedError('Prefetch interrupted')
            previous={sig:signal.signal(sig,interrupted) for sig in (signal.SIGHUP,signal.SIGTERM)}
            try:yield folder
            finally:
                (folder/'docker/config.json').unlink(missing_ok=True)
                for sig,handler in previous.items():signal.signal(sig,handler)

    def path(self, version):
        if not VERSION.fullmatch(version): raise ValueError("Invalid formal version")
        return self.root/"releases"/version

    def load(self, version):
        directory=self.path(version); target=validate_manifest(read(directory/"release.json",{}))
        receipt=read(directory/"verified.json",{})
        if receipt.get("manifestSha256")!=sha((directory/"release.json").read_bytes()):
            raise RuntimeError("Manifest has no matching trusted verification receipt")
        return target

    def prepare(self, version, token, upload=None):
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
        advertised={a["name"]:a["size"] for a in api("releases/tags/candidate-"+candidate)["assets"]}
        if not 0<advertised.get("release.json",0)<=1024*1024 or not 0<advertised.get("source.tar.gz",0)<=512*1024**2:
            raise ValueError("Missing or oversized candidate assets")
        if shutil.disk_usage(self.root).free<14*GIB+advertised["source.tar.gz"]+1024**2:
            raise RuntimeError("Insufficient space to download candidate assets")
        with self.workspace(version) as folder:
            if upload is None:
                command("gh","release","download","candidate-"+candidate,"--repo",REPOSITORY,
                        "--pattern","release.json","--pattern","source.tar.gz","--dir",str(folder),env=env,timeout=180)
            else:
                self.receive_assets(folder,upload[0],upload[1],advertised,trailing=bool(upload[2]))
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
            if source.stat().st_size!=target["sourceArchiveBytes"] or sha(source.read_bytes())!=target["sourceArchiveSha256"]: raise ValueError("Source checksum/size failed")
            cached={name for name,image in target['images'].items() if self.cached(image)}
            archives=upload[2] if upload else {}
            if any(size>target['images'][name]['compressedBytes']+2*1024**2 for name,size in archives.items()):
                raise ValueError('Image transfer exceeds attested size budget')
            budget=sum(i["compressedBytes"]+i["unpackedBytes"] for name,i in target["images"].items() if name not in cached)
            budget+=sum(archives.values())+2*target["sourceArchiveBytes"]+target["sourceUnpackedBytes"]
            if shutil.disk_usage(self.root).free<14*GIB+budget: raise RuntimeError("Insufficient pull/unpack budget plus 14 GiB reserve")
            for name in sorted(archives):
                remaining=archives[name]
                with (folder/(name+'.oci.tar')).open('xb') as output:
                    while remaining:
                        block=upload[0].read(min(1024**2,remaining))
                        if not block:raise ValueError('Truncated OCI upload')
                        output.write(block);remaining-=len(block)
                from .oci import validate_archive
                validate_archive(folder/(name+'.oci.tar'),target['images'][name])
            if archives and upload[0].read(1):raise ValueError('Unexpected trailing OCI data')
            docker=folder/"docker";docker.mkdir(mode=0o700);env["DOCKER_CONFIG"]=str(docker)
            # This token exists only for this prefetch command and is removed
            # before the independent systemd executor is started.
            command("docker","login","ghcr.io","--username",REPOSITORY.split('/')[0],"--password-stdin",data=token.encode(),env=env)
            for name,image in target["images"].items():
                ref=image["repository"]+"@"+image["manifestDigest"]
                command("gh","attestation","verify","oci://"+ref,*verify,"--source-digest",image["sourceCommit"],
                        "--source-ref",image["sourceRef"],env=env,timeout=120)
                if name not in cached:
                    if name in archives:command('docker','image','load','--input',str(folder/(name+'.oci.tar')),timeout=300)
                    else:command("docker","pull","--platform=linux/amd64",ref,env=env,timeout=300)
                info=inspect(ref)
                if image_config_digest(info)!=image["configDigest"] or info["Architecture"]!="amd64" or info["Os"]!="linux": raise ValueError("Pulled image differs")
            destination=self.path(version)
            if destination.exists():
                if (destination/"release.json").read_bytes()!=manifest.read_bytes(): raise ValueError("Immutable version already has different artifacts")
            else:
                destination.mkdir(parents=True,mode=0o700)
                shutil.copyfile(manifest,destination/"release.json")
                shutil.copyfile(source,destination/"source.tar.gz")
            atomic(destination/"verified.json",{"verifiedAt":int(time.time()),"manifestSha256":sha(manifest.read_bytes()),
                   "productionCommit":head,"candidateCommit":candidate,"workflow":WORKFLOW,"cachedImages":sorted(cached)})
        return target
