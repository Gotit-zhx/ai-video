#!/usr/bin/env python3
"""Root-only adoption tooling. Capture is read-only apart from private new files.

Installation does not activate containers, timers or a release. The initial
application transition must be separately verified under the shared lock.
"""
import argparse
import copy
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
from pathlib import Path
from heyi_ops.host import command, inspect, configuration, config_hash, SERVICES
from heyi_ops.state import Store, atomic, read


def dollar_escape(value):
    if isinstance(value,str):return value.replace("$","$$")
    if isinstance(value,list):return [dollar_escape(v) for v in value]
    if isinstance(value,dict):return {k:dollar_escape(v) for k,v in value.items()}
    return value


def capture(destination):
    if destination.exists():raise ValueError("Adoption capture must use a new private directory")
    destination.mkdir(parents=True,mode=0o700)
    before={service:inspect(name) for service,name in SERVICES.items()}
    project=before["app"]["Config"]["Labels"]["com.docker.compose.project"]
    base={"services":{},"networks":{},"volumes":{}};keys={};chains={}
    for service,info in before.items():
        labels=info["Config"]["Labels"]
        if labels["com.docker.compose.project"]!=project:raise ValueError("Different Compose projects require explicit adoption")
        files=labels["com.docker.compose.project.config_files"].split(",")
        if not all(Path(p).is_file() for p in files):raise ValueError("Missing live Compose dependency")
        cmd=["docker","compose","--project-directory",labels["com.docker.compose.project.working_dir"],"-p",project]
        for file in files:cmd.extend(["-f",file])
        env=dict(os.environ,HR_FRONTEND_IMAGE=before["frontend"]["Config"]["Image"])
        resolved=json.loads(command(*cmd,"config","--format","json",env=env,timeout=120))
        spec=copy.deepcopy(resolved["services"][labels["com.docker.compose.service"]])
        atomic(destination/(service+"-compose-private.json"),{"services":{service:spec},
            "networks":resolved.get("networks",{}),"volumes":resolved.get("volumes",{})})
        actual=dict(v.split("=",1) for v in info["Config"].get("Env") or [])
        defaults=dict(v.split("=",1) for v in inspect(info["Image"])["Config"].get("Env") or [])
        keys[service]=sorted(set(spec.get("environment",{}))|{k for k,v in actual.items() if defaults.get(k)!=v})
        spec["environment"]={k:actual[k] for k in keys[service]}
        for key in ["build","depends_on","env_file","profiles","pull_policy","develop","extends","labels"]:spec.pop(key,None)
        spec["image"]=info["Image"];spec["container_name"]=info["Name"].lstrip("/")
        spec["ports"]=[]
        for target,bindings in (info["HostConfig"]["PortBindings"] or {}).items():
            port,protocol=target.split("/")
            for binding in bindings or []:
                spec["ports"].append({"target":int(port),"published":binding["HostPort"],"protocol":protocol,
                                      **({"host_ip":binding["HostIp"]} if binding["HostIp"] else {})})
        spec["volumes"]=[]
        for mount in info["Mounts"]:
            if mount["Type"] not in {"bind","volume"}:raise ValueError("Unsupported mount type")
            entry={"type":mount["Type"],"target":mount["Destination"],"read_only":not mount["RW"]}
            if mount["Type"]=="bind":
                entry["source"]=mount["Source"]
                entry["bind"]={"create_host_path":False,"propagation":mount["Propagation"]}
            else:
                key="volume"+str(len(base["volumes"]))
                base["volumes"][key]={"external":True,"name":mount["Name"]};entry["source"]=key
            spec["volumes"].append(entry)
        spec["networks"]={}
        for network,settings in info["NetworkSettings"]["Networks"].items():
            key=next((k for k,v in base["networks"].items() if v["name"]==network),None)
            if key is None:key="network"+str(len(base["networks"]));base["networks"][key]={"external":True,"name":network}
            aliases=[v for v in settings.get("Aliases") or [] if v not in {info["Id"],info["Id"][:12]}]
            spec["networks"][key]={"aliases":aliases}
        host=info["HostConfig"]
        spec["logging"]={"driver":host["LogConfig"]["Type"],"options":host["LogConfig"]["Config"] or {}}
        # Memory/CPU limits are authoritative in inspect, not an old override.
        for field,target in [("Memory","mem_limit"),("MemorySwap","memswap_limit"),("PidsLimit","pids_limit"),("ShmSize","shm_size")]:
            if host.get(field) is not None:spec[target]=host[field]
        if host.get("NanoCpus"):spec["cpus"]=host["NanoCpus"]/1e9
        spec["restart"]=host["RestartPolicy"]["Name"] or "no"
        base["services"][service]=spec;chains[service]=files
    if any(inspect(SERVICES[k])["Id"]!=info["Id"] for k,info in before.items()):raise RuntimeError("Runtime changed during capture")
    atomic(destination/"base.json",dollar_escape(base))
    atomic(destination/"before-private.json",before)
    atomic(destination/"capture.json",{"composeProject":project,"environmentKeys":keys,
        "configurationHashes":{k:config_hash(v,keys[k]) for k,v in before.items()},
        "containerIds":{k:v["Id"] for k,v in before.items()},"composeChains":chains})
    return {"captured":True,"services":list(SERVICES),"composeReferences":{k:len(v) for k,v in chains.items()}}


def install(source,capture_dir,revision):
    if not re.fullmatch(r"[a-f0-9]{40}",revision):raise ValueError("Source revision required")
    evidence=read(capture_dir/"capture.json")
    if not evidence:raise ValueError("A verified capture is required")
    for service,expected in evidence["containerIds"].items():
        if inspect(SERVICES[service])["Id"]!=expected:raise ValueError("Recapture the current runtime before installing")
    root=Path("/opt/heyi/ops");config_dir=Path("/etc/heyi-ops")
    if (config_dir/"config.json").exists():raise ValueError("Existing control plane requires the explicit upgrade procedure")
    root.mkdir(mode=0o700,exist_ok=True);config_dir.mkdir(mode=0o700,exist_ok=True)
    version=Path("/usr/local/lib/heyi-ops/versions")/revision
    if version.exists():raise ValueError("Control plane revision already installed")
    version.mkdir(parents=True,mode=0o755)
    shutil.copytree(source/"ops/heyi-ops/heyi_ops",version/"heyi_ops",ignore=shutil.ignore_patterns("__pycache__","*.pyc"))
    shutil.copyfile(source/"scripts/ci/release.py",version/"heyi_ops/release_contract.py")
    library=version.parent.parent
    library.chmod(0o755);version.parent.chmod(0o755)
    link=library/".next";link.symlink_to(version);link.replace(library/"current")
    for name in ("gateway","ssh-deploy","ssh-monitoring"):
        shutil.copyfile(source/"ops/heyi-ops/bin"/name,library/name);(library/name).chmod(0o755)
    shutil.copyfile(source/"ops/heyi-ops/bin/heyi-ops","/usr/local/sbin/heyi-ops");Path("/usr/local/sbin/heyi-ops").chmod(0o755)
    compose=root/"compose";compose.mkdir(exist_ok=True)
    shutil.copyfile(capture_dir/"base.json",compose/"base.json")
    control=root/"control";control.mkdir(exist_ok=True);control.chmod(0o755)
    token=control/"status-token"
    if not token.exists():token.write_text(secrets.token_hex(32));os.chown(token,10001,10001);token.chmod(0o400)
    atomic(compose/"production.json",{"services":{name:{"volumes":[{"type":"bind","source":str(control),"target":"/run/heyi-ops","read_only":True,"bind":{"create_host_path":False}}]} for name in SERVICES}})
    configuration={"root":str(root),"lockPath":"/opt/heyi/operations/.project-release.lock","composeProject":evidence["composeProject"],
        "sourceRoot":"/opt/heyi/source","backendUrl":"http://127.0.0.1:8080","domain":"hoyee.org.cn",
        "backupRoot":"/opt/heyi/backups/mysql","certificate":"/etc/letsencrypt/live/hoyee.org.cn/fullchain.pem",
        "permanentContainers":command("docker","ps","--format","{{.Names}}").decode().splitlines(),
        "drainTimeoutSeconds":180,"selfHealingEnabled":False,"controlPlaneRevision":revision}
    atomic(config_dir/"config.json",configuration)
    atomic(root/"adoption.json",evidence)
    for unit in (source/"ops/heyi-ops/systemd").iterdir():
        shutil.copyfile(unit,Path("/etc/systemd/system")/unit.name)
    command("systemctl","daemon-reload")
    return {"installed":True,"revision":revision,"timersEnabled":False,"containersChanged":False}


if __name__=="__main__":
    os.umask(0o077)
    if os.geteuid()!=0:raise SystemExit("Root is required for private adoption files")
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest="action",required=True)
    q=sub.add_parser("capture");q.add_argument("destination",type=Path)
    q=sub.add_parser("install");q.add_argument("source",type=Path);q.add_argument("capture",type=Path);q.add_argument("revision")
    a=p.parse_args()
    if a.action=="capture":result=capture(a.destination)
    else:
        with Store("/opt/heyi/ops","/opt/heyi/operations/.project-release.lock").exclusive():
            result=install(a.source,a.capture,a.revision)
    print(json.dumps(result))
