#!/bin/sh
set -eu

PROFILE=/opt/heyi/boss-agent/accounts/pilot/chrome-profile
mkdir -p "$PROFILE"
chmod 700 "$PROFILE"
for _ in $(seq 1 30); do
    if [ -S /tmp/.X11-unix/X98 ]; then
        exec /usr/bin/google-chrome \
            --remote-debugging-address=127.0.0.1 \
            --remote-debugging-port=9222 \
            --user-data-dir="$PROFILE" \
            --no-first-run \
            --no-default-browser-check \
            --disable-dev-shm-usage \
            --restore-last-session \
            about:blank
    fi
    sleep 1
done
echo "Xvfb display :98 did not become ready" >&2
exit 1
