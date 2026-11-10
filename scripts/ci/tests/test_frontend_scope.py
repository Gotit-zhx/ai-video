import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import run

class FrontendScopeTests(unittest.TestCase):
    def test_finance_runs_complete_financial_and_shared_rendering_regressions(self):
        tests,qa=run.frontend_selection(['finance-frontend','frontend-assets'])
        self.assertEqual([],tests)
        names={p.name for p in qa}
        for name in ['test-finance-charges-ui.mjs','test-finance-ad-authorization-ui.mjs','test-finance-profit-ui.mjs','test-finance-fee-exports-ui.mjs','test-incremental-dom.mjs','test-incremental-charts.mjs']:
            self.assertIn(name,names)
        self.assertNotIn('test-video-direct-downloads.mjs',names)
    def test_full_frontend_keeps_complete_inventory(self):
        tests,qa=run.frontend_selection(['all-frontend'])
        self.assertGreater(len(tests),40)
        self.assertIn('test-video-direct-downloads.mjs',{p.name for p in qa})
