#!/bin/sh
set -u

RUNTIME=/opt/heyi/boss-agent/runtime
mkdir -p "$RUNTIME"
chmod 700 "$RUNTIME"
umask 077
rm -f "$RUNTIME/pip-exit"
if PIP_DEFAULT_TIMEOUT=600 PIP_CACHE_DIR="$RUNTIME/pip-cache" \
    /opt/heyi/boss-agent/venv/bin/python -m pip install \
    --retries 5 boss-agent-cli==2.0.0 > "$RUNTIME/pip-install.log" 2>&1; then
    result=0
else
    result=$?
fi
printf '%s\n' "$result" > "$RUNTIME/pip-exit"
exit "$result"
