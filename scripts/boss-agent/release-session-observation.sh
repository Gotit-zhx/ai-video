#!/usr/bin/env bash
# Scoped connector release. Chrome, credentials, commerce and observer stay up.
set -euo pipefail
release_dir="$(cd "$(dirname "$0")" && pwd)"
stage="$release_dir/staged"
backup="$release_dir/rollback"
bin="/opt/heyi/boss-agent/bin"
mkdir -p "$backup"
(cd "$stage" && sha256sum -c manifest.sha256)
sha256sum -c "$stage/backend-baseline.sha256"
runuser -u heyi-boss -- /opt/heyi/boss-agent/venv/bin/python \
  -m unittest discover -s "$stage" -p 'test_*.py' > "$release_dir/tests.log" 2>&1
tail -5 "$release_dir/tests.log"

python3 - <<'PY'
import json, pathlib, socket, subprocess
hold = pathlib.Path("/opt/heyi/boss-agent/runtime/recruiter-ip-risk-hold")
assert json.loads(hold.read_text())["code"] == "PC_LOGIN_RESTRICTED", "Live state changed; re-evaluate release"
rows = subprocess.check_output(["ps", "-u", "heyi-boss", "-o", "args="], text=True)
assert not any("guarded_cli.py " in row or "connector.py --" in row for row in rows.splitlines()), "Worker active"
with socket.socket(socket.AF_UNIX) as client:
    client.settimeout(10)
    client.connect("/opt/heyi/boss-agent/bridge/connector.sock")
    client.sendall(b'{"operation":"status"}\n')
    with client.makefile("rb") as stream:
        result = json.loads(stream.readline())
assert result["result"]["error"]["code"] != "LOGIN_PENDING", "Scan active"
PY
chrome_pid="$(systemctl show heyi-boss-chrome -p MainPID --value)"
docker inspect ai-commerce-app ai-commerce-frontend heyi-commerce-image-processor \
  --format '{{.Id}}' > "$backup/containers.before"
sha256sum /opt/heyi/boss-agent/runtime/resume-requests.sqlite3 \
  /opt/heyi/boss-agent/runtime/recruiter-ip-risk-hold > "$backup/records.sha256"
cp -p "$bin/connector.py" "$bin/guarded_cli.py" "$backup/"

rollback() {
  trap - ERR
  systemctl stop heyi-boss-connector
  install -o heyi-boss -g heyi-boss -m 0640 "$backup/connector.py" "$bin/connector.py"
  install -o heyi-boss -g heyi-boss -m 0640 "$backup/guarded_cli.py" "$bin/guarded_cli.py"
  systemctl start heyi-boss-connector
}
trap rollback ERR
# Preserve every resume record. Explicitly pause old-account background work
# through the observation window, until account ownership has been established.
install -o heyi-boss -g heyi-boss -m 0600 /dev/null \
  /opt/heyi/boss-agent/runtime/resume-auto-pause
systemctl stop heyi-boss-connector
install -o heyi-boss -g heyi-boss -m 0640 "$stage/connector.py" "$bin/connector.py"
install -o heyi-boss -g heyi-boss -m 0640 "$stage/guarded_cli.py" "$bin/guarded_cli.py"
systemctl start heyi-boss-connector
systemctl is-active --quiet heyi-boss-connector
sha256sum -c "$backup/records.sha256"
for file in connector.py guarded_cli.py; do
  test "$(sha256sum "$bin/$file" | cut -d' ' -f1)" = "$(sha256sum "$stage/$file" | cut -d' ' -f1)"
done
runuser -u heyi-boss -- /opt/heyi/boss-agent/venv/bin/python - <<'PY'
import sys
from unittest import mock
sys.path.insert(0, "/opt/heyi/boss-agent/bin")
import connector, guarded_cli
assert connector.RESUME_AUTO_PAUSE.exists()
connector.restore_login_state()
state = connector.login_snapshot()
assert state["failure_code"] == "PC_LOGIN_RESTRICTED" and state["retry_allowed"] is True
with mock.patch.object(connector, "cli_json") as cli:
    connector.poll_resumes_once()
    cli.assert_not_called()
try:
    guarded_cli.assert_requests_allowed()
except guarded_cli.SessionSafetyError:
    pass
else:
    raise AssertionError("Background hold missing")
print("Old-account background reads paused; manual rescan available; no platform request sent.")
PY
test "$(systemctl show heyi-boss-chrome -p MainPID --value)" = "$chrome_pid"
docker inspect ai-commerce-app ai-commerce-frontend heyi-commerce-image-processor \
  --format '{{.Id}}' > "$backup/containers.after"
cmp "$backup/containers.before" "$backup/containers.after"
systemctl is-active --quiet heyi-boss-session-observer-20260928
trap - ERR
printf 'Connector safeguards published; browser, commerce, records and observer preserved.\n'
