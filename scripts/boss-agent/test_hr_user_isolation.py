import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import desktop_bridge as bridge
import talent_pool as pool
import passive_pool as passive
import dingtalk_interviews as dt

ADMIN = {"id":"admin","username":"13800000000","organizationCode":"heyi",
         "platformAdmin":True,"roles":["ADMIN"]}
HR = {"id":"hr-a","username":"15800000001","organizationCode":"heyi",
      "platformAdmin":False,"roles":["HR_DEPARTMENT"],"permissions":["hr:access"]}


class HrUserIsolationTest(unittest.TestCase):
    def setUp(self):
        folder=tempfile.TemporaryDirectory();self.addCleanup(folder.cleanup)
        patch=mock.patch.object(pool,"DB",Path(folder.name)/"hr.sqlite3")
        patch.start();self.addCleanup(patch.stop)

    def test_permission_enables_hr_without_platform_administrator_access(self):
        self.assertEqual(bridge.identity(HR)["permissions"],["hr:access"])
        for user in (HR|{"permissions":[]},HR|{"permissions":[],"roles":["HR_DEPARTMENT","ADMIN"]},
                     HR|{"id":None}):
            with self.assertRaises(ValueError):bridge.identity(user)

    def test_two_hr_users_and_existing_administrator_have_distinct_data(self):
        other=HR|{"id":"hr-b","username":"15800000002"}
        for user,name in ((ADMIN,"管理员资料"),(HR,"甲的资料"),(other,"乙的资料")):
            pool.save(user,{"name":name,"stage":"invited"})
        self.assertEqual(pool.context(ADMIN)[0],"heyi")
        self.assertNotEqual(pool.context(HR)[0],pool.context(other)[0])
        self.assertEqual([r["name"] for r in pool.listing(HR,{})["items"]],["甲的资料"])
        self.assertEqual([r["name"] for r in pool.listing(other,{})["items"]],["乙的资料"])
        self.assertEqual([r["name"] for r in pool.listing(ADMIN,{})["items"]],["管理员资料"])

    def test_speeches_resume_download_and_dingtalk_settings_are_private(self):
        saved=passive.save_speech(HR,{"name":"甲的话术","kind":"reject","content":"感谢关注","isDefault":True})
        other=HR|{"id":"hr-b","username":"15800000002"}
        self.assertEqual(passive.listing(other,"speeches",{})["items"],[])
        with self.assertRaises(ValueError):passive.save_speech(other,saved|{"content":"越权修改"})
        owner,_=pool.context(HR)
        with pool.database() as db:
            passive.schema(db);dt.schema(db)
            db.execute("""INSERT INTO hr_resume_pool(id,owner,account,friend_id,rule_id,message_id,body,
                file_key,file_hash,created_at,updated_at) VALUES('r1',? ,?,'123','rule1','456',?,'key','hash',?,?)""",
                (owner,"a"*64,json.dumps({"name":"候选人","jobName":"运营","nameExtractionVersion":4}),passive.now(),passive.now()))
            db.execute("INSERT INTO hr_dingtalk_config VALUES(?,'app','encrypted','123',1,1)",(owner,))
        self.assertEqual(passive.listing(other,"resume-pool",{})["total"],0)
        with mock.patch("resume_storage.download") as download:
            with self.assertRaises(ValueError):passive.get_file(other,"r1")
            download.assert_not_called()
        self.assertFalse(dt.settings(other)["config"]["verified"])
