"""Pinned SDK + real guard + native transport; no external requests."""
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest import mock
from urllib.parse import urlsplit

import native_client

PDF = b"%PDF-1.7\n% offline native attachment fixture\n%%EOF"
URL = "https://docdownload.zhipin.com/wflow/zpgeek/download/download4boss/geek202?id=resume303"


class NativeDownloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import runner
        from boss_agent_cli.api import recruiter_endpoints as ep
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name) / "recruiter/accounts/owner/boss"
        cls.http, cls.downloads = [], []
        cls.reply = {"status": 200, "content": PDF}
        cls.history = {}
        cls.http_status = 200

        def call(_native, path, **payload):
            if path == "/snapshot":
                return {"accountId": "101", "userAgent": "offline-agent",
                        "cookies": [{"name": "wt2", "domain": ".zhipin.com", "value": "offline-token"}]}
            if path != "/http":
                raise AssertionError("Unexpected native action")
            cls.http.append(payload)
            route = urlsplit(payload["url"]).path
            data = {}
            if route == "/wapi/zpchat/boss/historyMsg":
                data = cls.history
            elif route == urlsplit(ep.BOSS_FRIEND_DETAIL_URL).path:
                data = {"friendList": [{"uid": 202, "encryptUid": "geek202", "securityId": "fixture"}]}
            elif route == "/wapi/zpgeek/resume/boss/preview/check.json":
                data = {"isResumeVisible": True, "expired": False}
            else:
                raise AssertionError("Unexpected offline endpoint")
            return {"heyiNativeHttp": 1, "statusCode": cls.http_status,
                    "body": {"code": 0, "zpData": data}}

        def download(_native, url):
            cls.downloads.append(url)
            if isinstance(cls.reply, BaseException):
                raise cls.reply
            return cls.reply["status"], cls.reply["content"]

        cls.patches = [mock.patch.object(native_client.NativeClient, "call", call),
                       mock.patch.object(native_client.NativeClient, "download", download)]
        for patch in cls.patches:
            patch.start()
        cls.connector, cls.guard = runner.configure(cls.root, "http://127.0.0.1:12345", {
            "url": "http://127.0.0.1:12345", "secret": "fixture", "accountId": "101"})
        cls.guard.MIN_INTERVAL = 0
        cls.guard.install_policy()
        from boss_agent_cli.auth.token_store import TokenStore
        cls.token = {"cookies": {"wt2": "offline-token"}, "user_agent": "offline-agent"}
        TokenStore(cls.connector.AUTH_DIR).save(cls.token)

    @classmethod
    def tearDownClass(cls):
        for patch in reversed(cls.patches):
            patch.stop()
        cls.temp.cleanup()

    def setUp(self):
        from boss_agent_cli.api.recruiter_client import BossRecruiterClient
        cls = type(self)
        cls.http.clear()
        cls.downloads.clear()
        cls.reply = {"status": 200, "content": PDF}
        cls.http_status = 200
        cls.guard.HOURLY_LIMIT = 120
        cls.guard.RISK_HOLD.unlink(missing_ok=True)
        cls.connector.DISCONNECTED.unlink(missing_ok=True)
        cls.history = {"messages": [{"mid": 303, "from": {"uid": 202, "source": 0},
                                    "body": {"hyperLink": {"hyperLinkType": 1,
                                                          "url": "boss://resume?type=openFile&id=resume303"}}}]}
        self.client = BossRecruiterClient(types.SimpleNamespace(get_token=lambda: self.token),
                                         delay=(0, 0), cdp_url="http://127.0.0.1:12345",
                                         browser_source="stored-cookie")
        self.addCleanup(self.client.close)
        self.output = self.root / "fixture.pdf"
        self.output.unlink(missing_ok=True)

    def test_sdk_validates_message_and_friend_then_saves_real_attachment_bytes(self):
        result = self.client.download_resume_by_friend(202, 303, self.output)
        self.assertEqual(result["code"], 0)
        self.assertEqual(self.output.read_bytes(), PDF)
        self.assertEqual(result["zpData"]["bytes"], len(PDF))
        self.assertEqual(len(self.http), 3)
        self.assertEqual(len(self.downloads), 1)
        self.assertEqual(urlsplit(self.downloads[0]).hostname, "docdownload.zhipin.com")

    def test_wrong_sender_and_invalid_file_cannot_create_a_resume(self):
        type(self).history["messages"][0]["from"]["uid"] = 999
        with self.assertRaises(ValueError):
            self.client.download_resume_by_friend(202, 303, self.output)
        self.assertEqual(self.downloads, [])
        self.assertFalse(self.output.exists())
        type(self).history["messages"][0]["from"]["uid"] = 202
        type(self).reply = {"status": 200, "content": b"<html>login required</html>"}
        with self.assertRaises(ValueError):
            self.client.download_resume_by_friend(202, 303, self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual(len(self.downloads), 1)

    def test_failed_download_is_not_retried_and_does_not_create_a_partial_file(self):
        type(self).reply = OSError("offline interrupted response")
        with self.assertRaises(OSError):
            self.client.download_resume_by_friend(202, 303, self.output)
        self.assertEqual(len(self.downloads), 1)
        self.assertFalse(self.output.exists())

    def test_attachment_http_limit_and_platform_risk_are_persisted_without_retries(self):
        for status, content, code in [(429, b"limit", "RATE_LIMITED"),
                                      (403, b"login", "TOKEN_REFRESH_FAILED"),
                                      (302, b"", "TOKEN_REFRESH_FAILED"),
                                      (200, b'{"code":36}', "ACCOUNT_RISK")]:
            with self.subTest(status=status, code=code):
                self.guard.RISK_HOLD.unlink(missing_ok=True)
                type(self).reply = {"status": status, "content": content}
                before = len(self.downloads)
                with self.assertRaises(Exception) as raised:
                    with self.client._get_client().stream("GET", URL, follow_redirects=False) as response:
                        response.read()
                self.assertEqual(getattr(raised.exception, "code", ""), code)
                self.assertEqual(json.loads(self.guard.RISK_HOLD.read_text())["code"], code)
                self.assertEqual(len(self.downloads), before + 1)
                with self.assertRaises(Exception):
                    with self.client._get_client().stream("GET", URL, follow_redirects=False):
                        pass
                self.assertEqual(len(self.downloads), before + 1)

    def test_budget_exhaustion_stops_before_native_download(self):
        type(self).guard.HOURLY_LIMIT = 0
        with self.assertRaises(Exception) as raised:
            with self.client._get_client().stream("GET", URL, follow_redirects=False):
                pass
        self.assertEqual(getattr(raised.exception, "code", ""), "REQUEST_BUDGET_EXHAUSTED")
        self.assertEqual(self.downloads, [])

    def test_json_transport_preserves_http_status_instead_of_reporting_success(self):
        type(self).http_status = 429
        with self.assertRaises(Exception) as raised:
            self.client.chat_history(202, retry=False)
        self.assertEqual(getattr(raised.exception, "code", ""), "RATE_LIMITED")
        self.assertEqual(len(self.http), 1)


if __name__ == "__main__":
    unittest.main()
