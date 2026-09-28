"""App-owned egress, bound to a physical adapter on macOS and Windows.

No routing-table, system proxy, VPN, DNS or firewall changes. Binding failure
stops the request; it never retries through the system's default route.
"""
import base64
import hmac
import ipaddress
import json
import os
import re
import secrets
import select
import socket
import struct
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_socket = socket.socket
_getaddrinfo = socket.getaddrinfo
_create_connection = socket.create_connection


def command(*args):
    return subprocess.check_output(args, text=True, encoding="utf-8",
                                   stderr=subprocess.DEVNULL, timeout=8).strip()


def discover():
    if sys.platform == "darwin":
        # Hardware-port inventory excludes utun/tun/bridge/VPN devices.
        hardware = command("/usr/sbin/networksetup", "-listallhardwareports")
        candidates = []
        for label, name in re.findall(r"Hardware Port: ([^\n]+)\nDevice: ([^\n]+)", hardware):
            if not re.fullmatch(r"en\d+", name):
                continue
            try:
                address = command("/usr/sbin/ipconfig", "getifaddr", name)
                route = command("/sbin/route", "-n", "get", "-ifscope", name, "default")
                gateway = re.search(r"gateway:\s+([\d.]+)", route).group(1)
                dns = command("/usr/sbin/ipconfig", "getoption", name, "domain_name_server").split()
                candidates.append({"name": name, "label": label, "address": address,
                                   "index": socket.if_nametoindex(name), "dns": dns or [gateway]})
            except (OSError, subprocess.SubprocessError, AttributeError):
                continue
        # Prefer connected wired Ethernet, then Wi-Fi. Never select a tunnel.
        candidates.sort(key=lambda c: (c["label"] == "Wi-Fi", c["name"]))
    elif sys.platform == "win32":
        # No administrator privilege is needed for these read-only queries.
        script = r"""
$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[Text.UTF8Encoding]::new()
$items=@(Get-NetAdapter -Physical | Where-Object Status -eq 'Up' | ForEach-Object {
  $a=$_; $i=Get-NetIPConfiguration -InterfaceIndex $a.ifIndex
  $r=Get-NetRoute -InterfaceIndex $a.ifIndex -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue
  $ip=@($i.IPv4Address | Where-Object AddressState -eq 'Preferred')[0]
  $d=@((Get-DnsClientServerAddress -InterfaceIndex $a.ifIndex -AddressFamily IPv4).ServerAddresses)
  if($ip -and $r -and $d.Count) {
    [PSCustomObject]@{name=$a.Name;label=$a.InterfaceDescription;address=$ip.IPAddress;
      index=[int]$a.ifIndex;dns=$d;metric=[int]($r | Sort-Object RouteMetric | Select-Object -First 1).RouteMetric}
  }
} | Sort-Object metric)
ConvertTo-Json -Compress -Depth 4 -InputObject $items
"""
        candidates = json.loads(command("powershell.exe", "-NoProfile", "-NonInteractive",
                                        "-Command", script))
    else:
        raise RuntimeError("Direct physical networking requires macOS or Windows")
    for candidate in candidates:
        try:
            ip = ipaddress.IPv4Address(candidate["address"])
            candidate["dns"] = [str(ipaddress.IPv4Address(d)) for d in candidate["dns"]]
            if ip.is_loopback or ip.is_link_local or not candidate["dns"]:
                continue
            return candidate
        except (KeyError, ValueError):
            continue
    raise RuntimeError("没有可用的有线或 Wi-Fi 网卡，请检查本机网络后重新启动助手")


def bind_physical(sock, adapter, platform=None):
    platform = platform or sys.platform
    if platform == "darwin":
        # XNU bsd/netinet/in.h: IP_BOUND_IF = 25.
        sock.setsockopt(socket.IPPROTO_IP, 25, int(adapter["index"]))
    elif platform == "win32":
        # Winsock IP_UNICAST_IF = 31, interface index in network byte order.
        sock.setsockopt(socket.IPPROTO_IP, 31, struct.pack("!I", int(adapter["index"])))
    else:
        raise OSError("Unsupported physical interface binding")
    sock.bind((adapter["address"], 0))


def public_ip(value):
    ip = ipaddress.ip_address(value)
    # is_global excludes private/loopback/link-local/reserved/fake-IP ranges.
    return ip.version == 4 and ip.is_global


def skip_name(packet, offset):
    for _ in range(128):
        length = packet[offset]
        offset += 1
        if length == 0:
            return offset
        if length & 0xC0 == 0xC0:
            return offset + 1
        if length > 63:
            raise ValueError("Invalid DNS label")
        offset += length
    raise ValueError("DNS name too long")


def dns_answers(packet, request):
    if len(packet) < 12 or packet[:2] != request[:2]:
        raise ValueError("DNS transaction mismatch")
    _, flags, questions, answers, _, _ = struct.unpack("!6H", packet[:12])
    if flags & 0x8000 == 0 or flags & 0x020F or questions != 1:
        raise ValueError("DNS lookup incomplete")
    question_end = skip_name(packet, 12) + 4
    if packet[12:question_end] != request[12:]:
        raise ValueError("DNS question mismatch")
    offset, addresses = question_end, []
    for _ in range(answers):
        offset = skip_name(packet, offset)
        kind, cls, _, size = struct.unpack("!HHIH", packet[offset:offset + 10])
        offset += 10
        value = packet[offset:offset + size]
        offset += size
        if kind == 1 and cls == 1 and size == 4:
            address = socket.inet_ntoa(value)
            if public_ip(address):
                addresses.append(address)
    if not addresses:
        raise OSError("Physical DNS returned no public address")
    return addresses


class DirectNetwork:
    def __init__(self, adapter):
        self.adapter = adapter
        self.cache = {}
        self.lock = threading.Lock()

    def socket(self, kind=socket.SOCK_STREAM):
        sock = _socket(socket.AF_INET, kind)
        try:
            bind_physical(sock, self.adapter)
            return sock
        except BaseException:
            sock.close()
            raise

    def resolve(self, host):
        if self.adapter.get("name") == "offline":
            raise OSError("Offline test forbids external traffic")
        host = host.encode("idna").decode("ascii").lower().rstrip(".")
        try:
            ipaddress.ip_address(host)
        except ValueError:
            pass
        else:
            if not public_ip(host):
                raise OSError("Non-public destination denied")
            return [host]
        if not re.fullmatch(r"(?=.{1,253}$)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", host):
            raise OSError("Invalid destination")
        with self.lock:
            cached = self.cache.get(host)
            if cached and cached[0] > time.monotonic():
                return cached[1]
        labels = host.split(".")
        if any(not 0 < len(label) < 64 for label in labels):
            raise OSError("Invalid DNS name")
        question = b"".join(bytes([len(label)]) + label.encode() for label in labels) + b"\0\0\1\0\1"
        request = secrets.token_bytes(2) + b"\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + question
        for resolver in self.adapter["dns"]:
            try:
                with self.socket(socket.SOCK_DGRAM) as sock:
                    sock.settimeout(3)
                    sock.connect((resolver, 53))
                    sock.send(request)
                    addresses = dns_answers(sock.recv(4096), request)
                    with self.lock:
                        self.cache[host] = (time.monotonic() + 60, addresses)
                    return addresses
            except (OSError, ValueError, IndexError, struct.error):
                continue
        raise OSError("Physical-adapter DNS unavailable; no proxy fallback")

    def connect(self, host, port, timeout=15):
        if port not in (80, 443):
            raise OSError("Only web destinations are permitted")
        for address in self.resolve(host):
            sock = self.socket()
            try:
                sock.settimeout(timeout)
                sock.connect((address, port))
                return sock
            except OSError:
                sock.close()
        raise OSError("Physical-adapter connection failed; no proxy fallback")


def clean_proxy_environment():
    for key in list(os.environ):
        if key.lower() in ("http_proxy", "https_proxy", "all_proxy", "no_proxy", "ftp_proxy"):
            os.environ.pop(key, None)
    os.environ["NO_PROXY"] = "*"
    # urllib/httpx otherwise consult macOS SystemConfiguration or WinINET.
    import urllib.request
    urllib.request.getproxies = lambda: {}


def install_client(adapter):
    """All synchronous CLI HTTP transports share the browser's physical NIC."""
    clean_proxy_environment()
    network = DirectNetwork(adapter)

    def connect(address, timeout=socket._GLOBAL_DEFAULT_TIMEOUT, source_address=None,
                *, all_errors=False):
        host, port = address
        if host in ("127.0.0.1", "::1", "localhost"):
            return _create_connection(address, timeout, source_address)
        return network.connect(host, int(port), 15 if timeout is socket._GLOBAL_DEFAULT_TIMEOUT else timeout)

    socket.create_connection = connect
    return network


class TunnelHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    timeout = 20

    def log_message(self, *_):
        pass  # Never log hostnames, credentials, resume URLs or cookies.

    def do_CONNECT(self):
        expected = "Basic " + base64.b64encode(("heyi:" + self.server.secret).encode()).decode()
        if not hmac.compare_digest(self.headers.get("Proxy-Authorization", ""), expected):
            self.send_response(407)
            self.send_header("Proxy-Authenticate", 'Basic realm="Heyi Direct"')
            self.send_header("Content-Length", "0")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            return
        remote = None
        try:
            host, port = self.path.rsplit(":", 1)
            remote = self.server.network.connect(host, int(port))
            self.send_response(200, "Connection Established")
            self.end_headers()
            self.close_connection = True
            remote.settimeout(20)
            self.connection.settimeout(20)
            # TCP/TLS bytes pass unchanged; no TLS termination or interception.
            while True:
                ready, _, _ = select.select([self.connection, remote], [], [], 60)
                if not ready:
                    break
                for source in ready:
                    data = source.recv(65536)
                    if not data:
                        return
                    (remote if source is self.connection else self.connection).sendall(data)
        except (OSError, ValueError):
            if remote is None:
                self.send_error(502, "Physical connection unavailable")
        finally:
            if remote is not None:
                remote.close()
            self.close_connection = True

    def do_GET(self):
        self.send_error(405, "HTTPS required")


def serve():
    adapter = discover()
    clean_proxy_environment()
    server = ThreadingHTTPServer(("127.0.0.1", 0), TunnelHandler)
    server.daemon_threads = True
    server.network = DirectNetwork(adapter)
    server.secret = secrets.token_urlsafe(32)
    print(json.dumps({"port": server.server_port, "secret": server.secret, "adapter": adapter}),
          flush=True)
    # Parent pipe closure ends the helper. No background service survives quit.
    def watch_parent():
        sys.stdin.buffer.read()
        server.shutdown()
    threading.Thread(target=watch_parent, daemon=True).start()
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        server.server_close()
