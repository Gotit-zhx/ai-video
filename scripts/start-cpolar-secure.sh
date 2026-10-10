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

gateway_pid=""
cpolar_pid=""
filter_pid=""
runtime_dir="$(mktemp -d "${TMPDIR:-/tmp}/cpolar-secure.XXXXXX")"
cleanup() {
  local pid
  for pid in "$cpolar_pid" "$gateway_pid" "$filter_pid"; do
    if [[ -n "$pid" ]]; then
      kill -TERM "$pid" 2>/dev/null || true
    fi
  done
  for pid in "$cpolar_pid" "$gateway_pid" "$filter_pid"; do
    if [[ -n "$pid" ]]; then
      wait "$pid" 2>/dev/null || true
    fi
  done
  rm -f "$runtime_dir/log.pipe"
  rmdir "$runtime_dir" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

gateway_args=()
if [[ "${CPOLAR_VIDEO_MEDIA_ENABLED:-false}" == "true" ]]; then
  gateway_args+=(--allow-video-media)
fi
"$python_bin" "$script_dir/cpolar-callback-gateway.py" \
  --listen-port "$gateway_port" --upstream "$upstream" "${gateway_args[@]}" &
gateway_pid=$!

gateway_ready=false
for _ in {1..30}; do
  if "$python_bin" - "$gateway_port" 2>/dev/null <<'PY'
import socket
import sys

with socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=0.2):
    pass
PY
  then
    gateway_ready=true
    break
  fi
  sleep 0.1
done
if [[ "$gateway_ready" != true ]] || ! kill -0 "$gateway_pid" 2>/dev/null; then
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
if [[ -n "${CPOLAR_SUBDOMAIN:-}" ]]; then
  args+=("-subdomain=${CPOLAR_SUBDOMAIN}")
fi

# The FIFO never stores logs on disk. Python's unbuffered output reports the
# hostname immediately, even when this wrapper's output is redirected.
mkfifo "$runtime_dir/log.pipe"
"$python_bin" -u - "$runtime_dir/log.pipe" "$frontend_return_url" <<'PY' &
import re
import sys
from urllib.parse import urlsplit


def safe_origin(value, schemes):
    try:
        parsed = urlsplit(value)
        host = parsed.hostname or ""
        if (parsed.scheme not in schemes or parsed.username or parsed.password
                or not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?", host)):
            return None
        port = parsed.port
        return f"{parsed.scheme}://{host}" + (f":{port}" if port else "")
    except ValueError:
        return None


frontend = safe_origin(sys.argv[2], {"http", "https"})
last_url = None
reported_errors = set()
# Only these fixed messages can be emitted; raw log lines may contain secrets.
error_reasons = (
    (r"authtoken|authentication|unauthorized|invalid token", "authentication failed; check the local cpolar credentials"),
    (r"subdomain.*(?:reserved|not available|already|in use|unavailable|denied)|(?:reserved|unavailable).*subdomain", "requested subdomain is unavailable or not reserved for this account"),
    (r"(?:plan|paid|premium|pro account|subscription)", "the requested tunnel option may require a different cpolar plan"),
    (r"too many|limit exceeded|maximum.*(?:tunnel|session)|simultaneous", "the account tunnel or session limit was reached"),
    (r"connection refused|no such host|network is unreachable|timed? out|timeout|failed to (?:connect|dial)|dial tcp|dial udp", "connection to the cpolar service failed; check network access and region"),
)
with open(sys.argv[1], encoding="utf-8", errors="replace") as stream:
    for line in stream:
        if "Tunnel established" in line:
            for candidate in re.findall(r'''https://[^\s"'<>]+''', line):
                url = safe_origin(candidate, {"https"})
                if url and url != last_url:
                    print(f"CPOLAR_PUBLIC_BASE_URL={url}", flush=True)
                    print(f"APP_SHOP_AUTH_CALLBACK_BASE_URL={url}", flush=True)
                    if frontend:
                        print(f"APP_SHOP_AUTH_FRONTEND_BASE_URL={frontend}", flush=True)
                    print(f"SHOPEE_CALLBACK={url}/api/v1/shop-authorizations/callback/shopee", flush=True)
                    print(f"TIKTOK_SHOP_CALLBACK={url}/api/v1/shop-authorizations/callback/tiktok_shop", flush=True)
                    last_url = url
        elif re.search(r"\berr(?:or)?\b|\bfatal\b|\bfailed\b", line, re.IGNORECASE):
            for pattern, reason in error_reasons:
                if re.search(pattern, line, re.IGNORECASE):
                    if reason not in reported_errors:
                        print(f"cpolar: {reason}", file=sys.stderr, flush=True)
                        reported_errors.add(reason)
                    break
if not last_url and not reported_errors:
    print("cpolar: exited before establishing an HTTPS tunnel; check the local cpolar configuration", file=sys.stderr, flush=True)
PY
filter_pid=$!

"$cpolar_bin" "${args[@]}" "$target" >"$runtime_dir/log.pipe" 2>&1 &
cpolar_pid=$!
cpolar_status=0
wait "$cpolar_pid" || cpolar_status=$?
cpolar_pid=""
wait "$filter_pid" || true
filter_pid=""
if [[ "$cpolar_status" -ne 0 ]]; then
  echo "cpolar exited with status $cpolar_status; raw logs were suppressed" >&2
fi
exit "$cpolar_status"
