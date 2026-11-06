import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("video_recovery", Path(__file__).with_name("observe-video-recovery.py"))
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


class RecoveryAuthorizationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.task = {"organizationId": "org", "id": "task", "items": [{"id": "item"}]}
        self.ledger = self.root / "data/org/task/item/private-validation/model.requests.json"
        self.ledger.parent.mkdir(parents=True)
        self.original = {"spent": 2, "attempts": [
            {"id": "first", "inputHash": "a", "rejected": True, "stream": True},
            {"id": "second", "inputHash": "a", "rejected": True, "stream": True}]}
        self.ledger.write_text(json.dumps(self.original))
        self.inspect = patch.object(recovery, "inspect", return_value={
            "Mounts": [{"Source": str(self.root / "data"), "Destination": "/data/ai-video-tasks"}]})
        self.inspect.start()
        self.addCleanup(self.inspect.stop)

    def authorize(self, status="FAILED"):
        with patch.object(recovery, "query", return_value=[status]):
            recovery.authorize_exhausted_once(self.root, self.task, "verified-case")

    def test_one_explicit_recovery_keeps_spent_ids_and_stream_flags_and_is_not_repeated(self):
        self.authorize()
        saved = json.loads(self.ledger.read_text())
        self.assertEqual(saved["spent"], 2)
        self.assertEqual(saved["attempts"], self.original["attempts"])
        self.assertEqual(saved["recoveryAllowance"], 1)
        self.assertIn("verified-case", saved["recoveryReason"])
        original_bytes = self.ledger.read_bytes()
        self.authorize()
        self.assertEqual(self.ledger.read_bytes(), original_bytes)
        backups = list((self.root / "recovery-ledger-backups").glob("*.json"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(json.loads(backups[0].read_text()), self.original)

    def test_active_task_cannot_receive_an_allowance(self):
        with self.assertRaises(AssertionError):
            self.authorize("IMAGE_GENERATING")
        self.assertEqual(json.loads(self.ledger.read_text()), self.original)

    def test_unclosed_or_unproven_attempt_cannot_receive_an_allowance(self):
        for state in [
            dict(self.original, attempts=[dict(self.original["attempts"][0], rejected=False), self.original["attempts"][1]]),
            dict(self.original, attempts=self.original["attempts"][:1]),
            dict(self.original, spent=3),
        ]:
            self.ledger.write_text(json.dumps(state))
            with self.assertRaises(AssertionError):
                self.authorize()
            self.assertEqual(json.loads(self.ledger.read_text()), state)

    def test_unused_normal_budget_is_not_increased(self):
        original = dict(self.original, spent=1, attempts=self.original["attempts"][:1])
        self.ledger.write_text(json.dumps(original))
        self.authorize()
        self.assertEqual(json.loads(self.ledger.read_text()), original)

    def test_dispatch_without_real_image_and_storage_proof_cannot_touch_sessions(self):
        with patch.object(recovery, "existing_cookies") as sessions:
            with self.assertRaises(AssertionError):
                recovery.dispatch(self.root, {"tasks": [], "scripts": []}, None)
            sessions.assert_not_called()

    def test_api_uses_configured_cookie_name_and_keeps_cookie_out_of_arguments(self):
        with patch.object(recovery, "command", return_value=b'{"data":{}}\n200') as command:
            self.assertEqual(recovery.api("/api/v1/auth/me", "unit-cookie"), (200, {"data": {}}))
        self.assertIn(b"Cookie: HEYI_SESSION=unit-cookie", command.call_args.kwargs["data"])
        self.assertNotIn("unit-cookie", " ".join(command.call_args.args))

    def test_atomic_save_preserves_existing_application_owner_and_mode(self):
        self.ledger.chmod(0o640)
        metadata = self.ledger.stat()
        with patch.object(recovery.os, "chown") as chown:
            recovery.save(self.ledger, self.original)
        self.assertEqual(self.ledger.stat().st_mode & 0o777, 0o640)
        self.assertEqual(chown.call_args.args[1:], (metadata.st_uid, metadata.st_gid))

    def test_receipts_are_checked_in_actual_private_validation_directory(self):
        operation_root = self.root / "operations"
        operation_root.mkdir()
        for attempt in self.original["attempts"]:
            file = operation_root / (recovery.hashlib.sha256(attempt["id"].encode()).hexdigest() + ".json")
            file.write_text(json.dumps({"status": "FAILED", "error": {"requestState": "REJECTED"}}))
        task = dict(self.task, items=[{"id": "item", "status": "FAILED", "error": "IMAGE_REQUEST_REJECTED"}])
        info = {"Mounts": [
            {"Source": str(self.root / "data"), "Destination": "/data/ai-video-tasks"},
            {"Source": str(operation_root), "Destination": "/data/image-operations"}]}
        with patch.object(recovery, "inspect", return_value=info):
            recovery.verify_image_receipts(task)
            file.write_text(json.dumps({"status": "FAILED", "error": {"requestState": "ACCEPTED_OR_UNKNOWN"}}))
            with self.assertRaises(AssertionError):
                recovery.verify_image_receipts(task)

    def test_failed_script_uses_retry_api_once_not_ready_only_reanalysis(self):
        script = {"id": "script", "name": "failed script", "owner": "owner", "organizationId": "org"}
        targets = {"tasks": [], "scripts": [script]}
        calls = []

        def api(path, cookie, method="GET"):
            calls.append((path, method))
            if path == "/api/v1/auth/me":
                return 200, {"data": {"userId": "owner", "organizationId": "org"}}
            if method == "GET":
                return 200, {"data": {"script": {"status": "FAILED"}}}
            return 202, {"data": {}}

        with patch.object(recovery, "verified_original_proof"), patch.object(
                recovery, "existing_cookies", return_value={"owner": ["unit-cookie"]}), patch.object(
                recovery, "api", side_effect=api):
            recovery.dispatch(self.root, targets, "verified-case")
            recovery.dispatch(self.root, targets, "verified-case")
        posts = [path for path, method in calls if method == "POST"]
        self.assertEqual(posts, ["/api/v1/video-scripts/script/retry"])

    def test_presubmit_repair_retains_prior_receipt_and_runs_at_most_once(self):
        task = dict(self.task, owner="owner", name="failed task", scriptId=None)
        targets = {"tasks": [task], "scripts": []}
        previous = {"status": "ACCEPTED", "at": 123, "httpStatus": 202}
        recovery.save(self.root / "recovery-dispatch.json", {"tasks:task": previous})
        calls = []

        def api(path, cookie, method="GET"):
            calls.append((path, method))
            if path == "/api/v1/auth/me":
                return 200, {"data": {"userId": "owner", "organizationId": "org"}}
            if method == "GET":
                return 200, {"data": {"task": {"status": "FAILED"}}}
            return 202, {"data": {}}

        with patch.object(recovery, "verified_original_proof"), patch.object(
                recovery, "existing_cookies", return_value={"owner": ["unit-cookie"]}), patch.object(
                recovery, "api", side_effect=api), patch.object(
                recovery, "query", side_effect=[["1"], ["0"]]), patch.object(
                recovery, "verify_image_receipts"), patch.object(
                recovery, "verify_unused_allowance") as unused:
            recovery.dispatch(self.root, targets, "verified-case", resume_presubmit=True)
            recovery.dispatch(self.root, targets, "verified-case", resume_presubmit=True)
        self.assertEqual([path for path, method in calls if method == "POST"],
                         ["/api/v1/ai-video-tasks/task/start-generation"])
        unused.assert_called_once_with(self.root, task, repair_ownership=True)
        result = json.loads((self.root / "recovery-dispatch.json").read_text())["tasks:task"]
        self.assertEqual(result["previousDispatch"], previous)
        self.assertTrue(result["presubmitRepairAttempted"])


if __name__ == "__main__":
    unittest.main()
