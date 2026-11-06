"""Exact-live backend overlay; online covering-index migration and unchanged frontend."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

os.umask(0o077)
ROOT=Path(sys.argv[1]).resolve()
spec=importlib.util.spec_from_file_location("finance_base","/opt/heyi/releases/finance-groups-20261022/release-finance-company-groups.py")
base=importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
core,APP,FRONT=base.core,base.APP,base.FRONT
IMAGE="ai-commerce-platform:finance-latency-20261030"
PREFIX="BOOT-INF/classes/com/yingdao/aicommerce/finance/"
FAMILIES={"FinanceRepository","FinanceCostReport","FinanceService","FinanceReadCache","FinanceCostWorker"}
MIGRATION="BOOT-INF/classes/db/migration/V136__finance_order_lookup_covering.sql"

def replaced(name):
    return name==MIGRATION or any(name==PREFIX+n+".class" or name.startswith(PREFIX+n+"$") for n in FAMILIES)

def snapshot():
    ROOT.mkdir(parents=True,exist_ok=True)
    assert not (ROOT/"snapshot.json").exists()
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="135"
    before={APP:core.inspect(APP)}
    core.write_json(ROOT/"before-private.json",before)
    data=core.run("docker","exec",APP,"cat","/app/app.jar")
    (ROOT/"previous.jar").write_bytes(data)
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        assert MIGRATION not in z.namelist()
        expected={"jar":core.sha(data),"expectedClasses":{n:core.sha(z.read(n)) for n in z.namelist() if replaced(n)}}
    expected["frontendId"]=core.inspect(FRONT)["Id"]
    expected["frontendFiles"]={n:core.sha(core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+n)) for n in ("index.html","finance-workspace.js")}
    core.write_json(ROOT/"snapshot.json",expected)
    print("Exact backend and unchanged frontend captured.")

def build():
    assert not (ROOT/"prepared.json").exists()
    manifest,before,expected=(core.read_json(n) for n in ("manifest.json","before-private.json","snapshot.json"))
    assert core.inspect(APP)["Id"]==before[APP]["Id"]
    data=core.run("docker","exec",APP,"cat","/app/app.jar")
    assert core.sha(data)==expected["jar"]
    folder=ROOT/"app-image";folder.mkdir()
    with zipfile.ZipFile(io.BytesIO(data)) as old,zipfile.ZipFile(folder/"app.jar","w") as new:
        assert {n:core.sha(old.read(n)) for n in old.namelist() if replaced(n)}==manifest["expectedClasses"]
        new.comment=old.comment
        for entry in old.infolist():
            if not replaced(entry.filename):new.writestr(entry,old.read(entry.filename))
        for name,digest in manifest["classes"].items():
            assert replaced(name)
            value=(ROOT/"classes"/name.removeprefix("BOOT-INF/classes/")).read_bytes()
            assert core.sha(value)==digest
            new.writestr(name,value,compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(data)) as old,zipfile.ZipFile(folder/"app.jar") as new:
        assert set(new.namelist())=={n for n in old.namelist() if not replaced(n)}|set(manifest["classes"])
        assert all(old.read(n)==new.read(n) for n in old.namelist() if not replaced(n))
    (folder/"app.jar").chmod(0o644)
    (folder/"Dockerfile").write_text("FROM "+before[APP]["Image"]+"\nCOPY app.jar /app/app.jar\n")
    core.run("docker","build","-t",IMAGE,str(folder))
    core.run("docker","run","--rm","--log-driver","none","--entrypoint","sh",IMAGE,"-c","test -r /app/app.jar")
    core.write_json(ROOT/"prepared.json",{"image":IMAGE,"jarHash":core.sha((folder/"app.jar").read_bytes())})
    print("Backend overlay built; frontend, dependencies and unrelated classes preserved.")

def migrate():
    before=core.read_json("before-private.json")[APP]
    assert core.inspect(APP)["Id"]==before["Id"]
    app=core.inspect(APP);db=core.inspect("ai-commerce-mysql")
    settings=dict(v.split("=",1) for v in app["Config"]["Env"])
    private=ROOT/"migration-private.env"
    resources=ROOT/"migration-classes"
    with zipfile.ZipFile(ROOT/"app-image/app.jar") as z:
        for n in z.namelist():
            if n.startswith("BOOT-INF/classes/") and not n.endswith("/"):
                p=resources/n.removeprefix("BOOT-INF/classes/")
                p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(n))
    try:
        private.write_text("".join(k+"="+settings[k]+"\n" for k in ("DB_URL","DB_USERNAME","DB_PASSWORD")))
        network=next(iter(set(app["NetworkSettings"]["Networks"])&set(db["NetworkSettings"]["Networks"])))
        libs="/tmp/finance-reporting-months-qa-20261028/lib"
        subprocess.run(["docker","run","--rm","--log-driver","none","--user","0:0","--name","finance-lookup-index-migration",
            "--network",network,"--memory","512m","--cpus","1","--env-file",str(private),
            "-v",str(ROOT)+":/release:ro","-v",libs+":/deps:ro","--entrypoint","java",app["Config"]["Image"],
            "-Xmx256m","-cp","/release/runner:/release/migration-classes:/deps/*",
            "com.yingdao.aicommerce.finance.FinanceQueryIndexMigration"],check=True,timeout=900)
    finally:
        private.unlink(missing_ok=True)
    assert base.sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='finance_records' AND index_name='idx_finance_settled_order'")=="15"
    print("Online index migration completed with the existing web service available.")

def verify():
    core.health(APP)
    assert core.inspect(APP)["Config"]["Image"]==IMAGE
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("prepared.json")["jarHash"]
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="136"
    expected=core.read_json("snapshot.json")
    assert core.inspect(FRONT)["Id"]==expected["frontendId"]
    for n,digest in expected["frontendFiles"].items():
        assert core.sha(core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+n))==digest
    for endpoint in ("overview","costs/orders","records"):
        status=core.run("curl","--noproxy","*","-sS","-o","/dev/null","-w","%{http_code}","--max-time","8",
            "--resolve","hoyee.org.cn:443:127.0.0.1","https://hoyee.org.cn/api/v1/finance/workspace/"+endpoint).decode()
        assert status=="401"

def deploy():
    assert not (ROOT/"activation.json").exists()
    before=core.read_json("before-private.json")
    assert core.inspect(APP)["Id"]==before[APP]["Id"]
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="136"
    base.release.idle()
    assert base.sql("SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)")=="0","Finance request in progress"
    assert base.sql("SELECT COUNT(*) FROM finance_ad_accounts WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)")=="0"
    assert base.sql("SELECT COUNT(*) FROM finance_authorization_sessions WHERE status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)")=="0"
    preserved={x["Name"]:x["Id"] for x in json.loads(core.run("docker","inspect",*core.run("docker","ps","-q").decode().split())) if x["Name"].lstrip("/")!=APP}
    rollback=IMAGE+"-before";core.run("docker","tag",before[APP]["Image"],rollback)
    try:
        core.activate(before,APP,IMAGE);verify()
        assert core.configuration(core.inspect(APP))==core.configuration(before[APP])
        assert all(core.inspect(n)["Id"]==identifier for n,identifier in preserved.items())
    except BaseException:
        core.activate(before,APP,rollback);core.health(APP);raise
    core.write_json(ROOT/"activation.json",{"image":IMAGE,"rollbackImage":rollback,"changedClasses":sorted(FAMILIES),
        "frontendUnchanged":True,"configurationPreserved":True,"schema":136,"indexPrefixPreserved":True})
    print("Finance query optimization active; original sync schedules will be restored.")

base.deploy=deploy
if __name__=="__main__":
    try:
        {"snapshot":snapshot,"build":build,"migrate":migrate,"drain-deploy":base.drain_deploy,"verify":verify}[sys.argv[2]]()
    except subprocess.CalledProcessError:
        raise SystemExit("Finance query release failed; inspect phase before retry. No persistent log was created.")
