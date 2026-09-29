"""One allowlisted operation per process; credentials travel only on stdin."""
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import time


def configure(root, cdp):
    import connector as c
    import guarded_cli as g
    from urllib.parse import urlsplit
    parsed = urlsplit(cdp)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or not parsed.port:
        raise ValueError("Invalid local browser endpoint")
    root = Path(root).resolve()
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    for name, filename in {
        "AUTH_DIR": "data/auth", "RISK_HOLD": "risk-hold.json",
        "DISCONNECTED": "disconnected", "RESUME_DB": "resumes.sqlite3",
        "RESUME_DIR": "downloads", "RESUME_AUTO_PAUSE": "resume-auto-pause",
        "LOGIN_ATTEMPTS_DB": "login-attempts.sqlite3",
    }.items():
        setattr(c, name, root / filename)
        if hasattr(g, name):
            setattr(g, name, root / filename)
    for name, filename in {
        "BUDGET_DB": "request-budget.sqlite3", "AUDIT_DB": "request-audit.sqlite3",
        "REQUEST_LOCK": "request.lock",
    }.items():
        setattr(g, name, root.parents[2] / filename)
    g.CDP_URL = cdp
    os.environ["HEYI_EMBEDDED_DEFAULT_CONTEXT"] = "1"
    c.LOGIN.update(state="complete")
    # Navigation by the old server helper used a fixed Linux CDP endpoint.
    # Desktop only uses an already-open official chat page for write actions.
    def existing_chat():
        import urllib.request
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(cdp + "/json/list", timeout=3) as response:
            pages = json.load(response)
        from urllib.parse import urlsplit
        if not any(urlsplit(p.get("url", "")).hostname == "www.zhipin.com"
                   and urlsplit(p.get("url", "")).path.rstrip("/") == "/web/chat/index"
                   for p in pages if p.get("type") == "page"):
            raise RuntimeError("请在应用的 BOSS 页面打开沟通会话后再索取，未发送请求")
    c.prepare_recruiter_chat = existing_chat
    return c, g


def main(request):
    # Must happen before importing HTTP clients: macOS/Windows system proxies
    # otherwise override environment cleanup. Browser and CLI share one NIC.
    if request.get("network"):
        from direct_network import install_client
        install_client(request["network"])
    elif request.get("operation") not in ("health", "diagnose-local"):
        raise RuntimeError("网卡直连未就绪，请重新启动助手")
    if request.get("operation") == "health":
        import importlib.metadata
        import guarded_cli
        guarded_cli.install_policy()
        return {"ok": True, "version": importlib.metadata.version("boss-agent-cli"),
                "platform": sys.platform, "python": sys.version.split()[0]}
    c, g = configure(request["root"], request["cdp"])
    operation = request.get("operation")
    if operation == "diagnose-local":
        # Offline/CDP reads only, also used by packaged OS compatibility tests.
        g.install_policy()
        values, agent = g.browser_session_snapshot()
        return {"ok": True, "sessionCount": len(values), "userAgent": agent}
    from boss_agent_cli.auth.token_store import TokenStore
    store = TokenStore(c.AUTH_DIR)
    if operation == "sync":
        token = request["token"]
        if not token.get("cookies", {}).get("wt2") or not token.get("user_agent"):
            return {"ok": False, "error": {"code": "AUTH_REQUIRED", "message": "尚未建立浏览器登录态"}}
        store.save(token)
        return {"ok": True}
    if operation == "clear":
        store.clear()
        c.DISCONNECTED.touch(mode=0o600)
        return {"ok": True}
    if operation == "hold":
        c.pause_requests(request.get("code"), retry_at=request.get("retry_at", ""),
                         reason=request.get("reason", ""))
        return {"ok": True}
    if operation == "tracked":
        return {"ok": True, "data": c.tracked_resumes()}
    g.install_policy()
    from boss_agent_cli.main import cli

    def invoke(*args, timeout=90):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
            try:
                cli.main(args=["--data-dir", str(c.AUTH_DIR.parent),
                               "--cdp-url", request["cdp"], "--browser-source", "stored-cookie",
                               "--role", "recruiter", "--json", *args], standalone_mode=False)
            except SystemExit:
                pass
        try:
            value = json.loads(output.getvalue())
        except ValueError:
            return {"ok": False, "error": {"code": "CLI_OUTPUT", "message": "招聘运行时返回异常，已停止"}}
        return c.observe_result(value)

    c.cli_json = invoke
    import passive_runtime
    def passive_delay(**options):
        return passive_runtime.budget_delay(g.BUDGET_DB, g.HOURLY_LIMIT, **options)
    c.passive_delay = passive_delay
    if operation in ('passive-local', 'passive-ack', 'passive-send', 'passive-step'):
        return passive_runtime.execute(c, request['root'], request)
    if operation == "verify":
        # Explicit user request only; bound to the existing hold generation.
        c.DISCONNECTED.unlink(missing_ok=True)
        digest = __import__("hashlib").sha256(c.RISK_HOLD.read_bytes()).hexdigest() if c.RISK_HOLD.exists() else ""
        os.environ[g.RECOVERY_ENV] = digest
        result = invoke("hr", "jobs", "list")
        if result.get("ok") is True:
            current = __import__("hashlib").sha256(c.RISK_HOLD.read_bytes()).hexdigest() if c.RISK_HOLD.exists() else ""
            if current != digest:
                return {"ok": False, "error": {"code": "REQUESTS_PAUSED", "message": "期间出现新的限制，已停止"}}
            c.RISK_HOLD.unlink(missing_ok=True)
        return result
    if operation == "request-resume":
        return {"ok": True, "data": c.request_resume(request["friendId"], retry_uncertain=False)}
    if operation == "poll-resumes":
        c.poll_resumes_once()
        if c.held_code():
            return {"ok": False, "error": {"code": c.held_code(),
                    "message": "平台会话已暂停，请在官方页面确认账号状态"}}
        return {"ok": True, "data": c.tracked_resumes()}
    if operation == "download-resume":
        output = c.download_resume(request["friendId"], request["messageId"])
        return {"ok": True, "file": str(output)}
    args = c.command(request)
    result = invoke(*args)
    return c.sanitize(result)


if __name__ == "__main__":
    if "--direct-network" in sys.argv or "--direct-network-test" in sys.argv:
        import direct_network
        if "--direct-network-test" in sys.argv:
            if not os.environ.get("HEYI_TEST_USER_DATA"):
                raise SystemExit("Disposable test profile required")
            direct_network.discover = lambda: {"name": "offline", "label": "Offline",
                "address": "127.0.0.1", "index": 0, "dns": []}
        direct_network.serve()
        raise SystemExit(0)
    try:
        payload = sys.stdin.buffer.read(1024 * 1024 + 1)
        if len(payload) > 1024 * 1024:
            raise ValueError("Request too large")
        request = json.loads(payload)
        # Existing connector logging never reaches stdout/stderr of the app.
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            result = main(request)
    except Exception as error:
        code = "LOCAL_RUNTIME_ERROR"
        try:
            import connector
            code = connector.held_code() or code
        except Exception:
            pass
        message = "本机执行未完成，已停止；未自动重试或重新发送"
        # Only local fixed errors may be displayed. Provider exceptions can
        # contain private URLs and must not cross the process boundary.
        text = str(error)
        if text.startswith(("请在应用的 BOSS 页面", "该会话已有未完成的简历请求")):
            message = text
        result = {"ok": False, "error": {"code": code, "message": message}}
    sys.stdout.write(json.dumps(result, ensure_ascii=False))
