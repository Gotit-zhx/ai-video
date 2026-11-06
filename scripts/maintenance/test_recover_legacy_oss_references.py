import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("legacy_recovery", Path(__file__).with_name("recover-legacy-oss-references.py"))
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        org, task, item, job, rid = ["00000000-0000-0000-0000-" + str(i).zfill(12) for i in range(1, 6)]
        self.data = b"\xff\xd8\xff" + b"original-audit-frame"
        relative = f"{org}/{task}/{item}/private-video-review/{job}/first-frame.jpg"
        self.file = self.root / relative
        self.file.parent.mkdir(parents=True)
        self.file.write_bytes(self.data)
        sha = hashlib.sha256(self.data).hexdigest()
        (self.file.parent / "audit-result.json").write_text(json.dumps({
            "version": "VIDEO_FRAME_LAYOUT_V1", "frameSha256": sha,
        }))
        self.row = {
            "id": rid, "organizationId": org, "taskId": task, "itemId": item, "proofJobId": job,
            "type": "VIDEO_FIRST_FRAME", "contentType": "image/jpeg", "size": len(self.data),
            "path": f"oss://hoyee/heyi/video-audits/{org}/{task}/{job}/{sha[:24]}/first-frame.jpg",
            "newPath": "oss://test-hoyee/heyi/server-materials/ai-video-tasks/" + relative,
            "proofSource": "/data/ai-video-tasks/" + relative, "sha256": sha,
            "md5": hashlib.md5(self.data).hexdigest(),
        }

    def test_exact_audit_provenance_and_destination_content_are_required(self):
        self.assertEqual(recovery.validate_recovery(self.row, self.root, "test-hoyee"), self.data)
        recovery.verify_destination(self.data, self.row)
        for field, value in [
            ("type", "MODEL_IMAGE"), ("contentType", "image/png"), ("size", len(self.data) + 1),
            ("organizationId", "00000000-0000-0000-0000-000000000009"),
            ("proofJobId", "00000000-0000-0000-0000-000000000009"),
            ("newPath", self.row["newPath"].replace("test-hoyee", "another-bucket")),
            ("proofSource", self.row["proofSource"].replace("private-video-review", "other")),
            ("path", self.row["path"].replace(self.row["sha256"][:24], "0"*24)),
            ("sha256", "0"*64), ("md5", "0"*32),
        ]:
            with self.subTest(field=field):
                with self.assertRaises(ValueError):
                    recovery.validate_recovery({**self.row, field: value}, self.root, "test-hoyee")
        with self.assertRaises(ValueError):
            recovery.verify_destination(self.data + b"x", self.row)
        with self.assertRaises(ValueError):
            recovery.verify_destination(b"\xff\xd8\xff" + b"changed-audit-frame!", self.row)

    def test_local_change_and_missing_or_mismatched_audit_proof_are_rejected(self):
        self.file.write_bytes(self.data + b"changed")
        with self.assertRaises(ValueError):
            recovery.validate_recovery(self.row, self.root, "test-hoyee")
        self.file.write_bytes(self.data)
        audit = self.file.parent / "audit-result.json"
        audit.write_text(json.dumps({"version": "VIDEO_FRAME_LAYOUT_V1", "frameSha256": "0"*64}))
        with self.assertRaises(ValueError):
            recovery.validate_recovery(self.row, self.root, "test-hoyee")
        audit.unlink()
        with self.assertRaises(FileNotFoundError):
            recovery.validate_recovery(self.row, self.root, "test-hoyee")

    def test_symlink_proof_is_rejected(self):
        original = self.root / "other.jpg"
        original.write_bytes(self.data)
        self.file.unlink()
        self.file.symlink_to(original)
        with self.assertRaisesRegex(ValueError, "symlink"):
            recovery.validate_recovery(self.row, self.root, "test-hoyee")

    def test_reference_switch_uses_exact_identity_and_refuses_concurrent_changes(self):
        calls = []
        current = {"path": self.row["newPath"], "size": self.row["size"], "type": self.row["type"],
                   **{k: self.row[k] for k in ("organizationId", "taskId", "itemId")}}

        def query(sql):
            calls.append(sql)
            return "1" if sql.startswith("UPDATE") else json.dumps(current)

        recovery.switch_reference(query, self.row)
        self.assertIn("AND size_bytes=" + str(self.row["size"]), calls[0])
        for field in ("id", "file_path", "asset_type", "organization_id", "task_id", "item_id"):
            self.assertIn("`" + field + "`=", calls[0])
        with self.assertRaisesRegex(ValueError, "changed during switch"):
            recovery.switch_reference(lambda sql: "0", self.row)
        current["path"] = "oss://test-hoyee/a-different-update"
        with self.assertRaisesRegex(ValueError, "post-switch"):
            recovery.switch_reference(query, self.row)
        current["path"] = self.row["newPath"]
        current["itemId"] = "another-item"
        self.assertFalse(recovery.same_asset(current, self.row, self.row["newPath"]))

    def test_sql_strings_are_hex_literals_and_backup_events_are_private(self):
        value = "quote' backslash\\ 中文"
        literal = recovery.sql_text(value)
        encoded = literal.split("0x", 1)[1].split(" ", 1)[0]
        self.assertEqual(bytes.fromhex(encoded).decode(), value)
        log = self.root / "events.jsonl"
        recovery.append_event(log, {"stage": "verified", "id": self.row["id"]})
        self.assertEqual(log.stat().st_mode & 0o777, 0o600)
        self.assertEqual(json.loads(log.read_text())["id"], self.row["id"])


if __name__ == "__main__":
    unittest.main()
