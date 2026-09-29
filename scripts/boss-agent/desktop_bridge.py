"""Hoyee-only pairing and device leases. This module never contacts BOSS."""
import contextlib
import hashlib
import json
import re
import secrets
import sqlite3
import time
from pathlib import Path

DB = Path("/opt/heyi/boss-agent/runtime/desktop-pairing.sqlite3")
TOKEN = re.compile(r"^[A-Za-z0-9_-]{43}$")
DEVICE = re.compile(r"^[a-f0-9-]{36}$")
LEASE_SECONDS = 1800


class DeviceConflict(ValueError):
    pass


def digest(value):
    if not isinstance(value, str) or not TOKEN.fullmatch(value):
        raise ValueError("连接凭证无效，请重新连接本机助手")
    return hashlib.sha256(value.encode()).hexdigest()


@contextlib.contextmanager
def database():
    DB.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with contextlib.closing(sqlite3.connect(DB, timeout=5)) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute("CREATE TABLE IF NOT EXISTS tickets (hash TEXT PRIMARY KEY, user TEXT, expires REAL)")
        connection.execute("""CREATE TABLE IF NOT EXISTS leases (
            owner TEXT PRIMARY KEY, hash TEXT, device TEXT, user TEXT, expires REAL)""")
        DB.chmod(0o600)
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("DELETE FROM tickets WHERE expires < ?", (time.time(),))
        connection.execute("DELETE FROM leases WHERE expires < ?", (time.time(),))
        try:
            yield connection
            connection.commit()
        except BaseException:
            connection.rollback()
            raise


def identity(user):
    if (user.get("username") != "13800000000" or user.get("platformAdmin") is not True
            or user.get("organizationCode") != "heyi" or "ADMIN" not in user.get("roles", [])):
        raise ValueError("招聘模块仅限指定平台管理员")
    return {key: user.get(key) for key in
            ("username", "id", "userId", "organizationCode", "platformAdmin", "roles")}


def issue(user, automatic=False):
    user = identity(user)
    if automatic:
        user["_automatic"] = True
    ticket = secrets.token_urlsafe(32)
    with database() as db:
        # Only a handful of unconsumed browser tickets are useful.
        db.execute("DELETE FROM tickets WHERE user = ?", (json.dumps(user),))
        db.execute("INSERT INTO tickets VALUES (?, ?, ?)",
                   (digest(ticket), json.dumps(user), time.time() + 60))
    return {"ticket": ticket, "expiresIn": 60}


def redeem(ticket, device):
    if not isinstance(device, str) or not DEVICE.fullmatch(device):
        raise ValueError("本机设备标识无效")
    token = secrets.token_urlsafe(32)
    with database() as db:
        row = db.execute("SELECT * FROM tickets WHERE hash = ?", (digest(ticket),)).fetchone()
        if not row:
            raise ValueError("连接凭证已过期或已使用，请重新连接本机助手")
        stored_user = json.loads(row["user"])
        user = identity(stored_user)
        owner = f'{user["organizationCode"]}:{user.get("id") or user.get("userId") or user["username"]}'
        current = db.execute("SELECT device FROM leases WHERE owner = ?", (owner,)).fetchone()
        if stored_user.get("_automatic") and current and current["device"] != device:
            raise DeviceConflict("另一台电脑正在使用助手，请手动确认切换设备")
        expires = time.time() + LEASE_SECONDS
        db.execute("DELETE FROM tickets WHERE hash = ?", (row["hash"],))
        db.execute("INSERT OR REPLACE INTO leases VALUES (?, ?, ?, ?, ?)",
                   (owner, digest(token), device, row["user"], expires))
    return {"token": token, "user": user, "expiresAt": expires}


def check(token, device):
    with database() as db:
        row = db.execute("SELECT * FROM leases WHERE hash = ? AND device = ?",
                         (digest(token), device)).fetchone()
        if not row:
            raise ValueError("本机连接已到期或已切换到另一台电脑，请重新连接助手")
        return {"user": identity(json.loads(row["user"])), "expiresAt": row["expires"]}


def release(token, device):
    with database() as db:
        db.execute("DELETE FROM leases WHERE hash = ? AND device = ?", (digest(token), device))
    return {"released": True}
