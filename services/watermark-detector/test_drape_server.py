"""Exercise the authenticated local drape HTTP boundary without generation calls."""
import json
import os
import secrets
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

os.environ.setdefault('WATERMARK_DETECTOR_TOKEN', secrets.token_hex(24))
os.environ['WATERMARK_DETECTOR_ENGINE'] = 'local-overlay'
import server
import drape_reuse


class DrapeServiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http = server.ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = 'http://127.0.0.1:' + str(cls.http.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown(); cls.http.server_close(); cls.thread.join()

    def request(self, route, body, token=True):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['X-Internal-Token'] = server.TOKEN
        return urlopen(Request(self.url+route, data=json.dumps(body).encode(), headers=headers), timeout=10)

    def test_token_is_required_before_either_route_runs(self):
        for route in ('/drape-prepare', '/drape-render'):
            with self.subTest(route=route), patch.object(drape_reuse, 'prepare') as prepare, patch.object(drape_reuse, 'render') as render:
                with self.assertRaises(HTTPError) as caught:
                    self.request(route, {}, token=False)
                self.assertEqual(401, caught.exception.code)
                prepare.assert_not_called(); render.assert_not_called()

    def test_real_validation_returns_explicit_stop_without_retry(self):
        with self.assertRaises(HTTPError) as caught:
            self.request('/drape-render', {})
        self.assertEqual(422, caught.exception.code)
        result = json.load(caught.exception)
        self.assertEqual('MAPPING_INVALID', result['code'])
        self.assertFalse(result['retryable']); self.assertEqual(0, result['modelCalls'])
        self.assertIn('场景', result['message'])

    def test_both_routes_dispatch_while_holding_shared_lock(self):
        def local_handler(body):
            self.assertTrue(server.LOCK.locked())
            self.assertEqual({'fixture': 'local'}, body)
            return {'algorithm': drape_reuse.ALGORITHM, 'report': {'passed': True, 'modelCalls': 0}}
        for route, function in (('/drape-prepare', 'prepare'), ('/drape-render', 'render')):
            with self.subTest(route=route), patch.object(drape_reuse, function, side_effect=local_handler) as handler:
                with self.request(route, {'fixture': 'local'}) as response:
                    result = json.load(response)
                handler.assert_called_once()
                self.assertTrue(result['report']['passed'])

    def test_unexpected_failures_do_not_expose_exception_content(self):
        with patch.object(drape_reuse, 'prepare', side_effect=RuntimeError('private filesystem details')):
            with self.assertRaises(HTTPError) as caught:
                self.request('/drape-prepare', {})
        result = json.load(caught.exception)
        self.assertEqual('LOCAL_DRAPE_FAILED', result['code'])
        self.assertNotIn('private', json.dumps(result))
        self.assertFalse(result['retryable']); self.assertEqual(0, result['modelCalls'])


if __name__ == '__main__':
    unittest.main()
