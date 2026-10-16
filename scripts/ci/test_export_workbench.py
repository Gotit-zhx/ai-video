import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import export_workbench as module


class ExportWorkbenchTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.output = self.root / "export"
        self.package = self.root / "workbench"
        files = {}
        for name in (*module.REQUIRED, "native-client/boss-zhipin.exe"):
            target = self.package / name
            target.parent.mkdir(parents=True, exist_ok=True)
            data = b"own isolated export fixture"
            target.write_bytes(data)
            files[name] = hashlib.sha256(data).hexdigest()
        self.manifest = {"schemaVersion": 1, "experimental": True, "realAccountTested": False,
                         "originalResourcesModified": False, "files": files}
        self.smoke = {"originalExitCode": 0, "entryControllerExitCode": 0, "candidateMessagesSent": 0,
                      "actualBossNetworkUsed": False, "timedOut": False, "terminationRequested": False}
        for name in module.CHECKS:
            target = self.smoke
            parts = name.split(".")
            for key in parts[:-1]:
                target = target.setdefault(key, {})
            target[parts[-1]] = True
        self.smoke["shutdown"]["realAccountUsed"] = False
        self.smoke["incomingActivity"]["additionalBossRequests"] = 0
        self.write()
        (self.root / "ci-result.json").write_text('{"ok":false}')
        self.pin = patch.object(module, "PIN", {"boss-zhipin.exe": files["native-client/boss-zhipin.exe"]})
        self.pin.start()
        self.addCleanup(self.pin.stop)

    def write(self):
        (self.package / "heyi-package.json").write_text(json.dumps(self.manifest))
        (self.root / "windows-workbench-smoke.json").write_text(json.dumps(self.smoke))

    def run_export(self, private=True):
        return module.export(self.root, self.output, {"repository": {"private": private}}, "a" * 40)

    def test_exports_only_verified_files_and_keeps_failed_probe_visible(self):
        (self.package / "untracked-session.enc").write_text("must not ship")
        (self.root / "profile").mkdir()
        (self.root / "profile/cookies").write_text("must not ship")
        report = self.run_export()
        self.assertFalse(report["overallProbePassed"])
        self.assertFalse(report["realAccountTested"])
        with zipfile.ZipFile(self.output / report["archive"]) as archive:
            self.assertEqual(len(archive.namelist()), len(self.manifest["files"]) + 2)
            self.assertFalse(any("session" in name or "cookies" in name for name in archive.namelist()))
        self.assertEqual(module.digest(self.output / report["archive"]), report["sha256"])

    def test_public_repository_is_rejected_without_creating_output(self):
        with self.assertRaisesRegex(ValueError, "Private"):
            self.run_export(private=False)
        self.assertFalse(self.output.exists())

    def test_missing_resume_check_cannot_pass_with_outer_ok(self):
        del self.smoke["resumeAction"]["nativeHTTPExchangeFixture"]
        self.write()
        with self.assertRaisesRegex(ValueError, "checks must pass"):
            self.run_export()
        self.assertFalse(self.output.exists())

    def test_chat_fixture_without_windows_adapter_cannot_be_exported(self):
        del self.smoke["chatAction"]["windowsAdapterPath"]
        self.write()
        with self.assertRaisesRegex(ValueError, "checks must pass"):
            self.run_export()
        self.assertFalse(self.output.exists())

    def test_modified_binary_discards_archive(self):
        (self.package / "赫逸工作台.exe").write_bytes(b"modified")
        with self.assertRaisesRegex(ValueError, "differs"):
            self.run_export()
        self.assertEqual(list(self.output.glob("*.zip")), [])

    def test_path_escape_rejected_before_writing(self):
        for name in ["../outside", "/absolute", "C:/absolute", "a//b", "a/./b"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                module.source_file(self.package, name)

    def test_symlink_to_profile_rejected(self):
        (self.root / "profile.enc").write_text("private")
        link = self.package / "session-link"
        try:
            link.symlink_to(self.root / "profile.enc")
        except OSError:
            self.skipTest("Test account cannot create symlinks")
        with self.assertRaisesRegex(ValueError, "Linked"):
            module.source_file(self.package, "session-link")


if __name__ == "__main__":
    unittest.main()
