"""Exact-live company authorization and Qianyi-native after-sales evidence overlay."""
import gzip
import importlib.util
import io
import json
import os
import subprocess
import sys
import zipfile

os.umask(0o077)
spec=importlib.util.spec_from_file_location("queue_release","/opt/heyi/releases/finance-sync-queue-20261030/release-finance-sync-queue.py")
queue=importlib.util.module_from_spec(spec);spec.loader.exec_module(queue)
previous=queue.previous
core,base,ROOT,APP,FRONT=queue.core,queue.base,queue.ROOT,queue.APP,queue.qianyi.FRONT
previous.IMAGES={APP:"ai-commerce-platform:finance-qianyi-company-20261030",FRONT:"ai-commerce-frontend:finance-qianyi-company-20261030"}
previous.FAMILIES={"FinanceQianyiAccount","FinanceQianyiAccountController","FinanceQianyiReturns","FinanceQianyiReturnsController",
    "FinanceReadCache","FinanceService","FinanceRepository","FinanceSync","FinanceSyncClaims","FinanceRefundPacing","FinanceCostExport","FinanceQianyi","FinanceQianyiController","FinanceQianyiEvidence","FinanceQianyiWorker","FinanceActualCosts","FinanceProfitReport"}
previous.MIGRATION="BOOT-INF/classes/db/migration/V140__finance_qianyi_company_configuration.sql"
previous.FILES={"index.html","finance-workspace.js","finance-qianyi.js","finance-qianyi.css","finance-loading.css"}
previous.NEW_FILES={"finance-qianyi.css","finance-loading.css"}

def snapshot():
    ROOT.mkdir(parents=True,exist_ok=True)
    assert not (ROOT/"snapshot.json").exists()
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="139"
    core.write_json(ROOT/"before-private.json",{n:core.inspect(n) for n in (APP,FRONT)})
    jar=core.run("docker","exec",APP,"cat","/app/app.jar");(ROOT/"previous.jar").write_bytes(jar)
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        result={"jar":core.sha(jar),"expectedClasses":{n:core.sha(archive.read(n)) for n in archive.namelist() if previous.replaced(n)}}
    folder=ROOT/"baseline-frontend";folder.mkdir()
    result["expectedFiles"]={}
    for name in previous.FILES-previous.NEW_FILES:
        value=core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+name)
        (folder/name).write_bytes(value);result["expectedFiles"][name]=core.sha(value)
    core.write_json(ROOT/"snapshot.json",result)
    print("Captured live source, shop UI and Qianyi schema 139.")

def build():
    previous.build()
    with zipfile.ZipFile(ROOT/"app-image/app.jar") as archive:
        for name in archive.namelist():
            if name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                path=ROOT/"migration-classes"/name.removeprefix("BOOT-INF/classes/")
                path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(archive.read(name))

def migrate():
    previous.baseline()
    version=base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")
    if version=="140":
        assert base.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0")=="0"
        core.health(APP)
        print("Schema 140 already applied; resuming activation without rebuilding indexes.")
        return
    assert version=="139", "Unexpected schema version"
    app=core.inspect(APP);db=core.inspect("ai-commerce-mysql")
    settings=dict(v.split("=",1) for v in app["Config"]["Env"])
    private=ROOT/"operation-private.env"
    network=next(iter(set(app["NetworkSettings"]["Networks"])&set(db["NetworkSettings"]["Networks"])))
    try:
        private.write_text("".join(k+"="+settings[k]+"\n" for k in ("DB_URL","DB_USERNAME","DB_PASSWORD")))
        subprocess.run(["docker","run","--rm","--log-driver","none","--user","0:0",
            "--name","finance-company-migration","--network",network,"--memory","512m","--cpus","1",
            "--env-file",str(private),"-v",str(ROOT)+":/release:ro",
            "-v","/tmp/finance-reporting-months-qa-20261028/lib:/deps:ro",
            "--entrypoint","java",previous.IMAGES[APP],"-Xmx256m",
            "-cp","/release/runner:/release/migration-classes:/deps/*",
            "com.yingdao.aicommerce.finance.FinanceQianyiCompanyMigration"],check=True,timeout=600)
    except subprocess.TimeoutExpired:
        subprocess.run(["docker","rm","-f","finance-company-migration"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        raise
    finally:
        private.unlink(missing_ok=True)
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="140"
    core.health(APP)
    print("Additive global configuration and indexed source fields migrated.")

def verify():
    core.health(APP)
    for name,image in previous.IMAGES.items():assert core.inspect(name)["Config"]["Image"]==image
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("prepared.json")["jarHash"]
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="140"
    core.run("docker","exec",FRONT,"nginx","-t")
    for name,digest in core.read_json("manifest.json")["files"].items():
        assert core.sha(core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+name))==digest
        for encoding in ("identity","gzip"):
            value=core.run("curl","--noproxy","*","-fsS","--retry","5","--retry-all-errors","--retry-delay","1","--max-time","8",
                "--resolve","hoyee.org.cn:443:127.0.0.1","-H","Accept-Encoding: "+encoding,"https://hoyee.org.cn/"+name)
            if encoding=="gzip" and value.startswith(b"\x1f\x8b"):value=gzip.decompress(value)
            assert core.sha(value)==digest
    for path in ("qianyi","qianyi/shops","qianyi/returns","qianyi/returns/detail?shopId=test&returnNumber=test"):
        status=core.run("curl","--noproxy","*","-sS","-o","/dev/null","-w","%{http_code}","--max-time","8",
            "--resolve","hoyee.org.cn:443:127.0.0.1","https://hoyee.org.cn/api/v1/finance/"+path).decode()
        assert status=="401",path
    print("Global configuration, Qianyi evidence assets, schema, access guards and health verified.")

def deploy():
    assert not (ROOT/"activation.json").exists()
    for table,condition in (
        ("finance_sync_states","status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)"),
        ("finance_ad_accounts","status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)"),
        ("product_publish_workflows","status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)"),
        ("finance_authorization_sessions","status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)"),
        ("finance_qianyi_connections","enabled=TRUE AND (lease_token IS NULL OR lease_token<>'"+queue.QIANYI_LEASE+"')")
    ):
        assert base.sql("SELECT COUNT(*) FROM "+table+" WHERE "+condition)=="0","Finance request in progress: "+table
    assert base.sql("SELECT COUNT(*) FROM finance_label_worker_lock WHERE id=1 AND lease_token='"+queue.qianyi.LABEL_LEASE+"' AND lease_until>UTC_TIMESTAMP(6)")=="1"
    before,_=previous.baseline();base.release.idle()
    # Validate the complete compose chain before a migration or container change.
    # Compose files remain runtime dependencies even after package cleanup.
    for name in previous.IMAGES:
        labels=before[name]["Config"]["Labels"]
        command=["docker","compose","--project-directory",labels["com.docker.compose.project.working_dir"],
            "-p",labels["com.docker.compose.project"]]
        for file in labels["com.docker.compose.project.config_files"].split(","):
            command+=["-f",file]
        core.run(*command,"config","--quiet",env=dict(os.environ,HR_FRONTEND_IMAGE=core.inspect(FRONT)["Config"]["Image"]))
    migrate()
    subprocess.run(["python3",str(ROOT/"probe/run-probe.py")],check=True,timeout=180)
    preserved={c["Name"]:c["Id"] for c in json.loads(core.run("docker","inspect",*core.run("docker","ps","-q").decode().split()))
        if c["Name"].lstrip("/") not in previous.IMAGES}
    rollback={name:image+"-before" for name,image in previous.IMAGES.items()}
    for name in rollback:core.run("docker","tag",before[name]["Image"],rollback[name])
    try:
        core.activate(before,APP,previous.IMAGES[APP]);core.health(APP)
        core.activate(before,FRONT,previous.IMAGES[FRONT]);verify()
        for name in previous.IMAGES:assert core.configuration(core.inspect(name))==core.configuration(before[name])
        assert all(core.inspect(name)["Id"]==identifier for name,identifier in preserved.items())
    except BaseException:
        for name in rollback:core.activate(before,name,rollback[name])
        core.health(APP);raise
    core.write_json(ROOT/"activation.json",{"images":previous.IMAGES,"rollbackImages":rollback,"schema":140,
        "configurationPreserved":True,"unrelatedJarEntriesPreserved":True})

def drain_deploy():
    queue.deploy=deploy;queue.drain_deploy()

if __name__=="__main__":
    {"snapshot":snapshot,"build":build,"migrate":migrate,"verify":verify,"drain-deploy":drain_deploy}[sys.argv[2]]()
