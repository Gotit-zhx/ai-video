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
import urllib.request
from pathlib import Path
from .host import command, sha, inspect, image_config_digest
from .state import atomic, read
from .storage import require_space
from .timing import TimingRecorder
try:
    from .release_contract import validate_manifest, VERSION, REPOSITORY
except ImportError:  # repository development; installer copies the same module
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]/"scripts/ci"))
    from release import validate_manifest, VERSION, REPOSITORY

WORKFLOW = REPOSITORY+"/.github/workflows/candidate.yml"


def production_head():
    request=urllib.request.Request('https://api.github.com/repos/'+REPOSITORY+'/git/ref/heads/main-tree',
        headers={'User-Agent':'heyi-ops','Accept':'application/vnd.github+json'})
    with urllib.request.urlopen(request,timeout=15) as response:
        head=json.load(response)['object']['sha']
    if not re.fullmatch(r'[a-f0-9]{40}',head):raise ValueError('Invalid production head')
    return head


def file_sha(path):
    import hashlib
    hashed=hashlib.sha256()
    with Path(path).open('rb') as source:
        while block:=source.read(1024**2):hashed.update(block)
    return hashed.hexdigest()


def process_identity(pid):
    try:return Path('/proc/'+str(int(pid))+'/stat').read_text().rsplit(')',1)[1].split()[19]
    except (FileNotFoundError,ProcessLookupError,PermissionError,ValueError,TypeError,IndexError):return None


class Artifacts:
    def __init__(self, root): self.root=Path(root);self.timings=TimingRecorder()

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
        return removed+self.cleanup_transfer_plans()

    def cleanup_transfer_plans(self):
        """Only expired, dead-process plans with an exact owned file vocabulary."""
        from .transfer import PLAN_ID
        planned=[]
        for folder in (self.root/'staging').glob('transfer-*'):
            if folder.is_symlink() or not folder.is_dir() or not PLAN_ID.fullmatch(folder.name[9:]):continue
            try:owner=read(folder/'plan.json',{})
            except (ValueError,OSError):continue
            if (owner.get('owner')!='heyi-ops.transfer' or owner.get('planId')!=folder.name[9:] or
                not VERSION.fullmatch(owner.get('version','')) or not owner.get('processStart') or
                type(owner.get('expiresAt')) is not int or owner['expiresAt']>int(time.time()) or
                process_identity(owner.get('pid',0))==owner['processStart']):continue
            safe=True
            for entry in folder.rglob('*'):
                relative=entry.relative_to(folder);parts=relative.parts
                if entry.is_symlink():safe=False;break
                if entry.is_dir():valid=len(parts)==1 and (parts[0]=='blobs' or re.fullmatch(r'cache-[A-Za-z0-9_-]+',parts[0]))
                else:
                    valid=(len(parts)==1 and parts[0] in {'plan.json','release.json','source.tar.gz','app.oci.tar','frontend.oci.tar'}) or (len(parts)==2 and (parts[0]=='blobs' or re.fullmatch(r'cache-[A-Za-z0-9_-]+',parts[0])) and re.fullmatch(r'[a-f0-9]{64}(?:\.part)?',parts[1]))
                if not valid:safe=False;break
            if safe:planned.append(folder)
        if planned:atomic(self.root/'transfer-cleanup-plan.json',{'createdAt':int(time.time()),'owner':'heyi-ops.transfer','paths':[str(p) for p in planned]})
        for folder in planned:shutil.rmtree(folder)
        return [p.name for p in planned]

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

    def trusted_inputs(self, version, token, folder, upload=None):
        with self.timings.measure('sourceAndProofVerify'):
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
            require_space(self.root, advertised["source.tar.gz"]+1024**2)
        with self.timings.measure('serverReceive' if upload else 'sourceDownload') as timing:
            if upload is None:
                command("gh","release","download","candidate-"+candidate,"--repo",REPOSITORY,
                        "--pattern","release.json","--pattern","source.tar.gz","--dir",str(folder),env=env,timeout=180)
            else:
                self.receive_assets(folder,upload[0],upload[1],advertised,trailing=bool(upload[2]))
            timing.add_bytes(sum(advertised[name] for name in ('release.json','source.tar.gz')))
        with self.timings.measure('sourceAndProofVerify'):
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
            if source.stat().st_size!=target["sourceArchiveBytes"] or file_sha(source)!=target["sourceArchiveSha256"]: raise ValueError("Source checksum/size failed")
            docker=folder/'docker';docker.mkdir(mode=0o700);env['DOCKER_CONFIG']=str(docker)
            command('docker','login','ghcr.io','--username',REPOSITORY.split('/')[0],'--password-stdin',data=token.encode(),env=env)
            for image in target['images'].values():
                ref=image['repository']+'@'+image['manifestDigest']
                command('gh','attestation','verify','oci://'+ref,*verify,'--source-digest',image['sourceCommit'],
                        '--source-ref',image['sourceRef'],env=env,timeout=120)
            proof={'verifiedAt':int(time.time()),'manifestSha256':file_sha(manifest),
                   'productionCommit':head,'candidateCommit':candidate,'workflow':WORKFLOW}
            return target,proof,env

    def publish(self, folder, target, proof):
        destination=self.path(target['version']);manifest=folder/'release.json'
        if destination.exists():
            if (destination/'release.json').read_bytes()!=manifest.read_bytes():raise ValueError('Immutable version already has different artifacts')
        else:
            destination.mkdir(parents=True,mode=0o700)
            shutil.copyfile(manifest,destination/'release.json')
            shutil.copyfile(folder/'source.tar.gz',destination/'source.tar.gz')
        atomic(destination/'verified.json',proof)

    def verify_import(self,image):
        ref=image['repository']+'@'+image['manifestDigest'];info=inspect(ref)
        if (image_config_digest(info)!=image['configDigest'] or info['Architecture']!='amd64' or info['Os']!='linux' or
            (ref not in info.get('RepoDigests',[]) and info.get('Descriptor',{}).get('digest')!=image['manifestDigest'])):
            raise ValueError('Imported manifest or config differs')

    def prepare(self, version, token, upload=None):
        with self.workspace(version) as folder:
            target,proof,env=self.trusted_inputs(version,token,folder,upload)
            archives=upload[2] if upload else {}
            cached={name for name,image in target['images'].items() if name not in archives and self.cached(image)}
            if any(size>target['images'][name]['compressedBytes']+2*1024**2 for name,size in archives.items()):
                raise ValueError('Image transfer exceeds attested size budget')
            budget=sum(i["compressedBytes"]+i["unpackedBytes"] for name,i in target["images"].items() if name not in cached)
            budget+=sum(archives.values())+2*target["sourceArchiveBytes"]+target["sourceUnpackedBytes"]
            require_space(self.root, budget)
            archive_graphs={}
            for name in sorted(archives):
                remaining=archives[name]
                with self.timings.measure('serverReceive') as timing:
                    with (folder/(name+'.oci.tar')).open('xb') as output:
                        while remaining:
                            block=upload[0].read(min(1024**2,remaining))
                            if not block:raise ValueError('Truncated OCI upload')
                            output.write(block);remaining-=len(block)
                    timing.add_bytes(archives[name])
                from .oci import validate_archive
                with self.timings.measure('ociVerify'):
                    archive_graphs[name]=validate_archive(folder/(name+'.oci.tar'),target['images'][name])
            if archives and upload[0].read(1):raise ValueError('Unexpected trailing OCI data')
            for name,image in target["images"].items():
                ref=image["repository"]+"@"+image["manifestDigest"]
                with self.timings.measure('imageLoad'):
                    if name not in cached:
                        if name in archives:command('docker','image','load','--input',str(folder/(name+'.oci.tar')),timeout=300)
                        else:command("docker","pull","--platform=linux/amd64",ref,env=env,timeout=300)
                    self.verify_import(image)
            if archive_graphs:
                from .transfer import ContainerdCache,CacheUnavailable,CacheIntegrityError,plan_missing,unique_descriptors
                descriptors=unique_descriptors([d for graph in archive_graphs.values() for d in graph['descriptors']])
                try:
                    with ContainerdCache(folder,descriptors) as imported:
                        if plan_missing(descriptors,imported.open):raise CacheIntegrityError('Imported OCI content is incomplete')
                except CacheUnavailable:pass  # Classic Docker validates the complete archive on load.
            self.publish(folder,target,{**proof,'cachedImages':sorted(cached)})
        return target

    def plan_path(self, plan_id):
        from .transfer import PLAN_ID
        if not isinstance(plan_id,str) or not PLAN_ID.fullmatch(plan_id):raise ValueError('Invalid transfer plan ID')
        return self.root/'staging'/('transfer-'+plan_id)

    def plan_response(self,plan,missing):
        return {'protocol':2,'planId':plan['planId'],'version':plan['version'],'manifestSha256':plan['manifestSha256'],
                'transferMode':'delta','missingBlobs':missing,'uploadBytes':sum(d['size'] for d in missing),
                'temporaryPeakBytes':plan['temporaryPeakBytes'],'cachedBlobBytes':plan.get('cachedBlobBytes',0),'logicalBlobBytes':sum(d['size'] for d in plan['descriptors']),'timings':self.timings.snapshot(),'expiresInSeconds':max(0,plan['expiresAt']-int(time.time()))}

    def prepare_plan(self,version,plan_id,token,stream,sizes,metadata_sizes):
        from .transfer import validate_metadata,unique_descriptors,plan_missing,temporary_peak,ContainerdCache,CacheUnavailable,CacheIntegrityError,TTL_SECONDS
        folder=self.plan_path(plan_id);self.path(version)
        if (not isinstance(metadata_sizes,dict) or set(metadata_sizes)!={'app','frontend'} or
            any(type(n) is not int or not 0<n<=1024**2 for n in metadata_sizes.values())):raise ValueError('Invalid metadata frame bounds')
        with self.workspace(version) as incoming:
            target,proof,_=self.trusted_inputs(version,token,incoming,(stream,sizes,True))
            graphs={};raw={};descriptors=[]
            for name in sorted(metadata_sizes):
                with self.timings.measure('serverReceive') as timing:
                    data=stream.read(metadata_sizes[name]);timing.add_bytes(len(data))
                    if len(data)!=metadata_sizes[name]:raise ValueError('Truncated metadata frame')
                with self.timings.measure('ociVerify'):
                    root,items,metadata=validate_metadata(target['images'][name],json.loads(data))
                graphs[name]={'root':root,'descriptors':items};descriptors.extend(items);raw.update(metadata)
            if stream.read(1):raise ValueError('Unexpected metadata trailing data')
            descriptors=unique_descriptors(descriptors);peak=temporary_peak(target,descriptors)
            require_space(self.root,peak)
            prior=read(folder/'plan.json')
            baseline=read(self.root/'active.json')
            if prior and (prior['version']!=version or prior['manifestSha256']!=proof['manifestSha256']):raise ValueError('Transfer plan ID reused')
            if prior and (prior['expiresAt']<=int(time.time()) or prior['baseline']!=baseline or prior['productionCommit']!=proof['productionCommit']):
                return {'protocol':2,'planId':plan_id,'phase':'needs_replan','reason':'PlanExpiredOrBaselineChanged'}
            try:
                with ContainerdCache(incoming,descriptors) as cache:
                    def cached(value):
                        file=folder/'blobs'/value[7:]
                        if prior and file.exists():return file.open('rb')
                        existing=cache.open(value)
                        if existing is not None:return existing
                        return __import__('io').BytesIO(raw[value]) if value in raw else None
                    missing=plan_missing(descriptors,cached)
            except CacheUnavailable:
                return {'protocol':2,'planId':plan_id,'version':version,'transferMode':'full','missingBlobs':[],
                        'manifestSha256':proof['manifestSha256'],'temporaryPeakBytes':peak,'expiresInSeconds':0}
            except CacheIntegrityError:
                return {'protocol':2,'planId':plan_id,'phase':'needs_repair','reason':'CachedBlobIntegrity'}
            if prior and prior.get('completed') and not missing:
                self.load(version)
                for image in target['images'].values():self.verify_import(image)
                return {**self.plan_response(prior,[]),'prepared':True,'timings':self.timings.snapshot()}
            plan={**proof,'owner':'heyi-ops.transfer','planId':plan_id,'version':version,'baseline':baseline,
                  'createdAt':int(time.time()),'expiresAt':prior['expiresAt'] if prior else int(time.time())+TTL_SECONDS,
                  'pid':os.getpid(),'processStart':process_identity(os.getpid()),'graphs':graphs,'descriptors':descriptors,'cachedBlobBytes':max(0,sum(d['size'] for d in descriptors)-sum(d['size'] for d in missing)-sum(len(v) for v in raw.values())),'temporaryPeakBytes':peak,'missingBlobs':missing,'completed':False}
            folder.mkdir(mode=0o700,parents=True,exist_ok=True);(folder/'blobs').mkdir(mode=0o700,exist_ok=True)
            atomic(folder/'plan.json',plan)
            for name in ('release.json','source.tar.gz'):shutil.copyfile(incoming/name,folder/name)
            for value,data in raw.items():(folder/'blobs'/value[7:]).write_bytes(data)
            atomic(folder/'plan.json',plan)
            return self.plan_response(plan,missing)

    def stage_delta(self,version,payload,stream):
        from .transfer import plan_missing,receive_blobs,reconstruct,ContainerdCache,CacheUnavailable,CacheIntegrityError
        self.path(version);folder=self.plan_path(payload.get('planId'));plan=read(folder/'plan.json')
        if not plan or plan.get('owner')!='heyi-ops.transfer' or plan['version']!=version:raise ValueError('Unknown transfer plan')
        def replan(reason,missing=None):
            return {'protocol':2,'planId':plan['planId'],'phase':'needs_replan','reason':reason,**({'missingBlobs':missing} if missing is not None else {})}
        if plan['expiresAt']<=int(time.time()) or plan['baseline']!=read(self.root/'active.json') or plan['productionCommit']!=production_head():
            return replan('PlanExpiredOrBaselineChanged')
        if file_sha(folder/'release.json')!=plan['manifestSha256']:raise ValueError('Transfer plan manifest changed')
        target=validate_manifest(read(folder/'release.json'))
        if plan.get('completed'):
            if payload['blobs'] or stream.read(1):raise ValueError('Completed plan does not accept content')
            self.load(version)
            for image in target['images'].values():self.verify_import(image)
            return {'protocol':2,'version':version,'planId':plan['planId'],'prepared':True,'timings':self.timings.snapshot()}
        if file_sha(folder/'source.tar.gz')!=target['sourceArchiveSha256']:raise ValueError('Transfer source changed')
        plan.update(pid=os.getpid(),processStart=process_identity(os.getpid()));atomic(folder/'plan.json',plan)
        require_space(self.root,plan['temporaryPeakBytes'])
        try:
            with ContainerdCache(folder,plan['descriptors']) as cache:
                def cached(value):
                    path=folder/'blobs'/value[7:]
                    return path.open('rb') if path.exists() else cache.open(value)
                missing=plan_missing(plan['descriptors'],cached)
                # Recompute after interrupted transfers: retained verified files are not requested twice.
                if payload['blobs']!=missing:
                    plan['missingBlobs']=missing;atomic(folder/'plan.json',plan)
                    return replan('MissingSetChanged',missing)
                with self.timings.measure('serverReceive') as timing:
                    receive_blobs(folder/'blobs',stream,payload['blobs'],missing);timing.add_bytes(sum(d['size'] for d in missing))
                missing=plan_missing(plan['descriptors'],cached)
                if missing:return replan('CachedBlobEvicted',missing)
                if plan['expiresAt']<=int(time.time()) or plan['baseline']!=read(self.root/'active.json') or plan['productionCommit']!=production_head():
                    return replan('PlanExpiredOrBaselineChanged')
                for name,image in target['images'].items():
                    archive=folder/(name+'.oci.tar');graph=plan['graphs'][name]
                    with self.timings.measure('ociVerify'):
                        reconstruct(image,graph['root'],graph['descriptors'],cached,archive)
                    with self.timings.measure('imageLoad'):
                        command('docker','image','load','--input',str(archive),timeout=300)
                        self.verify_import(image)
                    archive.unlink()
            with ContainerdCache(folder,plan['descriptors']) as imported:
                if plan_missing(plan['descriptors'],imported.open):raise CacheIntegrityError('Imported OCI content is incomplete')
            self.publish(folder,target,{key:plan[key] for key in ('verifiedAt','manifestSha256','productionCommit','candidateCommit','workflow')})
            plan['completed']=True;plan['missingBlobs']=[];atomic(folder/'plan.json',plan)
            # Retain only tiny immutable proof; copied source/OCI blobs have served their purpose.
            shutil.rmtree(folder/'blobs');(folder/'source.tar.gz').unlink()
            return {'protocol':2,'version':version,'planId':plan['planId'],'prepared':True,'timings':self.timings.snapshot()}
        except CacheIntegrityError:
            return {'protocol':2,'planId':plan['planId'],'phase':'needs_repair','reason':'CachedBlobIntegrity'}
        except CacheUnavailable:return replan('ContentAPIUnavailable')
