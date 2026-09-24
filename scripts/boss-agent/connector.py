#!/opt/heyi/boss-agent/venv/bin/python
"""Local-only, allowlisted JSON bridge for the Hoyee recruiter workspace."""

import json
import os
import re
import time
import grp
import socketserver
import subprocess
import threading
import datetime
import http.server
import contextlib
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
ID = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_\-=]{0,179}$")
SECRET_KEY = re.compile(r"(cookie|token|stoken|wt2|secret|authorization|password)", re.I)
LOGIN_TIMEOUT = 300
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
RESUME_POLL_SECONDS = 120


def identifier(value):
    text = str(value or "").strip()
    if not ID.fullmatch(text):
        raise ValueError("标识格式不正确")
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
        message_id TEXT, updated_at INTEGER NOT NULL, error TEXT NOT NULL DEFAULT ''
    )""")
    os.chmod(RESUME_DB, 0o600)
    try:
        with connection:
            yield connection
    finally:
        connection.close()


def tracked_resumes():
    with resume_db() as connection:
        rows = connection.execute(
            "SELECT friend_id, requested_at, state, message_id, updated_at, error "
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


def resume_cli(*args, timeout=60):
    result = cli_json(*args, timeout=timeout)
    if result.get("ok") is not True:
        raise RuntimeError((result.get("error") or {}).get("message") or "BOSS 请求未获成功确认")
    return result


def request_resume(friend_id):
    friend_id = positive_id(friend_id)
    with LOGIN_LOCK:
        if LOGIN["state"] != "complete":
            raise RuntimeError("请先连接招聘方账号")
    if not LOCK.acquire(timeout=5):
        raise RuntimeError("招聘账号正在处理另一项请求")
    try:
        with resume_db() as connection:
            previous = connection.execute("SELECT state FROM resume_requests WHERE friend_id=?",
                                          (friend_id,)).fetchone()
        if previous and previous["state"] in ("waiting", "received", "uncertain"):
            raise ValueError("该会话已有未完成的简历请求，请先核对消息，避免重复索取")
        history = resume_cli("hr", "chatmsg", friend_id, "--count", "100")
        baseline = latest_message_id(history)
        try:
            resume_cli("hr", "request-resume", friend_id, timeout=90)
        except Exception:
            # A lost write response is ambiguous. Never offer an automatic resend.
            now = int(time.time())
            with resume_db() as connection:
                connection.execute(
                    "INSERT OR REPLACE INTO resume_requests VALUES (?, ?, ?, 'uncertain', NULL, ?, ?)",
                    (friend_id, now, baseline, now, "索取结果未确认；请在 BOSS 官方会话核对"))
            raise RuntimeError("索取结果未确认，请在 BOSS 官方会话核对，不要重复发送")
        now = int(time.time())
        with resume_db() as connection:
            connection.execute(
                "INSERT OR REPLACE INTO resume_requests VALUES (?, ?, ?, 'waiting', NULL, ?, '')",
                (friend_id, now, baseline, now))
        return {"friendId": friend_id, "state": "waiting"}
    finally:
        LOCK.release()


def poll_resumes_once():
    with resume_db() as connection:
        pending = connection.execute(
            "SELECT friend_id, baseline_mid FROM resume_requests WHERE state IN ('waiting','uncertain') "
            "ORDER BY updated_at ASC LIMIT 20").fetchall()
    if not pending:
        return
    with LOGIN_LOCK:
        state = LOGIN["state"]
    if state != "complete":
        if state != "idle" or not LOCK.acquire(blocking=False):
            return
        try:
            healthy = ((cli_json("status", timeout=20).get("data") or {})
                       .get("auth_health") or {}).get("summary") == "healthy"
            verified = healthy and verify_recruiter()
            if verified:
                with LOGIN_LOCK:
                    if LOGIN["state"] == "idle":
                        LOGIN["state"] = "complete"
        finally:
            LOCK.release()
        if not verified:
            return
    for row in pending:
        if not LOCK.acquire(timeout=1):
            return
        try:
            result = cli_json("hr", "chatmsg", row["friend_id"], "--count", "100", timeout=45)
        finally:
            LOCK.release()
        if result.get("ok") is not True:
            # Leave the request waiting; a transient read must not erase it.
            code = (result.get("error") or {}).get("code")
            if code in ("AUTH_REQUIRED", "AUTH_EXPIRED"):
                with resume_db() as connection:
                    connection.execute(
                        "UPDATE resume_requests SET error=? WHERE friend_id=? "
                        "AND state IN ('waiting','uncertain')",
                        ("招聘账号登录已过期，重新登录后继续检测", row["friend_id"]))
            continue
        mid = incoming_attachment(result, row["friend_id"], row["baseline_mid"])
        if mid:
            with resume_db() as connection:
                connection.execute(
                    "UPDATE resume_requests SET state='received', message_id=?, updated_at=?, error='' "
                    "WHERE friend_id=? AND state IN ('waiting','uncertain')",
                    (mid, int(time.time()), row["friend_id"]))


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
            args.extend(["--security-id", identifier(request["securityId"])])
        return args
    raise ValueError("不支持的只读操作")


def execute(request):
    try:
        args = command(request)
    except (TypeError, ValueError) as error:
        return {"ok": False, "error": {"code": "INVALID_PARAM", "message": str(error)}}
    with LOGIN_LOCK:
        login_state = LOGIN["state"]
        verified = login_state == "complete"
    if request.get("operation") == "status" and login_state in ("pending", "verification_pending"):
        return {"ok": True, "result": {"ok": False, "error": {
            "code": "LOGIN_PENDING", "message": "正在等待招聘方身份验证"}}}
    if request.get("operation") != "status" and not verified:
        return {"ok": False, "error": {
            "code": "RECRUITER_REQUIRED",
            "message": "请先用 BOSS 招聘方账号扫码并通过身份验证"}}
    if not LOCK.acquire(timeout=5):
        return {"ok": False, "error": {"code": "BUSY", "message": "招聘账号正在处理另一项请求，请稍后重试"}}
    try:
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
        if request.get("operation") == "status" and envelope.get("ok") is True:
            if not verified:
                confirmed, code = recruiter_probe()
                if confirmed:
                    with LOGIN_LOCK:
                        LOGIN["state"] = "complete"
                else:
                    if code == "ROLE_MISMATCH":
                        discard_login()
                        with LOGIN_LOCK:
                            LOGIN.update(state="failed", failure_code=code,
                                         error="平台明确拒绝招聘方身份，请检查登录账号")
                        return {"ok": True, "result": {"ok": False, "error": {
                            "code": "RECRUITER_REQUIRED",
                            "message": "平台明确拒绝招聘方身份，请检查登录账号"}}}
                    with LOGIN_LOCK:
                        LOGIN.update(state="verification_pending", failure_code=code,
                                     error=verification_message(code))
                    return {"ok": True, "result": {"ok": False, "error": {
                        "code": "RECRUITER_VERIFICATION_PENDING",
                        "message": verification_message(code)}}}
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


def clear_boss_cookies():
    from patchright.sync_api import sync_playwright
    with sync_playwright() as playwright:
        browser = playwright.chromium.connect_over_cdp("http://127.0.0.1:9222", timeout=5000)
        try:
            for context in browser.contexts:
                context.clear_cookies(domain=re.compile(r"(^|\.)zhipin\.com$"))
                for page in list(context.pages):
                    if "zhipin.com" in page.url:
                        page.close()
        finally:
            browser.close()


def cli_json(*args, timeout=30):
    result = subprocess.run([BOSS, "--json", *args], capture_output=True,
                            timeout=timeout, check=False, env=CLI_ENV)
    if len(result.stdout) > MAX_OUTPUT:
        return {}
    try:
        return json.loads(result.stdout)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {}


def recruiter_probe():
    status = cli_json("status", timeout=20)
    if status.get("ok") is not True:
        return False, safe_error_code(status, "AUTH_UNAVAILABLE")
    jobs = cli_json("hr", "jobs", "list", timeout=45)
    if jobs.get("ok") is True and jobs.get("command") == "recruiter-jobs-list":
        return True, ""
    return False, safe_error_code(jobs, "JOBS_UNAVAILABLE")


def safe_error_code(envelope, fallback):
    code = (envelope.get("error") or {}).get("code")
    return code if isinstance(code, str) and re.fullmatch(r"[A-Z_]{2,40}", code) else fallback


def verification_message(code):
    return f"扫码凭据已保存，招聘方职位接口暂未核验成功（{code}）。登录态已保留，请重试验证。"


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
        cli_json("logout", timeout=15)
    finally:
        try:
            clear_boss_cookies()
        except Exception:
            pass
        close_browser()


def login_worker():
    global LOGIN_PROCESS
    with LOCK:
        try:
            browser_service("start", "heyi-boss-chrome.service")
            wait_for_cdp()
            if LOGIN_CANCEL.is_set():
                return
            process = subprocess.Popen(
                [BOSS, "--json", "login", "--cdp", "--force", "--timeout", str(LOGIN_TIMEOUT)],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=CLI_ENV)
            with LOGIN_LOCK:
                LOGIN_PROCESS = process
            if LOGIN_CANCEL.is_set():
                process.terminate()
            try:
                stdout, _ = process.communicate(timeout=LOGIN_TIMEOUT + 45)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
                raise
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
                try:
                    if chrome_has_login_cookie():
                        recovered = cli_json("login", "--cdp", "--timeout", "45", timeout=65)
                        ok = recovered.get("ok") is True
                        code = safe_error_code(recovered, "LOGIN_INCOMPLETE") if not ok else ""
                except (OSError, subprocess.TimeoutExpired):
                    code = "LOGIN_RECOVERY_FAILED"
            if ok:
                with LOGIN_LOCK:
                    LOGIN["phase"] = "verifying"
                for attempt in range(3):
                    try:
                        confirmed, code = recruiter_probe()
                    except (OSError, subprocess.TimeoutExpired):
                        confirmed, code = False, "JOBS_TIMEOUT"
                    if confirmed or code in ("ACCOUNT_RISK", "ENVIRONMENT_RISK"):
                        break
                    if attempt < 2:
                        time.sleep(5 * (attempt + 1))
                if not confirmed:
                    if code == "ROLE_MISMATCH":
                        discard_login()
                    else:
                        close_browser()
            else:
                # Only clear an incomplete CLI login, not a successfully scanned
                # account whose recruiter endpoint could not be reached.
                cli_json("logout", timeout=15)
                close_browser()
            with LOGIN_LOCK:
                LOGIN["state"] = ("complete" if ok and confirmed else
                                  "failed" if ok and code == "ROLE_MISMATCH" else
                                  "verification_pending" if ok else "failed")
                LOGIN["phase"] = ""
                LOGIN["failure_code"] = "" if ok and confirmed else code
                LOGIN["error"] = ("" if ok and confirmed else
                                  "平台明确拒绝招聘方身份，请检查登录账号"
                                  if ok and code == "ROLE_MISMATCH" else
                                  verification_message(code) if ok else
                                  f"登录凭据未建立（{code}）。请使用当前二维码扫码并在手机确认。")
        except (OSError, subprocess.TimeoutExpired, subprocess.CalledProcessError):
            try:
                close_browser()
            except Exception:
                pass
            with LOGIN_LOCK:
                LOGIN["state"] = "failed"
                LOGIN["phase"] = ""
                LOGIN["failure_code"] = "LOGIN_RUNTIME_ERROR"
                LOGIN["error"] = "扫码未完成或登录超时，请重新获取二维码"
        finally:
            with LOGIN_LOCK:
                LOGIN_PROCESS = None


def verify_saved_login_worker():
    with LOCK:
        try:
            browser_service("start", "heyi-boss-chrome.service")
            wait_for_cdp()
            confirmed, code = recruiter_probe()
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
            if not confirmed and code != "ROLE_MISMATCH":
                close_browser()
        except (OSError, subprocess.TimeoutExpired, subprocess.CalledProcessError):
            with LOGIN_LOCK:
                LOGIN.update(state="verification_pending", phase="",
                             failure_code="JOBS_TIMEOUT",
                             error=verification_message("JOBS_TIMEOUT"))
            try:
                close_browser()
            except Exception:
                pass


def begin_login():
    with LOGIN_LOCK:
        if LOGIN["state"] == "pending":
            return dict(LOGIN)
        if not LOCK.acquire(blocking=False):
            raise RuntimeError("招聘账号正在处理查询，请稍后再试")
        LOCK.release()
        LOGIN_CANCEL.clear()
        retry_verification = LOGIN["state"] == "verification_pending"
        LOGIN.update(state="pending", phase="verifying" if retry_verification else "qr",
                     started=time.monotonic(), error="", failure_code="")
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
        discard_login()
        with LOGIN_LOCK:
            LOGIN.update(state="idle", phase="", started=0.0, error="", failure_code="")
    finally:
        LOCK.release()


class ScanInProgress(Exception):
    pass


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
        return 200

    def do_GET(self):
        if len(self.path) > 2048:
            self.send_json(414, {"success": False, "message": "查询条件过长"})
            return
        access = self.authorized()
        if access != 200:
            self.send_json(access, {"success": False, "message": "招聘模块仅限指定平台管理员"})
            return
        parsed = urllib.parse.urlsplit(self.path)
        route = parsed.path.removeprefix("/api/v1/hr/")
        if route == "login/status":
            with LOGIN_LOCK:
                state = {key: value for key, value in LOGIN.items()
                         if key not in ("started", "qr_refreshed")}
            self.send_json(200, {"success": True, "data": state})
            return
        if route == "login/qr":
            with LOGIN_LOCK:
                pending = LOGIN["state"] == "pending"
            if not pending:
                self.send_json(409, {"success": False, "message": "请先开始扫码登录"})
                return
            try:
                self.send_image(qr_image())
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
                if not isinstance(body, dict) or set(body) != {"friendId"}:
                    raise ValueError("请选择指定候选人的会话")
                result = request_resume(body["friendId"])
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
            self.send_json(200, {"success": True, "data": {"state": "idle"}})
        elif self.path.endswith("/refresh"):
            with LOGIN_LOCK:
                pending = LOGIN["state"] == "pending"
            if not pending:
                self.send_json(409, {"success": False, "message": "当前没有待扫码的登录"})
                return
            try:
                qr_image(force_refresh=True)
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


class HttpServer(http.server.ThreadingHTTPServer):
    daemon_threads = True


def main():
    # A service restart kills its login worker but not the separate Chrome unit.
    # Close that orphan before accepting a new QR session.
    close_browser()
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
    main()
