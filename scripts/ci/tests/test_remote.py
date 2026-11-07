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
        with patch('remote.subprocess.run',return_value=SimpleNamespace(returncode=0,stdout=b'{"prepared":true}')) as command:
            self.assertTrue(self.remote.call('stage','v1.0.0',payload={'pullToken':'synthetic-secret'},assets=assets)['prepared'])
        args=command.call_args
        self.assertNotIn('synthetic-secret',' '.join(args.args[0]))
        header,content=args.kwargs['input'].split(b'\n',1)
        self.assertEqual({'release.json':2,'source.tar.gz':3},json.loads(header)['assetSizes'])
        self.assertEqual(b'{}\x1f\x8b\0',content)
    def test_assets_cannot_be_attached_to_other_commands(self):
        with self.assertRaises(ValueError):
            self.remote.call('doctor',payload={'pullToken':'synthetic'},assets={'release.json':b'{}','source.tar.gz':b'x'})
