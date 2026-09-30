import contextlib
import sqlite3
import tempfile
from pathlib import Path
import unittest
from unittest import mock
import account_history


class AccountHistoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.current = self.base / ('a'*64); self.current.mkdir()
        self.legacy = self.base / ('b'*64); self.legacy.mkdir()
        with contextlib.closing(sqlite3.connect(self.legacy / 'resumes.sqlite3')) as db:
            db.execute('CREATE TABLE resume_requests(friend_id,state,message_id,baseline_mid,updated_at)')
            db.execute("INSERT INTO resume_requests VALUES('123','received','456',100,999)")
            db.commit()
        self.c = mock.Mock()
        self.c.passive_delay.return_value = 0
        self.c.resume_messages = lambda value:value['data']['messages']
        self.message = {'mid':'456','from':{'uid':123},'to':{'uid':999}}
        self.c.cli_json.return_value = {'ok':True,'data':{'messages':[self.message]}}

    def test_exact_archived_mid_and_uid_pair_recovers_without_old_credentials(self):
        value = account_history.recover(self.c, self.current, '999')
        self.assertEqual(value['data']['accountKey'],self.legacy.name)
        self.c.cli_json.assert_called_once_with('hr','chatmsg','123','--count','20','--max-msg-id','457')

    def test_same_candidate_wrong_recruiter_or_missing_message_cannot_match(self):
        self.assertEqual(account_history.recover(self.c,self.current,'888')['data']['accountKey'],'')
        self.message['mid']='455'
        self.assertEqual(account_history.recover(self.c,self.current,'999')['data']['accountKey'],'')

    def test_held_history_and_budget_never_bypass_restrictions(self):
        (self.legacy/'risk-hold.json').write_text('{}')
        self.assertFalse(account_history.recover(self.c,self.current,'999')['ok'])
        self.c.cli_json.reset_mock();self.c.passive_delay.return_value=50
        self.assertFalse(account_history.recover(self.c,self.current,'999')['ok'])
        self.c.cli_json.assert_not_called()

    def test_zero_baseline_has_no_identity_evidence(self):
        with contextlib.closing(sqlite3.connect(self.legacy/'resumes.sqlite3')) as db:
            db.execute('UPDATE resume_requests SET message_id=NULL,baseline_mid=0');db.commit()
        self.assertEqual(account_history.recover(self.c,self.current,'999')['data']['accountKey'],'')
        self.c.cli_json.assert_not_called()

if __name__ == '__main__':
    unittest.main()
