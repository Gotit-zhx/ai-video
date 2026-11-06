"""Deploy reviewed HR modules and assets over an asserted live baseline.

Usage: python3 release-resume-review.py <staged release directory>
Stage the files below, expected-baseline.json and code-commit.txt first.
Only the recruiter connector and frontend restart; app/video jobs are preserved.
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
modules = ('connector.py', 'passive_pool.py', 'resume_storage.py')
assets = ('hr-passive.js', 'hr-pool.js', 'hr-workspace.css', 'hr-resume-preview.js')
if (root / 'job-jd-release.json').exists():
    assert json.loads((root / 'job-jd-release.json').read_text()) == {'enabled': True}
    modules += ('recruitment_demands.py',)
    assets += ('hr-demands.js', 'hr-workspace.js', 'hr-desktop-bridge.js')
expected = json.loads((root / 'expected-baseline.json').read_text())
assert not (root / 'activation.json').exists(), 'Already activated'
backup = root / 'rollback'
backup.mkdir(exist_ok=True)
module_root = Path('/opt/heyi/boss-agent/bin')


def run(*args):
    environment = os.environ.copy()
    if args[:2] == ('docker', 'compose'):
        environment['HR_FRONTEND_IMAGE'] = front['Config']['Image']
    return subprocess.check_output(args, stderr=subprocess.STDOUT, env=environment)


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
for name in modules:
    assert sha((module_root / name).read_bytes()) == expected[name], 'Production drift: ' + name
    shutil.copy2(module_root / name, backup / name)
    run('/opt/heyi/boss-agent/venv/bin/python', '-m', 'py_compile', str(root / name))
for name in ('index.html', *(name for name in assets if name in expected)):
    data = run('docker', 'exec', 'ai-commerce-frontend', 'cat', '/usr/share/nginx/html/' + name)
    assert sha(data) == expected[name], 'Production drift: ' + name
    (backup / name).write_bytes(data)
db = '/opt/heyi/boss-agent/runtime/talent-pool.sqlite3'
with sqlite3.connect('file:' + db + '?mode=ro', uri=True) as source, sqlite3.connect(backup / 'talent-pool.sqlite3') as dest:
    source.backup(dest)
(backup / 'talent-pool.sqlite3').chmod(0o600)
index = (backup / 'index.html').read_text()
patches = (
    ('/hr-workspace.css?v=20260929-passive-1', '/hr-workspace.css?v=20260929-resume-review-1'),
    ('/hr-pool.js?v=20260929-passive-1', '/hr-pool.js?v=20260929-resume-review-1'),
    ('<script src="/hr-passive.js?v=20260929-passive-realtime-1" defer></script>',
     '<script src="/hr-resume-preview.js?v=20260929-resume-review-1" defer></script>\n'
     '  <script src="/hr-passive.js?v=20260929-resume-review-1" defer></script>'),
)
if (root / 'index-patches.json').exists():
    patches = json.loads((root / 'index-patches.json').read_text())
for old, new in patches:
    assert index.count(old) == 1, 'Review current cache version: ' + old
    index = index.replace(old, new)
(root / 'index.html').write_text(index)
# Exclude runtime data, backups and installers from the Docker build context.
(root / '.dockerignore').write_text('*\n!Dockerfile\n!index.html\n' + ''.join('!' + name + '\n' for name in assets))
(root / 'Dockerfile').write_text('FROM ' + front['Image'] + '\nCOPY index.html ' + ' '.join(assets) + ' /usr/share/nginx/html/\n')
image = 'ai-commerce-frontend:' + root.name
run('docker', 'build', '-t', image, str(root))
labels = front['Config']['Labels']
service = labels['com.docker.compose.service']
compose = ['docker', 'compose', '--project-directory', labels['com.docker.compose.project.working_dir'],
           '-p', labels['com.docker.compose.project']]
for file in labels['com.docker.compose.project.config_files'].split(','):
    compose += ['-f', file]
override = root / 'compose.review.json'
rollback = root / 'compose.rollback.json'
override.write_text(json.dumps({'services': {service: {'image': image}}}))
rollback.write_text(json.dumps({'services': {service: {'image': front['Config']['Image']}}}))
run(*compose, '-f', str(override), 'config', '-q')
owner = pwd.getpwnam('heyi-boss')


def install(directory):
    for name in modules:
        target = module_root / name
        temporary = target.with_suffix('.py.new')
        shutil.copy2(directory / name, temporary)
        os.chown(temporary, owner.pw_uid, owner.pw_gid)
        temporary.chmod(0o640)
        os.replace(temporary, target)


try:
    install(root)
    run('systemctl', 'restart', 'heyi-boss-connector')
    run('systemctl', 'is-active', '--quiet', 'heyi-boss-connector')
    run(*compose, '-f', str(override), 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', service)
    for name in ('index.html', *assets):
        for attempt in range(20):
            try:
                served = run('curl', '--noproxy', '*', '-fsS', '--max-time', '5', '--resolve',
                             'hoyee.org.cn:443:127.0.0.1', 'https://hoyee.org.cn/' + name)
                assert sha(served) == sha((root / name).read_bytes())
                break
            except (subprocess.CalledProcessError, AssertionError):
                if attempt == 19:
                    raise
                time.sleep(1)
    assert processes() == preserved, 'Unrelated process changed'
except BaseException:
    install(backup)
    run('systemctl', 'restart', 'heyi-boss-connector')
    run(*compose, '-f', str(rollback), 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', service)
    raise
(root / 'activation.json').write_text(json.dumps({
    'image': image, 'commit': (root / 'code-commit.txt').read_text().strip(),
    'preservedProcesses': preserved,
    'sha256': {name: sha((root / name).read_bytes()) for name in ('index.html', *assets, *modules)},
}, indent=2))
print('Resume review deployed; asset hashes verified; application/video processes preserved')
