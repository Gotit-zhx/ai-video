#!/usr/bin/env bash
# Frontend-only login feedback release; preserve the active recruiter session.
set -euo pipefail
release_dir="$(cd "$(dirname "$0")" && pwd)"
stage="$release_dir/staged"
backup="$release_dir/rollback"
base_image="ai-commerce-frontend:before-hr-login-progress-20260928"
release_image="ai-commerce-frontend:hr-login-progress-20260928"
mkdir -p "$backup"
(cd "$stage" && sha256sum -c manifest.sha256)
test "$(docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/hr-workspace.js | cut -d' ' -f1)" = \
  "7f076676c494f945dcdd7d2a7d3dd770813a4e05cd47da1ddb0c1f1fe3a0fbb2"
test "$(docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/hr-workspace.css | cut -d' ' -f1)" = \
  "65c6fd6f2abcf114070cc9de4ce8d00ca2215c9a375730d0b0b6a38c224345f6"
chrome_pid="$(systemctl show heyi-boss-chrome -p MainPID --value)"
connector_pid="$(systemctl show heyi-boss-connector -p MainPID --value)"
old_backend="$(docker inspect ai-commerce-app --format '{{.Id}}')"
old_processor="$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')"
docker commit ai-commerce-frontend "$base_image" >/dev/null
docker cp ai-commerce-frontend:/usr/share/nginx/html/index.html "$backup/index.html"
docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/app.js \
  /etc/nginx/conf.d/default.conf > "$backup/unchanged.sha256"
python3 - "$backup/index.html" "$stage/index.html" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text()
text, count = re.subn(r'(/hr-workspace\.(?:js|css))\?v=[^"\']+',
                     r'\1?v=20260928-login-progress-4', text)
assert count == 2
pathlib.Path(sys.argv[2]).write_text(text)
PY
cat > "$stage/Dockerfile.login-progress" <<'EOF'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY hr-workspace.js hr-workspace.css index.html /usr/share/nginx/html/
EOF
docker build --pull=false --build-arg "BASE_IMAGE=$base_image" \
  -f "$stage/Dockerfile.login-progress" -t "$release_image" "$stage"
docker run --rm --entrypoint nginx -v /etc/letsencrypt:/etc/letsencrypt:ro \
  -v /opt/heyi/shared/certbot-webroot:/var/www/certbot:ro "$release_image" -t
rollback() {
  trap - ERR
  HR_FRONTEND_IMAGE="$base_image" docker compose -p web_sdk_ \
    -f "$stage/compose.release.yaml" up -d --no-deps --force-recreate frontend
}
trap rollback ERR
HR_FRONTEND_IMAGE="$release_image" docker compose -p web_sdk_ \
  -f "$stage/compose.release.yaml" up -d --no-deps --force-recreate frontend
docker exec -i ai-commerce-frontend sha256sum -c < "$backup/unchanged.sha256"
for file in hr-workspace.js hr-workspace.css index.html; do
  test "$(docker exec ai-commerce-frontend sha256sum "/usr/share/nginx/html/$file" | cut -d' ' -f1)" = \
    "$(sha256sum "$stage/$file" | cut -d' ' -f1)"
done
test "$(curl --noproxy '*' -sS --retry 3 --retry-connrefused --max-time 10 -o /dev/null -w '%{http_code}' \
  --resolve hoyee.org.cn:443:127.0.0.1 https://hoyee.org.cn/)" = 200
test "$(systemctl show heyi-boss-chrome -p MainPID --value)" = "$chrome_pid"
test "$(systemctl show heyi-boss-connector -p MainPID --value)" = "$connector_pid"
test "$(docker inspect ai-commerce-app --format '{{.Id}}')" = "$old_backend"
test "$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')" = "$old_processor"
trap - ERR
printf 'Published %s; recruiter session and business workers preserved.\n' "$release_image"
