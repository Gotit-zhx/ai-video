"""Publish local media fixes on the verified live application baseline."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import zipfile

root = Path(sys.argv[1]).resolve()
expected = json.loads((root / "expected.json").read_text())
manifest = json.loads((root / "manifest.json").read_text())
rollback = root / "rollback"
rollback.mkdir(exist_ok=True)
assert not (root / "activation.json").exists(), "Release already activated"
assert shutil.disk_usage(root).free >= 2 * 1024**3, "Insufficient release storage"
entries = {
    "VideoScriptController.class": "BOOT-INF/classes/com/yingdao/aicommerce/video/web/VideoScriptController.class",
    "OssStorageService.class": "BOOT-INF/classes/com/yingdao/aicommerce/storage/OssStorageService.class",
}
if expected.get("AiVideoTaskService.class"):
    entries.update({
        "AiVideoTaskService.class": "BOOT-INF/classes/com/yingdao/aicommerce/video/application/AiVideoTaskService.class",
        "AiVideoTaskController.class": "BOOT-INF/classes/com/yingdao/aicommerce/video/web/AiVideoTaskController.class",
        "GeneralVideoTaskController.class": "BOOT-INF/classes/com/yingdao/aicommerce/video/web/GeneralVideoTaskController.class",
    })
front_files = ("app.js", "index.html")


def run(*args, **kwargs):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, **kwargs)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def mounts(info):
    return sorted((m["Source"], m["Destination"], m["RW"]) for m in info["Mounts"])


def require_idle():
    query = ";".join([
        "SELECT COUNT(*) FROM ai_video_task_items WHERE status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING')",
        "SELECT COUNT(*) FROM video_scripts WHERE status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
        "SELECT COUNT(*) FROM product_image_processing_jobs WHERE status IN ('PENDING','QUEUED','RUNNING','PROCESSING')",
        "SELECT COUNT(*) FROM product_publish_workflows WHERE status IN ('PENDING','QUEUED','RUNNING','PUBLISHING','PROCESSING_IMAGES')",
        "SELECT COUNT(*) FROM commerce_batch_executions WHERE status IN ('QUEUED','RUNNING','PAUSING')",
    ])
    counts = run(
        "docker", "exec", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB -e "$1"',
        "query", query).decode().split()
    assert len(counts) == 5 and not any(int(n) for n in counts), "Business work is active"


def replace(before, tag, suffix):
    labels = before["Config"]["Labels"]
    service = labels["com.docker.compose.service"]
    override = root / ("compose." + suffix + ".json")
    override.write_text(json.dumps({"services": {service: {"image": tag}}}))
    command = ["docker", "compose", "--project-directory",
               labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f", file]
    environment = {**os.environ, "HR_FRONTEND_IMAGE": frontend["Config"]["Image"]}
    run(*command, "-f", str(override), "config", "-q", env=environment)
    run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build",
        "--pull", "never", service, env=environment)


def wait_app():
    for attempt in range(240):
        try:
            health = json.loads(run("docker", "exec", "ai-commerce-app", "curl", "-fsS",
                                    "--max-time", "2", "http://127.0.0.1:8080/actuator/health"))
            if health.get("status") == "UP":
                return
        except (subprocess.CalledProcessError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError("Application health failed")


def public(path):
    for attempt in range(15):
        try:
            return run("curl", "--noproxy", "*", "-fsS", "--max-time", "5", "--resolve",
                       "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/" + path)
        except subprocess.CalledProcessError:
            if attempt == 14:
                raise
            time.sleep(1)


app, frontend = inspect("ai-commerce-app"), inspect("ai-commerce-frontend")
preserved = {i["Name"]: i["Id"] for i in json.loads(run(
    "docker", "inspect", *run("docker", "ps", "-q").decode().split()))
    if i["Name"] not in {"/ai-commerce-app", "/ai-commerce-frontend"}}
run("docker", "cp", "ai-commerce-app:/app/app.jar", str(rollback / "app.jar"))
assert sha((rollback / "app.jar").read_bytes()) == expected["app.jar"], "Live JAR drift"
for name in front_files:
    current = run("docker", "exec", "ai-commerce-frontend", "cat", "/usr/share/nginx/html/" + name)
    assert sha(current) == expected[name], "Live frontend drift: " + name
    (rollback / name).write_bytes(current)
    assert sha((root / "staged" / name).read_bytes()) == manifest[name], "Staged asset mismatch"
new_classes = {}
for name, entry in entries.items():
    content = (root / "staged" / name).read_bytes()
    assert sha(content) == manifest[name], "Staged class mismatch: " + name
    new_classes[entry] = content
app_dir, front_dir = root / "app-image", root / "frontend-image"
app_dir.mkdir(exist_ok=True)
front_dir.mkdir(exist_ok=True)
with zipfile.ZipFile(rollback / "app.jar") as old, zipfile.ZipFile(app_dir / "app.jar", "w") as new:
    for name, entry in entries.items():
        assert sha(old.read(entry)) == expected[name], "Live class drift: " + name
    for info in old.infolist():
        new.writestr(info, new_classes.get(info.filename, old.read(info.filename)))
with zipfile.ZipFile(rollback / "app.jar") as old, zipfile.ZipFile(app_dir / "app.jar") as new:
    assert old.namelist() == new.namelist()
    assert all(old.read(n) == new.read(n) for n in old.namelist() if n not in new_classes), "Unrelated JAR change"
app_image = "ai-commerce-platform:" + root.name
front_image = "ai-commerce-frontend:" + root.name
(app_dir / "Dockerfile").write_text("FROM " + app["Image"] + "\nCOPY app.jar /app/app.jar\n")
run("docker", "build", "-t", app_image, str(app_dir))
for name in front_files:
    shutil.copy2(root / "staged" / name, front_dir / name)
(front_dir / "Dockerfile").write_text(
    "FROM " + frontend["Image"] + "\nCOPY " + " ".join(front_files) + " /usr/share/nginx/html/\n")
run("docker", "build", "-t", front_image, str(front_dir))

app_changed = front_changed = False
try:
    require_idle()
    app_changed = True
    replace(app, app_image, "app")
    wait_app()
    front_changed = True
    replace(frontend, front_image, "frontend")
    for name in front_files:
        assert sha(public(name)) == manifest[name], "Published asset mismatch: " + name
    for name, before in (("ai-commerce-app", app), ("ai-commerce-frontend", frontend)):
        after = inspect(name)
        assert mounts(after) == mounts(before), "Mount drift: " + name
        assert sorted(after["Config"]["Env"]) == sorted(before["Config"]["Env"]), "Environment drift"
    for name, ident in preserved.items():
        assert inspect(name)["Id"] == ident, "Other container restarted: " + name
except BaseException:
    if app_changed:
        replace(app, app["Config"]["Image"], "app-rollback")
        wait_app()
    if front_changed:
        replace(frontend, frontend["Config"]["Image"], "frontend-rollback")
    raise
(root / "activation.json").write_text(json.dumps({
    "files": manifest,
    "applicationImage": inspect("ai-commerce-app")["Image"],
    "frontendImage": inspect("ai-commerce-frontend")["Image"],
    "preservedContainers": preserved,
    "otherJarEntriesUnchanged": True,
    "activeBusinessTasksBeforeRestart": 0,
}, indent=2))
print("Local preview release healthy; unrelated JAR content, mounts and services preserved")
