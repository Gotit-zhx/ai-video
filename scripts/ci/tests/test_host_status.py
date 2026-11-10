import json
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from host_status import snapshot


class HostStatusTest(unittest.TestCase):
    def test_read_only_snapshot_drops_unknown_fields_and_retains_busy_state(self):
        remote = Mock()
        remote.call.return_value = {
            'protocol': 1, 'version': 'v2026.11.10.5', 'observedAt': 100,
            'maintenance': True, 'operations': [{'id': 'a'*32, 'phase': 'switching',
                                               'privateToken': 'synthetic-secret'}],
            'sourceCommit': 'b'*40, 'controlPlaneRevision': 'c'*40,
            'operationsInputs': 'd'*64, 'migrationInputs': 'e'*64,
            'schemaFingerprint': 'f'*64, 'releaseSchemas': [1, 2],
            'transferProtocols': [1, 2], 'privateEnvironment': 'synthetic-secret'}
        value = snapshot(remote)
        remote.call.assert_called_once_with('status')
        self.assertTrue(value['maintenance'])
        self.assertEqual('switching', value['operations'][0]['phase'])
        self.assertNotIn('synthetic-secret', json.dumps(value))

    def test_legacy_status_does_not_fabricate_maintenance_proof(self):
        remote = Mock()
        remote.call.return_value = {'protocol': 1, 'version': 'v1.0.0',
                                    'maintenance': False, 'operations': []}
        value = snapshot(remote)
        self.assertNotIn('operationsInputs', value)
        self.assertNotIn('observedAt', value)

    def test_null_fingerprints_are_unknown_while_valid_status_survives(self):
        remote = Mock()
        remote.call.return_value = {'protocol':1,'version':'v1.0.0','maintenance':False,'operations':[],
            'observedAt':123,'sourceCommit':'a'*40,'controlPlaneRevision':None,
            'operationsInputs':None,'migrationInputs':None,'schemaFingerprint':None,'releaseSchemas':[1,2]}
        value=snapshot(remote)
        self.assertEqual('a'*40,value['sourceCommit']);self.assertEqual(123,value['observedAt'])
        for key in ('controlPlaneRevision','operationsInputs','migrationInputs','schemaFingerprint'):
            self.assertNotIn(key,value)
        remote.call.return_value['operationsInputs']='invalid'
        with self.assertRaises(ValueError):snapshot(remote)

    def test_malformed_status_is_rejected(self):
        for value in ({}, {'protocol': 1, 'version': 'v1.0.0',
                          'maintenance': 'false', 'operations': []}):
            remote = Mock(); remote.call.return_value = value
            with self.assertRaises(ValueError):
                snapshot(remote)
