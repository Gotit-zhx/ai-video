import tempfile
import datetime
import hashlib
import io
import json
import unittest
import uuid
from pathlib import Path
from unittest import mock
import talent_pool as pool
import recruitment_demands as demands
USER = {"id": "admin-1", "username": "13800000000", "organizationCode": "heyi",
        "platformAdmin": True, "roles": ["ADMIN"]}


class DemandsTest(unittest.TestCase):
    def test_stream_progress_is_persisted_job_scoped_and_never_completes_without_result(self):
        row = demands.save(USER, self.body)
        with mock.patch.object(demands.threading, "Thread"):
            running = demands.generate(USER, {"id": row["id"], "version": row["version"]}, "fixture")
        job = running["aiJob"]
        demands.update_progress(USER, row["id"], "wrong-job", {"phase": "generating", "receivedChars": 99})
        self.assertEqual(demands.listing(USER, {"id": row["id"]})["item"]["aiProgress"]["phase"], "connecting")
        demands.update_progress(USER, row["id"], job, {"phase": "generating", "receivedChars": 100})
        demands.update_progress(USER, row["id"], job, {"phase": "connecting", "receivedChars": 0})
        self.assertEqual(demands.listing(USER, {"id": row["id"]})["item"]["aiProgress"]["receivedChars"], 100)
        result = {"portrait": {"summary": "fixture"}, "competencies": [{}], "interviewQuestions": [{}]}
        stream = io.BytesIO(("event: progress\ndata: {\"phase\":\"validating\",\"receivedChars\":100}\n\n"
                            "event: result\ndata: " + json.dumps({"data": result}) + "\n\n").encode())
        self.assertEqual(demands.read_driver_events(stream, lambda value: demands.update_progress(USER, row["id"], job, value)), result)
        self.assertEqual(demands.listing(USER, {"id": row["id"]})["item"]["aiStatus"], "running")
        demands.finish(USER, row["id"], job, result=result)
        demands.update_progress(USER, row["id"], job, {"phase": "generating", "receivedChars": 200})
        self.assertEqual(demands.listing(USER, {"id": row["id"]})["item"]["aiProgress"]["phase"], "complete")
        with self.assertRaisesRegex(ValueError, "响应中断"):
            demands.read_driver_events(io.BytesIO(b'event: progress\ndata: {"phase":"generating"}\n\n'), lambda _: None)
        with self.assertRaisesRegex(ValueError, "未返回有效"):
            demands.read_driver_events(io.BytesIO(b'event: error\ndata: {"message":"secret-key","status":502}\n\n'), lambda _: None)

    def test_optional_references_are_offline_deduplicated_versioned_and_added_only_when_present(self):
        import base64
        import resume_storage as storage
        row = demands.save(USER, self.body)
        self.assertEqual(row["references"], [])
        self.assertNotIn("referenceResumes", demands.driver_input(self.body))
        with mock.patch.object(storage, "extract", return_value=("实际项目交付与跨团队协调经历。" * 5, "正文")), \
                mock.patch.object(storage, "store", return_value="private/file") as store, \
                mock.patch.object(demands, "call_driver") as driver:
            upload = {"id": row["id"], "version": row["version"], "filename": "员工.pdf",
                      "fileBase64": base64.b64encode(b"%PDF-1.4 fixture").decode()}
            row = demands.references(USER, upload)
            self.assertEqual(row["version"], 2)
            self.assertEqual(len(row["references"]), 1)
            self.assertNotIn("body", row["references"][0])
            duplicate = demands.references(USER, {**upload, "version": 2})
            self.assertEqual(duplicate["version"], 2)
            self.assertEqual(store.call_count, 1)
            driver.assert_not_called()
            with self.assertRaises(pool.Conflict): demands.references(USER, upload)
        with mock.patch.object(demands.threading, "Thread") as worker:
            demands.generate(USER, {"id": row["id"], "version": 2}, "fixture")
            snapshot = worker.call_args.kwargs["args"][3]
            self.assertIn("实际项目交付", demands.driver_input(snapshot)["referenceResumes"][0])
        with self.assertRaises(pool.Conflict):
            demands.references(USER, {"id": row["id"], "version": 2, "referenceId": row["references"][0]["id"]}, remove=True)

    def setUp(self):
        folder=tempfile.TemporaryDirectory();self.addCleanup(folder.cleanup)
        patch=mock.patch.object(pool,"DB",Path(folder.name)/"pool.sqlite3");patch.start();self.addCleanup(patch.stop)
        self.body={"createId":str(uuid.uuid4()),"date":"2026-09-29","position":"运营","department":"电商",
                   "headcount":2,"years":3,"responsibilities":"店铺运营","requirements":"本科和相关经验",
                   "jobId":"job-1","jobAccountKey":"a"*64,"jobDescription":"负责内容运营，要求有直播项目经验。",
                   "jobFetchedAt":datetime.datetime.now(datetime.timezone.utc).isoformat()}

    def test_delete_is_versioned_owner_scoped_idempotent_and_preserves_audit(self):
        row = demands.save(USER, self.body)
        other = demands.save(USER, {**self.body, "createId": str(uuid.uuid4()), "jobId": "job-2"})
        body = {"id": row["id"], "version": row["version"], "requestId": str(uuid.uuid4())}
        with self.assertRaises(ValueError):
            demands.delete({**USER, "organizationCode": "another-org"}, body)
        with self.assertRaises(pool.Conflict):
            demands.delete(USER, {**body, "version": row["version"]+1})
        with pool.database() as db:
            db.execute("INSERT INTO hr_demand_references VALUES(?,?,?,?,?,?,?,?)",
                       ("ref1", "heyi", row["id"], "digest", "fixture.pdf", '{"text":"fixture"}', "shared-key", "now"))
        result = demands.delete(USER, body)
        self.assertEqual(result, {"id": row["id"], "deleted": True})
        self.assertEqual(demands.delete(USER, body), result)
        self.assertEqual(demands.listing(USER, {})["items"][0]["id"], other["id"])
        with self.assertRaises(ValueError):
            demands.listing(USER, {"id": row["id"]})
        with self.assertRaises(pool.Conflict):
            demands.delete(USER, {**body, "id": other["id"]})
        with self.assertRaises(pool.Conflict):
            demands.save(USER, self.body)  # Replaying creation cannot resurrect a deleted demand.
        with pool.database() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM hr_demand_references").fetchone()[0], 0)
            events = db.execute("SELECT before_json FROM talent_events WHERE action='demand.delete'").fetchall()
            self.assertEqual(len(events), 1)
            self.assertEqual(json.loads(events[0][0])["jobId"], row["jobId"])

    def test_running_ai_cannot_be_deleted_and_invalid_request_has_no_effect(self):
        row = demands.save(USER, self.body)
        body = {"id": row["id"], "version": row["version"], "requestId": str(uuid.uuid4())}
        for invalid in ({**body, "requestId": "bad"}, {**body, "version": True}):
            with self.assertRaises(ValueError):
                demands.delete(USER, invalid)
        with mock.patch.object(demands.threading, "Thread"):
            demands.generate(USER, {"id": row["id"], "version": row["version"]}, "fixture")
        with self.assertRaises(pool.Conflict):
            demands.delete(USER, body)
        self.assertEqual(demands.listing(USER, {})["total"], 1)

    def test_delete_blocks_unconfirmed_handoff_and_retains_delivered_snapshot(self):
        row = demands.save(USER, self.body)
        body = {"id": row["id"], "version": row["version"], "requestId": str(uuid.uuid4())}
        snapshot = json.dumps({"demandId": row["id"], "questions": ["fixture"]})
        with pool.database() as db:
            db.execute("CREATE TABLE hr_interview_handoffs(owner TEXT,body TEXT,status TEXT)")
            db.execute("INSERT INTO hr_interview_handoffs VALUES(?,?,?)", ("heyi", snapshot, "uncertain"))
        with self.assertRaises(pool.Conflict):
            demands.delete(USER, body)
        with pool.database() as db:
            db.execute("UPDATE hr_interview_handoffs SET status='sent'")
        demands.delete(USER, body)
        with pool.database() as db:
            self.assertEqual(db.execute("SELECT body FROM hr_interview_handoffs").fetchone()[0], snapshot)

    def test_active_sourcing_using_demand_cannot_be_deleted(self):
        row = demands.save(USER, self.body)
        with pool.database() as db:
            db.execute("CREATE TABLE hr_sourcing_jobs(id TEXT,owner TEXT,demand_source TEXT)")
            db.execute("CREATE TABLE hr_sourcing_runs(job TEXT,owner TEXT,state TEXT)")
            db.execute("INSERT INTO hr_sourcing_jobs VALUES(?,?,?)", ("job", "heyi", json.dumps({"id": row["id"]})))
            db.execute("INSERT INTO hr_sourcing_runs VALUES(?,?,?)", ("job", "heyi", "running"))
        with self.assertRaises(pool.Conflict):
            demands.delete(USER, {"id": row["id"], "version": row["version"], "requestId": str(uuid.uuid4())})

    def test_specific_position_and_full_jd_are_required_without_ai_side_effects(self):
        for fields in [{"jobId":""},{"jobAccountKey":""},{"jobDescription":""},{"jobFetchedAt":"bad"},
                       {"jobId":"../../job"},{"jobDescription":"a"*8001}]:
            with self.subTest(fields=list(fields)), mock.patch.object(demands,"call_driver") as driver:
                with self.assertRaises(ValueError):demands.save(USER,{**self.body,**fields})
                driver.assert_not_called()
        row=demands.save(USER,self.body)
        second=demands.save(USER,{**self.body,"createId":str(uuid.uuid4()),"jobId":"job-2"})
        self.assertEqual(row["position"],second["position"])
        self.assertNotEqual(row["jobId"],second["jobId"])
        self.assertEqual(row["jobDescriptionHash"],hashlib.sha256(self.body["jobDescription"].encode()).hexdigest())

    def test_driver_receives_original_jd_and_hr_supplements_on_existing_contract(self):
        values=demands.driver_input(self.body)
        self.assertIn(self.body["jobDescription"],values["responsibilities"])
        self.assertIn(self.body["responsibilities"],values["responsibilities"])
        self.assertEqual(values["requirements"],self.body["requirements"])
        self.assertEqual(set(values),set(demands.DRIVER_FIELDS))
        self.assertNotIn("jobAccountKey",values)
        with mock.patch.object(demands.urllib.request,"build_opener") as build:
            build.return_value.open.return_value.__enter__.return_value.headers = {"Content-Type": "application/json"}
            build.return_value.open.return_value.__enter__.return_value.read.return_value=json.dumps({
                "data":{"portrait":{"summary":"fixture"},"competencies":[{}],"interviewQuestions":[{}]}}).encode()
            demands.call_driver(demands.save(USER,self.body),"fixture")
            wire=json.loads(build.return_value.open.call_args.args[0].data)
            self.assertEqual(wire,values)

    def test_stale_or_missing_jd_blocks_ai_before_a_billable_job(self):
        row=demands.save(USER,self.body)
        for fields in [{"jobFetchedAt":"2020-01-01T00:00:00+00:00"},{"jobDescription":""},{"jobId":""},
                       {"jobFetchedAt":"2099-01-01T00:00:00+00:00"},
                       {"responsibilities":"a"*10000}]:
            with pool.database() as db:
                db.execute("UPDATE recruitment_demands SET body=? WHERE id=?",
                           (json.dumps({**self.body,**fields}),row["id"]))
            with self.subTest(fields=list(fields)), mock.patch.object(demands.threading,"Thread") as thread:
                with self.assertRaises(ValueError):demands.generate(USER,{"id":row["id"],"version":1},"fixture")
                thread.assert_not_called()
                self.assertEqual(demands.listing(USER,{"id":row["id"]})["item"]["aiStatus"],"idle")

    def test_driver_error_reports_only_safe_controller_diagnostics_without_retry(self):
        for message, expected in [
            ("驱动模型服务返回错误（HTTP 500），本次未自动重复调用", "HTTP 500"),
            ("驱动模型响应中断，请手动重试", "响应中断"),
            ("驱动模型服务返回错误（HTTP 502），本次未自动重复调用 [HR_AI_UPSTREAM_HTTP]", "HTTP 502"),
            ("secret-provider-key", "未返回有效"),
        ]:
            failure=demands.urllib.error.HTTPError("http://private",502,"failure",{},
                io.BytesIO(json.dumps({"message":message}).encode()))
            with mock.patch.object(demands.urllib.request,"build_opener") as opener:
                opener.return_value.open.side_effect=failure
                with self.assertRaisesRegex(ValueError,expected) as raised:
                    demands.call_driver(demands.save(USER,{**self.body,"createId":str(uuid.uuid4())}),"private-cookie")
                opener.return_value.open.assert_called_once()
                self.assertNotIn("secret-provider-key",str(raised.exception))

    def test_fields_filters_updates_and_create_retry(self):
        row=demands.save(USER,self.body)
        self.assertEqual(demands.save(USER,self.body)["id"],row["id"])
        self.assertEqual(demands.listing(USER,{})["total"],1)
        changed=demands.save(USER,{**row,"status":"recruiting","manager":"经理"})
        self.assertEqual(demands.listing(USER,{"status":"recruiting","q":"经理"})["total"],1)
        self.assertEqual(demands.listing(USER,{"id":row["id"]})["item"]["version"],2)
        with self.assertRaises(pool.Conflict):demands.save(USER,row)
        self.assertEqual(changed["manager"],"经理")

    def test_invalid_fields_access_and_unknown_records(self):
        for fields in [{"date":"2026-02-30"},{"headcount":0},{"headcount":1.5},{"years":"nan"},
                       {"position":""},{"status":"unknown"}]:
            with self.assertRaises(ValueError):demands.save(USER,{**self.body,**fields})
        with self.assertRaises(ValueError):demands.listing({**USER,"platformAdmin":False},{})
        with self.assertRaises(ValueError):demands.generate(USER,{"id":"missing"},"cookie")

    def test_generation_is_single_claim_no_duplicate_spend_and_preserves_result(self):
        row=demands.save(USER,self.body)
        with mock.patch.object(demands.threading,"Thread") as thread:
            running=demands.generate(USER,{"id":row["id"],"version":row["version"]},"session=secret")
            duplicate=demands.generate(USER,{"id":row["id"],"version":row["version"]},"session=secret")
            self.assertEqual(running["aiJob"],duplicate["aiJob"]);thread.return_value.start.assert_called_once()
            with self.assertRaises(pool.Conflict):demands.save(USER,row)
        demands.finish(USER,row["id"],running["aiJob"],result={"portrait":{"summary":"真实结果"},"modelId":"configured-driver"})
        complete=demands.listing(USER,{"id":row["id"]})["item"]
        self.assertEqual(complete["aiStatus"],"complete")
        self.assertEqual(complete["aiResult"]["sourceVersion"],1)
        self.assertEqual(complete["aiResult"]["sourceJobId"],"job-1")
        self.assertEqual(complete["aiResult"]["sourceJobAccountKey"],"a"*64)
        self.assertEqual(complete["aiResult"]["sourceJobDescriptionHash"],row["jobDescriptionHash"])
        with mock.patch.object(demands.threading,"Thread") as thread:
            cached=demands.generate(USER,{"id":row["id"],"version":1},"cookie")
            self.assertEqual(cached["aiStatus"],"complete");thread.assert_not_called()
        updated=demands.save(USER,{**complete,"requirements":"更新任职要求"})
        self.assertEqual(updated["aiStatus"],"stale")
        self.assertEqual(updated["aiResult"]["portrait"]["summary"],"真实结果")
        with pool.database() as db:
            audit=str([tuple(r) for r in db.execute("SELECT * FROM talent_events")])
            self.assertNotIn("session=secret",audit)

    def test_restart_and_expiry_are_terminal_without_automatic_model_retries(self):
        row=demands.save(USER,self.body)
        with mock.patch.object(demands.threading,"Thread"):
            running=demands.generate(USER,{"id":row["id"],"version":1},"cookie")
        demands.recover_interrupted()
        demands.finish(USER,row["id"],running["aiJob"],result={"should":"be ignored"})
        result=demands.listing(USER,{"id":row["id"]})["item"]
        self.assertEqual(result["aiStatus"],"failed");self.assertEqual(result["aiResult"],{})
        with mock.patch.object(demands.threading,"Thread"):
            demands.generate(USER,{"id":row["id"],"version":1},"cookie")
        with pool.database() as db:db.execute("UPDATE recruitment_demands SET ai_started=0")
        self.assertEqual(demands.listing(USER,{"id":row["id"]})["item"]["aiStatus"],"failed")

    def test_worker_failure_is_saved_without_retrying_or_leaking(self):
        row=demands.save(USER,self.body)
        with mock.patch.object(demands.threading,"Thread"):
            running=demands.generate(USER,{"id":row["id"],"version":1},"cookie")
        with mock.patch.object(demands,"call_driver",side_effect=RuntimeError("private-key")) as driver:
            demands.worker(USER,row["id"],running["aiJob"],row,"cookie")
            driver.assert_called_once()
        result=demands.listing(USER,{"id":row["id"]})["item"]
        self.assertEqual(result["aiStatus"],"failed");self.assertNotIn("private-key",result["aiError"])

    def test_departments_are_validated_and_manager_is_resolved_by_directory(self):
        directory=[{"id":"dept-1","name":"统一部门","manager":"真实经理","status":"ACTIVE"}]
        with self.assertRaises(ValueError):demands.save(USER,self.body,directory)
        record=demands.save(USER,{**self.body,"departmentId":"dept-1","manager":"伪造经理"},directory)
        self.assertEqual(record["department"],"统一部门");self.assertEqual(record["manager"],"真实经理")
        directory[0]["status"]="DISABLED"
        with self.assertRaises(ValueError):
            demands.save(USER,{**self.body,"createId":str(uuid.uuid4()),"departmentId":"dept-1"},directory)
        updated=demands.save(USER,{**record,"status":"filled"},directory)
        self.assertEqual(updated["status"],"filled")


if __name__=="__main__":unittest.main()
