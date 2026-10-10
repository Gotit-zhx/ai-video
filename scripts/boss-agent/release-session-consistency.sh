#!/usr/bin/env bash
# Backend-only follow-up to a10c762; retain browser, restriction and commerce.
set -euo pipefail
release_dir="$(cd "$(dirname "$0")" && pwd)"
stage="$release_dir/staged"
backup="$release_dir/rollback"
bin="/opt/heyi/boss-agent/bin"
mkdir -p "$backup"
(cd "$stage" && sha256sum -c manifest.sha256)
sha256sum -c "$stage/backend-baseline.sha256"
python3 - <<'PY'
import json, pathlib, subprocess
root = pathlib.Path("/opt/heyi/boss-agent")
assert json.loads((root / "runtime/recruiter-ip-risk-hold").read_text())["code"] == "PC_LOGIN_RESTRICTED"
processes = subprocess.check_output(["ps", "-u", "heyi-boss", "-o", "args="], text=True)
assert not any("guarded_cli.py " in row or "--sync-browser-login" in row
               or "--prepare-recruiter-chat" in row for row in processes.splitlines())
PY
runuser -u heyi-boss -- /opt/heyi/boss-agent/venv/bin/python \
  -m unittest discover -s "$stage" -p 'test_*.py' > "$release_dir/tests.log" 2>&1
tail -5 "$release_dir/tests.log"
chrome_pid="$(systemctl show heyi-boss-chrome -p MainPID --value)"
docker inspect ai-commerce-frontend ai-commerce-app heyi-commerce-image-processor \
  --format '{{.Id}}' > "$backup/container-ids"
cp -p "$bin/connector.py" "$bin/guarded_cli.py" "$backup/"
sha256sum /opt/heyi/boss-agent/runtime/resume-requests.sqlite3 \
  /opt/heyi/boss-agent/runtime/recruiter-ip-risk-hold > "$backup/records.sha256"
rollback() {
  trap - ERR
  systemctl stop heyi-boss-connector
  install -o heyi-boss -g heyi-boss -m 0640 "$backup/connector.py" "$bin/connector.py"
  install -o heyi-boss -g heyi-boss -m 0640 "$backup/guarded_cli.py" "$bin/guarded_cli.py"
  systemctl start heyi-boss-connector
}
trap rollback ERR
systemctl stop heyi-boss-connector
install -o heyi-boss -g heyi-boss -m 0640 "$stage/connector.py" "$bin/connector.py"
install -o heyi-boss -g heyi-boss -m 0640 "$stage/guarded_cli.py" "$bin/guarded_cli.py"
systemctl start heyi-boss-connector
systemctl is-active --quiet heyi-boss-connector
runuser -u heyi-boss -- /opt/heyi/boss-agent/venv/bin/python - <<'PY'
import json, socket, sys
sys.path.insert(0, "/opt/heyi/boss-agent/bin")
import connector, guarded_cli
connector.restore_login_state()
assert connector.held_code() == "PC_LOGIN_RESTRICTED"
assert connector.login_snapshot()["retry_allowed"] is False
try:
    connector.begin_login()
except RuntimeError:
    pass
else:
    raise AssertionError("Restricted login was allowed")
guarded_cli.install_policy()
from boss_agent_cli.api._base_client import _BaseHttpClient
from boss_agent_cli.api.client import PlatformRiskError
from boss_agent_cli.api import recruiter_endpoints as ep
# No client/auth/transport exists: this verifies rejection before construction.
try:
    _BaseHttpClient._request(object(), "GET", ep.BOSS_JOB_LIST_URL)
except PlatformRiskError as error:
    assert error.code == "REQUESTS_PAUSED"
else:
    raise AssertionError("Paused SDK request was allowed")
with socket.socket(socket.AF_UNIX) as client:
    client.settimeout(10)
    client.connect("/opt/heyi/boss-agent/bridge/connector.sock")
    client.sendall(b'{"operation":"status"}\n')
    with client.makefile("rb") as stream:
        result = json.loads(stream.readline())
assert "电脑端登录权限" in result["result"]["error"]["message"]
print("Connector and SDK reject locally; no platform transport constructed.")
PY
sha256sum -c "$backup/records.sha256"
for file in connector.py guarded_cli.py; do
  test "$(sha256sum "$bin/$file" | cut -d' ' -f1)" = "$(sha256sum "$stage/$file" | cut -d' ' -f1)"
done
docker inspect ai-commerce-frontend ai-commerce-app heyi-commerce-image-processor \
  --format '{{.Id}}' > "$release_dir/container-ids-after"
cmp "$backup/container-ids" "$release_dir/container-ids-after"
test "$(systemctl show heyi-boss-chrome -p MainPID --value)" = "$chrome_pid"
test "$(curl --noproxy '*' -sS --max-time 10 -o /dev/null -w '%{http_code}' \
  --resolve hoyee.org.cn:443:127.0.0.1 https://hoyee.org.cn/)" = 200
trap - ERR
printf 'Session consistency protection published; browser, restriction and commerce preserved.\n'
