"""Fixed command surface used by both forced SSH identities and local operators."""
import argparse
import json
import os
import re
import shlex
import sys
from pathlib import Path
from .artifacts import Artifacts
from .engine import Engine
from .host import Host, command, inspect, config_hash
from .state import Store, atomic, read, REQUEST, TERMINAL

CONFIG = Path("/etc/heyi-ops/config.json")


def context():
    config=read(CONFIG)
    if not config: raise RuntimeError("heyi-ops is not installed")
    return config,Store(config["root"],config["lockPath"]),Host(config),Artifacts(config["root"])


def public_operation(op):
    if not op: return None
    return {key:op[key] for key in ("id","phase","createdAt","updatedAt","finishedAt","errorType","switched") if key in op} | {"version":op["target"]["version"]}


def status(store, host):
    current=store.active()
    return {"protocol":1,"version":current["version"] if current else None,
            "maintenance":host.marker.exists(),"operations":[public_operation(o) for o in store.incomplete()]}


def dispatch(args, role="local"):
    allowed={"monitoring":{"status","doctor","receipt"},
             "deploy":{"status","doctor","receipt","stage","deploy","rollback","preflight"},
             "local":{"status","doctor","receipt","stage","deploy","rollback","preflight","recover","cleanup","monitor","execute","watchdog","heal"}}
    if not args or args[0] not in allowed.get(role,set()): raise ValueError("Action is not allowed for this identity")
    action=args[0];config,store,host,artifacts=context()
    if action in {"status","doctor","recover","cleanup","monitor","watchdog"} and len(args)!=1: raise ValueError("Unexpected arguments")
    if action=="status": return status(store,host)
    if action=="doctor":
        from .monitor import diagnose
        return diagnose(config,store,host)
    if action=="receipt":
        if len(args)!=2: raise ValueError("Operation ID required")
        return public_operation(read(store.path(args[1])))
    if action in {"stage","preflight"}:
        if len(args)!=2: raise ValueError("Version required")
        if action=="stage":
            raw=sys.stdin.buffer.read(16385)
            if len(raw)>16384: raise ValueError("Credential input too large")
            payload=json.loads(raw)
            if set(payload)!={"pullToken"}: raise ValueError("Unexpected input fields")
            with store.exclusive(): target=artifacts.prepare(args[1],payload["pullToken"])
            return {"version":target["version"],"prepared":True}
        with store.exclusive(): host.preflight(artifacts.load(args[1]),store.active())
        return {"preflight":"passed","version":args[1]}
    if action in {"deploy","rollback"}:
        if len(args)!=4 or not REQUEST.fullmatch(args[3]): raise ValueError("Expected version, baseline and operation ID")
        target=artifacts.load(args[1]); baseline=artifacts.load(args[2])
        existing=read(store.path(args[3]))
        if existing:
            if existing["target"]["version"]!=args[1] or existing["expectedVersion"]!=args[2]: raise ValueError("Operation ID reused")
            return public_operation(existing)
        if action=="rollback":
            verified=[o for p in (store.root/"operations").glob("*.json") if (o:=read(p))["phase"]=="succeeded"]
            if not any(o["target"]["version"]==target["version"] for o in verified): raise ValueError("Rollback version was not previously accepted")
        with store.exclusive():
            if store.incomplete(): raise RuntimeError("Recover unfinished operation first")
            if store.active()["version"]!=args[2]: raise ValueError("Stale baseline")
            request={"id":args[3],"version":args[1],"expectedVersion":args[2],"rollback":action=="rollback"}
            file=store.root/"requests"/(args[3]+".json")
            prior=read(file)
            if prior and prior!=request: raise ValueError("Operation ID reused")
            atomic(file,request)
            # systemd owns execution independently of this SSH session/runner.
            command("systemctl","start","--no-block","heyi-ops-executor@"+args[3]+".service")
        return {"id":args[3],"version":args[1],"phase":"queued"}
    if action=="execute":
        if len(args)!=2 or not REQUEST.fullmatch(args[1]): raise ValueError("Invalid operation ID")
        request=read(store.root/"requests"/(args[1]+".json"))
        result=Engine(store,host).deploy(request["id"],artifacts.load(request["version"]),request["expectedVersion"],request["rollback"])
        return public_operation(result)
    if action=="recover": return [public_operation(o) for o in Engine(store,host).recover()]
    if action=="heal":
        if len(args)!=2 or not REQUEST.fullmatch(args[1]):raise ValueError("Invalid recovery ID")
        state=read(store.root/"monitor-state.json",{})
        if not config.get("selfHealingEnabled") or state.get("operation")!=args[1] or not state.get("attempted"):
            raise ValueError("No bounded recovery reservation")
        target=artifacts.load(store.active()["version"])
        return public_operation(Engine(store,host).deploy(args[1],target,target["version"],restart=True))
    if action=="cleanup":
        from .monitor import cleanup
        with store.exclusive(): return cleanup(config,store)
    if action=="monitor":
        from .monitor import monitor
        return monitor(config,store,host)
    if action=="watchdog":
        from .monitor import watchdog
        return watchdog(config)


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
