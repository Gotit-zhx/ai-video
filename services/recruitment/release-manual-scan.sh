#!/usr/bin/env bash
# Publish the reviewed fix without restarting Chrome or commerce workers.
set -euo pipefail
release_dir="$(cd "$(dirname "$0")" && pwd)"
stage="$release_dir/staged"
backup="$release_dir/rollback"
bin="/opt/heyi/boss-agent/bin"
base_image="ai-commerce-frontend:before-hr-manual-scan-20260928"
release_image="ai-commerce-frontend:hr-manual-scan-20260928"
mkdir -p "$backup"
(cd "$stage" && sha256sum -c manifest.sha256)
sha256sum -c "$stage/backend-baseline.sha256"
docker exec -i ai-commerce-frontend sha256sum -c < "$stage/frontend-baseline.sha256"
python3 - <<'PY'
import json, pathlib, subprocess
hold = pathlib.Path("/opt/heyi/boss-agent/runtime/recruiter-ip-risk-hold")
assert json.loads(hold.read_text())["code"] == "PC_LOGIN_RESTRICTED"
rows = subprocess.check_output(["ps", "-u", "heyi-boss", "-o", "args="], text=True)
assert not any("guarded_cli.py " in row or "--sync-browser-login" in row
               or "--prepare-recruiter-chat" in row for row in rows.splitlines())
PY
runuser -u heyi-boss -- /opt/heyi/boss-agent/venv/bin/python \
  -m unittest discover -s "$stage" -p 'test_*.py' > "$release_dir/tests.log" 2>&1
tail -5 "$release_dir/tests.log"
chrome_pid="$(systemctl show heyi-boss-chrome -p MainPID --value)"
old_backend="$(docker inspect ai-commerce-app --format '{{.Id}}')"
old_processor="$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')"
cp -p "$bin/connector.py" "$bin/guarded_cli.py" "$backup/"
docker commit ai-commerce-frontend "$base_image" >/dev/null
docker cp ai-commerce-frontend:/usr/share/nginx/html/index.html "$backup/index.html"
docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/app.js \
  /etc/nginx/conf.d/default.conf > "$backup/unchanged.sha256"
sha256sum /opt/heyi/boss-agent/runtime/resume-requests.sqlite3 \
  /opt/heyi/boss-agent/runtime/recruiter-ip-risk-hold > "$backup/records.sha256"
python3 - "$backup" "$stage" <<'PY'
import pathlib, re, sys
backup, stage = map(pathlib.Path, sys.argv[1:])
text, count = re.subn(r'(/hr-workspace\.(?:js|css))\?v=[^"\']+',
                     r'\1?v=20260928-manual-scan-11', (backup / "index.html").read_text())
assert count == 2
(stage / "index.html").write_text(text)
PY
cat > "$stage/Dockerfile.manual-scan" <<'EOF'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY hr-workspace.js hr-workspace.css index.html /usr/share/nginx/html/
EOF
docker build --pull=false --build-arg "BASE_IMAGE=$base_image" \
  -f "$stage/Dockerfile.manual-scan" -t "$release_image" "$stage"
docker run --rm --entrypoint nginx -v /etc/letsencrypt:/etc/letsencrypt:ro \
  -v /opt/heyi/shared/certbot-webroot:/var/www/certbot:ro "$release_image" -t
rollback() {
  trap - ERR
  systemctl stop heyi-boss-connector
  install -o heyi-boss -g heyi-boss -m 0640 "$backup/connector.py" "$bin/connector.py"
  install -o heyi-boss -g heyi-boss -m 0640 "$backup/guarded_cli.py" "$bin/guarded_cli.py"
  systemctl start heyi-boss-connector
  HR_FRONTEND_IMAGE="$base_image" docker compose -p web_sdk_ \
    -f "$stage/compose.release.yaml" up -d --no-deps --force-recreate frontend
}
trap rollback ERR
systemctl stop heyi-boss-connector
install -o heyi-boss -g heyi-boss -m 0640 "$stage/connector.py" "$bin/connector.py"
install -o heyi-boss -g heyi-boss -m 0640 "$stage/guarded_cli.py" "$bin/guarded_cli.py"
systemctl start heyi-boss-connector
systemctl is-active --quiet heyi-boss-connector
HR_FRONTEND_IMAGE="$release_image" docker compose -p web_sdk_ \
  -f "$stage/compose.release.yaml" up -d --no-deps --force-recreate frontend
docker exec -i ai-commerce-frontend sha256sum -c < "$backup/unchanged.sha256"
sha256sum -c "$backup/records.sha256"
for file in connector.py guarded_cli.py; do
  test "$(sha256sum "$bin/$file" | cut -d' ' -f1)" = "$(sha256sum "$stage/$file" | cut -d' ' -f1)"
done
for file in hr-workspace.js hr-workspace.css index.html; do
  test "$(docker exec ai-commerce-frontend sha256sum "/usr/share/nginx/html/$file" | cut -d' ' -f1)" = \
    "$(sha256sum "$stage/$file" | cut -d' ' -f1)"
done
runuser -u heyi-boss -- /opt/heyi/boss-agent/venv/bin/python - <<'PY'
import hashlib, json, socket, sys
from unittest import mock
sys.path.insert(0, "/opt/heyi/boss-agent/bin")
import connector, guarded_cli
connector.restore_login_state()
state = connector.login_snapshot()
assert state["failure_code"] == "PC_LOGIN_RESTRICTED" and state["retry_allowed"] is True
# Verify the explicit entry point without creating any real login worker.
with mock.patch.object(connector, "reserve_login_attempt"), \
     mock.patch.object(connector.threading, "Thread") as worker:
    assert connector.begin_login()["state"] == "pending"
    worker.assert_called_once()
assert connector.held_code() == "PC_LOGIN_RESTRICTED"
try:
    guarded_cli.assert_requests_allowed()
except guarded_cli.SessionSafetyError:
    pass
else:
    raise AssertionError("Background requests must remain paused")
permit = hashlib.sha256(guarded_cli.RISK_HOLD.read_bytes()).hexdigest()
with mock.patch.dict(guarded_cli.os.environ, {guarded_cli.RECOVERY_ENV: permit}):
    guarded_cli.assert_requests_allowed(recovery=True)
with socket.socket(socket.AF_UNIX) as client:
    client.settimeout(10)
    client.connect("/opt/heyi/boss-agent/bridge/connector.sock")
    client.sendall(b'{"operation":"status"}\n')
    with client.makefile("rb") as stream:
        result = json.loads(stream.readline())
assert "重新扫码" in result["result"]["error"]["message"]
print("Manual retry enabled; background hold retained; no real scan started.")
PY
test "$(curl --noproxy '*' -sS --retry 3 --retry-connrefused --max-time 10 -o /dev/null -w '%{http_code}' \
  --resolve hoyee.org.cn:443:127.0.0.1 https://hoyee.org.cn/)" = 200
test "$(systemctl show heyi-boss-chrome -p MainPID --value)" = "$chrome_pid"
test "$(docker inspect ai-commerce-app --format '{{.Id}}')" = "$old_backend"
test "$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')" = "$old_processor"
trap - ERR
printf 'Published %s; browser and records preserved; manual scan is available while background requests remain paused.\n' "$release_image"
