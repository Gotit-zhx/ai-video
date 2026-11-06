import contextlib
import tempfile
import sqlite3
from pathlib import Path
import unittest
from unittest import mock

import runner
import passive_runtime as flow


class RecommendationBudgetTest(unittest.TestCase):
    def test_continuous_pagination_waits_before_any_platform_call_and_preserves_requested_page(self):
        with tempfile.TemporaryDirectory() as folder:
            c = mock.Mock()
            c.AUTH_DIR = Path(folder) / 'auth'
            g = mock.Mock()
            g.BUDGET_DB = Path(folder) / 'budget.sqlite3'
            g.HOURLY_LIMIT = 120
            request = {'operation': 'recommendations', 'root': folder,
                       'cdp': 'http://127.0.0.1:9222', 'jobId': 'open-job', 'page': 23,
                       'network': {'address': '127.0.0.1'}}
            with mock.patch('runner.configure', return_value=(c, g)), \
                    mock.patch('direct_network.install_client'), \
                    mock.patch('boss_agent_cli.auth.token_store.TokenStore'), \
                    mock.patch('passive_runtime.budget_delay', return_value=120) as delay:
                result = runner.main(request)
            self.assertEqual(result['error']['code'], 'REQUEST_BUDGET_WAIT')
            self.assertEqual(request['page'], 23)
            delay.assert_called_once_with(g.BUDGET_DB, 120, cost=2, history=True, last_operation_at=0)
            c.command.assert_not_called()
            c.sanitize.assert_not_called()

    def test_page_cooldown_survives_restart_and_is_not_reset_by_unrelated_reads(self):
        with tempfile.TemporaryDirectory() as folder:
            budget = Path(folder)/'budget.sqlite3'
            with contextlib.closing(sqlite3.connect(budget)) as db, db:
                db.execute('CREATE TABLE requests(at REAL)')
                db.execute('INSERT INTO requests VALUES(999)')
            c = mock.Mock()
            c.passive_delay = lambda **kw: flow.budget_delay(budget, 120, **kw)
            with mock.patch('runner.time.time', return_value=1000):
                self.assertEqual(runner.recommendation_wait(c, folder), 0)
            with mock.patch('runner.time.time', return_value=1020):
                self.assertEqual(runner.recommendation_wait(c, folder), 40)
            with contextlib.closing(sqlite3.connect(budget)) as db, db:
                db.execute('INSERT INTO requests VALUES(1059)')
            with mock.patch('runner.time.time', return_value=1060):
                self.assertEqual(runner.recommendation_wait(c, folder), 0)
            with mock.patch('runner.time.time', return_value=1061):
                self.assertEqual(runner.recommendation_wait(c, folder), 59)

    def test_receipt_reserve_remains_unavailable_to_recommendation_pages(self):
        with tempfile.TemporaryDirectory() as folder:
            budget = Path(folder)/'budget.sqlite3'
            with contextlib.closing(sqlite3.connect(budget)) as db, db:
                db.execute('CREATE TABLE requests(at REAL)')
                db.executemany('INSERT INTO requests VALUES(?)', [(999,)]*80)
            c = mock.Mock()
            c.passive_delay = lambda **kw: flow.budget_delay(budget, 120, **kw)
            with mock.patch('runner.time.time', return_value=1000):
                self.assertGreater(runner.recommendation_wait(c, folder), 0)


if __name__ == '__main__':
    unittest.main()
