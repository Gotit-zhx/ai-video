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
