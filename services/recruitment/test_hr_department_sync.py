import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import dingtalk_interviews as dt
import hr_departments as departments
import hr_dingtalk_directory as directory
import recruitment_demands
import talent_pool as pool
from test_talent_pool import USER


class DepartmentSyncTest(unittest.TestCase):
    def setUp(self):
        folder=tempfile.TemporaryDirectory();self.addCleanup(folder.cleanup)
        root=Path(folder.name)
        for patch in (mock.patch.object(pool,"DB",root/"hr.sqlite"),
                      mock.patch.object(dt,"KEY_FILE",root/"private"/"ding.key"),
                      mock.patch.object(dt,"token",return_value="test-token"),
                      mock.patch.object(dt,"launch")):
            patch.start();self.addCleanup(patch.stop)
        with pool.database() as db:
            dt.schema(db)
            db.execute("INSERT INTO hr_dingtalk_config VALUES(?,?,?,?,?,1)",("heyi","test-app","encrypted-secret","123",1))
        self.data={"state":"complete","error":"","configVersion":1,"appKey":"test-app","syncedAt":1,
            "departmentCount":4,"employeeCount":2,
            "departments":[{"id":"1","name":"公司直属人员","parentId":""},{"id":"2","name":"总经办","parentId":"1"},
                           {"id":"3","name":"运营","parentId":"1"},{"id":"4","name":"运营","parentId":"2"}],
            "items":[{"userId":"employee1","name":"经理","departmentIds":["2"]},
                     {"userId":"employee2","name":"组员","departmentIds":["3","4"]}]}

    def sync(self):
        with mock.patch.object(directory,"snapshot",return_value=copy.deepcopy(self.data)):
            return departments.synchronize(USER,{"configVersion":1})

    def by_source(self,source_id):
        with pool.database() as db:
            record=db.execute("SELECT department_id FROM hr_department_sources WHERE owner='heyi' AND source_id=?", (source_id,)).fetchone()[0]
        return next(d for d in departments.listing(USER)["items"] if d["id"]==record)

    def test_adopts_unique_legacy_id_preserves_binding_and_business_links_and_is_idempotent(self):
        legacy=departments.save(USER,{"name":"总经办","code":"HQ","manager":"经理","status":"ACTIVE"})
        talent=pool.save(USER,{"name":"合成人才","stage":"first","departmentId":legacy["id"]})
        with pool.database() as db:
            db.execute("INSERT INTO hr_department_dingtalk VALUES(?,?,?,?,?,?)",("heyi",legacy["id"],"employee1","经理","",1))
            recruitment_demands.schema(db)
            db.execute("""INSERT INTO recruitment_demands(id,owner,body,status,version,created_at,updated_at)
                VALUES('demand-test','heyi',?,'draft',1,'2026-10-09','2026-10-09')""", (json.dumps({"departmentId":legacy["id"]}),))
        self.sync()
        row=self.by_source("2")
        self.assertEqual(row["id"],legacy["id"]);self.assertEqual(row["code"],"HQ")
        self.assertTrue(row["dingtalk"]["valid"]);self.assertEqual(row["memberCount"],1)
        self.assertEqual(row["parentId"],self.by_source("1")["id"])
        versions={d["id"]:d["version"] for d in departments.listing(USER)["items"]}
        self.assertEqual(self.sync()["changed"],0)
        self.assertEqual(versions,{d["id"]:d["version"] for d in departments.listing(USER)["items"]})
        self.assertNotEqual(self.by_source("3")["name"],self.by_source("4")["name"])
        self.data["departments"][1]["name"]="行政"
        self.sync()
        self.assertEqual(self.by_source("2")["id"],legacy["id"])
        self.assertEqual(self.by_source("2")["name"],"行政")
        with pool.database() as db:
            self.assertEqual(json.loads(db.execute("SELECT body FROM talents WHERE id=?", (talent["id"],)).fetchone()[0])["departmentId"],legacy["id"])
            self.assertEqual(json.loads(db.execute("SELECT body FROM recruitment_demands WHERE id='demand-test'").fetchone()[0])["departmentId"],legacy["id"])

    def test_incomplete_failed_and_stale_snapshots_never_modify_departments(self):
        legacy=departments.save(USER,{"name":"历史","code":"OLD","status":"ACTIVE"})
        for state in ("running","failed"):
            self.data["state"]=state
            self.assertEqual(self.sync()["state"],state)
            self.assertEqual(departments.listing(USER)["items"][0]["version"],legacy["version"])
        self.data["state"]="complete";self.data["configVersion"]=2
        with self.assertRaises(pool.Conflict):self.sync()
        self.assertEqual(len(departments.listing(USER)["items"]),1)
        with self.assertRaises(ValueError):
            departments.synchronize(USER|{"organizationCode":"foreign"},{"configVersion":1})

    def test_absent_and_out_of_department_bindings_are_disabled_without_deleting_history(self):
        self.sync();record=self.by_source("2")["id"]
        with pool.database() as db:
            db.execute("INSERT INTO hr_department_dingtalk VALUES(?,?,?,?,?,?)",("heyi",record,"employee2","组员","",1))
            db.execute("UPDATE hr_departments SET manager='组员' WHERE id=?", (record,))
        self.sync();self.assertIsNone(self.by_source("2")["dingtalk"])
        self.data["departments"]=[d for d in self.data["departments"] if d["id"]!="2" and d["id"]!="4"]
        self.sync()
        row=next(d for d in departments.listing(USER)["items"] if d["id"]==record)
        self.assertEqual(row["status"],"DISABLED");self.assertFalse(row["synced"])

    def test_member_query_pins_the_server_side_department_not_client_filter(self):
        self.sync();record=self.by_source("2")["id"]
        with mock.patch.object(directory,"listing",return_value={"items":[]}) as listing:
            departments.members(USER,{"id":record,"configVersion":"1","departmentId":"4","q":"组员","page":"2"})
        self.assertEqual(listing.call_args.args[1]["departmentId"],"2")
        self.assertEqual(listing.call_args.args[1]["page"],"2")
        with self.assertRaises(ValueError):
            departments.members(USER,{"id":"foreign","configVersion":"1"})

    def test_member_identity_and_confirmation_recheck_current_membership_and_never_send(self):
        self.sync();row=self.by_source("2")
        body={"id":row["id"],"version":row["version"],"configVersion":1,"userId":"employee1","name":"不可信姓名"}
        def details(path,body,*args):
            self.assertEqual(path,"topapi/v2/user/get")
            return {"result":{"userid":body["userid"],"name":"经理","dept_id_list":[2]}}
        with mock.patch.object(dt,"api",side_effect=details):
            proof=dt.department_identity(USER,body)
            self.assertEqual(proof["name"],"经理")
            self.assertIsNone(self.by_source("2")["dingtalk"],"Selection alone does not bind")
            with self.assertRaises(ValueError):
                dt.bind_department(USER,{"id":self.by_source("3")["id"],"identityProof":proof["identityProof"]})
            dt.bind_department(USER,{"id":row["id"],"identityProof":proof["identityProof"]})
        self.assertTrue(self.by_source("2")["dingtalk"]["valid"])
        dt.launch.assert_not_called()
        row=self.by_source("2");body["version"]=row["version"]
        with mock.patch.object(dt,"api",side_effect=details):
            proof=dt.department_identity(USER,body)
        with mock.patch.object(dt,"api",return_value={"result":{"userid":"employee1","name":"经理","dept_id_list":[3]}}):
            with self.assertRaisesRegex(ValueError,"不属于"):
                dt.department_identity(USER,body)
            with self.assertRaisesRegex(ValueError,"不属于"):
                dt.bind_department(USER,{"id":row["id"],"identityProof":proof["identityProof"]})

    def test_department_rename_swaps_stay_unique_and_configuration_changes_do_not_mix_enterprises(self):
        self.sync()
        self.data["departments"][1]["name"],self.data["departments"][0]["name"]="公司直属人员","总经办"
        self.sync();self.assertEqual(self.sync()["changed"],0)
        original=self.by_source("2")["id"]
        with pool.database() as db:
            db.execute("UPDATE hr_dingtalk_config SET app_key='different-app',version=2")
        self.data["appKey"]="different-app";self.data["configVersion"]=2
        self.sync()
        self.assertNotEqual(self.by_source_for_app("2","different-app")["id"],original)
        old=next(d for d in departments.listing(USER)["items"] if d["id"]==original)
        self.assertEqual(old["status"],"DISABLED")

    def test_old_directory_snapshot_and_config_rotation_cannot_restore_previous_manager(self):
        self.sync();row=self.by_source("2")
        self.data["syncedAt"]=10;self.sync()
        self.data["syncedAt"]=1
        with self.assertRaises(pool.Conflict):self.sync()
        with pool.database() as db:
            db.execute("UPDATE hr_dingtalk_config SET version=2")
        with self.assertRaises(pool.Conflict):
            dt.department_identity(USER,{"id":row["id"],"version":row["version"],
                "configVersion":2,"mobile":"13900000001"})

    def by_source_for_app(self,source_id,app):
        with pool.database() as db:
            record=db.execute("SELECT department_id FROM hr_department_sources WHERE owner='heyi' AND source_id=? AND app_key=?", (source_id,app)).fetchone()[0]
        return next(d for d in departments.listing(USER)["items"] if d["id"]==record)


if __name__=="__main__":unittest.main()
