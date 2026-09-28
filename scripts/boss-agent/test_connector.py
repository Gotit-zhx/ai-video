import importlib.util
import contextlib
import json
import pathlib
import sqlite3
import subprocess
import tempfile
import types
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

spec = importlib.util.spec_from_file_location("connector", pathlib.Path(__file__).with_name("connector.py"))
connector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(connector)


class FakeAuth(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        cookie = self.headers.get("Cookie")
        if not cookie:
            self.send_response(401)
            self.end_headers()
            return
        user = {"username": "13800000000" if cookie == "session=owner" else "13900000000",
                "organizationCode": "heyi", "platformAdmin": True, "roles": ["ADMIN"]}
        body = json.dumps({"success": True, "data": user}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class ConnectorTest(unittest.TestCase):
    def setUp(self):
        # Tests may run under the production service account. Never touch its
        # real hold marker, resume tracker or encrypted credential directory.
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = pathlib.Path(folder.name)
        for name, path in (("RISK_HOLD", root / "risk"),
                           ("DISCONNECTED", root / "disconnected"),
                           ("RESUME_DB", root / "resumes.sqlite3"),
                           ("RESUME_AUTO_PAUSE", root / "resume-auto-pause"),
                           ("LOGIN_ATTEMPTS_DB", root / "login-attempts.sqlite3"),
                           ("AUTH_DIR", root / "auth"),
                           ("CHROME_PROFILE", root / "chrome-profile")):
            patch = mock.patch.object(connector, name, path)
            patch.start()
            self.addCleanup(patch.stop)

    def test_recovery_permit_is_explicit_and_frozen_to_original_hold(self):
        with mock.patch.dict(connector.LOGIN), mock.patch.object(connector.RECOVERY, "active", False, create=True):
            connector.pause_requests("ACCOUNT_RISK")
            self.assertNotIn("HEYI_HR_RECOVERY_HOLD", connector.cli_environment())
            connector.start_recovery_scope()
            original = connector.cli_environment()["HEYI_HR_RECOVERY_HOLD"]
            connector.RISK_HOLD.write_text('{"code":"ENVIRONMENT_RISK"}')
            self.assertEqual(original, connector.cli_environment()["HEYI_HR_RECOVERY_HOLD"])
            connector.RECOVERY.active = False

    def test_new_risk_during_successful_probe_is_not_cleared(self):
        def cli(*args, **kwargs):
            if args == ("status",):
                return {"ok": True}
            connector.pause_requests("ACCOUNT_RISK")
            return {"ok": True, "command": "recruiter-jobs-list"}
        with mock.patch.dict(connector.LOGIN), mock.patch.object(connector, "cli_json", side_effect=cli):
            self.assertEqual((False, "ACCOUNT_RISK"), connector.recruiter_probe())
            self.assertEqual("ACCOUNT_RISK", connector.held_code())

    def test_observation_pause_prevents_old_account_resume_polling(self):
        now = int(connector.time.time())
        with connector.resume_db() as db:
            db.execute("INSERT INTO resume_requests "
                       "(friend_id,requested_at,baseline_mid,state,updated_at) "
                       "VALUES ('12345',?,10,'waiting',?)", (now, now))
        connector.RESUME_AUTO_PAUSE.touch()
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "cli_json") as cli:
            connector.poll_resumes_once()
        cli.assert_not_called()
        self.assertEqual("waiting", connector.tracked_resumes()[0]["state"])

    def test_chat_preparation_rechecks_hold_after_budget_before_navigation(self):
        module = types.ModuleType("guarded_cli")
        module.install_policy = mock.Mock()
        module.request_trace = mock.MagicMock()
        module.assert_requests_allowed = mock.Mock(side_effect=[None, RuntimeError("paused")])
        module.reserve_request = mock.Mock()
        module.verify_session = mock.Mock()
        with mock.patch.dict("sys.modules", {"guarded_cli": module}), \
             mock.patch.object(connector, "prepare_recruiter_chat_page") as navigate:
            self.assertFalse(connector.prepare_recruiter_chat_result()["ok"])
            navigate.assert_not_called()
            module.verify_session.assert_not_called()
            module.reserve_request.assert_called_once()

    def test_chat_preparation_success_reserves_once_and_verifies_before_navigation(self):
        module = types.ModuleType("guarded_cli")
        module.install_policy = mock.Mock()
        module.request_trace = mock.MagicMock()
        module.assert_requests_allowed = mock.Mock()
        module.reserve_request = mock.Mock()
        module.verify_session = mock.Mock()
        with mock.patch.dict("sys.modules", {"guarded_cli": module}), \
             mock.patch.object(connector, "prepare_recruiter_chat_page",
                               return_value={"ok": True}) as navigate:
            self.assertTrue(connector.prepare_recruiter_chat_result()["ok"])
            module.reserve_request.assert_called_once()
            module.verify_session.assert_called_once()
            navigate.assert_called_once()

    def test_chat_preparation_budget_failure_stops_before_navigation(self):
        from guarded_cli import BudgetExhausted
        module = types.ModuleType("guarded_cli")
        module.install_policy = mock.Mock()
        module.request_trace = mock.MagicMock()
        module.assert_requests_allowed = mock.Mock()
        module.reserve_request = mock.Mock(side_effect=BudgetExhausted("budget"))
        module.verify_session = mock.Mock()
        with mock.patch.dict("sys.modules", {"guarded_cli": module}), \
             mock.patch.object(connector, "prepare_recruiter_chat_page") as navigate:
            self.assertEqual({"ok": False, "code": "REQUEST_BUDGET_EXHAUSTED"},
                             connector.prepare_recruiter_chat_result())
            navigate.assert_not_called()
            module.verify_session.assert_not_called()

    def test_disconnect_preserves_all_risk_holds_and_session_checks(self):
        for code in ("ACCOUNT_RISK", "ENVIRONMENT_RISK", "SESSION_MISMATCH"):
            with self.subTest(code=code), mock.patch.dict(connector.LOGIN), \
                 mock.patch.object(connector, "LOGIN_PROCESS", None), \
                 mock.patch.object(connector, "discard_login"):
                connector.pause_requests(code)
                connector.logout_boss()
                self.assertEqual(code, connector.held_code())
        connector.LOGIN_CANCEL.clear()

    def test_official_pc_restriction_precedes_scan_success_and_persists(self):
        text = ("扫描成功\n请在App端确认登录\n安全提示\n"
                "暂为您关闭PC端登录权限\n预计开启时间：2026-09-29 13:44:43")
        restriction = connector.login_restriction_from_text(text)
        self.assertEqual({"code": "PC_LOGIN_RESTRICTED", "retry_at": "2026-09-29 13:44:43"},
                         restriction)
        with mock.patch.dict(connector.LOGIN, {"state": "pending", "phase": "scanned"}), \
             mock.patch.object(connector.time, "time", return_value=1790578000), \
             mock.patch.object(connector.threading, "Thread") as start:
            connector.pause_requests(restriction["code"], retry_at=restriction["retry_at"])
            connector.LOGIN.update(state="idle", phase="")
            connector.restore_login_state()
            snapshot = connector.login_snapshot()
            self.assertEqual("verification_pending", snapshot["state"])
            self.assertEqual("2026-09-29 13:44:43", snapshot["retry_at"])
            self.assertTrue(snapshot["retry_allowed"])
            self.assertIn("电脑端登录权限", snapshot["error"])
            start.assert_not_called()
            self.assertEqual("pending", connector.begin_login()["state"])
            start.assert_called_once_with(target=connector.login_worker, daemon=True)
            self.assertEqual("PC_LOGIN_RESTRICTED", connector.held_code())

    def test_pc_restriction_date_does_not_automatically_release_requests(self):
        with mock.patch.dict(connector.LOGIN, {"state": "pending", "phase": "scanned"}), \
             mock.patch.object(connector.time, "time", return_value=1790740800), \
             mock.patch.object(connector.subprocess, "run") as cli, \
             mock.patch.object(connector.threading, "Thread") as worker:
            connector.pause_requests("PC_LOGIN_RESTRICTED", retry_at="2026-09-29 13:44:43")
            self.assertTrue(connector.login_snapshot()["retry_allowed"])
            self.assertEqual("PC_LOGIN_RESTRICTED", connector.held_code())
            self.assertFalse(connector.execute({"operation": "jobs"})["ok"])
            cli.assert_not_called()
            self.assertEqual("pending", connector.begin_login()["state"])
            worker.assert_called_once_with(target=connector.login_worker, daemon=True)
            self.assertEqual("PC_LOGIN_RESTRICTED", connector.held_code())

    def test_pc_restriction_without_valid_time_keeps_manual_retry_available(self):
        for text in ("暂为您关闭PC端登录权限",
                     "暂为您关闭PC端登录权限\n预计开启时间：2026-99-99 24:99:99"):
            restriction = connector.login_restriction_from_text(text)
            self.assertEqual("", restriction["retry_at"])
            with mock.patch.dict(connector.LOGIN):
                connector.pause_requests(restriction["code"], retry_at=restriction["retry_at"])
                self.assertTrue(connector.login_snapshot()["retry_allowed"])
                self.assertEqual("PC_LOGIN_RESTRICTED", connector.held_code())

    def test_realname_linked_restriction_overrides_scanned_status(self):
        restriction = connector.login_restriction_from_text(
            "扫描成功\n请在App端确认登录\n安全提示\n"
            "您好，由于您实名绑定的上一账号，关闭了PC端登录权限。"
            "在上一账号PC端登录权限恢复正常前，您实名认证的当前账号也做同步限制。\n"
            "预计开启时间：2026-09-29 13:44:05")
        self.assertEqual({"code": "PC_LOGIN_RESTRICTED",
                          "retry_at": "2026-09-29 13:44:05",
                          "reason": "REALNAME_LINKED_ACCOUNT"}, restriction)
        with mock.patch.dict(connector.LOGIN, {"state": "pending", "phase": "scanned"}):
            connector.pause_requests(**restriction)
            state = connector.login_snapshot()
            self.assertEqual("verification_pending", state["state"])
            self.assertEqual("", state["phase"])
            self.assertTrue(state["retry_allowed"])
            self.assertIn("实名绑定的上一账号", state["error"])
            self.assertIn("2026-09-29 13:44:05", state["error"])
            # A subsequent local safety error must not erase the platform reason.
            connector.pause_requests("SESSION_MISMATCH")
            self.assertEqual("REALNAME_LINKED_ACCOUNT", connector.pc_login_restriction_reason())

    def test_pc_restriction_recognizes_completed_and_reversed_wording(self):
        for text in ("关闭了PC端登录权限", "关闭了电脑端登录权限",
                     "PC端登录权限已关闭", "电脑端登录权限被关闭"):
            with self.subTest(text=text):
                self.assertEqual("PC_LOGIN_RESTRICTED",
                                 connector.login_restriction_from_text(text)["code"])

    def test_linked_restriction_stops_wait_without_next_platform_request(self):
        process = mock.Mock()
        process.communicate.side_effect = subprocess.TimeoutExpired("login", 5)
        with mock.patch.dict(connector.LOGIN, {"state": "pending", "phase": "scanned"}), \
             mock.patch.object(connector, "observe_login_page", return_value={
                 "code": "PC_LOGIN_RESTRICTED", "retry_at": "2026-09-29 13:44:05",
                 "reason": "REALNAME_LINKED_ACCOUNT"}), \
             mock.patch.object(connector, "stop_login_process") as stop, \
             mock.patch.object(connector, "close_browser") as close, \
             mock.patch.object(connector, "cli_json") as cli:
            connector.LOGIN_CANCEL.clear()
            self.addCleanup(connector.LOGIN_CANCEL.clear)
            self.assertEqual(b"", connector.wait_for_login_process(process))
            stop.assert_called_once_with(process)
            close.assert_not_called()
            cli.assert_not_called()
            self.assertEqual("verification_pending", connector.LOGIN["state"])
            self.assertEqual("REALNAME_LINKED_ACCOUNT", connector.pc_login_restriction_reason())
        self.assertIsNone(connector.login_restriction_from_text("扫描成功，请在App端确认登录"))

    def test_login_safety_page_is_distinguished_from_normal_sms_login(self):
        self.assertIsNone(connector.login_restriction_from_text("验证码登录/注册\n扫码登录"))
        for text, code in (("扫描成功\n请完成安全验证", "SECURITY_VERIFICATION_REQUIRED"),
                           ("您的IP地址存在异常行为", "IP_RESTRICTED"),
                           ("访问环境异常", "ENVIRONMENT_RISK"),
                           ("账号存在风险", "ACCOUNT_RISK")):
            self.assertEqual(code, connector.login_restriction_from_text(text)["code"])

    def test_pc_restriction_stops_cli_even_without_frontend_polling(self):
        process = mock.Mock()
        process.communicate.side_effect = subprocess.TimeoutExpired("login", 5)
        with mock.patch.dict(connector.LOGIN, {"state": "pending", "phase": "scanned"}), \
             mock.patch.object(connector, "observe_login_page", return_value={
                 "code": "PC_LOGIN_RESTRICTED", "retry_at": "2026-09-29 13:44:43"}), \
             mock.patch.object(connector, "stop_login_process") as stop, \
             mock.patch.object(connector, "close_browser") as close:
            connector.LOGIN_CANCEL.clear()
            self.addCleanup(connector.LOGIN_CANCEL.clear)
            self.assertEqual(b"", connector.wait_for_login_process(process))
            stop.assert_called_once_with(process)
            close.assert_not_called()
            self.assertEqual("verification_pending", connector.LOGIN["state"])
            self.assertEqual("PC_LOGIN_RESTRICTED", connector.LOGIN["failure_code"])

    def test_killing_cli_descendants_has_a_bounded_pipe_cleanup(self):
        process = mock.Mock(pid=1234)
        process.communicate.side_effect = subprocess.TimeoutExpired("login", 5)
        with mock.patch.object(connector.os, "killpg") as kill:
            connector.stop_login_process(process)
        self.assertEqual(2, kill.call_count)
        self.assertEqual([mock.call(timeout=5), mock.call(timeout=5)],
                         process.communicate.call_args_list)
        process.stdout.close.assert_called_once()
        process.stderr.close.assert_called_once()

    def test_unexpected_cdp_failure_never_leaves_login_pending(self):
        with mock.patch.dict(connector.LOGIN, {"state": "pending", "phase": "qr"}), \
             mock.patch.object(connector, "browser_service"), \
             mock.patch.object(connector, "wait_for_cdp", side_effect=RuntimeError("secret")):
            connector.LOGIN_CANCEL.clear()
            connector.login_worker()
            self.assertEqual("failed", connector.LOGIN["state"])
            self.assertEqual("LOGIN_RUNTIME_ERROR", connector.LOGIN["failure_code"])
            self.assertNotIn("secret", connector.LOGIN["error"])

    def test_disconnect_cannot_erase_official_pc_restriction(self):
        with mock.patch.dict(connector.LOGIN, {"state": "verification_pending"}), \
             mock.patch.object(connector, "LOGIN_PROCESS", None), \
             mock.patch.object(connector, "discard_login"):
            connector.pause_requests("PC_LOGIN_RESTRICTED", retry_at="2026-09-29 13:44:43")
            connector.logout_boss()
            self.assertEqual("PC_LOGIN_RESTRICTED", connector.held_code())
        connector.LOGIN_CANCEL.clear()

    def test_manual_attempts_are_recorded_without_historical_count_lockout(self):
        with mock.patch.object(connector.time, "time", return_value=10000):
            for _ in range(5):
                connector.reserve_login_attempt()
            with contextlib.closing(sqlite3.connect(connector.LOGIN_ATTEMPTS_DB)) as db:
                self.assertEqual(5, db.execute("SELECT COUNT(*) FROM attempts").fetchone()[0])
        with mock.patch.object(connector.time, "time", return_value=11801):
            connector.reserve_login_attempt()

    def test_duplicate_start_does_not_consume_another_login_attempt(self):
        with mock.patch.dict(connector.LOGIN, {"state": "pending"}), \
             mock.patch.object(connector, "reserve_login_attempt") as reserve:
            self.assertEqual("pending", connector.begin_login()["state"])
            reserve.assert_not_called()

    def test_new_identical_official_refusal_invalidates_manual_retry_permit(self):
        with mock.patch.dict(connector.LOGIN):
            connector.pause_requests("PC_LOGIN_RESTRICTED", retry_at="2026-09-29 13:44:43")
            connector.start_recovery_scope()
            try:
                original = connector.cli_environment()["HEYI_HR_RECOVERY_HOLD"]
                connector.pause_requests("PC_LOGIN_RESTRICTED", retry_at="2026-09-29 13:44:43")
                self.assertNotEqual(original, connector.hashlib.sha256(connector.RISK_HOLD.read_bytes()).hexdigest())
            finally:
                connector.RECOVERY.active = False

    def test_historical_restriction_does_not_kill_fresh_manual_login_process(self):
        process = mock.Mock()
        process.communicate.return_value = (b'{"ok":true}', b"")
        with mock.patch.dict(connector.LOGIN), mock.patch.object(connector, "stop_login_process") as stop:
            connector.pause_requests("PC_LOGIN_RESTRICTED", retry_at="2026-09-29 13:44:43")
            connector.LOGIN_CANCEL.clear()
            self.assertEqual(b'{"ok":true}', connector.wait_for_login_process(process))
            stop.assert_not_called()

    def test_disconnect_is_local_and_remains_disconnected_after_restart(self):
        connector.AUTH_DIR.mkdir()
        session = connector.AUTH_DIR / "session.enc"
        salt = connector.AUTH_DIR / "salt"
        session.write_bytes(b"synthetic-session")
        salt.write_bytes(b"synthetic-salt")
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "LOGIN_PROCESS", None), \
             mock.patch.object(connector, "close_browser") as close, \
             mock.patch.object(connector, "cli_json") as cli, \
             mock.patch.object(connector.subprocess, "run") as run:
            connector.logout_boss()
            self.assertFalse(session.exists())
            self.assertEqual(b"synthetic-salt", salt.read_bytes())
            self.assertTrue(connector.DISCONNECTED.is_file())
            close.assert_called_once()
            cli.assert_not_called()
            connector.LOGIN["state"] = "idle"
            connector.restore_login_state()
            self.assertEqual("disconnected", connector.LOGIN["state"])
            self.assertEqual("SESSION_DISCONNECTED",
                             connector.execute({"operation": "status"})["result"]["error"]["code"])
            self.assertFalse(connector.execute({"operation": "jobs"})["ok"])
            run.assert_not_called()
        connector.LOGIN_CANCEL.clear()

    def test_explicit_connect_is_required_to_remove_disconnect_marker(self):
        connector.DISCONNECTED.touch()
        connector.RISK_HOLD.touch()
        with mock.patch.dict(connector.LOGIN, {"state": "idle"}), \
             mock.patch.object(connector.threading, "Thread") as worker:
            connector.restore_login_state()
            self.assertEqual("disconnected", connector.LOGIN["state"])
            worker.assert_not_called()
            result = connector.begin_login()
            self.assertEqual("pending", result["state"])
            self.assertEqual("qr", result["phase"])
            self.assertFalse(connector.DISCONNECTED.exists())
            worker.assert_called_once_with(target=connector.login_worker, daemon=True)
            worker.return_value.start.assert_called_once()

    def test_reset_removes_persisted_boss_cookies_after_browser_stops(self):
        databases = [connector.CHROME_PROFILE / "Default/Cookies",
                     connector.CHROME_PROFILE / "Profile 1/Network/Cookies"]
        for database in databases:
            database.parent.mkdir(parents=True)
            with contextlib.closing(sqlite3.connect(database)) as connection, connection:
                connection.execute("CREATE TABLE cookies (host_key TEXT, name TEXT)")
                connection.executemany("INSERT INTO cookies VALUES (?, ?)", [
                    ("zhipin.com", "wt2"), (".zhipin.com", "wt2"),
                    ("www.zhipin.com", "wt2"), ("sub.www.zhipin.com", "other"),
                    ("other.example", "wt2"), ("notzhipin.com", "wt2"),
                    ("zhipin.com.example", "wt2")])
        events = []
        clear_stored = connector.clear_stored_boss_cookies
        def clear_after_stop():
            self.assertEqual(["stop"], events)
            events.append("disk")
            clear_stored()
        with mock.patch.object(connector, "close_browser", side_effect=lambda: events.append("stop")), \
             mock.patch.object(connector, "clear_stored_boss_cookies", side_effect=clear_after_stop):
            connector.discard_login()
        for database in databases:
            with contextlib.closing(sqlite3.connect(database)) as connection:
                remaining = connection.execute("SELECT host_key FROM cookies").fetchall()
            self.assertEqual([("other.example",), ("notzhipin.com",),
                              ("zhipin.com.example",)], remaining)
        self.assertEqual(["stop", "disk"], events)

    def test_reset_fails_closed_if_persisted_cookie_cleanup_fails(self):
        database = connector.CHROME_PROFILE / "Default/Cookies"
        database.parent.mkdir(parents=True)
        database.write_bytes(b"not a sqlite database")
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "LOGIN_PROCESS", None), \
             mock.patch.object(connector, "close_browser"):
            with self.assertRaisesRegex(OSError, "旧会话清理失败"):
                connector.logout_boss()
            self.assertTrue(connector.DISCONNECTED.is_file())
            self.assertEqual("disconnected", connector.LOGIN["state"])
        connector.LOGIN_CANCEL.clear()

    def test_cookie_database_is_untouched_if_browser_cannot_stop(self):
        with mock.patch.object(connector, "close_browser", side_effect=OSError("still running")), \
             mock.patch.object(connector, "clear_stored_boss_cookies") as clear:
            with self.assertRaises(OSError):
                connector.discard_login()
            clear.assert_not_called()

    def test_cookie_cleanup_does_not_follow_profile_symlinks(self):
        outside = connector.CHROME_PROFILE.parent / "outside"
        outside.mkdir()
        (outside / "Cookies").write_bytes(b"untouched")
        connector.CHROME_PROFILE.mkdir()
        (connector.CHROME_PROFILE / "Default").symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(OSError, "会话目录异常"):
            connector.clear_stored_boss_cookies()
        self.assertEqual(b"untouched", (outside / "Cookies").read_bytes())

    def test_cookie_cleanup_does_not_create_missing_profile(self):
        connector.clear_stored_boss_cookies()
        self.assertFalse(connector.CHROME_PROFILE.exists())

    def test_disconnected_resume_polling_does_not_contact_platform(self):
        with connector.resume_db() as connection:
            connection.execute("INSERT INTO resume_requests "
                               "(friend_id,requested_at,baseline_mid,state,message_id,updated_at,error) VALUES "
                               "('12345', 1, 0, 'waiting', NULL, 1, '')")
        with mock.patch.dict(connector.LOGIN, {"state": "disconnected"}), \
             mock.patch.object(connector, "cli_json") as cli:
            connector.poll_resumes_once()
            cli.assert_not_called()
            self.assertEqual("waiting", connector.tracked_resumes()[0]["state"])

    def test_failed_disconnect_cleanup_does_not_enable_automatic_reconnect(self):
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "LOGIN_PROCESS", None), \
             mock.patch.object(connector, "discard_login", side_effect=OSError("cleanup failed")):
            with self.assertRaises(OSError):
                connector.logout_boss()
            self.assertTrue(connector.DISCONNECTED.is_file())
            self.assertEqual("disconnected", connector.LOGIN["state"])
        connector.LOGIN_CANCEL.clear()

    def test_queued_read_cannot_contact_platform_after_disconnect(self):
        def acquired(**_kwargs):
            connector.LOGIN["state"] = "disconnected"
            return True
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "LOCK") as lock, \
             mock.patch.object(connector.subprocess, "run") as run:
            lock.acquire.side_effect = acquired
            response = connector.execute({"operation": "jobs"})
            self.assertEqual("SESSION_DISCONNECTED", response["error"]["code"])
            run.assert_not_called()
            lock.release.assert_called_once()

    def test_resume_preserves_realistic_long_security_and_tilde_entity_ids(self):
        security = "a" * 700 + "~" + "B" * 45 + "=="
        args = connector.command({"operation": "resume", "geekId": "~geek-id~test",
                                  "jobId": "job-test", "securityId": security})
        self.assertEqual(["hr", "resume", "~geek-id~test", "--job-id", "job-test",
                          "--security-id", security], args)
        self.assertEqual(748, len(args[-1]))
        self.assertEqual("~job-test", connector.identifier("~job-test"))

    def test_security_id_remains_bounded_and_rejects_argument_injection(self):
        for value in ("", "a" * 4097, "--help", "valid\nid", "id x", "id;whoami",
                      "id$(whoami)", "id`whoami`", "id/../../", "汉字", "token\x00"):
            with self.subTest(value_type=value[:12]), self.assertRaises(ValueError):
                connector.security_identifier(value)
        self.assertEqual("a" * 4096, connector.security_identifier("a" * 4096))
        with self.assertRaises(ValueError):
            connector.identifier("a" * 181)

    def test_http_resume_preserves_long_query_and_keeps_request_limit(self):
        security = "a" * 2300 + "~tail=="
        handler = object.__new__(connector.HttpHandler)
        handler.authorized = mock.Mock(return_value=200)
        handler.send_json = mock.Mock()
        handler.path = "/api/v1/hr/resume?" + urllib.parse.urlencode({
            "geekId": "~geek-test", "jobId": "job-test", "securityId": security})
        with mock.patch.object(connector, "execute", return_value={
                "ok": True, "result": {"ok": True, "data": {}}}) as execute:
            handler.do_GET()
            request = execute.call_args.args[0]
            self.assertEqual(security, request["securityId"])
            self.assertEqual("~geek-test", request["geekId"])
            self.assertEqual(200, handler.send_json.call_args.args[0])
            self.assertEqual(security, connector.command(request)[-1])
            execute.reset_mock()
            handler.authorized.reset_mock()
            handler.path = "/api/v1/hr/resume?" + "a" * connector.MAX_REQUEST
            handler.do_GET()
            self.assertEqual(414, handler.send_json.call_args.args[0])
            handler.authorized.assert_not_called()
            execute.assert_not_called()

    def test_scanned_qr_is_never_reloaded_or_clicked_again(self):
        page = mock.MagicMock()
        page.url = "https://www.zhipin.com/web/user/"
        box = page.locator.return_value.first
        page.locator.return_value.inner_text.return_value = ""
        box.inner_text.return_value = "已扫码，请在手机上确认"
        context = mock.MagicMock()
        context.cookies.return_value = []
        context.pages = [page]
        browser = mock.MagicMock()
        browser.contexts = [context]
        browser_api = mock.MagicMock()
        browser_api.chromium.connect_over_cdp.return_value = browser
        playwright = mock.MagicMock()
        playwright.__enter__.return_value = browser_api
        module = types.ModuleType("patchright.sync_api")
        module.sync_playwright = mock.Mock(return_value=playwright)
        with mock.patch.dict("sys.modules", {"patchright.sync_api": module}), \
             mock.patch.dict(connector.LOGIN, {"phase": "qr"}):
            with self.assertRaises(connector.ScanInProgress):
                connector.qr_image()
            self.assertEqual("scanned", connector.LOGIN["phase"])
        page.reload.assert_not_called()
        page.get_by_text.assert_not_called()

    def test_fresh_scan_closes_only_old_unauthenticated_login_tabs(self):
        login = mock.MagicMock(url="https://www.zhipin.com/web/user/")
        chat = mock.MagicMock(url="https://www.zhipin.com/web/chat/index")
        external = mock.MagicMock(url="https://example.invalid/web/user/")
        context = mock.MagicMock()
        context.cookies.return_value = []
        context.pages = [login, chat, external]
        authenticated = mock.MagicMock()
        authenticated.cookies.return_value = [{"name": "wt2", "value": "synthetic", "domain": ".zhipin.com"}]
        authenticated.pages = [mock.MagicMock(url="https://www.zhipin.com/web/user/")]
        browser = mock.MagicMock()
        browser.contexts = [context, authenticated]
        playwright = mock.MagicMock()
        playwright.__enter__.return_value.chromium.connect_over_cdp.return_value = browser
        module = types.ModuleType("patchright.sync_api")
        module.sync_playwright = mock.Mock(return_value=playwright)
        with mock.patch.dict("sys.modules", {"patchright.sync_api": module}):
            connector.prepare_new_scan()
        login.close.assert_called_once_with(run_before_unload=False)
        chat.close.assert_not_called()
        external.close.assert_not_called()
        authenticated.pages[0].close.assert_not_called()
        context.clear_cookies.assert_not_called()
        context.new_page.assert_not_called()

    def test_qr_poll_reports_safety_dialog_before_scanned_text(self):
        page = mock.MagicMock()
        page.url = "https://www.zhipin.com/web/user/"
        page.locator.return_value.inner_text.return_value = (
            "扫描成功\n请在App端确认登录\n安全提示\n暂为您关闭PC端登录权限\n"
            "预计开启时间：2026-09-29 13:44:43")
        context = mock.MagicMock()
        context.cookies.return_value = []
        context.pages = [page]
        browser = mock.MagicMock()
        browser.contexts = [context]
        browser_api = mock.MagicMock()
        browser_api.chromium.connect_over_cdp.return_value = browser
        playwright = mock.MagicMock()
        playwright.__enter__.return_value = browser_api
        module = types.ModuleType("patchright.sync_api")
        module.sync_playwright = mock.Mock(return_value=playwright)
        with mock.patch.dict("sys.modules", {"patchright.sync_api": module}), \
             mock.patch.dict(connector.LOGIN, {"state": "pending", "phase": "scanned"}):
            self.addCleanup(connector.LOGIN_CANCEL.clear)
            with self.assertRaises(connector.LoginRestricted):
                connector.qr_image(force_refresh=True)
            self.assertEqual("verification_pending", connector.LOGIN["state"])
            self.assertEqual("PC_LOGIN_RESTRICTED", connector.LOGIN["failure_code"])
            page.locator.assert_called_once_with("body")
        page.reload.assert_not_called()
        page.screenshot.assert_not_called()

    def test_scanned_page_is_detected_after_boss_removes_qr_container(self):
        page = mock.MagicMock()
        page.url = "https://www.zhipin.com/web/user/"
        body = mock.Mock()
        body.inner_text.return_value = "扫描成功\n请在App端确认登录"
        def locate(selector):
            self.assertEqual("body", selector, "must not wait for removed QR container")
            return body
        page.locator.side_effect = locate
        context = mock.MagicMock()
        context.cookies.return_value = []
        context.pages = [page]
        browser = mock.MagicMock()
        browser.contexts = [context]
        browser_api = mock.MagicMock()
        browser_api.chromium.connect_over_cdp.return_value = browser
        playwright = mock.MagicMock()
        playwright.__enter__.return_value = browser_api
        module = types.ModuleType("patchright.sync_api")
        module.sync_playwright = mock.Mock(return_value=playwright)
        with mock.patch.dict("sys.modules", {"patchright.sync_api": module}), \
             mock.patch.dict(connector.LOGIN, {"phase": "qr"}):
            with self.assertRaises(connector.ScanInProgress):
                connector.qr_image(force_refresh=True)
            self.assertEqual("scanned", connector.LOGIN["phase"])
        page.reload.assert_not_called()

    def test_expired_qr_uses_own_refresh_control(self):
        page = mock.MagicMock()
        page.url = "https://www.zhipin.com/web/user/"
        box = page.locator.return_value.first
        page.locator.return_value.inner_text.return_value = ""
        box.inner_text.return_value = "请重新刷新二维码"
        control = mock.MagicMock()
        control.count.return_value = 1
        def locate(value):
            if value == ".invalid-box button":
                return control
            if value == ".invalid-box":
                return mock.MagicMock()
            image = mock.MagicMock()
            image.screenshot.return_value = b"\x89PNG\r\n\x1a\n" + b"sample"
            return image
        box.locator.side_effect = locate
        context = mock.MagicMock()
        context.cookies.return_value = []
        context.pages = [page]
        browser = mock.MagicMock()
        browser.contexts = [context]
        browser_api = mock.MagicMock()
        browser_api.chromium.connect_over_cdp.return_value = browser
        playwright = mock.MagicMock()
        playwright.__enter__.return_value = browser_api
        module = types.ModuleType("patchright.sync_api")
        module.sync_playwright = mock.Mock(return_value=playwright)
        with mock.patch.dict("sys.modules", {"patchright.sync_api": module}):
            self.assertTrue(connector.qr_image().startswith(b"\x89PNG"))
        control.click.assert_called_once()
        page.reload.assert_not_called()

    def test_delayed_resume_attachment_is_tracked_without_resending_request(self):
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(connector, "RESUME_DB", pathlib.Path(folder) / "requests.sqlite3"), \
             mock.patch.dict(connector.LOGIN, {"state": "complete"}):
            before = {"ok": True, "data": {"messages": [{"mid": 10}]}}
            sent = {"ok": True, "command": "recruiter-request-resume",
                    "data": {"requested": True}}
            with mock.patch.object(connector, "cli_json", side_effect=[before, sent]) as cli, \
                 mock.patch.object(connector, "prepare_recruiter_chat") as prepare:
                result = connector.request_resume("12345")
                self.assertEqual("waiting", result["state"])
                requested_at = result["requested_at"]
                self.assertGreater(requested_at, 0)
                # Each read opens a fresh SQLite connection, as after a restart.
                self.assertEqual(requested_at, connector.tracked_resumes()[0]["requested_at"])
                self.assertEqual("waiting", connector.tracked_resumes()[0]["state"])
                self.assertEqual(2, cli.call_count)
                prepare.assert_called_once()
            with mock.patch.object(connector, "cli_json") as cli:
                with self.assertRaisesRegex(ValueError, "避免重复索取"):
                    connector.request_resume("12345")
                cli.assert_not_called()
            later = {"ok": True, "data": {"messages": [
                {"mid": 11, "from": {"uid": 12345, "source": 0},
                 "body": {"hyperLink": {"hyperLinkType": 1,
                                        "url": "https://www.zhipin.com/resume?id=one"}}},
            ]}}
            with mock.patch.object(connector, "cli_json", return_value=later):
                connector.poll_resumes_once()
            self.assertEqual("received", connector.tracked_resumes()[0]["state"])
            self.assertEqual("11", connector.tracked_resumes()[0]["message_id"])
            self.assertEqual(requested_at, connector.tracked_resumes()[0]["requested_at"])

    def test_chat_preflight_failure_never_sends_or_creates_uncertain_request(self):
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "prepare_recruiter_chat", side_effect=RuntimeError("未发送")), \
             mock.patch.object(connector, "cli_json") as cli:
            with self.assertRaisesRegex(RuntimeError, "未发送"):
                connector.request_resume("12345")
            cli.assert_not_called()
            self.assertEqual([], connector.tracked_resumes())

    def test_request_records_attempt_before_send_and_preserves_real_failure_code(self):
        before = {"ok": True, "data": {"messages": [{"mid": 10}]}}
        def execute(*args, **kwargs):
            if args[1] == "chatmsg":
                return before
            self.assertEqual("uncertain", connector.tracked_resumes()[0]["state"])
            return {"ok": False, "error": {"code": "RECRUITER_CHAT_TAB_REQUIRED"}}
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "prepare_recruiter_chat"), \
             mock.patch.object(connector, "cli_json", side_effect=execute):
            with self.assertRaisesRegex(RuntimeError, "RECRUITER_CHAT_TAB_REQUIRED"):
                connector.request_resume("12345")
        self.assertEqual("failed", connector.tracked_resumes()[0]["state"])

    def test_uncertain_request_requires_explicit_retry_and_is_sent_only_once(self):
        with connector.resume_db() as connection:
            connection.execute("INSERT INTO resume_requests "
                               "(friend_id,requested_at,baseline_mid,state,message_id,updated_at,error) VALUES "
                               "('12345', 1, 10, 'uncertain', NULL, 1, '')")
        before = {"ok": True, "data": {"messages": [{"mid": 10}]}}
        sent = {"ok": True, "data": {"requested": True}}
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "prepare_recruiter_chat") as prepare, \
             mock.patch.object(connector, "cli_json", side_effect=[before, sent]) as cli:
            with self.assertRaisesRegex(ValueError, "避免重复索取"):
                connector.request_resume("12345")
            cli.assert_not_called()
            prepare.assert_not_called()
            self.assertEqual("waiting", connector.request_resume("12345", retry_uncertain=True)["state"])
            self.assertEqual(1, sum(call.args[1] == "request-resume" for call in cli.call_args_list))
            with self.assertRaises(ValueError):
                connector.request_resume("12345", retry_uncertain=True)

    def test_ambiguous_resume_response_stays_uncertain_without_automatic_resend(self):
        before = {"ok": True, "data": {"messages": []}}
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "prepare_recruiter_chat"), \
             mock.patch.object(connector, "cli_json", side_effect=[
                 before, {"ok": False, "error": {"code": "NETWORK_ERROR"}}, before]) as cli:
            with self.assertRaisesRegex(RuntimeError, "NETWORK_ERROR"):
                connector.request_resume("12345")
            self.assertEqual("uncertain", connector.tracked_resumes()[0]["state"])
            self.assertEqual(3, cli.call_count)

    def test_resume_request_requires_explicit_dispatch_confirmation(self):
        before = {"ok": True, "data": {"messages": []}}
        for data in (None, {}, {"requested": False}, {"requested": "true"}):
            with self.subTest(data=data), \
                 mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
                 mock.patch.object(connector, "prepare_recruiter_chat"), \
                 mock.patch.object(connector, "cli_json", side_effect=[
                     before, {"ok": True, "data": data}, before]) as cli:
                with self.assertRaisesRegex(RuntimeError, "REQUEST_UNCONFIRMED"):
                    connector.request_resume("12345", retry_uncertain=True)
                self.assertEqual("uncertain", connector.tracked_resumes()[0]["state"])
                self.assertEqual(3, cli.call_count)

    def test_ambiguous_write_is_confirmed_from_persisted_outbound_message_without_resending(self):
        before = {"ok": True, "data": {"messages": [{"mid": 10}]}}
        delivered = {"ok": True, "data": {"messages": [
            {"mid": 11, "from": {"uid": 98765, "source": 0}, "to": {"uid": 12345, "source": 0},
             "time": 1790567780586, "body": {"type": 1, "text": "方便发一份简历过来吗？"}}]}}
        for failure in ({"ok": False, "error": {"code": "NETWORK_ERROR"}},
                        subprocess.TimeoutExpired("boss", 90)):
            with self.subTest(failure=type(failure).__name__), \
                 mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
                 mock.patch.object(connector, "prepare_recruiter_chat"), \
                 mock.patch.object(connector, "cli_json", side_effect=[before, failure, delivered]) as cli:
                with connector.resume_db() as connection:
                    connection.execute("DELETE FROM resume_requests")
                result = connector.request_resume("12345")
                self.assertEqual("waiting", result["state"])
                self.assertEqual(1790567780, result["confirmed_at"])
                self.assertEqual(1790567780, connector.tracked_resumes()[0]["requested_at"])
                self.assertEqual(1, sum(call.args[1] == "request-resume" for call in cli.call_args_list))
                with self.assertRaises(ValueError):
                    connector.request_resume("12345")

    def test_background_poll_confirms_delayed_send_and_receipt_preserving_request_time(self):
        with connector.resume_db() as connection:
            connection.execute("INSERT INTO resume_requests "
                               "(friend_id,requested_at,baseline_mid,state,updated_at) "
                               "VALUES ('12345',1790567785,10,'uncertain',1790567785)")
        history = {"ok": True, "data": {"messages": [
            {"mid": 11, "from": {"uid": 98765}, "to": {"uid": 12345}, "time": 1790567780586,
             "body": {"type": 1, "text": "方便发一份简历过来吗？"}},
            {"mid": 12, "from": {"uid": 12345, "source": 0},
             "body": {"hyperLink": {"hyperLinkType": 1, "url": "https://www.zhipin.com/resume?id=test"}}},
        ]}}
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "cli_json", return_value=history) as cli:
            connector.poll_resumes_once()
            self.assertEqual(1, cli.call_count)
            self.assertEqual("chatmsg", cli.call_args.args[1])
        record = connector.tracked_resumes()[0]
        self.assertEqual("received", record["state"])
        self.assertEqual(1790567780, record["confirmed_at"])
        self.assertEqual(1790567780, record["requested_at"])

    def test_reconciliation_rejects_old_incoming_other_recipient_and_unrelated_messages(self):
        base = {"mid": 11, "from": {"uid": 98765}, "to": {"uid": 12345}, "time": 1790567780586,
                "body": {"type": 1, "text": "方便发一份简历过来吗？"}}
        with connector.resume_db() as connection:
            connection.execute("INSERT INTO resume_requests "
                               "(friend_id,requested_at,baseline_mid,state,updated_at) "
                               "VALUES ('12345',1790567785,10,'uncertain',1790567785)")
        for change in ({"mid": 10}, {"from": {"uid": 12345}}, {"to": {"uid": 54321}},
                       {"body": {"type": 1, "text": "普通聊天"}}, {"time": None}):
            history = {"ok": True, "data": {"messages": [{**base, **change}]}}
            self.assertFalse(connector.reconcile_resume_request(history, "12345", 10))
            self.assertEqual("uncertain", connector.tracked_resumes()[0]["state"])
            self.assertIsNone(connector.tracked_resumes()[0]["confirmed_at"])

    def test_existing_tracker_migration_preserves_records_and_only_backfills_confirmed_waiting(self):
        connector.RESUME_DB.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(connector.RESUME_DB) as db:
            db.execute("CREATE TABLE resume_requests (friend_id TEXT PRIMARY KEY, requested_at INTEGER NOT NULL,"
                       "baseline_mid INTEGER NOT NULL, state TEXT NOT NULL, message_id TEXT,"
                       "updated_at INTEGER NOT NULL, error TEXT NOT NULL DEFAULT '')")
            db.executemany("INSERT INTO resume_requests VALUES (?,1,0,?,NULL,1,'')",
                           [("12345", "waiting"), ("54321", "uncertain")])
        records = {row["friend_id"]: row for row in connector.tracked_resumes()}
        self.assertEqual(1, records["12345"]["confirmed_at"])
        self.assertIsNone(records["54321"]["confirmed_at"])
        self.assertEqual(2, len(connector.tracked_resumes()))

    def test_chat_preparation_reuses_blank_page_and_only_checks_runtime(self):
        page = mock.MagicMock()
        page.url = "about:blank"
        context = mock.MagicMock()
        context.cookies.return_value = [{"name": "wt2", "value": "synthetic", "domain": ".zhipin.com"}]
        context.pages = [page]
        browser = mock.MagicMock()
        browser.contexts = [context]
        runtime = mock.MagicMock()
        runtime.chromium.connect_over_cdp.return_value = browser
        playwright = mock.MagicMock()
        playwright.__enter__.return_value = runtime
        module = types.ModuleType("patchright.sync_api")
        module.sync_playwright = mock.Mock(return_value=playwright)
        with mock.patch.dict("sys.modules", {"patchright.sync_api": module}):
            self.assertEqual({"ok": True}, connector.prepare_recruiter_chat_page())
            page.goto.assert_called_once_with("https://www.zhipin.com/web/chat/index",
                                              wait_until="domcontentloaded", timeout=25000)
            context.new_page.assert_not_called()
            page.click.assert_not_called()
            page.evaluate.assert_not_called()
            page.close.assert_not_called()
            page.goto.reset_mock()
            page.url = "https://www.zhipin.com/web/chat/index"
            self.assertEqual({"ok": True}, connector.prepare_recruiter_chat_page())
            page.goto.assert_not_called()
            context.cookies.return_value = []
            self.assertEqual("BROWSER_LOGIN_REQUIRED", connector.prepare_recruiter_chat_page()["code"])

    def test_chat_preparation_process_is_bounded_and_reports_unsent(self):
        with mock.patch.object(connector.subprocess, "run", side_effect=subprocess.TimeoutExpired("python", 50)):
            with self.assertRaisesRegex(RuntimeError, "未发送"):
                connector.prepare_recruiter_chat()

    def test_resume_tracking_ignores_old_and_other_party_attachments(self):
        history = {"data": {"messages": [
            {"mid": 10, "from": {"uid": 123, "source": 0},
             "body": {"hyperLink": {"hyperLinkType": 1,
                                    "url": "https://www.zhipin.com/resume?id=old"}}},
            {"mid": 11, "from": {"uid": 999, "source": 0},
             "body": {"hyperLink": {"hyperLinkType": 1,
                                    "url": "https://www.zhipin.com/resume?id=other"}}},
            {"mid": 12, "from": {"uid": 123, "source": 1},
             "body": {"hyperLink": {"hyperLinkType": 1,
                                    "url": "https://www.zhipin.com/resume?id=ours"}}},
        ]}}
        self.assertIsNone(connector.incoming_attachment(history, "123", 10))

    def test_inconclusive_recruiter_probe_preserves_saved_login(self):
        response = mock.Mock(stdout=json.dumps({
            "ok": True, "data": {"logged_in": True,
                                  "auth_health": {"summary": "degraded"}}}).encode())
        with mock.patch.dict(connector.LOGIN, {"state": "idle"}), \
             mock.patch.object(connector.subprocess, "run", return_value=response), \
             mock.patch.object(connector, "recruiter_probe",
                               return_value=(False, "JOBS_UNAVAILABLE")), \
             mock.patch.object(connector, "discard_login") as discard:
            result = connector.execute({"operation": "status"})
            self.assertEqual("RECRUITER_VERIFICATION_PENDING",
                             result["result"]["error"]["code"])
            discard.assert_not_called()
            self.assertEqual("verification_pending", connector.LOGIN["state"])

    def test_platform_ip_restriction_is_explicit_and_preserves_scan(self):
        status = {"ok": True, "data": {"auth_health": {"summary": "degraded"}}}
        jobs = {"ok": False, "command": "recruiter-jobs-list",
                "error": {"code": "UNKNOWN", "message": "您的IP地址存在异常行为."}}
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(connector, "RISK_HOLD", pathlib.Path(folder) / "risk"), \
             mock.patch.object(connector, "cli_json", side_effect=[status, jobs]):
            self.assertEqual((False, "IP_RESTRICTED"), connector.recruiter_probe())
            self.assertTrue(connector.RISK_HOLD.is_file())
        with mock.patch.dict(connector.LOGIN, {"state": "verification_pending",
                                               "failure_code": "IP_RESTRICTED"}), \
             mock.patch.object(connector.subprocess, "run") as cli:
            result = connector.execute({"operation": "status"})
            self.assertEqual("RECRUITER_VERIFICATION_PENDING",
                             result["result"]["error"]["code"])
            self.assertIn("IP 地址存在异常行为", result["result"]["error"]["message"])
            cli.assert_not_called()

    def test_platform_ip_restriction_does_not_repeat_probe_or_clear_login(self):
        process = mock.MagicMock()
        process.communicate.return_value = (b'{"ok":true}', b"")
        process.returncode = 0
        with mock.patch.dict(connector.LOGIN, {"state": "pending", "phase": "qr"}), \
             mock.patch.object(connector, "browser_service"), \
             mock.patch.object(connector, "wait_for_cdp"), \
             mock.patch.object(connector, "chrome_has_login_cookie", return_value=False), \
             mock.patch.object(connector.subprocess, "run", return_value=mock.Mock(returncode=0)), \
             mock.patch.object(connector.subprocess, "Popen", return_value=process), \
             mock.patch.object(connector, "recruiter_probe",
                               return_value=(False, "IP_RESTRICTED")) as probe, \
             mock.patch.object(connector, "close_browser") as close, \
             mock.patch.object(connector, "discard_login") as discard:
            connector.LOGIN_CANCEL.clear()
            connector.login_worker()
            probe.assert_called_once()
            self.assertEqual("verification_pending", connector.LOGIN["state"])
            discard.assert_not_called()
            close.assert_not_called()
            self.assertNotIn("--force", connector.subprocess.Popen.call_args.args[0])

    def test_runtime_ip_restriction_stops_later_reads(self):
        response = mock.Mock(stdout=json.dumps({
            "ok": False, "error": {"code": "UNKNOWN",
                                    "message": "您的IP地址存在异常行为."}}).encode())
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(connector, "RISK_HOLD", pathlib.Path(folder) / "risk"), \
             mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector.subprocess, "run", return_value=response) as cli, \
             mock.patch.object(connector, "close_browser"):
            first = connector.execute({"operation": "jobs"})
            self.assertEqual("IP_RESTRICTED", first["result"]["error"]["code"])
            self.assertTrue(connector.RISK_HOLD.is_file())
            second = connector.execute({"operation": "jobs"})
            self.assertEqual("IP_RESTRICTED", second["error"]["code"])
            self.assertEqual(1, cli.call_count)

    def test_recruiter_identity_is_required_after_qr_scan(self):
        healthy = {"ok": True, "data": {"auth_health": {"summary": "healthy"}}}
        jobs = {"ok": True, "command": "recruiter-jobs-list", "data": {"items": []}}
        with mock.patch.object(connector, "cli_json", side_effect=[healthy, jobs]):
            self.assertTrue(connector.verify_recruiter())
        with mock.patch.object(connector, "cli_json", side_effect=[healthy, {
                "ok": False, "error": {"code": "ROLE_MISMATCH"}}]):
            self.assertFalse(connector.verify_recruiter())
        degraded = {"ok": True, "data": {"auth_health": {"summary": "degraded"}}}
        with mock.patch.object(connector, "cli_json", side_effect=[degraded, jobs]):
            self.assertTrue(connector.verify_recruiter())

    def test_successful_scan_keeps_credentials_when_jobs_temporarily_fail(self):
        process = mock.MagicMock()
        process.communicate.return_value = (b'{"ok":true}', b"")
        process.returncode = 0
        with mock.patch.dict(connector.LOGIN, {"state": "pending", "phase": "qr"}), \
             mock.patch.object(connector, "browser_service"), \
             mock.patch.object(connector, "wait_for_cdp"), \
             mock.patch.object(connector, "chrome_has_login_cookie", return_value=False), \
             mock.patch.object(connector.subprocess, "run", return_value=mock.Mock(returncode=0)), \
             mock.patch.object(connector.subprocess, "Popen", return_value=process), \
             mock.patch.object(connector, "recruiter_probe",
                               return_value=(False, "JOBS_UNAVAILABLE")) as probe, \
             mock.patch.object(connector.time, "sleep"), \
             mock.patch.object(connector, "close_browser"), \
             mock.patch.object(connector, "discard_login") as discard, \
             mock.patch.object(connector, "cli_json") as cli:
            connector.LOGIN_CANCEL.clear()
            connector.login_worker()
            self.assertEqual(1, probe.call_count)
            self.assertEqual("verification_pending", connector.LOGIN["state"])
            self.assertEqual("JOBS_UNAVAILABLE", connector.LOGIN["failure_code"])
            discard.assert_not_called()
            cli.assert_not_called()

    def test_all_auth_and_risk_failures_persist_stop_across_restart(self):
        for code in connector.STOP_CODES:
            with self.subTest(code=code), \
                 mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
                 mock.patch.object(connector.subprocess, "run", return_value=mock.Mock(
                     stdout=json.dumps({"ok": False, "error": {"code": code}}).encode())) as run, \
                 mock.patch.object(connector, "discard_login") as clear:
                connector.RISK_HOLD.unlink(missing_ok=True)
                connector.execute({"operation": "jobs"})
                self.assertEqual(code, connector.held_code())
                connector.LOGIN["state"] = "idle"
                connector.restore_login_state()
                self.assertEqual("verification_pending", connector.LOGIN["state"])
                connector.execute({"operation": "status"})
                connector.execute({"operation": "jobs"})
                connector.poll_resumes_once()
                self.assertEqual(1, run.call_count)
                clear.assert_not_called()

    def test_passive_status_never_probes_platform(self):
        with mock.patch.dict(connector.LOGIN, {"state": "idle"}), \
             mock.patch.object(connector.subprocess, "run", return_value=mock.Mock(
                 stdout=b'{"ok":true,"data":{"logged_in":true}}')) as run, \
             mock.patch.object(connector, "recruiter_probe") as probe:
            connector.execute({"operation": "status"})
            probe.assert_not_called()
            self.assertEqual("status", run.call_args.args[0][-1])

    def test_resume_poll_stops_entire_batch_on_first_risk(self):
        now = int(connector.time.time())
        with connector.resume_db() as db:
            db.executemany("INSERT INTO resume_requests "
                           "(friend_id,requested_at,baseline_mid,state,updated_at) "
                           "VALUES (?,?,0,'waiting',?)", [("12345", now, now), ("12346", now, now)])
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "cli_json", return_value={
                 "ok": False, "error": {"code": "ACCOUNT_RISK"}}) as cli:
            connector.poll_resumes_once()
            connector.poll_resumes_once()
            cli.assert_called_once()
            self.assertTrue(all(row["state"] == "waiting" for row in connector.tracked_resumes()))
            self.assertEqual("ACCOUNT_RISK", connector.held_code())

    def test_resume_poll_has_age_batch_and_interval_limits(self):
        now = int(connector.time.time())
        with connector.resume_db() as db:
            db.executemany("INSERT INTO resume_requests "
                           "(friend_id,requested_at,baseline_mid,state,updated_at) "
                           "VALUES (?,?,0,'waiting',?)",
                           [(str(100 + n), now, now) for n in range(5)]
                           + [("999", now - connector.RESUME_POLL_MAX_AGE - 1, now)])
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "cli_json", return_value={"ok": True, "data": {}}) as cli:
            connector.poll_resumes_once()
            self.assertEqual(3, cli.call_count)
            connector.poll_resumes_once()
            self.assertEqual(5, cli.call_count)
            connector.poll_resumes_once()
            self.assertEqual(5, cli.call_count)
            self.assertNotIn("999", [call.args[2] for call in cli.call_args_list])

    def test_resume_poll_three_transport_failures_pause_without_losing_checkpoint(self):
        now = int(connector.time.time())
        with connector.resume_db() as db:
            db.execute("INSERT INTO resume_requests "
                       "(friend_id,requested_at,baseline_mid,state,updated_at) "
                       "VALUES ('12345',?,10,'waiting',?)", (now, now))
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "cli_json", side_effect=subprocess.TimeoutExpired("mock", 1)) as cli:
            for n in range(4):
                with mock.patch.object(connector.time, "time", return_value=now + n * 301):
                    connector.poll_resumes_once()
            self.assertEqual(3, cli.call_count)
            self.assertEqual("MANUAL_REVIEW_REQUIRED", connector.held_code())
            self.assertEqual("waiting", connector.tracked_resumes()[0]["state"])

    def test_hold_blocks_queued_cli_and_allows_only_explicit_recovery_scope(self):
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector.subprocess, "run", return_value=mock.Mock(stdout=b'{"ok":true}')) as run:
            connector.pause_requests("ACCOUNT_RISK")
            connector.cli_json("hr", "jobs", "list")
            run.assert_not_called()
            connector.RECOVERY.active = True
            try:
                connector.cli_json("hr", "jobs", "list")
            finally:
                connector.RECOVERY.active = False
            run.assert_called_once()
            self.assertTrue(connector.RISK_HOLD.exists(), "a random success cannot release the hold")

    def test_resume_write_risk_does_not_issue_reconciliation_reads(self):
        with mock.patch.dict(connector.LOGIN, {"state": "complete"}), \
             mock.patch.object(connector, "prepare_recruiter_chat"), \
             mock.patch.object(connector, "cli_json", side_effect=[
                 {"ok": True, "data": {"messages": []}},
                 {"ok": False, "error": {"code": "ACCOUNT_RISK"}}]) as cli:
            with self.assertRaisesRegex(RuntimeError, "ACCOUNT_RISK"):
                connector.request_resume("12345")
            self.assertEqual(2, cli.call_count)
            self.assertEqual("uncertain", connector.tracked_resumes()[0]["state"])
            self.assertEqual("ACCOUNT_RISK", connector.held_code())

    def test_saved_browser_cookie_recovers_after_cli_login_error(self):
        process = mock.MagicMock()
        process.communicate.return_value = (
            b'{"ok":false,"error":{"code":"NETWORK_ERROR"}}', b"")
        process.returncode = 1
        with mock.patch.dict(connector.LOGIN, {"state": "pending", "phase": "qr"}), \
             mock.patch.object(connector, "browser_service"), \
             mock.patch.object(connector, "wait_for_cdp"), \
             mock.patch.object(connector.subprocess, "run", return_value=mock.Mock(returncode=0)), \
             mock.patch.object(connector.subprocess, "Popen", return_value=process), \
             mock.patch.object(connector, "chrome_has_login_cookie", side_effect=[False, True]), \
             mock.patch.object(connector, "sync_browser_login", return_value={"ok": True}) as sync, \
             mock.patch.object(connector, "cli_json") as cli, \
             mock.patch.object(connector, "recruiter_probe", return_value=(True, "")), \
             mock.patch.object(connector, "discard_login") as discard:
            connector.LOGIN_CANCEL.clear()
            connector.login_worker()
            self.assertEqual("complete", connector.LOGIN["state"])
            sync.assert_called_once_with()
            cli.assert_not_called()
            discard.assert_not_called()

    def test_existing_browser_login_never_runs_cli_login(self):
        with mock.patch.dict(connector.LOGIN, {"state": "pending"}), \
             mock.patch.object(connector, "browser_service"), \
             mock.patch.object(connector, "wait_for_cdp"), \
             mock.patch.object(connector, "chrome_has_login_cookie", return_value=True), \
             mock.patch.object(connector, "sync_browser_login", return_value={"ok": True}), \
             mock.patch.object(connector.subprocess, "Popen") as login, \
             mock.patch.object(connector, "recruiter_probe", return_value=(True, "")):
            connector.LOGIN_CANCEL.clear()
            connector.login_worker()
            self.assertEqual("complete", connector.LOGIN["state"])
            login.assert_not_called()

    def test_retry_syncs_current_browser_before_reading_recruiter_jobs(self):
        order = []
        with mock.patch.dict(connector.LOGIN, {"state": "pending"}), \
             mock.patch.object(connector, "browser_service"), \
             mock.patch.object(connector, "wait_for_cdp"), \
             mock.patch.object(connector, "sync_browser_login",
                               side_effect=lambda: (order.append("sync") or {"ok": True})), \
             mock.patch.object(connector, "recruiter_probe",
                               side_effect=lambda: (order.append("jobs") or (True, ""))), \
             mock.patch.object(connector, "close_browser") as close:
            connector.LOGIN_CANCEL.clear()
            connector.verify_saved_login_worker()
            self.assertEqual(["sync", "jobs"], order)
            self.assertEqual("complete", connector.LOGIN["state"])
            close.assert_not_called()

    def test_sync_failure_preserves_browser_and_never_probes_old_credentials(self):
        for code in ("AUTH_EXPIRED", "BROWSER_SYNC_TIMEOUT", "BROWSER_LOGIN_REQUIRED"):
            with self.subTest(code=code), \
                 mock.patch.dict(connector.LOGIN, {"state": "pending"}), \
                 mock.patch.object(connector, "browser_service"), \
                 mock.patch.object(connector, "wait_for_cdp"), \
                 mock.patch.object(connector, "sync_browser_login",
                                   return_value={"ok": False, "error": {"code": code}}), \
                 mock.patch.object(connector, "recruiter_probe") as probe, \
                 mock.patch.object(connector, "close_browser") as close, \
                 mock.patch.object(connector, "discard_login") as discard:
                connector.LOGIN_CANCEL.clear()
                connector.verify_saved_login_worker()
                self.assertEqual("verification_pending", connector.LOGIN["state"])
                self.assertEqual(code, connector.LOGIN["failure_code"])
                probe.assert_not_called()
                close.assert_not_called()
                discard.assert_not_called()

    def test_sync_process_timeout_is_bounded_and_does_not_clear_browser(self):
        with mock.patch.object(connector.subprocess, "run",
                               side_effect=subprocess.TimeoutExpired("sync", 60)) as run, \
             mock.patch.object(connector, "discard_login") as discard:
            self.assertEqual("BROWSER_SYNC_TIMEOUT",
                             connector.sync_browser_login()["error"]["code"])
            self.assertEqual(60, run.call_args.kwargs["timeout"])
            discard.assert_not_called()

    def test_connector_restart_preserves_browser_and_historical_hold(self):
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(connector, "RISK_HOLD", pathlib.Path(folder) / "risk"), \
             mock.patch.dict(connector.LOGIN, {"state": "idle"}), \
             mock.patch.object(connector, "close_browser") as close, \
             mock.patch.object(connector, "recruiter_probe") as probe:
            connector.RISK_HOLD.touch()
            connector.restore_login_state()
            self.assertEqual("verification_pending", connector.LOGIN["state"])
            self.assertIn("上次", connector.LOGIN["error"])
            close.assert_not_called()
            probe.assert_not_called()

    def test_safe_reuse_saves_only_after_upstream_success_without_force_fallback(self):
        class StaleSession(RuntimeError):
            pass
        class AccountRisk(RuntimeError):
            pass
        class EnvironmentRisk(RuntimeError):
            pass
        browser = types.ModuleType("boss_agent_cli.auth.browser")
        browser.ReusedSessionStaleError = StaleSession
        browser.login_via_cdp = mock.Mock()
        store = types.ModuleType("boss_agent_cli.auth.token_store")
        store.TokenStore = mock.Mock()
        errors = types.ModuleType("boss_agent_cli.api.client")
        errors.AccountRiskError = AccountRisk
        errors.EnvironmentRiskError = EnvironmentRisk
        modules = {"boss_agent_cli.auth.browser": browser,
                   "boss_agent_cli.auth.token_store": store,
                   "boss_agent_cli.api.client": errors}
        with mock.patch.dict("sys.modules", modules), \
             mock.patch.object(connector, "install_session_policy") as policy, \
             mock.patch.object(connector, "chrome_has_login_cookie", return_value=True):
            browser.login_via_cdp.side_effect = StaleSession("secret fingerprint")
            self.assertEqual({"ok": False, "error": {"code": "AUTH_EXPIRED"}},
                             connector.sync_browser_login_result())
            browser.login_via_cdp.assert_called_once_with(
                cdp_url="http://127.0.0.1:9222", timeout=30,
                platform="zhipin", reuse_existing=True)
            store.TokenStore.assert_not_called()
            browser.login_via_cdp.side_effect = None
            browser.login_via_cdp.return_value = {"cookies": {"wt2": "test-only"}}
            self.assertEqual({"ok": True}, connector.sync_browser_login_result())
            store.TokenStore.return_value.save.assert_called_once_with(
                {"cookies": {"wt2": "test-only"}})
            self.assertEqual(2, policy.call_count)

    def test_successful_recruiter_read_clears_only_the_old_risk_hold(self):
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(connector, "RISK_HOLD", pathlib.Path(folder) / "risk"):
            connector.RISK_HOLD.touch()
            status = {"ok": True}
            with mock.patch.object(connector, "cli_json", side_effect=[
                status, {"ok": False, "error": {"code": "NETWORK_ERROR"}}]):
                self.assertFalse(connector.recruiter_probe()[0])
                self.assertTrue(connector.RISK_HOLD.exists())
            with mock.patch.object(connector, "cli_json", side_effect=[
                status, {"ok": True, "command": "recruiter-jobs-list"}]):
                self.assertTrue(connector.recruiter_probe()[0])
                self.assertFalse(connector.RISK_HOLD.exists())

    def test_allowlist_and_secret_redaction(self):
        self.assertEqual(["hr", "jobs", "list"], connector.command({"operation": "jobs"}))
        self.assertEqual({"data": {"name": "ok"}},
                         connector.sanitize({"token": "secret", "data": {"Cookie": "secret", "name": "ok"}}))
        for request in ({"operation": "greet"}, {"operation": "messages", "friendId": "1;id"},
                        {"operation": "candidates", "keyword": "--help"},
                        {"operation": "applications", "page": 100}):
            with self.subTest(request=request), self.assertRaises(ValueError):
                connector.command(request)

    def test_http_always_validates_existing_session_and_exact_admin(self):
        with ThreadingHTTPServer(("127.0.0.1", 0), FakeAuth) as auth, \
             connector.HttpServer(("127.0.0.1", 0), connector.HttpHandler) as api:
            for server in (auth, api):
                threading.Thread(target=server.serve_forever, daemon=True).start()
            with mock.patch.object(connector, "AUTH_URL", f"http://127.0.0.1:{auth.server_port}/me"), \
                 mock.patch.object(connector, "execute", return_value={"ok": True, "result": {"ok": False, "error": {"code": "AUTH_REQUIRED"}}}), \
                 mock.patch.object(connector, "begin_login", return_value={"state": "pending"}) as login, \
                 mock.patch.object(connector, "logout_boss") as logout:
                url = f"http://127.0.0.1:{api.server_port}/api/v1/hr/status"
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                for cookie, expected in (("", 401), ("session=someone", 403)):
                    with self.subTest(cookie=cookie):
                        with self.assertRaises(urllib.error.HTTPError) as caught:
                            opener.open(urllib.request.Request(url, headers={"Cookie": cookie}))
                        self.assertEqual(expected, caught.exception.code)
                with opener.open(urllib.request.Request(url, headers={"Cookie": "session=owner"})) as response:
                    body = json.load(response)
                    self.assertTrue(body["success"])
                    self.assertEqual("AUTH_REQUIRED", body["data"]["error"]["code"])
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    opener.open(urllib.request.Request(url, data=b"", headers={"Cookie": "session=owner"}))
                self.assertEqual(405, caught.exception.code)
                start = url.replace("/status", "/login/start")
                for cookie, origin, expected in (("session=owner", "https://evil.example", 403),
                                                 ("session=someone", connector.ALLOWED_ORIGIN, 403),
                                                 ("", connector.ALLOWED_ORIGIN, 401)):
                    with self.subTest(cookie=cookie, origin=origin):
                        with self.assertRaises(urllib.error.HTTPError) as caught:
                            opener.open(urllib.request.Request(start, data=b"",
                                headers={"Cookie": cookie, "Origin": origin}))
                        self.assertEqual(expected, caught.exception.code)
                login.assert_not_called()
                with opener.open(urllib.request.Request(start, data=b"",
                    headers={"Cookie": "session=owner", "Origin": connector.ALLOWED_ORIGIN})) as response:
                    self.assertEqual(202, response.status)
                    self.assertEqual("pending", json.load(response)["data"]["state"])
                login.assert_called_once()
                qr_url = url.replace("/status", "/login/qr")
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    opener.open(urllib.request.Request(qr_url, headers={"Cookie": "session=someone"}))
                self.assertEqual(403, caught.exception.code)
                with mock.patch.dict(connector.LOGIN, {"state": "pending", "qr_ready": True}), \
                     mock.patch.object(connector, "qr_image", return_value=b"\x89PNG\r\n\x1a\nsample") as qr:
                    with opener.open(urllib.request.Request(qr_url, headers={"Cookie": "session=owner"})) as response:
                        self.assertEqual("image/png", response.headers.get("Content-Type"))
                        self.assertTrue(response.read().startswith(b"\x89PNG"))
                    refresh = url.replace("/status", "/login/refresh")
                    with opener.open(urllib.request.Request(refresh, data=b"",
                        headers={"Cookie": "session=owner", "Origin": connector.ALLOWED_ORIGIN})) as response:
                        self.assertTrue(json.load(response)["data"]["refreshed"])
                    qr.assert_any_call(force_refresh=True)
                logout_url = url.replace("/status", "/login/logout")
                with opener.open(urllib.request.Request(logout_url, data=b"",
                    headers={"Cookie": "session=owner", "Origin": connector.ALLOWED_ORIGIN})) as response:
                    self.assertEqual("disconnected", json.load(response)["data"]["state"])
                logout.assert_called_once()
            auth.shutdown()
            api.shutdown()


if __name__ == "__main__":
    unittest.main()
