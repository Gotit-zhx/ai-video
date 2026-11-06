import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import uuid

import talent_pool as pool
import hr_onboarding as onboard
import dingtalk_interviews as ding

USER={"id":"hr","username":"13800000000","organizationCode":"heyi","platformAdmin":True,"roles":["ADMIN"]}
ARRANGEMENT={"startDate":"2026-10-20","position":"算法工程师","supervisor":"主管","department":"研发",
             "company":"示例公司","fixedSalary":"22000","performanceSalary":"3000","commission":"无",
             "mentorName":"导师","joined":False}


class OnboardingTest(unittest.TestCase):
    def setUp(self):
        folder=tempfile.TemporaryDirectory();self.addCleanup(folder.cleanup)
        self.root=Path(folder.name)
        for patch in (mock.patch.object(pool,"DB",self.root/"pool.db"),
                      mock.patch.object(ding,"KEY_FILE",self.root/"private/key")):
            patch.start();self.addCleanup(patch.stop)
        self.talent=pool.save(USER,{"name":"候选人","stage":"second","position":"算法工程师"})
        with pool.database() as db:
            onboard.schema(db)
            db.execute("INSERT INTO hr_dingtalk_config VALUES('heyi','app','encrypted','123',1,1)")
            db.execute("INSERT INTO hr_departments VALUES('dept','heyi','研发','DEV','','导师','ACTIVE',1)")
            db.execute("INSERT INTO hr_department_dingtalk VALUES('heyi','dept','mentor-id','导师','138****0001',1)")

    def review(self, arrangement=ARRANGEMENT):
        return {"talentId":self.talent["id"],"version":1,"round":"second","opinion":"能力符合岗位要求",
                "outcome":"passed","requestId":str(uuid.uuid4()),"onboarding":dict(arrangement)}

    def enrolled(self,joined=False):
        result=pool.review_interview(USER,self.review(ARRANGEMENT|{"joined":joined}))
        rows=onboard.listing(USER,{"status":"all"})["items"]
        return result,rows[0]

    def send_body(self,row):
        return {"talentId":row["talentId"],"version":row["version"],"requestId":str(uuid.uuid4()),
                "configVersion":1,"recipientId":"mentor-id","bindingKey":"department:dept:1"}

    def test_second_approval_atomically_creates_arrangement_and_keeps_history(self):
        body=self.review()
        result=pool.review_interview(USER,body)
        self.assertEqual(result["stage"],"onboarding")
        self.assertEqual(pool.review_interview(USER,body)["stage"],"onboarding")
        with self.assertRaises(pool.Conflict):
            pool.review_interview(USER,body|{"onboarding":ARRANGEMENT|{"company":"另一个公司"}})
        rows=onboard.listing(USER,{"status":"pending"})["items"]
        self.assertEqual(len(rows),1);self.assertEqual(rows[0]["fixedSalary"],"22000")
        self.assertEqual(pool.listing(USER,{"stage":"second","outcome":"passed"})["total"],1)
        self.assertEqual(pool.listing(USER,{})["pendingCounts"]["second"],0)

    def test_missing_or_invalid_arrangement_cannot_save_partial_interview_result(self):
        for changes in ({"company":""},{"fixedSalary":"NaN"},{"performanceSalary":"-1"},{"fixedSalary":"1.001"},
                        {"startDate":"2026-02-30"},{"joined":"false"},{"mentorName":""}):
            with self.subTest(changes=changes):
                with self.assertRaises(ValueError):
                    pool.review_interview(USER,self.review(ARRANGEMENT|changes))
        body=self.review();body.pop("onboarding")
        with self.assertRaises(ValueError):pool.review_interview(USER,body)
        with pool.database() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM hr_interview_reviews").fetchone()[0],0)
            self.assertEqual(db.execute("SELECT stage FROM talents").fetchone()[0],"second")

    def test_confirming_joined_updates_stage_but_does_not_send(self):
        _,row=self.enrolled()
        with mock.patch.object(onboard.threading,"Thread") as thread:
            onboard.save(USER,row|{"joined":True})
            thread.assert_not_called()
        self.assertEqual(pool.listing(USER,{"stage":"hired"})["total"],1)
        with self.assertRaises(pool.Conflict):onboard.save(USER,row|{"joined":True})

    def test_initial_joined_flag_goes_to_hired_with_saved_arrangement(self):
        result,row=self.enrolled(True)
        self.assertEqual(result["stage"],"hired");self.assertTrue(row["joined"])

    def test_departure_is_scoped_versioned_idempotent_and_has_no_notifications(self):
        _,row=self.enrolled(True)
        body={"talentId":row["talentId"],"version":row["version"],"requestId":str(uuid.uuid4())}
        with self.assertRaises(ValueError):onboard.depart(USER|{"organizationCode":"other"},body)
        with self.assertRaises(pool.Conflict):onboard.depart(USER,body|{"version":9})
        with mock.patch.object(onboard.threading,"Thread") as thread,mock.patch.object(ding,"api") as api:
            self.assertTrue(onboard.depart(USER,body)["departed"])
            self.assertTrue(onboard.depart(USER,body)["departed"])
            thread.assert_not_called();api.assert_not_called()
        self.assertEqual(onboard.listing(USER,{"status":"joined"})["total"],0)
        departed=onboard.listing(USER,{"status":"departed"})["items"][0]
        self.assertTrue(departed["joined"]);self.assertTrue(departed["departed"]);self.assertTrue(departed["departedAt"])
        self.assertEqual(onboard.listing(USER,{})["total"],1)
        self.assertEqual(pool.listing(USER,{"stage":"departed"})["total"],1)
        onboard.save(USER,departed|{"company":"资料更新"})
        self.assertEqual(onboard.listing(USER,{"status":"departed"})["total"],1)
        updated=onboard.listing(USER,{"status":"departed"})["items"][0]
        with self.assertRaisesRegex(ValueError,"已离职"):onboard.send(USER,self.send_body(updated))
        with self.assertRaises(pool.Conflict):onboard.save(USER,updated|{"joined":False})
        with pool.database() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM talent_events WHERE action='onboarding.depart'").fetchone()[0],1)

    def test_unjoined_employee_and_ambiguous_notice_cannot_be_marked_departed(self):
        _,row=self.enrolled()
        body={"talentId":row["talentId"],"version":row["version"],"requestId":str(uuid.uuid4())}
        with self.assertRaisesRegex(ValueError,"尚未入职"):onboard.depart(USER,body)
        onboard.save(USER,row|{"joined":True})
        row=onboard.listing(USER,{"status":"joined"})["items"][0]
        with mock.patch.object(onboard.threading,"Thread"):notice=onboard.send(USER,self.send_body(row))
        with pool.database() as db:db.execute("UPDATE hr_onboarding_notices SET status='uncertain'")
        with self.assertRaises(pool.Conflict):onboard.depart(USER,body|{"version":row["version"]})
        with pool.database() as db:db.execute("UPDATE hr_onboarding_notices SET status='sent'")
        onboard.depart(USER,body|{"version":row["version"]})
        self.assertEqual(onboard.listing(USER,{"status":"departed"})["items"][0]["notice"]["id"],notice["id"])

    def test_mentor_send_requires_actual_joining_and_exact_verified_name(self):
        _,row=self.enrolled()
        with self.assertRaisesRegex(ValueError,"已入职"):onboard.send(USER,self.send_body(row))
        onboard.save(USER,row|{"joined":True,"mentorName":"其他导师"})
        row=onboard.listing(USER,{"status":"joined"})["items"][0]
        with self.assertRaisesRegex(ValueError,"导师名称一致"):onboard.send(USER,self.send_body(row))
        with self.assertRaises(ValueError):
            onboard.send(USER|{"organizationCode":"other"},self.send_body(row))

    def test_explicit_notification_uses_one_recipient_and_tracks_delivery(self):
        _,row=self.enrolled(True);body=self.send_body(row)
        with mock.patch.object(onboard.threading,"Thread") as thread:
            notice=onboard.send(USER,body)
            self.assertEqual(onboard.send(USER,body)["id"],notice["id"]);thread.assert_called_once()
        def api(path,data,*args,**kwargs):
            if path.endswith("asyncsend_v2"):
                self.assertEqual(data["userid_list"],"mentor-id");self.assertFalse(data["to_all_user"])
                self.assertEqual(data["msg"]["text"]["content"],"候选人 已经入职，请准备好 候选人 的带教计划。")
                self.assertNotIn("22000",json.dumps(data,ensure_ascii=False))
                return {"errcode":0,"task_id":123}
            return {"send_result":{"unread_user_id_list":["mentor-id"]}}
        with mock.patch.object(ding,"token",return_value="synthetic"),mock.patch.object(ding,"employee",return_value="导师"), \
             mock.patch.object(ding,"api",side_effect=api) as upstream:
            onboard.deliver("heyi",notice["id"])
        self.assertEqual(upstream.call_count,2)
        self.assertEqual(onboard.listing(USER,{"status":"joined"})["items"][0]["notice"]["status"],"sent")
        with mock.patch.object(onboard.threading,"Thread") as thread:
            onboard.send(USER,body|{"requestId":str(uuid.uuid4())});thread.assert_not_called()

    def test_unknown_send_is_held_and_not_replayed_on_restart(self):
        _,row=self.enrolled(True);body=self.send_body(row)
        with mock.patch.object(onboard.threading,"Thread"):notice=onboard.send(USER,body)
        with mock.patch.object(ding,"token",return_value="synthetic"),mock.patch.object(ding,"employee",return_value="导师"), \
             mock.patch.object(ding,"api",side_effect=ding.UnknownSend("结果不明确")):
            onboard.deliver("heyi",notice["id"])
        onboard.recover()
        self.assertEqual(onboard.listing(USER,{"status":"joined"})["items"][0]["notice"]["status"],"uncertain")
        with mock.patch.object(onboard.threading,"Thread") as thread:
            onboard.send(USER,body|{"requestId":str(uuid.uuid4())});thread.assert_not_called()
        with self.assertRaises(pool.Conflict):onboard.save(USER,row|{"company":"变更公司"})

    def test_phone_lookup_proof_is_scoped_and_name_checked_without_send(self):
        _,row=self.enrolled(True)
        with mock.patch.object(ding,"token",return_value="synthetic"),mock.patch.object(ding,"employee",return_value="导师"), \
             mock.patch.object(ding,"api",return_value={"result":{"userid":"mentor-id"}}), \
             mock.patch.object(onboard.threading,"Thread") as thread:
            person=onboard.identity(USER,{"talentId":row["talentId"],"version":1,"mobile":"13800000001"})
            thread.assert_not_called()
        with mock.patch.object(onboard.threading,"Thread"):
            self.assertEqual(onboard.send(USER,self.send_body(row)|{"identityProof":person["identityProof"]})["status"],"preparing")
        with self.assertRaises(pool.Conflict):onboard.identity(USER,{"talentId":row["talentId"],"version":9,"mobile":"13800000001"})

    def test_legacy_passed_second_interviews_are_backfilled_without_notifications(self):
        with pool.database() as db:
            db.execute("INSERT INTO hr_interview_reviews VALUES(?,?,?,?,?,?,?,?)",
                       ("heyi",self.talent["id"],"second","passed","历史意见","legacy","hr","2026-10-01"))
        with mock.patch.object(ding,"api") as api,onboard.pool.database() as db:
            onboard.schema(db);onboard.backfill(db,"heyi");api.assert_not_called()
        rows=onboard.listing(USER,{"status":"pending"})["items"]
        self.assertEqual(len(rows),1);self.assertEqual(rows[0]["company"],"")

    def test_default_mentor_is_exact_department_leader_without_writing(self):
        with pool.database() as db:
            talent=db.execute("SELECT * FROM talents WHERE id=?",(self.talent["id"],)).fetchone()
            value=json.loads(talent["body"]);value["departmentId"]="dept"
            db.execute("UPDATE talents SET body=? WHERE id=?",(json.dumps(value),self.talent["id"]))
            self.assertEqual(onboard.defaults(db,db.execute("SELECT * FROM talents WHERE id=?",(self.talent["id"],)).fetchone())["mentorName"],"导师")
        _,row=self.enrolled(True)
        result=onboard.recipients(USER,{"talentId":row["talentId"]})
        self.assertEqual(result["defaultRecipient"]["userId"],"mentor-id")
        self.assertEqual(onboard.listing(USER,{})["items"][0]["version"],row["version"])
        with self.assertRaises(ValueError):onboard.recipients(USER|{"organizationCode":"other"},{"talentId":row["talentId"]})
        with pool.database() as db:
            db.execute("UPDATE hr_dingtalk_config SET version=2")
        self.assertIsNone(onboard.recipients(USER,{"talentId":row["talentId"]})["defaultRecipient"])

    def test_other_directory_mentor_is_verified_saved_atomically_and_delivery_uses_new_version(self):
        _,row=self.enrolled(True)
        with mock.patch.object(ding,"employee",return_value="其他老师"):
            person=onboard.identity(USER,{"talentId":row["talentId"],"version":row["version"],
                                        "configVersion":1,"userId":"other-id"})
        body=self.send_body(row)|{"recipientId":"other-id","identityProof":person["identityProof"],"updateMentor":True}
        with mock.patch.object(onboard.threading,"Thread") as thread:
            notice=onboard.send(USER,body)
            onboard.send(USER,body)
            thread.assert_called_once()
        updated=onboard.listing(USER,{})["items"][0]
        self.assertEqual(updated["mentorName"],"其他老师")
        self.assertEqual(updated["mentorUserId"],"other-id")
        self.assertEqual(updated["version"],row["version"]+1)
        with pool.database() as db:
            snapshot=json.loads(db.execute("SELECT body FROM hr_onboarding_notices").fetchone()[0])
            self.assertEqual(snapshot["version"],updated["version"])
        def api(path,data,*args,**kwargs):
            if path.endswith("asyncsend_v2"):
                self.assertEqual(data["userid_list"],"other-id")
                return {"task_id":123}
            return {"send_result":{"unread_user_id_list":["other-id"]}}
        with mock.patch.object(ding,"token",return_value="synthetic"),mock.patch.object(ding,"employee",return_value="其他老师"),mock.patch.object(ding,"api",side_effect=api):
            onboard.deliver("heyi",notice["id"])
        updated=onboard.listing(USER,{})["items"][0]
        self.assertEqual(updated["notice"]["status"],"sent")
        onboard.save(USER,updated|{"company":"更新"})
        self.assertEqual(onboard.listing(USER,{})["items"][0]["mentorUserId"],"other-id")

    def test_changed_config_or_record_cannot_change_mentor_or_create_notice(self):
        _,row=self.enrolled(True)
        with mock.patch.object(ding,"employee",return_value="其他老师"):
            person=onboard.identity(USER,{"talentId":row["talentId"],"version":row["version"],"configVersion":1,"userId":"other-id"})
        body=self.send_body(row)|{"recipientId":"other-id","identityProof":person["identityProof"],"updateMentor":True}
        with self.assertRaises(pool.Conflict):onboard.send(USER,body|{"version":99})
        with pool.database() as db:db.execute("UPDATE hr_dingtalk_config SET version=2")
        with self.assertRaises(ValueError):onboard.send(USER,body|{"configVersion":2})
        updated=onboard.listing(USER,{})["items"][0]
        self.assertEqual(updated["mentorName"],"导师");self.assertIsNone(updated["notice"])

    def test_second_pass_persists_selected_directory_mentor_and_prefers_them_over_leader(self):
        arrangement=ARRANGEMENT|{"mentorName":"选定老师","mentorUserId":"chosen-id","mentorConfigVersion":1}
        with mock.patch.object(ding,"employee",return_value="选定老师"),mock.patch.object(onboard.threading,"Thread") as thread:
            body=self.review(arrangement)
            pool.review_interview(USER,body);pool.review_interview(USER,body)
            thread.assert_not_called()
        row=onboard.listing(USER,{})["items"][0]
        self.assertEqual(row["mentorUserId"],"chosen-id")
        self.assertEqual(onboard.recipients(USER,{"talentId":row["talentId"]})["defaultRecipient"]["userId"],"chosen-id")
        with mock.patch.object(ding,"employee",return_value="选定老师"):
            onboard.save(USER,row|{"joined":True})
        self.assertEqual(onboard.listing(USER,{})["items"][0]["mentorUserId"],"chosen-id")
        with pool.database() as db:
            db.execute("UPDATE hr_dingtalk_config SET version=2")
            db.execute("UPDATE hr_department_dingtalk SET config_version=2")
        self.assertIsNone(onboard.recipients(USER,{"talentId":row["talentId"]})["defaultRecipient"])

    def test_wrong_directory_name_or_stale_config_does_not_save_second_pass(self):
        arrangement=ARRANGEMENT|{"mentorName":"选定老师","mentorUserId":"chosen-id","mentorConfigVersion":1}
        with mock.patch.object(ding,"employee",return_value="同名其他人"):
            with self.assertRaisesRegex(ValueError,"身份已变化"):pool.review_interview(USER,self.review(arrangement))
        with self.assertRaises(pool.Conflict):pool.review_interview(USER,self.review(arrangement|{"mentorConfigVersion":9}))
        with pool.database() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM hr_interview_reviews").fetchone()[0],0)
            self.assertEqual(db.execute("SELECT count(*) FROM hr_onboarding_records").fetchone()[0],0)
