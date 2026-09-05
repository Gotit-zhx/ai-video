#!/usr/bin/env python3
"""Expose only marketplace OAuth callbacks to a cpolar tunnel."""

from __future__ import annotations

import argparse
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


ALLOWED_CALLBACKS = frozenset(
    {
        "/api/v1/shop-authorizations/callback/shopee",
        "/api/v1/shop-authorizations/callback/tiktok_shop",
    }
)
HOP_BY_HOP_HEADERS = frozenset(
    {
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "content-length",
        "server",
        "date",
    }
)
MAX_REQUEST_TARGET_BYTES = 16 * 1024
MAX_RESPONSE_BYTES = 1024 * 1024


class CallbackGateway(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, server_address: tuple[str, int], upstream: tuple[str, int]):
        super().__init__(server_address, CallbackHandler)
        self.upstream = upstream


class CallbackHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if len(self.path.encode("utf-8", errors="ignore")) > MAX_REQUEST_TARGET_BYTES:
            self._empty(414)
            return
        parsed = urlsplit(self.path)
        if parsed.path not in ALLOWED_CALLBACKS:
            self._empty(404)
            return

        upstream_host, upstream_port = self.server.upstream  # type: ignore[attr-defined]
        upstream = http.client.HTTPConnection(upstream_host, upstream_port, timeout=30)
        try:
            upstream.request(
                "GET",
                self.path,
                headers={
                    "Host": f"{upstream_host}:{upstream_port}",
                    "User-Agent": self.headers.get("User-Agent", "cpolar-callback-gateway"),
                    "Accept": self.headers.get("Accept", "*/*"),
                    "X-Forwarded-Proto": "https",
                    "X-Forwarded-Host": self.headers.get("Host", ""),
                },
            )
            response = upstream.getresponse()
            body = response.read(MAX_RESPONSE_BYTES + 1)
            if len(body) > MAX_RESPONSE_BYTES:
                self._empty(502)
                return
            self.send_response(response.status)
            for name, value in response.getheaders():
                if name.lower() not in HOP_BY_HOP_HEADERS:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            if body:
                self.wfile.write(body)
        except (OSError, http.client.HTTPException):
            self._empty(502)
        finally:
            upstream.close()

    def do_HEAD(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._empty(404)

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._empty(405)

    def log_message(self, _format: str, *_args: object) -> None:
        # OAuth callbacks carry one-time code/state values in the query string.
        # Never retain the request target in local logs.
        return

    def _empty(self, status: int) -> None:
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.end_headers()


def parse_upstream(value: str) -> tuple[str, int]:
    parsed = urlsplit(f"//{value}")
    if parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
        raise argparse.ArgumentTypeError("upstream must be a local host:port")
    if parsed.hostname not in {"127.0.0.1", "localhost", "::1"} or parsed.port is None:
        raise argparse.ArgumentTypeError("upstream must be loopback host:port")
    return parsed.hostname, parsed.port


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-port", type=int, default=39080)
    parser.add_argument("--upstream", type=parse_upstream, default=parse_upstream("127.0.0.1:3000"))
    args = parser.parse_args()
    if not 1 <= args.listen_port <= 65535:
        parser.error("listen port must be between 1 and 65535")
    server = CallbackGateway(("127.0.0.1", args.listen_port), args.upstream)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
