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


if __name__ == "__main__":
    unittest.main()
