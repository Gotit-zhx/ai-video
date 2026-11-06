"""Private, bounded PowerPoint-to-PDF conversion API. No third-party HTTP dependencies."""
from __future__ import annotations

import hmac
import io
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit
import zipfile

MAX_BYTES = 50 * 1024 * 1024
CONVERSION_SECONDS = 90
READ_SECONDS = 20
OLE_MAGIC = bytes.fromhex("d0cf11e0a1b11ae1")


class ConversionError(Exception):
    def __init__(self, status: int, code: str):
        self.status, self.code = status, code
        super().__init__(code)


def validate_source(payload: bytes, extension: str) -> None:
    if extension == "ppt":
        if not payload.startswith(OLE_MAGIC):
            raise ConversionError(422, "INVALID_PRESENTATION")
        return
    try:
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            members = archive.infolist()
            names = {item.filename for item in members}
            if len(members) > 4096 or sum(item.file_size for item in members) > 200 * 1024 * 1024 \
                    or len(names) != len(members) or "ppt/presentation.xml" not in names \
                    or "[Content_Types].xml" not in names:
                raise ValueError("Invalid package")
            for member in members:
                name = member.filename
                if name.startswith("/") or "\\" in name or ".." in name.split("/") \
                        or member.flag_bits & 1 or name.lower().endswith("vbaproject.bin"):
                    raise ValueError("Unsupported package")
    except (ValueError, OSError, zipfile.BadZipFile):
        raise ConversionError(422, "INVALID_PRESENTATION") from None


class LibreOfficeConverter:
    def __init__(self, timeout=CONVERSION_SECONDS, worker=None, python=None, temp_root=None):
        self.timeout = timeout
        self.worker = Path(worker or Path(__file__).with_name("convert_worker.py"))
        self.python = python or "/usr/bin/python3"
        self.temp_root = temp_root

    def __call__(self, payload: bytes, extension: str) -> bytes:
        with tempfile.TemporaryDirectory(prefix="document-preview-", dir=self.temp_root) as directory:
            root = Path(directory)
            root.chmod(0o700)
            source = root / ("source." + extension)
            output = root / "output.pdf"
            source.write_bytes(payload)
            # Do not propagate the internal HTTP token or any unrelated service environment to LibreOffice.
            environment = {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8",
                           "HOME": directory, "TMPDIR": directory, "XDG_CACHE_HOME": str(root / "cache"),
                           "SAL_USE_VCLPLUGIN": "svp", "PYTHONDONTWRITEBYTECODE": "1"}
            process = subprocess.Popen([self.python, str(self.worker), str(source), str(output), str(root / "profile")],
                                       env=environment, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL, start_new_session=True)
            try:
                try:
                    result = process.wait(timeout=self.timeout)
                except subprocess.TimeoutExpired:
                    raise ConversionError(504, "CONVERSION_TIMEOUT") from None
                if result != 0 or not output.is_file() or output.is_symlink():
                    raise ConversionError(422, "CONVERSION_FAILED")
                if output.stat().st_size > MAX_BYTES:
                    raise ConversionError(413, "OUTPUT_TOO_LARGE")
                pdf = output.read_bytes()
                if not pdf.startswith(b"%PDF-") or len(pdf) < 8:
                    raise ConversionError(422, "CONVERSION_FAILED")
                return pdf
            finally:
                # Covers all LibreOffice descendants, including a wedged worker or orphan child.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()


class PreviewServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 8

    def __init__(self, address, token, converter=None, max_bytes=MAX_BYTES, read_seconds=READ_SECONDS):
        if not isinstance(token, str) or len(token) < 32 or not token.isascii() or any(c.isspace() for c in token):
            raise ValueError("DOCUMENT_PREVIEW_TOKEN must contain at least 32 non-whitespace ASCII characters")
        self.token = token.encode("ascii")
        self.converter = converter or LibreOfficeConverter()
        self.slot = threading.BoundedSemaphore(1)
        self.max_bytes, self.read_seconds = max_bytes, read_seconds
        super().__init__(address, PreviewHandler)

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(self.read_seconds)
        return connection, address

    def handle_error(self, request, client_address):
        # Never dump request paths, file data, or authorization headers to container logs.
        pass


class PreviewHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "DocumentPreview"
    sys_version = ""

    def log_message(self, *_):
        pass

    def respond(self, status, body, content_type="application/json"):
        self.close_connection = True
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Connection", "close")
            if status == 429:
                self.send_header("Retry-After", "3")
            self.end_headers()
            self.wfile.write(body)
        except (ConnectionError, TimeoutError, socket.timeout):
            pass

    def error(self, status, code):
        self.respond(status, json.dumps({"code": code}, separators=(",", ":")).encode())

    def do_GET(self):
        if self.path == "/health":
            self.respond(200, b'{"status":"UP"}')
        else:
            self.error(404, "NOT_FOUND")

    def do_POST(self):
        parsed = urlsplit(self.path)
        if parsed.path != "/convert" or parsed.scheme or parsed.netloc or parsed.fragment:
            self.error(404, "NOT_FOUND")
            return
        authorization = self.headers.get_all("Authorization", [])
        actual = authorization[0].encode("utf-8") if len(authorization) == 1 else b""
        if not hmac.compare_digest(actual, b"Bearer " + self.server.token):
            self.error(401, "UNAUTHORIZED")
            return
        parameters = parse_qs(parsed.query, keep_blank_values=True)
        if set(parameters) != {"format"} or parameters["format"] not in (["ppt"], ["pptx"]):
            self.error(400, "UNSUPPORTED_FORMAT")
            return
        sizes = self.headers.get_all("Content-Length", [])
        if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Encoding", "identity") != "identity" \
                or len(sizes) != 1 or not sizes[0].isascii() or not sizes[0].isdigit():
            self.error(400, "CONTENT_LENGTH_REQUIRED")
            return
        try:
            size = int(sizes[0])
        except ValueError:
            self.error(400, "INVALID_SIZE")
            return
        if size <= 0 or size > self.server.max_bytes:
            self.error(413, "INPUT_TOO_LARGE")
            return
        if not self.server.slot.acquire(blocking=False):
            self.error(429, "CONVERTER_BUSY")
            return
        try:
            remaining = size
            chunks = []
            deadline = time.monotonic() + self.server.read_seconds
            while remaining:
                seconds = deadline - time.monotonic()
                if seconds <= 0:
                    raise ConversionError(408, "UPLOAD_TIMEOUT")
                self.connection.settimeout(seconds)
                chunk = self.rfile.read1(min(65536, remaining))
                if not chunk:
                    raise ConversionError(400, "INCOMPLETE_UPLOAD")
                chunks.append(chunk)
                remaining -= len(chunk)
            payload = b"".join(chunks)
            del chunks
            extension = parameters["format"][0]
            validate_source(payload, extension)
            self.connection.settimeout(20)
            pdf = self.server.converter(payload, extension)
            if len(pdf) > self.server.max_bytes:
                raise ConversionError(413, "OUTPUT_TOO_LARGE")
            if not pdf.startswith(b"%PDF-"):
                raise ConversionError(422, "CONVERSION_FAILED")
            self.respond(200, pdf, "application/pdf")
        except ConversionError as failure:
            self.error(failure.status, failure.code)
        except (TimeoutError, socket.timeout):
            self.error(408, "UPLOAD_TIMEOUT")
        except Exception:
            self.error(500, "CONVERSION_UNAVAILABLE")
        finally:
            self.server.slot.release()


def main():
    token = os.environ.get("DOCUMENT_PREVIEW_TOKEN", "")
    server = PreviewServer(("0.0.0.0", 8092), token)
    server.serve_forever(poll_interval=0.25)


if __name__ == "__main__":
    main()
