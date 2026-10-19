import hashlib
import http.server
import importlib.util
from pathlib import Path
import threading
import types
import unittest

spec = importlib.util.spec_from_file_location(
    "private_installer_storage", Path(__file__).resolve().parents[1] / "private-desktop-installer-storage.py")
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)


class StorageTest(unittest.TestCase):
    def setUp(self):
        self.payload = b"verified installer fixture"
        self.unsigned = {"GET": 403, "HEAD": 403}
        self.sent = self.payload
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):
                signed = self.path == "/installer?signature=fixture"
                status = 200 if signed else owner.unsigned[self.command]
                self.send_response(status)
                self.send_header("Content-Length", str(len(owner.sent)))
                self.send_header("Content-Disposition", 'attachment; filename="installer.exe"')
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(owner.sent)

            do_HEAD = do_GET

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.bucket = types.SimpleNamespace(
            head_object=lambda _: types.SimpleNamespace(content_length=len(self.payload)),
            sign_url=lambda *_args, **_kwargs: f"http://127.0.0.1:{server.server_port}/installer?signature=fixture")
        self.record = {"key": "fixture", "name": "installer.exe", "size": len(self.payload),
                       "sha256": hashlib.sha256(self.payload).hexdigest()}

    def test_private_bytes_verified_without_acl_metadata_api(self):
        result = storage.verify_object(self.bucket, self.record)
        self.assertTrue(result["private"])
        self.assertTrue(result["verified"])

    def test_public_get_rejected_even_when_head_denied(self):
        self.unsigned["GET"] = 200
        with self.assertRaisesRegex(ValueError, "Anonymous"):
            storage.verify_object(self.bucket, self.record)

    def test_public_head_rejected(self):
        self.unsigned["HEAD"] = 200
        with self.assertRaisesRegex(ValueError, "Anonymous"):
            storage.verify_object(self.bucket, self.record)

    def test_same_length_corruption_rejected(self):
        self.sent = b"x" * len(self.payload)
        with self.assertRaisesRegex(ValueError, "checksum"):
            storage.verify_object(self.bucket, self.record)

    def test_storage_outage_is_not_privacy_evidence(self):
        self.unsigned["GET"] = 503
        with self.assertRaisesRegex(ValueError, "storage status"):
            storage.verify_object(self.bucket, self.record)


if __name__ == "__main__":
    unittest.main()
