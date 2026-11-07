import copy
import io
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops import cli, monitor
from heyi_ops.state import Store, atomic, read, Busy


class ControlTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.store=Store(self.root,self.root/'lock')
        self.old={"version":"v1.0.0","images":{k:{"configDigest":"old"} for k in ('app','frontend')}}
        self.new={"version":"v1.0.1","images":{k:{"configDigest":"new"} for k in ('app','frontend')}}
        self.store.set_active(self.old);self.host=Mock();self.host.marker=self.root/'control/maintenance.json'
        self.artifacts=Mock();self.artifacts.load.side_effect=lambda v:copy.deepcopy(self.old if v==self.old['version'] else self.new)
        self.config={"root":str(self.root),"sourceRoot":str(self.root/'source'),"selfHealingEnabled":True,
            "permanentContainers":['ai-commerce-app','ai-commerce-frontend','mysql'],"backupRoot":str(self.root/'backup'),"certificate":"test-cert"}
        self.patcher=patch.object(cli,'context',return_value=(self.config,self.store,self.host,self.artifacts));self.patcher.start();self.addCleanup(self.patcher.stop)
        self.request='a'*32
    def queue(self):
        with patch.object(cli,'command') as command:
            result=cli.dispatch(['deploy','v1.0.1','v1.0.0',self.request],'deploy')
        return result,command
    def test_ssh_submission_is_durable_before_systemd_start_and_duplicate_safe(self):
        def start(*args):self.assertTrue((self.root/'requests'/(self.request+'.json')).is_file())
        with patch.object(cli,'command',side_effect=start):cli.dispatch(['deploy','v1.0.1','v1.0.0',self.request],'deploy')
        first=read(self.root/'requests'/(self.request+'.json'))
        result,_=self.queue();self.assertEqual('queued',result['phase'])
        self.assertEqual(first,read(self.root/'requests'/(self.request+'.json')))
        self.assertEqual('queued',cli.dispatch(['receipt',self.request],'monitoring')['phase'])
    def test_prejournal_rejection_is_visible_in_receipt(self):
        self.queue()
        with patch.object(cli.Engine,'deploy',side_effect=Busy('another operation')):
            result=cli.dispatch(['execute',self.request])
        self.assertEqual('aborted',result['phase']);self.assertEqual('Busy',result['errorType'])
    def test_finalize_records_process_death_before_first_journal(self):
        self.queue();result=cli.dispatch(['finalize',self.request])
        self.assertEqual('aborted',result['receipt']['phase'])
        self.assertEqual('ExecutorInterruptedBeforeJournal',result['receipt']['errorType'])
    def test_boot_recovers_only_inactive_orphan_requests(self):
        self.queue()
        with patch.object(cli.Engine,'recover',return_value=[]),patch.object(cli,'command',return_value=b'activating\n'):
            self.assertEqual([],cli.dispatch(['recover']))
        with patch.object(cli.Engine,'recover',return_value=[]),patch.object(cli,'command',return_value=b'inactive\n'):
            self.assertEqual('aborted',cli.dispatch(['recover'])[0]['phase'])
    def test_monitor_cannot_change_runtime_or_read_arbitrary_paths(self):
        for args in [['deploy','v1.0.1','v1.0.0',self.request],['cleanup'],['execute',self.request],['receipt','../private']]:
            with self.assertRaises(ValueError):cli.dispatch(args,'monitoring')
    def test_stage_header_is_bounded_and_rejects_unadvertised_fields(self):
        for payload in ({'pullToken':'synthetic','path':'/etc/private'},
                        {'pullToken':'synthetic','assetSizes':{'release.json':1,'source.tar.gz':513*1024**2}},
                        {'pullToken':'synthetic','assetSizes':{'release.json':True,'source.tar.gz':1}}):
            with patch.object(cli.sys,'stdin',SimpleNamespace(buffer=io.BytesIO(json.dumps(payload).encode()+b'\n'))):
                with self.assertRaises(ValueError):cli.dispatch(['stage','v1.0.1'],'deploy')
        self.artifacts.prepare.assert_not_called()
    def test_stage_upload_keeps_binary_stream_after_the_header(self):
        self.artifacts.prepare.return_value=self.new
        payload={'pullToken':'synthetic','assetSizes':{'release.json':2,'source.tar.gz':3}}
        stream=io.BytesIO(json.dumps(payload).encode()+b'\n{}abc')
        with patch.object(cli.sys,'stdin',SimpleNamespace(buffer=stream)):
            self.assertTrue(cli.dispatch(['stage','v1.0.1'],'deploy')['prepared'])
        self.assertEqual(b'{}abc',stream.read())
        self.assertEqual((stream,payload['assetSizes']),self.artifacts.prepare.call_args.kwargs['upload'])
    def test_final_receipt_cannot_be_reused_for_rollback(self):
        self.store.save({'id':self.request,'target':self.new,'expectedVersion':'v1.0.0','rollback':False},'succeeded')
        with self.assertRaises(ValueError):cli.dispatch(['rollback','v1.0.1','v1.0.0',self.request],'deploy')
    def snapshot(self,healthy=False,workers=None):
        return {'appHealthy':healthy,'maintenance':False,'plannedMaintenance':False,'activeWorkers':{'a':0} if workers is None else workers,
                'alerts':[]}
    def test_failed_worker_probe_and_backlog_do_not_authorize_restart(self):
        for workers in ({},{'a':1}):
            atomic(self.root/'monitor-state.json',{'consecutiveFailures':3})
            with patch.object(monitor,'diagnose',return_value=self.snapshot(workers=workers)),patch.object(monitor,'command') as command:
                monitor._monitor(self.config,self.store,self.host);command.assert_not_called()
    def test_only_one_recovery_attempt_and_thirty_minute_cooldown(self):
        atomic(self.root/'monitor-state.json',{'consecutiveFailures':2})
        with patch.object(monitor,'diagnose',return_value=self.snapshot()),patch.object(monitor,'command') as command:
            self.assertEqual('queued',monitor._monitor(self.config,self.store,self.host)['selfHealing'])
            monitor._monitor(self.config,self.store,self.host);self.assertEqual(1,command.call_count)
        with patch.object(monitor,'diagnose',return_value=self.snapshot(healthy=True)):
            monitor._monitor(self.config,self.store,self.host)
        with patch.object(monitor,'diagnose',return_value=self.snapshot()),patch.object(monitor,'command') as command:
            for _ in range(5):monitor._monitor(self.config,self.store,self.host)
            command.assert_not_called()
    def test_planned_switch_does_not_reset_or_increment_incident(self):
        atomic(self.root/'monitor-state.json',{'consecutiveFailures':2,'attempted':True})
        snapshot={**self.snapshot(),'plannedMaintenance':True}
        with patch.object(monitor,'diagnose',return_value=snapshot),patch.object(monitor,'command') as command:
            monitor._monitor(self.config,self.store,self.host);command.assert_not_called()
        self.assertEqual(2,read(self.root/'monitor-state.json')['consecutiveFailures'])
        self.assertTrue(read(self.root/'monitor-state.json')['attempted'])
    def test_cleanup_retains_distinct_verified_images_across_docs_versions(self):
        for n,image in enumerate(['older','middle','newest','newest','newest']):
            target={'version':'v1.0.'+str(n),'images':{k:{'configDigest':image} for k in ('app','frontend')}}
            self.store.save({'id':format(n,'032x'),'target':target,'finishedAt':n},'succeeded')
        self.store.set_active(target)
        self.assertEqual(['v1.0.4','v1.0.1','v1.0.0','v1.0.3','v1.0.2'],monitor.retained_versions(self.store))
    def test_cleanup_needs_accepted_baseline_and_respects_incomplete_work(self):
        self.store.save({'id':self.request,'target':self.new},'draining')
        with self.assertRaises(Busy):monitor.cleanup(self.config,self.store)


if __name__=='__main__':unittest.main()
