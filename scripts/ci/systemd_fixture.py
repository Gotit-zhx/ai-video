#!/usr/bin/env python3
"""Disposable runner fixture: exercise the real unit and durable state machine."""
import os
import sys
import time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'ops/heyi-ops'))
from heyi_ops.state import Store, atomic, read
from heyi_ops.engine import Engine


class Host:
    def __init__(self,root):self.root=root;self.marker=root/'gate.json'
    def preflight(self,*args,**kw):pass
    def compatible(self,*args):pass
    def gate(self,owner):atomic(self.marker,{'owner':owner})
    def ungate(self,owner):
        if self.marker.exists() and read(self.marker)['owner']!=owner:raise RuntimeError('Foreign gate')
        self.marker.unlink(missing_ok=True)
    def owns_gate(self,owner):return read(self.marker,{}).get('owner')==owner
    def drain(self,owner):pass
    def activate(self,target,changed):
        atomic(self.root/'runtime.json',target)
        if (self.root/'interrupt-switch').exists() and target['version']=='v1.0.1':
            atomic(self.root/'switch-started.json',{'pid':os.getpid()})
            while True:time.sleep(1)
    def verify(self,target,maintenance=False):
        assert read(self.root/'runtime.json')==target
        assert self.marker.exists()==maintenance
    def sync_source(self,target):atomic(self.root/'source.json',{'version':target['version']})


def main(action,request):
    root=Path(os.environ['HEYI_FIXTURE_ROOT']);store=Store(root,root/'lock');host=Host(root);engine=Engine(store,host)
    if action=='execute':
        target=read(root/'target.json');engine.deploy(request,target,'v1.0.0')
    elif action=='finalize':engine.recover()
    else:raise ValueError('Unexpected fixture command')


if __name__=='__main__':main(*sys.argv[1:])
