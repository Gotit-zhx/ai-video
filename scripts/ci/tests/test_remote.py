import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from remote import Remote


class FailedReceipts(unittest.TestCase):
    def setUp(self):
        self.remote=Remote();self.remote.key=Path('/test/key');self.remote.hosts=Path('/test/hosts');self.remote.destination='heyi-deploy@example.test'
    def test_nonzero_gateway_exit_preserves_a_failed_operation_receipt(self):
        for phase in ('aborted','rolled_back','needs_attention'):
            value={'phase':phase,'id':'a'*32,'version':'v1.0.0'}
            with patch('remote.subprocess.run',return_value=SimpleNamespace(returncode=1,stdout=json.dumps(value).encode())):
                self.assertEqual(value,self.remote.call('receipt','a'*32))
    def test_transport_or_authorization_failure_is_not_a_successful_receipt(self):
        with patch('remote.subprocess.run',return_value=SimpleNamespace(returncode=1,stdout=b'{"error":"ValueError"}')):
            with self.assertRaises(RuntimeError):self.remote.call('receipt','a'*32)
    def test_stage_stream_frames_assets_and_keeps_credential_out_of_arguments(self):
        assets={'release.json':b'{}','source.tar.gz':b'\x1f\x8b\0'}
        with patch.object(self.remote,'_stream_call',return_value=SimpleNamespace(returncode=0,stdout=b'{"prepared":true}')) as command:
            self.assertTrue(self.remote.call('stage','v1.0.0',payload={'pullToken':'synthetic-secret'},assets=assets)['prepared'])
        args=command.call_args
        self.assertNotIn('synthetic-secret',' '.join(args.args[0]))
        header=args.args[1];content=b''.join(args.args[2])
        self.assertEqual({'release.json':2,'source.tar.gz':3},json.loads(header)['assetSizes'])
        self.assertEqual(b'{}\x1f\x8b\0',content)
    def test_assets_cannot_be_attached_to_other_commands(self):
        with self.assertRaises(ValueError):
            self.remote.call('doctor',payload={'pullToken':'synthetic'},assets={'release.json':b'{}','source.tar.gz':b'x'})
    def test_oci_frames_have_fixed_names_and_deterministic_order(self):
        assets={'frontend.oci.tar':b'front','release.json':b'{}','app.oci.tar':b'app','source.tar.gz':b'source'}
        with patch.object(self.remote,'_stream_call',return_value=SimpleNamespace(returncode=0,stdout=b'{"prepared":true}')) as command:
            self.remote.call('stage','v1.0.0',payload={'pullToken':'synthetic'},assets=assets)
        header=command.call_args.args[1];data=b''.join(command.call_args.args[2])
        self.assertEqual({'app':3,'frontend':5},json.loads(header)['imageArchives'])
        self.assertEqual(b'{}sourceappfront',data)

class StreamingTransfer(unittest.TestCase):
    def test_file_frames_stream_in_order_and_secret_stays_out_of_command(self):
        import tempfile
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);capture=root/'capture';script=root/'ssh.py'
            script.write_text('import sys\nfrom pathlib import Path\nPath(sys.argv[1]).write_bytes(sys.stdin.buffer.read())\nprint(\'{"prepared":true}\')\n')
            source=root/'source';source.write_bytes(b'x'*(2*1024**2+9))
            remote=Remote();remote.key=Path('/test/key');remote.hosts=Path('/test/hosts');remote.destination='heyi-deploy@example.test'
            with patch.object(remote,'ssh_command',return_value=[sys.executable,str(script),str(capture)]):
                self.assertTrue(remote.call('stage','v1.0.0',payload={'pullToken':'synthetic'},assets={'release.json':b'{}','source.tar.gz':source})['prepared'])
            header,data=capture.read_bytes().split(b'\n',1)
            self.assertEqual(2*1024**2+9,json.loads(header)['assetSizes']['source.tar.gz'])
            self.assertEqual(b'{}'+source.read_bytes(),data)
    def test_new_schema_cannot_fall_back_to_old_host(self):
        from remote import require_capabilities
        with self.assertRaises(ValueError):require_capabilities({'schemaVersion':2},{'protocol':1})
        self.assertEqual([1],require_capabilities({'schemaVersion':1},{'protocol':1}))

class CandidateGate(unittest.TestCase):
    def test_delta_is_explicitly_opt_in(self):
        from remote import delta_enabled
        with patch.dict('os.environ',{},clear=True):self.assertFalse(delta_enabled())
        for value,want in [('true',True),('1',False),('false',False)]:
            with patch.dict('os.environ',{'HEYI_DELTA_TRANSFER_ENABLED':value}):self.assertEqual(want,delta_enabled())
    def test_timing_artifact_reports_observed_transfer_boundaries(self):
        from remote import write_timings
        from timing import TimingRecorder
        import tempfile
        remote=Remote();remote.timings=TimingRecorder();remote.timings.add('sshUpload',15,bytes=123)
        remote.transfer_summary={'logicalBytes':200,'uploadedBlobBytes':100,'cachedBlobBytes':50,'transferMode':'delta'}
        remote.upload_finished_at=1000;remote.response_received_at=1005
        with tempfile.TemporaryDirectory() as temp:
            path=write_timings(remote,Path(temp))
            result=json.loads(path.read_text())
        self.assertEqual(123,result['uploadedBytes']);self.assertEqual(1000,result['uploadFinishedAtMs']);self.assertEqual(1005,result['responseReceivedAtMs'])

class DeltaNegotiation(unittest.TestCase):
    def test_candidate_uploads_only_signed_missing_layer_from_files(self):
        import contextlib
        import hashlib
        import os
        import tempfile
        from remote import stage_candidate
        from timing import TimingRecorder
        sys.path.insert(0,str(Path(__file__).resolve().parents[3]/'ops/heyi-ops/tests'))
        from test_transfer import fixture
        image,metadata,contents=fixture();layer=next(d for d in contents if d not in metadata)
        source=b'source'
        target={'schemaVersion':1,'repository':'Gotit-zhx/ai-video','version':'v1.0.0',
            'source':{'commit':'a'*40,'tree':'b'*40,'ref':'refs/heads/release/test','inputs':{k:'f'*64 for k in ['app','frontend','unsupported','source','operations','migrations']}},
            'sourceArchiveBytes':6,'sourceUnpackedBytes':100,'sourceArchiveSha256':hashlib.sha256(source).hexdigest(),
            'validation':{'result':'passed','unexpectedSkips':0,'containerSmoke':'passed'},
            'images':{name:{**image,'repository':'ghcr.io/gotit-zhx/ai-video/'+name,'sourceCommit':'a'*40,'sourceRef':'refs/heads/release/test',
                'platform':'linux/amd64','assetManifestSha256':'f'*64,'smokeAssets':{'app.js':'f'*64}} for name in ('app','frontend')}}
        calls=[];downloaded=[];plan_id='a'*32
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);manifest=root/'target/promotion/release.json';manifest.parent.mkdir(parents=True);manifest.write_text(json.dumps(target))
            source_path=root/'source.tar.gz';source_path.write_bytes(source)
            @contextlib.contextmanager
            def assets(*args,**kwargs):yield {'release.json':manifest,'source.tar.gz':source_path}
            def download(image,descriptors,folder,token):
                downloaded.extend(descriptors)
                for d in descriptors:(folder/d['digest'][7:]).write_bytes(contents[d['digest']])
            class Peer:
                timings=TimingRecorder()
                def call(self,*args,**kwargs):
                    calls.append(args[0])
                    if args[0]=='stage-plan':
                        assert all(isinstance(path,Path) for path in kwargs['assets'].values())
                        return {'protocol':2,'planId':plan_id,'version':'v1.0.0','manifestSha256':hashlib.sha256(manifest.read_bytes()).hexdigest(),
                            'transferMode':'delta','cachedBlobBytes':0,'missingBlobs':[{'digest':layer,'size':10240}]}
                    assert args[0]=='stage'
                    assert kwargs['blobs'][layer].read_bytes()==contents[layer]
                    return {'prepared':True}
            previous=os.getcwd()
            try:
                os.chdir(root)
                with patch('remote.candidate_assets',assets),patch('heyi_ops.oci.export_metadata',return_value=metadata),patch('heyi_ops.oci.export_blobs',download),patch.dict(os.environ,{'GH_TOKEN':'synthetic','HEYI_DELTA_TRANSFER_ENABLED':'true'}):
                    result=stage_candidate('v1.0.0',Peer(),plan_id,{'releaseSchemas':[1,2],'transferProtocols':[1,2]})
            finally:os.chdir(previous)
        self.assertTrue(result['prepared']);self.assertEqual(['stage-plan','stage'],calls)
        self.assertEqual([{'digest':layer,'size':10240}],downloaded)
