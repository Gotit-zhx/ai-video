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

    def download(self, url):
        """Receive bounded bytes; never expose remote URLs or credentials."""
        body = json.dumps({"accountId": self.config["accountId"], "url": url}).encode("utf-8")
        request = urllib.request.Request(
            self.config["url"] + "/download", data=body, method="POST",
            headers={"Authorization": "Bearer " + self.config["secret"],
                     "Content-Type": "application/json"})
        try:
            response = self.opener.open(request, timeout=75)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            if response.headers.get("X-Heyi-Native-Download") != "1":
                try:
                    value = json.loads(response.read(65537))
                except (ValueError, UnicodeError):
                    value = {}
                error = value.get("error") or {}
                raise NativeError(error.get("code", "DOWNLOAD_FAILED"), "原客户端附件下载未完成")
            data = response.read(20 * 1024 * 1024 + 1)
            if len(data) > 20 * 1024 * 1024:
                raise NativeError("ATTACHMENT_TOO_LARGE", "附件超过 20 MiB 上限")
            # Forward only attachment display metadata, never remote cookies,
            # redirects or authentication headers.
            disposition = response.headers.get("Content-Disposition", "")
            headers = {}
            if len(disposition) <= 8192 and disposition and all(32 <= ord(c) <= 126 for c in disposition):
                headers["Content-Disposition"] = disposition
            return response.status, data, headers


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
            if request.url.host == "docdownload.zhipin.com":
                if request.method != "GET":
                    raise NativeError("INVALID_ATTACHMENT", "附件下载只允许读取")
                # Upstream streams attachments outside _request. Wrap this
                # otherwise unguarded read in the same lock/budget/session
                # policy, once. No extra request or authentication refresh.
                with guard.request_trace("native_download", "BOSS_RESUME_DOWNLOAD_URL", "GET") as metadata:
                    guard.assert_requests_allowed()
                    guard.reserve_request()
                    guard.assert_requests_allowed()
                    guard.verify_session()
                    status, content, headers = native.download(str(request.url))
                    metadata["http_status"] = status
                    if status == 429:
                        raise guard.SessionSafetyError("RATE_LIMITED", "平台要求降低请求频率，附件未重试")
                    if status in (301, 302, 303, 307, 308, 401, 403):
                        raise guard.SessionSafetyError("TOKEN_REFRESH_FAILED", "附件访问需要在原客户端确认")
                    try:
                        payload = json.loads(content)
                    except (ValueError, UnicodeError):
                        payload = {}
                    code = payload.get("code") if isinstance(payload, dict) else None
                    metadata["platform_code"] = code if type(code) is int else None
                    if code in (9, 36, 37):
                        stop = {9: "RATE_LIMITED", 36: "ACCOUNT_RISK", 37: "ENVIRONMENT_RISK"}[code]
                        if code == 37:
                            from boss_agent_cli.api.zhipin_errors import classify_code_37
                            if classify_code_37(payload) == "token_expired":
                                stop = "TOKEN_REFRESH_FAILED"
                        raise guard.SessionSafetyError(stop, "平台要求确认附件访问，已停止后续请求")
                    return httpx.Response(status, content=content, headers=headers, request=request)
            data = {}
            raw = request.read()
            if raw:
                if "application/json" in request.headers.get("content-type", ""):
                    data = json.loads(raw)
                else:
                    data = {k: v[-1] if len(v) == 1 else v
                            for k, v in parse_qs(raw.decode("utf-8"), keep_blank_values=True).items()}
            result = native.call("/http", url=str(request.url), method=request.method, data=data)
            if isinstance(result, dict) and result.get("heyiNativeHttp") == 1:
                status = result.get("statusCode")
                if type(status) is not int or not 100 <= status <= 599:
                    raise NativeError("NATIVE_TRANSPORT_ERROR", "原客户端未返回有效的 HTTP 状态")
                return httpx.Response(status, json=result.get("body"), request=request)
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
        if script == "heyi-native-request-resume-v1":
            return native.call("/request-resume", **arg)
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
    def exchange(self, friend_id, exchange_type):
        if exchange_type != 4:
            raise NativeError("UNSUPPORTED_NATIVE_ACTION", "当前仅支持索取附件简历")
        # ConversationScope checks the current job before any UI selection.
        # The guard around evaluate_js still accounts and checks this action.
        friend = self._require_chat_friend_data(friend_id)
        receipt = self._get_browser().evaluate_js("heyi-native-request-resume-v1", {"friend": friend})
        return {"code": 0, "message": "Success", "zpData": receipt}
    BossRecruiterClient.exchange_request_by_friend = exchange
    return native
