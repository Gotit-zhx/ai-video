import json
from pathlib import Path
import tempfile
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
