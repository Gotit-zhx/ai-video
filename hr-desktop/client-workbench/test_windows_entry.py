import unittest

from windows_entry import self_test_requested


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


if __name__ == "__main__":
    unittest.main()
