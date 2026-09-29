import tempfile
import unittest
from pathlib import Path
from unittest import mock
import hr_departments as departments
import talent_pool as pool
from test_talent_pool import USER


class DepartmentsTest(unittest.TestCase):
    def setUp(self):
        folder=tempfile.TemporaryDirectory();self.addCleanup(folder.cleanup)
        patch=mock.patch.object(pool,"DB",Path(folder.name)/"hr.sqlite3");patch.start();self.addCleanup(patch.stop)

    def test_independent_empty_directory_create_edit_and_audit(self):
        self.assertEqual(departments.listing(USER)["items"],[])
        first=departments.save(USER,{"name":"电商","code":"ecom","manager":"张经理","status":"ACTIVE"})
        self.assertEqual(first["code"],"ECOM")
        second=departments.save(USER,{**first,"manager":"李经理","status":"DISABLED"})
        self.assertEqual(second["version"],2)
        self.assertEqual(departments.listing(USER)["items"][0]["manager"],"李经理")
        with pool.database() as db:
            tables={row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            self.assertNotIn("departments",tables);self.assertIn("hr_departments",tables)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM talent_events").fetchone()[0],2)
        with self.assertRaises(pool.Conflict):departments.save(USER,{**first,"manager":"旧修改"})

    def test_hierarchy_rejects_cycles_missing_parents_and_duplicate_names(self):
        root=departments.save(USER,{"name":"电商","code":"ec","status":"ACTIVE"})
        child=departments.save(USER,{"name":"运营","code":"ops","status":"ACTIVE","parentId":root["id"]})
        with self.assertRaises(ValueError):departments.save(USER,{**root,"parentId":child["id"]})
        with self.assertRaises(ValueError):departments.save(USER,{**root,"parentId":"missing"})
        with self.assertRaises(pool.Conflict):departments.save(USER,{"name":"电商","code":"other","status":"ACTIVE"})
        with self.assertRaises(ValueError):departments.listing({**USER,"username":"other"})

if __name__=="__main__":unittest.main()
