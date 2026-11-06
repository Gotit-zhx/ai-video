"""Exercise the configured guard in a separate process and disposable ledger."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class DesktopBudgetTest(unittest.TestCase):
    def test_500th_request_is_allowed_and_501st_is_rejected_without_resetting_ledger(self):
        with tempfile.TemporaryDirectory() as directory:
            script = """
import sqlite3,sys,time
from pathlib import Path
import runner
root=Path(sys.argv[1])/'accounts'/'owner'/'account'
c,g=runner.configure(root,'http://127.0.0.1:9222')
assert g.HOURLY_LIMIT==500
assert g.MIN_INTERVAL>=6
with sqlite3.connect(g.BUDGET_DB) as db:
    db.execute('CREATE TABLE requests(at REAL NOT NULL)')
    db.executemany('INSERT INTO requests VALUES(?)',[(time.time()-60,)]*499)
g.reserve_request()
runner.configure(root,'http://127.0.0.1:9222')
try:
    g.reserve_request()
except g.BudgetExhausted:
    pass
else:
    raise AssertionError('501st request must be refused')
with sqlite3.connect(g.BUDGET_DB) as db:
    assert db.execute('SELECT count(*) FROM requests').fetchone()[0]==500
"""
            runtime = Path(__file__).resolve().parent
            env = {**os.environ, 'PYTHONPATH': os.pathsep.join([
                str(runtime), str(runtime/'build/shared'), os.environ.get('PYTHONPATH', '')])}
            result = subprocess.run([sys.executable, '-c', script, directory], env=env,
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
