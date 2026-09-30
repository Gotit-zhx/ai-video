"""Hermetic checks: real pinned SDK + synthetic HTTP, never a live account."""
import contextlib
import importlib.util
import hashlib
import json
import pathlib
import sqlite3
import sys
import tempfile
import types
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("guarded_cli", pathlib.Path(__file__).with_name("guarded_cli.py"))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class IsolatedTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        for name in ("BUDGET_DB", "AUDIT_DB", "REQUEST_LOCK", "RISK_HOLD", "DISCONNECTED", "AUTH_DIR"):
            patch = mock.patch.object(guard, name, pathlib.Path(folder.name) / name.lower())
            patch.start()
            self.addCleanup(patch.stop)
        patch = mock.patch.dict(guard.os.environ, {guard.RECOVERY_ENV: ""})
        patch.start()
        self.addCleanup(patch.stop)

class SessionTest(IsolatedTest):
    def test_snapshot_uses_only_local_read_commands_and_exact_cookie_domains(self):
        websocket = mock.MagicMock()
        websocket.recv.side_effect = [
            json.dumps({"id": 1, "result": {"browserContextIds": []}}),
            json.dumps({"id": 2, "result": {"cookies": [
                {"name": "wt2", "value": "synthetic", "domain": ".zhipin.com", "expires": -1},
                {"name": "wt2", "value": "other", "domain": "evil-zhipin.com", "expires": -1},
                {"name": "wt2", "value": "expired", "domain": "www.zhipin.com", "expires": 1},
            ]}}),
            json.dumps({"id": 3, "result": {"userAgent": "synthetic-browser"}}),
        ]
        client_module = types.ModuleType("websockets.sync.client")
        client_module.connect = mock.MagicMock()
        client_module.connect.return_value.__enter__.return_value = websocket
        response = mock.MagicMock()
        response.read.return_value = b'{"webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/browser/test"}'
        opener = mock.MagicMock()
        opener.open.return_value.__enter__.return_value = response
        with mock.patch.dict(sys.modules, {"websockets.sync.client": client_module}), \
             mock.patch.object(guard.urllib.request, "build_opener", return_value=opener):
            self.assertEqual(({"synthetic"}, "synthetic-browser"), guard.browser_session_snapshot())
        opener.open.assert_called_once_with("http://127.0.0.1:9222/json/version", timeout=3)
        self.assertEqual(["Target.getBrowserContexts", "Storage.getCookies", "Browser.getVersion"],
                         [json.loads(call.args[0])["method"] for call in websocket.send.call_args_list])

    def test_primary_cookie_and_browser_identity_must_match(self):
        token = {"cookies": {"wt2": "synthetic"}, "user_agent": "synthetic-browser"}
        for snapshot in [(set(), "synthetic-browser"), ({"old"}, "synthetic-browser"),
                         ({"synthetic", "other"}, "synthetic-browser"), ({"synthetic"}, "different")]:
            with self.subTest(snapshot=snapshot), \
                 mock.patch.object(guard, "browser_session_snapshot", return_value=snapshot):
                with self.assertRaises(guard.SessionSafetyError) as error:
                    guard.verify_session(token)
                self.assertEqual("SESSION_MISMATCH", error.exception.code)
        with mock.patch.object(guard, "browser_session_snapshot",
                               return_value=({"synthetic"}, "synthetic-browser")):
            guard.verify_session(token)

    def test_local_browser_failure_is_safe_and_does_not_expose_exception(self):
        with mock.patch.object(guard, "browser_session_snapshot",
                               side_effect=RuntimeError("secret-cookie")):
            with self.assertRaises(guard.SessionSafetyError) as error:
                guard.verify_session({})
            self.assertEqual("SESSION_UNAVAILABLE", error.exception.code)
            self.assertNotIn("secret-cookie", str(error.exception))

    def test_explicit_recovery_ignores_historical_date_but_remains_scoped(self):
        guard.RISK_HOLD.write_text('{"code":"ACCOUNT_RISK"}')
        digest = hashlib.sha256(guard.RISK_HOLD.read_bytes()).hexdigest()
        with mock.patch.dict(guard.os.environ, {guard.RECOVERY_ENV: digest}):
            guard.assert_requests_allowed(recovery=True)
            with self.assertRaises(guard.SessionSafetyError):
                guard.assert_requests_allowed()
            guard.persist_stop("ACCOUNT_RISK")
            with self.assertRaises(guard.SessionSafetyError):
                guard.assert_requests_allowed(recovery=True)
        guard.RISK_HOLD.write_text(json.dumps({
            "code": "PC_LOGIN_RESTRICTED", "retry_at": "2026-09-29 13:44:43"}))
        digest = hashlib.sha256(guard.RISK_HOLD.read_bytes()).hexdigest()
        with mock.patch.dict(guard.os.environ, {guard.RECOVERY_ENV: digest}), \
             mock.patch.object(guard.time, "time", return_value=1790578000):
            guard.assert_requests_allowed(recovery=True)
            with self.assertRaises(guard.SessionSafetyError):
                guard.assert_requests_allowed()
            guard.persist_stop("SESSION_MISMATCH")
            self.assertEqual("PC_LOGIN_RESTRICTED", json.loads(guard.RISK_HOLD.read_text())["code"])
            with self.assertRaises(guard.SessionSafetyError):
                guard.assert_requests_allowed(recovery=True)

    def test_disconnect_blocks_even_explicit_recovery(self):
        guard.DISCONNECTED.touch()
        with self.assertRaises(guard.SessionSafetyError):
            guard.assert_requests_allowed(recovery=True)

    def test_audit_keeps_only_numeric_response_codes(self):
        guard.audit("synthetic", "finish", "http", "BOSS_JOB_LIST_URL", "GET",
                    http_status=200, platform_code={"cookie": "secret"})
        with contextlib.closing(sqlite3.connect(guard.AUDIT_DB)) as db:
            row = db.execute("SELECT http_status,platform_code FROM events").fetchone()
        self.assertEqual((200, None), row)
        with self.assertRaises(ValueError):
            guard.audit("synthetic", "start", "http", "https://host/?cookie=secret", "GET")
        self.assertNotIn(b"secret", guard.AUDIT_DB.read_bytes())

    def test_navigation_budget_exhaustion_is_audited_and_persisted(self):
        with self.assertRaises(guard.BudgetExhausted):
            with guard.request_trace("browser", "PREPARE_RECRUITER_CHAT", "NAVIGATE"):
                raise guard.BudgetExhausted("budget")
        self.assertEqual("REQUEST_BUDGET_EXHAUSTED",
                         json.loads(guard.RISK_HOLD.read_text())["code"])
        with contextlib.closing(sqlite3.connect(guard.AUDIT_DB)) as db:
            outcome = db.execute("SELECT outcome FROM events WHERE event='finish'").fetchone()[0]
        self.assertEqual("REQUEST_BUDGET_EXHAUSTED", outcome)


class BudgetTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        patch = mock.patch.object(guard, "BUDGET_DB", pathlib.Path(folder.name) / "budget.sqlite3")
        patch.start()
        self.addCleanup(patch.stop)

    def test_separate_connections_share_interval_and_bounded_queue(self):
        with mock.patch.object(guard.time, "time", return_value=10000), \
             mock.patch.object(guard.time, "sleep") as sleep:
            guard.reserve_request()
            guard.reserve_request()
            sleep.assert_called_once_with(6)
            guard.reserve_request()
            with self.assertRaises(guard.BudgetExhausted):
                guard.reserve_request()

    def test_hourly_budget_persists_and_expires(self):
        with mock.patch.object(guard, "HOURLY_LIMIT", 1), \
             mock.patch.object(guard.time, "time", return_value=10000):
            guard.reserve_request()
            with self.assertRaises(guard.BudgetExhausted):
                guard.reserve_request()
        with mock.patch.object(guard.time, "time", return_value=14000):
            guard.reserve_request()

    def test_unreviewed_sdk_version_fails_closed(self):
        with mock.patch.object(guard.importlib.metadata, "version", return_value="99.0.0"):
            with self.assertRaisesRegex(RuntimeError, "版本变化"):
                guard.install_policy()


class PinnedSDKTest(IsolatedTest):
    @classmethod
    def setUpClass(cls):
        try:
            import httpx
            from boss_agent_cli.api._base_client import _BaseHttpClient
            from boss_agent_cli.api.client import AuthError
            from boss_agent_cli.api.zhipin_errors import classify_code_37
        except ImportError:
            raise unittest.SkipTest("Run installed SDK tests in the isolated production venv")
        guard.install_policy()
        cls.httpx, cls.base, cls.auth_error = httpx, _BaseHttpClient, AuthError
        cls.classify = staticmethod(classify_code_37)

    def test_download_keeps_display_name_but_never_uses_it_as_output_path(self):
        from boss_agent_cli.api import recruiter_client
        with tempfile.TemporaryDirectory() as folder:
            output=pathlib.Path(folder)/'random.pdf'
            response=self.httpx.Response(200,content=b'%PDF-test',
                headers={'Content-Disposition':"attachment; filename*=UTF-8''..%2F%E8%8B%8F%E7%90%AA%E7%AE%80%E5%8E%86.pdf"})
            result=recruiter_client.save_resume(response,output)
            self.assertEqual(result['originalFileName'],'苏琪简历.pdf')
            self.assertEqual(output.read_bytes(),b'%PDF-test')
            self.assertEqual(list(pathlib.Path(folder).iterdir()),[output])

    def setUp(self):
        super().setUp()
        patch = mock.patch.object(guard, "verify_session")
        self.verify_session = patch.start()
        self.addCleanup(patch.stop)

    def request(self, status, payload=None, text=None, *, url="https://example.invalid/test", keep_hold=False):
        if not keep_hold:
            guard.RISK_HOLD.unlink(missing_ok=True)
        responses = []
        def handle(request):
            responses.append(request)
            return self.httpx.Response(status, json=payload) if text is None else self.httpx.Response(status, text=text)
        with self.httpx.Client(transport=self.httpx.MockTransport(handle), cookies={"wt2": "synthetic"}) as transport:
            auth = mock.Mock()
            auth.get_token.return_value = {"stoken": "synthetic-only", "cookies": {"wt2": "synthetic"}}
            client = types.SimpleNamespace(
                _get_client=lambda: transport, _auth=auth, _throttle=mock.Mock(),
                _headers_for=lambda _: {}, _merge_cookies=mock.Mock(),
                _AUTH_ERROR_CLS=self.auth_error, _CODE_RATE_LIMITED=9,
                _ADD_ENDPOINT_HINT=False,
                _should_refresh_token_response=lambda data: data.get("code") == 37
                and self.classify(data) == "token_expired",
            )
            try:
                with mock.patch.object(guard, "reserve_request") as budget:
                    return self.base._request(client, "GET", url)
            finally:
                self.assertEqual(1, len(responses))
                budget.assert_called_once()
                auth.force_refresh.assert_not_called()

    def test_audited_failure_identifies_endpoint_status_and_platform_code(self):
        from boss_agent_cli.api.client import PlatformRiskError
        with self.assertRaises(PlatformRiskError):
            self.request(200, {"code": 36, "message": "private message"})
        with contextlib.closing(sqlite3.connect(guard.AUDIT_DB)) as db:
            row = db.execute("SELECT outcome,http_status,platform_code FROM events "
                             "WHERE event='finish'").fetchone()
        self.assertEqual(("ACCOUNT_RISK", 200, 36), row)
        self.assertNotIn(b"private message", guard.AUDIT_DB.read_bytes())
        self.assertEqual("ACCOUNT_RISK", json.loads(guard.RISK_HOLD.read_text())["code"])

    def test_persistent_hold_prevents_call_even_outside_connector(self):
        from boss_agent_cli.api.client import PlatformRiskError
        guard.RISK_HOLD.write_text('{"code":"ACCOUNT_RISK"}')
        client = mock.Mock()
        with self.assertRaises(PlatformRiskError):
            self.base._request(client, "GET", "https://example.invalid/")
        client._get_client.assert_not_called()
        self.verify_session.assert_not_called()

    def test_waiting_request_rechecks_new_hold_before_network(self):
        from boss_agent_cli.api.client import PlatformRiskError
        client = mock.Mock()
        with mock.patch.object(guard, "reserve_request",
                               side_effect=lambda: guard.RISK_HOLD.write_text('{"code":"ACCOUNT_RISK"}')):
            with self.assertRaises(PlatformRiskError):
                self.base._request(client, "GET", "https://example.invalid/")
        client._get_client.assert_not_called()

    def test_session_mismatch_stops_before_constructing_http_client(self):
        from boss_agent_cli.api.client import PlatformRiskError
        self.verify_session.side_effect = guard.SessionSafetyError("SESSION_MISMATCH", "synthetic")
        client = mock.Mock()
        with mock.patch.object(guard, "reserve_request"), self.assertRaises(PlatformRiskError):
            self.base._request(client, "GET", "https://example.invalid/")
        client._get_client.assert_not_called()
        self.assertEqual("SESSION_MISMATCH", json.loads(guard.RISK_HOLD.read_text())["code"])

    def test_cross_process_lock_prevents_overlapping_dispatch(self):
        from boss_agent_cli.api.client import PlatformRiskError
        with guard.REQUEST_LOCK.open("w") as existing:
            guard.fcntl.flock(existing, guard.fcntl.LOCK_EX)
            client = mock.Mock()
            with self.assertRaises(PlatformRiskError) as error:
                self.base._request(client, "GET", "https://example.invalid/")
            self.assertEqual("ACCOUNT_BUSY", error.exception.code)
            client._get_client.assert_not_called()
        self.assertFalse(guard.RISK_HOLD.exists())

    def test_recovery_does_not_allow_unrelated_endpoints(self):
        from boss_agent_cli.api.client import PlatformRiskError
        guard.RISK_HOLD.write_text('{"code":"ACCOUNT_RISK"}')
        digest = hashlib.sha256(guard.RISK_HOLD.read_bytes()).hexdigest()
        with mock.patch.dict(guard.os.environ, {guard.RECOVERY_ENV: digest}):
            with self.assertRaises(PlatformRiskError):
                self.base._request(mock.Mock(), "GET", "https://example.invalid/")
        self.verify_session.assert_not_called()

    def test_explicit_recovery_can_verify_jobs_without_clearing_hold_itself(self):
        from boss_agent_cli.api import recruiter_endpoints as ep
        guard.RISK_HOLD.write_text('{"code":"ACCOUNT_RISK"}')
        digest = hashlib.sha256(guard.RISK_HOLD.read_bytes()).hexdigest()
        with mock.patch.dict(guard.os.environ, {guard.RECOVERY_ENV: digest}):
            self.assertEqual({"code": 0},
                             self.request(200, {"code": 0}, url=ep.BOSS_JOB_LIST_URL, keep_hold=True))
        self.assertTrue(guard.RISK_HOLD.exists())
        with contextlib.closing(sqlite3.connect(guard.AUDIT_DB)) as db:
            self.assertEqual(("BOSS_JOB_LIST_URL", 200, 0), db.execute(
                "SELECT endpoint,http_status,platform_code FROM events WHERE event='finish'").fetchone())

    def test_primary_cookie_rotation_stops_without_copying_back(self):
        from boss_agent_cli.api.client import PlatformRiskError
        requests = []
        def respond(request):
            requests.append(request)
            return self.httpx.Response(200, json={"code": 0},
                                       headers={"set-cookie": "wt2=rotated; Path=/"})
        with self.httpx.Client(transport=self.httpx.MockTransport(respond),
                              cookies={"wt2": "synthetic"}) as transport:
            auth = mock.Mock()
            auth.get_token.return_value = {"cookies": {"wt2": "synthetic"}}
            client = types.SimpleNamespace(_auth=auth, _get_client=lambda: transport,
                _throttle=mock.Mock(), _headers_for=lambda _: {}, _merge_cookies=mock.Mock(),
                _AUTH_ERROR_CLS=self.auth_error)
            with mock.patch.object(guard, "reserve_request"), self.assertRaises(PlatformRiskError):
                self.base._request(client, "GET", "https://example.invalid/")
            self.assertEqual(1, len(requests))
            client._merge_cookies.assert_not_called()
            auth._store.save.assert_not_called()

    def test_non_cdp_login_has_no_fallback(self):
        from boss_agent_cli.auth.manager import AuthManager, TokenRefreshFailed
        for kwargs in ({}, {"force_cdp": False}, {"force_cdp": True, "force_relogin": True}):
            with self.assertRaises(TokenRefreshFailed):
                AuthManager.login(object(), **kwargs)

    def test_unverified_probe_stops_before_saving_or_testing_jobs(self):
        from boss_agent_cli.auth import browser
        from boss_agent_cli.auth.manager import TokenRefreshFailed
        page = mock.Mock()
        page.evaluate.return_value = {"code": -1}
        with mock.patch.object(guard, "reserve_request"), self.assertRaises(TokenRefreshFailed):
            browser._probe_reused_session(page, platform="zhipin")
        page.evaluate.assert_called_once()
        with contextlib.closing(sqlite3.connect(guard.AUDIT_DB)) as db:
            self.assertEqual(("USER_INFO_URL", -1), db.execute(
                "SELECT endpoint,platform_code FROM events WHERE event='finish'").fetchone())

    def test_browser_fetch_write_is_rejected_before_dispatch(self):
        from boss_agent_cli.api import browser_client
        from boss_agent_cli.api.client import PlatformRiskError
        browser = mock.Mock()
        with mock.patch.object(guard, "reserve_request"), self.assertRaises(PlatformRiskError):
            browser_client.BrowserSession.request(browser, "POST", "https://example.invalid/")
        browser._ensure_started.assert_not_called()

    def test_success_makes_one_request(self):
        self.assertEqual({"code": 0, "zpData": {}}, self.request(200, {"code": 0, "zpData": {}}))

    def test_security_and_auth_responses_never_refresh_or_retry(self):
        from boss_agent_cli.auth.manager import TokenRefreshFailed
        for status, payload, text in [
            (403, {}, None), (200, None, "安全验证"), (401, {}, None), (302, {}, None),
        ]:
            with self.subTest(status=status), self.assertRaises(TokenRefreshFailed):
                self.request(status, payload, text)

    def test_platform_risk_and_limit_stop_on_first_response(self):
        from boss_agent_cli.api.client import PlatformRiskError
        for code in (36, 37, 9):
            with self.subTest(code=code), self.assertRaises(PlatformRiskError):
                self.request(200, {"code": code, "message": "访问环境异常"})
        with self.assertRaises(PlatformRiskError):
            self.request(429, {})

    def test_direct_refresh_is_disabled(self):
        from boss_agent_cli.auth.manager import AuthManager, TokenRefreshFailed
        with self.assertRaises(TokenRefreshFailed):
            AuthManager.force_refresh(object(), cdp_url="http://example.invalid")

    def test_stale_cdp_session_never_falls_back_to_forced_login(self):
        from boss_agent_cli.auth import manager
        from boss_agent_cli.auth.browser import ReusedSessionStaleError
        auth = types.SimpleNamespace(_platform="zhipin")
        with mock.patch.object(manager, "login_via_cdp",
                               side_effect=ReusedSessionStaleError("synthetic")) as login:
            with self.assertRaises(manager.TokenRefreshFailed):
                manager.AuthManager._cdp_login(
                    auth, cdp_url="http://example.invalid", timeout=30, reuse_existing=True)
            login.assert_called_once_with(
                cdp_url="http://example.invalid", timeout=30, platform="zhipin", reuse_existing=True)
            with self.assertRaises(manager.TokenRefreshFailed):
                manager.AuthManager._cdp_login(
                    auth, cdp_url="http://example.invalid", timeout=30, reuse_existing=False)
            self.assertEqual(1, login.call_count)

    def test_reused_login_probe_participates_in_shared_request_budget(self):
        from boss_agent_cli.auth import browser
        page = mock.Mock()
        page.evaluate.return_value = {"code": 0}
        with mock.patch.object(guard, "reserve_request") as budget:
            self.assertEqual("valid", browser._probe_reused_session(page, platform="zhipin"))
        budget.assert_called_once()
        page.evaluate.assert_called_once()

    def test_raw_recruiter_action_is_budgeted_and_dispatched_only_once(self):
        from boss_agent_cli.api import browser_client
        browser = types.SimpleNamespace(
            _is_bridge=False, _cdp_url="http://example.invalid", _require_cdp_source=mock.Mock())
        with mock.patch.object(guard, "reserve_request") as budget, \
             mock.patch.object(browser_client, "_cdp_evaluate_with_chat_events_in_chat_tab",
                               return_value={"value": {"ok": True}, "events": []}) as dispatch:
            browser_client.BrowserSession.evaluate_js_with_chat_events(browser, "synthetic", {})
            budget.assert_called_once()
            dispatch.assert_called_once()

    def test_exhausted_budget_prevents_raw_recruiter_action(self):
        from boss_agent_cli.api import browser_client
        from boss_agent_cli.api.client import PlatformRiskError
        with mock.patch.object(guard, "reserve_request", side_effect=guard.BudgetExhausted("budget")), \
             mock.patch.object(browser_client, "_cdp_evaluate_with_chat_events_in_chat_tab") as dispatch:
            with self.assertRaises(PlatformRiskError):
                browser_client.BrowserSession.evaluate_js_with_chat_events(object(), "synthetic", {})
            dispatch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
