"""One-time legacy quiescence with write-ahead ownership and exact restoration.

No schedule/checkpoint, migration or business result is changed. Idle finance
leases are reserved by one UUID, then released only where that UUID remains.
An unknown/expired foreign lease fails closed instead of being stolen.
"""
import json
import os
import signal
import subprocess
import time
import uuid
from pathlib import Path
from .host import ACTIVE, SERVICES, command, inspect, sha
from .state import atomic, read, Busy

LEASES={
    'finance_sync_states':"status<>'RUNNING'",
    'finance_ad_accounts':"status<>'RUNNING'",
    'finance_qianyi_connections':"status<>'SYNCING'",
    'finance_qianyi_accounts':"status<>'SYNCING'",
    'finance_label_worker_lock':'id=1',
}


class Legacy:
    def __init__(self,host,folder,owner):
        self.host=host;self.folder=Path(folder);self.owner=owner
        self.file=self.folder/'legacy-gate.json'
        self.state=read(self.file)
        if self.state and self.state['owner']!=owner:raise Busy('Foreign adoption owner')
        if not self.state:
            self.state={'owner':owner,'lease':str(uuid.uuid4()),'createdAt':int(time.time())}
            atomic(self.file,self.state)
    def save(self,**fields):
        self.state.update(fields);atomic(self.file,self.state)
    def reserve(self):
        # The token is fsynced before the first UPDATE. Original nullable leases
        # are known, so recovery does not depend on a post-write list of rows.
        lease=self.state['lease']
        statements=["UPDATE "+table+" SET lease_token='"+lease+"',lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 MINUTE) WHERE lease_token IS NULL AND lease_until IS NULL AND "+condition for table,condition in LEASES.items()]
        self.host.sql(';'.join(statements))
        # A crash/recovery must not let reservations expire and be overwritten.
        self.host.sql(';'.join("UPDATE "+table+" SET lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 MINUTE) WHERE lease_token='"+lease+"'" for table in LEASES))
    def reservations_complete(self):
        values=self.host.sql(';'.join("SELECT COUNT(*) FROM "+table+" WHERE lease_token IS NULL OR lease_token<>'"+self.state['lease']+"'" for table in LEASES)).splitlines()
        return len(values)==len(LEASES) and not any(map(int,values))
    def release(self):
        self.host.sql(';'.join("UPDATE "+table+" SET lease_token=NULL,lease_until=NULL WHERE lease_token='"+self.state['lease']+"'" for table in LEASES))
        remaining=self.host.sql(';'.join("SELECT COUNT(*) FROM "+table+" WHERE lease_token='"+self.state['lease']+"'" for table in LEASES)).splitlines()
        if len(remaining)!=len(LEASES) or any(map(int,remaining)):raise RuntimeError('Owned reservations remain')
        self.save(reservationsReleased=True)
    def active(self):
        queries=[]
        for table,condition in ACTIVE.items():
            if table in LEASES:condition='('+condition+") AND (lease_token IS NULL OR lease_token<>'"+self.state['lease']+"')"
            queries.append('SELECT COUNT(*) FROM '+table+' WHERE '+condition)
        values=self.host.sql(';'.join(queries)).splitlines()
        if len(values)!=len(ACTIVE):raise RuntimeError('Incomplete legacy activity check')
        return dict(zip(ACTIVE,map(int,values)))
    def namespace(self,*args):
        info=inspect(SERVICES['app'])
        if info['Id']!=self.state['appId']:raise Busy('Legacy container identity changed')
        return command('nsenter','-t',str(info['State']['Pid']),'-n',*args,timeout=15)
    def ingress(self):
        app=inspect(SERVICES['app']);front=inspect(SERVICES['frontend'])
        if any(n.get('GlobalIPv6Address') for n in app['NetworkSettings']['Networks'].values()):
            raise RuntimeError('Legacy IPv6 ingress needs explicit adoption review')
        original=command('docker','exec',SERVICES['frontend'],'cat','/etc/nginx/conf.d/default.conf')
        anchor=b'    server_name hoyee.org.cn _;'
        if original.count(anchor)!=1 or b'heyi_adoption' in original:raise RuntimeError('Unrecognized legacy Nginx configuration')
        self.save(appId=app['Id'],appPid=app['State']['Pid'],frontendId=front['Id'],nginxOriginalSha256=sha(original),ingressIntent=True)
        (self.folder/'nginx-original.conf').write_bytes(original)
        with (self.folder/'nginx-original.conf').open('rb') as f:os.fsync(f.fileno())
        ready=self.folder/'legacy-proxy.json'
        subprocess.Popen(['nsenter','-t',str(app['State']['Pid']),'-n','/usr/bin/python3',str(Path(__file__).with_name('legacy_gate.py')),str(ready)],
            stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        deadline=time.monotonic()+10
        while not ready.exists():
            if time.monotonic()>deadline:raise RuntimeError('Legacy ingress gate did not start')
            time.sleep(.1)
        proxy=read(ready);self.save(proxy=proxy)
        self.namespace('iptables','-t','nat','-I','PREROUTING','1','-p','tcp','--dport','8080','-m','comment','--comment','heyi-adopt-'+self.owner,
                       '-j','REDIRECT','--to-ports',str(proxy['port']))
        # New Nginx workers block business calls; old workers finish their
        # already accepted requests. This includes independent ERP/downloads.
        marker='/tmp/heyi-adoption-'+self.owner
        snippet=('\n    set $heyi_adoption 0;\n    if (-f '+marker+') { set $heyi_adoption 1; }\n'
            '    if ($heyi_adoption_block) { return 503; }\n    error_page 503 = @heyi_adoption;\n'
            '    location @heyi_adoption {\n        default_type application/json;\n        add_header Retry-After "30" always;\n'
            '        add_header Cache-Control "no-store" always;\n'
            '        return 503 \'{"success":false,"data":null,"message":"Maintenance; retry later","timestamp":null}\';\n    }\n')
        modified=b'map "$heyi_adoption:$uri" $heyi_adoption_block { default 0; ~^1:/(api(?:/|$)|erphoyee(?:/|$)|downloads(?:/|$)) 1; }\n'+original.replace(anchor,anchor+snippet.encode())
        self.save(nginxModifiedSha256=sha(modified),nginxMarker=marker)
        command('docker','exec',SERVICES['frontend'],'touch',marker)
        command('docker','exec','-i',SERVICES['frontend'],'sh','-c','cat > /etc/nginx/conf.d/default.conf',data=modified)
        command('docker','exec',SERVICES['frontend'],'nginx','-t')
        command('docker','exec',SERVICES['frontend'],'nginx','-s','reload')
        self.save(ingressClosed=True)
    def idle_connections(self):
        info=inspect(SERVICES['app'])
        if info['Id']!=self.state['appId']:raise Busy('Legacy container changed during drain')
        for name in ('tcp','tcp6'):
            for line in Path('/proc/'+str(info['State']['Pid'])+'/net/'+name).read_text().splitlines()[1:]:
                fields=line.split()
                if fields[1].rsplit(':',1)[1].upper()=='1F90' and fields[3]=='01':return False
        processes=command('docker','top',SERVICES['frontend'],'-eo','args').decode()
        return 'worker process is shutting down' not in processes
    def drain(self):
        deadline=time.monotonic()+self.host.config.get('drainTimeoutSeconds',180);quiet=0
        while time.monotonic()<deadline:
            self.reserve()
            safe=self.reservations_complete() and not any(self.active().values()) and self.idle_connections()
            quiet=quiet+1 if safe else 0
            if quiet>=2:
                self.save(pauseIntent=True)
                command('docker','pause',SERVICES['app'])
                # Freeze closes the check/claim race. Cost transactions are
                # bounded and retained, then allowed graceful completion.
                if not any(self.active().values()) and self.idle_connections():
                    self.save(paused=True);return
                command('docker','unpause',SERVICES['app']);quiet=0
            time.sleep(2)
        raise Busy('Legacy business did not reach a safe point')
    def unpause(self):
        info=inspect(SERVICES['app'])
        if info['Id']==self.state.get('appId') and info['State']['Paused']:command('docker','unpause',SERVICES['app'])
    def reopen(self):
        self.unpause()
        app=inspect(SERVICES['app']);front=inspect(SERVICES['frontend'])
        proxy=self.state.get('proxy') or read(self.folder/'legacy-proxy.json',{})
        if app['Id']==self.state.get('appId') and proxy:
            rule=['-p','tcp','--dport','8080','-m','comment','--comment','heyi-adopt-'+self.owner,'-j','REDIRECT','--to-ports',str(proxy['port'])]
            check=subprocess.run(['nsenter','-t',str(app['State']['Pid']),'-n','iptables','-t','nat','-C','PREROUTING',*rule],capture_output=True)
            if check.returncode==0:self.namespace('iptables','-t','nat','-D','PREROUTING',*rule)
            elif check.returncode!=1:raise RuntimeError('Cannot verify legacy ingress rule removal')
        if front['Id']==self.state.get('frontendId') and self.state.get('nginxModifiedSha256'):
            actual=sha(command('docker','exec',SERVICES['frontend'],'cat','/etc/nginx/conf.d/default.conf'))
            if actual==self.state['nginxModifiedSha256']:
                original=(self.folder/'nginx-original.conf').read_bytes()
                if sha(original)!=self.state['nginxOriginalSha256']:raise RuntimeError('Legacy Nginx backup changed')
                command('docker','exec','-i',SERVICES['frontend'],'sh','-c','cat > /etc/nginx/conf.d/default.conf',data=original)
                command('docker','exec',SERVICES['frontend'],'nginx','-t')
                command('docker','exec',SERVICES['frontend'],'nginx','-s','reload')
            elif actual!=self.state['nginxOriginalSha256']:raise Busy('Foreign Nginx change during adoption')
            command('docker','exec',SERVICES['frontend'],'rm','-f',self.state['nginxMarker'])
        if proxy:
            process=Path('/proc/'+str(proxy['pid'])+'/cmdline')
            if process.exists() and str(self.folder/'legacy-proxy.json').encode() in process.read_bytes():os.kill(proxy['pid'],signal.SIGTERM)
        self.save(ingressRestored=True)
