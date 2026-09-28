import base64
import contextlib
import http.client
import os
import socket
import struct
import threading
import unittest
from unittest.mock import Mock, patch
import direct_network as d

ADAPTER = {"name": "en0", "label": "Ethernet", "index": 8,
           "address": "192.168.1.10", "dns": ["192.168.1.1"]}


class PhysicalNetworkTests(unittest.TestCase):
    def test_macos_binds_interface_and_source(self):
        sock = Mock()
        d.bind_physical(sock, ADAPTER, "darwin")
        sock.setsockopt.assert_called_once_with(socket.IPPROTO_IP, 25, 8)
        sock.bind.assert_called_once_with(("192.168.1.10", 0))

    def test_windows_index_is_network_order(self):
        sock = Mock()
        d.bind_physical(sock, ADAPTER, "win32")
        sock.setsockopt.assert_called_once_with(socket.IPPROTO_IP, 31, b"\0\0\0\x08")
        sock.bind.assert_called_once_with(("192.168.1.10", 0))

    def test_bind_failure_never_connects_without_binding(self):
        sock = Mock()
        with patch.object(d, "_socket", return_value=sock), \
                patch.object(d, "bind_physical", side_effect=OSError("unavailable")):
            with self.assertRaises(OSError):
                d.DirectNetwork(ADAPTER).socket()
        sock.close.assert_called_once()
        sock.connect.assert_not_called()

    def test_fake_ip_private_ipv6_are_rejected(self):
        for address in ["198.18.0.2", "127.0.0.1", "192.168.1.1", "169.254.1.1", "::1"]:
            with self.subTest(address=address), self.assertRaises(OSError):
                d.DirectNetwork(ADAPTER).resolve(address)

    def test_dns_is_sent_only_from_physical_socket(self):
        net = d.DirectNetwork(ADAPTER)
        sock = Mock()
        sock.__enter__ = Mock(return_value=sock)
        sock.__exit__ = Mock(return_value=False)
        question = b"\x07example\x03com\0\0\1\0\1"
        request = b"\x12\x34\x01\x00\x00\x01\0\0\0\0\0\0" + question
        response = b"\x12\x34\x81\x80\x00\x01\x00\x01\0\0\0\0" + question
        response += b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 60, 4) + socket.inet_aton("93.184.215.14")
        sock.recv.return_value = response
        with patch.object(net, "socket", return_value=sock) as factory, \
                patch.object(d.secrets, "token_bytes", return_value=b"\x12\x34"):
            self.assertEqual(net.resolve("example.com"), ["93.184.215.14"])
        factory.assert_called_once_with(socket.SOCK_DGRAM)
        sock.connect.assert_called_once_with(("192.168.1.1", 53))
        sock.send.assert_called_once_with(request)
        with self.assertRaises(ValueError):
            d.dns_answers(b"\x12\x35" + response[2:], request)

    def test_sidecar_uses_same_adapter_without_system_proxy(self):
        import urllib.request
        original = socket.create_connection
        try:
            with patch.dict(os.environ, {"https_proxy": "http://127.0.0.1:1082"}), \
                    patch.object(urllib.request, "getproxies"), \
                    patch.object(d.DirectNetwork, "connect", return_value="direct") as connect, \
                    patch.object(d, "_create_connection", return_value="local") as local:
                d.install_client(ADAPTER)
                self.assertEqual(socket.create_connection(("example.com", 443)), "direct")
                self.assertEqual(socket.create_connection(("127.0.0.1", 12345)), "local")
                connect.assert_called_once_with("example.com", 443, 15)
                local.assert_called_once()
                self.assertEqual(urllib.request.getproxies(), {})
                self.assertNotIn("https_proxy", os.environ)
        finally:
            socket.create_connection = original

    def test_tunnel_rejects_unpaired_process_before_network(self):
        server = d.ThreadingHTTPServer(("127.0.0.1", 0), d.TunnelHandler)
        server.secret = "unit-test"
        server.network = Mock()
        server.network.connect.side_effect = OSError("offline")
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            client = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
            client.request("CONNECT", "example.com:443")
            self.assertEqual(client.getresponse().status, 407)
            server.network.connect.assert_not_called()
            client.close()
            client = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
            auth = base64.b64encode(b"heyi:unit-test").decode()
            client.request("CONNECT", "example.com:443", headers={"Proxy-Authorization": "Basic " + auth})
            self.assertEqual(client.getresponse().status, 502)
            server.network.connect.assert_called_once_with("example.com", 443)
            client.close()
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
