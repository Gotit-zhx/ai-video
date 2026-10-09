import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid
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

    def review_body(self, talent, outcome="passed", round="first"):
        return {"talentId":talent["id"],"version":talent["version"],"round":round,
                "outcome":outcome,"opinion":"具备项目经验，沟通清晰。","requestId":str(uuid.uuid4())}

    def test_first_approval_advances_atomically_and_keeps_first_round_history(self):
        group=pool.save_project(USER,{"name":"研发"})
        talent=pool.save(USER,{"name":"王同学","stage":"first","projectId":group["id"]})
        result=pool.review_interview(USER,self.review_body(talent))
        self.assertEqual(result["stage"],"second")
        self.assertEqual(result["version"],2)
        self.assertEqual(result["interviewReviews"]["first"]["outcome"],"passed")
        history=pool.listing(USER,{"stage":"first","outcome":"passed","project":group["id"],"q":"王"})
        self.assertEqual([item["id"] for item in history["items"]],[talent["id"]])
        self.assertEqual(history["counts"]["all"],1)
        self.assertEqual(history["counts"]["first"],1)
        self.assertEqual(history["counts"]["second"],1)
        self.assertEqual(history["outcomeCounts"],{"all":1,"pending":0,"passed":1,"failed":0})
        self.assertEqual(pool.listing(USER,{"stage":"second","outcome":"pending"})["total"],1)
        with pool.database() as db:
            audit=db.execute("SELECT before_json,after_json FROM talent_events WHERE action='interview.review'").fetchone()
        self.assertEqual(json.loads(audit["before_json"])["interviewReviews"],{})
        self.assertEqual(json.loads(audit["after_json"])["stage"],"second")

    def test_first_rejection_stays_in_first_and_second_decisions_do_not_automatically_hire(self):
        first=pool.save(USER,{"name":"一面候选人","stage":"first"})
        rejected=pool.review_interview(USER,self.review_body(first,"failed"))
        self.assertEqual(rejected["stage"],"first")
        self.assertEqual(pool.listing(USER,{"stage":"first","outcome":"failed"})["total"],1)
        self.assertEqual(pool.listing(USER,{"stage":"second"})["total"],0)
        for decision in ("passed","failed"):
            second=pool.save(USER,{"name":decision,"stage":"second"})
            result=pool.review_interview(USER,self.review_body(second,decision,"second"))
            self.assertEqual(result["stage"],"second")
            self.assertEqual(pool.listing(USER,{"stage":"second","outcome":decision})["total"],1)
        self.assertEqual(pool.listing(USER,{"stage":"hired"})["total"],0)

    def test_review_duplicate_response_loss_and_stale_edit_do_not_overwrite_a_decision(self):
        talent=pool.save(USER,{"name":"候选人","stage":"first"})
        body=self.review_body(talent)
        result=pool.review_interview(USER,body)
        self.assertEqual(pool.review_interview(USER,body),result)
        for changes in ({"requestId":str(uuid.uuid4())},{"outcome":"failed"},{"opinion":"修改意见"}):
            with self.assertRaises(pool.Conflict):pool.review_interview(USER,body|changes)
        with pool.database() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM hr_interview_reviews").fetchone()[0],1)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM talent_events WHERE action='interview.review'").fetchone()[0],1)
        changed=pool.save(USER,{**result,"company":"新公司"})
        self.assertEqual(changed["interviewReviews"],result["interviewReviews"])
        self.assertEqual(pool.listing(USER,{})["items"][0]["interviewReviews"],result["interviewReviews"])
        with self.assertRaises(pool.Conflict):
            pool.review_interview(USER,self.review_body(result,"passed","second"))

    def test_review_rejects_empty_opinion_wrong_round_and_cross_organization(self):
        talent=pool.save(USER,{"name":"候选人","stage":"first"})
        body=self.review_body(talent)
        for changes in ({"opinion":"   "},{"opinion":"a"*5001},{"round":"invited"},{"outcome":"other"},
                        {"requestId":"bad"},{"talentId":"missing"}):
            with self.assertRaises(ValueError):pool.review_interview(USER,body|changes)
        with self.assertRaises(pool.Conflict):pool.review_interview(USER,body|{"round":"second"})
        with self.assertRaises(ValueError):
            pool.review_interview(USER|{"organizationCode":"other"},body)
        with self.assertRaises(ValueError):pool.listing(USER,{"stage":"all","outcome":"passed"})
        self.assertEqual(pool.listing(USER,{"stage":"first","outcome":"pending"})["total"],1)

    def test_passed_first_round_history_preserves_pagination(self):
        for number in range(21):
            talent=pool.save(USER,{"name":f"通过候选人{number}","stage":"first"})
            pool.review_interview(USER,self.review_body(talent))
        pending=pool.save(USER,{"name":"待面试","stage":"first"})
        one=pool.listing(USER,{"stage":"first","outcome":"passed"})
        two=pool.listing(USER,{"stage":"first","outcome":"passed","page":"2"})
        self.assertEqual((len(one["items"]),len(two["items"]),two["total"],two["pages"]),(20,1,21,2))
        self.assertFalse({row["id"] for row in one["items"]}&{row["id"] for row in two["items"]})
        self.assertEqual(pool.listing(USER,{"stage":"first","outcome":"pending"})["items"][0]["id"],pending["id"])
        self.assertEqual(pool.listing(USER,{})["total"],22)

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
            self.assertEqual(request("/dingtalk", cookie=False)[0], 401)
            self.assertEqual(request("/interview?talentId=missing", cookie=False)[0], 401)
            self.assertEqual(request("/interview/start", {}, cookie=False)[0], 401)
            self.assertEqual(request("/dingtalk/save", {}, origin="https://evil.example")[0], 403)
            self.assertEqual(request("/dingtalk/recipient", {}, origin="https://evil.example")[0], 403)
            self.assertEqual(request("/dingtalk")[1]["data"]["config"]["verified"], False)
            self.assertEqual(request("/interview/start", {})[0], 400)
            self.assertEqual(request("/interview/review", {}, cookie=False)[0],401)
            self.assertEqual(request("/interview/review", {}, origin="https://evil.example")[0],403)
            self.assertEqual(request("/interview/review", {})[0],400)
            self.assertEqual(request("/dingtalk/save", {})[0], 400)
            self.assertEqual(request("/dingtalk/recipient", {})[0], 400)
            self.assertEqual(request(scope="departments",cookie=False)[0],401)
            for endpoint in ("/identity", "/bind"):
                self.assertEqual(request(endpoint, {}, scope="departments", cookie=False)[0],401)
                self.assertEqual(request(endpoint, {}, scope="departments", origin="https://evil.example")[0],403)
            with mock.patch("dingtalk_interviews.department_identity", return_value={"name":"识别负责人"}) as identify, \
                 mock.patch("dingtalk_interviews.bind_department", return_value={"name":"绑定负责人"}) as bind:
                self.assertEqual(request("/identity", {"id":"department"}, scope="departments")[1]["data"]["name"],"识别负责人")
                self.assertEqual(request("/bind", {"id":"department"}, scope="departments")[1]["data"]["name"],"绑定负责人")
                identify.assert_called_once_with(USER, {"id":"department"})
                bind.assert_called_once_with(USER, {"id":"department"})
            department=request("/save",{"name":"独立招聘部","code":"HR","manager":"经理","status":"ACTIVE"},scope="departments")
            self.assertEqual(department[0],200)
            self.assertEqual(request(scope="departments")[1]["data"]["items"][0]["manager"],"经理")
            self.assertEqual(request("/save",{"date":"2026-09-29","position":"运营","departmentId":department[1]["data"]["id"],
                "department":"伪造部门","manager":"伪造经理","headcount":1,"years":0,
                "jobId":"job1","jobAccountKey":"a"*64,"jobDescription":"原始岗位职责",
                "jobFetchedAt":"2020-01-01T00:00:00Z"},scope="demands")[0],200)
            demand=request(scope="demands")[1]["data"]["items"][0]
            self.assertEqual(demand["department"],"独立招聘部");self.assertEqual(demand["manager"],"经理")
            self.assertEqual(request("/generate",{"id":demand["id"],"version":demand["version"]},scope="demands")[0],400)
            execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
