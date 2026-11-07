import io
import os
import signal
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops.artifacts import Artifacts
from heyi_ops.state import atomic


class StagingCleanup(unittest.TestCase):
    def test_inline_upload_preserves_binary_bytes_in_fixed_asset_names(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary);manifest=b'{"synthetic":true}\n';source=b'\x1f\x8b\0\xff\narchive'
            sizes={'release.json':len(manifest),'source.tar.gz':len(source)}
            Artifacts.receive_assets(folder,io.BytesIO(manifest+source),sizes,sizes)
            self.assertEqual(manifest,(folder/'release.json').read_bytes())
            self.assertEqual(source,(folder/'source.tar.gz').read_bytes())
    def test_size_mismatch_truncation_and_trailing_data_fail_closed(self):
        sizes={'release.json':2,'source.tar.gz':3}
        for data,declared in [(b'{}abc',dict(sizes,**{'source.tar.gz':4})),(b'{}ab',sizes),(b'{}abcd',sizes)]:
            with self.subTest(data=data),tempfile.TemporaryDirectory() as temporary:
                with self.assertRaises(ValueError):Artifacts.receive_assets(Path(temporary),io.BytesIO(data),declared,sizes)
    def test_catchable_disconnect_removes_the_temporary_credential(self):
        with tempfile.TemporaryDirectory() as temporary:
            artifact=Artifacts(temporary);previous=signal.getsignal(signal.SIGHUP)
            with patch('heyi_ops.artifacts.process_identity',return_value='own-process'):
                with self.assertRaises(InterruptedError):
                    with artifact.workspace('v1.0.0') as folder:
                        (folder/'docker').mkdir();(folder/'docker/config.json').write_text('synthetic credential')
                        os.kill(os.getpid(),signal.SIGHUP)
            self.assertFalse(folder.exists());self.assertEqual(previous,signal.getsignal(signal.SIGHUP))
    def test_only_dead_owned_prepares_are_removed_after_a_hard_kill(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);artifact=Artifacts(root)
            for name,pid,extra in [('dead',11,False),('live',22,False),('foreign',33,True)]:
                folder=root/'staging'/('prepare-'+name);folder.mkdir(parents=True)
                atomic(folder/'.heyi-prepare.json',{'owner':'heyi-ops.prepare','version':'v1.0.0','pid':pid,'processStart':'same'})
                (folder/'docker').mkdir();(folder/'docker/config.json').write_text('synthetic credential')
                if extra:(folder/'notes').write_text('another task')
            unknown=root/'staging/prepare-unknown';unknown.mkdir()
            with patch('heyi_ops.artifacts.process_identity',side_effect=lambda pid:'same' if pid==22 else None):
                self.assertEqual(['prepare-dead'],artifact.cleanup_staging())
            for name in ('live','foreign','unknown'):self.assertTrue((root/'staging'/('prepare-'+name)).exists())
            self.assertTrue((root/'staging-cleanup-plan.json').is_file())
