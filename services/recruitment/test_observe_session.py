"""Pure-state tests: no CDP, browser, account, or production data access."""
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "observe_session", Path(__file__).with_name("observe_session.py"))
observer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(observer)


class ObservationTest(unittest.TestCase):
    def setUp(self):
        self.events = []
        self.observation = observer.Observation(1000, 3600, self.events.append)

    def test_requires_session_then_two_missing_samples_and_preserves_first_time(self):
        watch = self.observation
        watch.sample(set(), ("PC_LOGIN_RESTRICTED", 10, ""), 1000)
        self.assertEqual([], self.events)
        watch.sample({"synthetic-secret"}, ("", None, ""), 1005)
        watch.sample(set(), ("", None, ""), 1010)
        self.assertEqual("session_present", watch.state)
        watch.sample(set(), ("", None, ""), 1015)
        self.assertEqual("session_lost", watch.state)
        self.assertEqual(1010, watch.lost_at)
        self.assertEqual("BROWSER_SESSION_LOST", self.events[-1]["event"])
        self.assertNotIn("synthetic-secret", json.dumps(self.events) + json.dumps(watch.status(1015)))

    def test_unavailable_browser_is_unknown_and_never_reported_as_logout(self):
        watch = self.observation
        watch.sample({"synthetic"}, ("", None, ""), 1000)
        watch.sample(set(), ("", None, ""), 1005)
        watch.sample(None, ("", None, ""), 1010)
        self.assertEqual("unknown", watch.status(1010)["state"])
        watch.sample(set(), ("", None, ""), 1015)
        self.assertIsNone(watch.lost_at)
        watch.sample({"synthetic"}, ("", None, ""), 1020)
        self.assertNotIn("BROWSER_SESSION_LOST", [e["event"] for e in self.events])

    def test_cookie_rotation_is_distinct_from_loss_and_disconnect_is_local(self):
        watch = self.observation
        watch.sample({"one"}, ("", None, ""), 1000)
        watch.sample({"two"}, ("", None, ""), 1005)
        self.assertEqual("PRIMARY_SESSION_CHANGED", self.events[-1]["event"])
        watch.sample(set(), ("", None, ""), 1010, disconnected=True)
        watch.sample(set(), ("", None, ""), 1015, disconnected=True)
        self.assertEqual("LOCAL_DISCONNECT", self.events[-1]["event"])

    def test_old_hold_is_baseline_and_new_hold_is_not_claimed_to_be_logout(self):
        watch = self.observation
        watch.sample(set(), ("PC_LOGIN_RESTRICTED", 10, "old"), 1000)
        self.assertEqual([], self.events)
        watch.sample(set(), ("PC_LOGIN_RESTRICTED", 20, "new"), 1005)
        self.assertEqual("PLATFORM_HOLD_RECORDED", self.events[-1]["event"])
        self.assertFalse(self.events[-1]["had_browser_session"])
        self.assertIsNone(watch.lost_at)


if __name__ == "__main__":
    unittest.main()
