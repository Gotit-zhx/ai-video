"""Exact-live order/label decoupling and bounded Qianyi sync optimization."""
import importlib.util
import io
import json
import os
import sys
import time
import uuid
import zipfile

os.umask(0o077)
spec=importlib.util.spec_from_file_location("qianyi_release","/opt/heyi/releases/finance-qianyi-20261030/release-finance-qianyi.py")
qianyi=importlib.util.module_from_spec(spec);spec.loader.exec_module(qianyi)
previous=qianyi.previous
core,base,ROOT,APP=qianyi.core,qianyi.base,qianyi.ROOT,qianyi.APP
IMAGE="ai-commerce-platform:finance-sync-queue-20261030"
previous.IMAGES={APP:IMAGE}
previous.FAMILIES={"FinanceLabelCosts","FinanceQianyi","FinanceQianyiWorker"}
previous.MIGRATION="BOOT-INF/classes/db/migration/V139__finance_label_arrival_queue.sql"
QIANYI_LEASE=None

def snapshot():
    ROOT.mkdir(parents=True,exist_ok=True)
    assert not (ROOT/"snapshot.json").exists()
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="138"
    core.write_json(ROOT/"before-private.json",{APP:core.inspect(APP)})
    jar=core.run("docker","exec",APP,"cat","/app/app.jar");(ROOT/"previous.jar").write_bytes(jar)
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        assert previous.MIGRATION not in archive.namelist()
        core.write_json(ROOT/"snapshot.json",{"jar":core.sha(jar),"expectedClasses":{
            n:core.sha(archive.read(n)) for n in archive.namelist() if previous.replaced(n)}})
    print("Current Qianyi release captured; no source data changed.")

def baseline():
    before=core.read_json("before-private.json")
    assert core.inspect(APP)["Id"]==before[APP]["Id"]
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("snapshot.json")["jar"]
    return before

def build():
    before=baseline();manifest=core.read_json("manifest.json")
    assert manifest["expectedClasses"]==core.read_json("snapshot.json")["expectedClasses"]
    folder=ROOT/"app-image";folder.mkdir()
    with zipfile.ZipFile(ROOT/"previous.jar") as old,zipfile.ZipFile(folder/"app.jar","w") as new:
        new.comment=old.comment
        for entry in old.infolist():
            if not previous.replaced(entry.filename):new.writestr(entry,old.read(entry.filename))
        for name,digest in manifest["classes"].items():
            assert previous.replaced(name)
            value=(ROOT/"classes"/name.removeprefix("BOOT-INF/classes/")).read_bytes()
            assert core.sha(value)==digest
            new.writestr(name,value,compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(ROOT/"previous.jar") as old,zipfile.ZipFile(folder/"app.jar") as new:
        assert set(new.namelist())=={n for n in old.namelist() if not previous.replaced(n)}|set(manifest["classes"])
        assert all(old.read(n)==new.read(n) for n in old.namelist() if not previous.replaced(n))
        for name in new.namelist():
            if name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                path=ROOT/"migration-classes"/name.removeprefix("BOOT-INF/classes/")
                path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(new.read(name))
    (folder/"app.jar").chmod(0o644)
    (folder/"Dockerfile").write_text("FROM "+before[APP]["Image"]+"\nCOPY app.jar /app/app.jar\n")
    core.run("docker","build","-t",IMAGE,str(folder))
    core.write_json(ROOT/"prepared.json",{"image":IMAGE,"jarHash":core.sha((folder/"app.jar").read_bytes())})
    print("Three reviewed class families and additive queue migration built over exact live jar.")

def migrate():
    baseline()
    previous.java_run("com.yingdao.aicommerce.finance.FinanceSyncQueueMigration")
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="139"

def verify():
    core.health(APP)
    assert core.inspect(APP)["Config"]["Image"]==IMAGE
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("prepared.json")["jarHash"]
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="139"
    print("Live queue release, additive schema and application health verified.")

def deploy():
    assert not (ROOT/"activation.json").exists()
    for table,condition in (
        ("finance_sync_states","status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)"),
        ("finance_ad_accounts","status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)"),
        ("product_publish_workflows","status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)"),
        ("finance_authorization_sessions","status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)"),
        ("finance_qianyi_connections","enabled=TRUE AND (lease_token IS NULL OR lease_token<>'"+QIANYI_LEASE+"')")
    ):
        assert base.sql("SELECT COUNT(*) FROM "+table+" WHERE "+condition)=="0","Finance request in progress: "+table
    assert qianyi.LABEL_LEASE
    assert base.sql("SELECT COUNT(*) FROM finance_label_worker_lock WHERE id=1 AND lease_token='"+qianyi.LABEL_LEASE+"' AND lease_until>UTC_TIMESTAMP(6)")=="1"
    before=baseline();base.release.idle()
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="139"
    preserved={c["Name"]:c["Id"] for c in json.loads(core.run("docker","inspect",*core.run("docker","ps","-q").decode().split()))
        if c["Name"].lstrip("/")!=APP}
    rollback=IMAGE+"-before";core.run("docker","tag",before[APP]["Image"],rollback)
    try:
        core.activate(before,APP,IMAGE);verify()
        assert core.configuration(core.inspect(APP))==core.configuration(before[APP])
        assert all(core.inspect(name)["Id"]==identifier for name,identifier in preserved.items())
    except BaseException:
        core.activate(before,APP,rollback);core.health(APP);raise
    core.write_json(ROOT/"activation.json",{"image":IMAGE,"rollbackImage":rollback,"schema":139,
        "configurationPreserved":True,"unrelatedJarEntriesPreserved":True})

def drain_deploy():
    global QIANYI_LEASE
    QIANYI_LEASE=str(uuid.uuid4())
    core.write_json(ROOT/"qianyi-drain.json",{"lease":QIANYI_LEASE})
    try:
        deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            base.sql("UPDATE finance_qianyi_connections SET lease_token='"+QIANYI_LEASE+
                "',lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE) WHERE enabled=TRUE"+
                " AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))")
            if base.sql("SELECT COUNT(*) FROM finance_qianyi_connections WHERE enabled=TRUE AND (lease_token IS NULL OR lease_token<>'"+QIANYI_LEASE+"')")=="0":
                qianyi.deploy=deploy;qianyi.drain_deploy();return
            time.sleep(1)
        raise RuntimeError("Qianyi requests did not reach a safe checkpoint")
    finally:
        base.sql("UPDATE finance_qianyi_connections SET lease_token=NULL,lease_until=NULL WHERE lease_token='"+QIANYI_LEASE+"'")
        print("Qianyi reservations released; configuration and checkpoints preserved.")

def recover():
    verify()
    count=base.sql("""
        UPDATE finance_sync_states SET next_at=UTC_TIMESTAMP(6)
        WHERE status='ERROR' AND channel IN ('ORDER','ORDER_LIVE','ORDER_REPAIR')
          AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))
          AND error_message LIKE '%finance_label_invoice_rows%';
        SELECT ROW_COUNT();
        """)
    print("Invoice-blocked order channels resumed from existing checkpoints:",count)

if __name__=="__main__":
    {"snapshot":snapshot,"build":build,"migrate":migrate,"verify":verify,"drain-deploy":drain_deploy,"recover":recover}[sys.argv[2]]()
