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
    alerts=[]; containers={};verified={"disk","app","deployment"}
    operations=store.incomplete();marker=read(host.marker,{})
    planned=any(op["id"]==marker.get("owner") and op["phase"] in {"switching","verifying","source_sync","restoring","recovering"}
        and time.time()-op["updatedAt"]<300 and time.time()-op["createdAt"]<1200 for op in operations)
    for name in config["permanentContainers"]:
        try:
            info=inspect(name); state=info["State"]
            health=state.get("Health",{}).get("Status","running" if state["Running"] else "stopped")
            containers[name]={"running":state["Running"],"health":health,"oomKilled":state["OOMKilled"],"image":info["Config"]["Image"],"imageId":info["Image"]}
            verified.add(name)
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
        elif backup.get("outcome")=="success":verified.add("backup")
    expiry=None
    try:
        text=command("openssl","x509","-enddate","-noout","-in",config["certificate"],timeout=5).decode().strip().split("=",1)[1]
        expiry=datetime.datetime.strptime(text,"%b %d %H:%M:%S %Y %Z").replace(tzinfo=datetime.timezone.utc).timestamp()
        days=(expiry-time.time())/86400
        verified.add("tls")
        if days<14: alerts.append({"service":"tls","type":"expiry","severity":"critical" if days<7 else "warning"})
    except Exception: alerts.append({"service":"tls","type":"unreadable","severity":"critical"})
    counters={}; backlog=None
    try:
        counters=host.counters()
        backlog=int(host.sql("SELECT COUNT(*) FROM finance_order_cost_jobs"))
        stale=int(host.sql("SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE)"))
        verified.update({"database","finance-sync"})
        if stale: alerts.append({"service":"finance-sync","type":"stale-lease","severity":"warning"})
    except Exception: alerts.append({"service":"database","type":"probe-failed","severity":"critical"})
    for op in operations:
        if op["phase"]=="needs_attention" or time.time()-op["updatedAt"]>1200:
            alerts.append({"service":"deployment","type":"recovery-required","severity":"critical"})
    watchdog=read(store.root/"watchdog.json",{})
    if watchdog.get("state") and watchdog["state"] not in {"failed","credential_missing"}:verified.add("monitoring-workflow")
    if watchdog.get("state") in {"credential_missing","failed"}:
        alerts.append({"service":"monitoring-workflow","type":watchdog["state"],"severity":"warning"})
    if planned:
        suppressed={"app","ai-commerce-app","ai-commerce-frontend"}
        alerts=[a for a in alerts if a["service"] not in suppressed]
        verified-=suppressed
    return {"observedAt":int(time.time()),"version":(store.active() or {}).get("version"),
        "maintenance":host.marker.exists(),"plannedMaintenance":planned,"appHealthy":app_ok,"containers":containers,
        "freeBytes":free,"certificateExpiresAt":expiry,"backup":{k:backup.get(k) for k in ("outcome","finishedAt","lastSuccess")},
        "activeWorkers":counters,"costBacklog":backlog,"alerts":alerts,"verifiedScopes":sorted(verified)}


def monitor(config,store,host):
    try:
        from .state import Store
        with Store(store.root,store.root/".monitor.lock").exclusive():return _monitor(config,store,host)
    except Busy:return read(store.root/"health.json",{"skipped":"monitor_busy"})


def _monitor(config,store,host):
    snapshot=diagnose(config,store,host); state=read(store.root/"monitor-state.json",{})
    if snapshot.get("plannedMaintenance"):
        pass  # Neither a recovery nor a new incident while a bounded switch runs.
    elif snapshot["appHealthy"]:
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
                active=store.active()
                atomic(store.root/"requests"/(operation+".json"),{"id":operation,"version":active["version"],
                    "expectedVersion":active["version"],"rollback":False,"restart":True,"createdAt":int(time.time())})
                try:
                    command("systemctl","start","--no-block","heyi-ops-executor@"+operation+".service")
                    snapshot["selfHealing"]="queued"
                except Exception: snapshot["selfHealing"]="queue_failed"
    atomic(store.root/"monitor-state.json",state);atomic(store.root/"health.json",snapshot)
    return snapshot


def retained_versions(store):
    active=store.active()
    if not active:return []
    pair=lambda release:tuple(release["images"][k]["configDigest"] for k in ("app","frontend"))
    accepted=sorted((o for p in (store.root/"operations").glob("*.json") if (o:=read(p))["phase"]=="succeeded"),key=lambda o:o.get("finishedAt",0),reverse=True)
    keep=[active["version"]];pairs={pair(active)}
    for op in accepted:
        key=pair(op["target"])
        if key not in pairs:keep.append(op["target"]["version"]);pairs.add(key)
        if len(pairs)>=3:break
    return keep


def cleanup(config,store):
    if store.incomplete(): raise Busy("Cleanup blocked by incomplete maintenance")
    keep=set(retained_versions(store));protected_operations=set()
    if not keep:raise Busy("Cleanup requires an accepted active release")
    current_source=(Path(config["sourceRoot"])/"current").resolve()
    if current_source.exists():keep.add(current_source.name)
    operations={p.stem:read(p) for p in (store.root/"operations").glob("*.json")}
    for version in keep:
        matches=[o for o in operations.values() if o["target"]["version"]==version and o["phase"]=="succeeded"]
        if matches:protected_operations.add(max(matches,key=lambda o:o.get("finishedAt",0))["id"])
    for request_file in (store.root/"requests").glob("*.json"):
        if request_file.stem not in operations:keep.add(read(request_file)["version"])
    in_use=set()
    ids=command("docker","ps","-aq").decode().split()
    containers=json.loads(command("docker","inspect",*ids)) if ids else []
    in_use={x["Image"] for x in containers}
    config_files={p for x in containers for p in (x["Config"].get("Labels") or {}).get("com.docker.compose.project.config_files","").split(",") if p}
    mounts=[Path(m["Source"]) for x in containers for m in x["Mounts"]]
    protected=set()
    for version in keep:
        manifest=read(store.root/"releases"/version/"release.json",{})
        protected.update(i["configDigest"] for i in manifest.get("images",{}).values())
    from .artifacts import Artifacts
    candidates=[p for p in (store.root/"releases").iterdir() if p.is_dir() and not p.is_symlink() and p.name not in keep and (p/"verified.json").is_file()]
    atomic(store.root/"cleanup-plan.json",{"createdAt":int(time.time()),"retainedVersions":sorted(keep),
        "candidates":[{"path":str(p),"owner":"heyi-ops verified release","evidence":str(p/"verified.json")} for p in candidates]})
    removed=[];removed_refs=set();skipped=[]
    for directory in (store.root/"releases").iterdir():
        if not directory.is_dir() or directory.is_symlink() or directory.name in keep or not (directory/"verified.json").is_file(): continue
        was_accepted=any(o["phase"]=="succeeded" and o["target"]["version"]==directory.name for o in operations.values())
        if not was_accepted and time.time()-read(directory/"verified.json")["verifiedAt"]<86400:continue
        if any(m==directory or directory in m.parents for m in mounts):skipped.append(directory.name);continue
        if {p.name for p in directory.iterdir()}-{"release.json","verified.json","images.json","source.tar.gz"}:skipped.append(directory.name);continue
        manifest=Artifacts(store.root).load(directory.name)
        for image in manifest["images"].values():
            if image["configDigest"] in protected|in_use: continue
            ref=image["repository"]+"@"+image["manifestDigest"]
            if ref not in removed_refs:
                available=command("docker","image","ls","--quiet","--no-trunc",ref).decode().split()
                if available:
                    if available!=[image["configDigest"]]:raise RuntimeError("Image identity drift during cleanup")
                    command("docker","image","rm",ref)
                removed_refs.add(ref)
        source=Path(config["sourceRoot"])/"releases"/directory.name
        metadata=read(source/".heyi-source.json",{})
        if source.exists() and metadata.get("files") and not any(m==source or source in m.parents for m in mounts):
            import hashlib
            entries=list(source.rglob("*"))
            files={str(p.relative_to(source)):p for p in entries if p.is_file() and str(p.relative_to(source)) not in {".heyi-source.json",".heyi-owned.json"}}
            expected_dirs={str(p) for name in metadata["files"] for p in Path(name).parents if str(p)!="."}
            directories={str(p.relative_to(source)) for p in entries if p.is_dir()}
            if not any(p.is_symlink() for p in entries) and directories==expected_dirs and set(files)==set(metadata["files"]) and all(hashlib.sha256(p.read_bytes()).hexdigest()==metadata["files"][name] for name,p in files.items()):
                shutil.rmtree(source)
            else:skipped.append("source-drift:"+directory.name)
        (directory/"source.tar.gz").unlink(missing_ok=True)
        if any(str(directory)==p or p.startswith(str(directory)+"/") for p in config_files):
            # An unchanged-image docs release retains the container's original
            # Compose label. Keep that tiny live config, never its old source tar.
            continue
        shutil.rmtree(directory);removed.append(directory.name)
    # Only this control plane's operation receipts. No legacy release dirs,
    # source history, business volumes, logs or unrelated Docker images.
    paths=sorted((store.root/"operations").glob("*.json"),key=lambda p:p.stat().st_mtime,reverse=True)
    budget=0
    for path in paths:
        value=read(path);critical=value["id"] in protected_operations
        budget+=path.stat().st_size
        if not critical and (time.time()-path.stat().st_mtime>30*86400 or budget>50*1024*1024):
            path.unlink();(store.root/"requests"/path.name).unlink(missing_ok=True)
    return {"retainedVersions":sorted(keep),"removedManagedVersions":removed,"skipped":skipped}


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
