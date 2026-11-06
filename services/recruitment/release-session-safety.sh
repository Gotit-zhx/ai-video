#!/usr/bin/env bash
# Deploy the session guard while retaining the current browser and an explicit hold.
set -euo pipefail
release_dir="$(cd "$(dirname "$0")" && pwd)"
stage="$release_dir/staged"
backup="$release_dir/rollback"
bin="/opt/heyi/boss-agent/bin"
base_image="ai-commerce-frontend:before-hr-session-safety-20260928"
release_image="ai-commerce-frontend:hr-session-safety-20260928"
mkdir -p "$backup"
(cd "$stage" && sha256sum -c manifest.sha256)
test "$(sha256sum "$bin/connector.py" | cut -d' ' -f1)" = \
  "5b31525e469896a9dae3e65ac2a8eb87fbf024ecafe4e608faf231315477dd3d"
test "$(docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/hr-workspace.js | cut -d' ' -f1)" = \
  "22b2d6c22abbaeff6ff05677fa01d0cdda4f86076be7bf6d14cea4cb00a178f3"
python3 - <<'PY'
import subprocess
rows = subprocess.check_output(["ps", "-u", "heyi-boss", "-o", "args="], text=True)
assert not any(("--sync-browser-login" in row or "--prepare-recruiter-chat" in row
                or (("venv/bin/boss " in row or "guarded_cli.py " in row)
                    and (" login " in row or "request-resume" in row)))
               for row in rows.splitlines()), "Recruiter scan/write is active; release deferred"
PY
old_backend="$(docker inspect ai-commerce-app --format '{{.Id}}')"
old_processor="$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')"
chrome_pid="$(systemctl show heyi-boss-chrome -p MainPID --value)"
test "$chrome_pid" != 0
cp -p "$bin/connector.py" "$bin/boss-pilot" "$backup/"
docker commit ai-commerce-frontend "$base_image" >/dev/null
docker cp ai-commerce-frontend:/usr/share/nginx/html/index.html "$backup/index.html"
docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/app.js \
  /usr/share/nginx/html/hr-workspace.css /etc/nginx/conf.d/default.conf > "$backup/unchanged.sha256"
python3 - "$backup" "$stage" <<'PY'
import pathlib, re, sys
backup, stage = map(pathlib.Path, sys.argv[1:])
text, count = re.subn(r'(/hr-workspace\.(?:js|css))\?v=[^"\']+',
                     r'\1?v=20260928-session-safety-8', (backup / "index.html").read_text())
assert count == 2
(stage / "index.html").write_text(text)
PY
cat > "$stage/Dockerfile.session-safety" <<'EOF'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY hr-workspace.js index.html /usr/share/nginx/html/
EOF
docker build --pull=false --build-arg "BASE_IMAGE=$base_image" \
  -f "$stage/Dockerfile.session-safety" -t "$release_image" "$stage"
docker run --rm --entrypoint nginx -v /etc/letsencrypt:/etc/letsencrypt:ro \
  -v /opt/heyi/shared/certbot-webroot:/var/www/certbot:ro "$release_image" -t
systemctl stop heyi-boss-connector
rollback() {
  trap - ERR
  # A rollback must keep automatic platform calls stopped.
  systemctl stop heyi-boss-connector
  install -o heyi-boss -g heyi-boss -m 0640 "$backup/connector.py" "$bin/connector.py"
  install -o heyi-boss -g heyi-boss -m 0750 "$backup/boss-pilot" "$bin/boss-pilot"
  HR_FRONTEND_IMAGE="$base_image" docker compose -p web_sdk_ \
    -f "$stage/compose.release.yaml" up -d --no-deps --force-recreate frontend
}
trap rollback ERR
install -o heyi-boss -g heyi-boss -m 0640 "$stage/guarded_cli.py" "$bin/guarded_cli.py"
install -o heyi-boss -g heyi-boss -m 0640 "$stage/connector.py" "$bin/connector.py"
install -o heyi-boss -g heyi-boss -m 0750 "$stage/boss-pilot" "$bin/boss-pilot"
runuser -u heyi-boss -- /opt/heyi/boss-agent/venv/bin/python - <<'PY'
import sys
sys.path.insert(0, "/opt/heyi/boss-agent/bin")
import connector
connector.pause_requests("MANUAL_REVIEW_REQUIRED")
PY
systemctl start heyi-boss-connector
systemctl is-active --quiet heyi-boss-connector
HR_FRONTEND_IMAGE="$release_image" docker compose -p web_sdk_ \
  -f "$stage/compose.release.yaml" up -d --no-deps --force-recreate frontend
docker exec -i ai-commerce-frontend sha256sum -c < "$backup/unchanged.sha256"
for file in connector.py guarded_cli.py boss-pilot; do
  test "$(sha256sum "$bin/$file" | cut -d' ' -f1)" = "$(sha256sum "$stage/$file" | cut -d' ' -f1)"
done
for file in hr-workspace.js index.html; do
  test "$(docker exec ai-commerce-frontend sha256sum "/usr/share/nginx/html/$file" | cut -d' ' -f1)" = \
    "$(sha256sum "$stage/$file" | cut -d' ' -f1)"
done
python3 - <<'PY'
import json, socket
with socket.socket(socket.AF_UNIX) as client:
    client.settimeout(10)
    client.connect("/opt/heyi/boss-agent/bridge/connector.sock")
    client.sendall(b'{"operation":"status"}\n')
    with client.makefile("rb") as stream:
        response = json.loads(stream.readline())
assert response["result"]["error"]["code"] == "RECRUITER_VERIFICATION_PENDING"
print("Connector accessible; automatic platform requests remain paused.")
PY
test "$(curl --noproxy '*' -sS --retry 3 --retry-connrefused --max-time 10 -o /dev/null -w '%{http_code}' \
  --resolve hoyee.org.cn:443:127.0.0.1 https://hoyee.org.cn/)" = 200
test "$(systemctl show heyi-boss-chrome -p MainPID --value)" = "$chrome_pid"
test "$(docker inspect ai-commerce-app --format '{{.Id}}')" = "$old_backend"
test "$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')" = "$old_processor"
trap - ERR
printf 'Published %s; Chrome and business workers preserved; recruiter reads paused.\n' "$release_image"
