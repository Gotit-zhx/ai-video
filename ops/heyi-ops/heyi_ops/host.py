"""Linux/Docker adapter. No caller-supplied commands, SQL, URLs or file paths."""
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from .state import atomic, read, Busy

GIB = 1024 ** 3
SERVICES = {"app": "ai-commerce-app", "frontend": "ai-commerce-frontend"}
# Conservative business safe points. Cost backlog is durable and deliberately
# absent; its active transaction is counted by MaintenanceGate.activeWorkers.
ACTIVE = {
    "finance_sync_states": "status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)",
    "finance_ad_accounts": "status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)",
    "finance_qianyi_connections": "lease_until>UTC_TIMESTAMP(6)",
    "finance_qianyi_accounts": "lease_until>UTC_TIMESTAMP(6)",
    "finance_label_worker_lock": "lease_until>UTC_TIMESTAMP(6)",
    "finance_authorization_sessions": "status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)",
    "product_main_image_template_images": "status IN ('PENDING','QUEUED','ANALYZING')",
    "product_publish_workflows": "status IN ('PENDING','QUEUED','RUNNING','PUBLISHING','PROCESSING_IMAGES')",
    "product_image_processing_jobs": "status IN ('PENDING','QUEUED','RUNNING','PROCESSING')",
    "product_watermark_queue": "status IN ('QUEUED','RUNNING')",
    "commerce_batch_executions": "status IN ('QUEUED','RUNNING','PAUSING')",
    "ai_video_task_items": "status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING')",
    "video_scripts": "status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
    "ai_video_generation_jobs": "status IN ('SUBMITTING','REVIEWING') OR (status IN ('QUEUED','RUNNING') AND (provider_task_id IS NULL OR provider_task_id='' OR next_poll_at>DATE_ADD(UTC_TIMESTAMP(), INTERVAL 120 SECOND)))",
}


def command(*args, data=None, timeout=120, env=None):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout, env=env)
    if result.returncode: raise RuntimeError("Command failed: " + Path(args[0]).name)
    return result.stdout


def sha(value): return hashlib.sha256(value).hexdigest()


def inspect(name): return json.loads(command("docker", "inspect", name))[0]


def image_config_digest(info):
    """Classic Docker reports config IDs; containerd may report manifest IDs."""
    descriptor=info.get('Descriptor')
    if not descriptor:return info['Id']
    for depth in range(2):
        value=descriptor['digest']
        if not re.fullmatch(r'sha256:[a-f0-9]{64}',value) or descriptor.get('size',0)>1024**2:
            raise ValueError('Invalid local image descriptor')
        raw=command('ctr','-n','moby','content','get',value,timeout=15)
        if len(raw)>1024**2 or 'sha256:'+sha(raw)!=value:raise ValueError('Local image manifest checksum differs')
        manifest=json.loads(raw)
        if 'config' in manifest:
            result=manifest['config']['digest']
            if not re.fullmatch(r'sha256:[a-f0-9]{64}',result):raise ValueError('Invalid local config digest')
            return result
        matches=[d for d in manifest.get('manifests',[]) if d.get('platform',{}).get('architecture')==info['Architecture'] and d.get('platform',{}).get('os')==info['Os']]
        if len(matches)!=1:raise ValueError('Ambiguous local platform')
        descriptor=matches[0]
    raise ValueError('Unsupported local manifest nesting')


def configuration(info, env_keys=None):
    host = info["HostConfig"]; config = info["Config"]
    environment=dict(v.split("=",1) for v in config.get("Env") or [])
    if env_keys is not None:
        defaults=dict(v.split("=",1) for v in inspect(info["Image"])["Config"].get("Env") or [])
        tracked=set(env_keys)|{k for k,v in environment.items() if defaults.get(k)!=v}
        environment={k:v for k,v in environment.items() if k in tracked}
    return {
        "env": environment, "entrypoint": config.get("Entrypoint"),
        "command": config.get("Cmd"), "user": config.get("User"), "workingDir": config.get("WorkingDir"),
        "mounts": sorted(({k:m.get(k) for k in ("Type","Source","Destination","RW","Propagation")} for m in info["Mounts"]), key=lambda m:m["Destination"]),
        "networks": {name:sorted({a for a in net.get("Aliases") or [] if a not in {info["Id"],info["Id"][:12]}}) for name,net in info["NetworkSettings"]["Networks"].items()},
        "resources": {k:host.get(k) for k in ("PortBindings","Memory","MemoryReservation","MemorySwap","MemorySwappiness","NanoCpus","CpuShares","CpuPeriod","CpuQuota","CpusetCpus","CpusetMems","PidsLimit","CapAdd","CapDrop","SecurityOpt","ReadonlyRootfs","Ulimits","RestartPolicy","LogConfig","ExtraHosts","Dns","DnsOptions","DnsSearch","ShmSize","Init","OomKillDisable","OomScoreAdj","CgroupnsMode","Privileged","Devices","DeviceRequests","ReadonlyPaths","MaskedPaths","GroupAdd","Sysctls","IpcMode","PidMode")},
    }


def config_hash(info, env_keys=None): return sha(json.dumps(configuration(info,env_keys),sort_keys=True).encode())


class Host:
    def __init__(self, config):
        self.config = config; self.root = Path(config["root"])
        self.control = self.root / "control"; self.marker = self.control / "maintenance.json"

    def sql(self, query):
        # Credentials stay inside the existing MySQL container, never arguments.
        return command("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$MYSQL_DATABASE"',
            data=("SET SESSION MAX_EXECUTION_TIME=5000;"+query).encode(), timeout=15).decode().strip()

    def schema(self):
        rows = self.sql("SELECT installed_rank,COALESCE(version,''),type,script,COALESCE(checksum,''),success FROM flyway_schema_history ORDER BY installed_rank")
        if not rows or any(r.split("\t")[-1] != "1" for r in rows.splitlines()): raise RuntimeError("Failed or missing migrations")
        return sha(rows.encode())

    def compatible(self, target):
        baseline = read(self.root / "baseline.json")
        if self.schema() != baseline["schemaFingerprint"]:
            raise RuntimeError("Database migration history drift; use the maintenance procedure")
        if target["source"]["inputs"]["migrations"] != baseline["migrationInputs"]:
            raise RuntimeError("Schema changes require independent maintenance")

    def preflight(self, target, baseline, rollback=False, restart=False):
        self.compatible(target)
        if not (rollback or restart) and target["source"]["inputs"]["operations"]!=read(self.root/"baseline.json")["operationsInputs"]:
            raise RuntimeError("Control plane or backup changes require explicit host maintenance")
        if target["source"]["inputs"]["unsupported"] != baseline["source"]["inputs"]["unsupported"]:
            raise RuntimeError("Changes include services outside automatic CD")
        if shutil.disk_usage(self.root).free < 14*GIB+target["sourceUnpackedBytes"]: raise RuntimeError("Insufficient space for source plus 14 GiB reserve")
        if not (rollback or restart):
            req=urllib.request.Request("https://api.github.com/repos/Gotit-zhx/ai-video/git/ref/heads/main-tree",headers={"User-Agent":"heyi-ops","Accept":"application/vnd.github+json"})
            with urllib.request.urlopen(req,timeout=15) as response:head=json.load(response)["object"]["sha"]
            if head!=read(self.root/"releases"/target["version"]/"verified.json")["productionCommit"]:
                raise RuntimeError("A newer production head superseded this request")
        if self.marker.exists(): raise Busy("A maintenance gate already exists")
        contract = read(self.root / "baseline.json")
        source_current=Path(self.config["sourceRoot"])/"current"
        if source_current.resolve().name!=baseline["version"]:
            raise RuntimeError("Current source pointer drift")
        for service, name in SERVICES.items():
            info = inspect(name)
            if image_config_digest(inspect(info['Image'])) != baseline["images"][service]["configDigest"]: raise RuntimeError("Active image drift")
            if config_hash(info,contract["environmentKeys"][service]) != contract["configurationHashes"][service]: raise RuntimeError("Runtime configuration drift")
            image = target["images"][service]
            if image_config_digest(inspect(image["repository"]+"@"+image["manifestDigest"])) != image["configDigest"]:
                raise RuntimeError("Prefetched image config digest mismatch")
        actual = set(command("docker", "ps", "--format", "{{.Names}}").decode().splitlines())
        expected = set(self.config["permanentContainers"])
        if restart: actual.discard(SERVICES["app"]); expected.discard(SERVICES["app"])
        if actual != expected: raise Busy("Unknown or missing runtime containers")
        # Metadata probes stay local; output does not contain arbitrary commands.
        processes = command("ps", "-eo", "args").decode()
        for line in processes.splitlines():
            if "/opt/heyi/releases/" in line and ("release-" in line or "java " in line):
                raise Busy("Legacy maintenance process is active")

    def gate(self, owner):
        existing = read(self.marker)
        if existing and existing.get("owner") != owner: raise Busy("Maintenance gate has another owner")
        atomic(self.marker, {"owner":owner,"createdAt":int(time.time())})

    def ungate(self, owner):
        existing = read(self.marker)
        if existing and existing.get("owner") != owner: raise Busy("Refusing to remove another operation's gate")
        self.marker.unlink(missing_ok=True)
        fd=os.open(self.control,os.O_RDONLY)
        try:os.fsync(fd)
        finally:os.close(fd)

    def owns_gate(self,owner):
        value=read(self.marker)
        if value and value.get("owner")!=owner:raise Busy("Foreign maintenance gate")
        return bool(value)

    def request(self, path, *, authenticated=False):
        headers = {}
        if authenticated: headers["X-Heyi-Ops-Token"] = (self.control / "status-token").read_text().strip()
        req = urllib.request.Request(self.config["backendUrl"]+path, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                return response.status, response.read(1024*1024)
        except urllib.error.HTTPError as response: return response.code, response.read(4096)

    def counters(self):
        body = self.sql(";".join("SELECT COUNT(*) FROM "+table+" WHERE "+condition for table, condition in ACTIVE.items()))
        rows = body.splitlines()
        if len(rows) != len(ACTIVE): raise RuntimeError("Incomplete worker status")
        return dict(zip(ACTIVE, map(int, rows)))

    def drain(self, owner):
        deadline = time.monotonic()+self.config.get("drainTimeoutSeconds",180)
        quiet = 0
        while time.monotonic()<deadline:
            if read(self.marker,{}).get("owner") != owner: raise Busy("Gate ownership changed")
            running=inspect(SERVICES["app"])["State"]["Running"]
            if running:
                try:
                    code, body = self.request("/_heyi/ops/status", authenticated=True)
                    status = json.loads(body) if code == 200 else {}
                except (OSError,ValueError): status={}
            else:
                status={"protocol":1,"maintenance":True,"activeRequests":0,"activeWorkers":0}
            counters = self.counters()
            edge=command("docker","exec",SERVICES["frontend"],"wget","-qO-","http://127.0.0.1/_heyi/nginx-status",timeout=10).decode()
            counts=re.search(r"Reading:\s*(\d+)\s+Writing:\s*(\d+)",edge)
            # The status request itself is the sole allowed writer. Existing
            # downloads and independent proxied business requests finish first.
            edge_idle=bool(counts) and int(counts[1])==0 and int(counts[2])<=1
            safe = status.get("protocol")==1 and status.get("maintenance") is True and status.get("activeRequests")==0 and status.get("activeWorkers")==0 and not any(counters.values()) and edge_idle
            quiet = quiet+1 if safe else 0
            if quiet>=2: return
            time.sleep(2)
        raise Busy("Business did not reach a safe point; keep the old service")

    def compose(self, images_file, *args):
        return command("docker","compose","--project-directory",str(self.root/"compose"),"--env-file","/dev/null",
                       "-p",self.config["composeProject"],"-f",str(self.root/"compose/base.json"),
                       "-f",str(self.root/"compose/production.json"),"-f",str(images_file),*args,timeout=240)

    def activate(self, target, changed):
        images = self.root/"releases"/target["version"]/"images.json"
        desired = {"services":{name:{"image":info["repository"]+"@"+info["manifestDigest"]} for name,info in target["images"].items()}}
        atomic(images,desired)
        self.compose(images,"config","--quiet")
        # Graceful stop is bounded; tasks have already passed the safe point.
        self.compose(images,"up","-d","--force-recreate","--no-deps","--no-build","--pull","never","--timeout","120",*changed)

    def verify(self, target, maintenance=False):
        deadline = time.monotonic()+180
        while True:
            try:
                code,body=self.request("/actuator/health")
                if code==200 and json.loads(body).get("status")=="UP": break
            except (OSError,ValueError): pass
            if time.monotonic()>deadline: raise RuntimeError("Backend health deadline exceeded")
            time.sleep(2)
        for name,container in SERVICES.items():
            info=inspect(container)
            if not info["State"]["Running"] or info["State"]["OOMKilled"] or image_config_digest(inspect(info['Image']))!=target["images"][name]["configDigest"]:
                raise RuntimeError("Runtime image or health mismatch")
            contract=read(self.root/"baseline.json")
            if config_hash(info,contract["environmentKeys"][name])!=contract["configurationHashes"][name]:
                raise RuntimeError("Configuration changed during activation")
        self.compatible(target)
        code,_=self.request("/api/v1/finance/shops")
        if code!=(503 if maintenance else 401): raise RuntimeError("Maintenance/authentication contract failed")
        code,body=self.request("/_heyi/ops/status",authenticated=True)
        control=json.loads(body) if code==200 else {}
        if control.get("protocol")!=1 or control.get("maintenance")!=maintenance:
            raise RuntimeError("Private maintenance protocol failed")
        # Use the pinned domain and loopback resolution, retaining TLS verification.
        for resource in ("/", "/asset-manifest.json"):
            data=command("curl","--fail","--silent","--show-error","--max-time","15","--resolve",
                         self.config["domain"]+":443:127.0.0.1","https://"+self.config["domain"]+resource,timeout=20)
            if resource.endswith("json"):
                if sha(data)!=target["images"]["frontend"]["assetManifestSha256"]: raise RuntimeError("Public static asset manifest mismatch")
        for path,digest in target["images"]["frontend"]["smokeAssets"].items():
            data=command("curl","--fail","--silent","--show-error","--max-time","15","--compressed","--resolve",
                         self.config["domain"]+":443:127.0.0.1","https://"+self.config["domain"]+"/"+path,timeout=20)
            if sha(data)!=digest: raise RuntimeError("Public static resource mismatch")

    def sync_source(self, target):
        version=target["version"]; directory=Path(self.config["sourceRoot"])/"releases"/version
        archive=self.root/"releases"/version/"source.tar.gz"
        if sha(archive.read_bytes())!=target["sourceArchiveSha256"]: raise RuntimeError("Source archive checksum mismatch")
        if not directory.exists():
            staging=directory.with_name(version+".extracting")
            if staging.exists():
                owner=read(staging/".heyi-owned.json",{})
                if owner.get("archiveSha256")!=target["sourceArchiveSha256"]:raise Busy("Unknown source staging ownership")
                shutil.rmtree(staging)
            staging.mkdir(parents=True)
            atomic(staging/".heyi-owned.json",{"archiveSha256":target["sourceArchiveSha256"]})
            files={}
            with tarfile.open(archive) as tar:
                unpacked=0;seen=set()
                for member in tar:
                    parts=Path(member.name).parts
                    normalized=str(Path(member.name))
                    if member.name.startswith("/") or ".." in parts or normalized in {".",".heyi-owned.json",".heyi-source.json"} or normalized in seen or not (member.isfile() or member.isdir()):
                        raise ValueError("Unsafe source archive entry")
                    seen.add(normalized)
                    unpacked+=member.size
                    if unpacked>target["sourceUnpackedBytes"]:raise ValueError("Source archive exceeded its unpack budget")
                    if member.isfile():files[member.name]=sha(tar.extractfile(member).read())
                tar.extractall(staging,filter="data")
            atomic(staging/".heyi-source.json",{"version":version,"tree":target["source"]["tree"],"archiveSha256":target["sourceArchiveSha256"],"files":files})
            staging.rename(directory)
        receipt=read(directory/".heyi-source.json")
        if not receipt or receipt["archiveSha256"]!=target["sourceArchiveSha256"]: raise RuntimeError("Existing source differs")
        entries=list(directory.rglob("*"))
        if directory.is_symlink() or any(p.is_symlink() for p in entries):raise RuntimeError("Source directory contains a symlink")
        actual={str(p.relative_to(directory)):p for p in entries if p.is_file() and str(p.relative_to(directory)) not in {".heyi-source.json",".heyi-owned.json"}}
        if set(actual)!=set(receipt.get("files",{})) or any(p.is_symlink() or sha(p.read_bytes())!=receipt["files"][name] for name,p in actual.items()):
            raise RuntimeError("Source files differ from the verified archive")
        current=Path(self.config["sourceRoot"])/"current"; temporary=current.with_name(".current-next")
        temporary.unlink(missing_ok=True); temporary.symlink_to(directory); temporary.replace(current)
