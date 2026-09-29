import tempfile
import unittest
import uuid
from pathlib import Path
from unittest import mock
import talent_pool as pool
import recruitment_demands as demands
from test_talent_pool import USER


class DemandsTest(unittest.TestCase):
    def setUp(self):
        folder=tempfile.TemporaryDirectory();self.addCleanup(folder.cleanup)
        patch=mock.patch.object(pool,"DB",Path(folder.name)/"pool.sqlite3");patch.start();self.addCleanup(patch.stop)
        self.body={"createId":str(uuid.uuid4()),"date":"2026-09-29","position":"运营","department":"电商",
                   "headcount":2,"years":3,"responsibilities":"店铺运营","requirements":"本科和相关经验"}

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
