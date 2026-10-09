import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import uuid
import zipfile

import dingtalk_interviews as dt
import hr_departments
import passive_pool
import recruitment_demands
import talent_pool as pool

USER = {"id": "admin-1", "username": "13800000000", "organizationCode": "heyi",
        "platformAdmin": True, "roles": ["ADMIN"]}
RAW = b"%PDF-1.7\nsynthetic-resume\n"


class DingTalkInterviewTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        for patch in [mock.patch.object(pool, "DB", self.root/"pool.sqlite"),
                      mock.patch.object(dt, "KEY_FILE", self.root/"private"/"ding.key"),
                      mock.patch.object(dt, "launch")]:
            patch.start(); self.addCleanup(patch.stop)
        dt.TOKENS.clear()
        self.talent = pool.save(USER, {"name": "张同学", "stage": "invited", "source": "active", "position": "算法"})
        self.resume = str(uuid.uuid4())
        self.demand = str(uuid.uuid4())
        self.questions = [{"question": "<script>alert(1)</script>解释模型评估",
                           "competency": "算法能力", "followUps": ["如何验证"],
                           "evaluationCriteria": "准确性", "strongAnswerSignals": ["真实案例"]}]
        with pool.database() as db:
            passive_pool.schema(db); recruitment_demands.schema(db); dt.schema(db)
            now = "2026-10-08T00:00:00Z"
            db.execute("""INSERT INTO hr_resume_pool(id,owner,account,friend_id,rule_id,message_id,body,file_key,
              file_hash,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'approved',?,?)""",
                (self.resume, "heyi", "a"*64, "friend", "rule", "message",
                 json.dumps({"talentId": self.talent["id"], "jobId": "job-1", "jobName": "算法",
                             "fileName": "张同学.pdf", "extension": "pdf"}),
                 "synthetic-key", hashlib.sha256(RAW).hexdigest(), now, now))
            db.execute("""INSERT INTO recruitment_demands(id,owner,body,status,version,created_at,updated_at,
              ai_status,ai_version,ai_result) VALUES(?,?,?,'recruiting',1,?,?,'complete',1,?)""",
                (self.demand, "heyi", json.dumps({"position": "算法", "jobId": "job-1", "jobAccountKey": "a"*64,
                                               "jobDescription": "岗位JD", "department": "研发"}),
                 now, now, json.dumps({"interviewQuestions": self.questions})))
        with mock.patch.object(dt, "api", return_value={"accessToken": "synthetic-token", "expireIn": 7200}):
            dt.save_config(USER, {"appKey": "ding-test", "appSecret": "synthetic-private-secret", "agentId": "123", "version": 0})
        with mock.patch.object(dt, "employee", return_value="负责人"):
            dt.save_recipient(USER, {"demandId": self.demand, "userId": "employee1", "configVersion": 1})

    def body(self):
        return {"talentId": self.talent["id"], "requestId": str(uuid.uuid4()), "version": 1,
                "demandId": self.demand, "demandVersion": 1, "resumeId": self.resume,
                "recipientId": "employee1", "configVersion": 1}

    def job(self):
        with pool.database() as db:
            return dict(db.execute("SELECT * FROM hr_interview_handoffs").fetchone())

    def deliver(self, result=None, error=None):
        def call(path, *args, **kwargs):
            if path.endswith("asyncsend_v2"):
                if error: raise error
                return result or {"errcode": 0, "task_id": 12345}
            if path.endswith("getsendresult"):
                return {"errcode": 0, "send_result": {"unread_user_id_list": ["employee1"]}}
            raise AssertionError("Unexpected call: "+path)
        with mock.patch.object(dt, "employee", return_value="负责人"), \
             mock.patch.object(dt.resume_storage, "download", return_value=RAW), \
             mock.patch.object(dt, "upload", return_value="synthetic-media"), \
             mock.patch.object(dt, "api", side_effect=call) as upstream:
            dt.deliver("heyi", self.job()["id"])
            return upstream.call_args_list

    def test_exact_job_link_and_readiness_not_name_guessing(self):
        preview = dt.preview(USER, {"talentId": self.talent["id"]})
        self.assertEqual(preview["demands"][0]["recipient"], "负责人")
        self.assertEqual(preview["demands"][0]["questionCount"], 1)
        for changes in [{"recipientId": "other"}, {"version": 2}, {"demandVersion": 2},
                        {"configVersion": 2}, {"resumeId": "other"}]:
            with self.assertRaises(ValueError): dt.start(USER, self.body() | changes)
        with pool.database() as db:
            db.execute("UPDATE recruitment_demands SET body=json_set(body,'$.jobId','other')")
        with self.assertRaisesRegex(ValueError, "同一具体职位"):
            dt.start(USER, self.body())
        self.assertEqual(dt.preview(USER, {"talentId": self.talent["id"]})["demands"], [])

    def test_secret_encrypted_and_never_exposed_or_audited(self):
        result = json.dumps(dt.settings(USER))
        self.assertNotIn("synthetic-private-secret", result)
        with pool.database() as db:
            secret = db.execute("SELECT secret FROM hr_dingtalk_config").fetchone()[0]
            audits = str([tuple(row) for row in db.execute("SELECT * FROM talent_events")])
        self.assertNotIn("synthetic-private-secret", secret + audits)
        self.assertEqual(dt.cipher().decrypt(secret.encode()).decode(), "synthetic-private-secret")
        self.assertEqual(dt.KEY_FILE.stat().st_mode & 0o777, 0o600)

    def test_duplicate_click_and_lost_response_create_one_send(self):
        body = self.body()
        one = dt.start(USER, body)
        self.assertEqual(one["id"], dt.start(USER, body)["id"])
        self.assertEqual(one["id"], dt.start(USER, self.body())["id"])
        calls = self.deliver()
        send = [call for call in calls if call.args[0].endswith("asyncsend_v2")]
        self.assertEqual(len(send), 1)
        self.assertEqual(send[0].args[1]["userid_list"], "employee1")
        self.assertFalse(send[0].args[1]["to_all_user"])
        self.assertEqual(send[0].args[1]["msg"]["msgtype"], "file")
        self.assertEqual(self.job()["status"], "sent")
        self.assertEqual(pool.listing(USER, {})["items"][0]["stage"], "first")
        self.assertEqual(dt.start(USER, self.body())["status"], "sent")
        self.assertEqual(self.deliver(), [])

    def test_preparing_package_contains_resume_questions_and_jd(self):
        dt.start(USER, self.body())
        value = json.loads(self.job()["body"])
        with zipfile.ZipFile(io.BytesIO(dt.package(value, RAW))) as package:
            self.assertEqual(package.read("候选人简历.pdf"), RAW)
            self.assertEqual(package.read("岗位JD.txt").decode(), "岗位JD")
            questions = package.read("一面面试题库.html").decode()
            self.assertIn("&lt;script&gt;", questions)
            self.assertNotIn("<script>", questions)
            self.assertIn("真实案例", questions)
        with self.assertRaisesRegex(ValueError, "校验"):
            dt.package(value, RAW+b"changed")

    def test_download_uses_saved_snapshot_and_does_not_contact_dingtalk_or_start_another_send(self):
        dt.start(USER,self.body())
        with pool.database() as db:
            db.execute("UPDATE recruitment_demands SET body=json_set(body,'$.jobDescription','新版岗位'),version=2")
        with mock.patch.object(dt.resume_storage,"download",return_value=RAW) as stored, \
             mock.patch.object(dt,"api") as remote:
            content,filename=dt.download_package(USER,{"talentId":self.talent["id"]})
            stored.assert_called_once_with("heyi","synthetic-key")
            remote.assert_not_called()
        self.assertTrue(filename.endswith(".zip"))
        with zipfile.ZipFile(io.BytesIO(content)) as packet:
            self.assertEqual(packet.read("候选人简历.pdf"),RAW)
            self.assertEqual(packet.read("岗位JD.txt").decode(),"岗位JD")
            self.assertIn("解释模型评估",packet.read("一面面试题库.html").decode())
        self.assertEqual(self.job()["status"],"preparing")
        self.assertEqual(dt.launch.call_count,1)

    def test_download_before_send_needs_only_ready_exact_job_documents_not_dingtalk_configuration(self):
        with pool.database() as db:
            db.execute("DELETE FROM hr_dingtalk_config")
        self.assertTrue(dt.preview(USER,{"talentId":self.talent["id"]})["packageReady"])
        with mock.patch.object(dt.resume_storage,"download",return_value=RAW),mock.patch.object(dt,"api") as remote:
            content,_=dt.download_package(USER,{"talentId":self.talent["id"],
                                               "resumeId":self.resume,"demandId":self.demand})
            remote.assert_not_called()
        self.assertTrue(zipfile.is_zipfile(io.BytesIO(content)))
        dt.launch.assert_not_called()
        with pool.database() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM hr_interview_handoffs").fetchone()[0],0)

    def test_download_rejects_cross_candidate_job_account_owner_and_changed_file(self):
        query={"talentId":self.talent["id"],"resumeId":self.resume,"demandId":self.demand}
        for change in ({"talentId":"foreign"},{"resumeId":"foreign"},{"demandId":"foreign"}):
            with mock.patch.object(dt.resume_storage,"download") as stored:
                with self.assertRaises(ValueError):
                    dt.download_package(USER,query|change)
                stored.assert_not_called()
        with self.assertRaises(ValueError):
            dt.download_package(USER|{"organizationCode":"other"},query)
        with pool.database() as db:
            db.execute("UPDATE recruitment_demands SET body=json_set(body,'$.jobAccountKey',?)",("b"*64,))
        with self.assertRaisesRegex(ValueError,"同一账号"):
            dt.download_package(USER,query)
        with pool.database() as db:
            db.execute("UPDATE recruitment_demands SET body=json_set(body,'$.jobAccountKey',?)",("a"*64,))
        with mock.patch.object(dt.resume_storage,"download",return_value=RAW+b"changed"):
            with self.assertRaisesRegex(ValueError,"校验不一致"):
                dt.download_package(USER,query)

    def test_unknown_send_never_retry_even_after_reload_and_restart(self):
        dt.start(USER, self.body())
        self.deliver(error=dt.UnknownSend("结果待核对"))
        self.assertEqual(self.job()["status"], "uncertain")
        dt.recover()
        self.assertEqual(dt.start(USER, self.body())["status"], "uncertain")
        self.assertEqual(self.deliver(), [])
        self.assertEqual(pool.listing(USER, {})["items"][0]["stage"], "invited")

    def test_explicit_rejection_allows_only_explicit_retry(self):
        dt.start(USER, self.body())
        self.deliver(error=dt.DingError("钉钉未接受请求"))
        self.assertEqual(self.job()["status"], "failed")
        with self.assertRaises(pool.Conflict): dt.start(USER, self.body())
        retried = dt.start(USER, self.body() | {"retryId": self.job()["id"]})
        self.assertEqual(retried["attempt"], 2)
        self.deliver()
        self.assertEqual(self.job()["status"], "sent")

    def test_restart_distinguishes_preparation_and_started_send(self):
        dt.start(USER, self.body()); dt.recover()
        self.assertEqual(self.job()["status"], "failed")
        dt.start(USER, self.body() | {"retryId": self.job()["id"]})
        with pool.database() as db: db.execute("UPDATE hr_interview_handoffs SET status='sending'")
        dt.recover()
        self.assertEqual(self.job()["status"], "uncertain")

    def test_receipt_not_task_acceptance_advances_stage(self):
        dt.start(USER, self.body())
        with mock.patch.object(dt, "reconcile"): self.deliver()
        self.assertEqual(self.job()["status"], "submitted")
        self.assertEqual(pool.listing(USER, {})["items"][0]["stage"], "invited")
        with mock.patch.object(dt, "api", return_value={"errcode": 0, "send_result": {}}):
            dt.reconcile("heyi", self.job()["id"])
        self.assertEqual(self.job()["status"], "submitted")
        with pool.database() as db: db.execute("UPDATE hr_interview_handoffs SET checked_at=0")
        with mock.patch.object(dt, "api", return_value={"errcode": 0, "send_result": {"forbidden_list": [{"userid": "employee1"}]}}):
            dt.reconcile("heyi", self.job()["id"])
        self.assertEqual(self.job()["status"], "failed")
        self.assertEqual(pool.listing(USER, {})["items"][0]["stage"], "invited")

    def test_changed_candidate_or_mapping_during_upload_blocks_send(self):
        dt.start(USER, self.body())
        def upload(*args):
            with pool.database() as db:
                db.execute("UPDATE hr_dingtalk_recipients SET user_id='changed'")
            return "synthetic-media"
        with mock.patch.object(dt, "employee", return_value="负责人"), \
             mock.patch.object(dt.resume_storage, "download", return_value=RAW), \
             mock.patch.object(dt, "upload", side_effect=upload), mock.patch.object(dt, "api") as upstream:
            dt.deliver("heyi", self.job()["id"])
            upstream.assert_not_called()
        self.assertEqual(self.job()["status"], "failed")

    def test_organization_access_and_cross_candidate_resume_rejected(self):
        with self.assertRaises(ValueError): dt.settings(USER | {"organizationCode": "other"})
        second = pool.save(USER, {"name": "另一个人", "stage": "invited"})
        with self.assertRaisesRegex(ValueError, "该候选人"):
            dt.start(USER, self.body() | {"talentId": second["id"]})

    def test_file_limit_and_http_redirect_or_bad_response_never_claim_success(self):
        dt.start(USER, self.body())
        with mock.patch.object(dt, "MAX_PACKAGE", 1):
            with self.assertRaisesRegex(ValueError, "20MB"): dt.package(json.loads(self.job()["body"]), RAW)
        fake = mock.MagicMock()
        fake.open.return_value.__enter__.return_value.read.return_value = b'{"errmsg":"no task"}'
        with mock.patch.object(dt.urllib.request, "build_opener", return_value=fake):
            with self.assertRaises(dt.UnknownSend): dt.api("send", {}, "secret", sending=True)

    def test_malformed_token_metadata_never_leaks_upstream_values(self):
        dt.TOKENS.clear()
        with mock.patch.object(dt, "api", return_value={"accessToken": "synthetic-token",
                                                       "expireIn": "private-provider-value"}):
            with self.assertRaises(dt.DingError) as error:
                dt.token(dt.read_config("heyi"))
        self.assertNotIn("private-provider-value", str(error.exception))

    def test_permission_88_identifies_member_read_without_echoing_upstream_or_replaying(self):
        result = {"errcode": 88, "errmsg": "private-app-token",
                  "sub_code": "60011", "sub_msg": "permission qyapi_get_member private-phone-value"}
        fake = mock.MagicMock()
        fake.open.return_value.__enter__.return_value.read.return_value = json.dumps(result).encode()
        dt.TOKENS["current"] = ("synthetic-token", dt.time.time()+7200)
        dt.TOKENS["other"] = ("other-token", dt.time.time()+7200)
        with mock.patch.object(dt.urllib.request, "build_opener", return_value=fake), \
             mock.patch("builtins.print") as output:
            with self.assertRaisesRegex(dt.DingError, "成员信息读权限") as error:
                dt.api("topapi/v2/user/get", {"userid": "employee1"}, "synthetic-token")
        self.assertIn("88 / 60011", str(error.exception))
        self.assertNotIn("current", dt.TOKENS)
        self.assertIn("other", dt.TOKENS)
        self.assertEqual(fake.open.call_count, 1)
        logs = str(output.call_args_list)
        self.assertIn("qyapi_get_member", logs)
        for private in ("private-app-token", "private-phone-value", "synthetic-token", "employee1"):
            self.assertNotIn(private, str(error.exception)+logs)

    def test_phone_query_permission_is_distinct_and_definite_send_rejection_stays_retryable(self):
        fake = mock.MagicMock()
        fake.open.return_value.__enter__.return_value.read.return_value = b'{"errcode":88,"sub_code":"60011"}'
        with mock.patch.object(dt.urllib.request, "build_opener", return_value=fake), mock.patch("builtins.print"):
            with self.assertRaisesRegex(dt.DingError, "根据手机号获取成员基本信息权限"):
                dt.api("topapi/v2/user/getbymobile", {"mobile": "13900000001"}, "synthetic-token")
            with self.assertRaises(dt.DingError) as error:
                dt.api("topapi/message/corpconversation/asyncsend_v2", {}, "synthetic-token", sending=True)
            self.assertNotIsInstance(error.exception, dt.UnknownSend)
        self.assertEqual(fake.open.call_count, 2, "One upstream call per explicit operation, no retry")

    def department(self):
        return hr_departments.save(USER, {"name": "研发", "code": "RD", "manager": "旧经理", "status": "ACTIVE"})

    def identity(self, department):
        def call(path, data, *args, **kwargs):
            if path == "topapi/v2/user/getbymobile":
                self.assertEqual(data, {"mobile": "13900000001"})
                return {"errcode": 0, "result": {"userid": "employee2"}}
            if path == "topapi/v2/user/get":
                self.assertEqual(data["userid"], "employee2")
                return {"errcode": 0, "result": {"userid": "employee2", "name": "新负责人"}}
            raise AssertionError("Identity must not send: "+path)
        with mock.patch.object(dt, "api", side_effect=call):
            return dt.department_identity(USER, {"id": department["id"], "version": department["version"],
                                               "mobile": "+86 139-0000-0001", "configVersion": 1})

    def link_department(self, department):
        with pool.database() as db:
            db.execute("UPDATE recruitment_demands SET body=json_set(body,'$.departmentId',?) WHERE id=?",
                       (department["id"], self.demand))

    def test_phone_lookup_requires_name_confirmation_and_keeps_only_masked_phone(self):
        department = self.department()
        identity = self.identity(department)
        self.assertEqual(identity["name"], "新负责人")
        self.assertEqual(identity["mobileMasked"], "139****0001")
        self.assertIsNone(hr_departments.listing(USER)["items"][0]["dingtalk"])
        dt.bind_department(USER, {"id": department["id"], "identityProof": identity["identityProof"]})
        result = hr_departments.listing(USER)["items"][0]
        self.assertEqual(result["manager"], "新负责人")
        self.assertEqual(result["version"], 2)
        self.assertTrue(result["dingtalk"]["valid"])
        with pool.database() as db:
            data = str([tuple(row) for row in db.execute("SELECT * FROM hr_department_dingtalk")])
            data += str([tuple(row) for row in db.execute("SELECT * FROM talent_events")])
        self.assertNotIn("13900000001", data)
        self.assertNotIn(identity["identityProof"], data)
        with self.assertRaises(pool.Conflict):
            dt.bind_department(USER, {"id": department["id"], "identityProof": identity["identityProof"]})

    def test_department_binding_routes_all_exact_linked_jobs_over_legacy_mappings(self):
        department = self.department()
        identity = self.identity(department)
        dt.bind_department(USER, {"id": department["id"], "identityProof": identity["identityProof"]})
        self.link_department(department)
        preview = dt.preview(USER, {"talentId": self.talent["id"]})
        recipient = preview["demands"][0]
        self.assertEqual(recipient["recipientId"], "employee2", "Must not keep old job-specific employee1")
        self.assertTrue(recipient["recipientReady"])
        with pool.database() as db:
            db.execute("""INSERT INTO recruitment_demands(id,owner,body,status,version,created_at,updated_at)
                VALUES('another','heyi',?,'draft',1,'now','now')""",
                       (json.dumps({"position": "另一个岗位", "departmentId": department["id"]}),))
        self.assertEqual([item["recipient"]["userId"] for item in dt.settings(USER)["demands"]],
                         ["employee2", "employee2"])
        with self.assertRaisesRegex(ValueError, "负责人映射"):
            dt.start(USER, self.body())
        body = self.body() | {"recipientId": "employee2", "recipientBindingKey": recipient["recipientBindingKey"]}
        dt.start(USER, body)
        value = json.loads(self.job()["body"])
        self.assertEqual(value["recipientId"], "employee2")

    def test_unbound_changed_or_disabled_department_never_falls_back_to_old_recipient(self):
        department = self.department()
        self.link_department(department)
        self.assertFalse(dt.preview(USER, {"talentId": self.talent["id"]})["demands"][0]["recipientReady"])
        identity = self.identity(department)
        dt.bind_department(USER, {"id": department["id"], "identityProof": identity["identityProof"]})
        current = hr_departments.listing(USER)["items"][0]
        disabled = hr_departments.save(USER, current | {"status": "DISABLED"})
        self.assertFalse(dt.preview(USER, {"talentId": self.talent["id"]})["demands"][0]["recipientReady"])
        hr_departments.save(USER, disabled | {"status": "ACTIVE", "manager": "换人了"})
        self.assertIsNone(hr_departments.listing(USER)["items"][0]["dingtalk"])
        self.assertFalse(dt.preview(USER, {"talentId": self.talent["id"]})["demands"][0]["recipientReady"])
        with self.assertRaisesRegex(ValueError, "负责人映射"):
            dt.start(USER, self.body())

    def test_expired_wrong_department_changed_app_or_changed_department_proof_cannot_bind(self):
        department = self.department()
        identity = self.identity(department)
        with self.assertRaisesRegex(ValueError, "不一致"):
            dt.bind_department(USER, {"id": "wrong", "identityProof": identity["identityProof"]})
        proof = dt.cipher().decrypt(identity["identityProof"].encode())
        expired = dt.cipher().encrypt_at_time(proof, int(dt.time.time())-301).decode()
        with self.assertRaisesRegex(ValueError, "失效"):
            dt.bind_department(USER, {"id": department["id"], "identityProof": expired})
        with self.assertRaises(ValueError):
            dt.bind_department(USER, {"id": department["id"], "identityProof": "tampered"})
        hr_departments.save(USER, department | {"manager": "另一经理"})
        with self.assertRaises(pool.Conflict):
            dt.bind_department(USER, {"id": department["id"], "identityProof": identity["identityProof"]})
        current = hr_departments.listing(USER)["items"][0]
        identity = self.identity(current)
        with pool.database() as db:
            db.execute("UPDATE hr_dingtalk_config SET version=2")
        with self.assertRaises(pool.Conflict):
            dt.bind_department(USER, {"id": department["id"], "identityProof": identity["identityProof"]})
        self.assertIsNone(hr_departments.listing(USER)["items"][0]["dingtalk"])

    def test_invalid_phone_and_lookup_failure_do_not_create_a_binding(self):
        department = self.department()
        body = {"id": department["id"], "version": 1, "configVersion": 1}
        with mock.patch.object(dt, "api") as api:
            for mobile in ["", "139****0001", "123", "13900000001junk"]:
                with self.assertRaises(ValueError):
                    dt.department_identity(USER, body | {"mobile": mobile})
            api.assert_not_called()
        with mock.patch.object(dt, "api", return_value={"errcode": 0, "result": {}}):
            with self.assertRaisesRegex(ValueError, "未识别"):
                dt.department_identity(USER, body | {"mobile": "13900000001"})
        self.assertIsNone(hr_departments.listing(USER)["items"][0]["dingtalk"])

    def test_department_changes_during_package_upload_stop_before_any_notification(self):
        department = self.department()
        identity = self.identity(department)
        dt.bind_department(USER, {"id": department["id"], "identityProof": identity["identityProof"]})
        self.link_department(department)
        recipient = dt.preview(USER, {"talentId": self.talent["id"]})["demands"][0]
        dt.start(USER, self.body() | {"recipientId": "employee2", "recipientBindingKey": recipient["recipientBindingKey"]})
        def upload(*args):
            current = hr_departments.listing(USER)["items"][0]
            hr_departments.save(USER, current | {"manager": "更换负责人"})
            return "synthetic-media"
        with mock.patch.object(dt, "employee", return_value="新负责人"), \
             mock.patch.object(dt.resume_storage, "download", return_value=RAW), \
             mock.patch.object(dt, "upload", side_effect=upload), mock.patch.object(dt, "api") as upstream:
            dt.deliver("heyi", self.job()["id"])
            upstream.assert_not_called()
        self.assertEqual(self.job()["status"], "failed")
        self.assertEqual(pool.listing(USER, {})["items"][0]["stage"], "invited")

if __name__ == "__main__":
    unittest.main()
