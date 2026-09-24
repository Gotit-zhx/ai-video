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
import urllib.error
import urllib.parse
import urllib.request
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
LOGIN_TIMEOUT = 180
LOGIN_LOCK = threading.Lock()
QR_LOCK = threading.Lock()
QR_REFRESH_SECONDS = 40
LOGIN = {"state": "idle", "started": 0.0, "qr_refreshed": 0.0, "error": ""}
LOGIN_PROCESS = None
LOGIN_CANCEL = threading.Event()
ALLOWED_ORIGIN = os.environ.get("APP_HR_ALLOWED_ORIGIN", "https://hoyee.org.cn")
CLI_ENV = {"HOME": "/opt/heyi/boss-agent",
           "PATH": "/opt/heyi/boss-agent/venv/bin:/usr/bin:/bin"}
SYSTEMCTL = ["/usr/bin/sudo", "-n", "/usr/bin/systemctl"]


def identifier(value):
    text = str(value or "").strip()
    if not ID.fullmatch(text):
        raise ValueError("标识格式不正确")
    return text


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
    if request.get("operation") == "status" and login_state == "pending":
        return {"ok": True, "result": {"ok": False, "error": {
            "code": "LOGIN_PENDING", "message": "正在等待扫码和招聘方身份验证"}}}
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
            health = ((envelope.get("data") or {}).get("auth_health") or {}).get("summary")
            if health == "healthy" and not verified:
                if verify_recruiter():
                    with LOGIN_LOCK:
                        LOGIN["state"] = "complete"
                else:
                    discard_login()
                    with LOGIN_LOCK:
                        LOGIN["state"] = "failed"
                    return {"ok": True, "result": {"ok": False, "error": {
                        "code": "RECRUITER_REQUIRED",
                        "message": "当前账号未通过 BOSS 招聘方身份验证，请使用招聘账号扫码"}}}
            elif health != "healthy":
                discard_login()
                with LOGIN_LOCK:
                    LOGIN["state"] = "failed"
                return {"ok": True, "result": {"ok": False, "error": {
                    "code": "RECRUITER_REQUIRED",
                    "message": "当前账号未通过招聘方身份验证；求职账号不能接入"}}}
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


def verify_recruiter():
    status = cli_json("status", timeout=20)
    health = (status.get("data") or {}).get("auth_health") or {}
    if status.get("ok") is not True or health.get("summary") != "healthy":
        return False
    jobs = cli_json("hr", "jobs", "list", timeout=45)
    return jobs.get("ok") is True and jobs.get("command") == "recruiter-jobs-list"


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
            stdout, _ = process.communicate(timeout=LOGIN_TIMEOUT + 45)
            try:
                envelope = json.loads(stdout)
                ok = process.returncode == 0 and envelope.get("ok") is True
            except (UnicodeDecodeError, json.JSONDecodeError, AttributeError):
                ok = False
            if LOGIN_CANCEL.is_set():
                return
            if ok:
                ok = verify_recruiter()
            if not ok:
                discard_login()
            with LOGIN_LOCK:
                LOGIN["state"] = "complete" if ok else "failed"
                LOGIN["error"] = "" if ok else (
                    "未通过招聘方身份验证。求职账号不能接入，请切换 BOSS 招聘方账号重新扫码")
        except (OSError, subprocess.TimeoutExpired, subprocess.CalledProcessError):
            try:
                discard_login()
            except Exception:
                pass
            with LOGIN_LOCK:
                LOGIN["state"] = "failed"
                LOGIN["error"] = "扫码未完成或登录超时，请重新获取二维码"
        finally:
            with LOGIN_LOCK:
                LOGIN_PROCESS = None


def begin_login():
    with LOGIN_LOCK:
        if LOGIN["state"] == "pending":
            return dict(LOGIN)
        if not LOCK.acquire(blocking=False):
            raise RuntimeError("招聘账号正在处理查询，请稍后再试")
        LOCK.release()
        LOGIN_CANCEL.clear()
        LOGIN.update(state="pending", started=time.monotonic(),
                     qr_refreshed=time.monotonic(), error="")
        threading.Thread(target=login_worker, daemon=True).start()
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
            LOGIN.update(state="idle", started=0.0, qr_refreshed=0.0, error="")
    finally:
        LOCK.release()


def qr_image(force_refresh=False):
    """Capture only the QR element from the dedicated CDP login page."""
    from patchright.sync_api import sync_playwright
    with QR_LOCK, sync_playwright() as playwright:
        browser = playwright.chromium.connect_over_cdp("http://127.0.0.1:9222", timeout=5000)
        try:
            for context in browser.contexts:
                for page in reversed(context.pages):
                    if page.url != "https://www.zhipin.com/web/user/":
                        continue
                    with LOGIN_LOCK:
                        stale = time.monotonic() - LOGIN["qr_refreshed"] >= QR_REFRESH_SECONDS
                    if force_refresh or stale:
                        page.reload(wait_until="domcontentloaded", timeout=15000)
                        with LOGIN_LOCK:
                            LOGIN["qr_refreshed"] = time.monotonic()
                    qr = page.locator(".qr-img-box img")
                    try:
                        qr.wait_for(state="visible", timeout=5000)
                    except Exception:
                        if page.get_by_text("APP扫码登录", exact=True).first.is_visible():
                            page.get_by_text("APP扫码登录", exact=True).first.click(timeout=3000)
                    qr.wait_for(state="visible", timeout=5000)
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
            except Exception:
                # Only fixed text is exposed; CDP errors can contain sensitive URLs.
                self.send_json(503, {"success": False, "message": "二维码正在生成，请稍后刷新"})
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
                             "/api/v1/hr/login/logout"):
            self.send_json(405, {"success": False, "message": "此阶段仅支持扫码登录与只读操作"})
            return
        if self.headers.get("Origin") != ALLOWED_ORIGIN:
            self.send_json(403, {"success": False, "message": "请求来源不正确"})
            return
        access = self.authorized()
        if access != 200:
            self.send_json(access, {"success": False, "message": "招聘模块仅限指定平台管理员"})
            return
        if int(self.headers.get("Content-Length", "0") or 0) != 0:
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
        with HttpServer((HTTP_BIND, HTTP_PORT), HttpHandler) as http:
            http.serve_forever()


if __name__ == "__main__":
    main()
