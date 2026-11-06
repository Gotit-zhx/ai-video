"""Bounded local probes, one safe recovery attempt, retention and cron watchdog."""
import datetime
import json
import os
import shutil
import time
from pathlib import Path
from .host import GIB, command, inspect
from .state import atomic, read, Busy


def diagnose(config, store, host):
    alerts=[]; containers={}
    for name in config["permanentContainers"]:
        try:
            info=inspect(name); state=info["State"]
            health=state.get("Health",{}).get("Status","running" if state["Running"] else "stopped")
            containers[name]={"running":state["Running"],"health":health,"oomKilled":state["OOMKilled"]}
            if health not in {"healthy","running"} or state["OOMKilled"]:
                alerts.append({"service":name,"type":"unhealthy","severity":"critical"})
        except Exception: alerts.append({"service":name,"type":"missing","severity":"critical"})
    try:
        code,body=host.request("/actuator/health")
        app_ok=code==200 and json.loads(body).get("status")=="UP"
    except Exception: app_ok=False
    if not app_ok: alerts.append({"service":"app","type":"availability","severity":"critical"})
    free=shutil.disk_usage(config["root"]).free
    if free<14*GIB: alerts.append({"service":"disk","type":"free-space","severity":"critical" if free<8*GIB else "warning"})
    backup=read(Path(config["backupRoot"])/"status.json",{})
    successful=backup.get("lastSuccess",{})
    if not successful or time.time()-successful.get("finishedAt",0)>36*3600:
        alerts.append({"service":"backup","type":"stale","severity":"critical"})
    if backup.get("outcome") not in {"success",None}:
        alerts.append({"service":"backup","type":backup["outcome"],"severity":"warning" if backup["outcome"]=="lock_busy" else "critical"})
    if successful:
        name=successful.get("archive","")
        file=Path(config["backupRoot"])/name
        if Path(name).name!=name or not file.is_file() or file.stat().st_size!=successful.get("bytes"):
            alerts.append({"service":"backup","type":"archive-missing-or-changed","severity":"critical"})
    expiry=None
    try:
        text=command("openssl","x509","-enddate","-noout","-in",config["certificate"],timeout=5).decode().strip().split("=",1)[1]
        expiry=datetime.datetime.strptime(text,"%b %d %H:%M:%S %Y %Z").replace(tzinfo=datetime.timezone.utc).timestamp()
        days=(expiry-time.time())/86400
        if days<14: alerts.append({"service":"tls","type":"expiry","severity":"critical" if days<7 else "warning"})
    except Exception: alerts.append({"service":"tls","type":"unreadable","severity":"critical"})
    counters={}; backlog=None
    try:
        counters=host.counters()
        backlog=int(host.sql("SELECT COUNT(*) FROM finance_order_cost_jobs"))
        stale=int(host.sql("SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE)"))
        if stale: alerts.append({"service":"finance-sync","type":"stale-lease","severity":"warning"})
    except Exception: alerts.append({"service":"database","type":"probe-failed","severity":"critical"})
    for op in store.incomplete():
        if op["phase"]=="needs_attention" or time.time()-op["updatedAt"]>1200:
            alerts.append({"service":"deployment","type":"recovery-required","severity":"critical"})
    watchdog=read(store.root/"watchdog.json",{})
    if watchdog.get("state") in {"credential_missing","failed"}:
        alerts.append({"service":"monitoring-workflow","type":watchdog["state"],"severity":"warning"})
    return {"observedAt":int(time.time()),"version":(store.active() or {}).get("version"),
        "maintenance":host.marker.exists(),"appHealthy":app_ok,"containers":containers,
        "freeBytes":free,"certificateExpiresAt":expiry,"backup":{k:backup.get(k) for k in ("outcome","finishedAt","lastSuccess")},
        "activeWorkers":counters,"costBacklog":backlog,"alerts":alerts}


def monitor(config,store,host):
    snapshot=diagnose(config,store,host); state=read(store.root/"monitor-state.json",{})
    if snapshot["appHealthy"]:
        state.update(consecutiveFailures=0,attempted=False)
    else:
        state["consecutiveFailures"]=state.get("consecutiveFailures",0)+1
        allowed=(state["consecutiveFailures"]>=3 and not state.get("attempted") and
                 time.time()-state.get("lastAttempt",0)>=1800 and not snapshot["maintenance"] and
                 not store.incomplete() and not snapshot["activeWorkers"] and
                 config.get("selfHealingEnabled",False))
        # A missing worker probe is NOT evidence of safety. Actual restart
        # independently rechecks every counter under the deployment lock.
        if allowed: snapshot["selfHealing"]="blocked_worker_probe"
        elif (state["consecutiveFailures"]>=3 and not state.get("attempted") and
              time.time()-state.get("lastAttempt",0)>=1800 and not snapshot["maintenance"] and
              not store.incomplete() and snapshot["activeWorkers"] and
              not any(snapshot["activeWorkers"].values()) and config.get("selfHealingEnabled",False)):
            dependencies=[a for a in snapshot["alerts"] if a["service"] not in {"app","ai-commerce-app","tls","backup","monitoring-workflow"}]
            if not dependencies:
                import secrets
                operation=secrets.token_hex(16)
                state.update(attempted=True,lastAttempt=int(time.time()),operation=operation)
                atomic(store.root/"monitor-state.json",state)
                try:
                    command("systemd-run","--quiet","--unit=heyi-ops-heal-"+operation,
                            "--property=Type=oneshot","--property=TimeoutStartSec=600",
                            "--property=StandardOutput=null","--property=StandardError=null",
                            "--property=ExecStopPost=/usr/local/sbin/heyi-ops recover",
                            "/usr/local/sbin/heyi-ops","heal",operation)
                    snapshot["selfHealing"]="queued"
                except Exception: snapshot["selfHealing"]="queue_failed"
    atomic(store.root/"monitor-state.json",state);atomic(store.root/"health.json",snapshot)
    return snapshot


def cleanup(config,store):
    if store.incomplete(): raise Busy("Cleanup blocked by incomplete maintenance")
    receipts=[read(p) for p in (store.root/"operations").glob("*.json")]
    accepted=sorted((o for o in receipts if o["phase"]=="succeeded"),key=lambda o:o.get("finishedAt",0),reverse=True)
    keep={(store.active() or {})["version"]}
    for op in accepted:
        keep.add(op["target"]["version"])
        if len(keep)>=3: break
    in_use=set()
    ids=command("docker","ps","-aq").decode().split()
    if ids: in_use={x["Image"] for x in json.loads(command("docker","inspect",*ids))}
    protected=set()
    for version in keep:
        manifest=read(store.root/"releases"/version/"release.json",{})
        protected.update(i["configDigest"] for i in manifest.get("images",{}).values())
    removed=[]
    for directory in (store.root/"releases").iterdir():
        if not directory.is_dir() or directory.name in keep or not (directory/"verified.json").is_file(): continue
        if time.time()-read(directory/"verified.json")["verifiedAt"]<86400:continue
        manifest=read(directory/"release.json",{})
        if not manifest or any(i["configDigest"] in in_use for i in manifest["images"].values()): continue
        for image in manifest["images"].values():
            if image["configDigest"] in protected: continue
            command("docker","image","rm",image["repository"]+"@"+image["manifestDigest"])
        shutil.rmtree(directory);removed.append(directory.name)
    # Only this control plane's operation receipts. No legacy release dirs,
    # source history, business volumes, logs or unrelated Docker images.
    paths=sorted((store.root/"operations").glob("*.json"),key=lambda p:p.stat().st_mtime,reverse=True)
    budget=0
    for path in paths:
        value=read(path);critical=value["target"]["version"] in keep
        budget+=path.stat().st_size
        if not critical and (time.time()-path.stat().st_mtime>30*86400 or budget>50*1024*1024): path.unlink()
    return {"retainedVersions":sorted(keep),"removedManagedVersions":removed}


def watchdog(config):
    root=Path(config["root"]);file=Path("/etc/heyi-ops/monitor-actions-token")
    if not file.is_file():
        result={"state":"credential_missing","checkedAt":int(time.time())};atomic(root/"watchdog.json",result);return result
    env=dict(os.environ,GH_TOKEN=file.read_text().strip(),GH_PROMPT_DISABLED="1")
    path="repos/Gotit-zhx/ai-video/actions/workflows/operations.yml"
    try:
        value=json.loads(command("gh","api",path,env=env,timeout=15));state=value["state"]
        if state=="disabled_inactivity": command("gh","api","--method","PUT",path+"/enable",env=env,timeout=15);state="enabled_after_inactivity"
        result={"state":state,"checkedAt":int(time.time())}
    except Exception:result={"state":"failed","checkedAt":int(time.time())}
    atomic(root/"watchdog.json",result);return result
