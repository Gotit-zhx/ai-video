"""Hermetic checks: real pinned SDK + synthetic HTTP, never a live account."""
import importlib.util
import pathlib
import tempfile
import types
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("guarded_cli", pathlib.Path(__file__).with_name("guarded_cli.py"))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


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


class PinnedSDKTest(unittest.TestCase):
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

    def request(self, status, payload=None, text=None):
        responses = []
        def handle(request):
            responses.append(request)
            return self.httpx.Response(status, json=payload) if text is None else self.httpx.Response(status, text=text)
        with self.httpx.Client(transport=self.httpx.MockTransport(handle)) as transport:
            auth = mock.Mock()
            auth.get_token.return_value = {"stoken": "synthetic-only"}
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
                    return self.base._request(client, "GET", "https://example.invalid/test")
            finally:
                self.assertEqual(1, len(responses))
                budget.assert_called_once()
                auth.force_refresh.assert_not_called()

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
