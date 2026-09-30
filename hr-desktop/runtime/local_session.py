"""Restore an existing local session without logging in or making BOSS requests."""
import json
import os
import sqlite3
import time

import portable_lock

TEMPORARY = {"SESSION_UNAVAILABLE", "REQUEST_BUDGET_EXHAUSTED"}


def resume(connector, guard):
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
                with sqlite3.connect("file:" + str(guard.BUDGET_DB) + "?mode=ro", uri=True) as db:
                    count = db.execute("SELECT COUNT(*) FROM requests WHERE at>?", (time.time()-3600,)).fetchone()[0]
                if count >= guard.HOURLY_LIMIT:
                    return {"ok": False, "error": {"code": code}}
        # Local CDP and token comparison only; never sync/replace credentials.
        guard.verify_session()
        current = guard.RISK_HOLD.read_bytes() if guard.RISK_HOLD.exists() else None
        if current != before:
            return {"ok": False, "error": {"code": "REQUESTS_PAUSED"}}
        if before is not None:
            guard.RISK_HOLD.unlink()
        return {"ok": True}
    except Exception as error:
        return {"ok": False, "error": {"code": getattr(error, "code", "SESSION_UNAVAILABLE")}}
    finally:
        os.close(lock)
