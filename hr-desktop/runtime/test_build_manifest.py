import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

from build_manifest import MANIFEST, source_snapshot, verify_runtime, write_manifest


class RuntimeBuildManifestTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "source"
        for relative in [
            "hr-desktop/runtime/runner.py",
            "hr-desktop/runtime/build.py",
            "hr-desktop/runtime/constraints.txt",
            "hr-desktop/vendor/boss_agent_cli-2.0.0-py3-none-any.whl",
            "scripts/boss-agent/connector.py",
            "scripts/boss-agent/guarded_cli.py",
        ]:
            file = self.repo / relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("fixture source", encoding="utf-8")
        self.worker = self.root / "worker"
        self.worker.mkdir()
        (self.worker / "heyi-boss").write_bytes(b"offline worker fixture")

    def stamp(self):
        return write_manifest(self.worker, source_snapshot(self.repo), self.repo)

    def test_copied_worker_and_source_tree_keep_valid_provenance(self):
        self.stamp()
        copied = self.root / "copied"
        copied_source = self.root / "copied-source"
        shutil.copytree(self.worker, copied)
        shutil.copytree(self.repo, copied_source)
        value = verify_runtime(copied, sys.platform, copied_source)
        self.assertEqual(value["schemaVersion"], 1)
        self.assertEqual(set(value["files"]), {"heyi-boss"})

    def test_changed_or_new_production_source_requires_rebuild(self):
        self.stamp()
        for name in ["runner.py", "new_policy.py"]:
            file = self.repo / "hr-desktop/runtime" / name
            file.write_text("new source", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "sources are stale"):
                verify_runtime(self.worker, sys.platform, self.repo)

    def test_changed_shared_policy_and_sdk_require_rebuild(self):
        for relative in [
            "scripts/boss-agent/guarded_cli.py",
            "hr-desktop/vendor/boss_agent_cli-2.0.0-py3-none-any.whl",
        ]:
            with self.subTest(relative=relative):
                (self.worker / MANIFEST).unlink(missing_ok=True)
                self.stamp()
                (self.repo / relative).write_bytes(b"changed fixture")
                with self.assertRaisesRegex(ValueError, "sources are stale"):
                    verify_runtime(self.worker, sys.platform, self.repo)

    def test_changed_or_additional_binary_requires_rebuild(self):
        self.stamp()
        (self.worker / "heyi-boss").write_bytes(b"changed worker")
        with self.assertRaisesRegex(ValueError, "files changed"):
            verify_runtime(self.worker, sys.platform, self.repo)
        (self.worker / "heyi-boss").write_bytes(b"offline worker fixture")
        (self.worker / "unexpected.dll").write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "files changed"):
            verify_runtime(self.worker, sys.platform, self.repo)

    def test_missing_or_foreign_manifest_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "no valid build manifest"):
            verify_runtime(self.worker, sys.platform, self.repo)
        self.stamp()
        target = self.worker / MANIFEST
        value = json.loads(target.read_text())
        value["platform"] = "foreign-os"
        target.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "different operating system"):
            verify_runtime(self.worker, sys.platform, self.repo)

    def test_source_change_during_build_does_not_stamp_output(self):
        snapshot = source_snapshot(self.repo)
        (self.repo / "hr-desktop/runtime/runner.py").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "changed during"):
            write_manifest(self.worker, snapshot, self.repo)
        self.assertFalse((self.worker / MANIFEST).exists())

    @unittest.skipIf(sys.platform == "win32", "Windows symlink creation needs a separate OS privilege")
    def test_external_link_is_rejected_before_packaging(self):
        outside = self.root / "outside"
        outside.write_bytes(b"not a worker resource")
        (self.worker / "link").symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "external or broken"):
            self.stamp()


if __name__ == "__main__":
    unittest.main()
