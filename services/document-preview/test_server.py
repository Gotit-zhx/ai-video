import contextlib
import http.client
import io
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest
import zipfile

from server import ConversionError, LibreOfficeConverter, OLE_MAGIC, PreviewServer, validate_source

TOKEN = "test-only-token-not-used-in-production-000000000000"
PDF = b"%PDF-1.7\nfunctional test bytes\n%%EOF"


def pptx(extra=None):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("[Content_Types].xml", "<Types/>")
        archive.writestr("ppt/presentation.xml", "<presentation/>")
        for name, data in (extra or {}).items():
            archive.writestr(name, data)
    return output.getvalue()


@contextlib.contextmanager
def running(converter=None, **kwargs):
    server = PreviewServer(("127.0.0.1", 0), TOKEN, converter or (lambda *_: PDF), **kwargs)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def request(server, payload=None, path="/convert?format=pptx", headers=None, method="POST"):
    payload = pptx() if payload is None else payload
    connection = http.client.HTTPConnection(*server.server_address, timeout=3)
    supplied = [("Authorization", "Bearer " + TOKEN), ("Content-Length", str(len(payload)))] if headers is None else headers
    connection.putrequest(method, path)
    for name, value in supplied:
        connection.putheader(name, value)
    connection.endheaders(payload if method == "POST" else None)
    response = connection.getresponse()
    result = response.status, dict(response.getheaders()), response.read()
    connection.close()
    return result


class ProtocolTest(unittest.TestCase):
    def test_success_preserves_bytes_returns_private_pdf(self):
        received = []
        def convert(payload, extension):
            received.append((payload, extension))
            return PDF
        with running(convert) as server:
            source = pptx()
            status, headers, body = request(server, source)
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/pdf")
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertEqual(body, PDF)
        self.assertEqual(received, [(source, "pptx")])

    def test_legacy_ppt_is_passed_as_ppt(self):
        received = []
        with running(lambda data, ext: received.append(ext) or PDF) as server:
            self.assertEqual(request(server, OLE_MAGIC + b"test", "/convert?format=ppt")[0], 200)
        self.assertEqual(received, ["ppt"])

    def test_authentication_is_required_and_duplicate_authorization_rejected(self):
        with running() as server:
            for headers in [[("Content-Length", "2")], [("Authorization", "Bearer wrong"), ("Content-Length", "2")],
                            [("Authorization", "Bearer " + TOKEN), ("Authorization", "Bearer " + TOKEN), ("Content-Length", "2")]]:
                self.assertEqual(request(server, b"xx", headers=headers)[0], 401)

    def test_only_explicit_single_ppt_or_pptx_format_accepted(self):
        with running() as server:
            for path in ["/convert", "/convert?format=pdf", "/convert?format=pptx&format=ppt", "/convert?format=pptx&path=file"]:
                self.assertEqual(request(server, path=path)[0], 400)

    def test_length_required_and_ambiguous_or_encoded_body_rejected(self):
        auth = [("Authorization", "Bearer " + TOKEN)]
        with running() as server:
            for headers in [auth, auth + [("Content-Length", "2"), ("Content-Length", "2")],
                            auth + [("Content-Length", "-2")], auth + [("Transfer-Encoding", "chunked")],
                            auth + [("Content-Length", "2"), ("Content-Encoding", "gzip")]]:
                self.assertEqual(request(server, b"xx", headers=headers)[0], 400)

    def test_source_size_checked_before_reading_and_output_is_bounded(self):
        with running(max_bytes=512) as server:
            headers = [("Authorization", "Bearer " + TOKEN), ("Content-Length", "513")]
            self.assertEqual(request(server, b"", headers=headers)[0], 413)
        with running(lambda *_: b"%PDF-" + b"0" * 600, max_bytes=512) as server:
            self.assertEqual(request(server)[0], 413)

    def test_invalid_package_or_non_pdf_conversion_is_rejected(self):
        with running() as server:
            for source in [b"not a package", pptx({"../escape": "x"}), pptx({"ppt/vbaProject.bin": "macro"})]:
                self.assertEqual(request(server, source)[0], 422)
        with running(lambda *_: b"not pdf") as server:
            self.assertEqual(request(server)[0], 422)

    def test_busy_is_immediate_and_health_stays_available(self):
        entered, release = threading.Event(), threading.Event()
        def convert(*_):
            entered.set()
            release.wait(2)
            return PDF
        with running(convert) as server:
            first = []
            thread = threading.Thread(target=lambda: first.append(request(server)[0]))
            thread.start()
            self.assertTrue(entered.wait(1))
            status, headers, body = request(server)
            self.assertEqual(status, 429)
            self.assertEqual(headers["Retry-After"], "3")
            self.assertEqual(json.loads(body), {"code": "CONVERTER_BUSY"})
            self.assertEqual(request(server, method="GET", path="/health")[2], b'{"status":"UP"}')
            release.set()
            thread.join(2)
            self.assertEqual(first, [200])

    def test_failed_conversion_releases_slot_and_hides_exception(self):
        failures = [RuntimeError("private filename token and content"), ConversionError(504, "CONVERSION_TIMEOUT")]
        def convert(*_):
            if failures:
                raise failures.pop(0)
            return PDF
        with running(convert) as server:
            first = request(server)
            self.assertEqual(first[0], 500)
            self.assertNotIn(b"private", first[2])
            self.assertEqual(request(server)[0], 504)
            self.assertEqual(request(server)[0], 200)

    def test_incomplete_or_stalled_upload_releases_slot(self):
        with running(read_seconds=0.1) as server:
            connection = socket.create_connection(server.server_address)
            connection.sendall(("POST /convert?format=pptx HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "
                                + TOKEN + "\r\nContent-Length: 999\r\n\r\nx").encode())
            received = connection.recv(4096)
            connection.close()
            self.assertIn(b"408", received)
            self.assertEqual(request(server)[0], 200)

    def test_no_default_or_weak_service_token(self):
        for token in ["", "short", "x" * 40 + "\n"]:
            with self.assertRaises(ValueError):
                PreviewServer(("127.0.0.1", 0), token)


class WorkerBoundaryTest(unittest.TestCase):
    def test_worker_environment_omits_token_and_temp_tree_is_cleaned(self):
        import os
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            worker = root / "worker.py"
            worker.write_text("import os,sys,pathlib\nassert 'DOCUMENT_PREVIEW_TOKEN' not in os.environ\npathlib.Path(sys.argv[2]).write_bytes(b'%PDF-1.7\\nvalid')\n")
            os.environ["DOCUMENT_PREVIEW_TOKEN"] = TOKEN
            try:
                converter = LibreOfficeConverter(worker=worker, python=sys.executable, temp_root=directory)
                self.assertTrue(converter(OLE_MAGIC, "ppt").startswith(b"%PDF-"))
            finally:
                os.environ.pop("DOCUMENT_PREVIEW_TOKEN", None)
            self.assertEqual(list(root.glob("document-preview-*")), [])

    def test_timeout_kills_child_process_and_cleans_temp_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "child-survived"
            worker = root / "worker.py"
            child = "import time,pathlib; time.sleep(0.7); pathlib.Path(" + repr(str(marker)) + ").write_text('bad')"
            worker.write_text("import subprocess,sys,time\nsubprocess.Popen([sys.executable,'-c'," + repr(child) + "])\ntime.sleep(30)\n")
            converter = LibreOfficeConverter(timeout=0.2, worker=worker, python=sys.executable, temp_root=directory)
            with self.assertRaises(ConversionError) as failure:
                converter(OLE_MAGIC, "ppt")
            self.assertEqual(failure.exception.status, 504)
            time.sleep(0.7)
            self.assertFalse(marker.exists())
            self.assertEqual(list(root.glob("document-preview-*")), [])

    def test_non_pdf_worker_output_is_rejected_and_cleaned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            worker = root / "worker.py"
            worker.write_text("import sys,pathlib\npathlib.Path(sys.argv[2]).write_bytes(b'not pdf')\n")
            converter = LibreOfficeConverter(worker=worker, python=sys.executable, temp_root=directory)
            with self.assertRaises(ConversionError) as failure:
                converter(OLE_MAGIC, "ppt")
            self.assertEqual(failure.exception.status, 422)
            self.assertEqual(list(root.glob("document-preview-*")), [])


if __name__ == "__main__":
    unittest.main()
