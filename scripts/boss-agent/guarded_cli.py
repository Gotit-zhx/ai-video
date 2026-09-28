#!/opt/heyi/boss-agent/venv/bin/python
"""Restrict the pinned SDK without changing its installed files or risk checks."""

import contextlib
import functools
import hashlib
import importlib.metadata
import json
import os
import sqlite3
import time
from pathlib import Path

BUDGET_DB = Path("/opt/heyi/boss-agent/runtime/platform-request-budget.sqlite3")
MIN_INTERVAL = 6
HOURLY_LIMIT = 120
REVIEWED_SOURCES = {
    "api/_base_client.py": "e0e2a88e1159d62f1d836fefebb51498d8afe58e20214e4d85e240b313488518",
    "api/recruiter_client.py": "140dfad3189cbd7b29dae33cdb3adfd9f17f2a3969c22a5fc31fcb3b79b2856a",
    "api/browser_client.py": "fd18ddfc70f3a40371b448e12848882a4bbfff28c18b735b74a3e8efd3956601",
    "auth/manager.py": "b835c4f4eb7592b4725ba8e2ee242433c7da4b033cc1877d1c49e53fe03e7d36",
    "auth/browser.py": "3e4103121987b4b05488ec0f97f2bd5292762dbb078da30c7867d35508740249",
}


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

    def before_request():
        try:
            reserve_request()
        except BudgetExhausted as error:
            raise RequestBudgetError(str(error)) from None

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
        before_request()
        kwargs["retry"] = False
        kwargs["follow_redirects"] = False
        # Security pages/403 in 2.0.0 otherwise call force_refresh and open a tab.
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
        return check_result(result)

    single_request._heyi_guarded = True
    _BaseHttpClient._request = single_request

    original_browser_request = BrowserSession.request

    @functools.wraps(original_browser_request)
    def browser_request(self, *args, **kwargs):
        before_request()
        return check_result(original_browser_request(self, *args, **kwargs))

    BrowserSession.request = browser_request

    # Recruiter resume requests use these raw-CDP methods rather than request().
    # Reserve once per explicitly dispatched action; the site's own internal
    # network traffic is not represented as individually counted HTTP requests.
    def guarded_browser_action(original):
        @functools.wraps(original)
        def action(self, *args, **kwargs):
            before_request()
            return original(self, *args, **kwargs)
        return action

    BrowserSession.evaluate_js = guarded_browser_action(BrowserSession.evaluate_js)
    BrowserSession.evaluate_js_with_chat_events = guarded_browser_action(
        BrowserSession.evaluate_js_with_chat_events)

    original_probe = auth_browser._probe_reused_session

    @functools.wraps(original_probe)
    def budgeted_probe(page, *, platform):
        if platform == "zhipin":
            before_request()
        return original_probe(page, platform=platform)

    auth_browser._probe_reused_session = budgeted_probe

    def single_cdp_login(self, *, cdp_url, timeout, reuse_existing):
        # A stale-session result must not silently turn a reuse into --force.
        # The connector has an explicit, separately confirmed local reset flow.
        if not reuse_existing:
            raise TokenRefreshFailed("请先由用户明确清理服务器会话，未强制重登")
        try:
            return auth_manager.login_via_cdp(
                cdp_url=cdp_url, timeout=timeout, platform=self._platform, reuse_existing=True)
        except auth_browser.ReusedSessionStaleError:
            raise TokenRefreshFailed("已有会话失效，已保留浏览器，未自动清理或重登") from None

    AuthManager._cdp_login = single_cdp_login

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
