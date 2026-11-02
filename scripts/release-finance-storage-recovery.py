"""Deploy two reviewed finance classes over the exact live image; disable log files."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import zipfile

os.umask(0o077)
spec=importlib.util.spec_from_file_location("queue_release","/opt/heyi/releases/finance-sync-queue-20261030/release-finance-sync-queue.py")
queue=importlib.util.module_from_spec(spec);spec.loader.exec_module(queue)
core,base,ROOT=queue.core,queue.base,queue.ROOT
APP,FRONT,REDIS="ai-commerce-app","ai-commerce-frontend","ai-commerce-redis"
IMAGE="ai-commerce-platform:finance-storage-recovery-20261102"
FAMILIES={"FinanceLabelCosts","FinanceSync"}

def replaced(name):
    return name.startswith("BOOT-INF/classes/com/yingdao/aicommerce/finance/") and name.rsplit("/",1)[-1].split("$")[0].split(".")[0] in FAMILIES

def build():
    old=(ROOT/"previous.jar").read_bytes()
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.sha(old)
    folder=ROOT/"app-image";folder.mkdir(exist_ok=True)
    entries={}
    for file in (ROOT/"classes").rglob("*.class"):
        name="BOOT-INF/classes/"+str(file.relative_to(ROOT/"classes"))
        assert replaced(name)
        entries[name]=file.read_bytes()
    assert len(entries)>=2
    with zipfile.ZipFile(io.BytesIO(old)) as source,zipfile.ZipFile(folder/"app.jar","w") as target:
        target.comment=source.comment
        for info in source.infolist():
            if not replaced(info.filename):target.writestr(info,source.read(info.filename))
        for name,value in entries.items():target.writestr(name,value,compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(old)) as source,zipfile.ZipFile(folder/"app.jar") as target:
        assert set(target.namelist())=={n for n in source.namelist() if not replaced(n)}|set(entries)
        assert all(source.read(n)==target.read(n) for n in source.namelist() if not replaced(n))
    before=core.inspect(APP)
    assert core.inspect(before["Config"]["Image"])["Id"]==before["Image"]
    (folder/"app.jar").chmod(0o644)
    (folder/"Dockerfile").write_text("FROM "+before["Config"]["Image"]+"\nCOPY app.jar /app/app.jar\n")
    core.run("docker","build","--pull=false","-t",IMAGE,str(folder))
    core.write_json(ROOT/"prepared.json",{"previousJar":core.sha(old),"jarHash":core.sha((folder/"app.jar").read_bytes()),
        "classes":{n:core.sha(v) for n,v in entries.items()},"unrelatedEntriesPreserved":True})
    print("Exact-live two-class overlay built.")

def activate(before,name,image):
    labels=before[name]["Config"]["Labels"]
    command=["docker","compose","--project-directory",labels["com.docker.compose.project.working_dir"],
        "-p",labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        assert Path(file).is_file(),"Missing runtime Compose dependency"
        command+=["-f",file]
    override=ROOT/("compose."+name+".json")
    core.write_json(override,{"services":{labels["com.docker.compose.service"]:{
        "image":image,"logging":{"driver":"none"}}}})
    # Preserve explicit loopback bindings from the running container. Older
    # Compose sources may still contain an unrestricted published port.
    ports=[]
    for target,bindings in (before[name]["HostConfig"]["PortBindings"] or {}).items():
        port,protocol=target.split("/")
        for binding in bindings or []:
            ports.append({"target":int(port),"published":binding["HostPort"],
                **({"host_ip":binding["HostIp"]} if binding["HostIp"] else {}),"protocol":protocol})
    port_override=ROOT/("compose."+name+".ports.yaml")
    port_override.write_text("services:\n  "+labels["com.docker.compose.service"]+
        ":\n    ports: !override "+json.dumps(ports)+"\n")
    env=dict(os.environ,HR_FRONTEND_IMAGE=before[FRONT]["Config"]["Image"])
    core.run(*command,"-f",str(override),"-f",str(port_override),"config","--quiet",env=env)
    core.run(*command,"-f",str(override),"-f",str(port_override),"up","-d","--no-deps","--no-build","--pull","never",
        labels["com.docker.compose.service"],env=env)

def verify():
    core.health(APP)
    assert core.inspect(APP)["Config"]["Image"]==IMAGE
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("prepared.json")["jarHash"]
    for name in (APP,FRONT,REDIS,"ai-commerce-mysql"):
        info=core.inspect(name)
        assert info["HostConfig"]["LogConfig"]["Type"]=="none"
        assert not info.get("LogPath")
    for endpoint in ("/","/api/v1/finance/shops","/api/v1/finance/overview"):
        status=core.run("curl","--noproxy","*","-sS","--max-time","10","-o","/dev/null","-w","%{http_code}",
            "--resolve","hoyee.org.cn:443:127.0.0.1","https://hoyee.org.cn"+endpoint).decode()
        assert status==("200" if endpoint=="/" else "401")
    print("Live health, exact overlay, access guards and no-log configuration verified.")

def deploy():
    assert core.read_json("qa-passed.json")["tests"]==18
    assert not (ROOT/"activation.json").exists()
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("prepared.json")["previousJar"]
    before={name:core.inspect(name) for name in (APP,FRONT,REDIS)}
    original=core.read_json("before-private.json")
    for name in before:
        before[name]["HostConfig"]["PortBindings"]=original[name]["HostConfig"]["PortBindings"]
    rollback=IMAGE+"-before";core.run("docker","tag",before[APP]["Image"],rollback)
    try:
        core.run("docker","stop","--timeout","120",APP)
        activate(before,REDIS,before[REDIS]["Config"]["Image"])
        for _ in range(30):
            if core.inspect(REDIS)["State"].get("Health",{}).get("Status")=="healthy":break
            time.sleep(1)
        assert core.inspect(REDIS)["State"].get("Health",{}).get("Status")=="healthy"
        activate(before,APP,IMAGE);core.health(APP)
        activate(before,FRONT,before[FRONT]["Config"]["Image"])
        verify()
        for name in before:assert core.configuration(core.inspect(name))==core.configuration(before[name])
    except BaseException:
        activate(before,APP,rollback);core.health(APP)
        activate(before,FRONT,before[FRONT]["Config"]["Image"])
        raise
    core.write_json(ROOT/"activation.json",{"image":IMAGE,"rollbackImage":rollback,"tests":18,
        "configurationPreserved":True,"persistentContainerLogs":False})

def drain_deploy():
    lease=core.read_json("label-maintenance.json")["lease"]
    base.sql("UPDATE finance_label_worker_lock SET lease_token=NULL,lease_until=NULL WHERE id=1 AND lease_token='"+lease+"'")
    queue.deploy=deploy
    queue.drain_deploy()

if __name__=="__main__":
    {"build":build,"drain-deploy":drain_deploy,"verify":verify}[sys.argv[2]]()
