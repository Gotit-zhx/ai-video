#!/usr/bin/env bash
# Scoped release on top of the running frontend; preserve the browser session.
# Place connector.py, hr-workspace.css, compose.release.yaml and manifest.sha256
# in staged/ beside this script. Refuse a changed production baseline.
set -euo pipefail
release_dir="$(cd "$(dirname "$0")" && pwd)"
stage="$release_dir/staged"
backup="$release_dir/rollback"
connector="/opt/heyi/boss-agent/bin/connector.py"
base_image="ai-commerce-frontend:before-hr-resume-mono-20260928"
release_image="ai-commerce-frontend:hr-resume-mono-20260928"
mkdir -p "$backup"
(cd "$stage" && sha256sum -c manifest.sha256)
test "$(sha256sum "$connector" | cut -d' ' -f1)" = \
  "02e95905cd5a17b587940db517ed2221c030869094150c611ff7e7fed8726569"
test "$(docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/hr-workspace.js | cut -d' ' -f1)" = \
  "7bd97e969733985f3d2748eeeffe956869dcf3715c2a6301653e0b029d4c722a"
test "$(docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/hr-workspace.css | cut -d' ' -f1)" = \
  "bdd6046adda522a6d74e6b046c922e754b0d0db41b70ee07262193513235db00"
old_backend="$(docker inspect ai-commerce-app --format '{{.Id}}')"
old_processor="$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')"
chrome_pid="$(systemctl show heyi-boss-chrome -p MainPID --value)"
cp -p "$connector" "$backup/connector.py"
docker commit ai-commerce-frontend "$base_image" >/dev/null
docker cp ai-commerce-frontend:/usr/share/nginx/html/index.html "$backup/index.html"
docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/app.js \
  /usr/share/nginx/html/hr-workspace.js /usr/share/nginx/html/resend-theme.css \
  /etc/nginx/conf.d/default.conf > "$backup/unchanged.sha256"
python3 - "$backup/index.html" "$stage/index.html" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text()
text, count = re.subn(r'(/hr-workspace\.(?:js|css))\?v=[^"\']+',
                     r'\1?v=20260928-resume-mono-1', text)
assert count == 2, "Expected exactly two HR asset references"
pathlib.Path(sys.argv[2]).write_text(text)
PY
cat > "$stage/Dockerfile.resume-theme" <<'EOF'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY hr-workspace.css index.html /usr/share/nginx/html/
EOF
docker build --pull=false --build-arg "BASE_IMAGE=$base_image" \
  -f "$stage/Dockerfile.resume-theme" -t "$release_image" "$stage"
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
  printf 'Rolled back HR release; Chrome profile preserved.\n' >&2
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
for file in hr-workspace.css index.html; do
  test "$(docker exec ai-commerce-frontend sha256sum "/usr/share/nginx/html/$file" | cut -d' ' -f1)" = \
    "$(sha256sum "$stage/$file" | cut -d' ' -f1)"
done
test "$(sha256sum "$connector" | cut -d' ' -f1)" = \
  "$(sha256sum "$stage/connector.py" | cut -d' ' -f1)"
for attempt in $(seq 1 10); do
  if test "$(curl --noproxy '*' -sS --max-time 10 -o /dev/null -w '%{http_code}' \
    --resolve hoyee.org.cn:443:127.0.0.1 https://hoyee.org.cn/)" = 200; then break; fi
  test "$attempt" -lt 10
  sleep 1
done
test "$(curl --noproxy '*' -sS --max-time 10 -o /dev/null -w '%{http_code}' \
  --resolve hoyee.org.cn:443:127.0.0.1 https://hoyee.org.cn/api/v1/hr/status)" = 401
test "$(docker inspect ai-commerce-app --format '{{.Id}}')" = "$old_backend"
test "$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')" = "$old_processor"
test "$(systemctl show heyi-boss-chrome -p MainPID --value)" = "$chrome_pid"
trap - ERR
printf 'Published %s; browser session and business workers preserved.\n' "$release_image"
