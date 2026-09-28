import importlib.util
import json
import pathlib
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
                           ("RESUME_DB", root / "resumes.sqlite3"),
                           ("AUTH_DIR", root / "auth")):
            patch = mock.patch.object(connector, name, path)
            patch.start()
            self.addCleanup(patch.stop)

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

    def test_expired_qr_uses_own_refresh_control(self):
        page = mock.MagicMock()
        page.url = "https://www.zhipin.com/web/user/"
        box = page.locator.return_value.first
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
            with mock.patch.object(connector, "cli_json", side_effect=[before, sent]) as cli:
                result = connector.request_resume("12345")
                self.assertEqual("waiting", result["state"])
                self.assertEqual(2, cli.call_count)
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
             mock.patch.object(connector.subprocess, "Popen", return_value=process), \
             mock.patch.object(connector, "recruiter_probe",
                               return_value=(False, "JOBS_UNAVAILABLE")) as probe, \
             mock.patch.object(connector.time, "sleep"), \
             mock.patch.object(connector, "close_browser"), \
             mock.patch.object(connector, "discard_login") as discard, \
             mock.patch.object(connector, "cli_json") as cli:
            connector.LOGIN_CANCEL.clear()
            connector.login_worker()
            self.assertEqual(3, probe.call_count)
            self.assertEqual("verification_pending", connector.LOGIN["state"])
            self.assertEqual("JOBS_UNAVAILABLE", connector.LOGIN["failure_code"])
            discard.assert_not_called()
            cli.assert_not_called()

    def test_saved_browser_cookie_recovers_after_cli_login_error(self):
        process = mock.MagicMock()
        process.communicate.return_value = (
            b'{"ok":false,"error":{"code":"NETWORK_ERROR"}}', b"")
        process.returncode = 1
        with mock.patch.dict(connector.LOGIN, {"state": "pending", "phase": "qr"}), \
             mock.patch.object(connector, "browser_service"), \
             mock.patch.object(connector, "wait_for_cdp"), \
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
                with mock.patch.dict(connector.LOGIN, {"state": "pending"}), \
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
                    self.assertEqual("idle", json.load(response)["data"]["state"])
                logout.assert_called_once()
            auth.shutdown()
            api.shutdown()


if __name__ == "__main__":
    unittest.main()
