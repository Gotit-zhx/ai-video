"""Publish the reply workflow UI and connector without restarting app/video jobs.

Usage: python3 release-passive-reply.py <staged release directory>
Requires passive_pool.py, hr-passive.js, and expected-baseline.json.
"""
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import sqlite3
import subprocess
import sys
import time

root = Path(sys.argv[1]).resolve()
expected = json.loads((root / 'expected-baseline.json').read_text())
assert not (root / 'activation.json').exists(), 'Already activated'
backup = root / 'rollback'; backup.mkdir(exist_ok=True)
module = Path('/opt/heyi/boss-agent/bin/passive_pool.py')


def run(*args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT)


def inspect(name):
    return json.loads(run('docker', 'inspect', name))[0]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def processes():
    return {name: inspect(name)['Id'] for name in
            ('ai-commerce-app', 'heyi-pi-runtime', 'heyi-commerce-image-processor')}


preserved = processes()
front = inspect('ai-commerce-frontend')
assert 'APP_HR_DESKTOP_ONLY=1' in run('systemctl', 'show', 'heyi-boss-connector', '-p', 'Environment', '--value').decode()
assert Path('/opt/heyi/boss-agent/runtime/resume-auto-pause').exists()
assert sha(module.read_bytes()) == expected['passive_pool.py']
shutil.copy2(module, backup / module.name)
for name in ('index.html', 'hr-passive.js'):
    data = run('docker', 'exec', 'ai-commerce-frontend', 'cat', '/usr/share/nginx/html/' + name)
    assert sha(data) == expected[name], 'Production drift: ' + name
    (backup / name).write_bytes(data)
run('/opt/heyi/boss-agent/venv/bin/python', '-m', 'py_compile', str(root / module.name))
db = '/opt/heyi/boss-agent/runtime/talent-pool.sqlite3'
with sqlite3.connect('file:' + db + '?mode=ro', uri=True) as source, sqlite3.connect(backup / 'talent-pool.sqlite3') as dest:
    source.backup(dest)
(backup / 'talent-pool.sqlite3').chmod(0o600)
index = (backup / 'index.html').read_text()
old = '/hr-passive.js?v=20260929-passive-1'
assert index.count(old) == 1, 'Review current cache version'
(root / 'index.html').write_text(index.replace(old, '/hr-passive.js?v=20260929-passive-reply-2'))
(root / 'Dockerfile').write_text('FROM ' + front['Image'] + '\nCOPY index.html hr-passive.js /usr/share/nginx/html/\n')
image = 'ai-commerce-frontend:' + root.name
run('docker', 'build', '-t', image, str(root))
labels = front['Config']['Labels']; service = labels['com.docker.compose.service']
compose = ['docker', 'compose', '--project-directory', labels['com.docker.compose.project.working_dir'],
           '-p', labels['com.docker.compose.project']]
for file in labels['com.docker.compose.project.config_files'].split(','): compose += ['-f', file]
override = root / 'compose.reply.json'
override.write_text(json.dumps({'services': {service: {'image': image}}}))
rollback = root / 'compose.rollback.json'
rollback.write_text(json.dumps({'services': {service: {'image': front['Config']['Image']}}}))
owner = pwd.getpwnam('heyi-boss')
try:
    temporary = module.with_suffix('.py.new')
    shutil.copy2(root / module.name, temporary)
    os.chown(temporary, owner.pw_uid, owner.pw_gid); temporary.chmod(0o640)
    os.replace(temporary, module)
    run('systemctl', 'restart', 'heyi-boss-connector')
    run('systemctl', 'is-active', '--quiet', 'heyi-boss-connector')
    run(*compose, '-f', str(override), 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', service)
    for name in ('index.html', 'hr-passive.js'):
        for attempt in range(20):
            try:
                served = run('curl', '--noproxy', '*', '-fsS', '--max-time', '5', '--resolve',
                             'hoyee.org.cn:443:127.0.0.1', 'https://hoyee.org.cn/' + name)
                assert sha(served) == sha((root / name).read_bytes())
                break
            except (subprocess.CalledProcessError, AssertionError):
                if attempt == 19: raise
                time.sleep(1)
    assert processes() == preserved, 'Unrelated process changed'
except BaseException:
    shutil.copy2(backup / module.name, module)
    os.chown(module, owner.pw_uid, owner.pw_gid)
    run('systemctl', 'restart', 'heyi-boss-connector')
    run(*compose, '-f', str(rollback), 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', service)
    raise
(root / 'activation.json').write_text(json.dumps({'image': image, 'preservedProcesses': preserved,
    'sha256': {name: sha((root / name).read_bytes()) for name in ('index.html', 'hr-passive.js', 'passive_pool.py')}}, indent=2))
print('Reply workflow published; frontend hashes verified; application/video processes preserved')
