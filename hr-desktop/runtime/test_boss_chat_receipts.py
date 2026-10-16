import contextlib
import json
from pathlib import Path
import tempfile
import sqlite3
import time
import unittest
from unittest import mock

import boss_chat_receipts as receipts
import passive_runtime as flow


class ReceiptTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)
        self.c = mock.Mock()
        self.c.RESUME_DIR = self.root / 'downloads'
        self.c.DISCONNECTED = self.root / 'disconnected'
        self.c.RESUME_AUTO_PAUSE = self.root / 'pause'
        self.c.held_code.return_value = ''
        self.c.passive_delay = lambda **kw: 0
        self.c.receipt_conversations = None
        self.c.accept_incoming_resume = None
        self.c.resume_messages = lambda r: r.get('data', {}).get('messages', [])
        self.c.sourcing_friend_details.return_value = {'ok': True, 'data': {
            'friendList': [{'uid': 1, 'encryptJobId': 'closed-job', 'jobName': '运营'}]}}

    def message(self, mid, sender='1', attachment=True):
        return {'mid': str(mid), 'from': {'uid': sender, 'source': 0}, 'to': {'uid': '9'},
                'time': 1700000000000,
                'body': {'hyperLink': {'hyperLinkType': 1 if attachment else 3,
                                      'url': 'https://www.zhipin.com/?id=resume-id',
                                      'title': '王小明简历.pdf'}}}

    def seed(self, db):
        receipts.schema(db)
        receipts.wake(db, '1')

    def conversation(self, db):
        return db.execute("SELECT * FROM chat_resume_conversations WHERE friend='1'").fetchone()

    def inspect(self, db, messages):
        self.c.cli_json.return_value = {'ok': True, 'data': {'messages': messages}}
        return receipts.inspect(self.c, db, self.conversation(db))

    def test_manual_multiple_versions_closed_job_no_outgoing_and_exact_dedup(self):
        with flow.database(self.root) as db:
            self.seed(db)
            messages = [self.message(101), self.message(102), self.message(103, sender='9'),
                        self.message(104, attachment=False)]
            self.inspect(db, messages)
            receipts.wake(db, '1')
            self.inspect(db, messages)
            rows = db.execute('SELECT * FROM chat_resume_receipts ORDER BY mid').fetchall()
            self.assertEqual([r['mid'] for r in rows], ['101', '102'])
            self.assertTrue(all(json.loads(r['body'])['origin'] == 'boss_chat' for r in rows))
            self.assertEqual(json.loads(rows[0]['body'])['jobId'], 'closed-job')
            self.assertEqual(db.execute('SELECT count(*) FROM people').fetchone()[0], 0)
        self.c.request_resume.assert_not_called()
        self.assertTrue(all(call.args[1] == 'chatmsg' for call in self.c.cli_json.call_args_list))

    def offer(self, mid, **changes):
        message = {'mid': str(mid), 'from': {'uid': '1', 'source': 0}, 'to': {'uid': '9'},
                   'time': time.time()*1000, 'body': {'type': 7, 'dialog': {'type': 2, 'operated': False}}}
        message.update(changes)
        return message

    def test_offers_are_exact_incoming_resume_requests_not_attachments_or_other_exchanges(self):
        with flow.database(self.root) as db:
            self.seed(db)
            self.inspect(db, [self.offer(101),
                self.offer(102, body={'type': 7, 'dialog': {'type': 1, 'operated': False}}),
                self.offer(103, body={'type': 7, 'dialog': {'type': 2, 'operated': True}}),
                self.offer(104, time=(time.time()-8*86400)*1000),
                self.offer(105, **{'from': {'uid': '9'}})])
            offers = db.execute('SELECT * FROM chat_resume_offers').fetchall()
            self.assertEqual([row['mid'] for row in offers], ['101'])
            self.assertEqual(db.execute('SELECT count(*) FROM chat_resume_receipts').fetchone()[0], 0)
            self.assertEqual(offers[0]['job'], 'closed-job')
        self.c.cli_json.assert_called_once()

    def test_accept_offer_requires_open_job_and_never_replays_an_uncertain_or_completed_write(self):
        self.c.managed_job_statuses.return_value = {'statuses': {'closed-job': '已关闭'}}
        self.c.accept_incoming_resume = mock.Mock(return_value={'ok': False})
        with flow.database(self.root) as db:
            self.seed(db)
            self.inspect(db, [self.offer(101)])
            row = db.execute('SELECT * FROM chat_resume_offers').fetchone()
            receipts.accept_offer(self.c, db, row)
            self.c.accept_incoming_resume.assert_not_called()
            self.c.managed_job_statuses.return_value = {'statuses': {'closed-job': '开放中'}}
            receipts.accept_offer(self.c, db, row)
            receipts.accept_offer(self.c, db, row)
            self.c.accept_incoming_resume.assert_called_once_with('1', '101', 'closed-job')
            self.assertEqual(db.execute('SELECT state FROM chat_resume_offers').fetchone()[0], 'uncertain')

    def test_confirmed_acceptance_wakes_receipt_but_is_not_reported_as_an_archived_resume(self):
        self.c.managed_job_statuses.return_value = {'statuses': {'closed-job': '开放中'}}
        self.c.accept_incoming_resume = mock.Mock(return_value={
            'ok': True, 'data': {'accepted': True, 'friend_id': 1, 'message_id': 101}})
        with flow.database(self.root) as db:
            self.seed(db)
            self.inspect(db, [self.offer(101)])
            row = db.execute('SELECT * FROM chat_resume_offers').fetchone()
            self.assertTrue(receipts.accept_offer(self.c, db, row)['data']['resumeOfferAccepted'])
            self.assertEqual(db.execute('SELECT state FROM chat_resume_offers').fetchone()[0], 'accepted')
            self.assertTrue(self.conversation(db)['signaled'])
            self.assertEqual(receipts.exports(db), [])
            self.inspect(db, [self.message(102)])
            attachment = db.execute('SELECT * FROM chat_resume_receipts').fetchone()
            self.assertEqual(attachment['mid'], '102')
            self.assertEqual(attachment['state'], 'found')

    def test_pending_offer_hint_recovers_an_already_read_message_once_across_restart(self):
        with flow.database(self.root) as db:
            self.seed(db)
            self.inspect(db, [self.message(200, attachment=False)])
            receipts.wake(db, '1', '100')
            self.assertFalse(self.conversation(db)['dirty'])
            receipts.wake(db, '1', '100', resume_pending=True)
            self.assertTrue(self.conversation(db)['signaled'])
            self.inspect(db, [self.offer(100), self.message(200, attachment=False)])
            self.assertEqual(db.execute('SELECT count(*) FROM chat_resume_offers').fetchone()[0], 1)
        with flow.database(self.root) as db:
            receipts.wake(db, '1', '100', resume_pending=True)
            self.assertFalse(self.conversation(db)['dirty'])

    def test_offer_discovery_reuses_communication_read_without_a_second_chat_request(self):
        with flow.database(self.root) as db:
            self.seed(db)
            result = {'ok': True, 'data': {'messages': [self.offer(101)]}}
            receipts.inspect(self.c, db, self.conversation(db), result=result)
            self.c.cli_json.assert_not_called()
            self.assertEqual(db.execute('SELECT state FROM chat_resume_offers').fetchone()[0], 'pending')

    def test_old_pending_offer_keeps_slow_interval_but_can_use_receiving_capacity(self):
        now = time.time()
        budget = self.root/'budget.sqlite3'
        with sqlite3.connect(budget) as db:
            db.execute('CREATE TABLE requests(at REAL NOT NULL)')
            db.executemany('INSERT INTO requests VALUES(?)', [(now-1,)]*72)
        self.c.passive_delay = lambda **kw: flow.budget_delay(budget, 120, **kw)
        self.c.managed_job_statuses.return_value = {'statuses': {'closed-job': '开放中'}}
        self.c.accept_incoming_resume = mock.Mock(return_value={'ok': True, 'data': {
            'accepted': True, 'friend_id': 1, 'message_id': 101}})
        with flow.database(self.root) as db:
            self.seed(db)
            db.execute('UPDATE chat_resume_conversations SET signaled=0')
            self.inspect(db, [self.offer(101, time=now-7200)])
            flow.put_state(db, 'chat-resumes-last-request-at', now-599)
            self.assertIsNone(receipts.step(self.c, db, {'collectAllResumes': True}))
            self.c.accept_incoming_resume.assert_not_called()
            flow.put_state(db, 'chat-resumes-last-request-at', now-601)
            self.assertTrue(receipts.step(self.c, db, {'collectAllResumes': True})['data']['resumeOfferAccepted'])

    def test_candidate_offer_to_attachment_to_export_is_paced_and_only_cloud_ack_marks_stored(self):
        now = time.time()
        request = {'collectAllResumes': True}
        self.c.managed_job_statuses.return_value = {'statuses': {'closed-job': '开放中'}}
        self.c.accept_incoming_resume = mock.Mock(return_value={'ok': True, 'data': {
            'accepted': True, 'friend_id': 1, 'message_id': 101}})
        def network(*args):
            if args[1] == 'chatmsg':
                message = self.offer(101) if not self.c.accept_incoming_resume.called else self.message(102)
                message['time'] = now
                return {'ok': True, 'data': {'messages': [message]}}
            if args[1] == 'download-resume':
                Path(args[-1]).write_bytes(b'%PDF-1.4 own fixture')
                return {'ok': True}
            self.fail('Unexpected operation')
        self.c.cli_json.side_effect = network
        with flow.database(self.root) as db:
            self.seed(db)
            for offset in (0, 31, 62, 93):
                with mock.patch.object(receipts.time, 'time', return_value=now+offset):
                    self.assertTrue(receipts.step(self.c, db, request)['ok'])
                    self.assertIsNone(receipts.step(self.c, db, request), 'No burst inside a paced turn')
            self.c.accept_incoming_resume.assert_called_once_with('1', '101', 'closed-job')
            self.assertEqual(db.execute('SELECT state FROM chat_resume_receipts').fetchone()[0], 'export')
            self.assertTrue(Path(receipts.exports(db)[0]['file']).read_bytes().startswith(b'%PDF'))
            receipts.acknowledge(db, '1', '102')
            self.assertEqual(db.execute('SELECT state FROM chat_resume_receipts').fetchone()[0], 'stored')

    def test_large_historical_backlog_downloads_one_at_a_time_without_lifetime_count_limit(self):
        now = time.time()
        def downloaded(*args):
            self.assertEqual(args[1], 'download-resume')
            Path(args[-1]).write_bytes(b'%PDF-1.4 own fixture')
            return {'ok': True}
        self.c.cli_json.side_effect = downloaded
        with flow.database(self.root) as db:
            receipts.schema(db)
            db.executemany("INSERT INTO chat_resume_receipts(friend,mid,rule,body,state) VALUES('1',?,'rule','{}','stored')",
                           [(str(mid),) for mid in range(1, 501)])
            db.executemany("INSERT INTO chat_resume_receipts(friend,mid,rule,body) VALUES('1',?,'rule',?)",
                           [(str(mid), json.dumps({'originalFileName':'resume.pdf','receivedAt':now-86400})) for mid in (501,502)])
            with mock.patch.object(receipts.time, 'time', return_value=now):
                self.assertTrue(receipts.step(self.c, db, {})['ok'])
                self.assertIsNone(receipts.step(self.c, db, {}))
        with flow.database(self.root) as db:
            with mock.patch.object(receipts.time, 'time', return_value=now+599):
                self.assertIsNone(receipts.step(self.c, db, {}))
            with mock.patch.object(receipts.time, 'time', return_value=now+600):
                self.assertTrue(receipts.step(self.c, db, {})['ok'])
            self.assertEqual(len(receipts.exports(db)), 1, 'Cloud exports also remain serial')
            self.assertEqual(db.execute("SELECT count(*) FROM chat_resume_receipts WHERE state='export'").fetchone()[0], 2)
        self.assertEqual(self.c.cli_json.call_count, 2)

    def test_collection_index_does_not_request_optional_last_message_enrichment(self):
        self.c.receipt_conversations = mock.Mock(return_value={'ok': True, 'data': {'result': [{'uid': 1}]}})
        with flow.database(self.root) as db:
            receipts.schema(db)
            self.assertTrue(receipts.discover(self.c, db)['ok'])
            self.assertEqual(self.conversation(db)['friend'], '1')
        self.c.receipt_conversations.assert_called_once_with(1)
        self.c.cli_json.assert_not_called()

    def test_already_read_activity_does_not_schedule_the_same_message_again(self):
        with flow.database(self.root) as db:
            self.seed(db)
            self.inspect(db, [self.message(101)])
            receipts.wake(db, '1', '101')
            self.assertEqual(self.conversation(db)['signaled'], 0)
            receipts.wake(db, '1', '102')
            self.assertEqual(self.conversation(db)['signaled'], 1)

    def test_history_continues_beyond_five_pages_and_fresh_messages_do_not_erase_cursor(self):
        with flow.database(self.root) as db:
            self.seed(db)
            self.inspect(db, [self.message(mid, attachment=False) for mid in range(900, 1000)])
            self.assertEqual(self.conversation(db)['history_before'], 899)
            for high in (900, 800, 700, 600, 500, 400):
                self.inspect(db, [self.message(mid, attachment=mid == high-1) for mid in range(high-100, high)])
            self.assertEqual(self.conversation(db)['history_before'], 299)
            receipts.wake(db, '1')
            self.inspect(db, [self.message(1100)])
            self.assertEqual(self.conversation(db)['history_before'], 299)
            self.inspect(db, [self.message(201)])
            self.assertTrue(self.conversation(db)['history_done'])
            self.assertEqual(db.execute('SELECT count(*) FROM chat_resume_receipts').fetchone()[0], 8)

    def test_transient_read_failure_preserves_cursor_and_unknown_job_is_retained(self):
        with flow.database(self.root) as db:
            self.seed(db)
            self.c.cli_json.return_value = {'ok': False, 'error': {'code': 'NETWORK_ERROR'}}
            receipts.inspect(self.c, db, self.conversation(db))
            self.assertEqual(self.conversation(db)['latest_mid'], 0)
            self.c.sourcing_friend_details.return_value = {'ok': False}
            self.inspect(db, [self.message(100)])
            row = db.execute('SELECT * FROM chat_resume_receipts').fetchone()
            self.assertEqual(json.loads(row['body'])['jobId'], '')
            self.assertEqual(row['rule'], 'boss-chat-1')

    def test_sources_retain_existing_system_flow_and_account_local_ledgers(self):
        with flow.database(self.root) as db:
            self.seed(db)
            db.execute("INSERT INTO people(friend,rule,body,state,updated) VALUES('1','active-contact',?,'stored',0)",
                       (json.dumps({'jobId': 'closed-job'}),))
            self.inspect(db, [self.message(100)])
            row = db.execute('SELECT * FROM chat_resume_receipts').fetchone()
            self.assertEqual(row['rule'], 'active-contact')
            self.assertEqual(json.loads(row['body'])['origin'], 'active')
        (self.root / 'other-account').mkdir()
        with flow.database(self.root / 'other-account') as db:
            receipts.schema(db)
            self.assertEqual(db.execute('SELECT count(*) FROM chat_resume_receipts').fetchone()[0], 0)

    def test_download_and_acknowledgement_require_exact_receipt_and_survive_restarts(self):
        with flow.database(self.root) as db:
            self.seed(db)
            self.inspect(db, [self.message(100)])
            row = db.execute('SELECT * FROM chat_resume_receipts').fetchone()
            def downloaded(*args):
                Path(args[-1]).write_bytes(b'%PDF-1.4 test')
                return {'ok': True}
            self.c.cli_json.side_effect = downloaded
            receipts.download(self.c, db, row)
        local = flow.execute(self.c, self.root, {'operation': 'passive-local'})
        self.assertEqual(local['data']['exports'][0]['mid'], '100')
        flow.execute(self.c, self.root, {'operation': 'passive-ack', 'friendId': '1', 'messageId': '99'})
        self.assertEqual(len(flow.execute(self.c, self.root, {'operation': 'passive-local'})['data']['exports']), 1)
        flow.execute(self.c, self.root, {'operation': 'passive-ack', 'friendId': '1', 'messageId': '100'})
        self.assertEqual(flow.execute(self.c, self.root, {'operation': 'passive-local'})['data']['exports'], [])

    def test_collector_runs_without_rules_and_pause_stops_all_network(self):
        self.c.cli_json.return_value = {'ok': True, 'data': {'result': [{'uid': 1}]}}
        request = {'operation': 'passive-step', 'collectAllResumes': True, 'rules': []}
        self.assertTrue(flow.execute(self.c, self.root, request)['ok'])
        self.c.cli_json.assert_called_once_with('hr', 'chat', '--page', '1')
        self.c.cli_json.reset_mock()
        self.c.RESUME_AUTO_PAUSE.touch()
        self.assertFalse(flow.execute(self.c, self.root, request)['ok'])
        self.c.cli_json.assert_not_called()

    def test_history_leaves_capacity_for_admitted_replies_and_manual_operations(self):
        budget = self.root / 'budget.sqlite3'
        with contextlib.closing(sqlite3.connect(budget)) as db, db:
            db.execute('CREATE TABLE requests(at REAL NOT NULL)')
            db.executemany('INSERT INTO requests VALUES(?)', [(time.time()-600,)]*90)
        self.c.passive_delay = lambda **options: flow.budget_delay(budget, 120, **options)
        with flow.database(self.root) as db:
            self.assertIsNone(receipts.step(self.c, db, {}))
        self.c.cli_json.assert_not_called()
        self.assertEqual(self.c.passive_delay(cost=3, queued=True), 0)
        self.assertEqual(self.c.passive_delay(cost=20, queued=True, user_initiated=True), 0)

    def test_new_closed_job_attachment_and_download_use_receipt_reserve_after_history_pauses(self):
        budget = self.root / 'budget.sqlite3'
        with contextlib.closing(sqlite3.connect(budget)) as db, db:
            db.execute('CREATE TABLE requests(at REAL NOT NULL)')
            db.executemany('INSERT INTO requests VALUES(?)', [(time.time()-60,)]*90)
        self.c.passive_delay = lambda **options: flow.budget_delay(budget, 120, **options)
        message=self.message(100);message['time']=time.time()
        self.c.cli_json.return_value = {'ok': True, 'data': {'messages': [message]}}
        with flow.database(self.root) as db:
            self.assertIsNone(receipts.step(self.c, db, {}))
            receipts.wake(db, '1')
            self.assertTrue(receipts.step(self.c, db, {})['ok'])
            self.assertEqual(db.execute('SELECT count(*) FROM chat_resume_receipts').fetchone()[0], 1)
            def downloaded(*args):
                Path(args[-1]).write_bytes(b'%PDF-1.4 test')
                return {'ok': True}
            self.c.cli_json.side_effect = downloaded
            with mock.patch.object(receipts.time,'time',return_value=time.time()+31):
                self.assertTrue(receipts.step(self.c, db, {})['ok'])
            self.assertEqual(db.execute('SELECT state FROM chat_resume_receipts').fetchone()[0], 'export')
        self.c.request_resume.assert_not_called()

    def test_receipt_reserve_never_bypasses_shared_hard_quota(self):
        budget = self.root / 'budget.sqlite3'
        with contextlib.closing(sqlite3.connect(budget)) as db, db:
            db.execute('CREATE TABLE requests(at REAL NOT NULL)')
            db.executemany('INSERT INTO requests VALUES(?)', [(time.time(),)]*120)
        self.c.passive_delay = lambda **options: flow.budget_delay(budget, 120, **options)
        with flow.database(self.root) as db:
            self.seed(db)
            self.assertIsNone(receipts.step(self.c, db, {}))
            self.assertTrue(receipts.urgent(db))
        self.c.cli_json.assert_not_called()

    def test_job_checks_cannot_starve_durable_slow_collection(self):
        budget = self.root / 'budget.sqlite3'
        now = time.time()
        with contextlib.closing(sqlite3.connect(budget)) as db, db:
            db.execute('CREATE TABLE requests(at REAL NOT NULL)')
            db.executemany('INSERT INTO requests VALUES(?)', [(now-60,), (now-1,)])
        self.c.passive_delay = lambda **options: flow.budget_delay(budget, 120, **options)
        self.c.cli_json.return_value = {'ok': True, 'data': {'result': []}}
        with mock.patch.object(receipts.time, 'time', return_value=now):
            with flow.database(self.root) as db:
                flow.put_state(db, 'chat-resumes-last-request-at', now-601)
                self.assertTrue(receipts.step(self.c, db, {})['ok'])
                self.assertEqual(float(flow.get_state(db, 'chat-resumes-last-request-at')), now)
            # Reopening the ledger models a replacement/worker restart.
            with flow.database(self.root) as db:
                self.assertIsNone(receipts.step(self.c, db, {}))
        self.c.cli_json.assert_called_once_with('hr', 'chat', '--page', '1')
        self.c.request_resume.assert_not_called()

    def test_plain_text_uses_one_read_without_redundant_job_binding_request(self):
        with flow.database(self.root) as db:
            self.seed(db)
            self.inspect(db, [self.message(100, attachment=False)])
        self.c.sourcing_friend_details.assert_not_called()

    def test_rescan_of_existing_attachment_does_not_repeat_binding_or_download(self):
        with flow.database(self.root) as db:
            self.seed(db)
            self.inspect(db,[self.message(100)])
            self.c.sourcing_friend_details.reset_mock()
            receipts.wake(db,'1')
            self.inspect(db,[self.message(100)])
            self.c.sourcing_friend_details.assert_not_called()
            self.assertEqual(db.execute('SELECT count(*) FROM chat_resume_receipts').fetchone()[0],1)

    def test_filename_selects_docx_without_guessing_and_redownloading(self):
        with flow.database(self.root) as db:
            self.seed(db)
            message=self.message(100)
            message['body']['hyperLink']['title']='简历.docx'
            self.inspect(db,[message])
            def downloaded(*args):
                self.assertTrue(args[-1].endswith('.docx'))
                Path(args[-1]).write_bytes(b'valid docx fixture')
                return {'ok':True}
            self.c.cli_json.reset_mock();self.c.cli_json.side_effect=downloaded
            receipts.download(self.c,db,db.execute('SELECT * FROM chat_resume_receipts').fetchone())
            self.c.cli_json.assert_called_once()

    def test_mismatched_suffix_queues_exact_sdk_format_without_immediate_retry(self):
        with flow.database(self.root) as db:
            self.seed(db);self.inspect(db,[self.message(100)])
            self.c.cli_json.reset_mock()
            self.c.cli_json.return_value={'ok':False,'error':{'code':'INVALID_PARAM',
                'message':'文件内容与输出扩展名不符，请使用 .docx'}}
            receipts.download(self.c,db,db.execute('SELECT * FROM chat_resume_receipts').fetchone())
            self.c.cli_json.assert_called_once()
            saved=db.execute('SELECT * FROM chat_resume_receipts').fetchone()
            self.assertEqual(json.loads(saved['body'])['downloadSuffix'],'.docx')
            self.assertGreater(saved['retry_at'],time.time()+50)
            self.assertEqual(saved['state'],'found')

    def test_pacing_survives_restart_and_new_activity_does_not_create_a_burst(self):
        request={'activityHints':[{'friend':'1'}]}
        message=self.message(100);message['time']=1000
        self.c.cli_json.return_value={'ok':True,'data':{'messages':[message]}}
        with mock.patch.object(receipts.time,'time',return_value=1000):
            with flow.database(self.root) as db:
                self.seed(db);self.assertTrue(receipts.step(self.c,db,request)['ok'])
        self.c.cli_json.reset_mock();self.c.sourcing_friend_details.reset_mock()
        with mock.patch.object(receipts.time,'time',return_value=1010):
            with flow.database(self.root) as db:self.assertIsNone(receipts.step(self.c,db,request))
        self.c.cli_json.assert_not_called();self.c.sourcing_friend_details.assert_not_called()
        with mock.patch.object(receipts.time,'time',return_value=1030):
            with flow.database(self.root) as db:
                with mock.patch.object(receipts,'download',return_value={'ok':True}) as download:
                    self.assertTrue(receipts.step(self.c,db,request)['ok'])
                    download.assert_called_once()

    def test_history_waits_ten_minutes_across_reconnect_without_resetting_list_deadline(self):
        self.c.cli_json.return_value={'ok':True,'data':{'messages':[]}}
        with mock.patch.object(receipts.time,'time',return_value=1000):
            with flow.database(self.root) as db:
                self.seed(db);db.execute("UPDATE chat_resume_conversations SET signaled=0")
                flow.put_state(db,'chat-resumes-discover-at',9000)
                self.assertTrue(receipts.step(self.c,db,{'reconnectId':'first'})['ok'])
        self.c.cli_json.reset_mock()
        with mock.patch.object(receipts.time,'time',return_value=1599):
            with flow.database(self.root) as db:
                self.assertIsNone(receipts.step(self.c,db,{'reconnectId':'second'}))
                self.assertEqual(float(flow.get_state(db,'chat-resumes-discover-at')),9000)
        self.c.cli_json.assert_not_called()
        with mock.patch.object(receipts.time,'time',return_value=1600):
            with flow.database(self.root) as db:
                self.assertTrue(receipts.step(self.c,db,{'reconnectId':'second'})['ok'])
        self.c.cli_json.assert_called_once()

    def test_schedule_uses_saved_history_deadline_and_prioritizes_new_activity_without_network(self):
        with mock.patch.object(receipts.time,'time',return_value=1000):
            with flow.database(self.root) as db:
                self.seed(db);db.execute("UPDATE chat_resume_conversations SET signaled=0")
                flow.put_state(db,'chat-resumes-last-request-at',990)
                flow.put_state(db,'chat-resumes-discover-at',9000)
                planned=receipts.schedule(db)
                self.assertEqual(planned['collectorRetryAt'],1590)
                receipts.wake(db,'2')
                planned=receipts.schedule(db)
                self.assertEqual(planned['collectorRetryAt'],1020)
                self.assertEqual(planned['collectorPending'],3)
        self.c.cli_json.assert_not_called();self.c.sourcing_friend_details.assert_not_called()

    def test_old_attachment_in_new_activity_stays_historical_and_new_activity_can_preempt_it(self):
        with flow.database(self.root) as db:
            self.seed(db);self.inspect(db,[self.message(100)])
            saved=db.execute('SELECT * FROM chat_resume_receipts').fetchone()
            self.assertFalse(json.loads(saved['body'])['receiptPriority'])
            self.c.cli_json.reset_mock();self.c.cli_json.return_value={'ok':True,'data':{'messages':[]}}
            receipts.wake(db,'2')
            flow.put_state(db,'chat-resumes-last-request-at',time.time()-31)
            self.assertTrue(receipts.step(self.c,db,{'activityHints':[{'friend':'2'}]})['ok'])
            self.c.cli_json.assert_called_once()
            self.assertEqual(self.c.cli_json.call_args.args[:3],('hr','chatmsg','2'))

    def test_historical_attachment_does_not_spend_incoming_receipt_reserve(self):
        with flow.database(self.root) as db:
            self.seed(db);db.execute("UPDATE chat_resume_conversations SET signaled=0")
            self.inspect(db,[self.message(100)])
            self.c.passive_delay=mock.Mock(return_value=120)
            receipts.step(self.c,db,{})
            self.c.passive_delay.assert_called_once_with(cost=8,queued=False,receiving=False,history=True,
                                                        last_operation_at=0.0)

    def test_unchanged_conversation_list_does_not_requeue_old_history_every_sweep(self):
        with flow.database(self.root) as db:
            receipts.schema(db)
            self.c.cli_json.return_value = {'ok': True, 'data': {'result': [{'uid': 1, 'last_time': 100}]}}
            receipts.discover(self.c, db)
            self.inspect(db, [self.message(100, attachment=False)])
            db.execute("UPDATE chat_resume_conversations SET last_read=1 WHERE friend='1'")
            self.c.cli_json.return_value = {'ok': True, 'data': {'result': [{'uid': 1, 'last_time': 100}]}}
            receipts.discover(self.c, db)
            self.assertEqual(self.conversation(db)['dirty'], 0)

    def test_uid_only_official_list_retains_slow_receive_rescan_without_local_notifications(self):
        with flow.database(self.root) as db:
            receipts.schema(db)
            self.c.cli_json.return_value = {'ok': True, 'data': {'result': [{'uid': 1}]}}
            receipts.discover(self.c, db)
            self.inspect(db, [self.message(100, attachment=False)])
            self.c.cli_json.return_value = {'ok': True, 'data': {'result': [{'uid': 1}]}}
            receipts.discover(self.c, db)
            self.assertEqual(self.conversation(db)['dirty'], 0)
            db.execute("UPDATE chat_resume_conversations SET last_read=1 WHERE friend='1'")
            receipts.discover(self.c, db)
            self.assertEqual(self.conversation(db)['dirty'], 1)
    def test_new_signal_during_paginated_fresh_pass_rechecks_latest_after_completion(self):
        with flow.database(self.root) as db:
            self.seed(db)
            self.inspect(db, [self.message(100)])
            receipts.wake(db, '1')
            self.inspect(db, [self.message(mid, attachment=False) for mid in range(200, 300)])
            receipts.wake(db, '1')
            self.inspect(db, [self.message(101)])
            self.assertEqual(self.conversation(db)['dirty'], 1)
            self.inspect(db, [self.message(300)])
            self.assertEqual(self.conversation(db)['latest_mid'], 300)


if __name__ == '__main__':
    unittest.main()
