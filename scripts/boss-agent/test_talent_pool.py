import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest import mock

import connector
import talent_pool as pool

USER = {"id": "admin-1", "username": "13800000000", "organizationCode": "heyi",
        "platformAdmin": True, "roles": ["ADMIN"]}


class TalentPoolTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        patch = mock.patch.object(pool, "DB", Path(folder.name)/"pool.sqlite3")
        patch.start()
        self.addCleanup(patch.stop)

    def test_stage_project_search_and_counts_are_independent(self):
        group = pool.save_project(USER, {"name": "电商项目组"})
        for stage in pool.STAGES:
            pool.save(USER, {"name": "张三"+stage, "projectId": group["id"], "stage": stage})
        pool.save(USER, {"name": "另一个人", "stage": "hired"})
        result = pool.listing(USER, {"project": group["id"], "stage": "hired"})
        self.assertEqual(result["total"], 1)
        self.assertEqual(result["counts"]["all"], 5)
        self.assertEqual(result["counts"]["first"], 1)
        self.assertEqual(pool.listing(USER, {"project": "unassigned"})["total"], 1)
        self.assertEqual(pool.listing(USER, {"q": "%"})["total"], 0)
        self.assertEqual(pool.listing(USER, {"q": "张三"})["total"], 5)

    def test_save_persists_and_updates_audit_and_rejects_lost_updates(self):
        record = pool.save(USER, {"name": "陈同学", "education": "本科", "experience": "5年"})
        updated = pool.save(USER, {**record, "stage": "second", "notes": "安排复试"})
        self.assertEqual(updated["version"], 2)
        with self.assertRaises(pool.Conflict):
            pool.save(USER, {**record, "stage": "hired"})
        self.assertEqual(pool.listing(USER, {})["items"][0]["notes"], "安排复试")
        with pool.database() as db:
            events = db.execute("SELECT * FROM talent_events").fetchall()
            self.assertEqual(len(events), 2)
            self.assertEqual(events[-1]["actor"], "admin-1")
            self.assertEqual(json.loads(events[-1]["before_json"])["stage"], "")
            self.assertEqual(json.loads(events[-1]["after_json"])["stage"], "second")

    def test_duplicate_boss_candidate_cannot_overwrite_existing_progress(self):
        record = pool.save(USER, {"name": "王同学", "source": "boss", "sourceId": "geek-1", "stage": "first"})
        with self.assertRaises(pool.Conflict):
            pool.save(USER, {"name": "王同学", "sourceId": "geek-1"})
        result = pool.save(USER, {**record, "sourceId": "modified", "stage": "hired"})
        self.assertEqual(result["sourceId"], "geek-1")
        self.assertEqual(pool.listing(USER, {})["total"], 1)

    def test_retry_after_lost_create_response_does_not_duplicate_manual_record(self):
        body = {"name": "人工录入", "createId": "11d5b905-f3aa-42d3-9b47-044563508aeb"}
        one = pool.save(USER, body)
        two = pool.save(USER, body)
        self.assertEqual(one, two)
        self.assertEqual(pool.listing(USER, {})["total"], 1)
        with self.assertRaises(pool.Conflict):
            pool.save(USER, {**body, "name": "不能覆盖"})

    def test_invalid_fields_and_foreign_records_rejected(self):
        for body in [{"name": ""}, {"name": "a", "stage": "all"}, {"name": "a", "projectId": "missing"},
                     {"name": "a", "id": "missing"}, {"name": "a", "notes": "x"*5001}, []]:
            with self.assertRaises(ValueError):
                pool.save(USER, body)
        record = pool.save(USER, {"name": "<script>hello</script>", "cookie": "must-not-store"})
        self.assertNotIn("cookie", record)

    def test_access_is_limited_to_existing_hr_administrator(self):
        for changes in [{"username": "13900000000"}, {"roles": []},
                        {"organizationCode": "other"}, {"platformAdmin": False}]:
            with self.assertRaises(ValueError):
                pool.listing({**USER, **changes}, {})
            with self.assertRaises(ValueError):
                pool.save({**USER, **changes}, {"name": "a"})

    def test_projects_can_be_renamed_without_losing_membership(self):
        project = pool.save_project(USER, {"name": "原项目"})
        pool.save(USER, {"name": "李同学", "projectId": project["id"]})
        changed = pool.save_project(USER, {**project, "name": "新项目"})
        self.assertEqual(changed["version"], 2)
        self.assertEqual(pool.listing(USER, {"project": project["id"]})["total"], 1)
        with self.assertRaises(pool.Conflict):
            pool.save_project(USER, {"name": "新项目"})
        with self.assertRaises(pool.Conflict):
            pool.save_project(USER, {**project, "name": "旧的修改"})

    def test_pagination_is_bounded_and_stable(self):
        for number in range(25):
            pool.save(USER, {"name": str(number)})
        first = pool.listing(USER, {})
        last = pool.listing(USER, {"page": "9999"})
        self.assertEqual((len(first["items"]), len(last["items"]), last["page"]), (20, 5, 2))
        self.assertFalse({r["id"] for r in first["items"]} & {r["id"] for r in last["items"]})

    def test_http_works_without_desktop_and_never_executes_boss(self):
        class Handler(connector.HttpHandler):
            def authorized(self):
                if self.headers.get("Cookie") != "session=test":
                    return 401
                self.desktop_user = USER
                return 200
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{server.server_port}/api/v1/hr/pool"
        def request(path="", body=None, cookie=True, origin=connector.ALLOWED_ORIGIN, scope="pool"):
            headers = {"Content-Type": "application/json", "Origin": origin}
            if cookie:
                headers["Cookie"] = "session=test"
            req = urllib.request.Request(base.replace("/pool","/"+scope)+path, headers=headers,
                                         data=json.dumps(body).encode() if body is not None else None)
            try:
                with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=5) as response:
                    return response.status, json.load(response)
            except urllib.error.HTTPError as error:
                return error.code, json.load(error)
        with mock.patch.dict(connector.os.environ, {"APP_HR_DESKTOP_ONLY": "1"}), mock.patch.object(connector, "execute") as execute:
            self.assertEqual(request(cookie=False)[0], 401)
            self.assertEqual(request("/talents", {"name": "a"}, cookie=False)[0], 401)
            self.assertEqual(request("/talents", {"name": "a"}, origin="https://evil.example")[0], 403)
            self.assertEqual(request("/talents", {"name": "a", "stage": "invited"})[0], 200)
            self.assertEqual(request()[1]["data"]["total"], 1)
            self.assertEqual(request("/talents", [1, 2])[0], 400)
            self.assertEqual(request(scope="departments",cookie=False)[0],401)
            department=request("/save",{"name":"独立招聘部","code":"HR","manager":"经理","status":"ACTIVE"},scope="departments")
            self.assertEqual(department[0],200)
            self.assertEqual(request(scope="departments")[1]["data"]["items"][0]["manager"],"经理")
            self.assertEqual(request("/save",{"date":"2026-09-29","position":"运营","departmentId":department[1]["data"]["id"],
                "department":"伪造部门","manager":"伪造经理","headcount":1,"years":0},scope="demands")[0],200)
            demand=request(scope="demands")[1]["data"]["items"][0]
            self.assertEqual(demand["department"],"独立招聘部");self.assertEqual(demand["manager"],"经理")
            self.assertEqual(request("/generate",{"id":demand["id"],"version":demand["version"]},scope="demands")[0],400)
            execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
