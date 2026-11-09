import contextlib,copy,hashlib,importlib.util,json,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import Mock,patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops.host import Host
from heyi_ops.state import atomic,read

class SchemaCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.host=Host({'root':str(self.root)});self.owner='a'*32
        self.baseline={'schemaFingerprint':'after','migrationInputs':'new'}
        self.target={'source':{'inputs':{'migrations':'old'}}}
        self.receipt={'phase':'verified','operationId':self.owner,'afterSchemaFingerprint':'after',
            'migrationInputs':'new','previousMigrationInputs':'old','addedVersions':['148'],
            'historyPrefixPreserved':True,'protectedDataPreserved':True,'oldRuntimeValidationPassed':True,'oldRuntimeRestartPassed':True}
        self.path=self.root/'schema-maintenance'/self.owner/'verified.json'
        self.save()
    def save(self):
        atomic(self.path,self.receipt)
        self.baseline['schemaCompatibility']={'operationId':self.owner,'receiptSha256':hashlib.sha256(self.path.read_bytes()).hexdigest()}
        atomic(self.root/'baseline.json',self.baseline)
    def test_verified_addition_admits_old_runtime_but_not_unreviewed_migrations(self):
        with patch.object(self.host,'schema',return_value='after'):
            self.host.compatible(self.target)
            with self.assertRaises(RuntimeError):self.host.compatible({'source':{'inputs':{'migrations':'unknown'}}})
    def test_actual_history_drift_is_never_overridden(self):
        with patch.object(self.host,'schema',return_value='changed'):
            with self.assertRaises(RuntimeError):self.host.compatible(self.target)
    def test_missing_modified_or_unverified_receipt_is_rejected(self):
        self.path.unlink();self.assertFalse(self.host.additive_compatibility(self.target,self.baseline));self.save()
        self.path.write_text('{}');self.assertFalse(self.host.additive_compatibility(self.target,self.baseline))
        for field in ['historyPrefixPreserved','protectedDataPreserved','oldRuntimeValidationPassed','oldRuntimeRestartPassed']:
            original=copy.deepcopy(self.receipt);self.receipt[field]=False;self.save()
            self.assertFalse(self.host.additive_compatibility(self.target,self.baseline));self.receipt=original
        self.receipt['addedVersions']=['148','149'];self.save();self.assertFalse(self.host.additive_compatibility(self.target,self.baseline))
    def test_approval_cannot_read_arbitrary_paths_or_cross_schema_generations(self):
        self.baseline['schemaCompatibility']['operationId']='../private'
        self.assertFalse(self.host.additive_compatibility(self.target,self.baseline));self.save()
        self.receipt['afterSchemaFingerprint']='different';self.save();self.assertFalse(self.host.additive_compatibility(self.target,self.baseline))
    def test_exact_current_migration_inputs_need_no_exception(self):
        self.baseline.pop('schemaCompatibility');atomic(self.root/'baseline.json',self.baseline)
        with patch.object(self.host,'schema',return_value='after'):
            self.host.compatible({'source':{'inputs':{'migrations':'new'}}})
            with self.assertRaises(RuntimeError):self.host.compatible(self.target)
    def test_only_single_successful_v148_append_preserves_history(self):
        spec=importlib.util.spec_from_file_location('ad_schema',Path(__file__).resolve().parents[1]/'finance_ad_schema_maintenance.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        before='1\t147\tSQL\tV147__old.sql\t123\t1';after=before+'\n2\t148\tSQL\tV148__finance_ad_automation.sql\t456\t1'
        module.history_after(before,after)
        for value in [after.replace('123','321'),after.replace('456\t1','456\t0'),after+'\n3\t149\tSQL\tV149__unexpected.sql\t789\t1']:
            with self.assertRaises(RuntimeError):module.history_after(before,value)
        source=Path(__file__).resolve().parents[3]/'src/main/resources/db/migration'/module.SQL_NAME
        self.assertEqual(module.SQL_SHA,hashlib.sha256(source.read_bytes()).hexdigest())
    def test_recovery_after_ungate_does_not_replay_migration_or_reject_new_business_work(self):
        spec=importlib.util.spec_from_file_location('ad_schema_recovery',Path(__file__).resolve().parents[1]/'finance_ad_schema_maintenance.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        root=self.root/'schema-maintenance'/self.owner
        sql=Path(__file__).resolve().parents[3]/'src/main/resources/db/migration'/module.SQL_NAME
        (root/module.SQL_NAME).write_bytes(sql.read_bytes());(root/'FinanceAdSchemaMaintenance.class').write_bytes(b'synthetic-runner')
        manifest={'source':{'commit':'candidate','inputs':{'operations':'ops','migrations':'new'}}};atomic(root/'candidate-release.json',manifest)
        values={'expectedVersion':'old','candidateManifestSha256':module.sha((root/'candidate-release.json').read_bytes()),'runnerSha256':module.sha(b'synthetic-runner')};atomic(root/'inputs.json',values)
        state={'phase':'restoring','accepted':True,'inputsSha256':module.sha((root/'inputs.json').read_bytes())};atomic(root/'state.json',state)
        atomic(self.root/'baseline.json',dict(self.baseline,operationsInputs='ops'))
        config={'root':str(self.root),'lockPath':str(self.root/'lock'),'compatibilityLockPath':str(self.root/'old-lock'),'controlPlaneRevision':'candidate'}
        store=Mock();store.active.return_value={'version':'old'};store.incomplete.return_value=[];store.exclusive.side_effect=lambda:contextlib.nullcontext()
        host=Mock();host.owns_gate.return_value=False;host.sql.side_effect=AssertionError('Accepted recovery must not compare a stale business snapshot')
        def reading(path,default=None):return config if str(path)=='/etc/heyi-ops/config.json' else read(path,default)
        with patch.object(module.os,'geteuid',return_value=0),patch.object(module,'read',side_effect=reading),patch.object(module,'Store',return_value=store),patch.object(module,'Host',return_value=host),patch.object(module,'run_java') as runner:
            self.assertEqual('succeeded',module.execute(self.owner,recover=True)['phase'])
        runner.assert_not_called();host.drain.assert_not_called();host.gate.assert_not_called();host.ungate.assert_not_called()
        self.assertEqual(2,host.verify.call_count)

if __name__=='__main__':unittest.main()
