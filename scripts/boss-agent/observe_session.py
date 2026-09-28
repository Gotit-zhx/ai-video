#!/opt/heyi/boss-agent/venv/bin/python
"""Passive, bounded observation. Only local CDP reads and local audit files."""
import argparse
import contextlib
import datetime
import json
import os
from pathlib import Path
import sqlite3
import time
import uuid

ROOT = Path("/opt/heyi/boss-agent/runtime/session-observation")
AUDIT_DB = Path("/opt/heyi/boss-agent/runtime/platform-request-audit.sqlite3")
RISK_HOLD = Path("/opt/heyi/boss-agent/runtime/recruiter-ip-risk-hold")
DISCONNECTED = Path("/opt/heyi/boss-agent/runtime/recruiter-disconnected")
POLL_SECONDS = 5


def local_time(timestamp):
    return datetime.datetime.fromtimestamp(
        timestamp, datetime.timezone(datetime.timedelta(hours=8))).isoformat(timespec="seconds")


def recent_requests(at):
    """Correlation evidence only, never infer that the preceding request caused logout."""
    if not AUDIT_DB.exists():
        return []
    try:
        with contextlib.closing(sqlite3.connect(
                AUDIT_DB.as_uri() + "?mode=ro", uri=True, timeout=1)) as db:
            db.row_factory = sqlite3.Row
            rows = db.execute(
                "SELECT at,event,channel,endpoint,method,outcome,http_status,platform_code "
                "FROM events WHERE at BETWEEN ? AND ? ORDER BY id DESC LIMIT 20",
                (at - 120, at)).fetchall()
            return [dict(row) for row in reversed(rows)]
    except sqlite3.Error:
        return []


def hold_snapshot():
    try:
        raw = json.loads(RISK_HOLD.read_text())
        code = str(raw.get("code", ""))
        if not code or not all(c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ_" for c in code):
            code = "MANUAL_REVIEW_REQUIRED"
        return code, raw.get("since"), raw.get("generation", "")
    except (OSError, ValueError, AttributeError):
        return "", None, ""


class Observation:
    def __init__(self, started, duration, emit):
        self.run_id = uuid.uuid4().hex
        self.started, self.deadline, self.emit = started, started + duration, emit
        self.seen_at = self.lost_at = self.missing_since = None
        self.missing_count = 0
        self.previous_credentials = None  # Memory only, never serialize or log.
        self.previous_hold = None
        self.unavailable = False
        self.state = "waiting_for_login"

    def event(self, kind, at, **fields):
        self.emit({"run_id": self.run_id, "event": kind, "at": at,
                   "time": local_time(at), **fields})

    def sample(self, credentials, hold, at, *, disconnected=False):
        if credentials is None:
            self.missing_count, self.missing_since = 0, None
            if not self.unavailable:
                self.event("OBSERVATION_UNAVAILABLE", at)
            self.unavailable = True
            return
        if self.unavailable:
            self.event("OBSERVATION_RESTORED", at)
        self.unavailable = False
        if credentials:
            if self.seen_at is None:
                self.seen_at = at
                self.event("BROWSER_SESSION_APPEARED", at)
            elif self.state == "session_lost":
                self.event("BROWSER_SESSION_REAPPEARED", at)
            elif self.previous_credentials and credentials != self.previous_credentials:
                self.event("PRIMARY_SESSION_CHANGED", at)
            self.state = "session_present"
            self.missing_count, self.missing_since = 0, None
            self.previous_credentials = credentials
        elif self.seen_at is not None and self.state != "session_lost":
            self.missing_since = at if self.missing_since is None else self.missing_since
            self.missing_count += 1
            # A failed CDP read is never a missing cookie. Confirm with two reads.
            if self.missing_count >= 2:
                self.lost_at, self.state = self.missing_since, "session_lost"
                self.event("LOCAL_DISCONNECT" if disconnected else "BROWSER_SESSION_LOST",
                           self.lost_at, confirmed_at=at)
        if self.previous_hold is not None and hold != self.previous_hold and hold[0]:
            self.event("PLATFORM_HOLD_RECORDED", at, code=hold[0],
                       had_browser_session=bool(credentials))
        self.previous_hold = hold

    def status(self, at, *, running=True):
        return {"run_id": self.run_id, "monitoring": running,
                "state": "unknown" if self.unavailable else self.state,
                "last_known_state": self.state, "started_at": self.started,
                "deadline_at": self.deadline, "checked_at": at,
                "checked_time": local_time(at), "session_seen_at": self.seen_at,
                "session_lost_at": self.lost_at,
                "current_hold": self.previous_hold[0] if self.previous_hold else ""}


def main(hours):
    # Importing these modules does not start workers or install SDK monkeypatches.
    from guarded_cli import browser_session_snapshot
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.umask(0o077)

    def emit(event):
        if event["event"] in ("BROWSER_SESSION_LOST", "PLATFORM_HOLD_RECORDED"):
            event["recent_requests"] = recent_requests(event["at"])
        with (ROOT / "events.jsonl").open("a") as stream:
            stream.write(json.dumps(event, ensure_ascii=False) + "\n")

    def save(status):
        temporary = ROOT / "status.tmp"
        temporary.write_text(json.dumps(status, ensure_ascii=False))
        temporary.replace(ROOT / "status.json")

    observation = Observation(time.time(), hours * 3600, emit)
    observation.event("OBSERVATION_STARTED", observation.started,
                      deadline_at=observation.deadline)
    save(observation.status(time.time()))
    try:
        while time.time() < observation.deadline:
            try:
                credentials, _ = browser_session_snapshot()
            except Exception:
                credentials = None
            observation.sample(credentials, hold_snapshot(), time.time(),
                               disconnected=DISCONNECTED.exists())
            save(observation.status(time.time()))
            time.sleep(POLL_SECONDS)
    finally:
        observation.event("OBSERVATION_FINISHED", time.time())
        save(observation.status(time.time(), running=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--hours", type=float, default=6)
    args = parser.parse_args()
    if not 0 < args.hours <= 24:
        parser.error("Observation must be bounded to 24 hours or less")
    main(args.hours)
