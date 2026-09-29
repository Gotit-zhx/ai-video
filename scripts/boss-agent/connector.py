#!/opt/heyi/boss-agent/venv/bin/python
"""Local-only, allowlisted JSON bridge for the Hoyee recruiter workspace."""

import json
import io
import os
import re
import signal
import sys
import time
import grp
import socketserver
import subprocess
import threading
import datetime
import http.server
import contextlib
import hashlib
import urllib.error
import urllib.parse
import urllib.request
import sqlite3
import uuid
from pathlib import Path

SOCKET = Path("/opt/heyi/boss-agent/bridge/connector.sock")
BOSS = "/opt/heyi/boss-agent/bin/boss-pilot"
HTTP_BIND = os.environ.get("APP_HR_BIND", "127.0.0.1")
HTTP_PORT = 8765
AUTHORIZED_PHONE = os.environ.get("APP_HR_ADMIN_PHONE", "13800000000")
AUTH_URL = "http://127.0.0.1:8080/api/v1/auth/me"
MAX_REQUEST = 8192
MAX_OUTPUT = 4 * 1024 * 1024
LOCK = threading.Lock()
ID = re.compile(r"^[A-Za-z0-9_~][A-Za-z0-9_~\-=]{0,179}$")
SECURITY_ID = re.compile(r"^[A-Za-z0-9_~][A-Za-z0-9_~\-=]{0,4095}$")
SECRET_KEY = re.compile(r"(cookie|token|stoken|wt2|secret|authorization|password)", re.I)
LOGIN_TIMEOUT = 300
LOGIN_ATTEMPTS_DB = Path("/opt/heyi/boss-agent/runtime/recruiter-login-attempts.sqlite3")
LOGIN_ATTEMPT_WINDOW = 1800
LOGIN_LOCK = threading.Lock()
QR_LOCK = threading.Lock()
LOGIN = {"state": "idle", "phase": "", "started": 0.0, "error": "", "failure_code": ""}
LOGIN_PROCESS = None
LOGIN_CANCEL = threading.Event()
ALLOWED_ORIGIN = os.environ.get("APP_HR_ALLOWED_ORIGIN", "https://hoyee.org.cn")
CLI_ENV = {"HOME": "/opt/heyi/boss-agent",
           "PATH": "/opt/heyi/boss-agent/venv/bin:/usr/bin:/bin"}
SYSTEMCTL = ["/usr/bin/sudo", "-n", "/usr/bin/systemctl"]
RESUME_DIR = Path("/opt/heyi/boss-agent/runtime/resumes")
RESUME_DB = Path("/opt/heyi/boss-agent/runtime/resume-requests.sqlite3")
RESUME_AUTO_PAUSE = Path("/opt/heyi/boss-agent/runtime/resume-auto-pause")
RISK_HOLD = Path("/opt/heyi/boss-agent/runtime/recruiter-ip-risk-hold")
DISCONNECTED = Path("/opt/heyi/boss-agent/runtime/recruiter-disconnected")
AUTH_DIR = Path("/opt/heyi/boss-agent/accounts/pilot/data/auth")
CHROME_PROFILE = Path("/opt/heyi/boss-agent/accounts/pilot/chrome-profile")
RESUME_POLL_SECONDS = 300
RESUME_POLL_LIMIT = 3
RESUME_POLL_MAX_AGE = 7 * 86400
RECOVERY = threading.local()
STOP_CODES = {
    "ACCOUNT_RISK", "ENVIRONMENT_RISK", "IP_RESTRICTED", "AUTH_REQUIRED",
    "AUTH_EXPIRED", "TOKEN_REFRESH_FAILED", "RATE_LIMITED", "CAPTCHA_REQUIRED",
    "SECURITY_VERIFICATION_REQUIRED", "REQUEST_BUDGET_EXHAUSTED",
    "SESSION_POLICY_UNAVAILABLE", "MANUAL_REVIEW_REQUIRED",
    "PC_LOGIN_RESTRICTED",
    "LOGIN_RISK_CONTROL",
    "SESSION_MISMATCH", "SESSION_UNAVAILABLE",
}


def start_recovery_scope():
    RECOVERY.active = True
    RECOVERY.hold_digest = (hashlib.sha256(RISK_HOLD.read_bytes()).hexdigest()
                            if RISK_HOLD.exists() else "")


def cli_environment():
    environment = dict(CLI_ENV)
    if getattr(RECOVERY, "active", False):
        environment["HEYI_HR_RECOVERY_HOLD"] = getattr(RECOVERY, "hold_digest", "")
    return environment


def valid_retry_at(value):
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d\d-\d\d \d\d:\d\d:\d\d", value):
        return ""
    try:
        datetime.datetime.strptime(value, "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return ""
    return value


def pc_login_retry_at():
    try:
        return valid_retry_at(json.loads(RISK_HOLD.read_text()).get("retry_at"))
    except (OSError, ValueError, AttributeError):
        return ""


def pc_login_restriction_reason():
    try:
        value = json.loads(RISK_HOLD.read_text()).get("reason")
        return value if value == "REALNAME_LINKED_ACCOUNT" else ""
    except (OSError, ValueError, AttributeError):
        return ""


def pause_requests(code, *, retry_at="", reason=""):
    """Persist only a fixed error code/time, never upstream personal data."""
    code = code if code in STOP_CODES else "MANUAL_REVIEW_REQUIRED"
    retry_at = valid_retry_at(retry_at)
    if held_code() == "PC_LOGIN_RESTRICTED" and code != "PC_LOGIN_RESTRICTED":
        code, retry_at = "PC_LOGIN_RESTRICTED", pc_login_retry_at()
        reason = pc_login_restriction_reason()
    RISK_HOLD.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = RISK_HOLD.with_name(RISK_HOLD.name + "." + uuid.uuid4().hex + ".tmp")
    record = {"code": code, "since": int(time.time()), "generation": uuid.uuid4().hex}
    if code == "PC_LOGIN_RESTRICTED" and retry_at:
        record["retry_at"] = retry_at
    if code == "PC_LOGIN_RESTRICTED" and reason == "REALNAME_LINKED_ACCOUNT":
        record["reason"] = reason
    temporary.write_text(json.dumps(record))
    temporary.chmod(0o600)
    temporary.replace(RISK_HOLD)
    with LOGIN_LOCK:
        LOGIN.update(state="verification_pending", phase="", failure_code=code,
                     error=verification_message(code))
    print(json.dumps({"event": "recruiter_requests_paused", "code": code,
                      "time": int(time.time())}), flush=True)


def held_code():
    if not RISK_HOLD.is_file():
        return ""
    try:
        code = json.loads(RISK_HOLD.read_text()).get("code")
        return code if code in STOP_CODES else "MANUAL_REVIEW_REQUIRED"
    except (ValueError, AttributeError):
        return "IP_RESTRICTED"  # Original empty marker.


def observe_result(envelope):
    if envelope.get("ok") is False:
        code = recruiter_failure_code(envelope)
        if code in STOP_CODES:
            pause_requests(code)
            return {**envelope, "error": {"code": code, "message": verification_message(code)}}
    return envelope


def record_cli_result(args, envelope, started):
    # No IDs, parameters, URLs, messages, credentials or candidate data.
    operation = args[1] if len(args) > 1 and args[0] == "hr" else args[0]
    if operation == "status":
        return
    print(json.dumps({
        "event": "recruiter_cli_result", "operation": operation,
        "ok": envelope.get("ok") is True,
        "code": "" if envelope.get("ok") is True else safe_error_code(envelope, "CLI_OUTPUT"),
        "elapsed_ms": int((time.monotonic() - started) * 1000),
        "time": int(time.time()),
    }), flush=True)


def identifier(value):
    text = str(value or "").strip()
    if not ID.fullmatch(text):
        raise ValueError("标识格式不正确")
    return text


def security_identifier(value):
    # BOSS recommendation cards carry ~750-character opaque security IDs.
    # They are not short entity IDs. Preserve their full value without decoding,
    # truncating or logging it; subprocess receives a single literal argument.
    text = str(value or "")
    if not SECURITY_ID.fullmatch(text):
        raise ValueError("简历访问凭据格式不正确，请重新获取候选人后再试")
    return text


def positive_id(value):
    text = str(value or "")
    if not text.isdecimal() or not 1 <= len(text) <= 20 or int(text) <= 0:
        raise ValueError("会话 ID 和消息 ID 必须为正整数")
    return text


@contextlib.contextmanager
def resume_db():
    RESUME_DB.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    connection = sqlite3.connect(RESUME_DB, timeout=5)
    connection.row_factory = sqlite3.Row
    connection.execute("""CREATE TABLE IF NOT EXISTS resume_requests (
        friend_id TEXT PRIMARY KEY, requested_at INTEGER NOT NULL,
        baseline_mid INTEGER NOT NULL, state TEXT NOT NULL,
        message_id TEXT, updated_at INTEGER NOT NULL, error TEXT NOT NULL DEFAULT '',
        confirmed_at INTEGER
    )""")
    connection.execute("""CREATE TABLE IF NOT EXISTS resume_poll_state (
        friend_id TEXT PRIMARY KEY, last_polled_at INTEGER NOT NULL,
        failures INTEGER NOT NULL DEFAULT 0
    )""")
    os.chmod(RESUME_DB, 0o600)
    try:
        with connection:
            if "confirmed_at" not in {row[1] for row in connection.execute("PRAGMA table_info(resume_requests)")}:
                try:
                    connection.execute("ALTER TABLE resume_requests ADD COLUMN confirmed_at INTEGER")
                except sqlite3.OperationalError:
                    if "confirmed_at" not in {row[1] for row in connection.execute("PRAGMA table_info(resume_requests)")}:
                        raise
                connection.execute("UPDATE resume_requests SET confirmed_at=requested_at WHERE state='waiting'")
            yield connection
    finally:
        connection.close()


def tracked_resumes():
    with resume_db() as connection:
        rows = connection.execute(
            "SELECT friend_id, requested_at, state, message_id, updated_at, error, confirmed_at "
            "FROM resume_requests ORDER BY requested_at DESC LIMIT 50").fetchall()
        return [dict(row) for row in rows]


def resume_messages(envelope):
    data = envelope.get("data") or {}
    return data.get("messages", []) if isinstance(data, dict) else []


def latest_message_id(envelope):
    return max((int(str(item.get("mid"))) for item in resume_messages(envelope)
                if isinstance(item, dict) and str(item.get("mid", "")).isdecimal()), default=0)


def incoming_attachment(envelope, friend_id, baseline):
    matches = []
    for item in resume_messages(envelope):
        if not isinstance(item, dict):
            continue
        mid = str(item.get("mid", ""))
        sender = item.get("from") or {}
        body = item.get("body") or {}
        link = body.get("hyperLink") if isinstance(body, dict) else None
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(
            link.get("url", "") if isinstance(link, dict) else "").query)
        attachment_id = query.get("id") or query.get("encryptId")
        if (mid.isdecimal() and int(mid) > baseline and isinstance(sender, dict)
                and str(sender.get("uid")) == friend_id and sender.get("source", 0) == 0
                and isinstance(link, dict) and link.get("hyperLinkType") in (1, 9)
                and attachment_id and len(attachment_id) == 1):
            matches.append(mid)
    return min(matches, key=int) if matches else None


def reconcile_resume_request(envelope, friend_id, baseline):
    """Confirm an ambiguous write using the same conversation's persisted message."""
    if envelope.get("ok") is not True:
        return False
    for item in resume_messages(envelope):
        if not isinstance(item, dict):
            continue
        mid, sender, recipient, body = (str(item.get("mid", "")), item.get("from") or {},
                                        item.get("to") or {}, item.get("body") or {})
        if not all(isinstance(value, dict) for value in (sender, recipient, body)):
            continue
        # BOSS can label both endpoints source=0; UID direction is authoritative.
        if (not mid.isdecimal() or int(mid) <= baseline
                or not str(sender.get("uid", "")).isdecimal()
                or str(sender.get("uid")) == friend_id or str(recipient.get("uid")) != friend_id
                or body.get("type") != 1 or body.get("text") != "方便发一份简历过来吗？"):
            continue
        timestamp = item.get("time")
        if not isinstance(timestamp, (int, float)) or timestamp <= 0:
            continue
        confirmed_at = int(timestamp / 1000 if timestamp >= 1e12 else timestamp)
        with resume_db() as connection:
            updated = connection.execute(
                "UPDATE resume_requests SET confirmed_at=?, requested_at=?, "
                "state=CASE WHEN state='uncertain' THEN 'waiting' ELSE state END, error='', "
                "updated_at=CASE WHEN state='received' THEN updated_at ELSE ? END "
                "WHERE friend_id=? AND baseline_mid=? AND confirmed_at IS NULL "
                "AND state IN ('uncertain','waiting','received')",
                (confirmed_at, confirmed_at, int(time.time()), friend_id, baseline)).rowcount
        return bool(updated)
    return False


def resume_cli(*args, timeout=60):
    result = cli_json(*args, timeout=timeout)
    if result.get("ok") is not True:
        raise RuntimeError((result.get("error") or {}).get("message") or "BOSS 请求未获成功确认")
    return result


def request_resume(friend_id, retry_uncertain=False):
    friend_id = positive_id(friend_id)
    with LOGIN_LOCK:
        if LOGIN["state"] != "complete":
            raise RuntimeError("请先连接招聘方账号")
    if not LOCK.acquire(timeout=5):
        raise RuntimeError("招聘账号正在处理另一项请求")
    try:
        with LOGIN_LOCK:
            if LOGIN["state"] != "complete":
                raise RuntimeError("招聘连接已断开，请先连接招聘方账号")
        if held_code():
            raise RuntimeError(verification_message(held_code()))
        with resume_db() as connection:
            previous = connection.execute("SELECT state FROM resume_requests WHERE friend_id=?",
                                          (friend_id,)).fetchone()
        if previous and (previous["state"] in ("waiting", "received")
                         or (previous["state"] == "uncertain" and retry_uncertain is not True)):
            raise ValueError("该会话已有未完成的简历请求，请先核对消息，避免重复索取")
        # Upstream request-resume executes the recruiter's real Vue chat action.
        # A saved cookie alone is insufficient: login closes its temporary tab.
        # Prepare and verify the chat runtime before any write can be attempted.
        prepare_recruiter_chat()
        history = resume_cli("hr", "chatmsg", friend_id, "--count", "100")
        baseline = latest_message_id(history)
        now = int(time.time())
        with resume_db() as connection:
            # Persist before the external write so a service restart cannot lose
            # the attempt and silently make a second request safe to send.
            connection.execute(
                "INSERT OR REPLACE INTO resume_requests "
                "(friend_id, requested_at, baseline_mid, state, message_id, updated_at, error) "
                "VALUES (?, ?, ?, 'uncertain', NULL, ?, ?)",
                (friend_id, now, baseline, now, "索取请求正在核对结果，请勿重复发送"))
        try:
            result = cli_json("hr", "request-resume", friend_id, timeout=90)
        except Exception:
            # A lost write response is ambiguous. Never offer an automatic resend.
            result = {"ok": False, "error": {"code": "REQUEST_UNCONFIRMED"}}
        data = result.get("data")
        if (result.get("ok") is not True or not isinstance(data, dict)
                or data.get("requested") is not True):
            code = safe_error_code(result, "REQUEST_UNCONFIRMED")
            # These upstream errors occur before the chat action is dispatched.
            not_sent = code in {"RECRUITER_CHAT_TAB_REQUIRED", "CDP_UNAVAILABLE",
                               "BROWSER_SESSION_NOT_FOUND", "AUTH_REQUIRED", "AUTH_EXPIRED",
                               "INVALID_PARAM", "NOT_SUPPORTED", "ROLE_MISMATCH"}
            observe_result(result)
            if not not_sent and not held_code():
                try:
                    history = cli_json("hr", "chatmsg", friend_id, "--count", "100", timeout=30)
                    if reconcile_resume_request(history, friend_id, baseline):
                        with resume_db() as connection:
                            row = connection.execute(
                                "SELECT state, requested_at, updated_at, confirmed_at FROM resume_requests WHERE friend_id=?",
                                (friend_id,)).fetchone()
                        return {"friendId": friend_id, **dict(row)}
                except (RuntimeError, OSError, ValueError, sqlite3.Error, subprocess.TimeoutExpired):
                    pass
            message = ("BOSS 沟通页面或登录状态未就绪，本次请求未发送，请重试"
                       if not_sent else "索取结果未确认，请先核对 BOSS 官方会话")
            message += f"（{code}）"
            with resume_db() as connection:
                connection.execute(
                    "UPDATE resume_requests SET state=?, updated_at=?, error=? WHERE friend_id=?",
                    ("failed" if not_sent else "uncertain", int(time.time()), message, friend_id))
            raise RuntimeError(message)
        now = int(time.time())
        with resume_db() as connection:
            connection.execute(
                "UPDATE resume_requests SET state='waiting', requested_at=?, confirmed_at=?, updated_at=?, error='' "
                "WHERE friend_id=? AND baseline_mid=?",
                (now, now, now, friend_id, baseline))
        return {"friendId": friend_id, "state": "waiting", "requested_at": now,
                "confirmed_at": now, "updated_at": now}
    finally:
        LOCK.release()


def poll_resumes_once():
    if held_code() or DISCONNECTED.is_file() or RESUME_AUTO_PAUSE.exists():
        return
    with LOGIN_LOCK:
        if LOGIN["state"] != "complete":
            # Startup/status reads cannot silently verify or restore a session.
            return
    now = int(time.time())
    with resume_db() as connection:
        connection.execute(
            "UPDATE resume_requests SET error=? WHERE state IN ('waiting','uncertain') "
            "AND requested_at<?",
            ("自动接收检查已到 7 天期限，记录已保留，请在官方会话查看后续附件。",
             now - RESUME_POLL_MAX_AGE))
        pending = connection.execute(
            "SELECT r.friend_id, r.baseline_mid FROM resume_requests r "
            "LEFT JOIN resume_poll_state p ON p.friend_id=r.friend_id "
            "WHERE r.state IN ('waiting','uncertain') AND r.requested_at>=? "
            "AND COALESCE(p.last_polled_at,0)<=? AND COALESCE(p.failures,0)<3 "
            "ORDER BY COALESCE(p.last_polled_at,0) ASC LIMIT ?",
            (now - RESUME_POLL_MAX_AGE, now - RESUME_POLL_SECONDS, RESUME_POLL_LIMIT)).fetchall()
    if not pending:
        return
    for row in pending:
        if not LOCK.acquire(timeout=1):
            return
        try:
            with LOGIN_LOCK:
                if LOGIN["state"] != "complete":
                    return
            if held_code() or RESUME_AUTO_PAUSE.exists():
                return
            try:
                result = observe_result(cli_json(
                    "hr", "chatmsg", row["friend_id"], "--count", "100", timeout=45))
            except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired):
                result = {"ok": False, "error": {"code": "NETWORK_ERROR"}}
            with resume_db() as connection:
                connection.execute(
                    "INSERT INTO resume_poll_state VALUES (?,?,?) ON CONFLICT(friend_id) "
                    "DO UPDATE SET last_polled_at=excluded.last_polled_at, "
                    "failures=CASE WHEN excluded.failures=0 THEN 0 ELSE failures+1 END",
                    (row["friend_id"], int(time.time()), 0 if result.get("ok") is True else 1))
        finally:
            LOCK.release()
        if result.get("ok") is not True:
            # Leave the request waiting; a transient read must not erase it.
            code = (result.get("error") or {}).get("code")
            if code in STOP_CODES:
                with resume_db() as connection:
                    connection.execute(
                        "UPDATE resume_requests SET error=? WHERE friend_id=? "
                        "AND state IN ('waiting','uncertain')",
                        (verification_message(code), row["friend_id"]))
                return
            with resume_db() as connection:
                failures = connection.execute(
                    "SELECT failures FROM resume_poll_state WHERE friend_id=?",
                    (row["friend_id"],)).fetchone()[0]
            if failures >= 3:
                pause_requests("MANUAL_REVIEW_REQUIRED")
                return
            continue
        reconcile_resume_request(result, row["friend_id"], row["baseline_mid"])
        mid = incoming_attachment(result, row["friend_id"], row["baseline_mid"])
        if mid:
            with resume_db() as connection:
                connection.execute(
                    "UPDATE resume_requests SET state='received', message_id=?, updated_at=?, error='' "
                    "WHERE friend_id=? AND baseline_mid=? AND state IN ('waiting','uncertain')",
                    (mid, int(time.time()), row["friend_id"], row["baseline_mid"]))


def resume_poll_worker():
    while True:
        try:
            poll_resumes_once()
        except (OSError, sqlite3.Error, ValueError, RuntimeError, subprocess.TimeoutExpired):
            pass
        time.sleep(RESUME_POLL_SECONDS)


def download_resume(friend_id, message_id):
    friend_id, message_id = positive_id(friend_id), positive_id(message_id)
    with LOGIN_LOCK:
        if LOGIN["state"] != "complete":
            raise RuntimeError("请先连接招聘方账号")
    with resume_db() as connection:
        tracked = connection.execute(
            "SELECT message_id, state FROM resume_requests WHERE friend_id=?", (friend_id,)).fetchone()
    if not tracked or tracked["state"] != "received" or tracked["message_id"] != message_id:
        raise ValueError("请先等待系统确认这条会话的附件消息")
    RESUME_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not LOCK.acquire(timeout=5):
        raise RuntimeError("招聘账号正在处理另一项请求")
    try:
        with LOGIN_LOCK:
            if LOGIN["state"] != "complete":
                raise RuntimeError("招聘连接已断开，请先连接招聘方账号")
        for suffix in (".pdf", ".docx", ".doc", ".png", ".jpg"):
            output = RESUME_DIR / f"{uuid.uuid4().hex}{suffix}"
            result = cli_json("hr", "download-resume", friend_id, "--message-id",
                              message_id, "--output", str(output), timeout=90)
            if result.get("ok") is True and output.is_file():
                return output
            output.unlink(missing_ok=True)
            error = result.get("error") or {}
            if error.get("code") != "INVALID_PARAM" or "扩展名不符" not in error.get("message", ""):
                raise RuntimeError(error.get("message") or "附件下载失败")
        raise RuntimeError("附件格式不受支持")
    finally:
        LOCK.release()


def sanitize(value):
    if isinstance(value, dict):
        return {key: sanitize(item) for key, item in value.items()
                if not SECRET_KEY.search(key)}
    if isinstance(value, list):
        return [sanitize(item) for item in value]
    return value


def page(value):
    number = int(value or 1)
    if number < 1 or number > 20:
        raise ValueError("页码应在 1–20 之间")
    return str(number)


def command(request):
    operation = request.get("operation")
    if operation == "status":
        return ["status"]
    if operation == "jobs":
        return ["hr", "jobs", "list"]
    if operation == "applications":
        args = ["hr", "applications", "--page", page(request.get("page"))]
        if request.get("jobId"):
            args.extend(["--job-id", identifier(request["jobId"])])
        return args
    if operation == "candidates":
        keyword = str(request.get("keyword") or "").strip()
        if len(keyword) > 80 or keyword.startswith("-") or any(ord(char) < 32 for char in keyword):
            raise ValueError("关键词最多 80 字，不能包含控制字符")
        args = ["hr", "candidates", keyword, "--page", page(request.get("page"))]
        if request.get("jobId"):
            args.extend(["--job-id", identifier(request["jobId"])])
        return args
    if operation == "recommendations":
        return ["hr", "recommendations", "--job-id", identifier(request.get("jobId")),
                "--page", page(request.get("page"))]
    if operation == "chats":
        args = ["hr", "chat", "--page", page(request.get("page"))]
        if request.get("jobId"):
            args.extend(["--job-id", identifier(request["jobId"])])
        return args
    if operation == "messages":
        friend = str(request.get("friendId") or "")
        if not friend.isdecimal() or len(friend) > 20:
            raise ValueError("会话 ID 格式不正确")
        return ["hr", "chatmsg", friend, "--count", "20"]
    if operation == "resume":
        args = ["hr", "resume", identifier(request.get("geekId")),
                "--job-id", identifier(request.get("jobId"))]
        if request.get("securityId"):
            args.extend(["--security-id", security_identifier(request["securityId"])])
        return args
    raise ValueError("不支持的只读操作")


def disconnected_result(operation):
    error = {"code": "SESSION_DISCONNECTED",
             "message": "已断开服务器连接，需要时请点击扫码连接招聘账号"}
    return ({"ok": True, "result": {"ok": False, "error": error}}
            if operation == "status" else {"ok": False, "error": error})


def execute(request):
    try:
        args = command(request)
    except (TypeError, ValueError) as error:
        return {"ok": False, "error": {"code": "INVALID_PARAM", "message": str(error)}}
    with LOGIN_LOCK:
        login_state = LOGIN["state"]
        verified = login_state == "complete"
    if login_state == "disconnected":
        return disconnected_result(request.get("operation"))
    if held_code() and login_state != "pending":
        restore_login_state()
        login_state, verified = "verification_pending", False
    if request.get("operation") == "status" and login_state in ("pending", "verification_pending"):
        with LOGIN_LOCK:
            code = LOGIN["failure_code"]
        return {"ok": True, "result": {"ok": False, "error": {
            "code": "RECRUITER_VERIFICATION_PENDING" if login_state == "verification_pending"
                    else "LOGIN_PENDING",
            "message": verification_message(code) if login_state == "verification_pending"
                    else "正在等待招聘方身份验证"}}}
    if request.get("operation") != "status" and not verified:
        if login_state == "verification_pending":
            with LOGIN_LOCK:
                code = LOGIN["failure_code"]
            return {"ok": False, "error": {
                "code": "IP_RESTRICTED" if code == "IP_RESTRICTED"
                        else "RECRUITER_VERIFICATION_PENDING",
                "message": verification_message(code)}}
        return {"ok": False, "error": {
            "code": "RECRUITER_REQUIRED",
            "message": "请先用 BOSS 招聘方账号扫码并通过身份验证"}}
    if not LOCK.acquire(timeout=5):
        return {"ok": False, "error": {"code": "BUSY", "message": "招聘账号正在处理另一项请求，请稍后重试"}}
    try:
        with LOGIN_LOCK:
            if LOGIN["state"] == "disconnected":
                return disconnected_result(request.get("operation"))
        if held_code():
            error = {"code": held_code(), "message": verification_message(held_code())}
            return {"ok": True, "result": {"ok": False, "error": error}}
        started = time.monotonic()
        try:
            result = subprocess.run(
                [BOSS, "--json", *args], capture_output=True, timeout=90,
                check=False, env=CLI_ENV,
            )
        except subprocess.TimeoutExpired:
            return {"ok": False, "error": {"code": "TIMEOUT", "message": "读取超时，请稍后再试"}}
        if len(result.stdout) > MAX_OUTPUT:
            return {"ok": False, "error": {"code": "OUTPUT_LIMIT", "message": "结果过大，请缩小查询范围"}}
        try:
            envelope = json.loads(result.stdout)
        except (UnicodeDecodeError, json.JSONDecodeError):
            return {"ok": False, "error": {"code": "CLI_OUTPUT", "message": "连接器返回格式异常"}}
        record_cli_result(args, envelope, started)
        if request.get("operation") != "status":
            envelope = observe_result(envelope)
        if request.get("operation") == "status" and envelope.get("ok") is True:
            if not verified:
                # A passive status read must not become a live platform probe.
                with LOGIN_LOCK:
                    LOGIN.update(state="verification_pending", failure_code="MANUAL_REVIEW_REQUIRED",
                                 error=verification_message("MANUAL_REVIEW_REQUIRED"))
                return {"ok": True, "result": {"ok": False, "error": {
                    "code": "RECRUITER_VERIFICATION_PENDING",
                    "message": verification_message("MANUAL_REVIEW_REQUIRED")}}}
        return {"ok": True, "result": sanitize(envelope)}
    finally:
        LOCK.release()


def browser_service(action, name):
    subprocess.run([*SYSTEMCTL, action, name], check=True, timeout=45,
                   capture_output=True)


def wait_for_cdp(timeout=35):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with opener.open("http://127.0.0.1:9222/json/version", timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, ValueError):
            time.sleep(0.5)
    raise TimeoutError("CDP browser did not become ready")


def close_browser():
    for service in ("heyi-boss-chrome.service", "heyi-boss-display.service"):
        browser_service("stop", service)


def prepare_recruiter_chat_result():
    """Include chat-page navigation in the same account gate/budget/audit."""
    try:
        from guarded_cli import (
            install_policy, request_trace, assert_requests_allowed,
            reserve_request, verify_session,
        )
        install_policy()
        with request_trace("browser", "PREPARE_RECRUITER_CHAT", "NAVIGATE"):
            assert_requests_allowed()
            reserve_request()
            assert_requests_allowed()
            verify_session()
            return prepare_recruiter_chat_page()
    except Exception as error:
        code = getattr(error, "code", "")
        return {"ok": False, "code": code if code in STOP_CODES else "CHAT_PAGE_NOT_READY"}


def prepare_recruiter_chat_page():
    """Open/reuse the authenticated chat tab without sending any messages."""
    from patchright.sync_api import sync_playwright
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.connect_over_cdp("http://127.0.0.1:9222", timeout=5000)
            try:
                context = next((context for context in browser.contexts
                                if any(cookie.get("name") == "wt2" and cookie.get("value")
                                       and (cookie.get("domain") == "zhipin.com"
                                            or cookie.get("domain", "").endswith(".zhipin.com"))
                                       for cookie in context.cookies())), None)
                if context is None:
                    return {"ok": False, "code": "BROWSER_LOGIN_REQUIRED"}
                page = next((page for page in context.pages
                             if urllib.parse.urlsplit(page.url).hostname == "www.zhipin.com"
                             and urllib.parse.urlsplit(page.url).path.rstrip("/") == "/web/chat/index"), None)
                if page is None:
                    page = next((page for page in context.pages if page.url == "about:blank"), None)
                    page = page or context.new_page()
                    page.goto("https://www.zhipin.com/web/chat/index",
                              wait_until="domcontentloaded", timeout=25000)
                page.wait_for_function("""() => {
                    const vm = document.querySelector('.chat-user')?.__vue__;
                    return vm?.$options?.name === 'geek-list'
                        && typeof vm.geekClick === 'function';
                }""", timeout=15000)
                return {"ok": True}
            finally:
                browser.close()
    except Exception:
        # Browser exceptions can include URLs and account identifiers.
        return {"ok": False, "code": "CHAT_PAGE_NOT_READY"}


def prepare_recruiter_chat():
    try:
        result = subprocess.run(
            [sys.executable, str(Path(__file__).resolve()), "--prepare-recruiter-chat"],
            capture_output=True, timeout=50, check=False, env=CLI_ENV)
        envelope = json.loads(result.stdout) if len(result.stdout) <= 1024 else {}
    except (OSError, subprocess.TimeoutExpired, UnicodeDecodeError, json.JSONDecodeError):
        envelope = {}
    if isinstance(envelope, dict) and envelope.get("ok") is True:
        return
    if isinstance(envelope, dict) and envelope.get("code") in STOP_CODES:
        pause_requests(envelope["code"])
        raise RuntimeError(verification_message(envelope["code"]))
    if isinstance(envelope, dict) and envelope.get("code") == "BROWSER_LOGIN_REQUIRED":
        raise RuntimeError("BOSS 浏览器登录已过期，本次简历请求未发送，请先重新连接招聘账号")
    raise RuntimeError("BOSS 沟通页面准备失败，本次简历请求未发送，请稍后重试")


def clear_stored_boss_cookies():
    """Remove only BOSS cookies, after Chrome has completely stopped.

    A failed CDP cleanup or a tab writing cookies during shutdown must not
    resurrect a stale login on the next explicit clear-and-scan.
    """
    root = CHROME_PROFILE.resolve()
    databases = [*root.glob("*/Cookies"), *root.glob("*/Network/Cookies")]
    for database in databases:
        if not database.is_file():
            continue
        if database.is_symlink() or not database.resolve().is_relative_to(root):
            raise OSError("浏览器会话目录异常，未清除登录信息")
        try:
            # mode=rw avoids creating an empty cookie database on a first login.
            with contextlib.closing(sqlite3.connect(
                    database.as_uri() + "?mode=rw", uri=True, timeout=5)) as connection:
                with connection:
                    connection.execute(
                        "DELETE FROM cookies WHERE host_key = ? OR host_key LIKE ?",
                        ("zhipin.com", "%.zhipin.com"))
        except sqlite3.Error:
            raise OSError("浏览器旧会话清理失败，请重试重新扫码") from None


def cli_json(*args, timeout=30):
    code = held_code()
    if code and not getattr(RECOVERY, "active", False) and args != ("status",):
        return {"ok": False, "error": {"code": code, "message": verification_message(code)}}
    started = time.monotonic()
    result = subprocess.run([BOSS, "--json", *args], capture_output=True,
                            timeout=timeout, check=False, env=cli_environment())
    if len(result.stdout) > MAX_OUTPUT:
        return {}
    try:
        envelope = json.loads(result.stdout)
        record_cli_result(args, envelope, started)
        return envelope if args == ("status",) else observe_result(envelope)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {}


def sync_browser_login_result():
    """Reuse upstream CDP extraction without AuthManager's forced-login fallback."""
    from boss_agent_cli.auth.browser import login_via_cdp, ReusedSessionStaleError
    from boss_agent_cli.auth.token_store import TokenStore
    from boss_agent_cli.api.client import AccountRiskError, EnvironmentRiskError

    try:
        install_session_policy()
        # Do not turn a verification retry into a new QR enrollment.
        if not chrome_has_login_cookie():
            return {"ok": False, "error": {"code": "BROWSER_LOGIN_REQUIRED"}}
        # Upstream diagnostics can include account fingerprints. Never forward them.
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            token = login_via_cdp(cdp_url="http://127.0.0.1:9222", timeout=30,
                                  platform="zhipin", reuse_existing=True)
        if not isinstance(token, dict) or not (token.get("cookies") or {}).get("wt2"):
            return {"ok": False, "error": {"code": "AUTH_REQUIRED"}}
        TokenStore(AUTH_DIR).save(token)
        return {"ok": True}
    except ReusedSessionStaleError:
        code = "AUTH_EXPIRED"
    except AccountRiskError:
        code = "ACCOUNT_RISK"
    except EnvironmentRiskError:
        code = "ENVIRONMENT_RISK"
    except TimeoutError:
        code = "BROWSER_SYNC_TIMEOUT"
    except Exception:
        # Never serialize an upstream exception: it may contain credentials/URLs.
        code = "BROWSER_SYNC_FAILED"
    return {"ok": False, "error": {"code": code}}


def install_session_policy():
    from guarded_cli import install_policy, assert_requests_allowed
    install_policy()
    assert_requests_allowed(recovery=True)


def sync_browser_login():
    # CDP evaluations in upstream code may hang. Bound the child process while
    # keeping the independent Chrome session and previous encrypted token intact.
    try:
        result = subprocess.run(
            [sys.executable, str(Path(__file__).resolve()), "--sync-browser-login"],
            capture_output=True, timeout=60, check=False, env=cli_environment())
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": {"code": "BROWSER_SYNC_TIMEOUT"}}
    try:
        envelope = json.loads(result.stdout) if len(result.stdout) <= 1024 else {}
    except (UnicodeDecodeError, json.JSONDecodeError):
        envelope = {}
    if result.returncode == 0 and isinstance(envelope, dict) and envelope.get("ok") is True:
        return {"ok": True}
    code = safe_error_code(envelope if isinstance(envelope, dict) else {}, "BROWSER_SYNC_FAILED")
    return {"ok": False, "error": {"code": code}}


def recruiter_probe():
    previous_hold = RISK_HOLD.read_bytes() if RISK_HOLD.exists() else None
    status = cli_json("status", timeout=20)
    if status.get("ok") is not True:
        return False, safe_error_code(status, "AUTH_UNAVAILABLE")
    jobs = cli_json("hr", "jobs", "list", timeout=45)
    if jobs.get("ok") is True and jobs.get("command") == "recruiter-jobs-list":
        current_hold = RISK_HOLD.read_bytes() if RISK_HOLD.exists() else None
        if current_hold != previous_hold:
            return False, held_code() or "MANUAL_REVIEW_REQUIRED"
        RISK_HOLD.unlink(missing_ok=True)
        with resume_db() as connection:
            connection.execute("DELETE FROM resume_poll_state")
        return True, ""
    code = recruiter_failure_code(jobs)
    if code in STOP_CODES:
        pause_requests(code)
    return False, code


def safe_error_code(envelope, fallback):
    code = (envelope.get("error") or {}).get("code")
    return code if isinstance(code, str) and re.fullmatch(r"[A-Z_]{2,40}", code) else fallback


def recruiter_failure_code(envelope):
    error = envelope.get("error") or {}
    message = str(error.get("message") or "")
    if "IP地址存在异常行为" in message or "IP 地址存在异常行为" in message:
        return "IP_RESTRICTED"
    if any(word in message for word in ("安全验证", "验证码", "安全校验")):
        return "SECURITY_VERIFICATION_REQUIRED"
    return safe_error_code(envelope, "JOBS_UNAVAILABLE")


def verification_message(code):
    if code == "SESSION_MISMATCH":
        return "浏览器与保存的登录态不一致，已停止请求。请先检查官方页面，再手动同步；当前会话与索取记录已保留。"
    if code == "SESSION_UNAVAILABLE":
        return "暂时无法核对服务器浏览器会话，已停止请求并保留记录。请先检查浏览器连接，再手动同步。"
    if code == "PC_LOGIN_RESTRICTED":
        retry_at = pc_login_retry_at()
        if pc_login_restriction_reason() == "REALNAME_LINKED_ACCOUNT":
            return ("BOSS 官方提示：实名绑定的上一账号电脑端登录受限，当前账号也被同步限制。"
                    + (f"预计恢复时间：{retry_at}（北京时间）。" if retry_at else "")
                    + "本次登录已停止，后台请求已暂停。请先在 BOSS 官方 App 确认限制状态；系统不会自动重新登录。")
        return ("上次 BOSS 官方提示此账号的电脑端登录权限暂受限，后台自动请求已暂停。"
                + (f"当时预计恢复时间：{retry_at}（北京时间）。" if retry_at else "")
                + "你仍可点击“重新扫码”发起一次新的登录，以官方本次返回结果为准。")
    if code == "IP_RESTRICTED":
        return ("上次招聘接口返回“IP 地址存在异常行为”，已暂停自动请求并保留登录态。"
                "若远程浏览器已正常登录，请同步浏览器登录并验证；该提示不代表当前 IP 仍受限。")
    if code in ("BROWSER_LOGIN_REQUIRED", "AUTH_REQUIRED", "AUTH_EXPIRED"):
        return "登录尚未完成或已过期，自动请求已暂停。请在 BOSS 官方页面确认后，手动同步验证；当前会话已保留。"
    if code in STOP_CODES:
        return (f"招聘请求已暂停（{code}），当前会话和索取记录已保留。"
                "请先在 BOSS 官方页面确认账号和安全提示，再手动同步验证；系统不会自动重登或继续重试。")
    return f"招聘方职位接口暂未核验成功（{code}）。当前会话已保留，请同步浏览器登录并验证。"


def verify_recruiter():
    return recruiter_probe()[0]


def chrome_has_login_cookie():
    from patchright.sync_api import sync_playwright
    with sync_playwright() as playwright:
        browser = playwright.chromium.connect_over_cdp("http://127.0.0.1:9222", timeout=5000)
        try:
            return any(cookie.get("name") == "wt2" and cookie.get("value")
                       and cookie.get("domain", "").endswith("zhipin.com")
                       for context in browser.contexts for cookie in context.cookies())
        finally:
            browser.close()


def discard_login():
    try:
        # The pinned upstream TokenStore.clear() only removes session.enc.
        # Keep this local: never invoke an upstream logout command or an
        # account/session-revocation endpoint when disconnecting this server.
        (AUTH_DIR / "session.enc").unlink(missing_ok=True)
    finally:
        close_browser()
        # Never report reset success if old persisted cookies could be reused.
        # Stopping first also prevents open pages restoring a deleted cookie.
        clear_stored_boss_cookies()


def login_restriction_from_text(text):
    """The safety dialog takes precedence over 'scan successful' beneath it."""
    compact = re.sub(r"\s+", "", text)
    if not re.search(r"(?:关闭(?:了)?(?:PC|电脑)端登录权限|"
                     r"(?:PC|电脑)端登录权限(?:已|被)?关闭)", compact, re.I):
        for phrase, code in (("IP地址存在异常行为", "IP_RESTRICTED"),
                             ("安全验证", "SECURITY_VERIFICATION_REQUIRED"),
                             ("访问环境异常", "ENVIRONMENT_RISK"),
                             ("账号存在风险", "ACCOUNT_RISK")):
            if phrase in compact:
                return {"code": code, "retry_at": ""}
        return None
    match = re.search(r"预计开启时间[：:]\s*(\d{4}-\d\d-\d\d\s+\d\d:\d\d:\d\d)", text)
    restriction = {"code": "PC_LOGIN_RESTRICTED",
                   "retry_at": valid_retry_at(re.sub(r"\s+", " ", match[1])) if match else ""}
    if "实名绑定的上一账号" in compact and "同步限制" in compact:
        restriction["reason"] = "REALNAME_LINKED_ACCOUNT"
    return restriction


def observe_login_page_result():
    """Read the existing page over local CDP; never navigate, refresh or probe BOSS."""
    from websockets.sync.client import connect
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open("http://127.0.0.1:9222/json/list", timeout=3) as response:
        targets = json.load(response)
    for target in targets:
        parsed = urllib.parse.urlsplit(target.get("url", ""))
        if target.get("type") != "page" or parsed.hostname != "www.zhipin.com" \
                or parsed.path.rstrip("/") != "/web/user":
            continue
        endpoint = target.get("webSocketDebuggerUrl", "")
        ws_url = urllib.parse.urlsplit(endpoint)
        if ws_url.scheme != "ws" or ws_url.hostname != "127.0.0.1" or ws_url.port != 9222:
            continue
        with connect(endpoint, proxy=None, open_timeout=3, close_timeout=1) as websocket:
            websocket.send(json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {
                "expression": "(document.body?.innerText || '').slice(0,16000)",
                "returnByValue": True}}))
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                message = json.loads(websocket.recv(timeout=max(.01, deadline - time.monotonic())))
                if message.get("id") == 1:
                    text = message.get("result", {}).get("result", {}).get("value", "")
                    restriction = login_restriction_from_text(text) if isinstance(text, str) else None
                    if restriction:
                        return {"ok": True, "restriction": restriction}
                    break
    return {"ok": True}


def observe_login_page():
    # Process boundary also bounds an unavailable CDP connection.
    try:
        result = subprocess.run(
            [sys.executable, str(Path(__file__).resolve()), "--observe-login-page"],
            capture_output=True, timeout=10, check=False, env=CLI_ENV)
        envelope = json.loads(result.stdout) if len(result.stdout) <= 1024 else {}
        restriction = envelope.get("restriction") if isinstance(envelope, dict) else None
        if isinstance(restriction, dict) and restriction.get("code") in STOP_CODES:
            result = {"code": restriction["code"],
                      "retry_at": valid_retry_at(restriction.get("retry_at"))}
            if restriction.get("reason") == "REALNAME_LINKED_ACCOUNT":
                result["reason"] = restriction["reason"]
            return result
    except (OSError, ValueError, subprocess.TimeoutExpired):
        pass
    return None


def stop_login_process(process):
    # Only the CLI's private group; Chrome is an independent systemd service.
    for sig in (signal.SIGTERM, signal.SIGKILL):
        with contextlib.suppress(ProcessLookupError):
            os.killpg(process.pid, sig)
        try:
            process.communicate(timeout=5)
            return
        except subprocess.TimeoutExpired:
            pass
    for stream in (process.stdout, process.stderr):
        if stream:
            stream.close()


def wait_for_login_process(process):
    deadline = time.monotonic() + LOGIN_TIMEOUT + 45
    while True:
        if LOGIN_CANCEL.is_set():
            stop_login_process(process)
            return b""
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            stop_login_process(process)
            raise subprocess.TimeoutExpired("recruiter login", LOGIN_TIMEOUT + 45)
        try:
            stdout, _ = process.communicate(timeout=min(5, remaining))
            return stdout
        except subprocess.TimeoutExpired:
            restriction = observe_login_page()
            if restriction:
                pause_requests(restriction["code"], retry_at=restriction["retry_at"],
                               reason=restriction.get("reason", ""))
                LOGIN_CANCEL.set()
                stop_login_process(process)
                return b""


def login_snapshot():
    with LOGIN_LOCK:
        state = {key: value for key, value in LOGIN.items()
                 if key not in ("started", "qr_refreshed", "qr_ready")}
    if state.get("failure_code") == "PC_LOGIN_RESTRICTED":
        state.update(retry_at=pc_login_retry_at(), retry_allowed=True)
    return state


def record_login_result():
    state = login_snapshot()
    print(json.dumps({"event": "recruiter_login_result", "state": state["state"],
                      "code": state["failure_code"], "time": int(time.time())}), flush=True)


def login_worker():
    global LOGIN_PROCESS
    with LOCK:
        try:
            start_recovery_scope()  # Only this explicitly requested login worker.
            browser_service("start", "heyi-boss-chrome.service")
            wait_for_cdp()
            if LOGIN_CANCEL.is_set():
                return
            had_browser_login = chrome_has_login_cookie()
            if had_browser_login:
                envelope = sync_browser_login()
                ok = envelope.get("ok") is True
            else:
                prepared = subprocess.run(
                    [sys.executable, str(Path(__file__).resolve()), "--prepare-new-scan"],
                    capture_output=True, timeout=20, check=False, env=CLI_ENV)
                if prepared.returncode != 0:
                    raise RuntimeError("Unable to prepare a fresh login page")
                process = subprocess.Popen(
                    [BOSS, "--json", "login", "--cdp", "--timeout", str(LOGIN_TIMEOUT)],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=cli_environment(),
                    start_new_session=True)
                with LOGIN_LOCK:
                    LOGIN_PROCESS = process
                    LOGIN["qr_ready"] = True
                if LOGIN_CANCEL.is_set():
                    process.terminate()
                stdout = wait_for_login_process(process)
                if LOGIN_CANCEL.is_set():
                    return
                try:
                    envelope = json.loads(stdout)
                    ok = process.returncode == 0 and envelope.get("ok") is True
                except (UnicodeDecodeError, json.JSONDecodeError, AttributeError):
                    envelope = {}
                    ok = False
            if LOGIN_CANCEL.is_set():
                return
            code = safe_error_code(envelope, "LOGIN_INCOMPLETE")
            if not ok:
                observe_result(envelope)
            if not ok and not had_browser_login and code not in STOP_CODES:
                try:
                    if chrome_has_login_cookie():
                        had_browser_login = True
                        recovered = sync_browser_login()
                        ok = recovered.get("ok") is True
                        code = safe_error_code(recovered, "LOGIN_INCOMPLETE") if not ok else ""
                except (OSError, subprocess.TimeoutExpired):
                    code = "LOGIN_RECOVERY_FAILED"
            if LOGIN_CANCEL.is_set():
                return
            if ok:
                with LOGIN_LOCK:
                    LOGIN["phase"] = "verifying"
                try:
                    confirmed, code = recruiter_probe()
                except (OSError, subprocess.TimeoutExpired):
                    confirmed, code = False, "JOBS_TIMEOUT"
                if not confirmed:
                    if code == "ROLE_MISMATCH":
                        discard_login()
            with LOGIN_LOCK:
                LOGIN["state"] = ("complete" if ok and confirmed else
                                  "failed" if ok and code == "ROLE_MISMATCH" else
                                  "verification_pending" if ok or had_browser_login or code in STOP_CODES else "failed")
                LOGIN["phase"] = ""
                LOGIN["failure_code"] = "" if ok and confirmed else code
                LOGIN["error"] = ("" if ok and confirmed else
                                  "平台明确拒绝招聘方身份，请检查登录账号"
                                  if ok and code == "ROLE_MISMATCH" else
                                  verification_message(code) if ok or had_browser_login else
                                  f"登录凭据未建立（{code}）。请使用当前二维码扫码并在手机确认。")
        except (OSError, subprocess.TimeoutExpired, subprocess.CalledProcessError):
            if LOGIN_CANCEL.is_set():
                return
            with LOGIN_LOCK:
                LOGIN["state"] = "failed"
                LOGIN["phase"] = ""
                LOGIN["failure_code"] = "LOGIN_RUNTIME_ERROR"
                LOGIN["error"] = "扫码未完成或登录超时，请重新获取二维码"
        except Exception:
            # CDP libraries can throw their own exception types. A dead worker
            # must not leave "pending" forever or expose sensitive exception text.
            if not LOGIN_CANCEL.is_set():
                with LOGIN_LOCK:
                    LOGIN.update(state="failed", phase="", failure_code="LOGIN_RUNTIME_ERROR",
                                 error="登录连接中断，当前浏览器已保留，请检查官方页面后再试")
        finally:
            RECOVERY.active = False
            with LOGIN_LOCK:
                LOGIN_PROCESS = None
            record_login_result()


def verify_saved_login_worker():
    with LOCK:
        try:
            start_recovery_scope()
            browser_service("start", "heyi-boss-chrome.service")
            wait_for_cdp()
            if LOGIN_CANCEL.is_set():
                return
            synced = sync_browser_login()
            observe_result(synced)
            if LOGIN_CANCEL.is_set():
                return
            confirmed, code = (recruiter_probe() if synced.get("ok") is True else
                               (False, safe_error_code(synced, "BROWSER_SYNC_FAILED")))
            if LOGIN_CANCEL.is_set():
                return
            if not confirmed and code == "ROLE_MISMATCH":
                discard_login()
            with LOGIN_LOCK:
                LOGIN["state"] = ("complete" if confirmed else "failed"
                                  if code == "ROLE_MISMATCH" else "verification_pending")
                LOGIN["phase"] = ""
                LOGIN["failure_code"] = "" if confirmed else code
                LOGIN["error"] = ("" if confirmed else
                                  "平台明确拒绝招聘方身份，请检查登录账号"
                                  if code == "ROLE_MISMATCH" else verification_message(code))
        except (OSError, subprocess.TimeoutExpired, subprocess.CalledProcessError):
            with LOGIN_LOCK:
                LOGIN.update(state="verification_pending", phase="",
                             failure_code="JOBS_TIMEOUT",
                                     error=verification_message("JOBS_TIMEOUT"))
        except Exception:
            with LOGIN_LOCK:
                LOGIN.update(state="verification_pending", phase="",
                             failure_code="BROWSER_SYNC_FAILED",
                             error=verification_message("BROWSER_SYNC_FAILED"))
        finally:
            RECOVERY.active = False
            record_login_result()


def reserve_login_attempt():
    """Record explicit attempts; historical counts no longer lock manual scans."""
    LOGIN_ATTEMPTS_DB.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with contextlib.closing(sqlite3.connect(LOGIN_ATTEMPTS_DB, timeout=5)) as db:
        db.execute("CREATE TABLE IF NOT EXISTS attempts (at REAL NOT NULL)")
        LOGIN_ATTEMPTS_DB.chmod(0o600)
        db.execute("BEGIN IMMEDIATE")
        now = time.time()
        db.execute("DELETE FROM attempts WHERE at <= ?", (now - LOGIN_ATTEMPT_WINDOW,))
        db.execute("INSERT INTO attempts VALUES (?)", (now,))
        db.execute("DELETE FROM attempts WHERE rowid <= (SELECT MAX(rowid)-1000 FROM attempts)")
        db.commit()


def begin_login():
    with LOGIN_LOCK:
        if LOGIN["state"] == "pending":
            return dict(LOGIN)
        if not LOCK.acquire(blocking=False):
            raise RuntimeError("招聘账号正在处理查询，请稍后再试")
        LOCK.release()
        reserve_login_attempt()
        DISCONNECTED.unlink(missing_ok=True)
        LOGIN_CANCEL.clear()
        retry_verification = (LOGIN["state"] == "verification_pending"
                              and LOGIN["failure_code"] != "PC_LOGIN_RESTRICTED")
        LOGIN.update(state="pending", phase="verifying" if retry_verification else "qr",
                     started=time.monotonic(), error="", failure_code="", qr_ready=False)
        print(json.dumps({"event": "recruiter_login_started",
                          "mode": "sync" if retry_verification else "scan",
                          "time": int(time.time())}), flush=True)
        threading.Thread(target=verify_saved_login_worker if retry_verification else login_worker,
                         daemon=True).start()
        return dict(LOGIN)


def logout_boss():
    with LOGIN_LOCK:
        LOGIN_CANCEL.set()
        process = LOGIN_PROCESS
    if process and process.poll() is None:
        process.terminate()
    if not LOCK.acquire(timeout=12):
        raise RuntimeError("正在结束扫码，请稍后重试退出")
    try:
        # Persist before cleanup so refreshes, restarts and resume polling cannot
        # reconnect an account that the user deliberately disconnected.
        DISCONNECTED.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        DISCONNECTED.touch(mode=0o600, exist_ok=True)
        with LOGIN_LOCK:
            LOGIN.update(state="disconnected", phase="", started=0.0, error="", failure_code="")
        discard_login()
        # Clearing local cookies cannot lift a platform-imposed restriction.
        # A local reset cannot clear account/environment/session risk either.
    finally:
        LOCK.release()


class ScanInProgress(Exception):
    pass


class LoginRestricted(Exception):
    pass


def prepare_new_scan():
    """Discard only stale login tabs, never cookies, logged-in pages or Chrome."""
    from patchright.sync_api import sync_playwright
    with QR_LOCK, sync_playwright() as playwright:
        browser = playwright.chromium.connect_over_cdp("http://127.0.0.1:9222", timeout=5000)
        try:
            for context in browser.contexts:
                # A manual login may have completed while this child started.
                if any(cookie.get("name") == "wt2" and cookie.get("value")
                       and (cookie.get("domain") == "zhipin.com"
                            or cookie.get("domain", "").endswith(".zhipin.com"))
                       for cookie in context.cookies()):
                    continue
                pages = list(context.pages)
                old_login_pages = []
                for page in pages:
                    parsed = urllib.parse.urlsplit(page.url)
                    if parsed.hostname == "www.zhipin.com" and parsed.path.rstrip("/") == "/web/user":
                        old_login_pages.append(page)
                if old_login_pages and len(old_login_pages) == len(pages):
                    context.new_page()  # Keep Chrome alive when closing its last old login tab.
                for page in old_login_pages:
                    page.close(run_before_unload=False)
        finally:
            browser.close()


def qr_image(force_refresh=False):
    """Read the current QR without interrupting the server page's scan flow."""
    from patchright.sync_api import sync_playwright
    with QR_LOCK, sync_playwright() as playwright:
        browser = playwright.chromium.connect_over_cdp("http://127.0.0.1:9222", timeout=5000)
        try:
            for context in browser.contexts:
                if any(cookie.get("name") == "wt2" and cookie.get("value")
                       and cookie.get("domain", "").endswith("zhipin.com")
                       for cookie in context.cookies()):
                    with LOGIN_LOCK:
                        LOGIN["phase"] = "verifying"
                    raise ScanInProgress()
                for page in reversed(context.pages):
                    if page.url != "https://www.zhipin.com/web/user/":
                        continue
                    # After scanning, BOSS replaces the whole QR container.
                    # Read the login page's visible state before locating it;
                    # otherwise every poll times out while the phone awaits
                    # confirmation and the platform keeps showing a stale QR.
                    login_text = page.locator("body").inner_text(timeout=3000)
                    restriction = login_restriction_from_text(login_text)
                    if restriction:
                        pause_requests(restriction["code"], retry_at=restriction["retry_at"],
                                       reason=restriction.get("reason", ""))
                        LOGIN_CANCEL.set()
                        raise LoginRestricted()
                    if any(word in login_text for word in (
                            "已扫码", "扫描成功", "扫码成功", "请在App端确认",
                            "请在APP端确认", "请在手机上确认")):
                        with LOGIN_LOCK:
                            LOGIN["phase"] = "scanned"
                        raise ScanInProgress()
                    box = page.locator(".qr-img-box").first
                    text = box.inner_text(timeout=3000)
                    if any(word in text for word in ("已扫码", "扫描成功", "扫码成功", "手机确认", "请确认")):
                        with LOGIN_LOCK:
                            LOGIN["phase"] = "scanned"
                        raise ScanInProgress()
                    expired = any(word in text for word in ("已过期", "已失效", "重新扫描", "刷新二维码"))
                    if expired and box.locator(".invalid-box button").count():
                        box.locator(".invalid-box button").click(timeout=5000)
                        box.locator(".invalid-box").wait_for(state="hidden", timeout=8000)
                    elif force_refresh:
                        page.reload(wait_until="domcontentloaded", timeout=15000)
                        box = page.locator(".qr-img-box").first
                    qr = box.locator("img")
                    try:
                        qr.wait_for(state="visible", timeout=5000)
                    except Exception:
                        # A scan can hide the QR while the phone is awaiting confirmation.
                        # Never click the login tab here: that would invalidate the scanned QR.
                        text = box.inner_text(timeout=3000)
                        if any(word in text for word in ("已扫码", "扫描成功", "扫码成功", "手机确认", "请确认")):
                            with LOGIN_LOCK:
                                LOGIN["phase"] = "scanned"
                            raise ScanInProgress()
                        raise
                    with LOGIN_LOCK:
                        LOGIN["phase"] = "qr"
                    image = qr.screenshot(timeout=5000)
                    if not image.startswith(b"\x89PNG\r\n\x1a\n") or len(image) > 300_000:
                        raise ValueError("二维码图像无效")
                    return image
            raise ValueError("登录页尚未就绪")
        finally:
            browser.close()


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        raw = self.rfile.readline(MAX_REQUEST + 1)
        if not raw or len(raw) > MAX_REQUEST or not raw.endswith(b"\n"):
            response = {"ok": False, "error": {"code": "REQUEST_LIMIT", "message": "请求格式异常"}}
        else:
            try:
                request = json.loads(raw)
                if not isinstance(request, dict):
                    raise ValueError("请求必须为对象")
                response = execute(request)
            except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
                response = {"ok": False, "error": {"code": "INVALID_REQUEST", "message": "请求格式异常"}}
        self.wfile.write(json.dumps(response, ensure_ascii=False).encode() + b"\n")


class Server(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True


class HttpHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, _format, *_args):
        # Do not record cookies, queries, or resume data in service logs.
        pass

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_image(self, image):
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(len(image)))
        self.end_headers()
        self.wfile.write(image)

    def send_resume(self, output, friend_id, message_id):
        media = {".pdf": "application/pdf", ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 ".doc": "application/msword", ".png": "image/png", ".jpg": "image/jpeg"}
        try:
            size = output.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", media[output.suffix])
            self.send_header("Content-Disposition",
                             f'attachment; filename="resume-{friend_id}-{message_id}{output.suffix}"')
            self.send_header("Cache-Control", "private, no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with output.open("rb") as stream:
                while chunk := stream.read(65536):
                    self.wfile.write(chunk)
        finally:
            output.unlink(missing_ok=True)

    def authorized(self):
        cookie = self.headers.get("Cookie", "")
        if not cookie or len(cookie) > 4096:
            return 401
        request = urllib.request.Request(AUTH_URL, headers={
            "Cookie": cookie, "Accept": "application/json",
        })
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(request, timeout=4) as response:
                user = json.load(response).get("data") or {}
        except urllib.error.HTTPError as error:
            return 401 if error.code == 401 else 503
        except (OSError, ValueError, KeyError):
            return 503
        if (user.get("username") != AUTHORIZED_PHONE
                or user.get("organizationCode") != "heyi"
                or user.get("platformAdmin") is not True
                or "ADMIN" not in user.get("roles", [])):
            return 403
        self.desktop_user = user
        return 200

    def do_GET(self):
        if len(self.path) > MAX_REQUEST:
            self.send_json(414, {"success": False, "message": "查询条件过长"})
            return
        access = self.authorized()
        if access != 200:
            self.send_json(access, {"success": False, "message": "招聘模块仅限指定平台管理员"})
            return
        parsed = urllib.parse.urlsplit(self.path)
        route = parsed.path.removeprefix("/api/v1/hr/")
        if route in ("passive", "speeches", "resume-pool", "resume-pool/file"):
            self.passive_request(parsed=parsed)
            return
        if route in ("pool", "demands", "departments"):
            self.pool_request(write=False, parsed=parsed)
            return
        if route == "desktop/transport":
            self.send_json(200, {"success": True, "data": {"mode": "local", "port": 17861}})
            return
        if os.environ.get("APP_HR_DESKTOP_ONLY") == "1":
            self.send_json(409, {"success": False, "message": "招聘已切换到本机助手，请更新页面并连接助手"})
            return
        if route == "login/status":
            self.send_json(200, {"success": True, "data": login_snapshot()})
            return
        if route == "login/qr":
            with LOGIN_LOCK:
                pending = LOGIN["state"] == "pending"
                ready = LOGIN.get("qr_ready", True)
            if not pending:
                self.send_json(409, {"success": False, "message": "请先开始扫码登录"})
                return
            if not ready:
                self.send_json(503, {"success": False, "message": "正在打开新的登录页"})
                return
            try:
                self.send_image(qr_image())
            except LoginRestricted:
                self.send_json(409, {"success": False, "message":
                                    verification_message(held_code())})
            except ScanInProgress:
                with LOGIN_LOCK:
                    phase = LOGIN["phase"]
                self.send_json(202, {"success": True, "data": {"phase": phase}})
            except Exception:
                # Only fixed text is exposed; CDP errors can contain sensitive URLs.
                self.send_json(503, {"success": False, "message": "二维码正在生成，请稍后刷新"})
            return
        if route == "resumes":
            self.send_json(200, {"success": True, "data": tracked_resumes()})
            return
        if route.startswith("resumes/") and route.endswith("/download"):
            parts = route.split("/")
            if len(parts) != 4:
                self.send_json(404, {"success": False, "message": "没有这个招聘接口"})
                return
            try:
                friend_id, message_id = positive_id(parts[1]), positive_id(parts[2])
                output = download_resume(friend_id, message_id)
                self.send_resume(output, friend_id, message_id)
            except ValueError as error:
                self.send_json(400, {"success": False, "message": str(error)})
            except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
                self.send_json(502, {"success": False, "message": str(error)})
            return
        try:
            query = urllib.parse.parse_qs(parsed.query, strict_parsing=False, max_num_fields=8)
            get = lambda name: query.get(name, [""])[0]
            if route in {"status", "jobs", "applications", "candidates",
                         "recommendations", "chats", "resume"}:
                operation = route
                request = {"operation": operation, "page": get("page"),
                           "jobId": get("jobId"), "keyword": get("keyword"),
                           "geekId": get("geekId"), "securityId": get("securityId")}
            elif route.startswith("messages/"):
                request = {"operation": "messages", "friendId": route.removeprefix("messages/")}
            else:
                self.send_json(404, {"success": False, "message": "没有这个招聘接口"})
                return
            result = execute(request)
        except (ValueError, TypeError):
            self.send_json(400, {"success": False, "message": "查询条件格式不正确"})
            return
        if not result["ok"]:
            error = result["error"]
            self.send_json(400 if error["code"] == "INVALID_PARAM" else 502,
                           {"success": False, "message": error["message"]})
            return
        self.send_json(200, {"success": True, "data": result["result"], "message": "OK",
                             "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat()})

    def do_POST(self):
        if self.path in ("/api/v1/hr/passive/save", "/api/v1/hr/speeches/save",
                         "/api/v1/hr/resume-pool/review", "/api/v1/hr/resume-pool/analyze"):
            self.passive_request(write=True)
            return
        if self.path in ("/api/v1/hr/pool/talents", "/api/v1/hr/pool/projects",
                         "/api/v1/hr/demands/save", "/api/v1/hr/demands/generate", "/api/v1/hr/departments/save"):
            self.pool_request(write=True)
            return
        if self.path.startswith("/api/v1/hr/desktop/"):
            self.desktop_request()
            return
        if os.environ.get("APP_HR_DESKTOP_ONLY") == "1":
            self.send_json(409, {"success": False, "message": "招聘已切换到本机助手，服务器未执行操作"})
            return
        if self.path not in ("/api/v1/hr/login/start", "/api/v1/hr/login/refresh",
                             "/api/v1/hr/login/logout", "/api/v1/hr/resumes/request"):
            self.send_json(405, {"success": False, "message": "此阶段仅支持扫码登录与只读操作"})
            return
        if self.headers.get("Origin") != ALLOWED_ORIGIN:
            self.send_json(403, {"success": False, "message": "请求来源不正确"})
            return
        access = self.authorized()
        if access != 200:
            self.send_json(access, {"success": False, "message": "招聘模块仅限指定平台管理员"})
            return
        try:
            content_length = int(self.headers.get("Content-Length", "0") or 0)
        except ValueError:
            content_length = -1
        if self.path.endswith("/resumes/request"):
            if (not 1 <= content_length <= 256
                    or self.headers.get("Content-Type", "").split(";")[0] != "application/json"):
                self.send_json(400, {"success": False, "message": "请求格式不正确"})
                return
            try:
                body = json.loads(self.rfile.read(content_length))
                if (not isinstance(body, dict) or "friendId" not in body
                        or set(body) - {"friendId", "retryUncertain"}
                        or ("retryUncertain" in body and type(body["retryUncertain"]) is not bool)):
                    raise ValueError("请选择指定候选人的会话")
                result = request_resume(body["friendId"], retry_uncertain=body.get("retryUncertain", False))
            except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as error:
                self.send_json(400, {"success": False, "message": str(error)})
                return
            except (RuntimeError, OSError, sqlite3.Error, subprocess.TimeoutExpired) as error:
                self.send_json(502, {"success": False, "message": str(error)})
                return
            self.send_json(200, {"success": True, "data": result})
            return
        if content_length != 0:
            self.send_json(400, {"success": False, "message": "扫码请求不能包含正文"})
            return
        if self.path.endswith("/logout"):
            try:
                logout_boss()
            except (OSError, RuntimeError, subprocess.CalledProcessError):
                self.send_json(503, {"success": False, "message": "退出失败，请稍后重试"})
                return
            self.send_json(200, {"success": True, "data": {"state": "disconnected"}})
        elif self.path.endswith("/refresh"):
            with LOGIN_LOCK:
                pending = LOGIN["state"] == "pending"
            if not pending:
                self.send_json(409, {"success": False, "message": "当前没有待扫码的登录"})
                return
            try:
                qr_image(force_refresh=True)
            except LoginRestricted:
                self.send_json(409, {"success": False, "message":
                                    verification_message(held_code())})
            except ScanInProgress:
                self.send_json(409, {"success": False,
                                     "message": "已扫码或正在校验，请等待手机确认和招聘方验证"})
                return
            except Exception:
                self.send_json(503, {"success": False, "message": "二维码刷新失败，请稍后重试"})
                return
            self.send_json(200, {"success": True, "data": {"refreshed": True}})
        else:
            try:
                state = begin_login()
            except RuntimeError as error:
                self.send_json(409, {"success": False, "message": str(error)})
                return
            self.send_json(202, {"success": True, "data": {"state": state["state"]}})

    def pool_request(self, write=False, parsed=None):
        import talent_pool
        import recruitment_demands
        import hr_departments
        if write:
            if self.headers.get("Origin") != ALLOWED_ORIGIN:
                self.send_json(403, {"success": False, "message": "请求来源不正确"})
                return
            access = self.authorized()
            if access != 200:
                self.send_json(access, {"success": False, "message": "招聘模块仅限指定平台管理员"})
                return
        try:
            if write:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 131072:
                    raise ValueError("资料内容过长或为空")
                if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                    raise ValueError("请求格式不正确")
                body = json.loads(self.rfile.read(length))
                if self.path == "/api/v1/hr/demands/generate":
                    result = recruitment_demands.generate(self.desktop_user, body, self.headers.get("Cookie", ""))
                elif self.path == "/api/v1/hr/demands/save":
                    result = recruitment_demands.save(self.desktop_user, body,
                        hr_departments.listing(self.desktop_user)["items"])
                elif self.path == "/api/v1/hr/departments/save":
                    result = hr_departments.save(self.desktop_user, body)
                else:
                    action = talent_pool.save_project if self.path.endswith("/projects") else talent_pool.save
                    result = action(self.desktop_user, body)
            else:
                query = {key: values[-1] for key, values in urllib.parse.parse_qs(parsed.query, keep_blank_values=True).items()}
                if parsed.path.endswith("/departments"):
                    result = hr_departments.listing(self.desktop_user)
                else:
                    action = recruitment_demands.listing if parsed.path.endswith("/demands") else talent_pool.listing
                    result = action(self.desktop_user, query)
            self.send_json(200, {"success": True, "data": result})
        except talent_pool.Conflict as error:
            self.send_json(409, {"success": False, "message": str(error)})
        except (ValueError, TypeError, UnicodeError) as error:
            self.send_json(400, {"success": False, "message": str(error)})
        except (OSError, sqlite3.Error):
            self.send_json(503, {"success": False, "message": "人才库暂时不可用，请稍后重试"})

    def passive_request(self, write=False, parsed=None):
        import passive_pool
        import talent_pool
        import resume_storage
        try:
            if write:
                if self.headers.get("Origin") != ALLOWED_ORIGIN or self.authorized() != 200:
                    self.send_json(403, {"success": False, "message": "请先登录有招聘权限的管理员账号"})
                    return
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 262144: raise ValueError("请求内容过长")
                body = json.loads(self.rfile.read(length))
                if self.path.endswith("/passive/save"): result = passive_pool.save_rule(self.desktop_user, body)
                elif self.path.endswith("/speeches/save"): result = passive_pool.save_speech(self.desktop_user, body)
                elif self.path.endswith("/review"): result = passive_pool.review(self.desktop_user, body)
                else: result = passive_pool.analyze(self.desktop_user, body, self.headers.get("Cookie", ""))
            else:
                kind = parsed.path.removeprefix("/api/v1/hr/")
                query = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}
                if kind == "resume-pool/file":
                    raw, filename, extension = passive_pool.get_file(self.desktop_user, query.get("id"))
                    self.send_response(200)
                    self.send_header("Content-Type", resume_storage.TYPES[extension])
                    self.send_header("Cache-Control", "private, no-store")
                    self.send_header("X-Content-Type-Options", "nosniff")
                    disposition = "inline" if extension in ("pdf", "png", "jpg", "jpeg") else "attachment"
                    self.send_header("Content-Disposition", disposition + "; filename*=UTF-8''" + urllib.parse.quote(filename))
                    self.send_header("Content-Length", str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)
                    return
                result = passive_pool.listing(self.desktop_user, kind, query)
            self.send_json(200, {"success": True, "data": result})
        except talent_pool.Conflict as error:
            self.send_json(409, {"success": False, "message": str(error)})
        except (ValueError, TypeError, AttributeError):
            self.send_json(400, {"success": False, "message": "资料不完整或状态已变化，请刷新后核对"})
        except Exception:
            self.send_json(503, {"success": False, "message": "简历服务暂时不可用，请稍后重试；未发送 BOSS 消息"})

    def desktop_request(self):
        import desktop_bridge
        route = self.path.removeprefix("/api/v1/hr/desktop/")
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if route in ("passive-config", "passive-ingest", "passive-report", "passive-claim"):
                import passive_pool
                if not 0 < length <= (36 * 1024 * 1024 if route == "passive-ingest" else 262144):
                    raise ValueError("连接请求过长")
                body = json.loads(self.rfile.read(length))
                user = desktop_bridge.check(body.get("token"), body.get("device"))["user"]
                handlers = {"passive-config": passive_pool.device_config, "passive-ingest": passive_pool.ingest,
                            "passive-report": passive_pool.device_report, "passive-claim": passive_pool.claim_command}
                result = handlers[route](user, body)
                self.send_json(200, {"success": True, "data": result})
                return
            if not 0 <= length <= 1024:
                raise ValueError("连接请求过长")
            if route == "pair":
                if self.headers.get("Origin") != ALLOWED_ORIGIN or self.authorized() != 200:
                    self.send_json(403, {"success": False, "message": "请先登录有招聘权限的管理员账号"})
                    return
                if length:
                    raise ValueError("连接请求不能包含正文")
                result = desktop_bridge.issue(self.desktop_user,
                    automatic=self.headers.get("X-Heyi-Auto-Connect") == "1")
            else:
                if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                    raise ValueError("连接请求格式不正确")
                body = json.loads(self.rfile.read(length))
                if route == "redeem":
                    result = desktop_bridge.redeem(body.get("ticket"), body.get("device"))
                elif route == "lease":
                    result = desktop_bridge.check(body.get("token"), body.get("device"))
                elif route == "release":
                    result = desktop_bridge.release(body.get("token"), body.get("device"))
                else:
                    raise ValueError("未知连接操作")
            self.send_json(200, {"success": True, "data": result})
        except desktop_bridge.DeviceConflict:
            self.send_json(409, {"success": False, "code": "DEVICE_IN_USE",
                                "message": "另一台电脑正在使用助手，请手动确认切换设备"})
        except (ValueError, TypeError, AttributeError):
            self.send_json(409, {"success": False, "message": "连接凭证无效、已到期或已切换设备，请重新连接本机助手"})
        except (OSError, sqlite3.Error):
            self.send_json(503, {"success": False, "message": "连接服务暂时不可用，未执行招聘操作"})
        except Exception:
            self.send_json(503, {"success": False, "message": "简历同步暂未完成，本机记录已保留"})


class HttpServer(http.server.ThreadingHTTPServer):
    daemon_threads = True


def restore_login_state():
    # Chrome is independent: a connector restart must not close a user's
    # manually scanned session. Restore the hold, then await explicit verification.
    if DISCONNECTED.is_file():
        with LOGIN_LOCK:
            LOGIN.update(state="disconnected", phase="", failure_code="", error="")
    elif RISK_HOLD.is_file():
        code = held_code()
        with LOGIN_LOCK:
            LOGIN.update(state="verification_pending", phase="", failure_code=code,
                         error=verification_message(code))
    elif (AUTH_DIR / "session.enc").is_file():
        with LOGIN_LOCK:
            LOGIN.update(state="verification_pending", phase="", failure_code="MANUAL_REVIEW_REQUIRED",
                         error=verification_message("MANUAL_REVIEW_REQUIRED"))


def main():
    import recruitment_demands
    import passive_pool
    recruitment_demands.recover_interrupted()
    passive_pool.recover()
    restore_login_state()
    with resume_db():
        pass
    SOCKET.parent.mkdir(mode=0o710, parents=True, exist_ok=True)
    try:
        SOCKET.unlink()
    except FileNotFoundError:
        pass
    with Server(str(SOCKET), Handler) as server:
        os.chown(SOCKET, -1, grp.getgrnam("heyi-boss-client").gr_gid)
        os.chmod(SOCKET, 0o660)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        threading.Thread(target=resume_poll_worker, daemon=True).start()
        with HttpServer((HTTP_BIND, HTTP_PORT), HttpHandler) as http:
            http.serve_forever()


if __name__ == "__main__":
    if sys.argv[1:] == ["--prepare-new-scan"]:
        try:
            prepare_new_scan()
        except Exception:
            raise SystemExit(1)
    elif sys.argv[1:] == ["--observe-login-page"]:
        try:
            print(json.dumps(observe_login_page_result()))
        except Exception:
            print(json.dumps({"ok": False}))
    elif sys.argv[1:] == ["--sync-browser-login"]:
        print(json.dumps(sync_browser_login_result()))
    elif sys.argv[1:] == ["--prepare-recruiter-chat"]:
        print(json.dumps(prepare_recruiter_chat_result()))
    else:
        main()
