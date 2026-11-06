"""Authenticated redirects to verified private Ai boss installers.

Runs independently of recruitment workers. No BOSS calls, pairing writes or
installer bytes pass through this service; an atomic manifest changes both OSes.
"""
import http.server
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

ROUTES = {
    "/downloads/Heyi-Recruiter-mac-arm64.dmg": "mac",
    "/downloads/Heyi-Recruiter-windows-x64-Setup.exe": "windows",
}
ENDPOINT = "https://oss.cloud.dayan.cloud"
EXPIRY = 1800


def identity(user):
    """The existing HR download policy, without importing the retired collector runtime."""
    legacy = (user.get("username") == "13800000000" and user.get("platformAdmin") is True
              and user.get("organizationCode") == "heyi" and "ADMIN" in user.get("roles", []))
    if not legacy and ("hr:access" not in user.get("permissions", [])
                       or not user.get("organizationCode") or not (user.get("id") or user.get("userId"))):
        raise ValueError("账号未开通人力资源权限")
    return {key: user.get(key) for key in
            ("username", "id", "userId", "organizationCode", "platformAdmin", "roles", "permissions")}


def read_manifest(path):
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    commit = value.get("commit", "")
    if value.get("schemaVersion") != 1 or not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Verified release manifest required")
    artifacts = value.get("artifacts", {})
    if set(artifacts) != {"mac", "windows"}:
        raise ValueError("Both operating systems are required")
    for platform, record in artifacts.items():
        name = (f"Ai-boss-mac-arm64-{commit[:12]}.dmg" if platform == "mac"
                else f"Ai-boss-Windows-Setup-{commit[:12]}.exe")
        sha = record.get("sha256", "")
        if (record.get("name") != name or not re.fullmatch(r"[a-f0-9]{64}", sha)
                or not isinstance(record.get("size"), int) or record["size"] < 1_000_000
                or record.get("key") != f"private-desktop-installers/{sha}/{name}"
                or record.get("verified") is not True or record.get("private") is not True):
            raise ValueError("Invalid private installer record")
    return artifacts


def authorize(cookie):
    if not cookie or len(cookie) > 4096:
        return 401
    request = urllib.request.Request("http://127.0.0.1:8080/api/v1/auth/me", headers={
        "Cookie": cookie, "Accept": "application/json",
    })
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=5) as response:
            value = json.load(response)
        user = value.get("data") if value.get("success") is True else None
        if not isinstance(user, dict):
            return 401
        identity(user)
        return 200
    except urllib.error.HTTPError as error:
        return 401 if error.code == 401 else 503
    except (ValueError, TypeError, AttributeError):
        return 403
    except OSError:
        return 503


class Downloads:
    def __init__(self, manifest, bucket, authenticate=authorize):
        self.manifest = Path(manifest)
        self.bucket = bucket
        self.authenticate = authenticate

    def response(self, path, cookie, method="GET"):
        platform = ROUTES.get(urllib.parse.urlsplit(path).path) if len(path) <= 2048 else None
        if platform is None:
            return 404, {}
        if method not in ("GET", "HEAD"):
            return 405, {}
        access = self.authenticate(cookie)
        if access != 200:
            return access, {}
        record = read_manifest(self.manifest)[platform]
        url = self.bucket.sign_url(method, record["key"], EXPIRY, params={
            "response-content-disposition": 'attachment; filename="' + record["name"] + '"',
            "response-cache-control": "private, no-store",
        })
        parsed = urllib.parse.urlsplit(url)
        if (parsed.scheme != "https" or parsed.netloc != "oss.cloud.dayan.cloud"
                or not parsed.query or parsed.fragment):
            raise ValueError("Unexpected installer storage endpoint")
        return 302, {"Location": url, "X-Installer-SHA256": record["sha256"],
                     "X-Installer-Size": str(record["size"])}


def handler(downloads):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass  # Never log Cookie headers, signed URLs or query strings.

        def do_GET(self):
            self.serve()

        def do_HEAD(self):
            self.serve()

        def serve(self):
            try:
                status, headers = downloads.response(self.path, self.headers.get("Cookie", ""), self.command)
            except Exception:
                status, headers = 503, {}
            messages = {401: "请先登录赫逸系统后下载助手", 403: "此账号未开通人力资源权限",
                        404: "安装包不存在", 405: "请求方式不支持", 503: "安装包暂时不可用，请稍后重试"}
            body = (json.dumps({"success": False, "message": messages.get(status, "下载不可用")},
                               ensure_ascii=False).encode() if status != 302 else b"")
            self.send_response(status)
            for key, value in headers.items():
                self.send_header(key, value)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "private, no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
    return Handler


def main():
    import oss2
    credential = Path(os.environ["APP_INSTALLER_OSS_CREDENTIALS_FILE"]).read_text()
    values = dict(line.split("=", 1) for line in credential.splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
    values = {key.strip(): value.strip() for key, value in values.items()}
    bucket = oss2.Bucket(oss2.Auth(values["accessKeyId"], values["accessKeySecret"]),
                         ENDPOINT, os.environ["APP_INSTALLER_OSS_BUCKET"], is_path_style=True)
    bucket.session.session.trust_env = False
    downloads = Downloads(os.environ["APP_INSTALLER_MANIFEST"], bucket)
    read_manifest(downloads.manifest)
    server = http.server.ThreadingHTTPServer(
        (os.environ.get("APP_INSTALLER_BIND", "127.0.0.1"), int(os.environ.get("APP_INSTALLER_PORT", "8767"))),
        handler(downloads))
    server.serve_forever()


if __name__ == "__main__":
    main()
