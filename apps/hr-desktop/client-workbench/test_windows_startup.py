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

    def test_hidden_chromium_top_level_windows_are_observed_independent_of_suffix(self):
        from unittest.mock import Mock
        for window_class in ("Chrome_WidgetWin_0", "Chrome_WidgetWin_1"):
            with self.subTest(window_class=window_class):
                ui = StartupWindow.__new__(StartupWindow)
                ui.ready = ui.native_window_seen = False
                ui.observed_classes = set()
                ui.user = Mock()
                ui._owned = Mock(return_value=True)
                ui.user.IsWindowVisible.return_value = False
                ui.user.GetClassNameW.side_effect = lambda hwnd, buffer, size: setattr(buffer, "value", window_class)
                ui._hide(123)
                self.assertFalse(ui.native_window_seen, "SHOW alone may be a helper window")
                ui._hide(123, top_level=True)
                self.assertTrue(ui.native_window_seen)
                ui.user.ShowWindow.assert_not_called()
                ui.native_window_seen = False
                ui._owned.return_value = False
                ui._hide(456, top_level=True)
                self.assertFalse(ui.native_window_seen, "Another process must never release the gate")


if __name__ == "__main__":
    unittest.main()
