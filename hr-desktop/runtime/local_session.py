"""Restore an existing local session without logging in or making BOSS requests."""
import contextlib
import json
import os
import re
import sqlite3
import time

import portable_lock

TEMPORARY = {"SESSION_UNAVAILABLE", "REQUEST_BUDGET_EXHAUSTED"}


def resume(connector, guard, store=None):
    lock = os.open(guard.REQUEST_LOCK, os.O_CREAT | os.O_RDWR, 0o600)
    try:
        try:
            portable_lock.flock(lock, portable_lock.LOCK_EX | portable_lock.LOCK_NB)
        except BlockingIOError:
            return {"ok": False, "error": {"code": "ACCOUNT_BUSY"}}
        if connector.DISCONNECTED.exists():
            return {"ok": False, "error": {"code": "AUTH_REQUIRED"}}
        before = guard.RISK_HOLD.read_bytes() if guard.RISK_HOLD.exists() else None
        if before is not None:
            code = json.loads(before).get("code")
            if code not in TEMPORARY:
                return {"ok": False, "error": {"code": code or "REQUESTS_PAUSED"}}
            if code == "REQUEST_BUDGET_EXHAUSTED" and guard.BUDGET_DB.exists():
                with contextlib.closing(sqlite3.connect(guard.BUDGET_DB.resolve().as_uri() + "?mode=ro", uri=True)) as db:
                    count = db.execute("SELECT COUNT(*) FROM requests WHERE at>?", (time.time()-3600,)).fetchone()[0]
                if count >= guard.HOURLY_LIMIT:
                    return {"ok": False, "error": {"code": code}}
        # An app-only version bump changes Electron's default user agent.
        # Preserve every cookie and require an otherwise identical browser
        # environment before updating that local metadata.
        metadata = None
        if store is not None:
            token = store.load() or {}
            values, agent = guard.browser_session_snapshot()
            old_agent = token.get("user_agent", "")
            version = r"heyi-recruiter/\d+\.\d+\.\d+"
            if (values == {token.get("cookies", {}).get("wt2")} and agent != old_agent
                    and re.search(version, old_agent) and re.search(version, agent)
                    and re.sub(version, "heyi-recruiter/APP", old_agent) == re.sub(version, "heyi-recruiter/APP", agent)):
                metadata = {**token, "user_agent": agent}
        if metadata is None:
            guard.verify_session()
        else:
            guard.verify_session(metadata)
        current = guard.RISK_HOLD.read_bytes() if guard.RISK_HOLD.exists() else None
        if current != before:
            return {"ok": False, "error": {"code": "REQUESTS_PAUSED"}}
        if metadata is not None:
            store.save(metadata)
        if before is not None:
            guard.RISK_HOLD.unlink()
        return {"ok": True}
    except Exception as error:
        return {"ok": False, "error": {"code": getattr(error, "code", "SESSION_UNAVAILABLE")}}
    finally:
        os.close(lock)
