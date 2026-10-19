import unittest
from unittest.mock import MagicMock, patch

from windows_entry import main, self_test_requested


class WindowsEntryTests(unittest.TestCase):
    def test_normal_entry_does_not_enable_test_mode_from_environment(self):
        self.assertFalse(self_test_requested([], {
            "HEYI_WORKBENCH_TEST_DATA": "/unused",
            "HEYI_WORKBENCH_TEST_PORT": "0",
        }))

    def test_test_forwarding_requires_explicit_flag_and_complete_isolation(self):
        environment = {
            "HEYI_NATIVE_PROBE_ROOT": "isolated-root",
            "HEYI_WORKBENCH_TEST_DATA": "fresh-profile",
            "HEYI_WORKBENCH_TEST_OUTPUT": "fresh-report",
            "HEYI_WORKBENCH_TEST_PORT": "0",
        }
        self.assertTrue(self_test_requested(["--self-test"], environment))
        for key in environment:
            with self.subTest(key=key), self.assertRaises(ValueError):
                self_test_requested(["--self-test"], {k: v for k, v in environment.items() if k != key})
        with self.assertRaises(ValueError):
            self_test_requested(["--arbitrary"], environment)

    def test_repeated_click_never_creates_another_controller_or_native_window(self):
        guard = MagicMock()
        guard.already_running = True
        manager = MagicMock()
        manager.__enter__.return_value = guard
        with patch("windows_entry.sys.platform", "win32"), \
                patch("windows_entry.sys.frozen", True, create=True), \
                patch("windows_entry.sys.argv", ["Ai boss.exe"]), \
                patch("windows_entry.RunningGuard", return_value=manager), \
                patch("windows_entry.StartupWindow") as window, \
                patch("windows_entry.subprocess.Popen") as process:
            self.assertEqual(main(), 0)
        guard.activate.assert_called_once()
        window.assert_not_called()
        process.assert_not_called()


if __name__ == "__main__":
    unittest.main()
