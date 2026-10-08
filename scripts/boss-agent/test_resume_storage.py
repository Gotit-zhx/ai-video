import os
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest import mock
import resume_storage as storage


class ResumeStorageTest(unittest.TestCase):
    def test_private_local_storage_survives_oss_shutdown_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as root, mock.patch.dict(os.environ, {"APP_HR_LOCAL_RESUME_DIR": root, "APP_HR_OSS_WRITE_ENABLED": "0"}), mock.patch.object(storage, "bucket") as bucket:
            identifier = str(uuid.uuid4())
            raw = b"%PDF-1.4 fixture"
            key = storage.store("org1", identifier, raw, "pdf")
            self.assertEqual(storage.store("org1", identifier, raw, "pdf"), key)
            self.assertEqual(storage.download("org1", key), raw)
            self.assertEqual(storage.local_path("org1", key).stat().st_mode & 0o777, 0o600)
            with self.assertRaises(ValueError): storage.download("org2", key)
            with self.assertRaises(ValueError): storage.store("org1", identifier, b"%PDF-1.4 changed", "pdf")
            with self.assertRaises(ValueError): storage.store("org1", "../escape", raw, "pdf")
            bucket.assert_not_called()

    def test_legacy_object_read_and_symlink_escape(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as outside, mock.patch.dict(os.environ, {"APP_HR_LOCAL_RESUME_DIR": root}), mock.patch.object(storage, "bucket") as bucket:
            key = storage.prefix("org1") + str(uuid.uuid4()) + ".pdf"
            bucket.return_value.get_object.return_value.read.return_value = b"old"
            self.assertEqual(storage.download("org1", key), b"old")
            bucket.return_value.get_object.return_value.close.assert_called_once()
            parent = Path(root) / storage.prefix("org1")
            parent.parent.mkdir(parents=True)
            parent.symlink_to(outside, target_is_directory=True)
            with self.assertRaises(ValueError): storage.local_path("org1", key)
