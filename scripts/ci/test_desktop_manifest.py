import hashlib
import json
import os
from pathlib import Path
import runpy
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile


class DesktopManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.script = root / "scripts/ci/desktop-manifest.py"
        self.script.parent.mkdir(parents=True)
        shutil.copyfile(Path(__file__).with_name("desktop-manifest.py"), self.script)
        self.desktop = root / "apps/hr-desktop"
        self.release = self.desktop / "release"
        self.release.mkdir(parents=True)
        self.smoke = self.desktop / "test-results/packaged/smoke.json"
        self.smoke.parent.mkdir(parents=True)
        self.smoke.write_text(json.dumps({"ok": True, "packaged": True, "platform": "win32"}))
        (self.desktop / "package.json").write_text('{"version":"0.1.15"}')
        self.installer = self.release / "Heyi-Recruiter-0.1.15-windows-x64-Setup.exe"
        self.installer.write_bytes(b"isolated mock installer")

    def execute(self, requested="a" * 40):
        with patch.dict(os.environ, {"CIRCLE_SHA1": requested}), \
                patch("subprocess.check_output", return_value="a" * 40 + "\n"):
            runpy.run_path(str(self.script), run_name="__main__")

    def test_verified_checksum_commit_and_qa_archive(self):
        self.execute()
        manifest = json.loads((self.release / "verified-Windows.json").read_text())
        self.assertEqual(manifest["commit"], "a" * 40)
        self.assertEqual(manifest["artifacts"][self.installer.name]["sha256"],
                         hashlib.sha256(self.installer.read_bytes()).hexdigest())
        with zipfile.ZipFile(self.release / "verified-Windows-qa.zip") as archive:
            self.assertEqual(archive.namelist(), ["packaged/smoke.json"])
            self.assertIsNone(archive.testzip())

    def test_failed_or_source_smoke_never_certifies_installer(self):
        for smoke in ({"ok": False, "platform": "win32", "packaged": True},
                      {"ok": True, "platform": "win32", "packaged": False},
                      {"ok": True, "platform": "darwin", "packaged": True}):
            with self.subTest(smoke=smoke):
                self.smoke.write_text(json.dumps(smoke))
                with self.assertRaises(SystemExit):
                    self.execute()
                self.assertFalse((self.release / "verified-Windows.json").exists())

    def test_wrong_commit_never_certifies_installer(self):
        with self.assertRaises(SystemExit):
            self.execute("b" * 40)
        self.assertFalse((self.release / "verified-Windows.json").exists())


if __name__ == "__main__":
    unittest.main()
