import base64
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops import transfer, oci


def descriptor(data):
    return {'digest':'sha256:'+hashlib.sha256(data).hexdigest(),'size':len(data)}


def fixture():
    # Real OCI graph, with an actual uncompressed tar layer.
    import tarfile
    layer=io.BytesIO()
    with tarfile.open(fileobj=layer,mode='w') as tar:
        entry=tarfile.TarInfo('hello.txt');entry.size=5;tar.addfile(entry,io.BytesIO(b'hello'))
    layer=layer.getvalue()
    config=json.dumps({'architecture':'amd64','os':'linux','rootfs':{'type':'layers','diff_ids':[descriptor(layer)['digest']]}}).encode()
    manifest=json.dumps({'schemaVersion':2,'mediaType':'application/vnd.oci.image.manifest.v1+json',
        'config':{**descriptor(config),'mediaType':'application/vnd.oci.image.config.v1+json'},
        'layers':[{**descriptor(layer),'mediaType':'application/vnd.oci.image.layer.v1.tar'}]}).encode()
    image={'repository':'ghcr.io/gotit-zhx/ai-video/app','manifestDigest':descriptor(manifest)['digest'],
        'configDigest':descriptor(config)['digest'],'compressedBytes':len(config)+len(layer),'unpackedBytes':len(layer)}
    metadata={descriptor(raw)['digest']:base64.b64encode(raw).decode() for raw in (manifest,config)}
    return image,metadata,{descriptor(raw)['digest']:raw for raw in (manifest,config,layer)}


class MissingBlobTest(unittest.TestCase):
    def test_only_new_layer_is_requested_and_shared_digest_is_deduplicated(self):
        old,new=descriptor(b'base'),descriptor(b'new')
        self.assertEqual([new],transfer.plan_missing([old,new,new],lambda d:io.BytesIO(b'base') if d==old['digest'] else None))
    def test_corrupt_existing_content_is_not_trusted(self):
        for corrupt in (b'evil',b'bas',b'baseX'):
            with self.subTest(corrupt=corrupt),self.assertRaises(transfer.CacheIntegrityError):
                transfer.plan_missing([descriptor(b'base')],lambda d:io.BytesIO(corrupt))
    def test_conflicting_sizes_and_invalid_digests_are_rejected(self):
        for descriptors in ([descriptor(b'x'),{**descriptor(b'x'),'size':2}],[{'digest':'../../bad','size':1}]):
            with self.assertRaises(ValueError):transfer.plan_missing(descriptors,lambda d:None)
    def test_metadata_is_verified_before_layers_and_reconstructs_exact_graph(self):
        image,metadata,blobs=fixture();root,descriptors,raw=transfer.validate_metadata(image,metadata)
        self.assertEqual(set(blobs),{d['digest'] for d in descriptors})
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp)
            for digest,data in blobs.items():(folder/digest[7:]).write_bytes(data)
            path=folder/'image.tar';transfer.reconstruct(image,root,descriptors,lambda d:(folder/d[7:]).open('rb'),path)
            oci.validate_archive(path,image)
    def test_unsigned_metadata_and_external_layers_fail_closed(self):
        image,metadata,blobs=fixture()
        with self.assertRaises(ValueError):transfer.validate_metadata(image,{**metadata,descriptor(b'other')['digest']:base64.b64encode(b'other').decode()})
        damaged=dict(metadata);damaged[image['configDigest']]=base64.b64encode(b'evil').decode()
        with self.assertRaises(ValueError):transfer.validate_metadata(image,damaged)
        manifest=json.loads(blobs[image['manifestDigest']]);manifest['layers'][0]['urls']=['https://evil.example/layer']
        raw=json.dumps(manifest).encode();metadata.pop(image['manifestDigest']);metadata[descriptor(raw)['digest']]=base64.b64encode(raw).decode()
        with self.assertRaises(ValueError):transfer.validate_metadata({**image,'manifestDigest':descriptor(raw)['digest']},metadata)
    def test_blob_upload_resumes_only_complete_verified_files(self):
        values=[b'base',b'new'];descriptors=sorted(map(descriptor,values),key=lambda d:d['digest'])
        data={descriptor(v)['digest']:v for v in values}
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp)
            with self.assertRaises(ValueError):transfer.receive_blobs(folder,io.BytesIO(data[descriptors[0]['digest']]+b'x'),descriptors,descriptors)
            self.assertEqual([descriptors[1]],transfer.plan_missing(descriptors,lambda d:(folder/d[7:]).open('rb') if (folder/d[7:]).exists() else None))
            transfer.receive_blobs(folder,io.BytesIO(data[descriptors[1]['digest']]),[descriptors[1]],[descriptors[1]])
            self.assertFalse(list(folder.glob('*.part')))
    def test_extra_unknown_duplicate_unsorted_or_wrong_size_upload_is_rejected(self):
        d=descriptor(b'blob')
        for sent,data in [([d],b'blobX'),([d],b'blo'),([d],b'evil'),([d,d],b'blobblob'),([{**d,'size':3}],b'blo'),([descriptor(b'x')],b'x')]:
            with self.subTest(sent=sent,data=data),tempfile.TemporaryDirectory() as temp:
                with self.assertRaises(ValueError):transfer.receive_blobs(Path(temp),io.BytesIO(data),sent,[d])

class PlanLifecycleTest(unittest.TestCase):
    def setUp(self):
        from heyi_ops.artifacts import Artifacts
        from heyi_ops.state import atomic
        from unittest.mock import patch
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.artifacts=Artifacts(self.root);self.plan_id='a'*32
        image,self.metadata,self.blobs=fixture()
        source=b'synthetic-source'
        self.target={'schemaVersion':1,'repository':'Gotit-zhx/ai-video','version':'v1.0.0',
            'source':{'commit':'a'*40,'tree':'b'*40,'ref':'refs/heads/release/test','inputs':{k:'f'*64 for k in ['app','frontend','unsupported','source','operations','migrations']}},
            'sourceArchiveBytes':len(source),'sourceUnpackedBytes':100,'sourceArchiveSha256':hashlib.sha256(source).hexdigest(),
            'validation':{'result':'passed','unexpectedSkips':0,'containerSmoke':'passed'},
            'images':{name:{**image,'repository':'ghcr.io/gotit-zhx/ai-video/'+name,'sourceCommit':'a'*40,'sourceRef':'refs/heads/release/test',
                'platform':'linux/amd64','assetManifestSha256':'f'*64,'smokeAssets':{'app.js':'f'*64}} for name in ('app','frontend')}}
        manifest=json.dumps(self.target).encode();metadata=json.dumps(self.metadata).encode()
        self.assets={'release.json':len(manifest),'source.tar.gz':len(source)}
        self.metadata_sizes={'app':len(metadata),'frontend':len(metadata)}
        self.frames=manifest+source+metadata+metadata
        self.cache={};self.commands=[]
        owner=self
        class Cache:
            def __init__(self,*args):pass
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def open(self,digest):return io.BytesIO(owner.cache[digest]) if digest in owner.cache else None
        def command(*args,**kwargs):
            owner.commands.append(args)
            if args[:2]==('gh','api'):
                path=args[2]
                if path.endswith('git/ref/heads/main-tree') or path.endswith('git/ref/tags/v1.0.0'):return json.dumps({'object':{'sha':'c'*40,'type':'commit'}}).encode()
                if path.endswith('git/commits/'+'c'*40):return json.dumps({'parents':[{'sha':'d'*40},{'sha':'a'*40}],'tree':{'sha':'b'*40}}).encode()
                if 'releases/tags/candidate-' in path:return json.dumps({'assets':[{'name':n,'size':s} for n,s in owner.assets.items()]}).encode()
                raise AssertionError(path)
            if args[:3]==('gh','attestation','verify'):return b'{}'
            if args[:2]==('docker','login'):return b''
            if args[:3]==('docker','image','load'):
                oci.validate_archive(args[4],owner.target['images']['app'] if 'app.' in args[4] else owner.target['images']['frontend']);owner.cache.update(owner.blobs)
                if getattr(owner,'corrupt_import',False):owner.cache[next(d for d in owner.blobs if d not in owner.metadata)]=b'corrupt'
                return b''
            raise AssertionError(args)
        for path,value in [('heyi_ops.artifacts.command',command),('heyi_ops.artifacts.require_space',lambda *a:None),
            ('heyi_ops.transfer.ContainerdCache',Cache),('heyi_ops.artifacts.production_head',lambda:'c'*40),
            ('heyi_ops.artifacts.inspect',lambda ref:{'Id':image['configDigest'],'RepoDigests':[ref],'Architecture':'amd64','Os':'linux'})]:
            p=patch(path,value);p.start();self.addCleanup(p.stop)
        atomic(self.root/'active.json',{'version':'v0.9.0'})
    def plan(self):
        return self.artifacts.prepare_plan('v1.0.0',self.plan_id,'synthetic-secret',io.BytesIO(self.frames),self.assets,self.metadata_sizes)
    def upload(self,plan,data=None):
        missing=plan['missingBlobs'];payload={'protocol':2,'planId':self.plan_id,'blobs':missing}
        return self.artifacts.stage_delta('v1.0.0',payload,io.BytesIO(b''.join(self.blobs[d['digest']] for d in missing) if data is None else data))
    def test_trusted_plan_then_full_reconstruction_preserves_signed_images(self):
        plan=self.plan();self.assertEqual('delta',plan['transferMode']);self.assertEqual(10240,plan['uploadBytes'])
        result=self.upload(plan);self.assertTrue(result['prepared']);self.assertEqual(self.target,self.artifacts.load('v1.0.0'))
        self.assertNotIn('synthetic-secret',''.join(p.read_text() for p in self.root.rglob('*.json')))
        self.assertTrue(self.artifacts.stage_delta('v1.0.0',{'protocol':2,'planId':self.plan_id,'blobs':[]},io.BytesIO())['prepared'])
        self.assertTrue(self.plan()['prepared'])
        self.assertEqual(2,len([c for c in self.commands if c[:3]==('docker','image','load')]))
    def test_cache_eviction_requires_new_missing_set(self):
        self.cache=dict(self.blobs);plan=self.plan();self.assertEqual([],plan['missingBlobs']);self.cache={}
        result=self.upload(plan);self.assertEqual('needs_replan',result['phase']);self.assertEqual(10240,sum(d['size'] for d in result['missingBlobs']))
    def test_expired_plan_or_changed_production_baseline_does_not_import(self):
        from unittest.mock import patch
        from heyi_ops.state import atomic
        plan=self.plan()
        with patch('heyi_ops.artifacts.time.time',return_value=9999999999):self.assertEqual('needs_replan',self.upload(plan)['phase'])
        atomic(self.root/'active.json',{'version':'v0.9.1'})
        self.assertEqual('needs_replan',self.upload(plan)['phase'])
        self.assertFalse(any(c[:3]==('docker','image','load') for c in self.commands))
    def test_remote_production_head_drift_does_not_import(self):
        from unittest.mock import patch
        plan=self.plan()
        with patch('heyi_ops.artifacts.production_head',return_value='e'*40):self.assertEqual('needs_replan',self.upload(plan)['phase'])
    def test_head_drift_during_receive_does_not_import(self):
        from unittest.mock import patch
        plan=self.plan()
        with patch('heyi_ops.artifacts.production_head',side_effect=['c'*40,'e'*40]):
            self.assertEqual('needs_replan',self.upload(plan)['phase'])
        self.assertFalse(any(c[:3]==('docker','image','load') for c in self.commands))

    def test_corrupt_cache_never_imports_and_classic_store_requests_full(self):
        from unittest.mock import patch
        self.cache={next(d for d in self.blobs if d not in self.metadata):b'corrupt'}
        self.assertEqual('needs_repair',self.plan()['phase']);self.cache={}
        with patch('heyi_ops.transfer.ContainerdCache',side_effect=transfer.CacheUnavailable()):self.assertEqual('full',self.plan()['transferMode'])
    def test_full_import_does_not_publish_corrupt_daemon_content(self):
        self.corrupt_import=True
        target=self.target;root,descriptors,_=transfer.validate_metadata(target['images']['app'],self.metadata)
        archives={}
        for name,image in target['images'].items():
            path=self.root/(name+'.oci.tar');transfer.reconstruct(image,root,descriptors,lambda d:io.BytesIO(self.blobs[d]),path)
            archives[name]=path.read_bytes()
        content=self.frames[:sum(self.assets.values())]+b''.join(archives[name] for name in sorted(archives))
        with self.assertRaises(transfer.CacheIntegrityError):
            self.artifacts.prepare('v1.0.0','synthetic-secret',(io.BytesIO(content),self.assets,{n:len(v) for n,v in archives.items()}))
        self.assertFalse((self.root/'releases/v1.0.0/verified.json').exists())

    def test_wrong_source_or_attestation_never_reads_cache(self):
        from unittest.mock import patch
        for frames in (self.frames.replace(b'synthetic-source',b'corrupted-source'),self.frames+b'extra'):
            with patch('heyi_ops.transfer.ContainerdCache',side_effect=AssertionError('Cache touched before trust')):
                with self.assertRaises(ValueError):self.artifacts.prepare_plan('v1.0.0',self.plan_id,'synthetic-secret',io.BytesIO(frames),self.assets,self.metadata_sizes)

class CliCapabilitiesTest(unittest.TestCase):
    def test_status_reports_real_baseline_without_inventing_maintenance_evidence(self):
        from heyi_ops.cli import status
        from heyi_ops.state import Store,atomic
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);store=Store(root,root/'lock');host=SimpleNamespace(marker=root/'marker')
            result=status(store,host)
            self.assertEqual([1,2],result['transferProtocols']);self.assertEqual([1,2],result['releaseSchemas'])
            self.assertIsNone(result['operationsInputs'])
            atomic(root/'baseline.json',{'operationsInputs':'a'*64,'migrationInputs':'b'*64,'schemaFingerprint':'c'*64})
            atomic(root/'active.json',{'version':'v1.0.0','source':{'commit':'d'*40}})
            result=status(store,host)
            self.assertEqual('a'*64,result['operationsInputs']);self.assertEqual('d'*40,result['sourceCommit'])

class TransferCleanupTest(unittest.TestCase):
    def test_only_expired_dead_owned_plans_are_removed(self):
        from heyi_ops.artifacts import Artifacts
        from heyi_ops.state import atomic
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);artifacts=Artifacts(root)
            for char,expires,pid,extra in [('a',1,1,False),('b',9999999999,1,False),('c',1,2,False),('d',1,1,True)]:
                folder=root/'staging'/('transfer-'+char*32);(folder/'blobs').mkdir(parents=True)
                atomic(folder/'plan.json',{'owner':'heyi-ops.transfer','planId':char*32,'version':'v1.0.0','expiresAt':expires,'pid':pid,'processStart':'same'})
                (folder/'blobs'/('e'*64)).write_bytes(b'test')
                if extra:(folder/'foreign-notes').write_text('another task')
            with patch('heyi_ops.artifacts.process_identity',side_effect=lambda pid:'same' if pid==2 else None):
                removed=artifacts.cleanup_staging()
            self.assertIn('transfer-'+'a'*32,removed)
            for char in 'bcd':self.assertTrue((root/'staging'/('transfer-'+char*32)).exists())
