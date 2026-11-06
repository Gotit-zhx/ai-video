#!/usr/bin/env bash
# Release the local-only disconnect flow without logging out a live account.
set -euo pipefail
release_dir="$(cd "$(dirname "$0")" && pwd)"
stage="$release_dir/staged"
backup="$release_dir/rollback"
connector="/opt/heyi/boss-agent/bin/connector.py"
base_image="ai-commerce-frontend:before-hr-disconnect-20260928"
release_image="ai-commerce-frontend:hr-disconnect-20260928"
mkdir -p "$backup"
(cd "$stage" && sha256sum -c manifest.sha256)
test "$(sha256sum "$connector" | cut -d' ' -f1)" = \
  "0ec58a07c2e3390e2b069e5437e98bfd19259ee2e99d280a5c44f57ed5328d27"
test "$(docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/hr-workspace.js | cut -d' ' -f1)" = \
  "7bd97e969733985f3d2748eeeffe956869dcf3715c2a6301653e0b029d4c722a"
# Do not interrupt an active scan or browser-credential synchronization.
python3 - <<'PY'
import subprocess
rows = subprocess.check_output(["ps", "-u", "heyi-boss", "-o", "args="], text=True)
assert not any(("--sync-browser-login" in row or
                ("venv/bin/boss " in row and " login " in row))
               for row in rows.splitlines()), "Recruiter login is active; release deferred"
PY
old_backend="$(docker inspect ai-commerce-app --format '{{.Id}}')"
old_processor="$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')"
chrome_pid="$(systemctl show heyi-boss-chrome -p MainPID --value)"
cp -p "$connector" "$backup/connector.py"
docker commit ai-commerce-frontend "$base_image" >/dev/null
docker cp ai-commerce-frontend:/usr/share/nginx/html/index.html "$backup/index.html"
docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/app.js \
  /usr/share/nginx/html/hr-workspace.css /etc/nginx/conf.d/default.conf \
  > "$backup/unchanged.sha256"
python3 - "$backup/index.html" "$stage/index.html" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text()
text, count = re.subn(r'(/hr-workspace\.(?:js|css))\?v=[^"\']+',
                     r'\1?v=20260928-disconnect-1', text)
assert count == 2
pathlib.Path(sys.argv[2]).write_text(text)
PY
cat > "$stage/Dockerfile.disconnect" <<'EOF'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY hr-workspace.js index.html /usr/share/nginx/html/
EOF
docker build --pull=false --build-arg "BASE_IMAGE=$base_image" \
  -f "$stage/Dockerfile.disconnect" -t "$release_image" "$stage"
docker run --rm --entrypoint nginx -v /etc/letsencrypt:/etc/letsencrypt:ro \
  -v /opt/heyi/shared/certbot-webroot:/var/www/certbot:ro "$release_image" -t
HR_FRONTEND_IMAGE="$release_image" docker compose -p web_sdk_ \
  -f "$stage/compose.release.yaml" config -q
rollback() {
  trap - ERR
  cp -p "$backup/connector.py" "$connector"
  systemctl restart heyi-boss-connector
  HR_FRONTEND_IMAGE="$base_image" docker compose -p web_sdk_ \
    -f "$stage/compose.release.yaml" up -d --no-deps --force-recreate frontend
  printf 'Rolled back HR disconnect release.\n' >&2
}
trap rollback ERR
install -o heyi-boss -g heyi-boss -m 0640 "$stage/connector.py" "$connector"
systemctl restart heyi-boss-connector
systemctl is-active --quiet heyi-boss-connector
test "$(systemctl show heyi-boss-chrome -p MainPID --value)" = "$chrome_pid"
HR_FRONTEND_IMAGE="$release_image" docker compose -p web_sdk_ \
  -f "$stage/compose.release.yaml" up -d --no-deps --force-recreate frontend
docker exec ai-commerce-frontend nginx -t
docker exec -i ai-commerce-frontend sha256sum -c < "$backup/unchanged.sha256"
for file in hr-workspace.js index.html; do
  test "$(docker exec ai-commerce-frontend sha256sum "/usr/share/nginx/html/$file" | cut -d' ' -f1)" = \
    "$(sha256sum "$stage/$file" | cut -d' ' -f1)"
done
test "$(sha256sum "$connector" | cut -d' ' -f1)" = \
  "$(sha256sum "$stage/connector.py" | cut -d' ' -f1)"
test "$(curl --noproxy '*' -sS --retry 3 --retry-connrefused --max-time 10 -o /dev/null -w '%{http_code}' \
  --resolve hoyee.org.cn:443:127.0.0.1 https://hoyee.org.cn/)" = 200
test "$(curl --noproxy '*' -sS --max-time 10 -o /dev/null -w '%{http_code}' \
  --resolve hoyee.org.cn:443:127.0.0.1 https://hoyee.org.cn/api/v1/hr/status)" = 401
test "$(docker inspect ai-commerce-app --format '{{.Id}}')" = "$old_backend"
test "$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')" = "$old_processor"
test "$(systemctl show heyi-boss-chrome -p MainPID --value)" = "$chrome_pid"
trap - ERR
printf 'Published %s; no real logout/login invoked; Chrome and business workers preserved.\n' "$release_image"
