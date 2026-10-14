"""Run separately from the webpage SDK tests: installation replaces SDK methods."""
from pathlib import Path
import tempfile
import types
import unittest
import sqlite3
from unittest import mock

import native_client


class NativeClientTest(unittest.TestCase):
    def test_only_exact_boss_cookie_domains_are_used(self):
        client = native_client.NativeClient({"url": "http://127.0.0.1:12345",
                                            "accountId": "101", "secret": "fixture"})
        client.call = mock.Mock(return_value={
            "accountId": "101", "userAgent": "offline",
            "cookies": [{"name": "wt2", "domain": domain, "value": domain}
                        for domain in ["zhipin.com", ".www.zhipin.com",
                                       "evilzhipin.com", "zhipin.com.other"]]}
        )
        self.assertEqual(client.snapshot(), ({"zhipin.com", ".www.zhipin.com"}, "offline"))

    def test_endpoint_cannot_contain_credentials_queries_or_external_hosts(self):
        for url in ["https://127.0.0.1:12345", "http://example.invalid:12345",
                    "http://u:p@127.0.0.1:12345", "http://127.0.0.1:12345?x=1",
                    "http://127.0.0.1:12345#x", "http://127.0.0.1:12345/path"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                native_client.NativeClient({"url": url})

    def test_real_guard_retains_budget_risk_and_session_checks_over_native_transport(self):
        import runner
        from boss_agent_cli.api.recruiter_client import BossRecruiterClient
        from boss_agent_cli.api._base_client import _BaseHttpClient
        from boss_agent_cli.api.client import AccountRiskError
        from boss_agent_cli.api.browser_client import BrowserSession
        import conversation_scope
        calls = []
        platform_code = [0]

        def call(client, path, **payload):
            if path == "/snapshot":
                return {"accountId": "101", "userAgent": "offline-agent",
                        "cookies": [{"name": "wt2", "domain": ".zhipin.com", "value": "offline-token"}]}
            if path == "/http":
                calls.append(payload)
                return {"code": platform_code[0], "zpData": {"fixture": True}}
            raise AssertionError("Unexpected offline native action")

        # One isolated process owns these monkeypatches. No actual BOSS endpoint
        # is called; all HTTP data is supplied by the fake local transport.
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(native_client.NativeClient, "call", call):
            root = Path(directory) / "recruiter/accounts/owner/boss"
            connector, guard = runner.configure(root, "http://127.0.0.1:12345", {
                "url": "http://127.0.0.1:12345", "secret": "fixture", "accountId": "101"})
            # The native adapter itself rejects unsupported webpage execution.
            with self.assertRaises(native_client.NativeError):
                BrowserSession.evaluate_js(object(), "arbitrary script")
            guard.MIN_INTERVAL = 0
            guard.HOURLY_LIMIT = 3
            guard.install_policy()
            self.assertTrue(_BaseHttpClient._request._heyi_guarded)
            token = {"cookies": {"wt2": "offline-token"}, "user_agent": "offline-agent"}
            auth = types.SimpleNamespace(get_token=lambda: token)
            client = BossRecruiterClient(auth, delay=(0, 0), cdp_url="http://127.0.0.1:12345",
                                         browser_source="stored-cookie")
            self.addCleanup(client.close)
            url = "https://www.zhipin.com/wapi/zpchat/boss/historyMsg"
            self.assertEqual(client._request("GET", url)["code"], 0)
            self.assertEqual(len(calls), 1)
            platform_code[0] = 36
            with self.assertRaises(AccountRiskError):
                client._request("GET", url)
            self.assertEqual(len(calls), 2)
            # A persisted real risk hold prevents the transport running again.
            connector.pause_requests("ACCOUNT_RISK")
            with self.assertRaises(Exception):
                client._request("GET", url)
            self.assertEqual(len(calls), 2)
            self.assertTrue(guard.RISK_HOLD.exists())
            with sqlite3.connect(guard.BUDGET_DB) as ledger:
                self.assertEqual(ledger.execute("SELECT COUNT(*) FROM requests").fetchone()[0], 2)
            # Candidate/job verification still precedes the native send.
            client.friend_detail = mock.Mock(return_value={"code": 0, "zpData": {
                "friendList": [{"uid": 202, "encryptJobId": "wrong-job", "securityId": "fixture"}]}})
            scope = conversation_scope.ConversationScope(BossRecruiterClient)
            with scope.expect("202", "expected-job"), self.assertRaises(conversation_scope.ScopeError):
                client.send_message_by_friend(202, "offline fixture")
            # Webpage Vue actions cannot be silently dispatched via native RPC.
            from boss_agent_cli.api.client import PlatformRiskError
            with self.assertRaises(PlatformRiskError):
                BrowserSession.evaluate_js(object(), "arbitrary script")


if __name__ == "__main__":
    unittest.main()
