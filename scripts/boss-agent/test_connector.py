import importlib.util
import json
import pathlib
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
    def test_unverified_saved_account_is_rejected_and_cleared(self):
        response = mock.Mock(stdout=json.dumps({
            "ok": True, "data": {"logged_in": True,
                                  "auth_health": {"summary": "degraded"}}}).encode())
        with mock.patch.dict(connector.LOGIN, {"state": "idle"}), \
             mock.patch.object(connector.subprocess, "run", return_value=response), \
             mock.patch.object(connector, "discard_login") as discard:
            result = connector.execute({"operation": "status"})
            self.assertEqual("RECRUITER_REQUIRED", result["result"]["error"]["code"])
            discard.assert_called_once()
            self.assertEqual("failed", connector.LOGIN["state"])

    def test_recruiter_identity_is_required_after_qr_scan(self):
        healthy = {"ok": True, "data": {"auth_health": {"summary": "healthy"}}}
        jobs = {"ok": True, "command": "recruiter-jobs-list", "data": {"items": []}}
        with mock.patch.object(connector, "cli_json", side_effect=[healthy, jobs]):
            self.assertTrue(connector.verify_recruiter())
        with mock.patch.object(connector, "cli_json", side_effect=[healthy, {
                "ok": False, "error": {"code": "ROLE_MISMATCH"}}]):
            self.assertFalse(connector.verify_recruiter())
        with mock.patch.object(connector, "cli_json", return_value={
                "ok": True, "data": {"auth_health": {"summary": "degraded"}}}):
            self.assertFalse(connector.verify_recruiter())

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
