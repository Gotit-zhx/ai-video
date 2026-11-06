#!/usr/bin/env bash
# Run from the transferred release directory; preserve the exact live frontend.
set -euo pipefail

release_dir="$(cd "$(dirname "$0")" && pwd)"
stage="$release_dir/staged"
backup="$release_dir/rollback"
connector="/opt/heyi/boss-agent/bin/connector.py"
release_image="ai-commerce-frontend:hr-login-sync-20260928"
base_image="ai-commerce-frontend:before-hr-login-sync-20260928"
mkdir -p "$backup"

(cd "$stage" && sha256sum -c manifest.sha256)
test "$(sha256sum "$connector" | cut -d' ' -f1)" = \
  "3d2feebb2eded9c66dc8837bb366c17fe51cd5f2e5201bd96c3df5c82152b882"
test "$(docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/hr-workspace.js | cut -d' ' -f1)" = \
  "b2666e381f135aaf84fa7e46a9bf901047b3bff7a1ae76c2c8a6851e6cb86847"
old_backend="$(docker inspect ai-commerce-app --format '{{.Id}}')"
old_processor="$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')"
chrome_pid="$(systemctl show heyi-boss-chrome -p MainPID --value)"
cp -p "$connector" "$backup/connector.py"
docker commit ai-commerce-frontend "$base_image" >/dev/null
docker exec ai-commerce-frontend sha256sum \
  /usr/share/nginx/html/app.js /usr/share/nginx/html/index.html \
  /etc/nginx/conf.d/default.conf > "$backup/unchanged.sha256"

cat > "$stage/Dockerfile.login-sync" <<'EOF'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY hr-workspace.js /usr/share/nginx/html/hr-workspace.js
EOF
docker build --pull=false --build-arg "BASE_IMAGE=$base_image" \
  -f "$stage/Dockerfile.login-sync" -t "$release_image" "$stage"
docker run --rm --entrypoint nginx -v /etc/letsencrypt:/etc/letsencrypt:ro \
  -v /opt/heyi/shared/certbot-webroot:/var/www/certbot:ro "$release_image" -t
HR_FRONTEND_IMAGE="$release_image" docker compose -p web_sdk_ \
  -f "$stage/compose.release.yaml" config -q

rollback() {
  trap - ERR
  cp -p "$backup/connector.py" "$connector"
  systemctl restart heyi-boss-connector
  # The old connector closes Chrome on startup; restore the retained profile.
  systemctl start heyi-boss-chrome
  HR_FRONTEND_IMAGE="$base_image" docker compose -p web_sdk_ \
    -f "$stage/compose.release.yaml" up -d --no-deps --force-recreate frontend
  printf 'Rolled back frontend and connector; retained Chrome profile.\n' >&2
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
test "$(docker exec ai-commerce-frontend sha256sum /usr/share/nginx/html/hr-workspace.js | cut -d' ' -f1)" = \
  "$(sha256sum "$stage/hr-workspace.js" | cut -d' ' -f1)"
test "$(sha256sum "$connector" | cut -d' ' -f1)" = \
  "$(sha256sum "$stage/connector.py" | cut -d' ' -f1)"
for attempt in $(seq 1 10); do
  if test "$(curl --noproxy '*' -sS --max-time 10 -o /dev/null -w '%{http_code}' \
    --resolve hoyee.org.cn:443:127.0.0.1 https://hoyee.org.cn/)" = 200; then
    break
  fi
  test "$attempt" -lt 10
  sleep 1
done
test "$(curl --noproxy '*' -sS --max-time 10 -o /dev/null -w '%{http_code}' \
  --resolve hoyee.org.cn:443:127.0.0.1 https://hoyee.org.cn/api/v1/hr/status)" = 401
test "$(docker inspect ai-commerce-app --format '{{.Id}}')" = "$old_backend"
test "$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')" = "$old_processor"
test "$(systemctl show heyi-boss-chrome -p MainPID --value)" = "$chrome_pid"
trap - ERR
printf 'Published %s; Chrome session and business workers preserved.\n' "$release_image"
