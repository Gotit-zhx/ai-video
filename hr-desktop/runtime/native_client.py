"""Use the current client's transport while retaining the existing request guard."""
import json
from urllib.parse import parse_qs, urlsplit
import urllib.error
import urllib.request


class NativeError(RuntimeError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


class NativeClient:
    def __init__(self, config):
        url = urlsplit(config["url"])
        if (url.scheme != "http" or url.hostname != "127.0.0.1" or not url.port or url.path
                or url.username or url.password or url.query or url.fragment):
            raise ValueError("Invalid native endpoint")
        self.config = config
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, path, **input):
        body = json.dumps({"accountId": self.config["accountId"], **input}).encode("utf-8")
        req = urllib.request.Request(
            self.config["url"] + path, data=body, method="POST",
            headers={"Authorization": "Bearer " + self.config["secret"],
                     "Content-Type": "application/json"})
        try:
            with self.opener.open(req, timeout=90) as reply:
                value = json.load(reply)
        except urllib.error.HTTPError as error:
            value = json.load(error)
        if value.get("ok") is not True:
            error = value.get("error") or {}
            raise NativeError(error.get("code", "NATIVE_OPERATION_FAILED"),
                              error.get("message", "客户端操作未完成"))
        return value["data"]

    def snapshot(self):
        value = self.call("/snapshot")
        if str(value.get("accountId", "")) != str(self.config["accountId"]):
            raise NativeError("SESSION_MISMATCH", "客户端账号已变化")
        cookies = value.get("cookies", [])
        def owned(domain):
            domain = str(domain).lstrip(".").lower()
            return domain == "zhipin.com" or domain.endswith(".zhipin.com")
        tokens = {c["value"] for c in cookies if c.get("name") == "wt2"
                  and owned(c.get("domain", ""))}
        return tokens, value["userAgent"]


def install(config, guard):
    import httpx
    from boss_agent_cli.api._base_client import _BaseHttpClient
    from boss_agent_cli.api.recruiter_client import BossRecruiterClient
    from boss_agent_cli.api.browser_client import BrowserSession

    native = NativeClient(config)
    guard.browser_session_snapshot = native.snapshot
    original_get_client = _BaseHttpClient._get_client

    class Transport(httpx.BaseTransport):
        def handle_request(self, request):
            data = {}
            raw = request.read()
            if raw:
                if "application/json" in request.headers.get("content-type", ""):
                    data = json.loads(raw)
                else:
                    data = {k: v[-1] if len(v) == 1 else v
                            for k, v in parse_qs(raw.decode("utf-8"), keep_blank_values=True).items()}
            result = native.call("/http", url=str(request.url), method=request.method, data=data)
            status = result.get("statusCode", 200) if isinstance(result, dict) else 200
            if not isinstance(status, int) or not 100 <= status <= 599:
                status = 200
            return httpx.Response(status, json=result, request=request)

    def client(self):
        result = original_get_client(self)
        if not getattr(result, "_heyi_native", False):
            result._transport.close()
            result._transport = Transport()
            result._mounts.clear()
            result._heyi_native = True
        return result

    _BaseHttpClient._get_client = client
    # The guard installed immediately afterwards still surrounds _request,
    # checks the cookie/session match, records requests and applies pacing.
    def evaluate(self, script, arg=None):
        if script == "heyi-native-send-text-v1":
            return native.call("/send-text", **arg)
        raise NativeError("UNSUPPORTED_NATIVE_ACTION",
                          "该操作尚未适配原客户端，请在客户端手动完成")

    BrowserSession.evaluate_js = evaluate

    def send(self, friend_id, content):
        # Keep _require_chat_friend_data in the path: ConversationScope wraps it
        # and rejects a candidate whose current job differs from the workflow.
        friend = self._require_chat_friend_data(friend_id)
        receipt = self._get_browser().evaluate_js("heyi-native-send-text-v1", {
            "friend": friend, "content": content})
        return {"code": 0, "message": "Success", "zpData": receipt}

    BossRecruiterClient.send_message_by_friend = send
    def unsupported_exchange(self, friend_id, exchange_type):
        # The webpage's Vue exchange handler is not the native client's
        # handler. Do not replay it or report a synthetic websocket receipt.
        raise NativeError("UNSUPPORTED_NATIVE_ACTION",
                          "自动索取简历尚未完成原客户端适配，请在客户端手动索取")
    BossRecruiterClient.exchange_request_by_friend = unsupported_exchange
    return native
