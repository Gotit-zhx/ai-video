"""Exercise legacy ingress/reservations against disposable real containers."""
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'ops/heyi-ops'))
from heyi_ops.host import Host, SERVICES, command, inspect
from heyi_ops.legacy import Legacy


def exercise(dc):
    if os.geteuid()!=0 or os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('RUNNER_ENVIRONMENT')!='github-hosted':
        raise RuntimeError('Legacy network namespace fixture requires an isolated GitHub hosted runner')
    selected={name:dc('ps','-q',name) for name in ('app','frontend')}
    mysql=dc('ps','-q','mysql')
    class FixtureHost(Host):
        def sql(self,query):
            return command('docker','exec','-i',mysql,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$MYSQL_DATABASE"',
                data=query.encode(),timeout=15).decode().strip()
    with tempfile.TemporaryDirectory(prefix='heyi-adoption-fixture-') as temporary,patch.dict(SERVICES,selected,clear=True):
        root=Path(temporary);host=FixtureHost({'root':str(root),'drainTimeoutSeconds':30})
        first=Legacy(host,root/'first','a'*32)
        try:
            first.ingress();first.reserve()
            # All new direct backend and frontend business traffic must receive
            # the existing error envelope. Health remains routed to the old app.
            probe="""import urllib.request,urllib.error,json
for base in ('http://app:8080','http://frontend:80'):
 for path in ('/api/v1/finance/shops','/api/v1/auth/login'):
  try:urllib.request.urlopen(urllib.request.Request(base+path,data=b'{}',headers={'Content-Type':'application/json'}),timeout=5);raise AssertionError('Business request entered')
  except urllib.error.HTTPError as e:assert e.code==503 and e.headers['Retry-After']=='30' and json.load(e)['success'] is False
 assert json.load(urllib.request.urlopen(base+'/actuator/health',timeout=5))['status']=='UP'
"""
            dc('run','--rm','--no-deps','probe','python3','-c',probe)
            first.drain();assert inspect(selected['app'])['State']['Paused']
        finally:
            first.unpause();first.release();first.reopen()
        assert not inspect(selected['app'])['State']['Paused']
        # Fresh instance simulates executor loss after ingress was changed.
        second=Legacy(host,root/'second','b'*32)
        try:second.ingress();second.reserve()
        finally:
            recovered=Legacy(host,root/'second','b'*32)
            recovered.release();recovered.reopen()
        assert host.sql('SELECT COUNT(*) FROM finance_label_worker_lock WHERE lease_token IS NOT NULL')=='0'
        dc('run','--rm','--no-deps','probe')
    print('Legacy adoption: direct ingress, HTTP 503, health, real leases, pause and crash restoration passed')
