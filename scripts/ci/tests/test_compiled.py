import io
import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import java
import evidence

class CompiledTests(unittest.TestCase):
    def test_bundle_rejects_path_traversal_before_extract(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);archive=folder/'compiled.tar.gz'
            with tarfile.open(archive,'w:gz') as tar:
                entry=tarfile.TarInfo('../escaped');entry.size=1;tar.addfile(entry,io.BytesIO(b'x'))
            with self.assertRaises(ValueError):java.restore_compiled(archive,folder/'out',{})
            self.assertFalse((folder/'escaped').exists())
    def test_bundle_checks_every_payload_digest(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);archive=folder/'compiled.tar.gz'
            with tarfile.open(archive,'w:gz') as tar:
                entry=tarfile.TarInfo('target/classes/A.class');entry.size=1;tar.addfile(entry,io.BytesIO(b'x'))
            with self.assertRaises(ValueError):java.restore_compiled(archive,folder/'out',{'files':{'target/classes/A.class':'f'*64}})
    def test_reports_missing_failed_skipped_cannot_qualify(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d)
            with self.assertRaises(ValueError):evidence.audit_reports(folder,['java-unit'])
            report=folder/'java-unit-1/unit/summary.json';report.parent.mkdir(parents=True)
            for value in [{'result':'failed','unexpectedSkips':0},{'result':'passed','unexpectedSkips':1}]:
                report.write_text(json.dumps(value))
                with self.assertRaises(ValueError):evidence.audit_reports(folder,['java-unit'])
    def test_balanced_db_shards_keep_every_class_exactly_once(self):
        classes=['A','B','C','D','E'];durations={'A':90,'B':40,'C':30,'D':20,'E':10}
        shards=java.balanced_shards(classes,3,durations)
        self.assertEqual(classes,sorted(c for shard in shards for c in shard))
        self.assertEqual(shards,java.balanced_shards(list(reversed(classes)),3,durations))
        self.assertLessEqual(max(sum(durations[c] for c in shard) for shard in shards),90)
    def test_nonstandard_test_class_cannot_disappear_from_full_inventory(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);tests=folder/'src/test/java';tests.mkdir(parents=True)
            (tests/'HiddenSpec.java').write_text('class HiddenSpec { @Test void required() {} }')
            with patch.object(java,'ROOT',folder),self.assertRaises(ValueError):java.inventory()
