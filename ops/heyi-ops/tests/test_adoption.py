import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops.legacy import Legacy, LEASES
from heyi_ops.adoption import Adoption
from heyi_ops.state import Store, atomic, read


class Reservations(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.host=Mock()
        self.legacy=Legacy(self.host,self.root,'a'*32)
    def test_ownership_is_durable_before_any_write_and_schedules_are_untouched(self):
        def sql(query):
            self.assertEqual(self.legacy.state['lease'],read(self.root/'legacy-gate.json')['lease'])
            self.assertNotIn('next_at',query);self.assertNotIn('checkpoint',query)
            return ''
        self.host.sql.side_effect=sql;self.legacy.reserve()
        first=self.host.sql.call_args_list[0].args[0]
        self.assertEqual(len(LEASES),first.count('lease_token IS NULL AND lease_until IS NULL'))
        self.assertNotIn('lease_until<',first)
    def test_crash_recovery_releases_only_this_reservation(self):
        fresh=Legacy(self.host,self.root,'a'*32)
        self.host.sql.return_value='\n'.join(['0']*len(LEASES))
        fresh.release()
        for call in self.host.sql.call_args_list:
            self.assertEqual(len(LEASES),call.args[0].count("WHERE lease_token='"+fresh.state['lease']+"'"))
        self.assertTrue(read(self.root/'legacy-gate.json')['reservationsReleased'])
    def test_foreign_or_unknown_leases_block_the_initial_switch(self):
        self.host.sql.return_value='0\n0\n1\n0\n0'
        self.assertFalse(self.legacy.reservations_complete())
        self.host.sql.return_value='0\n0'
        self.assertFalse(self.legacy.reservations_complete())
    def test_lease_restore_failure_is_not_reported_as_restored(self):
        self.host.sql.return_value='1\n0\n0\n0\n0'
        with self.assertRaises(RuntimeError):self.legacy.release()
        self.assertNotIn('reservationsReleased',read(self.root/'legacy-gate.json'))


class InitialRecovery(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.store=Store(self.root,self.root/'lock')
        self.host=Mock();self.host.config={'sourceRoot':str(self.root/'source')};self.host.schema.return_value='schema'
        self.adoption=Adoption(self.store,self.host);self.legacy=Mock();self.request='a'*32
        self.old=self.root/'source/releases/v1.0.0';self.old.mkdir(parents=True)
        self.current=self.root/'source/current';self.current.symlink_to(self.old)
        self.op={'id':self.request,'adoption':True,'target':{'version':'v1.0.1'},'baseline':{'version':'v1.0.0'},
            'expectedVersion':'v1.0.0','contract':{'schemaFingerprint':'schema'},'mayHaveSwitched':True,'sourceChanging':True}
    def test_failed_initial_image_restores_configuration_source_and_own_reservations(self):
        with patch.object(self.adoption,'legacy_activate') as activate,patch.object(self.adoption,'legacy_verify'):
            result=self.adoption.restore(self.op,self.legacy)
        self.assertEqual('rolled_back',result['phase']);activate.assert_called_once()
        self.legacy.release.assert_called_once();self.legacy.reopen.assert_called_once()
        self.host.ungate.assert_called_once_with(self.request);self.assertEqual(self.old.resolve(),self.current.resolve())
    def test_schema_change_blocks_application_rollback_without_rewriting_history(self):
        self.host.schema.return_value='different'
        with patch.object(self.adoption,'legacy_activate') as activate:
            with self.assertRaises(RuntimeError):self.adoption.restore(self.op,self.legacy)
        activate.assert_not_called();self.host.ungate.assert_not_called()
        self.assertEqual('needs_attention',read(self.store.path(self.request))['phase'])
    def test_accepted_work_requires_a_new_safe_point_before_legacy_rollback(self):
        self.op['accepted']=True
        self.host.drain.side_effect=RuntimeError('active work')
        with patch.object(self.adoption,'legacy_activate') as activate:
            with self.assertRaises(RuntimeError):self.adoption.restore(self.op,self.legacy)
        activate.assert_not_called();self.legacy.release.assert_not_called()
