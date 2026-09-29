"""Publish connection recovery only; preserve every business container and dataset."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

root = Path(sys.argv[1]).resolve()
expected = json.loads((root / 'expected.json').read_text())
manifest = json.loads((root / 'manifest.json').read_text())
front = ['index.html', 'hr-workspace.js', 'hr-desktop-bridge.js']
back = ['desktop_bridge.py', 'connector.py']
bin_dir = Path('/opt/heyi/boss-agent/bin')
rollback = root / 'rollback'
assert not (root / 'activation.json').exists(), 'Already activated'
rollback.mkdir(exist_ok=True)

def run(*args, **kwargs):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, **kwargs)

def inspect(name):
    return json.loads(run('docker', 'inspect', name))[0]

def sha(data):
    return hashlib.sha256(data).hexdigest()

def business():
    return {name: inspect(name)['Id'] for name in
            ['ai-commerce-app', 'heyi-pi-runtime', 'heyi-commerce-image-processor']}

def public(path):
    for attempt in range(15):
        try:
            return run('curl', '--noproxy', '*', '-fsS', '--max-time', '5', '--resolve',
                       'hoyee.org.cn:443:127.0.0.1', 'https://hoyee.org.cn/' + path)
        except subprocess.CalledProcessError:
            if attempt == 14:
                raise
            time.sleep(1)

before = inspect('ai-commerce-frontend')
preserved = business()
assert 'APP_HR_DESKTOP_ONLY=1' in run('systemctl', 'show', 'heyi-boss-connector', '-p', 'Environment', '--value').decode()
assert Path('/opt/heyi/boss-agent/runtime/resume-auto-pause').exists()
for name in front + back:
    staged = (root / 'staged' / name).read_bytes()
    assert sha(staged) == manifest[name], 'Staged mismatch: ' + name
    live = run('docker', 'exec', 'ai-commerce-frontend', 'cat', '/usr/share/nginx/html/' + name) if name in front else (bin_dir / name).read_bytes()
    assert sha(live) == expected[name], 'Production drift: ' + name
    (rollback / name).write_bytes(live)
for name in back:
    run('/opt/heyi/boss-agent/venv/bin/python', '-m', 'py_compile', str(root / 'staged' / name))
image_dir = root / 'frontend-image'
image_dir.mkdir(exist_ok=True)
for name in front:
    shutil.copy2(root / 'staged' / name, image_dir / name)
nginx = run('docker', 'exec', 'ai-commerce-frontend', 'cat', '/etc/nginx/conf.d/default.conf')
(image_dir / 'nginx.conf').write_bytes(nginx)
image = 'ai-commerce-frontend:' + root.name
(image_dir / 'Dockerfile').write_text('FROM ' + before['Image'] + '\nCOPY ' + ' '.join(front)
    + ' /usr/share/nginx/html/\nCOPY nginx.conf /etc/nginx/conf.d/default.conf\n')
run('docker', 'build', '-t', image, str(image_dir))
labels = before['Config']['Labels']
service = labels['com.docker.compose.service']
compose = ['docker', 'compose', '--project-directory', labels['com.docker.compose.project.working_dir'], '-p', labels['com.docker.compose.project']]
for file in labels['com.docker.compose.project.config_files'].split(','):
    compose += ['-f', file]
override = root / 'compose.reconnect.json'
env = {**os.environ, 'HR_FRONTEND_IMAGE': before['Config']['Image']}

def replace_front(tag):
    override.write_text(json.dumps({'services': {service: {'image': tag}}}))
    run(*compose, '-f', str(override), 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', service, env=env)

def replace_back(source):
    for name in back:
        target = bin_dir / name
        metadata = target.stat()
        temporary = target.with_suffix('.py.new')
        shutil.copy2(source / name, temporary)
        os.chown(temporary, metadata.st_uid, metadata.st_gid)
        temporary.chmod(metadata.st_mode & 0o777)
        os.replace(temporary, target)
    run('systemctl', 'restart', 'heyi-boss-connector')
    run('systemctl', 'is-active', '--quiet', 'heyi-boss-connector')

changed = False
try:
    replace_back(root / 'staged')
    changed = True
    replace_front(image)
    for name in front:
        assert sha(public(name)) == manifest[name], 'Public mismatch: ' + name
    for route in ['pool', 'departments', 'demands', 'desktop/transport']:
        status = run('curl', '--noproxy', '*', '-sS', '--max-time', '10', '--resolve',
                     'hoyee.org.cn:443:127.0.0.1', '-o', '/dev/null', '-w', '%{http_code}',
                     'https://hoyee.org.cn/api/v1/hr/' + route).decode()
        assert status in ['401', '403'], 'Unauthenticated request must be rejected: ' + route
    assert business() == preserved
    after = inspect('ai-commerce-frontend')
    mounts = lambda value: sorted((m['Source'], m['Destination'], m['RW']) for m in value['Mounts'])
    assert mounts(after) == mounts(before), 'Mounts changed'
except BaseException:
    replace_back(rollback)
    if changed:
        for name in front:
            shutil.copy2(rollback / name, image_dir / name)
        run('docker', 'build', '-t', image + '-rollback', str(image_dir))
        replace_front(image + '-rollback')
    raise
(root / 'activation.json').write_text(json.dumps({'commit': (root / 'code-commit').read_text().strip(),
    'files': manifest, 'businessContainersUnchanged': preserved, 'frontendImage': inspect('ai-commerce-frontend')['Image']}, indent=2))
print('Connection recovery published; assets verified; business containers unchanged')
