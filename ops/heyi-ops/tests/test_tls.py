import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops import tls
from heyi_ops.state import Store, Busy, read


class CertificateReload(unittest.TestCase):
    def test_nginx_config_failure_does_not_reload_or_report_success(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);store=Store(root,root/'lock')
            with patch.object(tls,'command',side_effect=RuntimeError('invalid config')) as command:
                with self.assertRaises(RuntimeError):tls.reload({},store)
            self.assertEqual(1,command.call_count);self.assertFalse((root/'tls.json').exists())
    def test_served_certificate_is_checked_under_the_release_lock(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);store=Store(root,root/'lock')
            def observe(_):
                with self.assertRaises(Busy):
                    with Store(root,root/'lock').exclusive():pass
                return {'matches':True,'expiresAt':100,'fileSha256':'a','servedSha256':'a'}
            with patch.object(tls,'command'),patch.object(tls,'observe',side_effect=observe):
                self.assertEqual('passed',tls.reload({},store)['result'])
            self.assertTrue(read(root/'tls.json')['matches'])
    def test_unloaded_certificate_is_not_reported_as_success(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with patch.object(tls,'command'),patch.object(tls,'observe',return_value={'matches':False}),patch.object(tls.time,'sleep'):
                with self.assertRaises(RuntimeError):tls.reload({},Store(root,root/'lock'))
            self.assertFalse((root/'tls.json').exists())
