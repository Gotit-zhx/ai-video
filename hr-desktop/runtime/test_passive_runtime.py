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
        self.c.passive_delay=lambda **kwargs:0
        self.c.resume_messages=lambda result:result.get('data',{}).get('messages',[])
        self.c.cli_json.return_value={'ok':True,'data':{'sent':True}}
        self.rule={'id':'r1','jobId':'job1','jobName':'运营','enabled':True,'enabledAt':100}
        self.template={'id':'s1','version':1,'content':'{姓名}，可以沟通下{职位}。'}
        @contextlib.contextmanager
        def resume_db():
            with contextlib.closing(sqlite3.connect(self.root/'resumes.sqlite3')) as db:
                db.row_factory=sqlite3.Row
                with db:
                    db.execute('CREATE TABLE IF NOT EXISTS resume_requests(friend_id TEXT PRIMARY KEY,state TEXT,message_id TEXT)')
                    yield db
        self.c.resume_db=resume_db
        self.now=1000
        patch=mock.patch.object(flow.time,'time',side_effect=lambda:self.now);patch.start();self.addCleanup(patch.stop)

    def runop(self,op,**extra):
        return flow.execute(self.c,self.root,{'operation':op,**extra})

    def step(self,**extra):
        return self.runop('passive-step',rules=[self.rule],replyTemplate=self.template,**extra)

    def pending(self,state='inspect',friend='1',**extra):
        with flow.database(self.root) as db:
            flow.put_state(db,'discoveredAt',self.now)
            detail={'enabledAt':100,'name':'王同学','activity':900,**extra}
            db.execute("INSERT OR REPLACE INTO people VALUES(?,'r1',?,?,0,'','')",
                       (friend,json.dumps(detail),state))

    def state(self,friend='1'):
        with flow.database(self.root) as db:return db.execute('SELECT state FROM people WHERE friend=?',(friend,)).fetchone()[0]

    def messages(self,*items):
        self.c.cli_json.return_value={'ok':True,'data':{'messages':list(items)}}

    def message(self,sender='1',target='9',at=900,text='您好，想了解这个岗位'):
        return {'from':{'uid':sender},'to':{'uid':target},'time':at,
                'body':{'type':1,'text':text},'mid':'123'}

    def test_uid_direction_timestamp_and_owned_messages(self):
        self.now=1790669000
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

    def test_incoming_sends_configured_reply_then_requests_once_after_confirmation(self):
        self.pending();self.messages(self.message())
        self.assertEqual(self.step()['data']['phase'],'reply_queued')
        self.assertEqual(self.state(),'reply_ready');self.c.request_resume.assert_not_called()
        self.now+=31;self.c.cli_json.return_value={'ok':True,'data':{'sent':True}}
        self.assertEqual(self.step()['data']['phase'],'request_queued')
        self.c.cli_json.assert_called_with('hr','reply','1','王同学，可以沟通下运营。')
        self.assertEqual(self.state(),'ready')
        self.now+=31;self.step();self.c.request_resume.assert_not_called()
        self.now+=31;self.step();self.c.request_resume.assert_called_once_with('1',retry_uncertain=False)
        self.assertEqual(self.state(),'requested')
        self.now+=31;self.step();self.c.request_resume.assert_called_once()
        codes={e['code'] for e in self.runop('passive-local')['data']['events']}
        self.assertTrue({'INCOMING_DETECTED','AUTO_REPLY_SENT','RESUME_REQUESTED'}<=codes)

    def test_only_incoming_after_enable_can_trigger_and_malformed_messages_are_ignored(self):
        for items in [[self.message(at=99)],[self.message(sender='9',target='1')],[None,{},self.message(at=True)]]:
            with self.subTest(items=items):
                self.pending();self.messages(*items)
                with flow.database(self.root) as db:flow.put_state(db,'nextAt',0)
                self.step();self.assertEqual(self.state(),'seen')
        self.c.request_resume.assert_not_called()

    def test_existing_hr_reply_skips_automatic_reply(self):
        self.pending();self.messages(self.message(),self.message(sender='9',target='1',at=910,text='可以沟通下'))
        self.step();self.assertEqual(self.state(),'ready')
        self.now+=31;self.step();self.c.request_resume.assert_called_once()
        self.assertFalse(any(call.args[1]=='reply' for call in self.c.cli_json.call_args_list))

    def test_uncertain_reply_never_resends_or_requests_even_after_restart(self):
        self.pending('reply_ready');self.c.cli_json.side_effect=RuntimeError('lost response')
        self.assertEqual(self.step()['data']['phase'],'needs_review')
        self.assertEqual(self.state(),'reply_uncertain')
        self.now+=31;self.step()
        self.c.cli_json.assert_called_once();self.c.request_resume.assert_not_called()

    def test_missing_template_cannot_implicitly_send_text(self):
        self.pending('reply_ready')
        result=self.runop('passive-step',rules=[self.rule],replyTemplate=None)
        self.assertEqual(result['data']['phase'],'template_required')
        self.c.cli_json.assert_not_called();self.c.request_resume.assert_not_called()

    def test_later_incoming_requeues_seen_without_half_hour_delay(self):
        self.pending('seen',fingerprint='old')
        self.c.cli_json.return_value={'ok':True,'data':{'friendList':[{'friendId':'1','name':'王','lastTS':999000,'last_msg':'新消息'}]}}
        with flow.database(self.root) as db:flow.put_state(db,'discoveredAt',0)
        self.step();self.assertEqual(self.state(),'inspect')
        self.c.cli_json.assert_called_once_with('hr','chat','--job-id','job1','--page','1')

    def test_recent_discovery_not_starved_by_old_pending_queue(self):
        for i in range(1,28):self.pending(friend=str(i))
        with flow.database(self.root) as db:flow.put_state(db,'discoveredAt',0)
        self.c.cli_json.return_value={'ok':True,'data':{'friendList':[]}}
        self.step();self.c.cli_json.assert_called_once_with('hr','chat','--job-id','job1','--page','1')

    def test_budget_recovery_cannot_starve_queued_reply_with_list_refreshes(self):
        self.pending('reply_ready')
        with flow.database(self.root) as db:flow.put_state(db,'discoveredAt',0)
        self.c.cli_json.return_value={'ok':True,'data':{'friendList':[]}}
        self.step()  # Due list maintenance consumes this available slot.
        self.c.cli_json.assert_called_once_with('hr','chat','--job-id','job1','--page','1')
        self.c.cli_json.reset_mock()
        self.now+=31;self.c.passive_delay=lambda **kwargs:180
        self.assertEqual(self.step()['data']['phase'],'budget_wait')
        self.now+=181;self.c.passive_delay=lambda **kwargs:0
        self.c.cli_json.return_value={'ok':True,'data':{'sent':True}}
        self.assertEqual(self.step()['data']['phase'],'request_queued')
        self.c.cli_json.assert_called_once_with('hr','reply','1','王同学，可以沟通下运营。')
        self.c.request_resume.assert_not_called()

    def test_oldest_maintenance_receives_before_overdue_discovery(self):
        self.pending('requested')
        with self.c.resume_db() as db:db.execute("INSERT INTO resume_requests VALUES('1','waiting',NULL)")
        with flow.database(self.root) as db:
            flow.put_state(db,'discoveredAt',500);flow.put_state(db,'receivedAt',100)
        self.step()
        self.c.poll_resumes_once.assert_called_once()
        self.c.cli_json.assert_not_called()
        self.now+=31;self.step()
        self.c.cli_json.assert_called_once_with('hr','chat','--job-id','job1','--page','1')

    def test_newest_activity_is_inspected_first(self):
        self.pending(friend='1',activity=101);self.pending(friend='2',activity=990)
        self.messages(self.message(sender='2'));self.step()
        self.c.cli_json.assert_called_once_with('hr','chatmsg','2','--count','100')

    def test_update_time_and_unread_fallback_preserve_recent_chat_priority(self):
        self.assertEqual(flow.activity({'updateTime':1790670000000}),1790670000)
        today=flow.datetime.datetime.now()
        self.assertEqual(flow.activity({'last_time':'16:01'}),today.replace(hour=16,minute=1,second=0,microsecond=0).timestamp())
        self.assertLess(flow.activity({'last_time':'昨天'}),today.replace(hour=0,minute=0,second=0,microsecond=0).timestamp())
        self.pending(friend='1',activity=0,position=0,unread=False)
        self.pending(friend='2',activity=0,position=1,unread=True)
        self.messages(self.message(sender='2'));self.step()
        self.c.cli_json.assert_called_once_with('hr','chatmsg','2','--count','100')

    def test_explicit_recovery_is_candidate_rule_and_enable_window_scoped(self):
        self.pending();self.messages(self.message(at=90))
        with flow.database(self.root) as db:
            db.execute("INSERT INTO recovery_windows VALUES('1','r1',100,80,1000,'user approved limited recovery')")
            self.assertEqual(flow.trigger_since(db,'1',self.rule),80)
            self.assertEqual(flow.trigger_since(db,'2',self.rule),100)
            self.assertEqual(flow.trigger_since(db,'1',{**self.rule,'enabledAt':200}),200)
        self.step();self.assertEqual(self.state(),'reply_ready')
        self.c.request_resume.assert_not_called()

    def test_recovery_still_requires_candidate_message_after_authorized_cutoff(self):
        self.pending();self.messages(self.message(at=79),self.message(sender='9',target='1',at=90))
        with flow.database(self.root) as db:
            db.execute("INSERT INTO recovery_windows VALUES('1','r1',100,80,1000,'user approved limited recovery')")
        self.step();self.assertEqual(self.state(),'seen')
        self.c.request_resume.assert_not_called()

    def test_background_budget_and_clock_gate_do_not_issue_requests(self):
        self.pending('reply_ready');self.c.passive_delay=lambda **kwargs:60
        result=self.step()['data']
        self.assertEqual(result['phase'],'budget_wait');self.assertEqual(result['retryAt'],1060)
        self.now+=31;self.step()
        self.c.cli_json.assert_not_called();self.c.request_resume.assert_not_called()

    def test_queued_work_uses_reserved_capacity_without_refresh_or_limit_reset(self):
        self.pending('reply_ready')
        with flow.database(self.root) as db:flow.put_state(db,'discoveredAt',0)
        calls=[]
        def delay(**options):
            calls.append(options)
            return 0 if options['queued'] else 300
        self.c.passive_delay=delay
        self.step()
        self.c.cli_json.assert_called_once_with('hr','reply','1','王同学，可以沟通下运营。')
        self.assertTrue(any(not c['queued'] for c in calls))
        self.assertIn({'cost':3,'queued':True},calls)

    def test_budget_accounts_for_complete_operation_and_actual_expiry(self):
        path=self.root/'budget.sqlite3'
        with sqlite3.connect(path) as db:
            db.execute('CREATE TABLE requests(at REAL)')
            db.executemany('INSERT INTO requests VALUES(?)',[(500+i,) for i in range(96)])
        self.assertEqual(flow.budget_delay(path,120,cost=3,queued=True),0)
        # 96 + 5 requires one expiry to leave 20 interactive slots.
        self.assertEqual(flow.budget_delay(path,120,cost=5,queued=True),3101)
        # 96 + 2 - 82 = 16 expiries, rather than waking on the first one.
        self.assertEqual(flow.budget_delay(path,120,cost=2),3116)
        with sqlite3.connect(path) as db:self.assertEqual(db.execute('SELECT count(*) FROM requests').fetchone()[0],96)

    def test_chat_page_required_is_visible_without_marking_a_send_attempt(self):
        self.pending('reply_ready')
        self.c.prepare_recruiter_chat.side_effect=RuntimeError('请在应用的 BOSS 页面打开沟通会话后再索取，未发送请求')
        self.assertTrue(self.step()['data']['needsChat'])
        self.assertEqual(self.state(),'reply_ready');self.c.cli_json.assert_not_called()

    def test_account_events_are_durable_and_acknowledged(self):
        self.pending();self.messages(self.message());self.step()
        event=self.runop('passive-local')['data']['events'][0]
        self.assertNotIn('message',event)
        self.runop('passive-ack',eventId=event['id'])
        self.assertEqual(self.runop('passive-local')['data']['events'],[])

    def test_interrupted_request_recovers_only_local_ledger_and_never_resends(self):
        self.pending('request_uncertain')
        with self.c.resume_db() as db:db.execute("INSERT INTO resume_requests VALUES('1','waiting',NULL)")
        result=self.runop('passive-local');self.assertEqual(self.state(),'requested')
        events=result['data']['events']
        self.assertEqual([e['code'] for e in events],['RESUME_REQUESTED'])
        self.runop('passive-ack',eventId=events[0]['id'])
        self.assertFalse(self.runop('passive-local')['data']['events'])
        self.c.request_resume.assert_not_called();self.c.cli_json.assert_not_called()

if __name__=='__main__':unittest.main()
