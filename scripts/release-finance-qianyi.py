"""Exact-live overlay for Qianyi configuration and read-only after-sales sync."""
import gzip
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import zipfile
import datetime
import time
import uuid

os.umask(0o077)
spec=importlib.util.spec_from_file_location("invoice_release","/opt/heyi/releases/finance-label-invoices-20261030/release-finance-label-invoices.py")
previous=importlib.util.module_from_spec(spec);spec.loader.exec_module(previous)
core,base,ROOT,APP,FRONT=previous.core,previous.base,previous.ROOT,previous.APP,previous.FRONT
previous.IMAGES={APP:"ai-commerce-platform:finance-qianyi-20261030",FRONT:"ai-commerce-frontend:finance-qianyi-20261030"}
previous.FAMILIES={"FinanceQianyi","FinanceQianyiClient","FinanceQianyiController","FinanceQianyiEvidence",
                   "FinanceQianyiWorker","FinanceActualCosts","FinanceProfitReport","FinanceShopDeletion"}
previous.FILES={"index.html","finance-workspace.js","finance-qianyi.js"}
previous.NEW_FILES={"finance-qianyi.js"}
previous.MIGRATION="BOOT-INF/classes/db/migration/V138__finance_qianyi_returns.sql"
LABEL_LEASE=None

def snapshot():
    ROOT.mkdir(parents=True,exist_ok=True)
    assert not (ROOT/"snapshot.json").exists()
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="137"
    before={name:core.inspect(name) for name in (APP,FRONT)}
    core.write_json(ROOT/"before-private.json",before)
    jar=core.run("docker","exec",APP,"cat","/app/app.jar");(ROOT/"previous.jar").write_bytes(jar)
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        assert previous.MIGRATION not in archive.namelist()
        result={"jar":core.sha(jar),"expectedClasses":{n:core.sha(archive.read(n)) for n in archive.namelist() if previous.replaced(n)}}
    folder=ROOT/"baseline-frontend";folder.mkdir()
    result["expectedFiles"]={}
    for name in previous.FILES-previous.NEW_FILES:
        value=core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+name)
        (folder/name).write_bytes(value);result["expectedFiles"][name]=core.sha(value)
    core.run("docker","exec",FRONT,"sh","-c","test ! -f /usr/share/nginx/html/finance-qianyi.js")
    core.write_json(ROOT/"snapshot.json",result)
    print("Exact live backend and frontend captured; source data unchanged.")

def build():
    previous.build()
    folder=ROOT/"migration-classes"
    with zipfile.ZipFile(ROOT/"app-image/app.jar") as archive:
        for name in archive.namelist():
            if name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                path=folder/name.removeprefix("BOOT-INF/classes/");path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(archive.read(name))

def migrate():
    previous.baseline()
    previous.java_run("com.yingdao.aicommerce.finance.FinanceQianyiMigration")
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="138"
    core.health(APP)
    print("Only additive schema 138 applied; existing finance service remains available.")

def verify():
    core.health(APP)
    for name,image in previous.IMAGES.items():assert core.inspect(name)["Config"]["Image"]==image
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("prepared.json")["jarHash"]
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="138"
    core.run("docker","exec",FRONT,"nginx","-t")
    for name,digest in core.read_json("manifest.json")["files"].items():
        assert core.sha(core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+name))==digest
        for encoding in ("identity","gzip"):
            value=core.run("curl","--noproxy","*","-fsS","--retry","5","--retry-all-errors","--retry-delay","1","--max-time","8",
                "--resolve","hoyee.org.cn:443:127.0.0.1","-H","Accept-Encoding: "+encoding,"https://hoyee.org.cn/"+name)
            if encoding=="gzip" and value.startswith(b"\x1f\x8b"):value=gzip.decompress(value)
            assert core.sha(value)==digest,name
    for path in ("shops/test/qianyi","shops/test/qianyi/returns","overview","workspace/label-costs","workspace/costs/orders"):
        status=core.run("curl","--noproxy","*","-sS","-o","/dev/null","-w","%{http_code}","--max-time","8",
            "--resolve","hoyee.org.cn:443:127.0.0.1","https://hoyee.org.cn/api/v1/finance/"+path).decode()
        assert status=="401"
    print("Live Qianyi assets, schema, access guards and backend health verified.")

def deploy():
    assert not (ROOT/"activation.json").exists()
    for table,condition in (
        ("finance_sync_states","status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)"),
        ("finance_ad_accounts","status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)"),
        ("product_publish_workflows","status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)"),
        ("finance_authorization_sessions","status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)")
    ):
        assert base.sql("SELECT COUNT(*) FROM "+table+" WHERE "+condition)=="0","Finance request in progress: "+table
    assert LABEL_LEASE is not None
    assert base.sql("SELECT COUNT(*) FROM finance_label_worker_lock WHERE id=1 AND lease_token='"+LABEL_LEASE+"' AND lease_until>UTC_TIMESTAMP(6)")=="1","Invoice worker was not safely reserved"
    before,_=previous.baseline();base.release.idle()
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="138"
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
    core.write_json(ROOT/"activation.json",{"images":previous.IMAGES,"rollbackImages":rollback,"schema":138,
        "configurationPreserved":True,"unrelatedJarEntriesPreserved":True,"costBasisDefault":"UNVERIFIED"})

def drain_deploy():
    """Wait for the current invoice commit, then reserve only the idle worker until restart."""
    global LABEL_LEASE
    base.release.idle()
    marker=(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(minutes=15)).strftime("%Y-%m-%d %H:%M:%S.%f")
    LABEL_LEASE=str(uuid.uuid4());original={}
    def literal(value):
        return "CONVERT(0x"+value.encode().hex()+" USING utf8mb4)"
    try:
        deadline=time.monotonic()+600
        while time.monotonic()<deadline:
            found=base.sql("""
                CREATE TEMPORARY TABLE fi_release_pending AS
                SELECT organization_id,user_id,shop_id,channel,next_at FROM finance_sync_states
                WHERE status<>'RUNNING' AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6)) AND next_at<'%s';
                SELECT organization_id,user_id,shop_id,channel,DATE_FORMAT(next_at,'%%Y-%%m-%%d %%H:%%i:%%s.%%f') FROM fi_release_pending;
                UPDATE finance_sync_states st JOIN fi_release_pending p
                  ON st.organization_id=p.organization_id AND st.user_id=p.user_id AND st.shop_id=p.shop_id AND st.channel=p.channel
                  SET st.next_at='%s' WHERE st.next_at=p.next_at AND st.status<>'RUNNING'
                    AND (st.lease_until IS NULL OR st.lease_until<UTC_TIMESTAMP(6));
                DROP TEMPORARY TABLE fi_release_pending;
                """%(marker,marker))
            for line in found.splitlines():
                values=line.split("\t");assert len(values)==5
                original.setdefault(tuple(values[:4]),values[4])
            core.write_json(ROOT/"drain-private.json",{"marker":marker,"labelLease":LABEL_LEASE,"original":[list(k)+[v] for k,v in original.items()]})
            base.sql("UPDATE finance_label_worker_lock SET lease_token='"+LABEL_LEASE+"',lease_until='"+marker+
                "' WHERE id=1 AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))")
            idle=base.sql("SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)")=="0"
            reserved=base.sql("SELECT COUNT(*) FROM finance_label_worker_lock WHERE id=1 AND lease_token='"+LABEL_LEASE+"'")=="1"
            if idle and reserved:
                try:deploy();return
                except AssertionError as failure:
                    if "Finance request in progress" not in str(failure):raise
            time.sleep(1)
        raise RuntimeError("Active tasks did not reach a safe checkpoint; original schedules restored")
    finally:
        statements=[]
        for key,value in original.items():
            statements.append("UPDATE finance_sync_states SET next_at="+literal(value)+" WHERE "+
                " AND ".join(column+"="+literal(item) for column,item in zip(("organization_id","user_id","shop_id","channel"),key))+
                " AND next_at="+literal(marker)+" AND status<>'RUNNING' AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6));")
        if statements:base.sql("\n".join(statements))
        base.sql("UPDATE finance_label_worker_lock SET lease_token=NULL,lease_until=NULL WHERE id=1 AND lease_token='"+LABEL_LEASE+"'")
        core.write_json(ROOT/"drain-restored.json",{"restoredSchedules":len(original),"checkpointChanges":0,"idleInvoiceReservationReleased":True})
        print("Original schedules restored; in-flight leases and checkpoints were not discarded.")

if __name__=="__main__":
    {"snapshot":snapshot,"build":build,"migrate":migrate,"drain-deploy":drain_deploy,"verify":verify}[sys.argv[2]]()
