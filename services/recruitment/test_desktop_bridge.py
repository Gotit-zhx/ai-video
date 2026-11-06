import concurrent.futures
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
import desktop_bridge as bridge

USER = {"username": "13800000000", "id": "test-user", "organizationCode": "heyi",
        "roles": ["ADMIN"], "platformAdmin": True}


class PairingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.original = bridge.DB
        bridge.DB = Path(self.temp.name) / "pair.sqlite3"
        self.device = str(uuid.uuid4())

    def tearDown(self):
        bridge.DB = self.original
        self.temp.cleanup()

    def test_pair_once_and_switch_device_revokes_old_lease(self):
        ticket = bridge.issue(USER)["ticket"]
        first = bridge.redeem(ticket, self.device)
        self.assertEqual(bridge.check(first["token"], self.device)["user"]["id"], "test-user")
        with self.assertRaises(ValueError):
            bridge.redeem(ticket, self.device)
        second_device = str(uuid.uuid4())
        second = bridge.redeem(bridge.issue(USER)["ticket"], second_device)
        with self.assertRaises(ValueError):
            bridge.check(first["token"], self.device)
        self.assertEqual(bridge.check(second["token"], second_device)["user"]["id"], "test-user")

    def test_expired_ticket_and_lease_cannot_be_used(self):
        ticket = bridge.issue(USER)["ticket"]
        with patch("desktop_bridge.time.time", return_value=time.time() + 61):
            with self.assertRaises(ValueError):
                bridge.redeem(ticket, self.device)
        pair = bridge.redeem(bridge.issue(USER)["ticket"], self.device)
        with patch("desktop_bridge.time.time", return_value=time.time() + 1801):
            with self.assertRaises(ValueError):
                bridge.check(pair["token"], self.device)

    def test_auto_renewal_cannot_take_another_devices_live_lease(self):
        first = bridge.redeem(bridge.issue(USER)["ticket"], self.device)
        another_device = str(uuid.uuid4())
        with self.assertRaises(bridge.DeviceConflict):
            bridge.redeem(bridge.issue(USER, automatic=True)["ticket"], another_device)
        self.assertEqual(bridge.check(first["token"], self.device)["user"]["id"], "test-user")
        renewed = bridge.redeem(bridge.issue(USER, automatic=True)["ticket"], self.device)
        self.assertEqual(bridge.check(renewed["token"], self.device)["user"]["id"], "test-user")

    def test_wrong_role_and_untrusted_fields_denied(self):
        with self.assertRaises(ValueError):
            bridge.issue({**USER, "platformAdmin": False})
        with self.assertRaises(ValueError):
            bridge.redeem("malformed", self.device)
        with self.assertRaises(ValueError):
            bridge.check("malformed", self.device)

    def test_ticket_consumption_is_atomic(self):
        ticket = bridge.issue(USER)["ticket"]
        def attempt(_):
            try:
                bridge.redeem(ticket, self.device)
                return True
            except ValueError:
                return False
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            self.assertEqual(sum(pool.map(attempt, range(2))), 1)

    def test_pairing_storage_contains_no_plaintext_tokens(self):
        ticket = bridge.issue(USER)["ticket"]
        pair = bridge.redeem(ticket, self.device)
        with bridge.database() as db:
            values = [tuple(row) for row in db.execute("SELECT * FROM leases")]
        self.assertNotIn(pair["token"], str(values))
        self.assertNotIn(ticket, str(values))


if __name__ == "__main__":
    unittest.main()
