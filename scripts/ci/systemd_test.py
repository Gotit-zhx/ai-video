#!/usr/bin/env python3
"""Real systemd lifecycle test, only on a disposable GitHub hosted runner."""
import json
import os
import secrets
import shutil
import subprocess
import tempfile
import time
from pathlib import Path


def main():
    if os.geteuid()!=0 or os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('RUNNER_ENVIRONMENT')!='github-hosted':
        raise RuntimeError('This fixture is restricted to a disposable GitHub hosted runner')
    repo=Path(__file__).resolve().parents[2];owner='heyi-ci-'+secrets.token_hex(6)
    unit=Path('/etc/systemd/system')/(owner+'@.service')
    request='a'*32;name=owner+'@'+request+'.service'
    def run(*args):return subprocess.check_output(args,stderr=subprocess.STDOUT).decode().strip()
    def wait(path,phase=None):
        for _ in range(200):
            if path.exists():
                value=json.loads(path.read_text())
                if not phase or value.get('phase')==phase:return value
            time.sleep(.1)
        raise RuntimeError('Lifecycle fixture deadline exceeded')
    with tempfile.TemporaryDirectory(prefix=owner,dir='/run') as temporary:
        root=Path(temporary)
        bundle=root/'repo';fixture=bundle/'scripts/ci/systemd_fixture.py';fixture.parent.mkdir(parents=True)
        shutil.copyfile(repo/'scripts/ci/systemd_fixture.py',fixture)
        library=bundle/'ops/heyi-ops/heyi_ops';library.mkdir(parents=True)
        for module in ('__init__.py','engine.py','state.py'):
            shutil.copyfile(repo/'ops/heyi-ops/heyi_ops'/module,library/module)
        old={'version':'v1.0.0','images':{n:{'configDigest':'old'} for n in ['app','frontend']}}
        target={'version':'v1.0.1','images':{n:{'configDigest':'new'} for n in ['app','frontend']}}
        def setup():
            for n in ('active.json','runtime.json'):(root/n).write_text(json.dumps(old))
            (root/'target.json').write_text(json.dumps(target))
        text=(repo/'ops/heyi-ops/systemd/heyi-ops-executor@.service').read_text()
        text=text.replace('/usr/local/sbin/heyi-ops','/usr/bin/python3 '+str(fixture))
        text=text.replace('Type=oneshot','Type=oneshot\nEnvironment=HEYI_FIXTURE_ROOT='+str(root))
        unit.write_text(text)
        try:
            run('systemctl','daemon-reload');setup()
            # The submitting process has exited before acceptance completes.
            run('systemctl','start','--no-block',name)
            receipt=wait(root/'operations'/(request+'.json'),'succeeded')
            assert not (root/'gate.json').exists()
            run('systemctl','stop',name)
            (root/'operations'/(request+'.json')).unlink();setup()
            (root/'interrupt-switch').touch()
            run('systemctl','start','--no-block',name);wait(root/'switch-started.json')
            run('systemctl','kill','--kill-whom=main','--signal=SIGKILL',name)
            wait(root/'operations'/(request+'.json'),'recovered')
            assert not (root/'gate.json').exists()
            assert json.loads((root/'runtime.json').read_text())==old
            output=repo/'target/ci-reports/systemd';output.mkdir(parents=True,exist_ok=True)
            (output/'summary.json').write_text(json.dumps({'result':'passed','submitterExit':True,'executorKilled':True,'execStopPostRecovered':True})+'\n')
            print('systemd: detached submission and killed executor recovery passed')
        finally:
            subprocess.run(['systemctl','stop',name],capture_output=True)
            subprocess.run(['systemctl','reset-failed',name],capture_output=True)
            unit.unlink(missing_ok=True);run('systemctl','daemon-reload')


if __name__=='__main__':main()
