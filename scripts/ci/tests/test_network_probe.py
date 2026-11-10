import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from network_probe import classify_sample, run_probe, validate_endpoint, collect_process


class ProbeTest(unittest.TestCase):
    def test_partial_deadline_and_tls_are_distinct(self):
        self.assertEqual('sample_incomplete', classify_sample(28, True, True, 858643))
        self.assertEqual('connect_timeout', classify_sample(28, False, False, 0))
        self.assertEqual('tls_failed', classify_sample(35, True, False, 0))
        self.assertEqual('tls_failed', classify_sample(28, True, False, 0))

    def test_only_fixed_public_https_endpoints_are_allowed(self):
        for url in ['http://ghcr.io/v2/', 'https://evil.example/',
                    'https://secret@api.github.com/', 'https://api.github.com/?token=x',
                    'https://github.com/other/private/releases/download/a/source.tar.gz']:
            with self.assertRaises(ValueError): validate_endpoint({'name':'githubApi','url':url,'range':False})

    def test_configuration_budget_and_attempts_cannot_expand(self):
        base = {'endpoints': [{'name': 'githubApi', 'url': 'https://api.github.com/', 'range': False}], 'attempts': 2}
        for key, value in [('attempts', 3), ('maxBytes', 49*1024**2), ('maxSeconds', 151)]:
            with self.assertRaises(ValueError): run_probe(dict(base, **{key:value}), runner=lambda *a: {})

    def test_results_never_echo_auth_urls_or_claim_dns_repair(self):
        def fake(endpoint, maximum, deadline):
            return {'endpoint':endpoint['name'], 'failureClass':'none', 'bytes':0,
                    'url_effective':'https://x/?signature=private', 'stderr':'private'}
        value = run_probe({'endpoints':[{'name':'githubApi','url':'https://api.github.com/','range':False}]}, runner=fake)
        self.assertNotIn('private', json.dumps(value))
        self.assertFalse(value['repairPerformed'])

    def test_real_subprocess_output_is_bounded(self):
        import subprocess, time
        process = subprocess.Popen([sys.executable, '-c',
            'import sys;sys.stdout.buffer.write(b"x"*1000000);sys.stdout.flush()'],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        result = collect_process(process, 1024, time.monotonic()+3)
        self.assertTrue(result['limitHit'])
        self.assertLessEqual(result['bytes'], 1025)
