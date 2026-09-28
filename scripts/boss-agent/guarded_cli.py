#!/opt/heyi/boss-agent/venv/bin/python
"""Restrict the pinned SDK without changing its installed files or risk checks."""

import contextlib
import fcntl
import functools
import hashlib
import importlib.metadata
import json
import os
import re
import sqlite3
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

BUDGET_DB = Path("/opt/heyi/boss-agent/runtime/platform-request-budget.sqlite3")
AUDIT_DB = Path("/opt/heyi/boss-agent/runtime/platform-request-audit.sqlite3")
RISK_HOLD = Path("/opt/heyi/boss-agent/runtime/recruiter-ip-risk-hold")
DISCONNECTED = Path("/opt/heyi/boss-agent/runtime/recruiter-disconnected")
REQUEST_LOCK = Path("/opt/heyi/boss-agent/runtime/platform-request.lock")
AUTH_DIR = Path("/opt/heyi/boss-agent/accounts/pilot/data/auth")
CDP_URL = "http://127.0.0.1:9222"
RECOVERY_ENV = "HEYI_HR_RECOVERY_HOLD"
STOP_CODES = {
    "ACCOUNT_RISK", "ENVIRONMENT_RISK", "TOKEN_REFRESH_FAILED", "RATE_LIMITED",
    "REQUEST_BUDGET_EXHAUSTED", "SESSION_MISMATCH", "SESSION_UNAVAILABLE",
}
MIN_INTERVAL = 6
HOURLY_LIMIT = 120
REVIEWED_SOURCES = {
    "api/_base_client.py": "e0e2a88e1159d62f1d836fefebb51498d8afe58e20214e4d85e240b313488518",
    "api/recruiter_client.py": "140dfad3189cbd7b29dae33cdb3adfd9f17f2a3969c22a5fc31fcb3b79b2856a",
    "api/browser_client.py": "fd18ddfc70f3a40371b448e12848882a4bbfff28c18b735b74a3e8efd3956601",
    "auth/manager.py": "b835c4f4eb7592b4725ba8e2ee242433c7da4b033cc1877d1c49e53fe03e7d36",
    "auth/browser.py": "3e4103121987b4b05488ec0f97f2bd5292762dbb078da30c7867d35508740249",
    "api/httpx_helpers.py": "b5b7afe32b894c32eb58b9a0960e30c95503651feffe24c3d3a8e1555d9ff82d",
    "auth/token_store.py": "3d3ca9b7073727f00fc4cab37ff23df0322b88644d1492c30b595bcc16936a3d",
    "api/browser_source.py": "a9f8009273fa9b12379e0c006df40b2ee32dc579bccd9999fe9d9a72576999a8",
}


class SessionSafetyError(RuntimeError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def assert_requests_allowed(*, recovery=False):
    if DISCONNECTED.exists():
        raise SessionSafetyError("SESSION_UNAVAILABLE", "服务器会话已断开，未发起请求")
    if not RISK_HOLD.exists():
        return
    raw = RISK_HOLD.read_bytes()
    # A recovery is explicit, tied to the exact paused record, and limited by
    # callers to CDP login/probe or the single recruiter-jobs verification.
    if recovery and os.environ.get(RECOVERY_ENV) == hashlib.sha256(raw).hexdigest():
        # A historical reopening date is informative, not a local ban on a new
        # explicit scan. A fresh platform refusal changes the permit and stops.
        return
    raise SessionSafetyError("REQUESTS_PAUSED", "招聘账号已暂停，未发起请求")


def persist_stop(code):
    """Stop the next request even if an SDK caller catches the current error."""
    if code not in STOP_CODES:
        return
    RISK_HOLD.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    record = {"code": code, "since": int(time.time())}
    if RISK_HOLD.exists():
        try:
            previous = json.loads(RISK_HOLD.read_text())
            if previous.get("code") == "PC_LOGIN_RESTRICTED":
                record = previous
        except (ValueError, AttributeError):
            pass
    # A new failure invalidates a recovery permit even if the error code repeats.
    record["generation"] = uuid.uuid4().hex
    temporary = RISK_HOLD.with_name(RISK_HOLD.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as stream:
            json.dump(record, stream)
        temporary.replace(RISK_HOLD)
    finally:
        temporary.unlink(missing_ok=True)


def browser_session_snapshot():
    """Local CDP reads only. No page attach, navigation, fetch, or cookie writes."""
    from websockets.sync.client import connect
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(CDP_URL + "/json/version", timeout=3) as response:
        endpoint = json.load(response)["webSocketDebuggerUrl"]
    parsed = urllib.parse.urlsplit(endpoint)
    if parsed.scheme != "ws" or parsed.hostname != "127.0.0.1" or parsed.port != 9222:
        raise ValueError("unexpected CDP endpoint")
    results = []
    with connect(endpoint, proxy=None, open_timeout=3, close_timeout=1, max_size=2**20) as websocket:
        for number, method in enumerate(
                ("Target.getBrowserContexts", "Storage.getCookies", "Browser.getVersion"), 1):
            websocket.send(json.dumps({"id": number, "method": method}))
            deadline = time.monotonic() + 3
            while True:
                message = json.loads(websocket.recv(timeout=max(.01, deadline - time.monotonic())))
                if message.get("id") == number:
                    if "error" in message:
                        raise ValueError("CDP read failed")
                    results.append(message["result"])
                    break
                if time.monotonic() >= deadline:
                    raise TimeoutError("CDP read timed out")
    if results[0].get("browserContextIds"):
        raise ValueError("multiple browser contexts")
    values = set()
    for cookie in results[1].get("cookies", []):
        domain = str(cookie.get("domain", "")).lstrip(".").lower()
        if cookie.get("name") == "wt2" and (domain == "zhipin.com" or domain.endswith(".zhipin.com")):
            if cookie.get("value") and (cookie.get("expires", -1) < 0 or cookie["expires"] > time.time()):
                values.add(cookie["value"])
    return values, results[2].get("userAgent", "")


def verify_session(token=None):
    """Compare the primary session; never repair it by copying credentials."""
    if token is None:
        from boss_agent_cli.auth.token_store import TokenStore
        token = TokenStore(AUTH_DIR).load()
    try:
        values, user_agent = browser_session_snapshot()
    except Exception:
        raise SessionSafetyError("SESSION_UNAVAILABLE", "无法核对服务器浏览器会话，已停止请求") from None
    stored = ((token or {}).get("cookies") or {}).get("wt2")
    if not stored or values != {stored}:
        raise SessionSafetyError("SESSION_MISMATCH", "浏览器与保存的登录态不一致，已停止请求，请手动同步")
    if not token.get("user_agent") or token["user_agent"] != user_agent:
        raise SessionSafetyError("SESSION_MISMATCH", "浏览器环境与保存的登录态不一致，已停止请求，请手动同步")


def endpoint_label(url):
    # Never store URLs, query strings, identifiers, headers, or response bodies.
    from boss_agent_cli.api import endpoints, recruiter_endpoints
    for module in (recruiter_endpoints, endpoints):
        for name, value in vars(module).items():
            if name.endswith("_URL") and isinstance(value, str) and value == url:
                return name
    return "UNKNOWN_ENDPOINT"


def audit(event_id, event, channel, endpoint, method, *, outcome="", elapsed_ms=0,
          http_status=None, platform_code=None):
    AUDIT_DB.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Strict field allowlists keep arbitrary SDK error messages/URLs out.
    fields = (channel, endpoint, method, outcome)
    if any(not re.fullmatch(r"[A-Za-z0-9_]{0,80}", value) for value in fields):
        raise ValueError("unsafe audit label")
    with contextlib.closing(sqlite3.connect(AUDIT_DB, timeout=5)) as db:
        db.execute("""CREATE TABLE IF NOT EXISTS events (
            id INTEGER PRIMARY KEY, request_id TEXT, at REAL, event TEXT,
            channel TEXT, endpoint TEXT, method TEXT, outcome TEXT,
            elapsed_ms INTEGER, http_status INTEGER, platform_code INTEGER)""")
        os.chmod(AUDIT_DB, 0o600)
        db.execute("INSERT INTO events VALUES (NULL,?,?,?,?,?,?,?,?,?,?)",
                   (event_id, time.time(), event, *fields, elapsed_ms,
                    http_status if type(http_status) is int else None,
                    platform_code if type(platform_code) is int else None))
        db.execute("DELETE FROM events WHERE id <= (SELECT MAX(id)-10000 FROM events) OR at < ?",
                   (time.time() - 30 * 86400,))
        db.commit()


@contextlib.contextmanager
def request_trace(channel, endpoint, method, *, recovery=False):
    event_id, started, metadata = uuid.uuid4().hex, time.monotonic(), {}
    audit(event_id, "start", channel, endpoint, method)
    fd = None
    try:
        REQUEST_LOCK.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        fd = os.open(REQUEST_LOCK, os.O_CREAT | os.O_RDWR, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SessionSafetyError("ACCOUNT_BUSY", "招聘账号正在处理其他请求，未并发发送") from None
        assert_requests_allowed(recovery=recovery)
        yield metadata
    except BaseException as error:
        code = getattr(error, "code", "")
        if type(error).__name__ == "TokenRefreshFailed":
            code = "TOKEN_REFRESH_FAILED"
        if not isinstance(code, str) or not re.fullmatch(r"[A-Z_]{2,60}", code):
            code = "REQUEST_FAILED"
        persist_stop(code)
        audit(event_id, "finish", channel, endpoint, method, outcome=code,
              elapsed_ms=int((time.monotonic() - started) * 1000), **metadata)
        if isinstance(error, SessionSafetyError):
            from boss_agent_cli.api.client import PlatformRiskError
            converted = PlatformRiskError(str(error))
            converted.code = error.code
            raise converted from None
        raise
    else:
        audit(event_id, "finish", channel, endpoint, method, outcome="RETURNED",
              elapsed_ms=int((time.monotonic() - started) * 1000), **metadata)
    finally:
        # Keep exclusivity until the failure hold is durable.
        if fd is not None:
            os.close(fd)


class BudgetExhausted(RuntimeError):
    pass


def reserve_request():
    """One shared, persistent budget across short-lived CLI processes."""
    BUDGET_DB.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with contextlib.closing(sqlite3.connect(BUDGET_DB, timeout=10)) as db:
        db.execute("CREATE TABLE IF NOT EXISTS requests (at REAL NOT NULL)")
        os.chmod(BUDGET_DB, 0o600)
        db.execute("BEGIN IMMEDIATE")
        now = time.time()
        db.execute("DELETE FROM requests WHERE at < ?", (now - 3600,))
        count, latest = db.execute("SELECT COUNT(*), MAX(at) FROM requests").fetchone()
        if count >= HOURLY_LIMIT:
            db.rollback()
            raise BudgetExhausted("招聘请求预算已用完，请稍后由用户重新验证连接")
        at = max(now, (latest or 0) + MIN_INTERVAL)
        # Do not accumulate a long queue when multiple processes are launched.
        if at - now > MIN_INTERVAL * 2:
            db.rollback()
            raise BudgetExhausted("招聘请求过于密集，已暂停")
        db.execute("INSERT INTO requests VALUES (?)", (at,))
        db.commit()
    if at > now:
        time.sleep(at - now)


def install_policy():
    """All HTTP requests are single-attempt; refresh never starts a browser."""
    if importlib.metadata.version("boss-agent-cli") != "2.0.0":
        raise RuntimeError("招聘 SDK 版本变化，请先审核会话保护兼容性")
    import boss_agent_cli
    root = Path(boss_agent_cli.__file__).parent
    if any(hashlib.sha256((root / name).read_bytes()).hexdigest() != expected
           for name, expected in REVIEWED_SOURCES.items()):
        raise RuntimeError("招聘 SDK 源码变化，请先审核会话保护兼容性")
    from boss_agent_cli.api._base_client import _BaseHttpClient
    from boss_agent_cli.api.browser_client import BrowserSession
    from boss_agent_cli.api.client import (
        AccountRiskError, EnvironmentRiskError, PlatformRiskError,
    )
    from boss_agent_cli.api.zhipin_errors import classify_code_37
    from boss_agent_cli.auth.manager import AuthManager, TokenRefreshFailed
    from boss_agent_cli.auth import browser as auth_browser, manager as auth_manager
    import httpx

    if getattr(_BaseHttpClient._request, "_heyi_guarded", False):
        return

    class RequestBudgetError(PlatformRiskError):
        code = "REQUEST_BUDGET_EXHAUSTED"

    class RateLimitError(PlatformRiskError):
        code = "RATE_LIMITED"

    def before_request(*, recovery=False):
        assert_requests_allowed(recovery=recovery)
        try:
            reserve_request()
        except BudgetExhausted as error:
            raise RequestBudgetError(str(error)) from None
        # A queued request must recheck after waiting: another process may have
        # discovered a risk or the user may have disconnected in the meantime.
        assert_requests_allowed(recovery=recovery)

    def check_result(result):
        code = result.get("code") if isinstance(result, dict) else None
        if code == 36:
            raise AccountRiskError("平台返回账号风控，已停止请求")
        if code == 37:
            if classify_code_37(result) == "token_expired":
                raise TokenRefreshFailed("登录凭据已过期，未自动刷新")
            raise EnvironmentRiskError("平台要求检查访问环境，已停止请求")
        if code == 9:
            raise RateLimitError("平台要求降低请求频率，已停止请求")
        return result

    original_request = _BaseHttpClient._request

    @functools.wraps(original_request)
    def single_request(self, method, url, **kwargs):
        from boss_agent_cli.api import recruiter_endpoints as ep
        recovery = method == "GET" and url == ep.BOSS_JOB_LIST_URL
        with request_trace("http", endpoint_label(url), method, recovery=recovery) as metadata:
            before_request(recovery=recovery)
            token = self._auth.get_token()
            verify_session(token)
            client = self._get_client()
            expected_cookie = token.get("cookies", {}).get("wt2")
            jar_values = {cookie.value for cookie in client.cookies.jar if cookie.name == "wt2"}
            if jar_values != {expected_cookie}:
                raise SessionSafetyError("SESSION_MISMATCH", "请求凭据与当前会话不一致，已停止请求")

            def response_metadata(response):
                metadata["http_status"] = response.status_code
                # Record numeric platform codes, never the body or its message.
                try:
                    response.read()
                    payload = response.json()
                    if isinstance(payload, dict):
                        metadata["platform_code"] = payload.get("code")
                except (ValueError, UnicodeError):
                    pass
                if any(cookie.name == "wt2" and cookie.value != expected_cookie
                       for cookie in response.cookies.jar):
                    raise SessionSafetyError("SESSION_MISMATCH", "接口返回了不同登录凭据，已暂停并保留浏览器")

            hooks = client.event_hooks["response"]
            hooks.append(response_metadata)
            kwargs["retry"] = False
            kwargs["follow_redirects"] = False
            try:
                result = original_request(self, method, url, **kwargs)
            except self._AUTH_ERROR_CLS:
                raise TokenRefreshFailed("访问被拒绝或需要安全验证，未刷新或重试") from None
            except httpx.HTTPStatusError as error:
                if error.response.status_code == 429:
                    raise RateLimitError("平台要求降低请求频率，已停止请求") from None
                if error.response.status_code in (301, 302, 303, 307, 308, 401, 403):
                    raise TokenRefreshFailed("登录或安全验证需要用户确认，未跟随跳转或重试") from None
                raise
            finally:
                hooks.remove(response_metadata)
            return check_result(result)

    single_request._heyi_guarded = True
    _BaseHttpClient._request = single_request

    original_browser_request = BrowserSession.request

    @functools.wraps(original_browser_request)
    def browser_request(self, method, url, **kwargs):
        with request_trace("browser", endpoint_label(url), method) as metadata:
            before_request()
            verify_session()
            # The upstream browser method retries fetch after a destroyed
            # execution context. Exposed writes use raw CDP; prevent any future
            # caller accidentally using that retrying method for a write.
            if method != "GET":
                raise SessionSafetyError("REQUESTS_PAUSED", "此浏览器请求路径仅允许读取，未提交写入")
            result = original_browser_request(self, method, url, **kwargs)
            metadata["platform_code"] = result.get("code") if isinstance(result, dict) else None
            return check_result(result)

    BrowserSession.request = browser_request

    # Recruiter resume requests use these raw-CDP methods rather than request().
    # Reserve once per explicitly dispatched action; the site's own internal
    # network traffic is not represented as individually counted HTTP requests.
    def guarded_browser_action(original):
        @functools.wraps(original)
        def action(self, *args, **kwargs):
            with request_trace("cdp_action", original.__name__, "ACTION"):
                before_request()
                verify_session()
                return original(self, *args, **kwargs)
        return action

    BrowserSession.evaluate_js = guarded_browser_action(BrowserSession.evaluate_js)
    BrowserSession.evaluate_js_with_chat_events = guarded_browser_action(
        BrowserSession.evaluate_js_with_chat_events)

    original_probe = auth_browser._probe_reused_session

    @functools.wraps(original_probe)
    def budgeted_probe(page, *, platform):
        with request_trace("login_probe", "USER_INFO_URL", "GET", recovery=True) as metadata:
            class ObservedPage:
                def evaluate(self, *args, **kwargs):
                    data = page.evaluate(*args, **kwargs)
                    if isinstance(data, dict):
                        metadata["platform_code"] = data.get("code")
                    return data

            if platform == "zhipin":
                before_request(recovery=True)
            result = original_probe(ObservedPage(), platform=platform)
            if result != "valid":
                # Do not save an unverified token and immediately try jobs next.
                raise TokenRefreshFailed("浏览器登录态未通过验证，已停止后续请求")
            return result

    auth_browser._probe_reused_session = budgeted_probe

    def single_cdp_login(self, *, cdp_url, timeout, reuse_existing):
        # A stale-session result must not silently turn a reuse into --force.
        # The connector has an explicit, separately confirmed local reset flow.
        if not reuse_existing:
            raise TokenRefreshFailed("请先由用户明确清理服务器会话，未强制重登")
        assert_requests_allowed(recovery=True)
        try:
            return auth_manager.login_via_cdp(
                cdp_url=cdp_url, timeout=timeout, platform=self._platform, reuse_existing=True)
        except auth_browser.ReusedSessionStaleError:
            raise TokenRefreshFailed("已有会话失效，已保留浏览器，未自动清理或重登") from None

    AuthManager._cdp_login = single_cdp_login

    original_login = AuthManager.login

    @functools.wraps(original_login)
    def explicit_cdp_only(self, **kwargs):
        if not kwargs.get("force_cdp") or kwargs.get("force_relogin") or kwargs.get("cookie_source"):
            raise TokenRefreshFailed("只允许明确的专用浏览器扫码，不导入其他浏览器凭据或切换登录方式")
        assert_requests_allowed(recovery=True)
        return original_login(self, **kwargs)

    AuthManager.login = explicit_cdp_only

    def no_implicit_refresh(self, *args, **kwargs):
        raise TokenRefreshFailed("请由用户在官方页面确认后手动同步，未自动刷新")

    AuthManager.force_refresh = no_implicit_refresh


def main():
    try:
        install_policy()
    except Exception:
        print(json.dumps({"ok": False, "error": {
            "code": "SESSION_POLICY_UNAVAILABLE",
            "message": "招聘会话保护未就绪，已停止请求"}}))
        raise SystemExit(1)
    from boss_agent_cli.main import cli
    cli()


if __name__ == "__main__":
    main()
