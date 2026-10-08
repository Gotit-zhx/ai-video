"""Publish job-bound sourcing, connector and the reviewed controller on the exact live baseline.

Usage: python3 release-passive-pool.py /opt/heyi/releases/<release>
The release contains staged/frontend, staged/connector, expected-baseline.json,
manifest.json and code-commit. SQLite records survive rollback and app upgrades.
"""
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import sqlite3
import subprocess
import sys
import time
import zipfile

root = Path(sys.argv[1]).resolve()
expected = json.loads((root / "expected-baseline.json").read_text())
manifest = json.loads((root / "manifest.json").read_text())
files = ["index.html", "hr-workspace.js", "hr-workspace.css", "hr-passive.js", "hr-sourcing.js"]
connector_files = ["connector.py", "passive_pool.py", "hr_sourcing.py"]
for name in ("hr-demands.js",):
    if "frontend/"+name in manifest:
        files.append(name)
for name in ("recruitment_demands.py", "resume_storage.py"):
    if "connector/"+name in manifest:
        connector_files.append(name)
container = "ai-commerce-frontend"
bin_dir = Path("/opt/heyi/boss-agent/bin")
backup = root / "rollback"
if (root / "activation.json").exists():
    raise SystemExit("This release has already been published")
backup.mkdir(exist_ok=True)


def run(*args, **kwargs):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, **kwargs)


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def public_get(path):
    # Container replacement can briefly interrupt TLS before nginx is ready.
    # Retry only idempotent deployment health reads, never business operations.
    for attempt in range(15):
        try:
            return run("curl", "--noproxy", "*", "-fsS", "--max-time", "5", "--resolve",
                       "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/" + path)
        except subprocess.CalledProcessError:
            if attempt == 14:
                raise
            time.sleep(1)


def processes():
    return {name: {"id": inspect(name)["Id"], "started": inspect(name)["State"]["StartedAt"]}
            for name in ["heyi-pi-runtime", "heyi-commerce-image-processor"]}


def require_idle():
    query = ";".join([
        "SELECT COUNT(*) FROM ai_video_task_items WHERE status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING')",
        "SELECT COUNT(*) FROM video_scripts WHERE status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
        "SELECT COUNT(*) FROM product_image_processing_jobs WHERE status IN ('PENDING','QUEUED','RUNNING','PROCESSING')",
        "SELECT COUNT(*) FROM product_publish_workflows WHERE status IN ('PENDING','QUEUED','RUNNING','PUBLISHING','PROCESSING_IMAGES')",
        "SELECT COUNT(*) FROM commerce_batch_executions WHERE status IN ('QUEUED','RUNNING','PAUSING')",
    ])
    result = run("docker","exec","ai-commerce-mysql","sh","-c",
                 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB -e "$1"',"query",query).decode()
    assert not any(int(value) for value in result.split()), "Wait for active business jobs before restarting app"
    with sqlite3.connect("/opt/heyi/boss-agent/runtime/talent-pool.sqlite3") as db:
        for table,column,state in [("recruitment_demands","ai_status","running"),("hr_resume_pool","ai_state","running"),
                ("hr_resume_commands","status","sending"),("hr_talent_messages","status","sending")]:
            assert db.execute(f"SELECT count(*) FROM {table} WHERE {column}=?",(state,)).fetchone()[0]==0,"Wait for HR work: "+table



def app_compose(image_name):
    labels = app_before["Config"]["Labels"]
    override = root / "compose.app.json"
    override.write_text(json.dumps({"services": {labels["com.docker.compose.service"]: {"image": image_name}}}))
    command = ["docker","compose","--project-directory",labels["com.docker.compose.project.working_dir"],
               "-p",labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f",file]
    run(*command,"-f",str(override),"up","-d","--no-deps","--no-build","--pull","never",labels["com.docker.compose.service"])


def wait_app():
    for _ in range(50):
        try:
            health=json.loads(run("docker","exec","ai-commerce-app","curl","-fsS","--max-time","2",
                                  "http://127.0.0.1:8080/actuator/health"))
            if health.get("status")=="UP":
                return
        except (subprocess.CalledProcessError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError("Application did not become healthy")


before = inspect(container)
app_before = inspect("ai-commerce-app")
preserved = processes()
environment = run("systemctl", "show", "heyi-boss-connector", "-p", "Environment", "--value").decode()
assert "APP_HR_DESKTOP_ONLY=1" in environment, "Server BOSS execution must already be disabled"
assert Path("/opt/heyi/boss-agent/runtime/resume-auto-pause").exists(), "Do not restart legacy resume polling"
for relative, digest in manifest.items():
    assert sha((root / "staged" / relative).read_bytes()) == digest, "Staged file mismatch: " + relative
for name in files:
    if name not in expected:
        continue
    live = run("docker", "exec", container, "cat", "/usr/share/nginx/html/" + name)
    assert sha(live) == expected[name], "Frontend drift: " + name
    (backup / name).write_bytes(live)
for name in connector_files:
    if name in expected:
        assert sha((bin_dir/name).read_bytes()) == expected[name], "Connector drift: "+name
    else:
        assert not (bin_dir/name).exists(), "New module already exists: "+name
for name in connector_files:
    if (bin_dir / name).exists():
        shutil.copy2(bin_dir / name, backup / name)
    run("/opt/heyi/boss-agent/venv/bin/python", "-m", "py_compile", str(root / "staged/connector" / name))
db_path = Path("/opt/heyi/boss-agent/runtime/talent-pool.sqlite3")
if db_path.exists():
    with sqlite3.connect(f"file:{db_path}?mode=ro", uri=True) as source, sqlite3.connect(backup / "talent-pool.sqlite3") as destination:
        source.backup(destination)
    (backup / "talent-pool.sqlite3").chmod(0o600)

with sqlite3.connect(db_path) as db:
    assert db.execute("SELECT COUNT(*) FROM recruitment_demands WHERE ai_status='running'").fetchone()[0] == 0,"Demand generation is active"
    passive_before=list(db.execute("SELECT id,enabled FROM hr_passive_rules ORDER BY id"))

# Replace only the reviewed HR controller to the exact live JAR; retain every other byte
# of class/resource content, including earlier production-only fixes.
run("docker","cp","ai-commerce-app:/app/app.jar",str(backup/"app.jar"))
assert sha((backup/"app.jar").read_bytes())==expected["app.jar"],"Application JAR drift"
prefix="BOOT-INF/classes/com/yingdao/aicommerce/recruitment/"
class_files=[root/"staged/java/RecruitmentAiController.class"]
assert all(file.is_file() for file in class_files)
app_dir=root/"app-image"
app_dir.mkdir(exist_ok=True)
with zipfile.ZipFile(backup/"app.jar") as old, zipfile.ZipFile(app_dir/"app.jar","w") as new:
    assert all(sha(old.read(prefix+file.name)) == expected[file.name] for file in class_files),"Existing HR controller drift"
    changed = {prefix+file.name for file in class_files}
    for info in old.infolist():
        if info.filename not in changed:
            new.writestr(info,old.read(info.filename))
    for file in class_files:
        new.writestr(prefix+file.name,file.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
with zipfile.ZipFile(backup/"app.jar") as old, zipfile.ZipFile(app_dir/"app.jar") as new:
    assert all(old.read(name)==new.read(name) for name in old.namelist() if name not in changed),"Unrelated JAR entry changed"
app_image="ai-commerce-platform:"+root.name
(app_dir/"Dockerfile").write_text("FROM "+app_before["Image"]+"\nCOPY app.jar /app/app.jar\n")
run("docker","build","-t",app_image,str(app_dir))

image_dir = root / "frontend-image"
image_dir.mkdir(exist_ok=True)
for name in files:
    shutil.copy2(root / "staged/frontend" / name, image_dir / name)
nginx = run("docker", "exec", container, "cat", "/etc/nginx/conf.d/default.conf")
(backup / "nginx.conf").write_bytes(nginx)
(image_dir / "nginx.conf").write_bytes(nginx)
image = "ai-commerce-frontend:" + root.name
(image_dir / "Dockerfile").write_text(
    "FROM " + before["Image"] + "\nCOPY " + " ".join(files)
    + " /usr/share/nginx/html/\nCOPY nginx.conf /etc/nginx/conf.d/default.conf\n")
run("docker", "build", "-t", image, str(image_dir))
labels = before["Config"]["Labels"]
service = labels["com.docker.compose.service"]
compose = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
           "-p", labels["com.docker.compose.project"]]
for file in labels["com.docker.compose.project.config_files"].split(","):
    compose += ["-f", file]
override = root / "compose.passive.json"
override.write_text(json.dumps({"services": {service: {"image": image}}}, indent=2))
env = {**os.environ, "HR_FRONTEND_IMAGE": before["Config"]["Image"]}
run(*compose, "-f", str(override), "config", "-q", env=env)
network = next(iter(before["NetworkSettings"]["Networks"]))
run("docker", "run", "--rm", "--network", network, "--entrypoint", "nginx",
    "-v", "/etc/letsencrypt:/etc/letsencrypt:ro", "-v", "/opt/heyi/shared/certbot-webroot:/var/www/certbot:ro", image, "-t")
owner = pwd.getpwnam("heyi-boss")
frontend_changed = False
app_changed = False
try:
    require_idle()
    app_changed=True
    app_compose(app_image)
    wait_app()
    after_app=inspect("ai-commerce-app")
    assert sorted(after_app["Config"]["Env"])==sorted(app_before["Config"]["Env"]),"Application environment changed"
    mounts=lambda value:sorted((m["Source"],m["Destination"],m["RW"]) for m in value["Mounts"])
    assert mounts(after_app)==mounts(app_before),"Application mounts changed"
    for name in reversed(connector_files):
        target = bin_dir / name
        temporary = target.with_suffix(".py.new")
        shutil.copy2(root / "staged/connector" / name, temporary)
        os.chown(temporary, owner.pw_uid, owner.pw_gid)
        temporary.chmod(0o640)
        os.replace(temporary, target)
    run("systemctl", "restart", "heyi-boss-connector")
    run("systemctl", "is-active", "--quiet", "heyi-boss-connector")
    url = "https://hoyee.org.cn/api/v1/hr/pool"
    for attempt in range(10):
        status = run("curl", "--noproxy", "*", "-sS", "-o", "/dev/null", "-w", "%{http_code}",
                     "--max-time", "5", "--resolve", "hoyee.org.cn:443:127.0.0.1", url).decode()
        if status == "401":
            break
        time.sleep(.5)
    assert status == "401", "Pool endpoint must reject unauthenticated requests"
    frontend_changed = True
    run(*compose, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never", service, env=env)
    for name in files:
        served = public_get(name)
        assert sha(served) == manifest["frontend/" + name], "Public asset mismatch: " + name
    for route in ("demands", "resume-pool", "sourcing"):
        status=run("curl","--noproxy","*","-sS","-o","/dev/null","-w","%{http_code}","--max-time","5",
            "--resolve","hoyee.org.cn:443:127.0.0.1","https://hoyee.org.cn/api/v1/hr/"+route).decode()
        assert status=="401","Unauthenticated HR access: "+route
    with sqlite3.connect(db_path) as db:
        assert list(db.execute("SELECT id,enabled FROM hr_passive_rules ORDER BY id")) == passive_before,"Existing workflow settings changed"
    assert processes() == preserved, "Unrelated runtime process changed"
    assert any(m["Source"] == "/opt/heyi/shared/hr-desktop-downloads"
               for m in inspect(container)["Mounts"]), "Assistant installer mount missing"
except BaseException:
    for name in connector_files:
        if (backup / name).exists():
            shutil.copy2(backup / name, bin_dir / name)
    run("systemctl", "restart", "heyi-boss-connector")
    if app_changed:
        app_compose(app_before["Image"])
        wait_app()
    if frontend_changed:
        override.write_text(json.dumps({"services": {service: {"image": before["Image"]}}}))
        run(*compose, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never", service, env=env)
    raise
(root / "activation.json").write_text(json.dumps({
    "commit": (root / "code-commit").read_text().strip(), "frontendImage": inspect(container)["Image"],
    "appImage": inspect("ai-commerce-app")["Image"],
    "preservedServices": preserved, "files": manifest,
    "dataFile": str(db_path), "bossOperations": False
}, indent=2))
cleanup = []
for artifact in (app_dir / "app.jar", root / "baseline.jar"):
    if artifact.is_file():
        if artifact.name == "baseline.jar":
            assert sha(artifact.read_bytes()) == expected["app.jar"]
        cleanup.append({"path": str(artifact), "bytes": artifact.stat().st_size})
        artifact.unlink()
# Keep the rollback JAR; generated image input and transfer archives are redundant
# once the current container, source hashes and rollback copy are verified.
archive = Path("/tmp") / (root.name + ".tar.gz")
if archive.is_file() and not archive.is_symlink():
    cleanup.append({"path": str(archive), "bytes": archive.stat().st_size})
    archive.unlink()
(root / "cleanup.json").write_text(json.dumps(cleanup, indent=2))
print("Recruitment stream fix activated; frontend verified; app/connector healthy; unrelated JAR entries and image/video runtime processes preserved")
