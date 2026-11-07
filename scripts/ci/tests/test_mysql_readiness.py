from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import java


class MysqlReadinessTest(unittest.TestCase):
    def test_socket_only_initialization_is_not_ready(self):
        tcp_attempts=[]
        def query(command,**kwargs):
            if '--protocol=TCP' not in command:
                return subprocess.CompletedProcess(command,0,stdout=b'1\n')
            tcp_attempts.append(command)
            return subprocess.CompletedProcess(command,0 if len(tcp_attempts)==2 else 1,stdout=b'1\n')
        with patch.object(java.subprocess,'run',side_effect=query),patch.object(java.time,'sleep'):
            java.wait_for_mysql('disposable-unit-mysql')
        self.assertEqual(2,len(tcp_attempts))

    def test_hung_or_invalid_query_does_not_bypass_deadline(self):
        responses=[subprocess.TimeoutExpired('docker',5),subprocess.CompletedProcess([],0,stdout=b'')]
        with patch.object(java.subprocess,'run',side_effect=responses),patch.object(java.time,'sleep'), \
                patch.object(java.time,'monotonic',side_effect=[0,1,2,91]):
            with self.assertRaisesRegex(RuntimeError,'startup timed out'):
                java.wait_for_mysql('disposable-unit-mysql')


if __name__=='__main__':unittest.main()
