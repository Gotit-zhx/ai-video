import contextlib
import json
from pathlib import Path
import sqlite3
import tempfile
import time
import unittest
from unittest import mock
import passive_runtime as flow


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.c=mock.Mock()
        self.c.positive_id=lambda value:str(value)
        self.c.held_code.return_value=''
        self.c.DISCONNECTED=self.root/'disconnected'
        self.c.RESUME_AUTO_PAUSE=self.root/'pause'
        self.c.cli_json.return_value={'ok':True,'data':{'sent':True}}

    def runop(self,op,**extra):
        return flow.execute(self.c,self.root,{'operation':op,**extra})

    def test_uid_direction_timestamp_and_owned_messages(self):
        now=time.time()
        def message(sender='9',recipient='1',text='你好，方便了解一下吗',timestamp=now):
            return {'from':{'uid':sender,'source':0},'to':{'uid':recipient,'source':0},'time':timestamp,
                    'body':{'type':1,'text':text}}
        incoming=message('1','9',timestamp=now-15)
        self.assertTrue(flow.human_reply([incoming,message()],'1',now-10))
        self.assertFalse(flow.human_reply([message()],'1',now-10))
        for item in [message('1','9'),message(timestamp=now-100),message(text=flow.REQUEST_TEXT),
                     message(sender=''),message(timestamp=True),message(text='自动婉拒')]:
            self.assertFalse(flow.human_reply([item],'1',now-10,['自动婉拒']))
        self.assertTrue(flow.human_reply([incoming,message(timestamp=now*1000)],'1',now-10))

    def test_write_success_is_durable_and_never_replayed(self):
        command={'id':'c1','friendId':'1','message':'感谢，暂不合适'}
        self.assertEqual(self.runop('passive-send',command=command)['data']['status'],'sent')
        self.assertEqual(self.runop('passive-send',command=command)['data']['status'],'sent')
        self.c.cli_json.assert_called_once_with('hr','reply','1','感谢，暂不合适')
        reports=self.runop('passive-local')['data']['reports']
        self.assertEqual(reports[0]['state'],'sent')
        self.runop('passive-ack',commandId='c1')
        self.assertFalse(self.runop('passive-local')['data']['reports'])

    def test_interrupted_send_stays_uncertain_after_restart(self):
        command={'id':'c1','friendId':'1','message':'感谢，暂不合适'}
        self.c.cli_json.side_effect=RuntimeError('lost response')
        with self.assertRaises(RuntimeError):self.runop('passive-send',command=command)
        self.c.cli_json.reset_mock()
        self.assertEqual(self.runop('passive-send',command=command)['data']['status'],'uncertain')
        self.c.cli_json.assert_not_called()

    def test_risk_hold_blocks_all_boss_requests(self):
        self.c.held_code.return_value='ACCOUNT_RISK'
        self.assertFalse(self.runop('passive-step',rules=[])['ok'])
        self.assertFalse(self.runop('passive-send',command={})['ok'])
        self.c.cli_json.assert_not_called();self.c.poll_resumes_once.assert_not_called()

    def test_cloud_export_retry_uses_local_file_and_ack_marks_stored(self):
        with flow.database(self.root) as db:
            db.execute("INSERT INTO people VALUES('1','r1','{}','export',0,'file.pdf','2')")
        self.assertEqual(len(self.runop('passive-local')['data']['exports']),1)
        self.assertEqual(len(self.runop('passive-local')['data']['exports']),1)
        self.c.download_resume.assert_not_called()
        self.runop('passive-ack',friendId='1',messageId='2')
        self.assertFalse(self.runop('passive-local')['data']['exports'])

    def test_disabled_rules_cannot_trigger_ready_request(self):
        with flow.database(self.root) as db:
            db.execute("INSERT INTO state VALUES('phase','0')")
            db.execute("INSERT INTO people VALUES('1','r1',?,'ready',0,'','')",(json.dumps({'enabledAt':1}),))
        self.runop('passive-step',rules=[])
        self.c.request_resume.assert_not_called();self.c.cli_json.assert_not_called()

if __name__=='__main__':unittest.main()
