#!/usr/bin/env python3
"""Pinned-host SSH transport. Secrets enter files/stdin, never command output."""
import json
import contextlib
import io
import threading
import secrets
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path
from timing import TimingRecorder


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
    def ssh_command(self,args):
        return ["ssh","-T","-i",str(self.key),"-o","BatchMode=yes","-o","IdentitiesOnly=yes",
            "-o","StrictHostKeyChecking=yes","-o","UserKnownHostsFile="+str(self.hosts),"-o","ConnectTimeout=15",
            "-o","ServerAliveInterval=15","-o","ServerAliveCountMax=3",self.destination," ".join(args)]

    def _stream_call(self,command,header,frames,timeout):
        failures=[];recorder=getattr(self,'timings',None)
        if recorder is None:self.timings=recorder=TimingRecorder()
        with tempfile.TemporaryFile() as stdout:
            process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=stdout,stderr=subprocess.DEVNULL)
            def write():
                try:
                    with recorder.measure('sshUpload') as timing:
                        process.stdin.write(header);timing.add_bytes(len(header))
                        for frame in frames:
                            with (io.BytesIO(frame) if isinstance(frame,bytes) else Path(frame).open('rb')) as source:
                                while block:=source.read(1024**2):process.stdin.write(block);timing.add_bytes(len(block))
                        process.stdin.close();self.upload_finished_at=int(time.time()*1000)
                except Exception as error:failures.append(error)
            writer=threading.Thread(target=write,daemon=True);writer.start()
            try:
                process.wait(timeout=timeout);writer.join(timeout=5)
                if writer.is_alive():raise RuntimeError('SSH input writer did not finish')
                stdout.seek(0);output=stdout.read(1024**2+1)
                if len(output)>1024**2:raise ValueError('SSH response exceeds budget')
                if failures and not output:raise RuntimeError('SSH upload interrupted') from None
                from types import SimpleNamespace
                return SimpleNamespace(returncode=process.returncode,stdout=output)
            finally:
                if process.poll() is None:process.kill();process.wait()
                writer.join(timeout=5)
                if not process.stdin.closed:process.stdin.close()

    def call(self,*args,payload=None,assets=None,blobs=None,timeout=1200):
        if not args or not all(re.fullmatch(r"[A-Za-z0-9._-]+",a) for a in args):raise ValueError("Invalid command argument")
        frames=[];header=payload
        def size(value):return len(value) if isinstance(value,bytes) else Path(value).stat().st_size
        if assets is not None:
            plan=args[0]=='stage-plan'
            allowed={'release.json','source.tar.gz',*({'app.metadata.json','frontend.metadata.json'} if plan else {'app.oci.tar','frontend.oci.tar'})}
            if (args[0] not in {'stage','stage-plan'} or not {'release.json','source.tar.gz'}<=set(assets) or
                set(assets)-allowed or set(payload)!={'pullToken'}):raise ValueError('Assets are only accepted by stage or stage-plan')
            sizes={name:size(assets[name]) for name in ('release.json','source.tar.gz')}
            if not 0<sizes['release.json']<=1024*1024 or not 0<sizes['source.tar.gz']<=512*1024**2:raise ValueError('Invalid upload size bounds')
            header={**payload,'assetSizes':sizes};names=['release.json','source.tar.gz']
            if plan:
                if set(assets)!=allowed:raise ValueError('Both metadata frames required')
                metadata={name:size(assets[name+'.metadata.json']) for name in ('app','frontend')}
                if any(not 0<n<=1024**2 for n in metadata.values()):raise ValueError('Metadata exceeds budget')
                header['metadataSizes']=metadata;names.extend(name+'.metadata.json' for name in sorted(metadata))
            else:
                archives={name.removesuffix('.oci.tar'):size(value) for name,value in assets.items() if name.endswith('.oci.tar')}
                if any(not 0<n<=2*1024**3 for n in archives.values()):raise ValueError('Invalid OCI upload size')
                if archives:header['imageArchives']=archives
                names.extend(name+'.oci.tar' for name in sorted(archives))
            frames=[assets[name] for name in names]
        if blobs is not None:
            if assets is not None or args[0]!='stage' or set(payload)!={'protocol','planId','blobs'} or payload['protocol']!=2:raise ValueError('Invalid delta upload')
            from heyi_ops.transfer import unique_descriptors
            expected=unique_descriptors(payload['blobs'])
            if payload['blobs']!=expected or set(blobs)!={d['digest'] for d in expected} or any(size(blobs[d['digest']])!=d['size'] for d in expected):raise ValueError('Delta files differ from negotiated set')
            frames=[blobs[d['digest']] for d in expected]
        data=json.dumps(header,separators=(',',':')).encode()+b'\n' if header is not None else b''
        command=self.ssh_command(args)
        if assets is not None or blobs is not None:result=self._stream_call(command,data,frames,timeout)
        else:result=subprocess.run(command,input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
        self.response_received_at=int(time.time()*1000)
        value=json.loads(result.stdout)
        if isinstance(value,dict) and isinstance(value.get("timings"),dict):
            if not hasattr(self,"server_timings"):self.server_timings=[]
            self.server_timings.append({"command":args[0],"timings":value["timings"]})
        if result.returncode and not (args[0]=='receipt' and isinstance(value,dict) and value.get('phase') in {'aborted','rolled_back','recovered','needs_attention'}):
            error=value.get('error','Unknown') if isinstance(value,dict) else 'Unknown'
            if not re.fullmatch(r'[A-Za-z]{1,60}',error):error='Unknown'
            raise RuntimeError("Restricted SSH operation failed: "+error)
        return value


def require_capabilities(manifest,status):
    schemas=status.get('releaseSchemas',[1]);protocols=status.get('transferProtocols',[1])
    if manifest['schemaVersion'] not in schemas:raise ValueError('Host control plane does not support candidate schema; maintenance required')
    return protocols


@contextlib.contextmanager
def candidate_assets(version,remote,full=True):
    """Keep large immutable downloads on disk for the entire SSH upload lifetime."""
    from release import REPOSITORY,validate_manifest
    from heyi_ops.artifacts import file_sha
    manifest=Path('target/promotion/release.json')
    value=validate_manifest(json.loads(manifest.read_text()))
    if value['version']!=version:raise ValueError('Promotion manifest version differs')
    if not hasattr(remote,'timings'):remote.timings=TimingRecorder()
    with tempfile.TemporaryDirectory(prefix='heyi-candidate-') as temporary:
        root=Path(temporary);file=root/'source.tar.gz'
        with remote.timings.measure('sourceDownload') as timing:
            subprocess.run(['gh','release','download','candidate-'+value['source']['commit'],'--repo',REPOSITORY,
                '--pattern','source.tar.gz','--dir',temporary],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=180)
            if file.stat().st_size!=value['sourceArchiveBytes'] or file.stat().st_size>512*1024**2:raise ValueError('Candidate source size differs')
            if file_sha(file)!=value['sourceArchiveSha256']:raise ValueError('Candidate source checksum differs')
            timing.add_bytes(file.stat().st_size)
        assets={'release.json':manifest,'source.tar.gz':file}
        if full:_full_assets(value,remote,assets,root)
        yield assets


def _full_assets(value,remote,assets,root,force=False):
    from heyi_ops.oci import export_archive
    for component,image in value['images'].items():
        if not force:
            cached=remote.call('image-cache',component,image['manifestDigest'][7:],image['configDigest'][7:])
            if cached.get('present') is True:continue
        folder=root/component;folder.mkdir(exist_ok=True)
        path=folder/'image.tar'
        if not path.exists():
            with remote.timings.measure('ociExport') as timing:
                export_archive(image,path,os.environ['GH_TOKEN']);timing.add_bytes(path.stat().st_size)
        assets[component+'.oci.tar']=path


def stage_candidate(version,remote,plan_id=None,capabilities=None):
    from release import validate_manifest
    from heyi_ops.oci import export_metadata,export_blobs
    from heyi_ops.transfer import validate_metadata,unique_descriptors
    value=validate_manifest(json.loads(Path('target/promotion/release.json').read_text()))
    protocols=require_capabilities(value,capabilities if capabilities is not None else remote.call('status'))
    remote.transfer_summary={'logicalBytes':value['sourceArchiveBytes']+sum(i['compressedBytes'] for i in value['images'].values()),
        'uploadedBlobBytes':0,'cachedBlobBytes':0,'transferMode':'full'}
    with candidate_assets(version,remote,full=False) as assets:
        root=assets['source.tar.gz'].parent
        def full(force=False):
            _full_assets(value,remote,assets,root,force)
            remote.transfer_summary.update(transferMode='full',uploadedBlobBytes=sum(v.stat().st_size for k,v in assets.items() if k.endswith('.oci.tar')),
                cachedBlobBytes=sum(i['compressedBytes'] for name,i in value['images'].items() if name+'.oci.tar' not in assets))
            result=remote.call('stage',version,payload={'pullToken':os.environ['GH_TOKEN']},
                assets={k:v for k,v in assets.items() if not k.endswith('.metadata.json')})
            if not result.get('prepared'):raise RuntimeError('Full stage did not prepare candidate')
            return result
        if not delta_enabled() or 2 not in protocols:return full()
        graphs={};descriptor_map={};metadata_paths={}
        for component,image in value['images'].items():
            with remote.timings.measure('registryResolve'):
                metadata=export_metadata(image,os.environ['GH_TOKEN'])
            _,descriptors,_=validate_metadata(image,metadata);graphs[component]=descriptors
            for d in descriptors:descriptor_map[d['digest']]=d
            path=root/(component+'.metadata.json');path.write_text(json.dumps(metadata,separators=(',',':')))
            metadata_paths[path.name]=path
        plan_id=plan_id or secrets.token_hex(16)
        for attempt in range(3):
            plan=remote.call('stage-plan',version,plan_id,payload={'pullToken':os.environ['GH_TOKEN']},assets={**assets,**metadata_paths})
            if plan.get('prepared') is True:return plan
            if plan.get('phase')=='needs_repair':return full(force=True)
            if plan.get('phase')=='needs_replan':plan_id=secrets.token_hex(16);continue
            if plan.get('transferMode')=='full':return full()
            if plan.get('protocol')!=2 or plan.get('planId')!=plan_id or plan.get('version')!=version:raise ValueError('Unexpected transfer plan')
            from heyi_ops.artifacts import file_sha
            if plan.get('manifestSha256')!=file_sha(assets['release.json']):raise ValueError('Plan manifest identity differs')
            missing=unique_descriptors(plan['missingBlobs'])
            remote.transfer_summary.update(transferMode='delta',uploadedBlobBytes=sum(d['size'] for d in missing),
                cachedBlobBytes=plan.get('cachedBlobBytes',0))
            if missing!=plan['missingBlobs'] or any(descriptor_map.get(d['digest'])!=d for d in missing):raise ValueError('Plan requests unsigned content')
            blob_folder=root/'delta';blob_folder.mkdir(exist_ok=True)
            for component,image in value['images'].items():
                wanted=[d for d in missing if d in graphs[component] and not (blob_folder/d['digest'][7:]).exists()]
                if wanted:
                    with remote.timings.measure('registryDownload') as timing:
                        export_blobs(image,wanted,blob_folder,os.environ['GH_TOKEN']);timing.add_bytes(sum(d['size'] for d in wanted))
            result=remote.call('stage',version,payload={'protocol':2,'planId':plan_id,'blobs':missing},
                blobs={d['digest']:blob_folder/d['digest'][7:] for d in missing})
            if result.get('prepared'):return result
            if result.get('phase')=='needs_repair':return full(force=True)
            if result.get('phase')!='needs_replan':raise RuntimeError('Delta stage did not prepare candidate')
            if result.get('reason') in {'PlanExpiredOrBaselineChanged','ContentAPIUnavailable'}:plan_id=secrets.token_hex(16)
        raise RuntimeError('Transfer negotiation did not converge; retry with a fresh plan')


def delta_enabled():
    return os.environ.get('HEYI_DELTA_TRANSFER_ENABLED')=='true'


def write_timings(remote,output):
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    client=getattr(remote,'timings',TimingRecorder()).snapshot()
    summary={'schemaVersion':1,**getattr(remote,'transfer_summary',{}),
        'uploadedBytes':sum(p['bytes'] for p in client['phases'] if p['phase']=='sshUpload'),
        'uploadFinishedAtMs':getattr(remote,'upload_finished_at',None),
        'responseReceivedAtMs':getattr(remote,'response_received_at',None),
        'client':client,'server':getattr(remote,'server_timings',[])}
    path=output/'timings.json';path.write_text(json.dumps(summary,sort_keys=True,indent=2)+'\n');return path


def deploy(version,rollback=False,prepare_only=False):
    if not re.fullmatch(r"v\d+(?:\.\d+){2,3}",version):raise ValueError("Invalid version")
    # Re-run attempts use different IDs, duplicate polls/submissions use one ID.
    import hashlib
    operation=hashlib.sha256((os.environ["GITHUB_RUN_ID"]+":"+os.environ["GITHUB_RUN_ATTEMPT"]+":"+version).encode()).hexdigest()[:32]
    with Remote() as remote:
        capabilities=remote.call('status');baseline=capabilities['version']
        try:stage_result=stage_candidate(version,remote,operation,capabilities) if not rollback else None
        finally:write_timings(remote,Path('target/deployment'))
        if prepare_only:
            output=Path("target/deployment");output.mkdir(parents=True,exist_ok=True)
            result={"version":version,"phase":"prepared","productionChanged":False,"timings":remote.timings.snapshot(),"serverTimings":(stage_result or {}).get("timings")}
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
        write_timings(remote,output)
        (output/"receipt.json").write_text(json.dumps(result,sort_keys=True,indent=2)+"\n")
        print(json.dumps(result))
        if result["phase"]!="succeeded":raise RuntimeError("Deployment did not succeed")


if __name__=="__main__":
    import argparse
    p=argparse.ArgumentParser();p.add_argument("version");mode=p.add_mutually_exclusive_group();mode.add_argument("--rollback",action="store_true");mode.add_argument("--prepare-only",action="store_true")
    a=p.parse_args();deploy(a.version,a.rollback,a.prepare_only)
