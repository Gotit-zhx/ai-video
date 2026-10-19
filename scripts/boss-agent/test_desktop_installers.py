import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock
import urllib.error
import urllib.request

import desktop_installers as installers


class InstallerTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "release.json"
        self.commit = "c" * 40
        self.manifest = {"schemaVersion": 1, "commit": self.commit, "artifacts": {}}
        for platform, name in [("mac", "Ai-boss-mac-arm64-cccccccccccc.dmg"),
                               ("windows", "Ai-boss-Windows-Setup-cccccccccccc.exe")]:
            self.manifest["artifacts"][platform] = {
                "name": name, "size": 12345678, "sha256": "a"*64,
                "key": "private-desktop-installers/" + "a"*64 + "/" + name,
                "private": True, "verified": True,
            }
        self.save()
        self.bucket = mock.Mock()
        self.bucket.sign_url.return_value = "https://oss.cloud.dayan.cloud/bucket/private?Signature=fixture"
        self.auth = mock.Mock(return_value=200)
        self.downloads = installers.Downloads(self.path, self.bucket, self.auth)

    def save(self):
        self.path.write_text(json.dumps(self.manifest))

    def test_each_existing_link_uses_its_exact_verified_private_artifact(self):
        for path, platform in installers.ROUTES.items():
            with self.subTest(platform=platform):
                status, headers = self.downloads.response(path+"?v=0.1.24", "session=fixture")
                self.assertEqual(status, 302)
                self.assertEqual(headers["X-Installer-SHA256"], "a"*64)
                args = self.bucket.sign_url.call_args
                self.assertEqual(args.args, ("GET", self.manifest["artifacts"][platform]["key"], 1800))
                self.assertIn(self.manifest["artifacts"][platform]["name"],
                              args.kwargs["params"]["response-content-disposition"])
                self.auth.assert_called_with("session=fixture")

    def test_missing_login_permission_or_auth_outage_never_signs(self):
        for access in [401, 403, 503]:
            self.auth.return_value = access
            self.assertEqual(self.downloads.response(next(iter(installers.ROUTES)), ""), (access, {}))
        self.bucket.sign_url.assert_not_called()

    def test_unknown_path_and_methods_never_authenticate_or_sign(self):
        for path in ["/downloads/other.exe", "/downloads/../../release.json", "/"+"a"*2048]:
            self.assertEqual(self.downloads.response(path, "cookie"), (404, {}))
        self.assertEqual(self.downloads.response(next(iter(installers.ROUTES)), "cookie", "POST"), (405, {}))
        self.auth.assert_not_called()
        self.bucket.sign_url.assert_not_called()

    def test_partial_public_unverified_or_different_release_cannot_activate(self):
        original = json.loads(self.path.read_text())
        variants = [
            lambda x: x["artifacts"].pop("windows"),
            lambda x: x["artifacts"]["mac"].update(private=False),
            lambda x: x["artifacts"]["windows"].update(verified=False),
            lambda x: x["artifacts"]["mac"].update(name="other.dmg"),
            lambda x: x["artifacts"]["mac"].update(key="../private/file"),
            lambda x: x["artifacts"]["windows"].update(sha256="invalid"),
            lambda x: x.update(commit="d"*40),
        ]
        for change in variants:
            self.manifest = json.loads(json.dumps(original))
            change(self.manifest)
            self.save()
            with self.assertRaises(ValueError):
                self.downloads.response(next(iter(installers.ROUTES)), "cookie")
        self.bucket.sign_url.assert_not_called()

    def test_authentication_uses_existing_hr_identity_policy(self):
        cases = [
            ({"id": "user-1", "organizationCode": "heyi", "permissions": ["hr:access"]}, 200),
            ({"id": "user-2", "organizationCode": "heyi", "permissions": ["product:access"]}, 403),
            ({"username": "13800000000", "platformAdmin": True, "organizationCode": "heyi",
              "roles": ["ADMIN"]}, 200),
        ]
        for user, expected in cases:
            opener = mock.MagicMock()
            opener.open.return_value.__enter__.return_value.read.return_value = json.dumps(
                {"success": True, "data": user}).encode()
            with mock.patch.object(installers.urllib.request, "build_opener", return_value=opener):
                self.assertEqual(installers.authorize("session=fixture"), expected)
                self.assertEqual(opener.open.call_args.args[0].full_url,
                                 "http://127.0.0.1:8080/api/v1/auth/me")
        with mock.patch.object(installers.urllib.request, "build_opener") as open_factory:
            self.assertEqual(installers.authorize(""), 401)
            self.assertEqual(installers.authorize("a"*4097), 401)
            open_factory.assert_not_called()

    def test_real_http_head_redirect_and_unauthorized_responses_are_not_cached(self):
        server = installers.http.server.ThreadingHTTPServer(("127.0.0.1", 0),
                                                             installers.handler(self.downloads))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        url = f"http://127.0.0.1:{server.server_port}" + next(iter(installers.ROUTES))
        for method in ("GET", "HEAD"):
            with self.assertRaises(urllib.error.HTTPError) as raised:
                opener.open(urllib.request.Request(url, method=method, headers={"Cookie": "session=fixture"}))
            with raised.exception as response:
                self.assertEqual(response.code, 302)
                self.assertEqual(response.headers["Cache-Control"], "private, no-store")
                self.assertEqual(response.headers["Referrer-Policy"], "no-referrer")
                self.assertEqual(response.read(), b"")
            self.assertEqual(self.bucket.sign_url.call_args.args[0], method)
        self.auth.return_value = 401
        with self.assertRaises(urllib.error.HTTPError) as raised:
            opener.open(url)
        with raised.exception as response:
            self.assertEqual(response.code, 401)
            self.assertIsNone(response.headers.get("Location"))
            self.assertFalse(json.load(response)["success"])


if __name__ == "__main__":
    unittest.main()
