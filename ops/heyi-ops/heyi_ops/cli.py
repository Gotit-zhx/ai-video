"""Fixed command surface used by both forced SSH identities and local operators."""
import argparse
import json
import os
import re
import shlex
import sys
import time
from pathlib import Path
from .artifacts import Artifacts
from .engine import Engine
from .host import Host, command, inspect, config_hash
from .state import Store, atomic, read, REQUEST, TERMINAL

CONFIG = Path("/etc/heyi-ops/config.json")


def context():
    config=read(CONFIG)
    if not config: raise RuntimeError("heyi-ops is not installed")
    return config,Store(config["root"],config["lockPath"],config.get("compatibilityLockPath")),Host(config),Artifacts(config["root"])


def public_operation(op):
    if not op: return None
    return {key:op[key] for key in ("id","phase","createdAt","updatedAt","finishedAt","errorType","switched","restart","adoption","recovered") if key in op} | {"version":op["target"]["version"]}


def reject_unstarted(store,request,reason):
    existing=read(store.path(request["id"]))
    if existing:return existing
    return store.save({"id":request["id"],"target":{"version":request["version"]},"baseline":store.active(),
        "expectedVersion":request["expectedVersion"],"createdAt":request.get("createdAt",int(time.time())),
        "restart":request.get("restart",False)},"aborted",errorType=reason,finishedAt=int(time.time()))


def status(store, host):
    current=store.active()
    return {"protocol":1,"version":current["version"] if current else None,
            "maintenance":host.marker.exists(),"operations":[public_operation(o) for o in store.incomplete()]}


def recover_operations(store,host):
    from .adoption import Adoption
    results=[Adoption(store,host).recover(op) for op in store.incomplete() if op.get('adoption')]
    return results+Engine(store,host).recover()


def dispatch(args, role="local"):
    allowed={"monitoring":{"status","doctor","receipt"},
             "deploy":{"status","doctor","receipt","stage","deploy","rollback","preflight"},
             "local":{"status","doctor","receipt","stage","deploy","rollback","preflight","recover","cleanup","monitor","execute","watchdog","finalize","adopt","tls-reload"}}
    if not args or args[0] not in allowed.get(role,set()): raise ValueError("Action is not allowed for this identity")
    action=args[0];config,store,host,artifacts=context()
    if action in {"status","doctor","recover","cleanup","monitor","watchdog","tls-reload"} and len(args)!=1: raise ValueError("Unexpected arguments")
    if action=="status": return status(store,host)
    if action=="doctor":
        from .monitor import diagnose
        return diagnose(config,store,host)
    if action=="receipt":
        if len(args)!=2: raise ValueError("Operation ID required")
        value=read(store.path(args[1]));request=read(store.root/"requests"/(args[1]+".json"))
        return public_operation(value) if value else ({"id":request["id"],"version":request["version"],"phase":"queued","createdAt":request.get("createdAt")} if request else None)
    if action in {"stage","preflight"}:
        if len(args)!=2: raise ValueError("Version required")
        if action=="stage":
            raw=sys.stdin.buffer.read(16385)
            if len(raw)>16384: raise ValueError("Credential input too large")
            payload=json.loads(raw)
            if set(payload)!={"pullToken"}: raise ValueError("Unexpected input fields")
            with store.exclusive():
                if store.incomplete() or host.marker.exists():raise RuntimeError("Recover maintenance before preparing another release")
                target=artifacts.prepare(args[1],payload["pullToken"])
            return {"version":target["version"],"prepared":True}
        with store.exclusive(): host.preflight(artifacts.load(args[1]),store.active())
        return {"preflight":"passed","version":args[1]}
    if action=='adopt':
        if len(args)!=4 or not REQUEST.fullmatch(args[3]):raise ValueError('Expected target, legacy version and operation ID')
        target=artifacts.load(args[1])
        if read(store.root/'legacy-source.json')['version']!=args[2]:raise ValueError('Legacy source version differs')
        with store.exclusive():
            if store.active() or store.incomplete():raise RuntimeError('Adoption already active or requires recovery')
            request={'id':args[3],'version':args[1],'expectedVersion':args[2],'adoption':True,'rollback':False,'restart':False}
            file=store.root/'requests'/(args[3]+'.json');prior=read(file)
            if prior and any(prior.get(k)!=v for k,v in request.items()):raise ValueError('Operation ID reused')
            if not prior:atomic(file,{**request,'createdAt':int(time.time())})
            command('systemctl','enable','heyi-ops-recover.service')
            command('systemctl','start','--no-block','heyi-ops-executor@'+args[3]+'.service')
        return {'id':args[3],'version':args[1],'phase':'queued'}
    if action in {"deploy","rollback"}:
        if len(args)!=4 or not REQUEST.fullmatch(args[3]): raise ValueError("Expected version, baseline and operation ID")
        target=artifacts.load(args[1]); baseline=artifacts.load(args[2])
        existing=read(store.path(args[3]))
        if existing:
            if existing["target"]["version"]!=args[1] or existing["expectedVersion"]!=args[2] or existing.get("rollback",False)!=(action=="rollback") or existing.get("restart",False): raise ValueError("Operation ID reused")
            return public_operation(existing)
        if action=="rollback":
            from .monitor import retained_versions
            if target["version"] not in retained_versions(store): raise ValueError("Version is not in the retained verified rollback set")
        with store.exclusive():
            if store.incomplete(): raise RuntimeError("Recover unfinished operation first")
            if store.active()["version"]!=args[2]: raise ValueError("Stale baseline")
            request={"id":args[3],"version":args[1],"expectedVersion":args[2],"rollback":action=="rollback","restart":False}
            file=store.root/"requests"/(args[3]+".json")
            prior=read(file)
            if prior and any(prior.get(k)!=v for k,v in request.items()): raise ValueError("Operation ID reused")
            if not prior:atomic(file,{**request,"createdAt":int(time.time())})
            # systemd owns execution independently of this SSH session/runner.
            command("systemctl","start","--no-block","heyi-ops-executor@"+args[3]+".service")
        return {"id":args[3],"version":args[1],"phase":"queued"}
    if action=="execute":
        if len(args)!=2 or not REQUEST.fullmatch(args[1]): raise ValueError("Invalid operation ID")
        request=read(store.root/"requests"/(args[1]+".json"))
        if not request:raise ValueError("Missing durable request")
        try:
            if request.get('adoption'):
                from .adoption import Adoption
                return public_operation(Adoption(store,host).run(request['id'],artifacts.load(request['version']),request['expectedVersion']))
            if request.get("restart"):
                state=read(store.root/"monitor-state.json",{})
                if not config.get("selfHealingEnabled") or state.get("operation")!=request["id"] or not state.get("attempted"):
                    raise ValueError("No bounded recovery reservation")
                try:
                    code,body=host.request("/actuator/health")
                    healthy=code==200 and json.loads(body).get("status")=="UP"
                except (OSError,ValueError):healthy=False
                if healthy:
                    return public_operation(reject_unstarted(store,request,"AlreadyHealthy"))
            result=Engine(store,host).deploy(request["id"],artifacts.load(request["version"]),request["expectedVersion"],request["rollback"],request.get("restart",False))
        except Exception as error:
            result=reject_unstarted(store,request,type(error).__name__)
        return public_operation(result)
    if action=="finalize":
        if len(args)!=2 or not REQUEST.fullmatch(args[1]):raise ValueError("Invalid operation ID")
        operation=read(store.path(args[1]))
        if operation and operation["phase"] not in TERMINAL:recover_operations(store,host)
        request=read(store.root/"requests"/(args[1]+".json"))
        if request and not read(store.path(args[1])):reject_unstarted(store,request,"ExecutorInterruptedBeforeJournal")
        return {"finalized":args[1],"receipt":public_operation(read(store.path(args[1])))}
    if action=="recover":
        results=[public_operation(o) for o in recover_operations(store,host)]
        with store.exclusive():
            for file in (store.root/"requests").glob("*.json"):
                if read(store.path(file.stem)):continue
                state=command("systemctl","show","--property=ActiveState","--value","heyi-ops-executor@"+file.stem+".service").decode().strip()
                if state not in {"active","activating","deactivating","reloading"}:
                    results.append(public_operation(reject_unstarted(store,read(file),"ExecutorInterruptedBeforeJournal")))
        return results
    if action=="cleanup":
        from .monitor import cleanup
        with store.exclusive(): return cleanup(config,store)
    if action=="monitor":
        from .monitor import monitor
        return monitor(config,store,host)
    if action=="watchdog":
        from .monitor import watchdog
        return watchdog(config)
    if action=='tls-reload':
        from .tls import reload
        return reload(config,store)


def main(argv=None,role="local"):
    os.umask(0o077)
    try:
        result=dispatch(sys.argv[1:] if argv is None else argv,role)
        print(json.dumps(result,ensure_ascii=False,separators=(",",":")))
        if isinstance(result,dict) and result.get("phase") in {"aborted","rolled_back","needs_attention"}: return 1
        return 0
    except Exception as error:
        # Never print traceback or subprocess output from a privileged command.
        print(json.dumps({"error":type(error).__name__,"message":"Operation rejected or failed; consult heyi-ops doctor"}))
        return 1


def gateway(role,original):
    if role not in {"deploy","monitoring"} or len(original)>300 or any(c in original for c in "\r\n\0"):
        return 2
    return main(shlex.split(original),role)
