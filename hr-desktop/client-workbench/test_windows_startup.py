import json
from pathlib import Path
import tempfile
import unittest

from windows_startup import read_current_status, StartupWindow


class StartupStatusTests(unittest.TestCase):
    def test_old_and_partial_status_cannot_open_a_previous_native_process(self):
        with tempfile.TemporaryDirectory() as temporary:
            file = Path(temporary) / "status.json"
            self.assertEqual(read_current_status(file, "new"), {})
            file.write_text('{"launchId":"new",')
            self.assertEqual(read_current_status(file, "new"), {})
            file.write_text(json.dumps({"launchId": "old", "nativePid": 123, "stage": "ready"}))
            self.assertEqual(read_current_status(file, "new"), {})
            file.write_text(json.dumps({"launchId": "new", "nativePid": 456, "stage": "ready"}))
            self.assertEqual(read_current_status(file, "new")["nativePid"], 456)

    def test_foreground_rechecks_hwnd_ownership_and_restores_minimized_only(self):
        from unittest.mock import Mock
        ui = StartupWindow.__new__(StartupWindow)
        ui.user = Mock()
        ui._owned = Mock(return_value=False)
        ui.activation_count = 0
        self.assertFalse(ui.foreground({"windowHandle": "123"}))
        ui.user.ShowWindow.assert_not_called()
        ui._owned.return_value = True
        ui.user.IsIconic.return_value = True
        self.assertTrue(ui.foreground({"windowHandle": "123"}))
        ui.user.ShowWindow.assert_called_with(123, 9)
        ui.user.IsIconic.return_value = False
        self.assertTrue(ui.foreground({"windowHandle": "123"}))
        ui.user.ShowWindow.assert_called_with(123, 5)


if __name__ == "__main__":
    unittest.main()
