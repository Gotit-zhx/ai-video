"""Durable first transition from the audited legacy Compose/runtime baseline."""
import copy
import json
import os
import shutil
import time
from pathlib import Path
from .host import SERVICES, GIB, command, inspect, config_hash, configuration, sha
from .legacy import Legacy
from .state import Busy, atomic, read, TERMINAL


class Adoption:
    def __init__(self,store,host):self.store=store;self.host=host;self.root=store.root
    def original(self):return read(self.root/'legacy-runtime-private.json')
    def assert_original(self):
        evidence=read(self.root/'adoption.json')
        for service,name in SERVICES.items():
            info=inspect(name)
            if info['Id']!=evidence['containerIds'][service] or config_hash(info,evidence['environmentKeys'][service])!=evidence['configurationHashes'][service]:
                raise Busy('Legacy runtime changed; take a fresh adoption capture')
        source=read(self.root/'legacy-source.json');current=Path(self.host.config['sourceRoot'])/'current'
        if current.resolve().name!=source['version']:raise Busy('Legacy source pointer changed')
        for name,digest in source['files'].items():
            if sha((current/name).read_bytes())!=digest:raise Busy('Legacy source changed')
        actual=set(command('docker','ps','--format','{{.Names}}').decode().splitlines())
        if actual!=set(evidence['permanentContainers']):raise Busy('Unknown production maintenance container')
    def prepare(self,target,owner):
        self.assert_original();source=read(self.root/'legacy-source.json');evidence=read(self.root/'adoption.json')
        if target['source']['commit']!=self.host.config['controlPlaneRevision']:raise RuntimeError('Install this candidate control-plane revision first')
        for key in ('migrations','unsupported'):
            if source['source']['inputs'][key]!=target['source']['inputs'][key]:raise RuntimeError('Initial adoption includes unsupported service or schema changes')
        if shutil.disk_usage(self.root).free<14*GIB+target['sourceUnpackedBytes']:raise RuntimeError('Insufficient adoption headroom')
        before=self.original();expected={};folder=self.root/'adoption'/owner;folder.mkdir(parents=True,exist_ok=True)
        probes={name:SERVICES[name]+'-adoption-'+owner[:12] for name in SERVICES}
        override=folder/'probe-compose.json'
        atomic(override,{'services':{name:{'container_name':probes[name],
            'image':image['repository']+'@'+image['manifestDigest'],'labels':{'org.heyi.owner':owner}}
            for name,image in target['images'].items()}})
        args=['docker','compose','--project-directory',str(self.root/'compose'),'--env-file','/dev/null','-p','heyi-adoption-'+owner[:12],
            '-f',str(self.root/'compose/base.json'),'-f',str(self.root/'compose/production.json'),'-f',str(override)]
        try:
            command(*args,'create','--no-build','--pull','never','app','frontend',timeout=60)
            for service,name in probes.items():
                info=inspect(name)
                if info['State']['Running'] or info['Config']['Labels'].get('org.heyi.owner')!=owner:raise Busy('Invalid configuration probe ownership')
                actual=configuration(info,evidence['environmentKeys'][service])
                for aliases in actual['networks'].values():
                    if name in aliases:aliases.remove(name)
                desired=configuration(before[service],evidence['environmentKeys'][service])
                desired['mounts'].append({'Type':'bind','Source':str(self.host.control),'Destination':'/run/heyi-ops','RW':False,'Propagation':'rprivate'})
                desired['mounts'].sort(key=lambda m:m['Destination'])
                if actual!=desired:
                    # Only field names are public; environment values stay local.
                    atomic(folder/'configuration-difference.json',{'service':service,'fields':[k for k in actual if actual[k]!=desired[k]]})
                    raise RuntimeError('Canonical Compose does not preserve the legacy runtime')
                expected[service]=sha(json.dumps(desired,sort_keys=True).encode())
        finally:
            for name in probes.values():
                result=command('docker','ps','-aq','--filter','name=^/'+name+'$').decode().strip()
                if result:
                    info=inspect(result)
                    if info['Config']['Labels'].get('org.heyi.owner')!=owner or info['State']['Running']:raise Busy('Refusing to remove a foreign probe')
                    command('docker','rm',info['Id'])
        return {'schemaFingerprint':self.host.schema(),'migrationInputs':target['source']['inputs']['migrations'],
            'operationsInputs':target['source']['inputs']['operations'],'environmentKeys':evidence['environmentKeys'],
            'configurationHashes':expected,'legacyVersion':source['version']}
    def legacy_activate(self):
        command('docker','compose','--project-directory',str(self.root/'compose'),'--env-file','/dev/null',
            '-p',self.host.config['composeProject'],'-f',str(self.root/'compose/base.json'),
            'up','-d','--force-recreate','--no-deps','--no-build','--pull','never','--timeout','120','app','frontend',timeout=300)
    def legacy_verify(self):
        evidence=read(self.root/'adoption.json');before=self.original()
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            try:
                code,body=self.host.request('/actuator/health')
                if code==200 and json.loads(body).get('status')=='UP':break
            except (OSError,ValueError):pass
            time.sleep(2)
        else:raise RuntimeError('Legacy health did not recover')
        for service,name in SERVICES.items():
            info=inspect(name)
            if info['Image']!=before[service]['Image'] or config_hash(info,evidence['environmentKeys'][service])!=evidence['configurationHashes'][service]:
                raise RuntimeError('Legacy rollback configuration differs')
    def run(self,request,target,expected):
        with self.store.exclusive():
            existing=read(self.store.path(request))
            if existing:
                if not existing.get('adoption') or existing['target']!=target or existing['expectedVersion']!=expected:raise ValueError('Adoption ID reused')
                if existing['phase'] in TERMINAL:return existing
                raise Busy('Recover interrupted adoption first')
            if self.store.active() or self.store.incomplete() or self.host.marker.exists():raise Busy('Control plane is already active or busy')
            if read(self.root/'legacy-source.json')['version']!=expected:raise Busy('Wrong legacy baseline')
            op={'id':request,'adoption':True,'target':target,'baseline':{'version':expected},'expectedVersion':expected,'createdAt':int(time.time())}
            self.store.save(op,'preflight')
            legacy=Legacy(self.host,self.root/'adoption'/request,request)
            try:
                contract=self.prepare(target,request)
                self.store.save(op,'gating',contract=contract)
                self.host.gate(request);legacy.ingress()
                self.store.save(op,'draining');legacy.drain()
                self.store.save(op,'switching',mayHaveSwitched=True,safePointReached=True)
                legacy.unpause()
                command('docker','stop','--timeout','120',SERVICES['app'],timeout=140)
                atomic(self.root/'baseline.json',contract)
                self.host.activate(target,['app','frontend'])
                self.store.save(op,'verifying');self.host.verify(target,maintenance=True)
                self.store.save(op,'source_sync',sourceChanging=True);self.host.sync_source(target)
                self.store.save(op,'restoring',accepted=True);self.store.set_active(target)
                legacy.release();legacy.reopen();self.host.ungate(request)
                self.host.verify(target,maintenance=False)
                return self.store.save(op,'succeeded',finishedAt=int(time.time()),switched=True)
            except Exception as error:
                self.store.save(op,'recovering',errorType=type(error).__name__)
                return self.restore(op,legacy)
    def restore(self,op,legacy):
        try:
            if op.get('mayHaveSwitched'):
                # After accepted work was reopened, require the new protocol's
                # safe point before reverting to the legacy runtime.
                if op.get('accepted'):
                    self.host.gate(op['id']);self.host.drain(op['id'])
                if self.host.schema()!=op['contract']['schemaFingerprint']:raise RuntimeError('Schema changed during adoption')
                legacy.unpause();self.legacy_activate()
            self.legacy_verify()
            if op.get('sourceChanging'):
                current=Path(self.host.config['sourceRoot'])/'current';link=current.with_name('.adoption-restore')
                target=current.parent/'releases'/op['expectedVersion']
                if not target.is_dir():raise RuntimeError('Legacy source is missing')
                link.unlink(missing_ok=True);link.symlink_to(target);link.replace(current)
            legacy.release();legacy.reopen();self.host.ungate(op['id'])
            (self.root/'active.json').unlink(missing_ok=True);(self.root/'baseline.json').unlink(missing_ok=True)
            return self.store.save(op,'rolled_back' if op.get('mayHaveSwitched') else 'aborted',finishedAt=int(time.time()))
        except Exception as error:
            self.store.save(op,'needs_attention',errorType=type(error).__name__)
            raise RuntimeError('Initial adoption needs operator recovery') from None
    def recover(self,op):
        with self.store.exclusive():
            op=read(self.store.path(op['id']))
            if op['phase'] in TERMINAL:return op
            legacy=Legacy(self.host,self.root/'adoption'/op['id'],op['id'])
            if op.get('accepted') and op['phase'] not in {'recovering','needs_attention'}:
                try:
                    self.host.verify(op['target'],maintenance=self.host.owns_gate(op['id']))
                    legacy.release();legacy.reopen();self.host.ungate(op['id'])
                    self.host.verify(op['target'],maintenance=False);self.store.set_active(op['target'])
                    return self.store.save(op,'succeeded',recovered=True,finishedAt=int(time.time()),switched=True)
                except Exception as error:self.store.save(op,'recovering',errorType=type(error).__name__)
            return self.restore(op,legacy)
