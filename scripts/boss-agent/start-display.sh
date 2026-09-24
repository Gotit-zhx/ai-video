#!/bin/sh
set -eu

RUNTIME=/opt/heyi/boss-agent/runtime
AUTH="$RUNTIME/.Xauthority"
umask 077
mkdir -p "$RUNTIME"
xauth -f "$AUTH" add :98 . "$(openssl rand -hex 16)"
exec /usr/bin/Xvfb :98 -screen 0 1440x900x24 -nolisten tcp -auth "$AUTH"
