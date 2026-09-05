#!/usr/bin/env bash
set -euo pipefail

cpolar_bin="${CPOLAR_BIN:-cpolar}"
python_bin="${PYTHON_BIN:-python3}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
gateway_port="${CPOLAR_GATEWAY_PORT:-39080}"
upstream="${CPOLAR_CALLBACK_UPSTREAM:-127.0.0.1:${FRONTEND_PORT:-3000}}"
frontend_return_url="${CPOLAR_FRONTEND_RETURN_URL:-http://localhost:${FRONTEND_PORT:-3000}}"
target="127.0.0.1:${gateway_port}"

if ! command -v "$cpolar_bin" >/dev/null 2>&1; then
  echo "cpolar executable not found: $cpolar_bin" >&2
  exit 127
fi
if ! command -v "$python_bin" >/dev/null 2>&1; then
  echo "python executable not found: $python_bin" >&2
  exit 127
fi

"$python_bin" "$script_dir/cpolar-callback-gateway.py" \
  --listen-port "$gateway_port" --upstream "$upstream" &
gateway_pid=$!
cleanup() {
  kill -TERM "$gateway_pid" 2>/dev/null || true
  wait "$gateway_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM HUP

for _ in {1..30}; do
  if "$python_bin" - "$gateway_port" <<'PY'
import socket
import sys

with socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=0.2):
    pass
PY
  then
    break
  fi
  sleep 0.1
done
if ! kill -0 "$gateway_pid" 2>/dev/null; then
  echo "callback-only gateway failed to start" >&2
  exit 1
fi

args=(
  http
  -proto=https
  -redirect-https=true
  -inspect-addr=false
  -dashboard=off
  -log=stdout
  -log-level=INFO
  "-tunnelName=${CPOLAR_TUNNEL_NAME:-product-capture}"
)
if [[ -n "${CPOLAR_REGION:-}" ]]; then
  args+=("-region=${CPOLAR_REGION}")
fi

# cpolar only reports the assigned random hostname through its own log stream.
# Keep that stream in-memory and print only the HTTPS hostname/callbacks, so an
# OAuth code or state can never be copied into a terminal log by this wrapper.
"$cpolar_bin" "${args[@]}" "$target" 2>&1 | awk -v frontend_return_url="$frontend_return_url" '
  match($0, /https:\/\/[^\"[:space:]]+/) && /Tunnel established/ {
    url = substr($0, RSTART, RLENGTH)
    if (url != last_url) {
      print "CPOLAR_PUBLIC_BASE_URL=" url
      print "APP_SHOP_AUTH_CALLBACK_BASE_URL=" url
      print "APP_SHOP_AUTH_FRONTEND_BASE_URL=" frontend_return_url
      print "SHOPEE_CALLBACK=" url "/api/v1/shop-authorizations/callback/shopee"
      print "TIKTOK_SHOP_CALLBACK=" url "/api/v1/shop-authorizations/callback/tiktok_shop"
      fflush()
      last_url = url
    }
  }
'
