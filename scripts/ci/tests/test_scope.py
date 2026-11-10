import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import scope

class ScopeTests(unittest.TestCase):
    def classify(self,paths,**context):return scope.classify_changed_paths(paths,dict(lastFullPassed=True,lastFullAgeHours=1,**context))
    def test_shared_finance_frontend_is_complete_ui_not_all_db(self):
        result=self.classify(['frontend/finance-workspace.js'])
        self.assertIn('finance-frontend',result['requiredSuites']);self.assertNotIn('all-db',result['requiredSuites'])
    def test_finance_backend_migration_and_policy_are_full(self):
        for path in ['src/main/java/com/heyi/finance/FinanceService.java','src/main/resources/db/migration/V149__x.sql','scripts/ci/run.py','pom.xml']:
            self.assertEqual('full',self.classify([path])['profile'])
    def test_unknown_and_unmapped_test_are_full(self):
        self.assertIn('unknown_path',self.classify(['unknown.bin'])['reasonCodes'])
        self.assertIn('unmapped_test',self.classify(['scripts/qa/test-new-unknown.mjs'])['reasonCodes'])
    def test_fallbacks_and_host_maintenance(self):
        self.assertEqual('docs',self.classify(['docs/guides/cicd.md'])['profile'])
        self.assertTrue(self.classify(['ops/heyi-ops/heyi_ops/controller.py'])['requiresHostMaintenance'])
        self.assertTrue(self.classify(['services/pi-runtime/index.js'])['unsupported'])
        for context in [{},{'lastFullPassed':False,'lastFullAgeHours':1},{'lastFullPassed':True,'lastFullAgeHours':49}]:
            self.assertEqual('full',scope.classify_changed_paths(['frontend/finance-workspace.js'],context)['profile'])
    def test_catalog_expands_real_nonempty_suites(self):
        suites=scope.catalog()
        self.assertTrue(all(item['files'] for item in suites.values()))
        self.assertTrue(any(p.endswith('test-finance-charges-ui.mjs') for p in suites['finance-frontend']['files']))
        self.assertGreater(len(suites['all-db']['classes']),30)
    def test_component_readme_is_documentation_but_runtime_prompt_is_not(self):
        self.assertEqual('docs',self.classify(['services/pi-runtime/README.md'])['profile'])
        self.assertEqual('full',self.classify(['src/main/resources/prompts/worker.md'])['profile'])
