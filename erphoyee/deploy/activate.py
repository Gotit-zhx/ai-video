"""Run on the server after the isolated ERP is healthy. No service recreation."""
import hashlib
import json
import subprocess
from datetime import datetime
from pathlib import Path

ROOT=Path('/opt/heyi/erphoyee')
RELEASE=ROOT/'releases/20260908'
STAMP=datetime.now().strftime('%Y%m%d-%H%M%S')
BACKUP=ROOT/'backups'/STAMP
BACKUP.mkdir(parents=True,exist_ok=False)

def run(*args):
    return subprocess.check_output(args,text=True)

before=json.loads(run('docker','inspect','ai-commerce-frontend','ai-commerce-app','ai-commerce-mysql','ai-commerce-redis','heyi-pi-runtime'))
ids={x['Name']: {'id':x['Id'],'started':x['State']['StartedAt']} for x in before}
(BACKUP/'containers-before.json').write_text(json.dumps(ids,indent=2))
health=run('docker','exec','ai-commerce-frontend','wget','-q','-O','-','http://erphoyee:8137/erphoyee/api/health')
assert json.loads(health)['status']=='ok'
conf=run('docker','exec','ai-commerce-frontend','cat','/etc/nginx/conf.d/default.conf')
(BACKUP/'nginx.conf').write_text(conf)
assert '# BEGIN HOYEE ERP' not in conf, 'ERP route already exists; inspect instead of overwriting'
marker='    location /api/ {'
assert conf.count(marker)==1
route=(RELEASE/'deploy/nginx-location.conf').read_text()
updated=conf.replace(marker,route+'\n'+marker,1)
(BACKUP/'nginx-new.conf').write_text(updated)
# Snapshot the main site and static assets without copying business data.
checks=['/','/assets/hoyee-logo-transparent-white.png','/app.js']
baseline={}
for url in checks:
    data=subprocess.check_output(['docker','exec','ai-commerce-frontend','wget','-q','-O','-','http://127.0.0.1'+url])
    baseline[url]=hashlib.sha256(data).hexdigest()
(BACKUP/'baseline.json').write_text(json.dumps(baseline,indent=2))
try:
    run('docker','cp',str(BACKUP/'nginx-new.conf'),'ai-commerce-frontend:/etc/nginx/conf.d/default.conf')
    run('docker','exec','ai-commerce-frontend','nginx','-t')
    run('docker','exec','ai-commerce-frontend','nginx','-s','reload')
except Exception:
    run('docker','cp',str(BACKUP/'nginx.conf'),'ai-commerce-frontend:/etc/nginx/conf.d/default.conf')
    run('docker','exec','ai-commerce-frontend','nginx','-t')
    run('docker','exec','ai-commerce-frontend','nginx','-s','reload')
    raise
# Preserve the exact live container filesystem plus route in a new image. No pause/restart.
new_image='heyi-ai-frontend:erphoyee-route-20260908'
run('docker','commit','--pause=false','ai-commerce-frontend',new_image)
labels=before[0]['Config']['Labels']
files=labels['com.docker.compose.project.config_files'].split(',')
# This is the final active override, verified via Docker Compose labels.
last=Path(files[-1]); old=last.read_text()
old_image=before[0]['Config']['Image']
assert old.count(old_image)==1, 'Unexpected active override; leave runtime working and inspect persistence manually'
(BACKUP/'compose-final-override.before.yaml').write_text(old)
(BACKUP/'override-path.txt').write_text(str(last))
last.write_text(old.replace(old_image,new_image))
(ROOT/'current').symlink_to(RELEASE) if not (ROOT/'current').exists() else None
(ROOT/'last-activation.txt').write_text(str(BACKUP))
# Verify existing app responses and container identities.
for url,expected in baseline.items():
    data=subprocess.check_output(['docker','exec','ai-commerce-frontend','wget','-q','-O','-','http://127.0.0.1'+url])
    assert hashlib.sha256(data).hexdigest()==expected,url+' changed'
after=json.loads(run('docker','inspect',*list(ids)))
assert all(x['Id']==ids[x['Name']]['id'] and x['State']['StartedAt']==ids[x['Name']]['started'] for x in after),'Existing container restarted'
print(json.dumps({'backup':str(BACKUP),'existing_assets_unchanged':list(baseline),'existing_containers_not_restarted':True,'new_frontend_image':new_image},indent=2))
