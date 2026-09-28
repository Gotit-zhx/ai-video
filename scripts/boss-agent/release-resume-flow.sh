#!/usr/bin/env bash
# Preserve the logged-in Chrome while releasing resume names, requests and tracking.
set -euo pipefail
release_dir="$(cd "$(dirname "$0")" && pwd)"
stage="$release_dir/staged"
backup="$release_dir/rollback"
connector="/opt/heyi/boss-agent/bin/connector.py"
base_image="ai-commerce-frontend:before-hr-resume-tracking-20260928"
release_image="ai-commerce-frontend:hr-resume-tracking-20260928"
mkdir -p "$backup"
(cd "$stage" && sha256sum -c manifest.sha256)
test "$(sha256sum "$connector" | cut -d' ' -f1)" = \
  "a3aa8a4dd9fd544945191c5121d306fe98a26d2af1fc009b6fd4c0b7c214a797"
test "$(docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/hr-workspace.js | cut -d' ' -f1)" = \
  "8b9a675a39c86aeb84b5ed28ae234ed155760871da75f99cc47fff818beec119"
safe_to_restart() {
  python3 - <<'PY'
import subprocess
rows = subprocess.check_output(["ps", "-u", "heyi-boss", "-o", "args="], text=True)
assert not any(("--sync-browser-login" in row or "--prepare-recruiter-chat" in row
                or ("venv/bin/boss " in row and (" login " in row or "request-resume" in row)))
               for row in rows.splitlines()), "Recruiter scan/write is active; release deferred"
PY
}
safe_to_restart
old_backend="$(docker inspect ai-commerce-app --format '{{.Id}}')"
old_processor="$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')"
chrome_pid="$(systemctl show heyi-boss-chrome -p MainPID --value)"
cp -p "$connector" "$backup/connector.py"
docker commit ai-commerce-frontend "$base_image" >/dev/null
docker cp ai-commerce-frontend:/usr/share/nginx/html/index.html "$backup/index.html"
docker cp ai-commerce-frontend:/etc/nginx/conf.d/default.conf "$backup/default.conf"
docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/app.js \
  /usr/share/nginx/html/hr-workspace.css > "$backup/unchanged.sha256"
python3 - "$backup" "$stage" <<'PY'
import pathlib, re, sqlite3, sys
backup, stage = map(pathlib.Path, sys.argv[1:])
with sqlite3.connect("/opt/heyi/boss-agent/runtime/resume-requests.sqlite3") as source, \
     sqlite3.connect(backup / "resume-requests.sqlite3") as destination:
    source.backup(destination)
(backup / "resume-requests.sqlite3").chmod(0o600)
# Keep rollback compatible with the additive tracking column without restoring
# a stale database over requests that may arrive during this release.
old = (backup / "connector.py").read_text()
old = old.replace("INSERT OR REPLACE INTO resume_requests VALUES",
                  "INSERT OR REPLACE INTO resume_requests "
                  "(friend_id,requested_at,baseline_mid,state,message_id,updated_at,error) VALUES")
(backup / "connector-compatible.py").write_text(old)
text, count = re.subn(r'(/hr-workspace\.(?:js|css))\?v=[^"\']+',
                     r'\1?v=20260928-resume-tracking-6', (backup / "index.html").read_text())
assert count == 2
(stage / "index.html").write_text(text)
config, count = re.subn(
    r'(location \^~ /api/v1/hr/ \{[^}]*?proxy_read_timeout )(?:100|240)s;',
    r'\g<1>240s;', (backup / "default.conf").read_text())
assert count == 1
(stage / "default.conf").write_text(config)
PY
cat > "$stage/Dockerfile.resume-flow" <<'EOF'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY hr-workspace.js index.html /usr/share/nginx/html/
COPY default.conf /etc/nginx/conf.d/default.conf
EOF
docker build --pull=false --build-arg "BASE_IMAGE=$base_image" \
  -f "$stage/Dockerfile.resume-flow" -t "$release_image" "$stage"
docker run --rm --entrypoint nginx -v /etc/letsencrypt:/etc/letsencrypt:ro \
  -v /opt/heyi/shared/certbot-webroot:/var/www/certbot:ro "$release_image" -t
safe_to_restart
rollback() {
  trap - ERR
  install -o heyi-boss -g heyi-boss -m 0640 "$backup/connector-compatible.py" "$connector"
  systemctl restart heyi-boss-connector
  HR_FRONTEND_IMAGE="$base_image" docker compose -p web_sdk_ \
    -f "$stage/compose.release.yaml" up -d --no-deps --force-recreate frontend
}
trap rollback ERR
install -o heyi-boss -g heyi-boss -m 0640 "$stage/connector.py" "$connector"
systemctl restart heyi-boss-connector
systemctl is-active --quiet heyi-boss-connector
HR_FRONTEND_IMAGE="$release_image" docker compose -p web_sdk_ \
  -f "$stage/compose.release.yaml" up -d --no-deps --force-recreate frontend
docker exec -i ai-commerce-frontend sha256sum -c < "$backup/unchanged.sha256"
for file in hr-workspace.js index.html; do
  test "$(docker exec ai-commerce-frontend sha256sum "/usr/share/nginx/html/$file" | cut -d' ' -f1)" = \
    "$(sha256sum "$stage/$file" | cut -d' ' -f1)"
done
test "$(docker exec ai-commerce-frontend sha256sum /etc/nginx/conf.d/default.conf | cut -d' ' -f1)" = \
  "$(sha256sum "$stage/default.conf" | cut -d' ' -f1)"
test "$(sha256sum "$connector" | cut -d' ' -f1)" = "$(sha256sum "$stage/connector.py" | cut -d' ' -f1)"
test "$(curl --noproxy '*' -sS --retry 3 --retry-connrefused --max-time 10 -o /dev/null -w '%{http_code}' \
  --resolve hoyee.org.cn:443:127.0.0.1 https://hoyee.org.cn/)" = 200
test "$(systemctl show heyi-boss-chrome -p MainPID --value)" = "$chrome_pid"
test "$(docker inspect ai-commerce-app --format '{{.Id}}')" = "$old_backend"
test "$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')" = "$old_processor"
trap - ERR
printf 'Published %s; recruiter browser and business workers preserved.\n' "$release_image"
