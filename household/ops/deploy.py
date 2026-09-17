#!/usr/bin/env python3
"""Deploy this independent app without replacing existing server applications."""
import argparse
import datetime
import pathlib
import shlex
import subprocess
import tarfile
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', required=True)
    parser.add_argument('--identity', required=True)
    parser.add_argument('--public-url', required=True)
    args = parser.parse_args()
    if not args.public_url.startswith('https://') or not args.public_url.endswith('/chores/'):
        parser.error('public-url must be an HTTPS URL ending /chores/')
    release = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    ssh = ['ssh', '-i', args.identity, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', args.host]
    scp = ['scp', '-i', args.identity, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes']
    with tempfile.TemporaryDirectory(prefix='homecare-deploy-') as scratch:
        archive = pathlib.Path(scratch) / 'app.tar.gz'
        with tarfile.open(archive, 'w:gz') as out:
            for name in ['public', 'server.py', 'seed.py', 'mailer.py', 'requirements.txt', 'ops', 'README.md']:
                path = ROOT / name
                if path.exists():
                    out.add(path, arcname=name, filter=lambda info: None if '__pycache__' in info.name else info)
        remote_archive = f'/tmp/homecare-{release}.tar.gz'
        subprocess.run(scp + [str(archive), f'{args.host}:{remote_archive}'], check=True)
        command = REMOTE.replace('__RELEASE__', shlex.quote(release)).replace('__PUBLIC_URL__', shlex.quote(args.public_url))
        subprocess.run(ssh + ['bash', '-se'], input=command, text=True, check=True)
    print(f'Deployment healthy: {args.public_url} (release {release})')


REMOTE = r'''set -Eeuo pipefail
release=__RELEASE__
public_url=__PUBLIC_URL__
archive="/tmp/homecare-$release.tar.gz"
release_dir="/opt/household/releases/$release"
if ! id homecare >/dev/null 2>&1; then
    useradd --system --home-dir /var/lib/household --shell /usr/sbin/nologin homecare
fi
install -d -m 755 /opt/household/releases
install -d -o homecare -g homecare -m 700 /var/lib/household
install -d -m 755 "$release_dir"
tar -xzf "$archive" -C "$release_dir"
rm "$archive"
if ! python3 -c 'import ensurepip' >/dev/null 2>&1; then
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3-venv
fi
python3 -m venv "$release_dir/.venv"
"$release_dir/.venv/bin/pip" install --disable-pip-version-check -q -r "$release_dir/requirements.txt"
backup="/opt/household/deploy-backups/$release"
install -d -m 700 "$backup"
nginx_config=$(readlink -f /etc/nginx/sites-enabled/cliproxy)
for key in nginx snippet env unit; do
    case "$key" in
        nginx) target="$nginx_config" ;;
        snippet) target=/etc/nginx/snippets/household.conf ;;
        env) target=/etc/household.env ;;
        unit) target=/etc/systemd/system/household.service ;;
    esac
    if [ -e "$target" ]; then cp -p "$target" "$backup/$key"; fi
done
previous=$(readlink /opt/household/current || true)
service_was_active=0
if systemctl is-active --quiet household.service; then service_was_active=1; fi
rollback() {
    code=$?
    trap - ERR
    set +e
    echo 'Deployment failed; restoring prior application and proxy configuration.' >&2
    journalctl -u household.service -n 20 --no-pager >&2
    for key in nginx snippet env unit; do
        case "$key" in
            nginx) target="$nginx_config" ;;
            snippet) target=/etc/nginx/snippets/household.conf ;;
            env) target=/etc/household.env ;;
            unit) target=/etc/systemd/system/household.service ;;
        esac
        if [ -f "$backup/$key" ]; then cp -p "$backup/$key" "$target"; else rm -f "$target"; fi
    done
    if [ -n "$previous" ]; then ln -sfn "$previous" /opt/household/current; else rm -f /opt/household/current; fi
    systemctl daemon-reload
    if [ "$service_was_active" = 1 ]; then systemctl restart household.service; else systemctl stop household.service; fi
    if [ ! -f "$backup/unit" ]; then systemctl disable household.service >/dev/null 2>&1; fi
    nginx -t && systemctl reload nginx
    exit "$code"
}
trap rollback ERR
install -m 644 "$release_dir/ops/nginx.conf" /etc/nginx/snippets/household.conf
python3 - <<'PY'
import pathlib, re
p = pathlib.Path('/etc/nginx/sites-enabled/cliproxy')
s = p.read_text()
include = '    include /etc/nginx/snippets/household.conf;\n\n'
if 'include /etc/nginx/snippets/household.conf;' not in s:
    hits = list(re.finditer(r'(?m)^[ \t]*location / \{(?=[^{}]*proxy_pass\s+http://127\.0\.0\.1:8317/?\s*;)', s))
    if len(hits) != 1:
        raise RuntimeError('Expected one existing CLI proxy root location; refused ambiguous proxy edit')
    s = s[:hits[0].start()] + '\n' + include + s[hits[0].start():]
    p.write_text(s)
PY
nginx -t
printf 'DATA_DIR=/var/lib/household\nPUBLIC_URL=%s\nTRUST_PROXY=1\nPYTHONDONTWRITEBYTECODE=1\n' "$public_url" > /etc/household.env
chmod 600 /etc/household.env
install -m 644 "$release_dir/ops/household.service" /etc/systemd/system/household.service
ln -sfn "$release_dir" /opt/household/current
systemctl daemon-reload
systemctl enable household.service >/dev/null
systemctl restart household.service
healthy=0
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if curl -fsS http://127.0.0.1:8787/health >/dev/null 2>&1; then healthy=1; break; fi
    sleep 1
done
test "$healthy" = 1
systemctl reload nginx
curl --max-time 20 -fsS "$public_url"health
printf '\n'
systemctl is-active household.service
trap - ERR
'''


if __name__ == '__main__':
    main()
