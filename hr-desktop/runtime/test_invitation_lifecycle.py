"""Workflow + real resume ledgers/files; only external platform calls are fixtures.

This does not claim live candidate delivery. Native page delivery/exchange is
checked separately in the packaged Electron tests.
"""
import contextlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import connector
import passive_runtime as flow


class InvitationLifecycleTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.now = 1792116000
        self.messages = []
        self.calls = []
        self.scope = None
        self.job = "fixture-job"
        self.rule = {"id": "rule-1", "jobId": self.job, "jobName": "工程师",
                     "enabled": True, "enabledAt": self.now - 60, "historyBackfill": False}
        self.friend = {"uid": "202", "encryptUid": "fixture-person", "encryptJobId": self.job,
                       "name": "隔离测试候选人", "lastTS": self.now, "unread": True}
        patches = [
            mock.patch.object(flow.time, "time", lambda: self.now),
            mock.patch.multiple(connector, AUTH_DIR=self.root / "auth", RESUME_DB=self.root / "resumes.sqlite3",
                                RESUME_DIR=self.root / "downloads", RISK_HOLD=self.root / "risk",
                                DISCONNECTED=self.root / "disconnected", RESUME_AUTO_PAUSE=self.root / "pause",
                                LOGIN={"state": "complete"}),
            mock.patch.object(connector, "cli_json", self.cli),
            mock.patch.object(connector, "prepare_recruiter_chat", lambda: None),
            mock.patch.object(connector, "passive_delay", lambda **kw: 0, create=True),
            mock.patch.object(connector, "managed_job_statuses",
                              lambda **kw: {"statuses": {self.job: "开放中"}}, create=True),
            mock.patch.object(connector, "sourcing_friend_details",
                              lambda ids: {"ok": True, "data": {"friendList": [self.friend]}}, create=True),
            mock.patch.object(connector, "conversation_scope", self.expect, create=True),
            mock.patch.object(connector, "conversation_scope_error", lambda: False, create=True),
        ]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    @contextlib.contextmanager
    def expect(self, friend, job):
        self.assertEqual((friend, job), ("202", self.job))
        self.scope = (friend, job)
        try:
            yield
        finally:
            self.scope = None

    def message(self, mid, *, incoming=True, text=None, attachment=False):
        return {"from": {"uid": "202" if incoming else "101"},
                "to": {"uid": "101" if incoming else "202"}, "mid": str(mid), "time": self.now,
                "body": {"hyperLink": {"hyperLinkType": 1, "url": "https://www.zhipin.com/?id=fixture",
                                      "title": "隔离测试简历.pdf"}} if attachment else {"type": 1, "text": text or "您好"}}

    def cli(self, *args, **kwargs):
        self.calls.append(args)
        operation = args[1]
        if operation == "greet":
            self.assertEqual(args[args.index("--job-id") + 1], self.job)
            self.messages.append(self.message(301, incoming=False, text=args[args.index("--message") + 1]))
            return {"ok": True, "data": {"sent": True}}
        if operation == "chat":
            return {"ok": True, "data": {"result": [self.friend], "hasMore": False}}
        if operation == "chatmsg":
            self.assertEqual(args[2], "202")
            return {"ok": True, "data": {"messages": list(self.messages)}}
        if operation in ("reply", "request-resume"):
            self.assertEqual(self.scope, ("202", self.job), "all writes need the job scope")
            with connector.resume_db() as db:
                if operation == "request-resume":
                    self.assertEqual(db.execute("SELECT state FROM resume_requests").fetchone()[0], "uncertain")
            if operation == "reply":
                self.messages.append(self.message(303, incoming=False, text=args[3]))
            return {"ok": True, "data": {"sent": True} if operation == "reply" else {"requested": True}}
        if operation == "download-resume":
            self.assertEqual(args[2], "202")
            self.assertEqual(args[args.index("--message-id") + 1], "900")
            Path(args[args.index("--output") + 1]).write_bytes(b"%PDF-1.4\n% isolated fixture\n%%EOF")
            return {"ok": True, "data": {"originalFileName": "隔离测试简历.pdf"}}
        raise AssertionError("Unexpected external operation: " + operation)

    def execute(self, operation, **extra):
        return flow.execute(connector, self.root, {"operation": operation, **extra})

    def state(self):
        with flow.database(self.root) as db:
            row = db.execute("SELECT state FROM people WHERE friend='202'").fetchone()
            return row[0] if row else None

    def advance(self, expected, **request):
        for _ in range(20):
            self.execute("passive-step", replyTemplate={"id": "speech", "version": 1,
                         "content": "{姓名}，方便沟通一下{职位}吗？"}, **request)
            if self.state() == expected:
                return
            self.now += 61
        self.fail(f"workflow stopped at {self.state()}, expected {expected}")

    def finish_receipt(self, request, expected_origin):
        self.now += 61
        self.messages.append(self.message(900, attachment=True))
        self.advance("export", activityHints=[{"friend": "202", "mid": "900", "job": self.job, "at": self.now}],
                     **request)
        exported = self.execute("passive-local")["data"]["exports"][0]
        self.assertEqual((exported["friend"], exported["mid"]), ("202", "900"))
        self.assertTrue(Path(exported["file"]).read_bytes().startswith(b"%PDF-1.4"))
        metadata = json.loads(exported["body"])
        self.assertEqual(metadata["jobId"], self.job)
        self.assertEqual(metadata.get("origin", "passive"), expected_origin)
        self.assertEqual(metadata["originalFileName"], "隔离测试简历.pdf")
        calls_before = len(self.calls)
        self.assertEqual(self.execute("passive-local")["data"]["exports"][0]["file"], exported["file"])
        self.assertEqual(len(self.calls), calls_before, "cloud retry must reuse the file")
        self.execute("passive-ack", friendId="202", messageId="wrong")
        self.assertEqual(self.state(), "export")
        self.execute("passive-ack", friendId="202", messageId="900")
        self.assertEqual(self.state(), "stored")
        self.assertEqual(self.execute("passive-local")["data"]["exports"], [])
        self.assertEqual(sum(args[1] == "request-resume" for args in self.calls), 1)
        self.assertEqual(sum(args[1] == "download-resume" for args in self.calls), 1)

    def test_passive_incoming_reply_exchange_attachment_export_and_ack(self):
        self.messages.append(self.message(302))
        request = {"rules": [self.rule]}
        self.advance("requested", **request)
        self.assertEqual(sum(args[1] == "reply" for args in self.calls), 1)
        self.finish_receipt(request, "passive")

    def test_active_greet_exact_job_reply_exchange_attachment_export_and_ack(self):
        command = {"id": "active-1", "kind": "active_greet", "message": "隔离测试邀约",
                   "jobId": self.job, "jobName": "工程师", "readAt": self.now, "enabledAt": self.now,
                   "ruleId": "active-rule-1", "origin": "active", "status": "sent",
                   "identifiers": {"geekId": "fixture-person", "expectId": "fixture-expect",
                                   "lid": "fixture-lid", "securityId": "fixture-security"}}
        self.assertEqual(self.execute("sourcing-send", command=command)["data"]["status"], "sent")
        self.now += 61
        self.messages.append(self.message(302))
        request = {"rules": [], "activeContacts": [command]}
        self.advance("requested", **request)
        self.assertEqual(sum(args[1] == "greet" for args in self.calls), 1)
        self.assertEqual(sum(args[1] == "reply" for args in self.calls), 0, "active follow-up must not greet twice")
        self.finish_receipt(request, "active")


if __name__ == "__main__":
    unittest.main()
