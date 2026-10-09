import importlib.util
import io
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("trigger_backup", Path(__file__).with_name("trigger-backup.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BackupTriggerTests(unittest.TestCase):
    def test_explicit_branch_and_opt_in_parameter(self):
        calls = []

        def opener(request, timeout):
            calls.append(request)
            self.assertEqual(timeout, 30)
            return io.StringIO('{"id":"pipeline-1"}')

        self.assertEqual(module.trigger("codex/test-current-20260831", "test-token", opener), "pipeline-1")
        self.assertEqual(len(calls), 1)
        self.assertEqual(json.loads(calls[0].data),
                         {"branch": "codex/test-current-20260831", "parameters": {"desktop_build": True}})
        self.assertEqual(calls[0].method, "POST")

    def test_ambiguous_timeout_never_allocates_second_vm(self):
        calls = []

        def opener(request, timeout):
            calls.append(request)
            raise TimeoutError()

        with self.assertRaises(TimeoutError):
            module.trigger("codex/test-current-20260831", "test-token", opener)
        self.assertEqual(len(calls), 1)

    def test_missing_pipeline_id_is_not_success(self):
        with self.assertRaises(ValueError):
            module.trigger("codex/test-current-20260831", "test-token",
                           lambda request, timeout: io.StringIO("{}"))


if __name__ == "__main__":
    unittest.main()
