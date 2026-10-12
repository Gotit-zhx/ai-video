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
        self.c.managed_job_statuses.return_value={'statuses':{'job1':'开放中'}}
        self.c.conversation_scope=lambda friend,job:contextlib.nullcontext()
        self.c.conversation_scope_error.return_value=False
        self.c.resume_messages=lambda result:result.get('data',{}).get('messages',[])
        self.c.adopt_resume_request.return_value=False
        self.c.sourcing_friend_details.return_value={'ok':True,'data':{
            'friendList':[{'uid':'1','encryptJobId':'job1'}]}}
        self.c.cli_json.return_value={'ok':True,'data':{'sent':True}}
        self.rule={'id':'r1','jobId':'job1','jobName':'运营','enabled':True,'enabledAt':100,
                   'historyBackfill':False}
        self.template={'id':'s1','version':1,'content':'{姓名}，可以沟通下{职位}。'}
        @contextlib.contextmanager
        def resume_db():
            with contextlib.closing(sqlite3.connect(self.root/'resumes.sqlite3')) as db:
                db.row_factory=sqlite3.Row
                with db:
                    db.execute('CREATE TABLE IF NOT EXISTS resume_requests(friend_id TEXT PRIMARY KEY,state TEXT,message_id TEXT)')
                    db.execute('CREATE TABLE IF NOT EXISTS resume_poll_state(friend_id TEXT PRIMARY KEY,last_polled_at INTEGER,failures INTEGER)')
                    db.execute('CREATE TABLE IF NOT EXISTS resume_files(friend_id TEXT,message_id TEXT,original_name TEXT)')
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
            detail={'enabledAt':100,'jobId':'job1','name':'王同学','activity':900,**extra}
            db.execute("INSERT OR REPLACE INTO people VALUES(?,'r1',?,?,0,'','')",
                       (friend,json.dumps(detail),state))

    def state(self,friend='1'):
        with flow.database(self.root) as db:return db.execute('SELECT state FROM people WHERE friend=?',(friend,)).fetchone()[0]

    def messages(self,*items):
        self.c.cli_json.return_value={'ok':True,'data':{'messages':list(items)}}

    def message(self,sender='1',target='9',at=900,text='您好，想了解这个岗位'):
        return {'from':{'uid':sender},'to':{'uid':target},'time':at,
                'body':{'type':1,'text':text},'mid':'123'}

    def hint(self,friend='1',mid='200'):
        return {'friend':friend,'mid':mid,'job':'job1','at':self.now}

    def test_only_official_open_jobs_can_inspect_reply_or_request(self):
        for status in ('待开放', '已失效', '已关闭', '审核中', '免费曝光中', '状态待确认', None):
            for state in ('inspect', 'reply_ready', 'ready'):
                with self.subTest(status=status, state=state):
                    self.pending(state)
                    self.now += 31
                    self.c.managed_job_statuses.return_value = {'statuses': {'job1': status} if status else {}}
                    self.step(activityHints=[self.hint(mid=str(int(self.now)))])
                    self.assertEqual(self.state(), state, 'Keep the opted-in queue for reopening')
        self.c.cli_json.assert_not_called()
        self.c.request_resume.assert_not_called()

    def test_receipts_for_closed_jobs_continue_without_new_writes(self):
        self.c.managed_job_statuses.return_value = {'statuses': {'job1': '已关闭'}}
        self.pending('requested')
        with self.c.resume_db() as db:
            db.execute("INSERT INTO resume_requests VALUES('1','waiting',NULL)")
        def received(**kwargs):
            with self.c.resume_db() as db:
                db.execute("UPDATE resume_requests SET state='received',message_id='201' WHERE friend_id='1'")
        self.c.poll_resumes_once.side_effect = received
        self.c.download_resume.return_value = self.root / 'received.pdf'
        self.step(activityHints=[self.hint()])
        self.assertEqual(self.state(), 'export')
        self.c.cli_json.assert_not_called()
        self.c.request_resume.assert_not_called()

    def test_reopening_preserves_request_ledger_and_does_not_request_twice(self):
        self.pending('ready')
        with self.c.resume_db() as db:
            db.execute("INSERT INTO resume_requests VALUES('1','waiting',NULL)")
        self.c.managed_job_statuses.return_value = {'statuses': {'job1': '待开放'}}
        self.step()
        self.assertEqual(self.state(), 'ready')
        self.now += 31
        self.c.managed_job_statuses.return_value = {'statuses': {'job1': '开放中'}}
        self.step()
        self.assertEqual(self.state(), 'requested')
        self.c.request_resume.assert_not_called()
        self.c.cli_json.assert_not_called()

    def test_job_refresh_risk_stops_receipt_and_all_writes(self):
        self.pending('ready')
        def refresh(**kwargs):
            self.c.held_code.return_value = 'ACCOUNT_RISK'
            return {'statuses': {}}
        self.c.managed_job_statuses.side_effect = refresh
        self.assertEqual(self.step()['error']['code'], 'ACCOUNT_RISK')
        self.c.cli_json.assert_not_called()
        self.c.poll_resumes_once.assert_not_called()
        self.c.request_resume.assert_not_called()

    def test_pending_reply_precedes_all_conversation_backfill(self):
        self.pending('reply_ready')
        with mock.patch('boss_chat_receipts.step') as collect:
            self.step(collectAllResumes=True)
            collect.assert_not_called()
        self.c.cli_json.assert_called_once_with('hr', 'reply', '1', '王同学，可以沟通下运营。')
        self.assertEqual(self.state(), 'ready')

    def test_new_open_job_message_precedes_collection_and_keeps_its_durable_signal(self):
        with mock.patch('boss_chat_receipts.step') as collect:
            self.step(collectAllResumes=True, activityHints=[self.hint()])
            collect.assert_not_called()
        self.assertTrue(any(call.args[:2] == ('hr','chat') for call in self.c.cli_json.call_args_list))
        with flow.database(self.root) as db:
            row=db.execute("SELECT * FROM activity WHERE friend='1'").fetchone()
            self.assertEqual(row['mid'],'200')
        self.c.managed_job_statuses.assert_called_with(queued=True)

    def test_monitoring_new_conversations_uses_the_flow_reserve_before_a_reply_exists(self):
        self.c.passive_delay=lambda **options: 0 if options.get('queued') else 3600
        self.step()
        self.c.managed_job_statuses.assert_called_with(queued=True)
        self.assertTrue(any(call.args[:2] == ('hr','chat') for call in self.c.cli_json.call_args_list))

    def test_collection_cannot_acknowledge_a_signal_without_saving_it_for_the_flow(self):
        hint={**self.hint(), 'job':''}
        with mock.patch('boss_chat_receipts.step', return_value={'ok':True}) as collect:
            value=self.step(collectAllResumes=True, activityHints=[hint])
            collect.assert_called_once()
        self.assertEqual(value['data']['activityPending'],1)
        with flow.database(self.root) as db:
            row=db.execute("SELECT * FROM activity WHERE friend='1'").fetchone()
            self.assertEqual(row['done'],0)
            self.assertEqual(row['mid'],'200')

    def test_closed_job_pending_reply_does_not_block_receive_only_collection(self):
        self.pending('reply_ready')
        self.c.managed_job_statuses.return_value = {'statuses': {'job1': '已关闭'}}
        with mock.patch('boss_chat_receipts.step', return_value={'ok': True}) as collect:
            self.step(collectAllResumes=True)
            collect.assert_called_once()
        self.c.cli_json.assert_not_called()
        self.assertEqual(self.state(), 'reply_ready')

    def test_collector_status_cannot_hide_an_admitted_reply_behind_stale_budget_deadline(self):
        self.pending('reply_ready')
        with flow.database(self.root) as db:
            flow.put_state(db, 'nextAt', self.now+3600)
            flow.put_state(db, 'status', json.dumps({'phase': 'collecting_resumes'}))
        self.step()
        self.assertEqual(self.state(), 'ready')
        self.c.cli_json.assert_called_once()

    def test_progress_is_scoped_to_the_job_that_actually_requested(self):
        self.pending('requested',friend='1')
        self.pending('seen',friend='2')
        with flow.database(self.root) as db:
            db.execute("UPDATE people SET rule='other',updated=900 WHERE friend='1'")
            value=flow.status(db,'receiving')['data']
        self.assertEqual(value['progressByRule'],{'other':900})
        self.assertEqual(value['counts']['r1'],{'seen':1})

    def test_discovery_does_not_relabel_another_jobs_row_or_existing_queue(self):
        self.pending('ready', friend='1', replyAt=900)
        self.c.cli_json.return_value={'ok':True,'data':{'result':[
            {'uid':'1','encryptJobId':'job2'},
            {'uid':'2','encryptJobId':'job1'}]}}
        other={**self.rule,'id':'r2','jobId':'job2'}
        with flow.database(self.root) as db:
            flow.discover(self.c,db,{'r2':other},rule=other)
            saved=db.execute('SELECT * FROM people WHERE friend=?',('1',)).fetchone()
            self.assertEqual(saved['rule'],'r1')
            self.assertEqual(json.loads(saved['body'])['jobId'],'job1')
            self.assertEqual(saved['state'],'ready')
            self.assertIsNone(db.execute("SELECT 1 FROM people WHERE friend='2'").fetchone())
        self.c.request_resume.assert_not_called()

    def test_wrong_job_activity_cannot_wake_an_existing_conversation(self):
        self.pending('seen')
        self.step(activityHints=[{**self.hint(),'job':'job2'}])
        self.assertEqual(self.state(),'seen')
        self.c.cli_json.assert_not_called()
        self.c.request_resume.assert_not_called()
        with flow.database(self.root) as db:
            self.assertEqual(db.execute('SELECT done FROM activity').fetchone()[0],1)

    def test_pending_with_wrong_job_is_quarantined_before_any_platform_operation(self):
        self.pending('reply_ready',jobId='other')
        self.assertEqual(self.step()['data']['phase'],'job_review')
        self.assertEqual(self.state(),'job_review')
        self.c.cli_json.assert_not_called()
        self.c.prepare_recruiter_chat.assert_not_called()
        self.c.request_resume.assert_not_called()
        self.now+=31
        self.assertEqual(self.step()['data']['phase'],'job_review')

    def test_official_friend_detail_scope_blocks_reply_and_resume_and_never_replays(self):
        import conversation_scope
        for stage in ('reply_ready','ready'):
            with self.subTest(stage=stage):
                self.pending(stage)
                @contextlib.contextmanager
                def scope(friend,job):
                    self.assertEqual((friend,job),('1','job1'))
                    self.c.conversation_scope_error.return_value=True
                    raise conversation_scope.ScopeError('different official job')
                    yield
                self.c.conversation_scope=scope
                self.now+=61
                self.assertEqual(self.step()['data']['phase'],'job_review')
                self.assertEqual(self.state(),'job_review')
                self.c.cli_json.assert_not_called()
                self.c.request_resume.assert_not_called()
                self.now+=31
                self.step()
                self.c.cli_json.assert_not_called()
                self.c.request_resume.assert_not_called()
        codes={e['code'] for e in self.runop('passive-local')['data']['events']}
        self.assertIn('JOB_CONVERSATION_MISMATCH',codes)
        self.assertNotIn('AUTO_REPLY_SENT',codes)
        self.assertNotIn('RESUME_REQUESTED',codes)

    def test_incoming_during_queue_is_discovered_without_waiting_for_periodic_scan(self):
        self.pending('ready')
        with flow.database(self.root) as db:
            flow.put_state(db,'nextAt',self.now+30)
            flow.put_state(db,'lastLane','maintenance')
        self.c.cli_json.return_value={'ok':True,'data':[{'friendId':'2','name':'新候选人','lastTS':1000}]}
        self.step(activityHints=[self.hint('2')])
        self.c.cli_json.assert_called_once_with('hr','chat','--job-id','job1','--page','1')
        self.assertEqual(self.state('2'),'inspect')
        self.c.request_resume.assert_not_called()
        self.step(activityHints=[self.hint('2')])
        self.assertEqual(self.c.cli_json.call_count,1,'The same incoming MID is durable and not re-read')

    def test_reconnect_receives_offline_attachment_without_resending_request(self):
        self.pending('requested')
        with self.c.resume_db() as db:
            db.execute("INSERT INTO resume_requests VALUES('1','waiting',NULL)")
        def receive(**options):
            self.assertEqual(options,{'friend_id':'1','reconnect':True})
            with self.c.resume_db() as db:
                db.execute("UPDATE resume_requests SET state='received',message_id='900'")
            return {'checked':True,'more':False}
        self.c.poll_resumes_once.side_effect=receive
        self.c.download_resume.return_value=self.root/'received.pdf'
        result=self.step(reconnectId='login-1')
        self.assertEqual(self.state(),'export')
        self.assertEqual(result['data']['exportsPending'],1)
        self.c.request_resume.assert_not_called()
        self.c.cli_json.assert_not_called()
        self.runop('passive-ack',friendId='1',messageId='900')
        self.now+=31;self.step(reconnectId='login-2')
        self.assertEqual(self.c.poll_resumes_once.call_count,1,'Already stored resumes are not fetched again after relogin')

    def test_reconnect_pages_through_read_greetings_and_preserves_cursor(self):
        self.pending('stored',friend='9')
        pages=[
            {'ok':True,'data':{'friendList':[{'friendId':'1','lastTS':950,'unread':0},
                                            {'friendId':'9','lastTS':940,'unread':0}], 'hasMore':True}},
            {'ok':True,'data':{'friendList':[{'friendId':'2','lastTS':900,'unread':0}], 'hasMore':False}},
        ]
        self.c.cli_json.side_effect=pages
        self.step(reconnectId='login-1')
        self.assertEqual(self.state('1'),'inspect')
        self.assertEqual(self.state('9'),'stored')
        with flow.database(self.root) as db:
            flow.prepare_catchup(self.c,db,{'reconnectId':'login-1'},{'r1':self.rule})
            self.assertEqual(db.execute("SELECT page FROM catchup WHERE kind='job'").fetchone()[0],2)
            self.now+=31
            flow.catchup_step(self.c,db,{'r1':self.rule},lambda **kw:0)
            self.assertEqual(db.execute("SELECT done FROM catchup WHERE kind='job'").fetchone()[0],1)
            self.assertEqual(flow.get_state(db,'discovery-complete:r1'),'1000.0')
        self.assertEqual(self.state('2'),'inspect','Read-on-phone greetings are still discovered')
        self.assertEqual([call.args[-1] for call in self.c.cli_json.call_args_list],['1','2'])
        self.c.request_resume.assert_not_called()

    def test_catchup_budget_wait_keeps_queue_and_disabled_jobs_are_not_read(self):
        self.c.passive_delay=lambda **kw:120
        result=self.step(reconnectId='login-1')
        self.assertEqual(result['data']['catchupPending'],1)
        self.c.cli_json.assert_not_called()
        self.now+=121;self.c.passive_delay=lambda **kw:0
        self.runop('passive-step',reconnectId='login-1',rules=[])
        self.c.cli_json.assert_not_called()

    def test_full_filter_index_finishes_without_requesting_the_same_page_again(self):
        self.c.cli_json.return_value={'ok':True,'data':{'result':[
            {'uid':'1','lastTS':950}, {'uid':'2','lastTS':940}]}}
        self.step(reconnectId='full-index')
        with flow.database(self.root) as db:
            self.assertEqual(db.execute("SELECT done FROM catchup WHERE kind='job'").fetchone()[0],1)
        self.assertFalse(flow.discovery_complete({'data':{'friendList':[{'uid':'1'}]}}))
        self.assertFalse(flow.discovery_complete({'data':{'result':[{'uid':'1'}],'hasMore':True}}))
        self.assertFalse(flow.discovery_complete({'data':{'result':[{'uid':'1'}],'page':1}}))
        self.c.request_resume.assert_not_called()

    def test_local_realtime_horizon_does_not_limit_reconnect_history(self):
        self.c.cli_json.return_value={'ok':True,'data':{'friendList':[
            {'friendId':'1','lastTS':500,'unread':0}], 'hasMore':False}}
        self.step(reconnectId='login-1',activityWatching=True)
        self.assertEqual(self.state('1'),'inspect')
        self.c.request_resume.assert_not_called()

    def test_candidate_already_sent_resume_during_disconnect_does_not_get_another_request(self):
        self.pending('inspect')
        self.c.adopt_received_resume.return_value=True
        result=self.step()
        self.assertEqual(self.state(),'requested')
        self.assertEqual(result['data']['catchupPending'],1)
        self.c.request_resume.assert_not_called()
        self.c.cli_json.assert_called_once_with('hr','chatmsg','1','--count','100')

    def test_new_resume_targets_its_own_conversation_before_other_queued_work(self):
        self.pending('requested')
        self.pending('ready',friend='2')
        with self.c.resume_db() as db:
            db.execute("INSERT INTO resume_requests VALUES('1','waiting',NULL)")
        self.c.download_resume.return_value=self.root/'received.pdf'
        def received(**kwargs):
            self.assertEqual(kwargs,{'friend_id':'1'})
            with self.c.resume_db() as db:
                db.execute("UPDATE resume_requests SET state='received',message_id='201' WHERE friend_id='1'")
        self.c.poll_resumes_once.side_effect=received
        result=self.step(activityHints=[self.hint()])
        self.c.poll_resumes_once.assert_called_once_with(friend_id='1')
        self.c.download_resume.assert_called_once_with('1','201')
        self.c.request_resume.assert_not_called()
        self.assertEqual(self.state(),'export')
        self.assertEqual(result['data']['exportsPending'],1)
        self.assertEqual(result['data']['activityPending'],0)

    def test_incoming_resume_waits_for_short_read_interval_without_losing_signal(self):
        self.pending('requested')
        with self.c.resume_db() as db:
            db.execute("INSERT INTO resume_requests VALUES('1','waiting',NULL)")
            db.execute("INSERT INTO resume_poll_state VALUES('1',990,0)")
        with flow.database(self.root) as db:flow.put_state(db,'receivedAt',self.now)
        result=self.step(activityHints=[self.hint()])
        self.assertEqual(result['data']['activityPending'],1)
        self.assertEqual(result['data']['activityRetryAt'],1020)
        self.c.poll_resumes_once.assert_not_called()
        self.now=1020;self.step()
        self.c.poll_resumes_once.assert_called_once_with(friend_id='1')

    def test_activity_budget_hold_and_disabled_rules_never_trigger_a_write(self):
        self.c.passive_delay=lambda **kwargs:60
        result=self.step(activityHints=[self.hint()])
        self.assertEqual(result['data']['activityPending'],1)
        self.c.cli_json.assert_not_called()
        self.now+=61
        self.runop('passive-step',rules=[])
        self.c.cli_json.assert_not_called()
        self.c.request_resume.assert_not_called()

    def test_local_observation_reduces_sweeps_and_restores_fallback_when_unavailable(self):
        with flow.database(self.root) as db:flow.put_state(db,'discoveredAt',850)
        self.step(activityWatching=True)
        self.c.cli_json.assert_not_called()
        self.now+=31
        self.step(activityWatching=False)
        self.c.cli_json.assert_called_once_with('hr','chat','--job-id','job1','--page','1')

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

    def test_unreadable_history_is_isolated_without_sending_or_blocking_next_contact(self):
        self.pending(friend='1', activity=990)
        self.pending(friend='2', activity=900)
        self.c.cli_json.return_value = {'ok': False, 'error': {'code': 'UNKNOWN'}}
        first = self.step()
        self.assertTrue(first['ok'])
        self.assertEqual(first['data']['phase'], 'conversation_review')
        self.assertEqual(self.state('1'), 'inspect_review')
        self.c.request_resume.assert_not_called()
        self.now += 31
        self.messages(self.message(sender='2'))
        self.c.adopt_received_resume.return_value = False
        self.step()
        self.assertEqual(self.c.cli_json.call_args.args[2], '2')
        self.assertEqual(self.state('2'), 'reply_ready')
        self.assertEqual(self.state('1'), 'inspect_review')
        self.assertEqual(self.c.cli_json.call_count, 2)

    def test_rejected_history_is_not_retried_by_unchanged_list_refresh(self):
        self.pending(friend='1', activity=900, fingerprint='old')
        self.c.cli_json.return_value = {'ok': False, 'error': {'code': 'UNKNOWN'}}
        self.step()
        self.c.cli_json.return_value = {'ok': True, 'data': {'friendList': [
            {'friendId': '1', 'lastTS': 900, 'unread': True}]}}
        with flow.database(self.root) as db:
            flow.discover(self.c, db, {'r1': self.rule}, rule=self.rule)
        self.assertEqual(self.state(), 'inspect_review')
        self.now += 31
        self.step()
        self.assertFalse(any(call.args[1] == 'chatmsg' for call in self.c.cli_json.call_args_list[1:]))
        self.c.request_resume.assert_not_called()

    def test_a_fresh_incoming_hint_can_recheck_the_review_contact_once(self):
        self.pending()
        self.c.cli_json.return_value = {'ok': False, 'error': {'code': 'UNKNOWN'}}
        self.step()
        self.now += 31
        self.messages(self.message(at=self.now))
        self.c.adopt_received_resume.return_value = False
        self.step(activityHints=[self.hint()])
        self.assertEqual(self.state(), 'reply_ready')
        self.assertEqual(self.c.cli_json.call_count, 2)
        self.c.request_resume.assert_not_called()

    def test_platform_risk_and_network_failures_keep_the_existing_stop_and_backoff(self):
        for code in ('ACCOUNT_RISK', 'NETWORK_ERROR', 'RATE_LIMITED'):
            self.pending()
            failure = {'ok': False, 'error': {'code': code}}
            self.c.cli_json.return_value = failure
            self.now += 31
            result = self.step()
            self.assertEqual(result, failure)
            self.assertEqual(self.state(), 'inspect')
        self.c.request_resume.assert_not_called()

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

    def test_enabled_open_job_admits_historical_contacts_once_without_resetting_ledgers(self):
        self.rule.pop('historyBackfill')
        self.pending('seen',friend='1',activity=90)
        self.pending('requested',friend='2',activity=90)
        self.pending('reply_uncertain',friend='3',activity=90)
        with flow.database(self.root) as db:
            flow.prepare_history(db,{'r1':self.rule})
            self.assertEqual(flow.trigger_since(db,'1',self.rule),1)
            self.assertEqual(db.execute("SELECT state FROM people WHERE friend='1'").fetchone()[0],'inspect')
            self.assertEqual(db.execute("SELECT state FROM people WHERE friend='2'").fetchone()[0],'requested')
            self.assertEqual(db.execute("SELECT state FROM people WHERE friend='3'").fetchone()[0],'reply_uncertain')
            db.execute("UPDATE people SET state='seen' WHERE friend='1'")
            flow.prepare_history(db,{'r1':self.rule})
            self.assertEqual(db.execute("SELECT state FROM people WHERE friend='1'").fetchone()[0],'seen')
            self.assertEqual(db.execute("SELECT count(*) FROM events").fetchone()[0],1)
        self.c.cli_json.assert_not_called()
        self.c.request_resume.assert_not_called()

    def test_history_discovery_pages_past_enable_time_and_restores_cursor_after_reconnect(self):
        self.rule.pop('historyBackfill')
        self.c.cli_json.return_value={'ok':True,'data':{'friendList':[
            {'uid':'1','encryptJobId':'job1','lastTS':90}],'hasMore':True}}
        self.step(reconnectId='first')
        self.assertEqual(self.state(),'inspect')
        with flow.database(self.root) as db:
            task=db.execute("SELECT * FROM catchup WHERE kind='job'").fetchone()
            self.assertEqual((task['page'],task['since'],task['done']),(2,1,0))
            flow.prepare_catchup(self.c,db,{'reconnectId':'second'},{'r1':self.rule})
            task=db.execute("SELECT * FROM catchup WHERE kind='job'").fetchone()
            self.assertEqual((task['page'],task['since'],task['done']),(2,1,0))
            self.assertEqual(flow.trigger_since(db,'1',self.rule),1)
        self.c.request_resume.assert_not_called()

    def test_historical_job_is_officially_verified_before_message_read_or_adoption(self):
        self.pending('inspect',activity=90)
        with flow.database(self.root) as db:
            flow.admit_history(db,'1',{**self.rule,'historyBackfill':True})
        self.c.sourcing_friend_details.return_value={'ok':True,'data':{
            'friendList':[{'uid':'1','encryptJobId':'other-job'}]}}
        self.c.sourcing_friend_details.side_effect=None
        self.assertEqual(self.step()['data']['phase'],'job_review')
        self.c.cli_json.assert_not_called()
        self.c.adopt_received_resume.assert_not_called()
        self.c.adopt_resume_request.assert_not_called()
        self.c.request_resume.assert_not_called()

    def test_existing_official_resume_request_is_restored_without_reply_or_another_request(self):
        self.pending('inspect',activity=90)
        with flow.database(self.root) as db:
            flow.admit_history(db,'1',{**self.rule,'historyBackfill':True})
        self.messages(self.message(at=90))
        self.c.adopt_received_resume.return_value=False
        self.c.adopt_resume_request.return_value=True
        self.assertEqual(self.step()['data']['phase'],'catching_up')
        self.assertEqual(self.state(),'requested')
        self.c.adopt_resume_request.assert_called_once_with(self.c.cli_json.return_value,'1',1)
        self.c.request_resume.assert_not_called()
        self.assertEqual(self.c.cli_json.call_args.args,('hr','chatmsg','1','--count','100'))

    def test_closed_or_disabled_jobs_cannot_admit_history(self):
        self.rule.pop('historyBackfill')
        self.pending('seen',activity=90)
        self.c.managed_job_statuses.return_value={'statuses':{'job1':'待开放'}}
        self.step()
        self.assertEqual(self.state(),'seen')
        with flow.database(self.root) as db:
            self.assertEqual(db.execute('SELECT count(*) FROM recovery_windows').fetchone()[0],0)
        self.rule['enabled']=False
        self.c.managed_job_statuses.return_value={'statuses':{'job1':'开放中'}}
        self.now+=31
        self.step()
        self.assertEqual(self.state(),'seen')
        self.c.cli_json.assert_not_called()
        self.c.request_resume.assert_not_called()

    def test_unverifiable_historical_job_is_isolated_without_repeated_reads_or_writes(self):
        self.pending('inspect',activity=90)
        with flow.database(self.root) as db:
            flow.admit_history(db,'1',{**self.rule,'historyBackfill':True})
        self.c.sourcing_friend_details.side_effect=None
        self.c.sourcing_friend_details.return_value={'ok':False,'error':{'code':'FRIEND_DETAIL_FAILED'}}
        self.assertEqual(self.step()['data']['phase'],'job_review')
        self.now+=31;self.step()
        self.c.sourcing_friend_details.assert_called_once_with(['1'])
        self.c.cli_json.assert_not_called()
        self.c.request_resume.assert_not_called()

    def test_history_verification_and_message_read_share_the_existing_budget(self):
        self.pending('inspect',activity=90)
        with flow.database(self.root) as db:
            flow.admit_history(db,'1',{**self.rule,'historyBackfill':True})
        self.c.passive_delay=mock.Mock(return_value=60)
        self.assertEqual(self.step()['data']['phase'],'budget_wait')
        self.c.passive_delay.assert_called_once_with(cost=2,queued=True)
        self.c.sourcing_friend_details.assert_not_called()
        self.c.cli_json.assert_not_called()

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
        with contextlib.closing(sqlite3.connect(path)) as db, db:
            db.execute('CREATE TABLE requests(at REAL)')
            db.executemany('INSERT INTO requests VALUES(?)',[(500+i,) for i in range(96)])
        self.assertEqual(flow.budget_delay(path,120,cost=3,queued=True),0)
        # 96 + 5 requires one expiry to leave 20 interactive slots.
        self.assertEqual(flow.budget_delay(path,120,cost=5,queued=True),3101)
        # 96 + 2 - 82 = 16 expiries, rather than waking on the first one.
        self.assertEqual(flow.budget_delay(path,120,cost=2),3116)
        with contextlib.closing(sqlite3.connect(path)) as db:
            self.assertEqual(db.execute('SELECT count(*) FROM requests').fetchone()[0],96)

    def test_background_is_paced_but_incoming_receipts_and_manual_commands_keep_their_reserve(self):
        path=self.root/'budget.sqlite3'
        with contextlib.closing(sqlite3.connect(path)) as db, db:
            db.execute('CREATE TABLE requests(at REAL)')
            db.executemany('INSERT INTO requests VALUES(?)',[(999,)]*90)
        self.assertEqual(flow.budget_delay(path,120,cost=3,queued=True),59)
        self.assertGreater(flow.budget_delay(path,120,cost=2,history=True),3000)
        self.assertEqual(flow.budget_delay(path,120,cost=8,receiving=True),0)
        self.assertEqual(flow.budget_delay(path,120,cost=10,user_initiated=True),0)

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
