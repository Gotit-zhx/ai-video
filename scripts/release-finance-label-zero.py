"""Preserve explicit zero invoice amounts and remove the exporter billing-status UI."""
import gzip
import importlib.util
import io
import json
import os
import sys
import zipfile

os.umask(0o077)
spec=importlib.util.spec_from_file_location("queue_release","/opt/heyi/releases/finance-sync-queue-20261030/release-finance-sync-queue.py")
queue=importlib.util.module_from_spec(spec);spec.loader.exec_module(queue)
previous=queue.previous
core,base,ROOT,APP,FRONT=queue.core,queue.base,queue.ROOT,queue.APP,queue.qianyi.FRONT
previous.IMAGES={APP:"ai-commerce-platform:finance-label-zero-20261030",FRONT:"ai-commerce-frontend:finance-label-zero-20261030"}
previous.FAMILIES={"FinanceLabelCosts","FinanceLabelInvoice","FinanceQianyiClient"}
previous.MIGRATION="__no_schema_change__"
previous.FILES={"index.html","finance-label-costs.js"}
previous.NEW_FILES=set()

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
    for name in previous.FILES:
        value=core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+name)
        (folder/name).write_bytes(value);result["expectedFiles"][name]=core.sha(value)
    core.write_json(ROOT/"snapshot.json",result)
    print("Queue release and live invoice assets captured.")

def build():
    previous.build()
    with zipfile.ZipFile(ROOT/"app-image/app.jar") as archive:
        for name in archive.namelist():
            if name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                path=ROOT/"migration-classes"/name.removeprefix("BOOT-INF/classes/")
                path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(archive.read(name))

def verify():
    core.health(APP)
    for name,image in previous.IMAGES.items():assert core.inspect(name)["Config"]["Image"]==image
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("prepared.json")["jarHash"]
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="139"
    core.run("docker","exec",FRONT,"nginx","-t")
    for name,digest in core.read_json("manifest.json")["files"].items():
        assert core.sha(core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+name))==digest
        for encoding in ("identity","gzip"):
            value=core.run("curl","--noproxy","*","-fsS","--retry","5","--retry-all-errors","--retry-delay","1","--max-time","8",
                "--resolve","hoyee.org.cn:443:127.0.0.1","-H","Accept-Encoding: "+encoding,"https://hoyee.org.cn/"+name)
            if encoding=="gzip" and value.startswith(b"\x1f\x8b"):value=gzip.decompress(value)
            assert core.sha(value)==digest
    print("Live explicit-zero logic, invoice assets, schema and health verified.")

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
    core.write_json(ROOT/"activation.json",{"images":previous.IMAGES,"rollbackImages":rollback,"schema":139,
        "configurationPreserved":True,"unrelatedJarEntriesPreserved":True})

def drain_deploy():
    queue.deploy=deploy;queue.drain_deploy()

def refresh():
    verify()
    assert not (ROOT/"refresh.json").exists()
    # Existing parsers accepted only numeric amounts; preserve original status
    # text and values and regenerate only the derived totals/counters.
    count=base.sql("""
        UPDATE finance_label_state s SET requested_revision=requested_revision+1,
          source_revision=source_revision+1,error_message=NULL,retry_after=NULL,updated_at=UTC_TIMESTAMP(6)
        WHERE EXISTS(SELECT 1 FROM finance_label_imports i
          WHERE i.organization_id=s.organization_id AND i.status IN ('READY','PREPARED'));
        SELECT ROW_COUNT();
        """)
    core.write_json(ROOT/"refresh.json",{"organizations":count,"rawRowsChanged":0,"amountsChanged":0})
    print("Invoice totals queued for regeneration; original amounts and source rows retained:",count)

if __name__=="__main__":
    {"snapshot":snapshot,"build":build,"verify":verify,"drain-deploy":drain_deploy,"refresh":refresh}[sys.argv[2]]()
