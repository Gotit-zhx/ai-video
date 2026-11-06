#!/usr/bin/env bash
# Run on the production host after transferring the staged directory.
set -euo pipefail

release_dir="$(cd "$(dirname "$0")/.." && pwd)"
stage="$release_dir/staged"
base_image="ai-commerce-frontend:knowledge-preview-20260923"
release_image="ai-commerce-frontend:hr-boss-20260924"
old_image="$(docker inspect ai-commerce-frontend --format '{{.Config.Image}}')"
old_backend="$(docker inspect ai-commerce-app --format '{{.Id}}')"
old_processor="$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')"
rollback_image="ai-commerce-frontend:hr-before-20260924"
connector="/opt/heyi/boss-agent/bin/connector.py"
service="/etc/systemd/system/heyi-boss-connector.service"
sudoers="/etc/sudoers.d/heyi-boss-services"
backup="$release_dir/rollback"
mkdir -p "$backup"

rollback() {
  printf 'Release failed; restoring previous frontend and connector.\n' >&2
  cp "$backup/connector.py" "$connector"
  cp "$backup/heyi-boss-connector.service" "$service"
  if test -f "$backup/heyi-boss-services.sudoers"; then
    install -o root -g root -m 0440 "$backup/heyi-boss-services.sudoers" "$sudoers"
  else
    rm -f "$sudoers"
  fi
  systemctl daemon-reload
  systemctl enable --now heyi-boss-display.service heyi-boss-chrome.service
  systemctl restart heyi-boss-connector
  HR_FRONTEND_IMAGE="$old_image" docker compose -p web_sdk_ -f "$stage/compose.release.yaml" \
    up -d --no-deps --force-recreate frontend
}
test "$(docker image inspect "$base_image" --format '{{.Id}}')" = \
  "sha256:610d31b547a5f62ec0068b81e6c70eca11aa223b42373be8d90d04694228176c"
(cd "$stage" && sha256sum -c manifest.sha256)
for mapping in \
  "app.js:/usr/share/nginx/html/app.js" \
  "index.html:/usr/share/nginx/html/index.html" \
  "default.conf:/etc/nginx/conf.d/default.conf"; do
  filename="${mapping%%:*}"
  path="${mapping#*:}"
  live="$(docker exec ai-commerce-frontend sha256sum "$path" | cut -d' ' -f1)"
  expected="$(sha256sum "$stage/$filename" | cut -d' ' -f1)"
  test "$live" = "$expected" || {
    printf 'Live frontend drifted since staging: %s\n' "$filename" >&2
    exit 1
  }
done

cp "$connector" "$backup/connector.py"
cp "$service" "$backup/heyi-boss-connector.service"
if test -f "$sudoers"; then cp "$sudoers" "$backup/heyi-boss-services.sudoers"; fi
/usr/sbin/visudo -cf "$stage/heyi-boss-services.sudoers"
docker commit ai-commerce-frontend "$rollback_image" >/dev/null
old_image="$rollback_image"
trap rollback ERR
docker build --pull=false --build-arg "BASE_IMAGE=$base_image" \
  -f "$stage/Dockerfile.release" -t "$release_image" "$stage"
docker run --rm --entrypoint nginx -v /etc/letsencrypt:/etc/letsencrypt:ro \
  -v /opt/heyi/shared/certbot-webroot:/var/www/certbot:ro "$release_image" -t
HR_FRONTEND_IMAGE="$release_image" docker compose -p web_sdk_ -f "$stage/compose.release.yaml" config -q

install -o heyi-boss -g heyi-boss -m 0640 "$stage/connector.py" "$connector"
install -o root -g root -m 0644 "$stage/heyi-boss-connector.service" "$service"
install -o root -g root -m 0440 "$stage/heyi-boss-services.sudoers" "$sudoers"
systemctl daemon-reload
systemctl restart heyi-boss-connector
systemctl is-active --quiet heyi-boss-connector
systemctl disable --now heyi-boss-chrome.service heyi-boss-display.service
HR_FRONTEND_IMAGE="$release_image" docker compose -p web_sdk_ -f "$stage/compose.release.yaml" \
  up -d --no-deps --force-recreate frontend
test "$(docker inspect ai-commerce-frontend --format '{{.Config.Image}}')" = "$release_image"
docker exec ai-commerce-frontend nginx -t
for filename in app.js index.html hr-workspace.js hr-workspace.css; do
  live="$(docker exec ai-commerce-frontend sha256sum "/usr/share/nginx/html/$filename" | cut -d' ' -f1)"
  expected="$(sha256sum "$stage/$filename" | cut -d' ' -f1)"
  test "$live" = "$expected"
done
test "$(curl -ksS -o /dev/null -w '%{http_code}' https://hoyee.org.cn/)" = 200
test "$(curl -ksS -o /dev/null -w '%{http_code}' https://hoyee.org.cn/api/v1/hr/status)" = 401
test "$(curl -ksS -o /dev/null -w '%{http_code}' https://hoyee.org.cn/hr-workspace.js)" = 200
test "$(docker inspect ai-commerce-app --format '{{.Id}}')" = "$old_backend"
test "$(docker inspect heyi-commerce-image-processor --format '{{.Id}}')" = "$old_processor"
trap - ERR
printf 'Published %s; backend and product workers were not recreated.\n' "$release_image"
