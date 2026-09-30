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

    def test_app_only_upgrade_updates_metadata_without_changing_credentials(self):
        store=mock.Mock()
        original={"cookies":{"wt2":"same-session","other":"preserved"},"user_agent":"Chrome/150 heyi-recruiter/0.1.8 Electron/44","stoken":"unchanged"}
        store.load.return_value=original
        agent="Chrome/150 heyi-recruiter/0.1.9 Electron/44"
        self.g.browser_session_snapshot.return_value=({"same-session"},agent)
        self.assertTrue(local_session.resume(self.c,self.g,store)["ok"])
        store.save.assert_called_once_with({**original,"user_agent":agent})
        self.g.verify_session.assert_called_once_with({**original,"user_agent":agent})
        self.assertEqual(original["user_agent"],"Chrome/150 heyi-recruiter/0.1.8 Electron/44")

    def test_changed_cookie_or_browser_environment_never_rewrites_saved_token(self):
        for values,agent in [({"different"},"Chrome/150 heyi-recruiter/0.1.9 Electron/44"),
                              ({"same"},"Chrome/151 heyi-recruiter/0.1.9 Electron/44")]:
            store=mock.Mock();store.load.return_value={"cookies":{"wt2":"same"},"user_agent":"Chrome/150 heyi-recruiter/0.1.8 Electron/44"}
            self.g.browser_session_snapshot.return_value=(values,agent)
            self.g.verify_session.side_effect=RuntimeError("not equal")
            self.assertFalse(local_session.resume(self.c,self.g,store)["ok"])
            store.save.assert_not_called()


if __name__ == "__main__":
    unittest.main()
