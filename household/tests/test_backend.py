import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from zoneinfo import ZoneInfo

os.environ["HOMECARE_NO_DEFAULT_APP"] = "1"
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import server

PASSWORD = "correct-private-test-password"
HEADERS = {"X-Requested-With": "HomeCare"}


class HouseholdTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = server.create_app({"TESTING": True, "SCHEDULER_ENABLED": False,
                                      "DATA_DIR": self.tmp.name, "HOMECARE_PASSWORD": PASSWORD})
        self.client = self.app.test_client()
        self.login()
        self.state = self.client.get("/api/state").get_json()
        self.today = self.state["today"]
        self.task = next(t for t in self.state["tasks"] if t["frequency"] == "daily")

    def tearDown(self):
        self.tmp.cleanup()

    def login(self, client=None, password=PASSWORD):
        return (client or self.client).post("/api/login", json={"password": password}, headers=HEADERS)

    def write(self, path, body, client=None, method="put"):
        return getattr(client or self.client, method)(path, json=body, headers=HEADERS)

    def check(self, step=0, done=True, day=None, task=None, client=None):
        task = task or self.task
        return self.write("/api/progress", {"date": day or self.today, "taskId": task["id"], "stepId": task["steps"][step]["id"], "done": done}, client)

    def connection(self):
        return self.app.extensions["homecare"]["db"]()

    def permit_past(self):
        with self.app.app_context(), self.connection() as connection:
            connection.execute("UPDATE tasks SET created_date=?", ((datetime.fromisoformat(self.today) - timedelta(days=30)).date().isoformat(),))

    def test_auth_cookie_and_logout_revoke_session(self):
        anonymous = self.app.test_client()
        self.assertEqual(anonymous.get("/api/state").status_code, 401)
        login = self.login(anonymous)
        cookie = login.headers["Set-Cookie"]
        self.assertIn("HttpOnly", cookie)
        self.assertIn("SameSite=Lax", cookie)
        self.assertIn("Max-Age=7776000", cookie)
        token = anonymous.get_cookie(server.COOKIE_NAME).value
        self.assertEqual(anonymous.get("/api/state").status_code, 200)
        self.assertEqual(self.write("/api/logout", {}, anonymous, "post").status_code, 200)
        anonymous.set_cookie(server.COOKIE_NAME, token)
        self.assertEqual(anonymous.get("/api/state").status_code, 401)

    def test_csrf_and_invalid_json_rejected(self):
        self.assertEqual(self.client.post("/api/login", json={"password": PASSWORD}).status_code, 403)
        self.assertEqual(self.client.post("/api/login", json={"password": PASSWORD}, headers={**HEADERS, "Origin": "https://attacker.example"}).status_code, 403)
        self.assertEqual(self.client.put("/api/settings", data="not json", headers=HEADERS).status_code, 415)
        self.assertEqual(self.client.put("/api/settings", json=[], headers=HEADERS).status_code, 400)

    def test_rate_limit_persisted_and_bounded(self):
        anonymous = self.app.test_client()
        for _ in range(8):
            self.assertEqual(self.login(anonymous, "incorrect").status_code, 401)
        response = self.login(anonymous)
        self.assertEqual(response.status_code, 429)
        self.assertEqual(response.headers["Retry-After"], "900")
        self.assertEqual(self.client.get("/api/state").status_code, 200)

    def test_steps_do_not_leak_across_dates_and_unset_is_idempotent(self):
        self.permit_past()
        yesterday = (datetime.fromisoformat(self.today) - timedelta(days=1)).date().isoformat()
        self.assertEqual(self.check().status_code, 200)
        self.assertNotIn(self.task["id"], self.client.get("/api/state?date=" + yesterday).get_json()["progress"])
        self.assertEqual(self.check(1, day=yesterday).status_code, 200)
        current = self.client.get("/api/state").get_json()["progress"][self.task["id"]]["steps"]
        self.assertEqual(current, [self.task["steps"][0]["id"]])
        self.check(done=False)
        self.check(done=False)
        self.assertNotIn(self.task["id"], self.client.get("/api/state").get_json()["progress"])

    def test_parallel_step_updates_preserve_each_other(self):
        token = self.client.get_cookie(server.COOKIE_NAME).value
        def set_step(index):
            client = self.app.test_client()
            client.set_cookie(server.COOKIE_NAME, token)
            return self.check(index, client=client).status_code
        with ThreadPoolExecutor(max_workers=2) as executor:
            statuses = list(executor.map(set_step, [0, 1]))
        self.assertEqual(statuses, [200, 200])
        current = self.client.get("/api/state").get_json()
        self.assertEqual(set(current["progress"][self.task["id"]]["steps"]), {s["id"] for s in self.task["steps"]})
        self.assertEqual(current["history"][-1]["done"], 1)
        self.assertEqual(current["history"][-1]["minutes"], self.task["minutes"])

    def test_skip_preserves_steps_and_check_clears_skip(self):
        self.check()
        before = self.client.get("/api/state").get_json()["history"][-1]["total"]
        self.write("/api/skip", {"date": self.today, "taskId": self.task["id"], "skipped": True})
        state = self.client.get("/api/state").get_json()
        self.assertTrue(state["progress"][self.task["id"]]["skipped"])
        self.assertEqual(len(state["progress"][self.task["id"]]["steps"]), 1)
        self.assertEqual(state["history"][-1]["total"], before - 1)
        self.check(1)
        self.assertFalse(self.client.get("/api/state").get_json()["progress"][self.task["id"]]["skipped"])

    def test_edit_retains_unchanged_step_ids_and_history(self):
        self.permit_past()
        yesterday = (datetime.fromisoformat(self.today) - timedelta(days=1)).date().isoformat()
        for i in range(len(self.task["steps"])):
            self.check(i, day=yesterday)
        old = self.task["steps"][0]
        response = self.write("/api/tasks/" + self.task["id"], {"steps": [old["text"], "检查床下没有拖到地面的被角"], "minutes": 7})
        self.assertEqual(response.status_code, 200)
        revised = response.get_json()["task"]
        self.assertEqual(revised["steps"][0]["id"], old["id"])
        self.assertNotEqual(revised["steps"][1]["id"], self.task["steps"][1]["id"])
        previous_history = self.client.get("/api/state").get_json()["history"][-2]
        self.assertEqual(previous_history["done"], 1)
        self.assertEqual(previous_history["minutes"], self.task["minutes"])

    def test_weekly_matching_and_write_validation(self):
        day = datetime.fromisoformat(self.today).date()
        response = self.write("/api/tasks", {"title": "每周检查", "frequency": "weekly", "weekdays": [(day.weekday() + 1) % 7], "steps": ["完成检查"]}, method="post")
        self.assertEqual(response.status_code, 201)
        task = response.get_json()["task"]
        self.assertEqual(self.check(task=task).status_code, 400)
        due_count = sum(t["frequency"] == "daily" or day.weekday() in t["weekdays"] for t in self.state["tasks"])
        self.assertEqual(self.client.get("/api/state").get_json()["history"][-1]["total"], due_count)
        self.write("/api/tasks/" + task["id"], {"weekdays": [day.weekday()]})
        self.assertEqual(self.check(task=task).status_code, 200)

    def test_weekly_reschedule_keeps_prior_due_days(self):
        today = datetime.fromisoformat(self.today).date()
        old_day = today - timedelta(days=2)
        new_day = today - timedelta(days=1)
        response = self.write("/api/tasks", {"title": "每周检查", "frequency": "weekly", "weekdays": [old_day.weekday()], "steps": ["完成检查"]}, method="post")
        task = response.get_json()["task"]
        self.permit_past()
        self.write("/api/tasks/" + task["id"], {"weekdays": [new_day.weekday()]})
        before = self.client.get("/api/state?date=" + old_day.isoformat()).get_json()["tasks"]
        after = self.client.get("/api/state?date=" + new_day.isoformat()).get_json()["tasks"]
        self.assertIn(task["id"], {t["id"] for t in before})
        self.assertNotIn(task["id"], {t["id"] for t in after})

    def test_rejects_invalid_inputs_without_mutations(self):
        for changes in ({"minutes": True}, {"minutes": 0}, {"steps": []}, {"steps": ["重复", "重复"]}, {"title": "\n"}, {"weekdays": [True]}, {"frequency": "weekly", "weekdays": []}, {"essential": "true"}, {"period": []}, {"frequency": {}}):
            self.assertEqual(self.write("/api/tasks/" + self.task["id"], changes).status_code, 400, changes)
        for value in ("2026-02-30", "2026-1-01", "no-date"):
            self.assertEqual(self.client.get("/api/state?date=" + value).status_code, 400)
        tomorrow = (datetime.fromisoformat(self.today) + timedelta(days=1)).date().isoformat()
        too_old = (datetime.fromisoformat(self.today) - timedelta(days=31)).date().isoformat()
        self.assertEqual(self.check(day=tomorrow).status_code, 400)
        self.assertEqual(self.check(day=too_old).status_code, 400)
        self.assertEqual(self.check(done="yes").status_code, 400)
        self.assertEqual(self.write("/api/settings", {"timezone": "Mars/Olympus"}).status_code, 400)
        self.assertEqual(self.write("/api/settings", {"reminderTimes": {"morning": "99:99"}}).status_code, 400)

    def test_archives_preserve_backup_and_never_export_secrets(self):
        self.check()
        self.assertEqual(self.write("/api/tasks/" + self.task["id"], {}, method="delete").status_code, 200)
        state = self.client.get("/api/state").get_json()
        self.assertNotIn(self.task["id"], {t["id"] for t in state["tasks"]})
        response = self.client.get("/api/export")
        backup = response.get_json()
        self.assertEqual(len(backup["progress"]), 1)
        self.assertFalse(next(t for t in backup["tasks"] if t["id"] == self.task["id"])["enabled"])
        text = response.get_data(as_text=True)
        for secret in (PASSWORD, "token_hash", "vapid", "password", '"auth"', '"hash"'):
            self.assertNotIn(secret, text)
        self.assertEqual(response.headers["Cache-Control"], "no-store")

    def test_calendar_timezone_and_alarms(self):
        self.write("/api/settings", {"timezone": "America/New_York"})
        response = self.client.get("/api/reminders.ics")
        text = response.get_data(as_text=True)
        self.assertEqual(response.status_code, 200)
        self.assertIn("TZID:America/New_York", text)
        self.assertIn("DTSTART;TZID=America/New_York:", text)
        self.assertIn("BEGIN:DAYLIGHT", text)
        self.assertIn("TZOFFSETTO:-0400", text)
        self.assertIn("TZOFFSETTO:-0500", text)
        self.assertEqual(text.count("BEGIN:VALARM"), 2)
        self.assertTrue(all(len(line.encode()) <= 75 for line in text.split("\r\n")))

    def subscription(self):
        encode = lambda b: base64.urlsafe_b64encode(b).decode().rstrip("=")
        return {"endpoint": "https://fcm.googleapis.com/fcm/send/test-device", "keys": {"p256dh": encode(bytes([4]) + bytes(64)), "auth": encode(bytes(16))}}

    def test_push_validation_blocks_arbitrary_urls(self):
        subscription = self.subscription()
        for endpoint in ("http://127.0.0.1:8080/private", "https://attacker.example/", "https://fcm.googleapis.com@127.0.0.1/", "https://fcm.googleapis.com.evil.example/"):
            subscription["endpoint"] = endpoint
            self.assertEqual(self.write("/api/push/subscribe", subscription, method="post").status_code, 400)

    def test_reminders_opt_in_pending_only_and_no_duplicate_after_restart(self):
        subscription = self.subscription()
        self.assertEqual(self.write("/api/push/subscribe", subscription, method="post").status_code, 200)
        at = datetime.fromisoformat(self.today + "T20:30:00").replace(tzinfo=ZoneInfo("Asia/Shanghai"))
        tick = self.app.extensions["homecare"]["scheduler_tick"]
        with patch.object(server, "webpush") as delivery:
            with self.app.app_context():
                self.assertEqual(tick(at), 0)
            self.write("/api/settings", {"remindersEnabled": True})
            with self.app.app_context():
                self.assertEqual(tick(at), 1)
                self.assertEqual(tick(at), 0)
            recreated = server.create_app({"TESTING": True, "SCHEDULER_ENABLED": False, "DATA_DIR": self.tmp.name, "HOMECARE_PASSWORD": PASSWORD})
            with recreated.app_context():
                self.assertEqual(recreated.extensions["homecare"]["scheduler_tick"](at), 0)
            self.assertEqual(delivery.call_count, 1)
            self.assertEqual(json.loads(delivery.call_args.kwargs["data"])["url"], "/")
        tomorrow = at + timedelta(days=1)
        # No pending evening work means no notification, even at the scheduled minute.
        with self.app.app_context(), self.connection() as connection:
            for task in self.state["tasks"]:
                if task["period"] == "evening":
                    connection.execute("INSERT INTO skips(date,task_id,skipped) VALUES(?,?,1)", (tomorrow.date().isoformat(), task["id"]))
        with patch.object(server, "webpush") as delivery, self.app.app_context():
            self.assertEqual(tick(tomorrow), 0)
            delivery.assert_not_called()

    def test_prefix_and_secure_cookie_configuration(self):
        prefixed = server.create_app({"TESTING": True, "SCHEDULER_ENABLED": False, "DATA_DIR": self.tmp.name,
                                      "HOMECARE_PASSWORD": PASSWORD, "PUBLIC_URL": "https://38.54.88.139/chores/", "TRUST_PROXY": True})
        client = prefixed.test_client()
        response = client.post("/api/login", json={"password": PASSWORD}, headers={**HEADERS, "Host": "38.54.88.139", "Origin": "https://38.54.88.139", "X-Forwarded-Proto": "https", "X-Forwarded-Prefix": "/chores"})
        self.assertEqual(response.status_code, 200)
        self.assertIn("Path=/chores/", response.headers["Set-Cookie"])
        self.assertIn("Secure", response.headers["Set-Cookie"])
        token = client.get_cookie(server.COOKIE_NAME, domain="38.54.88.139", path="/chores/").value
        client.set_cookie(server.COOKIE_NAME, token, domain="38.54.88.139", path="/")
        calendar = client.get("/api/reminders.ics", base_url="https://38.54.88.139", headers={"X-Forwarded-Proto": "https", "X-Forwarded-Prefix": "/chores"})
        self.assertEqual(calendar.status_code, 200)
        self.assertIn("URL:https://38.54.88.139/chores/", calendar.get_data(as_text=True))

    def test_today_history_matches_checklist_after_steps_edit(self):
        for i in range(len(self.task["steps"])):
            self.check(i)
        self.assertEqual(self.client.get("/api/state").get_json()["history"][-1]["done"], 1)
        self.write("/api/tasks/" + self.task["id"], {"steps": [s["text"] for s in self.task["steps"]] + ["临睡前检查被角"]})
        state = self.client.get("/api/state").get_json()
        task = next(t for t in state["tasks"] if t["id"] == self.task["id"])
        self.assertEqual(state["history"][-1]["done"], 0)
        self.check(2, task=task)
        self.assertEqual(self.client.get("/api/state").get_json()["history"][-1]["done"], 1)

    def test_past_state_uses_historical_steps_and_archived_tasks(self):
        yesterday = (datetime.fromisoformat(self.today) - timedelta(days=1)).date().isoformat()
        self.assertEqual(self.client.get("/api/state?date=" + yesterday).get_json()["tasks"], [])
        self.assertEqual(self.check(day=yesterday).status_code, 400)
        self.permit_past()
        old_step = self.task["steps"][1]
        self.write("/api/tasks/" + self.task["id"], {"steps": [self.task["steps"][0]["text"], "新增加的步骤"]})
        self.write("/api/tasks/" + self.task["id"], {}, method="delete")
        past = self.client.get("/api/state?date=" + yesterday).get_json()
        old_task = next(t for t in past["tasks"] if t["id"] == self.task["id"])
        self.assertTrue(old_task["enabled"])
        self.assertEqual(old_task["steps"][1], old_step)
        self.assertEqual(self.check(1, day=yesterday, task=old_task).status_code, 200)

    def test_evening_defaults_and_essential_plan(self):
        self.assertFalse(any(t["period"] == "morning" for t in self.state["tasks"]))
        self.assertEqual({t["title"] for t in self.state["tasks"] if t["essential"]}, {"餐具与水槽清零", "检查厨余与垃圾", "地面与物品归位"})
        self.assertEqual(self.state["settings"]["reminderTimes"]["evening"], "20:30")
        self.assertEqual(self.state["settings"]["reminderTimes"]["bedtime"], "23:00")
        self.assertEqual(self.state["settings"]["emailTime"], "23:00")

    def test_push_late_window_retries_failed_device_only(self):
        first = self.subscription()
        second = {**first, "endpoint": first["endpoint"] + "-second"}
        self.write("/api/push/subscribe", first, method="post")
        self.write("/api/push/subscribe", second, method="post")
        self.write("/api/settings", {"remindersEnabled": True})
        at = datetime.fromisoformat(self.today + "T20:32:00").replace(tzinfo=ZoneInfo("Asia/Shanghai"))
        tick = self.app.extensions["homecare"]["scheduler_tick"]
        attempts = {}
        def delivery(**kwargs):
            endpoint = kwargs["subscription_info"]["endpoint"]
            attempts[endpoint] = attempts.get(endpoint, 0) + 1
            if endpoint == first["endpoint"] and attempts[endpoint] == 1:
                raise TimeoutError("temporary")
        with patch.object(server, "webpush", side_effect=delivery), self.app.app_context():
            self.assertEqual(tick(at), 1)
            self.assertEqual(tick(at + timedelta(seconds=30)), 0)
            self.assertEqual(tick(at + timedelta(seconds=60)), 1)
            self.assertEqual(tick(at + timedelta(seconds=120)), 0)
        self.assertEqual(attempts, {first["endpoint"]: 2, second["endpoint"]: 1})

    def test_push_failure_retry_limit_and_expired_window(self):
        self.write("/api/push/subscribe", self.subscription(), method="post")
        self.write("/api/settings", {"remindersEnabled": True})
        at = datetime.fromisoformat(self.today + "T20:30:00").replace(tzinfo=ZoneInfo("Asia/Shanghai"))
        tick = self.app.extensions["homecare"]["scheduler_tick"]
        with patch.object(server, "webpush", side_effect=TimeoutError("temporary")) as delivery, self.app.app_context():
            for seconds in (-60, 0, 60, 120, 180, 600):
                self.assertEqual(tick(at + timedelta(seconds=seconds)), 0)
            self.assertEqual(delivery.call_count, 3)

    def test_email_checks_unfinished_only_and_persists_no_duplicate(self):
        self.write("/api/settings", {"emailEnabled": True, "emailTo": "household@example.com"})
        for i in range(len(self.task["steps"])):
            self.check(i)
        skipped = next(t for t in self.state["tasks"] if t["id"] != self.task["id"] and t["frequency"] == "daily")
        self.write("/api/skip", {"date": self.today, "taskId": skipped["id"], "skipped": True})
        at = datetime.fromisoformat(self.today + "T23:02:00").replace(tzinfo=ZoneInfo("Asia/Shanghai"))
        tick = self.app.extensions["homecare"]["scheduler_tick"]
        with patch.object(server, "email_delivery_status", return_value={"configured": True}), patch.object(server, "send_email") as delivery:
            with self.app.app_context():
                self.assertEqual(tick(at - timedelta(minutes=3)), 0)
                self.assertEqual(tick(at), 1)
                self.assertEqual(tick(at + timedelta(minutes=1)), 0)
            recreated = server.create_app({"TESTING": True, "SCHEDULER_ENABLED": False, "DATA_DIR": self.tmp.name, "HOMECARE_PASSWORD": PASSWORD})
            with recreated.app_context():
                self.assertEqual(recreated.extensions["homecare"]["scheduler_tick"](at), 0)
            self.assertEqual(delivery.call_count, 1)
            recipient, subject, message = delivery.call_args.args
            self.assertEqual(recipient, "household@example.com")
            self.assertIn("未完成", subject)
            self.assertNotIn(self.task["title"] + "：", message)
            self.assertNotIn(skipped["title"] + "：", message)
            self.assertIn("滤网食物残渣", message)

    def test_email_complete_or_skipped_day_stays_quiet(self):
        self.write("/api/settings", {"emailEnabled": True, "emailTo": "household@example.com"})
        weekday = datetime.fromisoformat(self.today).weekday()
        for task in self.state["tasks"]:
            if task["frequency"] == "daily" or weekday in task["weekdays"]:
                self.write("/api/skip", {"date": self.today, "taskId": task["id"], "skipped": True})
        at = datetime.fromisoformat(self.today + "T23:00:00").replace(tzinfo=ZoneInfo("Asia/Shanghai"))
        with patch.object(server, "email_delivery_status", return_value={"configured": True}), patch.object(server, "send_email") as delivery, self.app.app_context():
            self.assertEqual(self.app.extensions["homecare"]["scheduler_tick"](at), 0)
            delivery.assert_not_called()

    def test_email_retry_limit_validation_and_test_rate_limit(self):
        for recipient in ("a@example.com\r\nBcc: victim@example.com", "a@", "", "a..b@example.com"):
            self.assertEqual(self.write("/api/settings", {"emailEnabled": True, "emailTo": recipient}).status_code, 400)
        self.write("/api/settings", {"emailEnabled": True, "emailTo": "household@example.com"})
        at = datetime.fromisoformat(self.today + "T23:00:00").replace(tzinfo=ZoneInfo("Asia/Shanghai"))
        with patch.object(server, "email_delivery_status", return_value={"configured": True}), patch.object(server, "send_email", side_effect=TimeoutError("temporary")) as delivery, self.app.app_context():
            tick = self.app.extensions["homecare"]["scheduler_tick"]
            for seconds in (0, 30, 60, 120, 180, 600):
                self.assertEqual(tick(at + timedelta(seconds=seconds)), 0)
            self.assertEqual(delivery.call_count, 3)
        with patch.object(server, "email_delivery_status", return_value={"configured": True}), patch.object(server, "send_email") as delivery:
            self.assertEqual(self.write("/api/email/test", {}, method="post").status_code, 200)
            self.assertEqual(self.write("/api/email/test", {}, method="post").status_code, 429)
            self.assertEqual(delivery.call_count, 1)


class InitialPasswordTests(unittest.TestCase):
    def test_random_password_written_privately_and_survives_restart(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = {"TESTING": True, "SCHEDULER_ENABLED": False, "DATA_DIR": tmp, "HOMECARE_PASSWORD": None}
            app = server.create_app(config)
            password_file = Path(tmp) / "access-password.txt"
            password = password_file.read_text().strip()
            self.assertGreaterEqual(len(password), 32)
            self.assertEqual(password_file.stat().st_mode & 0o777, 0o600)
            recreated = server.create_app(config)
            self.assertEqual(password_file.read_text().strip(), password)
            client = recreated.test_client()
            self.assertEqual(client.post("/api/login", json={"password": password}, headers=HEADERS).status_code, 200)
            with app.app_context():
                auth = app.extensions["homecare"]["db"]().execute("SELECT value FROM meta WHERE key='auth'").fetchone()[0]
                self.assertNotIn(password, auth)


if __name__ == "__main__":
    unittest.main(verbosity=2)
