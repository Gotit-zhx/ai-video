"""Isolate the bundled capture plugin from the desktop downloads mount.

Run on the server with a release directory containing the reviewed plugin ZIP.
Only the frontend is replaced. The live HTML/JS/CSS are preserved byte for byte.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import zipfile

root=Path(sys.argv[1]).resolve()
name='hoyee-assistant-0.2.4.zip'
archive=root/name
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    assert json.loads(z.read('hoyee-assistant/manifest.json'))['version']=='0.2.4'
    assert all(not f.startswith('/') and '..' not in f.split('/') for f in z.namelist())
digest=hashlib.sha256(archive.read_bytes()).hexdigest()

def run(*args,**kwargs):
    return subprocess.check_output(args,stderr=subprocess.STDOUT,**kwargs)

def inspect(name):
    return json.loads(run('docker','inspect',name))[0]

front=inspect('ai-commerce-frontend')
os.environ.setdefault('HR_FRONTEND_IMAGE',front['Config']['Image'])
preserved={name:inspect(name)['Id'] for name in ('ai-commerce-app','heyi-pi-runtime','heyi-commerce-image-processor')}
conf=run('docker','exec','ai-commerce-frontend','cat','/etc/nginx/conf.d/default.conf').decode()
assert 'location = /downloads/'+name not in conf, 'Repair already present; review instead of duplicating'
block='''    location = /downloads/hoyee-assistant-0.2.4.zip {
        alias /usr/share/nginx/html/plugin-downloads/hoyee-assistant-0.2.4.zip;
        default_type application/zip;
        add_header Content-Disposition 'attachment; filename="hoyee-assistant-0.2.4.zip"';
        add_header Cache-Control "no-store" always;
        add_header X-Content-Type-Options "nosniff" always;
    }

'''
assert conf.count('    location ^~ /downloads/ {')==1
(root/'nginx-before.conf').write_text(conf)
(root/'nginx.conf').write_text(conf.replace('    location ^~ /downloads/ {',block+'    location ^~ /downloads/ {'))
# A previous hot fix might exist only in the live writable layer. Capture it,
# excluding the large mounted installer files, before constructing the image.
run('docker','exec','ai-commerce-frontend','tar','-czf','/tmp/plugin-repair-html.tgz','--exclude=./downloads','-C','/usr/share/nginx/html','.')
run('docker','cp','ai-commerce-frontend:/tmp/plugin-repair-html.tgz',str(root/'html.tgz'))
run('docker','exec','ai-commerce-frontend','rm','/tmp/plugin-repair-html.tgz')
before_html=run('docker','exec','ai-commerce-frontend','sh','-c',
               'find /usr/share/nginx/html -path /usr/share/nginx/html/downloads -prune -o -type f -exec sha256sum {} \\;')
(root/'html-before.sha256').write_bytes(before_html)
image='ai-commerce-frontend:'+root.name
(root/'Dockerfile').write_text('FROM '+front['Image']+'\nADD html.tgz /usr/share/nginx/html/\nCOPY nginx.conf /etc/nginx/conf.d/default.conf\nCOPY '+name+' /usr/share/nginx/html/plugin-downloads/'+name+'\n')
run('docker','build','-t',image,str(root))
network=next(iter(front['NetworkSettings']['Networks']))
run('docker','run','--rm','--network',network,'--entrypoint','nginx',
    '-v','/etc/letsencrypt:/etc/letsencrypt:ro','-v','/opt/heyi/shared/certbot-webroot:/var/www/certbot:ro',image,'-t')
labels=front['Config']['Labels']
command=['docker','compose','--project-directory',labels['com.docker.compose.project.working_dir'],'-p',labels['com.docker.compose.project']]
for file in labels['com.docker.compose.project.config_files'].split(','):command+=['-f',file]
override=root/'compose.plugin.json'
service=labels['com.docker.compose.service']
override.write_text(json.dumps({'services':{service:{'image':image}}}))
run(*command,'-f',str(override),'config','-q')
try:
    run(*command,'-f',str(override),'up','-d','--no-deps','--no-build','--pull','never',service)
    run('docker','exec','ai-commerce-frontend','nginx','-t')
    # All previously served non-download assets must still match exactly.
    run('docker','exec','-i','ai-commerce-frontend','sha256sum','-c','-',input=before_html)
    for attempt in range(15):
        try:
            raw=run('curl','--noproxy','*','-fsS','--max-time','5','--resolve','hoyee.org.cn:443:127.0.0.1','https://hoyee.org.cn/downloads/'+name)
            assert hashlib.sha256(raw).hexdigest()==digest
            break
        except subprocess.CalledProcessError:
            if attempt==14:raise
            time.sleep(1)
    for filename in ('Heyi-Recruiter-mac-arm64.dmg','Heyi-Recruiter-windows-x64-Setup.exe'):
        run('curl','--noproxy','*','-fsSI','--max-time','5','--resolve','hoyee.org.cn:443:127.0.0.1','https://hoyee.org.cn/downloads/'+filename)
    assert preserved=={name:inspect(name)['Id'] for name in preserved}
except BaseException:
    (root/'nginx.conf').write_text(conf)
    rollback=image+'-rollback'
    run('docker','build','-t',rollback,str(root))
    override.write_text(json.dumps({'services':{service:{'image':rollback}}}))
    run(*command,'-f',str(override),'up','-d','--no-deps','--no-build','--pull','never',service)
    raise
(root/'activation.json').write_text(json.dumps({'plugin':name,'sha256':digest,'size':len(raw),'image':image,'preservedContainers':preserved},indent=2))
print('Plugin route isolated and verified; desktop downloads and application workers preserved.')
