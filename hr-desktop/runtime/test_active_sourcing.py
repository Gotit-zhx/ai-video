import json
import sqlite3
import unittest
from unittest import mock

import active_sourcing as active
import passive_runtime as flow
import test_passive_runtime as fixtures


class ActiveSourcingTest(unittest.TestCase):
    setUp = fixtures.WorkflowTest.setUp
    runop = fixtures.WorkflowTest.runop
    state = fixtures.WorkflowTest.state
    message = fixtures.WorkflowTest.message

    def command(self):
        return {'id': 'contact-1', 'kind': 'active_greet', 'message': '您好，希望沟通这个岗位。',
                'jobId': 'job1', 'jobName': '工程师', 'readAt': 900, 'enabledAt': 900,
                'ruleId': 'active-contact-1', 'origin': 'active', 'status': 'sent',
                'identifiers': {'geekId': 'geek1', 'expectId': 'expect1', 'lid': 'lid1', 'securityId': 'security1'}}

    def test_exactly_one_original_cli_greet_after_durable_uncertain_write(self):
        command = self.command()
        def send(*args):
            self.assertEqual(args[:2], ('hr', 'greet'))
            with flow.database(self.root) as db:
                self.assertEqual(db.execute('SELECT state FROM commands').fetchone()[0], 'uncertain')
            return {'ok': True, 'data': {'sent': True}}
        self.c.cli_json.side_effect = send
        self.assertEqual(self.runop('sourcing-send', command=command)['data']['status'], 'sent')
        self.assertEqual(self.runop('sourcing-send', command=command)['data']['status'], 'sent')
        self.c.cli_json.assert_called_once()
        self.assertIn('--yes', self.c.cli_json.call_args.args)
        self.assertFalse(self.c.request_resume.called)
        local = self.runop('passive-local')['data']
        self.assertEqual(local['reports'][0]['state'], 'sent')

    def test_lost_response_and_restart_never_resend(self):
        self.c.cli_json.side_effect = OSError('response lost')
        self.assertEqual(self.runop('sourcing-send', command=self.command())['data']['status'], 'uncertain')
        self.assertEqual(self.runop('sourcing-send', command=self.command())['data']['status'], 'uncertain')
        self.c.cli_json.assert_called_once()
        with flow.database(self.root) as db:
            self.assertEqual(db.execute('SELECT state FROM active_contacts').fetchone()[0], 'uncertain')
            self.assertEqual(active.daily_usage(db, self.now)['used'], 1)

    def test_explicit_greeting_uses_reserved_capacity_but_never_exceeds_hard_budget(self):
        path = self.root/'budget.sqlite3'
        with sqlite3.connect(path) as db:
            db.execute('CREATE TABLE requests(at REAL)')
            db.executemany('INSERT INTO requests VALUES(?)', [(900,)]*101)
        self.assertGreater(flow.budget_delay(path,120,cost=5,queued=True),0)
        self.assertEqual(flow.budget_delay(path,120,cost=5,queued=True,user_initiated=True),0)
        with sqlite3.connect(path) as db:
            db.executemany('INSERT INTO requests VALUES(?)', [(900,)]*15)
        self.assertGreater(flow.budget_delay(path,120,cost=5,queued=True,user_initiated=True),0)

    def test_daily_150_attempts_persist_and_resume_next_beijing_day(self):
        with flow.database(self.root) as db:
            active.schema(db)
            day=active.daily_usage(db,self.now)['day']
            db.executemany('INSERT INTO active_greeting_attempts VALUES(?,?,?)',
                           [(f'previous-{i}',day,self.now) for i in range(150)])
        status=self.runop('sourcing-ready',command=self.command())['data']
        self.assertFalse(status['allowed'])
        self.assertEqual(status['reasonCode'],'DAILY_GREETING_LIMIT')
        self.assertEqual(status['dailyGreeting']['used'],150)
        self.assertEqual(self.runop('sourcing-send',command=self.command())['data']['status'],'failed')
        self.c.cli_json.assert_not_called()
        self.now=status['dailyGreeting']['resetAt']+1
        new={**self.command(),'id':'next-day','readAt':self.now}
        status=self.runop('sourcing-ready',command=new)['data']
        self.assertTrue(status['allowed'])
        self.assertEqual(status['dailyGreeting']['remaining'],150)

    def test_readiness_reports_wait_without_refreshing_jobs_during_budget_or_risk_hold(self):
        self.c.passive_delay=lambda **kw:120
        value=self.runop('sourcing-ready',command=self.command())['data']
        self.assertEqual(value['reasonCode'],'REQUEST_BUDGET_WAIT')
        self.assertEqual(value['retryAt'],1120)
        self.c.managed_job_statuses.assert_not_called()
        self.c.held_code.return_value='ACCOUNT_RISK'
        value=self.runop('sourcing-ready',command=self.command())
        self.assertEqual(value['error']['code'],'ACCOUNT_RISK')

    def test_long_security_credential_is_a_single_unchanged_cli_argument(self):
        security = 'a'*700+'~'+'B'*45+'=='
        command = self.command();command['identifiers']['securityId'] = security
        self.c.cli_json.return_value = {'ok': True, 'data': {'sent': True}}
        self.assertEqual(self.runop('sourcing-send', command=command)['data']['status'], 'sent')
        args = self.c.cli_json.call_args.args
        self.assertEqual(args[args.index('--security-id')+1], security)
        self.assertEqual(self.runop('sourcing-send', command=command)['data']['status'], 'sent')
        self.c.cli_json.assert_called_once()

    def test_bad_or_oversized_security_credential_never_reaches_cli(self):
        for security in ['a'*4097, '--help', 'id x', 'id\nx', 'id;whoami', 'id$(whoami)', 'id/../../', 'id\x00']:
            command = self.command();command['identifiers']['securityId'] = security
            with self.subTest(length=len(security)), self.assertRaises(ValueError):
                self.runop('sourcing-send', command=command)
        self.c.cli_json.assert_not_called()
        with flow.database(self.root) as db:
            self.assertEqual(db.execute('SELECT count(*) FROM commands').fetchone()[0], 0)

    def test_shared_budget_cooldown_risk_and_stale_cards_prevent_sending(self):
        self.c.passive_delay = lambda **kw: 120
        self.assertFalse(self.runop('sourcing-ready')['data']['allowed'])
        self.assertEqual(self.runop('sourcing-send', command=self.command())['data']['status'], 'failed')
        self.c.cli_json.assert_not_called()
        self.c.passive_delay = lambda **kw: 0
        self.c.held_code.return_value = 'ACCOUNT_RISK'
        self.assertFalse(self.runop('sourcing-send', command={**self.command(), 'id': 'other'})['ok'])
        self.c.cli_json.assert_not_called()
        self.c.held_code.return_value = ''
        self.assertEqual(self.runop('sourcing-send', command={**self.command(), 'id': 'stale', 'readAt': -1000})['data']['status'], 'failed')

    def test_binding_requires_both_encrypted_person_and_job_not_same_name(self):
        self.c.cli_json.return_value = {'ok': True, 'data': {'result': [{'uid': '1'}, {'uid': '2'}]}}
        self.c.sourcing_friend_details.return_value = {'ok': True, 'data': {'friendList': [
            {'uid': '1', 'name': '同名人才', 'encryptUid': 'wrong', 'encryptJobId': 'job1'},
            {'uid': '2', 'name': '同名人才', 'encryptUid': 'geek1', 'encryptJobId': 'other'}]}}
        with flow.database(self.root) as db:
            rules, failure = active.rules(self.c, db, [self.command()])
            self.assertEqual(rules, {}); self.assertIsNone(failure)
            self.assertEqual(db.execute('SELECT count(*) FROM people').fetchone()[0], 0)
        self.c.sourcing_friend_details.return_value = {'ok': True, 'data': {'friendList': [
            {'uid': '2', 'encryptUid': 'geek1', 'encryptJobId': 'job1'}]}}
        self.now += 301
        with flow.database(self.root) as db:
            rules, failure = active.rules(self.c, db, [self.command()])
            self.assertEqual(rules['active-contact-1']['activeFriend'], '2')
            self.assertEqual(db.execute('SELECT state FROM people').fetchone()[0], 'inspect')
        bindings = self.runop('passive-local')['data']['bindings']
        self.assertEqual(bindings, [{'contactId': 'contact-1', 'friendId': '2'}])
        self.runop('passive-ack', **bindings[0])
        self.assertEqual(self.runop('passive-local')['data']['bindings'], [])

    def test_active_followup_does_not_admit_unrelated_candidates_or_send_second_reply(self):
        command = self.command()
        with flow.database(self.root) as db:
            active.schema(db)
            db.execute("INSERT INTO active_contacts(id,body,state,friend) VALUES(?,?,'sent','1')", (command['id'], json.dumps(command)))
            detail = {**command, 'replyAt': 900}
            db.execute("INSERT INTO people(friend,rule,body,state,updated) VALUES('1',?,?,'inspect',900)",
                       (command['ruleId'], json.dumps(detail)))
            flow.put_state(db, 'discoveredAt', 1000)
            # Mark the maintenance turn complete so this checks the targeted message.
            flow.put_state(db, 'lastLane', 'maintenance')
        self.c.adopt_received_resume.return_value = False
        self.c.cli_json.return_value = {'ok': True, 'data': {'messages': [self.message(at=950)]}}
        self.runop('passive-step', rules=[], activeContacts=[command])
        self.assertEqual(self.state(), 'ready')
        self.c.cli_json.assert_called_once_with('hr', 'chatmsg', '1', '--count', '100')
        self.c.request_resume.assert_not_called()
        self.now += 31
        self.runop('passive-step', rules=[], activeContacts=[command])
        self.c.request_resume.assert_called_once_with('1', retry_uncertain=False)
        self.assertEqual(self.state(), 'requested')
        with flow.database(self.root) as db:
            self.c.cli_json.return_value = {'ok': True, 'data': {'result': [{'uid': '1'}, {'uid': '2'}]}}
            rule = {'id': command['ruleId'], 'jobId': 'job1', 'enabledAt': 900, 'activeFriend': '1'}
            flow.discover(self.c, db, {rule['id']: rule})
            self.assertEqual(db.execute('SELECT count(*) FROM people').fetchone()[0], 1)

    def test_removed_cloud_approval_cannot_continue_local_contact(self):
        with flow.database(self.root) as db:
            active.schema(db)
            db.execute("INSERT INTO active_contacts(id,body,state,friend) VALUES(?,?,'sent','1')", ('contact-1', json.dumps(self.command())))
            rules, failure = active.rules(self.c, db, [])
            self.assertEqual(rules, {})
        self.c.cli_json.assert_not_called()

    def test_closed_or_unknown_jobs_cannot_greet_or_start_followup(self):
        for index, status in enumerate(('待开放', '已失效', '已关闭', None)):
            self.c.managed_job_statuses.return_value = {'statuses': {'job1': status} if status else {}}
            command = {**self.command(), 'id': f'closed-{index}'}
            self.assertFalse(self.runop('sourcing-ready', command=command)['data']['allowed'])
            self.assertEqual(self.runop('sourcing-send', command=command)['data']['status'], 'failed')
            self.runop('passive-step', rules=[], activeContacts=[command])
        self.c.cli_json.assert_not_called()
        self.c.sourcing_friend_details.assert_not_called()
        self.c.request_resume.assert_not_called()

    def test_exhausted_greeting_rights_stop_other_queued_candidates_for_same_job(self):
        self.c.cli_json.return_value = {'ok': False, 'error': {'code': 'GREET_LIMIT'}}
        self.assertEqual(self.runop('sourcing-send', command=self.command())['data']['status'], 'failed')
        self.now += 61
        other = {**self.command(), 'id': 'other', 'readAt': self.now}
        self.assertEqual(self.runop('sourcing-send', command=other)['data']['status'], 'failed')
        self.c.cli_json.assert_called_once()


if __name__ == '__main__': unittest.main()
