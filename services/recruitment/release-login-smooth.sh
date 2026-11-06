#!/usr/bin/env bash
# Frontend-only update: preserve an in-flight recruiter scan and its services.
set -euo pipefail
release_dir="$(cd "$(dirname "$0")" && pwd)"
stage="$release_dir/staged"
backup="$release_dir/rollback"
base_image="ai-commerce-frontend:before-hr-login-smooth-20260928"
release_image="ai-commerce-frontend:hr-login-smooth-20260928"
mkdir -p "$backup"
(cd "$stage" && sha256sum -c manifest.sha256)
docker exec -i ai-commerce-frontend sha256sum -c < "$stage/baseline.sha256"
chrome_pid="$(systemctl show heyi-boss-chrome -p MainPID --value)"
connector_pid="$(systemctl show heyi-boss-connector -p MainPID --value)"
old_backend="$(docker inspect ai-commerce-app --format '{{.Id}}')"
old_processor="$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')"
docker commit ai-commerce-frontend "$base_image" >/dev/null
docker cp ai-commerce-frontend:/usr/share/nginx/html/index.html "$backup/index.html"
docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/app.js \
  /etc/nginx/conf.d/default.conf > "$backup/unchanged.sha256"
python3 - "$backup" "$stage" <<'PY'
import pathlib, re, sys
backup, stage = map(pathlib.Path, sys.argv[1:])
text, count = re.subn(r'(/hr-workspace\.(?:js|css))\?v=[^"\']+',
                     r'\1?v=20260928-login-smooth-9', (backup / "index.html").read_text())
assert count == 2
(stage / "index.html").write_text(text)
PY
cat > "$stage/Dockerfile.login-smooth" <<'EOF'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY hr-workspace.js hr-workspace.css index.html /usr/share/nginx/html/
EOF
docker build --pull=false --build-arg "BASE_IMAGE=$base_image" \
  -f "$stage/Dockerfile.login-smooth" -t "$release_image" "$stage"
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
printf 'Published %s; recruiter scan/browser services and commerce workers preserved.\n' "$release_image"
