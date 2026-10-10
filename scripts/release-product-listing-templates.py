"""Overlay only reviewed listing files/classes onto the exact production images."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import time
import zipfile

root=Path(sys.argv[1]).resolve()
expected=json.loads((root/"expected.json").read_text())
manifest=json.loads((root/"manifest.json").read_text())
assert not (root/"activation.json").exists()
assert shutil.disk_usage(root).free >= 2*1024**3
backup=root/"rollback"
def run(*args, env=None):
    return subprocess.check_output(args,stderr=subprocess.STDOUT,env=env)
def inspect(name): return json.loads(run("docker","inspect",name))[0]
def sha(data): return hashlib.sha256(data).hexdigest()
def idle():
    queries=[
        "SELECT COUNT(*) FROM ai_video_task_items WHERE status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING')",
        "SELECT COUNT(*) FROM video_scripts WHERE status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
        "SELECT COUNT(*) FROM product_image_processing_jobs WHERE status IN ('PENDING','QUEUED','RUNNING','PROCESSING')",
        "SELECT COUNT(*) FROM product_publish_workflows WHERE status IN ('PENDING','QUEUED','RUNNING','PUBLISHING','PROCESSING_IMAGES')",
        "SELECT COUNT(*) FROM commerce_batch_executions WHERE status IN ('QUEUED','RUNNING','PAUSING')",
    ]
    counts=run("docker","exec","ai-commerce-mysql","sh","-c",
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB -e "$1"',"query",";".join(queries)).decode().split()
    assert all(int(n)==0 for n in counts),"Business operations still active"
    with sqlite3.connect("file:/opt/heyi/boss-agent/runtime/talent-pool.sqlite3?mode=ro",uri=True) as db:
        for table,condition in [
            ("recruitment_demands","ai_status='running'"),("hr_resume_pool","ai_state='running'"),
            ("hr_sourcing_runs","state='running'"),("hr_sourcing_jobs","profile_state='running'"),
            ("hr_sourcing_contacts","status='sending'"),("hr_talent_messages","status='sending'"),
            ("hr_resume_commands","status='sending'"),("hr_interview_handoffs","status IN ('preparing','sending')"),
            ("hr_onboarding_notices","status IN ('preparing','sending')")]:
            assert db.execute(f"SELECT COUNT(*) FROM {table} WHERE {condition}").fetchone()[0]==0,table

names=("ai-commerce-app","heyi-pi-runtime","ai-commerce-frontend")
before={name:inspect(name) for name in names}
preserved={info["Name"]:info["Id"] for info in json.loads(run("docker","inspect",*run("docker","ps","-q").decode().split()))
    if info["Name"].lstrip("/") not in names}
idle()
backup.mkdir(exist_ok=False)
run("docker","cp","ai-commerce-app:/app/app.jar",str(backup/"app.jar"))
assert sha((backup/"app.jar").read_bytes())==expected["jar"],"Live JAR changed"
prefixes=[
    "product/application/ProductCaptureService","product/application/ProductPublishWorkflowService",
    "product/application/ProductListingTemplateService","product/application/ProductListingTitleService",
    "product/web/ProductListingTemplateController","product/web/ProductListingTitleController",
    "product/application/provider/ShopeeOfficialPublishingProvider",
    "product/application/provider/TikTokProductDescription","product/application/provider/TikTokShopOfficialPublishingProvider",
    "config/SecurityConfig"]
changed={}
for prefix in prefixes:
    path=root/"source/target/classes/com/yingdao/aicommerce"/prefix
    classes=list(path.parent.glob(path.name+".class"))+list(path.parent.glob(path.name+"$*.class"))
    assert classes,prefix
    for file in classes: changed["BOOT-INF/classes/"+str(file.relative_to(root/"source/target/classes"))]=file
migration="BOOT-INF/classes/db/migration/V107__product_listing_templates.sql"
changed[migration]=root/"source/src/main/resources/db/migration/V107__product_listing_templates.sql"
def replace_class(name):
    return name==migration or any(name.startswith("BOOT-INF/classes/com/yingdao/aicommerce/"+prefix+"$")
        or name=="BOOT-INF/classes/com/yingdao/aicommerce/"+prefix+".class" for prefix in prefixes)
image_dir=root/"app-image";image_dir.mkdir()
with zipfile.ZipFile(backup/"app.jar") as old,zipfile.ZipFile(image_dir/"app.jar","w") as new:
    assert migration not in old.namelist(),"Migration version collision"
    new.comment=old.comment
    for info in old.infolist():
        if not replace_class(info.filename):new.writestr(info,old.read(info.filename))
    for name,file in changed.items():new.writestr(name,file.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
with zipfile.ZipFile(backup/"app.jar") as old,zipfile.ZipFile(image_dir/"app.jar") as new:
    assert all(old.read(name)==new.read(name) for name in old.namelist() if not replace_class(name))
    assert set(new.namelist())=={name for name in old.namelist() if not replace_class(name)}|set(changed)
images={}
for name,directory,files in [
    ("ai-commerce-app",image_dir,{}),
    ("heyi-pi-runtime",root/"pi-image",{"src/server.js":"/app/src/server.js","src/product-listing-title.js":"/app/src/product-listing-title.js"}),
    ("ai-commerce-frontend",root/"frontend-image",{file:"/usr/share/nginx/html/"+file for file in
        ("index.html","app.js","product-listing-templates.js","product-listing-templates.css")})]:
    directory.mkdir(exist_ok=True)
    copy_lines=["COPY app.jar /app/app.jar"] if name=="ai-commerce-app" else []
    for file,remote in files.items():
        key=name+"/"+file
        try:live=run("docker","exec",name,"cat",remote)
        except subprocess.CalledProcessError:
            assert expected.get(key) is None,key
            live=None
        assert (sha(live) if live is not None else None)==expected.get(key),"Live source changed: "+key
        if live is not None:
            saved=backup/name/file;saved.parent.mkdir(parents=True,exist_ok=True);saved.write_bytes(live)
        source=root/"staged"/name/file
        assert sha(source.read_bytes())==manifest[key],key
        target=directory/file;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
        copy_lines.append("COPY "+file+" "+remote)
    tag=before[name]["Config"]["Image"].split(":")[0]+":capture-templates-20261010"
    (directory/"Dockerfile").write_text("FROM "+before[name]["Image"]+"\n"+"\n".join(copy_lines)+"\n")
    run("docker","build","-t",tag,str(directory));images[name]=tag

def activate(name,tag):
    labels=before[name]["Config"]["Labels"];service=labels["com.docker.compose.service"]
    command=["docker","compose","--project-directory",labels["com.docker.compose.project.working_dir"],"-p",labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):command+=["-f",file]
    override=root/("compose."+name+".json")
    override.write_text(json.dumps({"services":{service:{"image":tag}}}))
    environment = dict(os.environ, HR_FRONTEND_IMAGE=before["ai-commerce-frontend"]["Config"]["Image"])
    run(*command,"-f",str(override),"up","-d","--no-deps","--no-build","--pull","never",service,env=environment)
def health(name):
    for attempt in range(55):
        try:
            if name=="ai-commerce-app":
                result=json.loads(run("docker","exec",name,"curl","-fsS","--max-time","2","http://127.0.0.1:8080/actuator/health"))
                assert result.get("status")=="UP"
            elif name=="heyi-pi-runtime":
                result=json.loads(run("docker","exec",name,"node","-e",
                    "fetch('http://127.0.0.1:8090/health').then(r=>r.json()).then(r=>process.stdout.write(JSON.stringify(r)))"))
                assert result.get("status")=="UP"
            else:
                served=run("curl","--noproxy","*","-fsS","--max-time","4","--resolve","hoyee.org.cn:443:127.0.0.1","https://hoyee.org.cn/app.js")
                assert sha(served)==manifest[name+"/app.js"]
            return
        except (subprocess.CalledProcessError,ValueError,AssertionError):time.sleep(1)
    raise RuntimeError(name+" failed health check")
idle()
activated=[]
try:
    for name in ("heyi-pi-runtime","ai-commerce-app","ai-commerce-frontend"):
        activated.append(name);activate(name,images[name]);health(name)
        after=inspect(name)
        mounts=lambda value:sorted((m["Source"],m["Destination"],m["RW"]) for m in value["Mounts"])
        assert mounts(after)==mounts(before[name])
        assert sorted(after["Config"]["Env"])==sorted(before[name]["Config"]["Env"])
    for key,digest in manifest.items():
        name,file=key.split("/",1)
        remote=("/app/" if name=="heyi-pi-runtime" else "/usr/share/nginx/html/")+file
        assert sha(run("docker","exec",name,"cat",remote))==digest,key
    assert all(inspect(name)["Id"]==identifier for name,identifier in preserved.items())
    assert run("systemctl","is-active","heyi-boss-connector").decode().strip()=="active"
except BaseException:
    rollback_errors=[]
    for name in reversed(activated):
        try:
            activate(name,before[name]["Config"]["Image"])
            if name != "ai-commerce-frontend": health(name)
        except Exception:
            rollback_errors.append(name)
    if rollback_errors: print("Rollback needs recovery for: "+", ".join(rollback_errors),file=sys.stderr)
    raise
(root/"activation.json").write_text(json.dumps({
    "images":images,"manifest":manifest,"javaEntries":{name:sha(file.read_bytes()) for name,file in changed.items()},
    "rollbackImages":{name:before[name]["Config"]["Image"] for name in names},
    "unrelatedJarEntriesPreserved":True,"preservedContainers":preserved},indent=2))
for directory in (root/"app-image",root/"pi-image",root/"frontend-image"):shutil.rmtree(directory)
print("Listing templates and capture AI titles published; all services healthy; original resources preserved")
