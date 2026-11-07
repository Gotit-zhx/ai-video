import fcntl
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('heyi_backup',Path(__file__).resolve().parents[2]/'heyi-mysql-backup.py')
backup=importlib.util.module_from_spec(spec);spec.loader.exec_module(backup)


class BackupReceipts(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.patcher=patch.object(backup,'ROOT',self.root);self.patcher.start();self.addCleanup(self.patcher.stop)
    def test_failure_retains_last_success_and_has_no_sql_or_credentials(self):
        backup.receipt('success',archive='ai-commerce-test.sql.gz',bytes=10,sha256='a'*64,integrity='gzip_verified')
        success=json.loads((self.root/'status.json').read_text())['lastSuccess']
        for reason in ('lock_busy','insufficient_space','timeout','integrity_failed','dump_failed','io_failed'):
            backup.receipt(reason)
            value=json.loads((self.root/'status.json').read_text())
            self.assertEqual(success,value['lastSuccess']);self.assertEqual(reason,value['outcome'])
            self.assertLess((self.root/'status.json').stat().st_size,1024)
    def test_low_disk_blocks_dump_without_pruning_valid_two_backups(self):
        files=[self.root/('ai-commerce-2026110'+str(i)+'.sql.gz') for i in (5,6)]
        for file in files:file.write_bytes(b'archive')
        with patch.object(backup,'available',return_value=14*backup.GIB-1),patch.object(backup.subprocess,'Popen') as popen:
            self.assertEqual(75,backup.main());popen.assert_not_called()
        self.assertTrue(all(f.is_file() for f in files))
        self.assertEqual('insufficient_space',json.loads((self.root/'status.json').read_text())['outcome'])
    def test_lock_busy_produces_distinct_receipt_without_starting_dump(self):
        with (self.root/'.backup.lock').open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with patch.object(backup.subprocess,'Popen') as popen:
                self.assertEqual(0,backup.main());popen.assert_not_called()
        self.assertEqual('lock_busy',json.loads((self.root/'status.json').read_text())['outcome'])
