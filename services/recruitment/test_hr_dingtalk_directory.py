import unittest
from unittest import mock

import dingtalk_interviews as dt
import hr_dingtalk_directory as directory
import talent_pool as pool

CONFIG = {"version": 1, "app_key": "test-app", "secret": "encrypted-test"}


class DirectoryTest(unittest.TestCase):
    def setUp(self):
        directory.CACHE.clear()
        for patch in (mock.patch.object(pool, "context", return_value=("heyi", "admin")),
                      mock.patch.object(dt, "read_config", return_value=CONFIG),
                      mock.patch.object(dt, "token", return_value="test-token"),
                      mock.patch.object(directory, "launch")):
            value = patch.start()
            self.addCleanup(patch.stop)
        self.query = {"configVersion": "1"}

    def run_sync(self, call):
        directory.listing({}, self.query)
        key = directory.key_for("heyi", CONFIG)
        with mock.patch.object(dt, "api", side_effect=call):
            directory.sync(key, CONFIG, directory.CACHE[key])
        return directory.listing({}, self.query)

    @staticmethod
    def call(path, body, token):
        assert path in ("topapi/v2/department/listsub", "topapi/v2/user/list")
        if path.endswith("listsub"):
            return {"result": [{"dept_id": 2, "name": "研发"}] if body["dept_id"] == 1 else []}
        if body["dept_id"] == 1:
            return {"result": {"list": [{"userid": "employee-a", "name": "甲", "mobile": "private-phone"}],
                               "has_more": False}}
        cursor = body["cursor"]
        return {"result": {"list": [{"userid": "employee-a" if cursor == 0 else "employee-b",
                                     "name": "甲" if cursor == 0 else "乙", "email": "private-email"}],
                           "has_more": cursor == 0, "next_cursor": 1}}

    def test_reads_every_page_deduplicates_departments_and_minimizes_fields(self):
        result = self.run_sync(self.call)
        self.assertEqual(result["state"], "complete")
        self.assertEqual(result["employeeCount"], 2)
        self.assertEqual(next(p for p in result["items"] if p["userId"] == "employee-a")["departmentIds"], ["1", "2"])
        self.assertNotIn("private", str(result))
        searched = directory.listing({}, self.query | {"q": "乙"})
        self.assertEqual([p["userId"] for p in searched["items"]], ["employee-b"])
        self.assertEqual(directory.listing({}, self.query | {"q": "研发", "departmentId": "2"})["total"], 2)
        self.assertEqual(directory.launch.call_count, 1)
        directory.listing({}, self.query | {"refresh": "1"})
        directory.listing({}, self.query | {"refresh": "1"})
        self.assertEqual(directory.launch.call_count, 2, "A running refresh has only one worker")

    def test_permission_error_fails_once_and_does_not_expose_partial_as_complete(self):
        def denied(path, body, token):
            if body["dept_id"] == 2:
                raise dt.DingError("通讯录权限不足，请授权")
            return self.call(path, body, token)
        result = self.run_sync(denied)
        self.assertEqual(result["state"], "failed")
        self.assertEqual(result["items"], [])
        self.assertIn("权限", result["error"])
        for _ in range(5):
            directory.listing({}, self.query)
        self.assertEqual(directory.launch.call_count, 1)
        directory.listing({}, self.query | {"refresh": "1"})
        self.assertEqual(directory.launch.call_count, 2)

    def test_repeated_cursor_and_department_cycle_fail_with_bounded_calls(self):
        for cycle in ("cursor", "department"):
            directory.CACHE.clear()
            def call(path, body, token):
                result = self.call(path, body, token)
                if cycle == "cursor" and path.endswith("user/list") and body["dept_id"] == 2:
                    result["result"]["next_cursor"] = 0
                if cycle == "department" and path.endswith("listsub") and body["dept_id"] == 2:
                    result["result"] = [{"dept_id": 1, "name": "循环"}]
                return result
            result = self.run_sync(call)
            self.assertEqual(result["state"], "failed")
            self.assertEqual(result["items"], [])

    def test_owner_config_isolation_expiry_and_page_boundaries(self):
        self.run_sync(self.call)
        key = directory.key_for("heyi", CONFIG)
        entry = directory.CACHE[key]
        entry["items"] = [{"userId": str(i), "name": "测试", "departmentIds": ["1"], "department": "研发"}
                          for i in range(45)]
        self.assertEqual(len(directory.listing({}, self.query | {"page": "2"})["items"]), 20)
        self.assertEqual(len(directory.listing({}, self.query | {"page": "100"})["items"]), 5)
        with mock.patch.object(pool, "context", return_value=("other", "admin")):
            self.assertEqual(directory.listing({}, self.query)["state"], "running")
        with mock.patch.object(dt, "read_config", return_value=CONFIG | {"secret": "changed"}):
            self.assertEqual(directory.listing({}, self.query)["state"], "running")
        entry["syncedAt"] -= directory.TTL+1
        self.assertEqual(directory.listing({}, self.query)["state"], "running")
        with self.assertRaises(pool.Conflict):
            directory.listing({}, {"configVersion": "2"})
        with mock.patch.object(dt, "read_config", side_effect=[CONFIG, CONFIG | {"version": 2}]):
            with self.assertRaises(pool.Conflict):
                directory.listing({}, self.query)
        with mock.patch.object(pool, "context", side_effect=ValueError("无权限")), mock.patch.object(dt, "api") as api:
            with self.assertRaises(ValueError):
                directory.listing({}, self.query)
            api.assert_not_called()

    def test_limits_and_missing_pagination_never_claim_complete(self):
        with mock.patch.object(directory, "MAX_EMPLOYEES", 1):
            result = self.run_sync(self.call)
        self.assertEqual(result["state"], "failed")
        directory.CACHE.clear()
        def incomplete(path, body, token):
            result = self.call(path, body, token)
            if path.endswith("user/list"):
                result["result"].pop("has_more")
            return result
        self.assertEqual(self.run_sync(incomplete)["state"], "failed")


if __name__ == "__main__":
    unittest.main()
