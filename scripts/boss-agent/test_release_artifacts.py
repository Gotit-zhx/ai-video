import importlib.util
import os
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "release_artifacts", Path(__file__).with_name("deduplicate-release-artifacts.py"))
artifacts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(artifacts)


class ArtifactTests(unittest.TestCase):
    def test_only_identical_old_artifacts_are_deduplicated_and_all_paths_survive(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for name, content in (("first.jar", b"same"), ("second.jar", b"same"),
                                  ("different.jar", b"else"), ("data.sqlite3", b"same"),
                                  ("recent.jar", b"same")):
                path = root / name
                path.write_bytes(content)
                os.utime(path, (1, 1))
            os.utime(root / "recent.jar", None)
            (root / "external.jar").symlink_to(root / "different.jar")
            dry = artifacts.deduplicate([root], minimum_bytes=1, older_than=10)
            self.assertEqual(dry["duplicateFiles"], 1)
            self.assertNotEqual((root / "first.jar").stat().st_ino, (root / "second.jar").stat().st_ino)
            result = artifacts.deduplicate([root], apply=True, minimum_bytes=1, older_than=10)
            self.assertEqual(result["reclaimedBytes"], 4)
            self.assertEqual((root / "first.jar").stat().st_ino, (root / "second.jar").stat().st_ino)
            self.assertEqual((root / "different.jar").read_bytes(), b"else")
            for name in ("first.jar", "second.jar", "data.sqlite3", "recent.jar"):
                self.assertEqual((root / name).read_bytes(), b"same")
            self.assertTrue((root / "external.jar").is_symlink())
            self.assertEqual(artifacts.deduplicate([root], apply=True, minimum_bytes=1,
                                                  older_than=10)["duplicateFiles"], 0)

    def test_different_permissions_and_symlinked_directories_are_untouched(self):
        with tempfile.TemporaryDirectory() as folder, tempfile.TemporaryDirectory() as outside:
            root = Path(folder)
            first, second = root / "a.jar", root / "b.jar"
            first.write_bytes(b"same")
            second.write_bytes(b"same")
            first.chmod(0o600)
            second.chmod(0o644)
            (root / "outside").symlink_to(outside, target_is_directory=True)
            (Path(outside) / "c.jar").write_bytes(b"same")
            result = artifacts.deduplicate([root], apply=True, minimum_bytes=1,
                                          older_than=float("inf"))
            self.assertEqual(result["duplicateFiles"], 0)


if __name__ == "__main__":
    unittest.main()
