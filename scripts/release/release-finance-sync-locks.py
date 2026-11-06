"""Remove order queue lock upgrades over the exact live jar; preserve all sync cursors."""
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import zipfile

os.umask(0o077)
spec=importlib.util.spec_from_file_location("invoice_release","/opt/heyi/releases/finance-label-invoices-20261030/release-finance-label-invoices.py")
previous=importlib.util.module_from_spec(spec);spec.loader.exec_module(previous)
core,base,ROOT,APP=previous.core,previous.base,previous.ROOT,previous.APP
IMAGE="ai-commerce-platform:finance-sync-locks-20261030"
previous.FAMILIES={"FinanceRepository","FinanceOrderCosts"}
previous.MIGRATION="__no_schema_change__"

def snapshot():
    ROOT.mkdir(parents=True,exist_ok=True)
    assert not (ROOT/"snapshot.json").exists()
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="137"
    core.write_json(ROOT/"before-private.json",{APP:core.inspect(APP)})
    jar=core.run("docker","exec",APP,"cat","/app/app.jar");(ROOT/"previous.jar").write_bytes(jar)
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        core.write_json(ROOT/"snapshot.json",{"jar":core.sha(jar),"expectedClasses":{
            name:core.sha(archive.read(name)) for name in archive.namelist() if previous.replaced(name)}})
    print("Exact live backend captured; no business data changes.")

def baseline():
    before=core.read_json("before-private.json")
    assert core.inspect(APP)["Id"]==before[APP]["Id"]
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("snapshot.json")["jar"]
    return before

def extract(jar,folder):
    if folder.exists():return
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                path=folder/name.removeprefix("BOOT-INF/classes/")
                path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(archive.read(name))

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
            assert core.sha(value)==digest;new.writestr(name,value,compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(ROOT/"previous.jar") as old,zipfile.ZipFile(folder/"app.jar") as new:
        assert set(new.namelist())=={n for n in old.namelist() if not previous.replaced(n)}|set(manifest["classes"])
        assert all(old.read(n)==new.read(n) for n in old.namelist() if not previous.replaced(n))
    (folder/"app.jar").chmod(0o644)
    (folder/"Dockerfile").write_text("FROM "+before[APP]["Image"]+"\nCOPY app.jar /app/app.jar\n")
    core.run("docker","build","-t",IMAGE,str(folder))
    core.write_json(ROOT/"prepared.json",{"image":IMAGE,"jarHash":core.sha((folder/"app.jar").read_bytes())})
    extract(ROOT/"previous.jar",ROOT/"baseline-classes")
    extract(folder/"app.jar",ROOT/"migration-classes")
    print("Two reviewed class families built; all other jar entries unchanged.")

def verify():
    core.health(APP)
    assert core.inspect(APP)["Config"]["Image"]==IMAGE
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("prepared.json")["jarHash"]
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="137"
    print("Live sync lock fix and application health verified.")

def deploy():
    assert not (ROOT/"activation.json").exists()
    before=baseline();base.release.idle()
    for table,condition in (
        ("finance_label_worker_lock","lease_until>UTC_TIMESTAMP(6)"),
        ("finance_sync_states","status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)"),
        ("finance_ad_accounts","status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)"),
        ("product_publish_workflows","status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)"),
        ("finance_authorization_sessions","status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)")
    ):
        assert base.sql("SELECT COUNT(*) FROM "+table+" WHERE "+condition)=="0","Finance request in progress"
    preserved={c["Name"]:c["Id"] for c in json.loads(core.run("docker","inspect",*core.run("docker","ps","-q").decode().split()))
               if c["Name"].lstrip("/")!=APP}
    rollback=IMAGE+"-before";core.run("docker","tag",before[APP]["Image"],rollback)
    try:
        core.activate(before,APP,IMAGE);verify()
        assert core.configuration(core.inspect(APP))==core.configuration(before[APP])
        assert all(core.inspect(name)["Id"]==identifier for name,identifier in preserved.items())
    except BaseException:
        core.activate(before,APP,rollback);core.health(APP);raise
    core.write_json(ROOT/"activation.json",{"image":IMAGE,"rollbackImage":rollback,"schema":137,
        "configurationPreserved":True,"unrelatedJarEntriesPreserved":True})

def recover():
    verify()
    # Only advance retries of the affected failed order transactions. Their
    # cursor, watermark, failure history and status remain until sync succeeds.
    count=base.sql("""
        UPDATE finance_sync_states SET next_at=UTC_TIMESTAMP(6)
        WHERE status='ERROR' AND channel IN ('ORDER','ORDER_LIVE','ORDER_REPAIR')
          AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))
          AND (error_message LIKE '%finance_order_cost_jobs%' OR error_message LIKE '%finance_label_state%');
        SELECT ROW_COUNT();
        """)
    print("Affected order channels scheduled from their existing checkpoints:",count)

base.deploy=deploy
if __name__=="__main__":
    {"snapshot":snapshot,"build":build,"verify":verify,"drain-deploy":base.drain_deploy,"recover":recover}[sys.argv[2]]()
