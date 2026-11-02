"""Publish bounded asynchronous invoice imports over the exact live application and assets."""
import gzip
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

os.umask(0o077)
ROOT=Path(sys.argv[1]).resolve()
spec=importlib.util.spec_from_file_location("finance_base","/opt/heyi/releases/finance-groups-20261022/release-finance-company-groups.py")
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
core,APP,FRONT=base.core,base.APP,base.FRONT
IMAGES={APP:"ai-commerce-platform:finance-label-invoices-20261030",FRONT:"ai-commerce-frontend:finance-label-invoices-20261030"}
FAMILIES={"FinanceCostController","FinanceCostExport","FinanceCostReport","FinanceFields","FinanceOrderCharges",
          "FinanceOrderCosts","FinanceProfitReport","FinanceRepository","FinanceShopDeletion","FinanceReadCache",
          "FinanceLabelCosts","FinanceLabelInvoice","FinanceInvoiceWorkbook"}
FILES={"index.html","finance-workspace.js","finance-costs.js","finance-charges.js","finance-costs.css","finance-label-costs.js"}
NEW_FILES={"finance-label-costs.js"}
PREFIX="BOOT-INF/classes/com/yingdao/aicommerce/finance/"
MIGRATION="BOOT-INF/classes/db/migration/V137__finance_label_invoice_imports.sql"

def replaced(name):
    return name==MIGRATION or any(name==PREFIX+n+".class" or name.startswith(PREFIX+n+"$") for n in FAMILIES)

def snapshot():
    ROOT.mkdir(parents=True,exist_ok=True)
    assert not (ROOT/"snapshot.json").exists()
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="136"
    before={name:core.inspect(name) for name in (APP,FRONT)}
    core.write_json(ROOT/"before-private.json",before)
    jar=core.run("docker","exec",APP,"cat","/app/app.jar")
    (ROOT/"previous.jar").write_bytes(jar)
    with zipfile.ZipFile(io.BytesIO(jar)) as z:
        assert MIGRATION not in z.namelist()
        expected={"jar":core.sha(jar),"expectedClasses":{n:core.sha(z.read(n)) for n in z.namelist() if replaced(n)}}
    folder=ROOT/"baseline-frontend";folder.mkdir()
    expected["expectedFiles"]={}
    for name in sorted(FILES-NEW_FILES):
        value=core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+name)
        (folder/name).write_bytes(value);expected["expectedFiles"][name]=core.sha(value)
    for name in NEW_FILES:
        core.run("docker","exec",FRONT,"sh","-c","test ! -f /usr/share/nginx/html/"+name)
    core.run("docker","exec",APP,"sh","-c","test -w /data/automation-attachments")
    core.write_json(ROOT/"snapshot.json",expected)
    print("Captured exact live backend/assets; persistent import directory is writable.")

def baseline():
    before=core.read_json("before-private.json");snapshot=core.read_json("snapshot.json")
    for name in (APP,FRONT):assert core.inspect(name)["Id"]==before[name]["Id"]
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==snapshot["jar"]
    for name,digest in snapshot["expectedFiles"].items():
        assert core.sha(core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+name))==digest,name
    return before,snapshot

def build():
    assert not (ROOT/"prepared.json").exists()
    before,snapshot=baseline();manifest=core.read_json("manifest.json")
    assert manifest["expectedClasses"]==snapshot["expectedClasses"]
    assert manifest["expectedFiles"]==snapshot["expectedFiles"]
    folder=ROOT/"app-image";folder.mkdir()
    with zipfile.ZipFile(ROOT/"previous.jar") as old,zipfile.ZipFile(folder/"app.jar","w") as new:
        new.comment=old.comment
        for entry in old.infolist():
            if not replaced(entry.filename):new.writestr(entry,old.read(entry.filename))
        for name,digest in manifest["classes"].items():
            assert replaced(name);value=(ROOT/"classes"/name.removeprefix("BOOT-INF/classes/")).read_bytes()
            assert core.sha(value)==digest;new.writestr(name,value,compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(ROOT/"previous.jar") as old,zipfile.ZipFile(folder/"app.jar") as new:
        assert set(new.namelist())=={n for n in old.namelist() if not replaced(n)}|set(manifest["classes"])
        assert all(old.read(n)==new.read(n) for n in old.namelist() if not replaced(n))
    (folder/"app.jar").chmod(0o644)
    (folder/"Dockerfile").write_text("FROM "+before[APP]["Image"]+"\nCOPY app.jar /app/app.jar\n")
    core.run("docker","build","-t",IMAGES[APP],str(folder))
    core.run("docker","run","--rm","--log-driver","none","--entrypoint","sh",IMAGES[APP],"-c","test -r /app/app.jar")
    front=ROOT/"frontend-image";front.mkdir();lines=["FROM "+before[FRONT]["Image"]]
    assert set(manifest["files"])==FILES
    for name,digest in manifest["files"].items():
        value=(ROOT/"frontend"/name).read_bytes();assert core.sha(value)==digest
        (front/name).write_bytes(value);(front/(name+".gz")).write_bytes(gzip.compress(value,mtime=0))
        for target in (name,name+".gz"):
            (front/target).chmod(0o644);lines.append("COPY "+target+" /usr/share/nginx/html/"+target)
    (front/"Dockerfile").write_text("\n".join(lines)+"\n")
    core.run("docker","build","-t",IMAGES[FRONT],str(front))
    core.run("docker","run","--rm","--log-driver","none","--volumes-from",FRONT,IMAGES[FRONT],"nginx","-t")
    core.write_json(ROOT/"prepared.json",{"images":IMAGES,"jarHash":core.sha((folder/"app.jar").read_bytes())})
    print("Reviewed backend and frontend overlays built; unrelated classes, dependencies and assets preserved.")

def java_run(class_name,*arguments,volumes=False):
    app=core.inspect(APP);db=core.inspect("ai-commerce-mysql");private=ROOT/"operation-private.env"
    settings=dict(v.split("=",1) for v in app["Config"]["Env"])
    resources=ROOT/"migration-classes"
    if not resources.exists():
        with zipfile.ZipFile(ROOT/"app-image/app.jar") as z:
            for name in z.namelist():
                if name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                    path=resources/name.removeprefix("BOOT-INF/classes/");path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(z.read(name))
    try:
        private.write_text("".join(k+"="+settings[k]+"\n" for k in ("DB_URL","DB_USERNAME","DB_PASSWORD")))
        network=next(iter(set(app["NetworkSettings"]["Networks"])&set(db["NetworkSettings"]["Networks"])))
        command=["docker","run","--rm","--log-driver","none","--user","0:0","--network",network,
                 "--memory","384m","--cpus","1","--env-file",str(private),"-v",str(ROOT)+":/release:ro",
                 "-v","/tmp/finance-reporting-months-qa-20261028/lib:/deps:ro"]
        if volumes:
            core.run("docker","exec",APP,"mkdir","-p","/data/automation-attachments/finance-label-imports")
            command+=["--volumes-from",APP,"-v","/tmp/finance-label-invoices-qa-20261030/monthly-source.xlsx:/invoice.xlsx:ro"]
        command+=["--entrypoint","java",IMAGES[APP],"-Xmx192m","-cp","/release/runner:/release/migration-classes:/deps/*",class_name,*arguments]
        subprocess.run(command,check=True,timeout=180)
    finally:private.unlink(missing_ok=True)

def migrate():
    baseline()
    java_run("com.yingdao.aicommerce.finance.FinanceLabelMigration")
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="137"
    core.health(APP)
    print("Additive invoice migration completed with existing service available.")

def verify():
    core.health(APP)
    for name,image in IMAGES.items():assert core.inspect(name)["Config"]["Image"]==image
    assert core.sha(core.run("docker","exec",APP,"cat","/app/app.jar"))==core.read_json("prepared.json")["jarHash"]
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="137"
    core.run("docker","exec",FRONT,"nginx","-t")
    for name,digest in core.read_json("manifest.json")["files"].items():
        assert core.sha(core.run("docker","exec",FRONT,"cat","/usr/share/nginx/html/"+name))==digest
        for encoding in ("identity","gzip"):
            value=core.run("curl","--noproxy","*","-fsS","--retry","5","--retry-all-errors","--retry-delay","1",
                "--max-time","8","--resolve","hoyee.org.cn:443:127.0.0.1","-H","Accept-Encoding: "+encoding,"https://hoyee.org.cn/"+name)
            if encoding=="gzip":value=gzip.decompress(value)
            assert core.sha(value)==digest,name
    for path in ("label-costs","costs/orders"):
        status=core.run("curl","--noproxy","*","-sS","-o","/dev/null","-w","%{http_code}","--max-time","8",
            "--resolve","hoyee.org.cn:443:127.0.0.1","https://hoyee.org.cn/api/v1/finance/workspace/"+path).decode()
        assert status=="401"
    print("Live invoice assets, schema, access guards and backend health verified.")

def deploy():
    assert not (ROOT/"activation.json").exists()
    before,_=baseline();base.release.idle()
    assert base.sql("SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)")=="0","Finance request in progress"
    assert base.sql("SELECT COUNT(*) FROM finance_ad_accounts WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)")=="0"
    assert base.sql("SELECT COUNT(*) FROM product_publish_workflows WHERE status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)")=="0"
    assert base.sql("SELECT COUNT(*) FROM finance_authorization_sessions WHERE status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)")=="0"
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="137"
    preserved={c["Name"]:c["Id"] for c in json.loads(core.run("docker","inspect",*core.run("docker","ps","-q").decode().split())) if c["Name"].lstrip("/") not in IMAGES}
    rollback={name:image+"-before" for name,image in IMAGES.items()}
    for name in IMAGES:core.run("docker","tag",before[name]["Image"],rollback[name])
    try:
        core.activate(before,APP,IMAGES[APP]);core.health(APP)
        core.activate(before,FRONT,IMAGES[FRONT]);verify()
        for name in IMAGES:assert core.configuration(core.inspect(name))==core.configuration(before[name])
        assert all(core.inspect(name)["Id"]==identifier for name,identifier in preserved.items())
    except BaseException:
        for name in IMAGES:core.activate(before,name,rollback[name])
        core.health(APP);raise
    core.write_json(ROOT/"activation.json",{"images":IMAGES,"rollbackImages":rollback,"schema":137,
        "configurationPreserved":True,"unrelatedJarEntriesPreserved":True})

def upload():
    verify()
    java_run("com.yingdao.aicommerce.finance.FinanceLabelUpload","6a61f788-6685-4b64-82b1-eec97ed8cf90",
             "/invoice.xlsx","9月(2).xlsx",volumes=True)

def import_result():
    java_run("com.yingdao.aicommerce.finance.FinanceLabelVerify","6a61f788-6685-4b64-82b1-eec97ed8cf90")

base.deploy=deploy
if __name__=="__main__":
    try:
        {"snapshot":snapshot,"build":build,"migrate":migrate,"drain-deploy":base.drain_deploy,"verify":verify,"upload":upload,"import-result":import_result}[sys.argv[2]]()
    except subprocess.CalledProcessError:
        raise SystemExit("Invoice release phase failed; preserve the reviewed baseline and inspect before retry.")
