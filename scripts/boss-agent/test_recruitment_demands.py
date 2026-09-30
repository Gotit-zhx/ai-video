import tempfile
import datetime
import hashlib
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
    def setUp(self):
        folder=tempfile.TemporaryDirectory();self.addCleanup(folder.cleanup)
        patch=mock.patch.object(pool,"DB",Path(folder.name)/"pool.sqlite3");patch.start();self.addCleanup(patch.stop)
        self.body={"createId":str(uuid.uuid4()),"date":"2026-09-29","position":"运营","department":"电商",
                   "headcount":2,"years":3,"responsibilities":"店铺运营","requirements":"本科和相关经验",
                   "jobId":"job-1","jobAccountKey":"a"*64,"jobDescription":"负责内容运营，要求有直播项目经验。",
                   "jobFetchedAt":datetime.datetime.now(datetime.timezone.utc).isoformat()}

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
