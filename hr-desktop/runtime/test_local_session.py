import json
from pathlib import Path
import sqlite3
import tempfile
import time
import unittest
from unittest import mock

import local_session


class LocalSessionTest(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);root=Path(temp.name)
        self.c=mock.Mock(DISCONNECTED=root/'disconnected')
        self.g=mock.Mock(REQUEST_LOCK=root/'request.lock',RISK_HOLD=root/'risk.json',
                         BUDGET_DB=root/'budget.sqlite3',HOURLY_LIMIT=2)

    def hold(self,code):
        self.g.RISK_HOLD.write_text(json.dumps({"code":code,"generation":"same"}))

    def test_local_match_restores_only_a_temporary_hold_without_boss_calls(self):
        self.hold("SESSION_UNAVAILABLE")
        self.assertTrue(local_session.resume(self.c,self.g)["ok"])
        self.g.verify_session.assert_called_once_with()
        self.assertFalse(self.g.RISK_HOLD.exists())
        self.assertEqual(self.g.method_calls,[mock.call.verify_session()])
        self.assertEqual(self.c.method_calls,[])

    def test_real_risk_mismatch_and_logout_are_never_automatically_cleared(self):
        for code in ["ACCOUNT_RISK","SESSION_MISMATCH","PC_LOGIN_RESTRICTED","RATE_LIMITED"]:
            self.hold(code);before=self.g.RISK_HOLD.read_bytes()
            self.assertFalse(local_session.resume(self.c,self.g)["ok"])
            self.assertEqual(self.g.RISK_HOLD.read_bytes(),before)
        self.g.verify_session.assert_not_called()
        self.g.RISK_HOLD.unlink();self.c.DISCONNECTED.touch()
        self.assertEqual(local_session.resume(self.c,self.g)["error"]["code"],"AUTH_REQUIRED")

    def test_budget_resumes_only_after_expiry_and_never_resets_the_ledger(self):
        self.hold("REQUEST_BUDGET_EXHAUSTED")
        with sqlite3.connect(self.g.BUDGET_DB) as db:
            db.execute("CREATE TABLE requests(at REAL)")
            db.executemany("INSERT INTO requests VALUES(?)",[(time.time(),),(time.time(),)])
        self.assertFalse(local_session.resume(self.c,self.g)["ok"])
        self.g.verify_session.assert_not_called()
        with mock.patch.object(local_session.time,"time",return_value=time.time()+3601):
            self.assertTrue(local_session.resume(self.c,self.g)["ok"])
        with sqlite3.connect(self.g.BUDGET_DB) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM requests").fetchone()[0],2)

    def test_failed_or_changed_session_cannot_remove_a_hold(self):
        self.hold("SESSION_UNAVAILABLE");before=self.g.RISK_HOLD.read_bytes()
        self.g.verify_session.side_effect=RuntimeError("private details")
        result=local_session.resume(self.c,self.g)
        self.assertEqual(result["error"]["code"],"SESSION_UNAVAILABLE")
        self.assertEqual(self.g.RISK_HOLD.read_bytes(),before)
        self.g.verify_session.side_effect=lambda:self.hold("ACCOUNT_RISK")
        self.assertFalse(local_session.resume(self.c,self.g)["ok"])
        self.assertEqual(json.loads(self.g.RISK_HOLD.read_text())["code"],"ACCOUNT_RISK")


if __name__ == "__main__":
    unittest.main()
