"""Fix invoice page reads and status filters over the exact live invoice release; no schema/data changes."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

os.umask(0o077)
spec=importlib.util.spec_from_file_location("invoice_release","/opt/heyi/releases/finance-label-invoices-20261030/release-finance-label-invoices.py")
previous=importlib.util.module_from_spec(spec);spec.loader.exec_module(previous)
core,base,ROOT=previous.core,previous.base,previous.ROOT
previous.IMAGES={previous.APP:"ai-commerce-platform:finance-label-pages-20261030",
                 previous.FRONT:"ai-commerce-frontend:finance-label-pages-20261030"}
previous.FAMILIES={"FinanceLabelCosts"}
previous.FILES={"index.html","finance-label-costs.js","finance-costs.css"}
previous.NEW_FILES=set()
previous.MIGRATION="__no_schema_change__"

def snapshot():
    ROOT.mkdir(parents=True,exist_ok=True);assert not (ROOT/"snapshot.json").exists()
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")=="137"
    before={name:core.inspect(name) for name in (previous.APP,previous.FRONT)}
    core.write_json(ROOT/"before-private.json",before)
    jar=core.run("docker","exec",previous.APP,"cat","/app/app.jar");(ROOT/"previous.jar").write_bytes(jar)
    with zipfile.ZipFile(io.BytesIO(jar)) as z:
        result={"jar":core.sha(jar),"expectedClasses":{n:core.sha(z.read(n)) for n in z.namelist() if previous.replaced(n)}}
    folder=ROOT/"baseline-frontend";folder.mkdir();result["expectedFiles"]={}
    for name in sorted(previous.FILES):
        value=core.run("docker","exec",previous.FRONT,"cat","/usr/share/nginx/html/"+name)
        (folder/name).write_bytes(value);result["expectedFiles"][name]=core.sha(value)
    core.write_json(ROOT/"snapshot.json",result)
    print("Exact live invoice release captured; no schema or accounting changes.")

def extract(jar,folder):
    if folder.exists():return
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                path=folder/name.removeprefix("BOOT-INF/classes/");path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(z.read(name))

def probe(mode):
    live=mode=="live";old=mode=="baseline"
    if live:(ROOT/"probe-live.jar").write_bytes(core.run("docker","exec",previous.APP,"cat","/app/app.jar"))
    folder=ROOT/("baseline-classes" if old else "live-classes" if live else "migration-classes")
    extract(ROOT/("previous.jar" if old else "probe-live.jar" if live else "app-image/app.jar"),folder)
    app=core.inspect(previous.APP);db=core.inspect("ai-commerce-mysql");settings=dict(v.split("=",1) for v in app["Config"]["Env"])
    private=ROOT/"probe-private.env"
    try:
        private.write_text("".join(k+"="+settings[k]+"\n" for k in ("DB_URL","DB_USERNAME","DB_PASSWORD")))
        network=next(iter(set(app["NetworkSettings"]["Networks"])&set(db["NetworkSettings"]["Networks"])))
        subprocess.run(["docker","run","--rm","--log-driver","none","--user","0:0","--network",network,
            "--memory","256m","--cpus","1","--env-file",str(private),"-v",str(ROOT)+":/release:ro",
            "-v","/tmp/finance-reporting-months-qa-20261028/lib:/deps:ro","--entrypoint","java",app["Config"]["Image"],
            "-Xmx128m","-cp","/release/runner:/release/"+folder.name+":/deps/*",
            "com.yingdao.aicommerce.finance.FinanceLabelPageProbe","6a61f788-6685-4b64-82b1-eec97ed8cf90",
            *(["baseline"] if old else [])],check=True,timeout=150)
    finally:private.unlink(missing_ok=True)

def deploy():
    assert base.sql("SELECT COUNT(*) FROM finance_label_worker_lock WHERE lease_until>UTC_TIMESTAMP(6)")=="0","Finance request in progress: invoice worker"
    previous.deploy()

base.deploy=deploy
if __name__=="__main__":
    {"snapshot":snapshot,"build":previous.build,"verify":previous.verify,
     "drain-deploy":base.drain_deploy,"probe-baseline":lambda:probe("baseline"),
     "probe-new":lambda:probe("new"),"probe-live":lambda:probe("live")}[sys.argv[2]]()
