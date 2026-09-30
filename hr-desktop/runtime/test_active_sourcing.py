import json
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

    def test_exhausted_greeting_rights_stop_other_queued_candidates_for_same_job(self):
        self.c.cli_json.return_value = {'ok': False, 'error': {'code': 'GREET_LIMIT'}}
        self.assertEqual(self.runop('sourcing-send', command=self.command())['data']['status'], 'failed')
        self.now += 61
        other = {**self.command(), 'id': 'other', 'readAt': self.now}
        self.assertEqual(self.runop('sourcing-send', command=other)['data']['status'], 'failed')
        self.c.cli_json.assert_called_once()


if __name__ == '__main__': unittest.main()
