import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import build_windows_installer as installer
from windows_installation import RunningGuard


class InstallerPayloadTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.package = Path(temporary.name).resolve()
        self.files = {}
        for name in (*installer.REQUIRED, "native-client/boss-zhipin.exe"):
            source = self.package / name
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(b"isolated application fixture")
            self.files[name] = hashlib.sha256(source.read_bytes()).hexdigest()
        original = self.files["native-client/boss-zhipin.exe"]
        self.pin = patch.object(installer, "PIN", {"boss-zhipin.exe": original})
        self.pin.start()
        self.addCleanup(self.pin.stop)
        self.write_manifest()

    def write_manifest(self):
        (self.package / "heyi-package.json").write_text(json.dumps({
            "schemaVersion": 1, "experimental": True, "realAccountTested": False,
            "originalResourcesModified": False, "files": self.files,
        }))

    def test_only_manifest_files_can_enter_the_installer(self):
        (self.package / "private-session.enc").write_bytes(b"must not ship")
        entries = installer.payload(self.package)
        self.assertEqual({name for name, _, _ in entries}, set(self.files) | {"heyi-package.json"})
        (self.package / installer.REQUIRED[0]).write_bytes(b"modified")
        with self.assertRaisesRegex(ValueError, "differs"):
            installer.payload(self.package)

    def test_path_escape_and_installer_directive_injection_are_rejected(self):
        for name in ('../outside', 'name"; Flags: external', 'file\n[Run]', '{userappdata}/session'):
            with self.subTest(name=name):
                self.files[name] = "0" * 64
                self.write_manifest()
                with self.assertRaises(ValueError):
                    installer.payload(self.package)
                del self.files[name]

    def test_case_collision_and_modified_original_are_rejected(self):
        self.files["AI BOSS.EXE"] = self.files[installer.REQUIRED[0]]
        self.write_manifest()
        with self.assertRaises(ValueError):
            installer.payload(self.package)
        del self.files["AI BOSS.EXE"]
        self.files["native-client/boss-zhipin.exe"] = "0" * 64
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "Original"):
            installer.payload(self.package)


class RunningGuardTests(unittest.TestCase):
    def api(self, updating=None, last_error=2, existing=False):
        api = MagicMock()
        api.CreateMutexW.return_value = 123
        api.OpenMutexW.return_value = updating
        api.CreateEventW.return_value = 789
        factory = patch("ctypes.WinDLL", return_value=api, create=True)
        error = patch("ctypes.get_last_error",
                      side_effect=[183 if existing else 0, last_error], create=True)
        factory.start()
        error.start()
        self.addCleanup(factory.stop)
        self.addCleanup(error.stop)
        return api

    def test_handle_covers_runtime_and_closes_on_failure(self):
        api = self.api()
        with self.assertRaisesRegex(RuntimeError, "runtime"):
            with RunningGuard():
                api.CloseHandle.assert_not_called()
                raise RuntimeError("runtime failure")
        self.assertEqual([call.args[0] for call in api.CloseHandle.call_args_list], [789, 123])

    def test_second_entry_signals_existing_process_instead_of_starting_another(self):
        api = self.api(existing=True)
        with RunningGuard() as guard:
            self.assertTrue(guard.already_running)
            guard.activate()
            api.SetEvent.assert_called_once_with(789)
            api.WaitForSingleObject.return_value = 0
            self.assertTrue(guard.activation_requested())
            api.WaitForSingleObject.return_value = 258
            self.assertFalse(guard.activation_requested())

    def test_update_or_unreadable_guard_prevents_startup_and_releases_handle(self):
        api = self.api(updating=456)
        with self.assertRaisesRegex(RuntimeError, "正在安装或更新"):
            with RunningGuard():
                self.fail("must not launch while updating")
        self.assertEqual([call.args[0] for call in api.CloseHandle.call_args_list], [456, 123])
        api.OpenMutexW.return_value = None
        with patch("ctypes.get_last_error", return_value=5, create=True):
            with self.assertRaises(OSError):
                with RunningGuard():
                    self.fail("must not ignore a guard access failure")


if __name__ == "__main__":
    unittest.main()
