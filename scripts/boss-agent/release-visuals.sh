#!/usr/bin/env bash
# Publish the HR presentation layer on top of the exact running frontend.
set -euo pipefail
release_dir="$(cd "$(dirname "$0")" && pwd)"
stage="$release_dir/staged"
backup="$release_dir/rollback"
base_image="ai-commerce-frontend:before-hr-visual-20260928"
release_image="ai-commerce-frontend:hr-visual-20260928"
mkdir -p "$backup"
(cd "$stage" && sha256sum -c manifest.sha256)
test "$(docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/hr-workspace.js | cut -d' ' -f1)" = \
  "faf96c2ba4ce067a56d49dff4196dd50f79cd298235dc95830eb18d7e56f6336"
test "$(docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/hr-workspace.css | cut -d' ' -f1)" = \
  "d01a541e48770ec698363c1f241ee4854fa38945e5c81e6a6b9a6fb3735ecd29"
old_backend="$(docker inspect ai-commerce-app --format '{{.Id}}')"
old_processor="$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')"
chrome_pid="$(systemctl show heyi-boss-chrome -p MainPID --value)"
connector_pid="$(systemctl show heyi-boss-connector -p MainPID --value)"
docker commit ai-commerce-frontend "$base_image" >/dev/null
docker cp ai-commerce-frontend:/usr/share/nginx/html/index.html "$backup/index.html"
docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/app.js \
  /etc/nginx/conf.d/default.conf > "$backup/unchanged.sha256"
# Only bump these two asset versions in the production HTML; keep all other
# features exactly as deployed, even if the local checkout differs.
python3 - "$backup/index.html" "$stage/index.html" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text()
text, count = re.subn(r'(/hr-workspace\.(?:js|css))\?v=[^"\']+',
                     r'\1?v=20260928-visual-1', text)
assert count == 2, "Expected exactly two HR asset references"
pathlib.Path(sys.argv[2]).write_text(text)
PY
cat > "$stage/Dockerfile.visuals" <<'EOF'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY hr-workspace.js hr-workspace.css index.html /usr/share/nginx/html/
EOF
docker build --pull=false --build-arg "BASE_IMAGE=$base_image" \
  -f "$stage/Dockerfile.visuals" -t "$release_image" "$stage"
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
docker exec ai-commerce-frontend nginx -t
docker exec -i ai-commerce-frontend sha256sum -c < "$backup/unchanged.sha256"
for file in hr-workspace.js hr-workspace.css index.html; do
  test "$(docker exec ai-commerce-frontend sha256sum "/usr/share/nginx/html/$file" | cut -d' ' -f1)" = \
    "$(sha256sum "$stage/$file" | cut -d' ' -f1)"
done
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
test "$(systemctl show heyi-boss-connector -p MainPID --value)" = "$connector_pid"
trap - ERR
printf 'Published %s; existing recruiter login and business services preserved.\n' "$release_image"
