import contextlib,hashlib,importlib.util,json,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import Mock,patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops.state import atomic,read

class AdSpendSchemaMaintenanceTest(unittest.TestCase):
    def load(self):
        path=Path(__file__).resolve().parents[1]/'finance_ad_spend_schema_maintenance.py'
        self.assertTrue(path.is_file(),'V150 needs its own guarded maintenance entry')
        spec=importlib.util.spec_from_file_location('spend_schema',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
    def test_only_the_reviewed_single_v150_append_preserves_history(self):
        module=self.load();before='1\t148\tSQL\tV148__old.sql\t123\t1';after=before+'\n2\t150\tSQL\tV150__finance_ad_spend_workspace.sql\t456\t1'
        module.history_after(before,after)
        for invalid in [after.replace('123','321'),after.replace('456\t1','456\t0'),after+'\n3\t151\tSQL\tV151__unknown.sql\t789\t1',after.replace('150\tSQL','149\tSQL')]:
            with self.assertRaises(RuntimeError):module.history_after(before,invalid)
        sql=Path(__file__).resolve().parents[3]/'src/main/resources/db/migration'/module.SQL_NAME
        self.assertEqual('a8dfff7b4f3b722e137dd7bc39aebc0a8b5df9201b75a29199dc55211e7c1e13',hashlib.sha256(sql.read_bytes()).hexdigest())
        self.assertEqual(module.SQL_SHA,hashlib.sha256(sql.read_bytes()).hexdigest())
    def test_protected_state_detects_account_checkpoint_and_limit_changes_without_exposing_values(self):
        module=self.load();host=Mock();value='private-encrypted-token'
        host.sql.side_effect=lambda sql:'organization_id\nadvertiser_id' if 'information_schema.columns' in sql else value
        before=module.protected_state(host)
        self.assertEqual({'finance_ad_accounts','finance_ad_stores','finance_ad_streams','finance_sync_states','finance_api_pacing'},set(before))
        self.assertNotIn(value,json.dumps(before));value='changed-checkpoint';self.assertNotEqual(before,module.protected_state(host))
    def test_accepted_recovery_does_not_compare_stale_business_state_or_repeat_migration(self):
        module=self.load()
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);owner='a'*32;root=base/'schema-maintenance'/owner;root.mkdir(parents=True)
            sql=Path(__file__).resolve().parents[3]/'src/main/resources/db/migration'/module.SQL_NAME
            (root/module.SQL_NAME).write_bytes(sql.read_bytes());(root/'FinanceAdSpendSchemaMaintenance.class').write_bytes(b'reviewed-runner')
            atomic(root/'candidate-release.json',{'source':{'commit':'candidate','inputs':{'operations':'ops','migrations':'new'}}})
            atomic(root/'inputs.json',{'expectedVersion':'old','candidateManifestSha256':module.sha((root/'candidate-release.json').read_bytes()),'runnerSha256':module.sha(b'reviewed-runner')})
            atomic(root/'state.json',{'phase':'restoring','accepted':True,'inputsSha256':module.sha((root/'inputs.json').read_bytes())})
            atomic(base/'baseline.json',{'operationsInputs':'ops'})
            config={'root':str(base),'lockPath':str(base/'lock'),'compatibilityLockPath':str(base/'old-lock'),'controlPlaneRevision':'candidate'}
            store=Mock();store.active.return_value={'version':'old'};store.incomplete.return_value=[];store.exclusive.side_effect=lambda:contextlib.nullcontext()
            host=Mock();host.owns_gate.return_value=False;host.sql.side_effect=AssertionError('Accepted work must only revalidate runtime')
            def reading(path,default=None):return config if str(path)=='/etc/heyi-ops/config.json' else read(path,default)
            with patch.object(module.os,'geteuid',return_value=0),patch.object(module,'read',side_effect=reading),patch.object(module,'Store',return_value=store),patch.object(module,'Host',return_value=host):
                self.assertEqual('succeeded',module.execute(owner,recover=True)['phase'])
            self.assertEqual('succeeded',read(root/'state.json')['phase'])
    def test_ddl_interruption_without_history_keeps_gate_and_refuses_replaying_create(self):
        module=self.load()
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);owner='b'*32;root=base/'schema-maintenance'/owner;root.mkdir(parents=True)
            sql=Path(__file__).resolve().parents[3]/'src/main/resources/db/migration'/module.SQL_NAME
            (root/module.SQL_NAME).write_bytes(sql.read_bytes());(root/'FinanceAdSpendSchemaMaintenance.class').write_bytes(b'reviewed-runner')
            atomic(root/'candidate-release.json',{'source':{'commit':'candidate','inputs':{'operations':'ops','migrations':'new'}}})
            atomic(root/'inputs.json',{'expectedVersion':'old','appContainerId':'app','candidateManifestSha256':module.sha((root/'candidate-release.json').read_bytes()),'runnerSha256':module.sha(b'reviewed-runner')})
            before='1\t148\tSQL\tV148__old.sql\t123\t1'
            atomic(root/'state.json',{'phase':'ready-to-migrate','safePointReached':True,'beforeHistory':before,'protectedState':{'digest':'same'},'inputsSha256':module.sha((root/'inputs.json').read_bytes())})
            atomic(base/'baseline.json',{'operationsInputs':'ops','environmentKeys':{'app':[]},'configurationHashes':{'app':'config'}})
            config={'root':str(base),'lockPath':str(base/'lock'),'compatibilityLockPath':str(base/'old-lock'),'controlPlaneRevision':'candidate'}
            store=Mock();store.active.return_value={'version':'old'};store.incomplete.return_value=[];store.exclusive.side_effect=lambda:contextlib.nullcontext()
            host=Mock();host.owns_gate.return_value=True;host.sql.return_value=before
            def reading(path,default=None):return config if str(path)=='/etc/heyi-ops/config.json' else read(path,default)
            calls=[]
            def interrupted(path,operation,mode):
                calls.append(mode)
                if mode=='migrate':raise RuntimeError('DDL committed before Flyway recorded history')
                return {'success':True}
            with patch.object(module.os,'geteuid',return_value=0),patch.object(module,'read',side_effect=reading),patch.object(module,'Store',return_value=store),patch.object(module,'Host',return_value=host),patch.object(module,'inspect',return_value={'Id':'app'}),patch.object(module,'config_hash',return_value='config'),patch.object(module,'protected_state',return_value={'digest':'same'}),patch.object(module,'run_java',side_effect=interrupted):
                for _ in range(2):
                    with self.assertRaises(RuntimeError):module.execute(owner,recover=True)
                    saved=read(root/'state.json');self.assertEqual('needs_attention',saved['phase']);self.assertTrue(saved['migrationAttempted'])
            self.assertEqual(['validate','migrate'],calls,'An uncertain DDL attempt must not be replayed')
            host.ungate.assert_not_called()

if __name__=='__main__':unittest.main()
