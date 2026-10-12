import tempfile
from pathlib import Path
import unittest
from unittest import mock

import runner


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
            delay.assert_called_once_with(g.BUDGET_DB, 120, cost=2, history=True)
            c.command.assert_not_called()
            c.sanitize.assert_not_called()


if __name__ == '__main__':
    unittest.main()
