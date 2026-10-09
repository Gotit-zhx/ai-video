"""Publish the HR permission controller on the exact live JAR, preserving other services."""
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import time
import zipfile

root = Path(sys.argv[1]).resolve()
assert not (root / "activation.json").exists(), "Already published"
expected = json.loads((root / "expected.json").read_text())
backup = root / "rollback"
backup.mkdir(exist_ok=False)


def run(*args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT)


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require_idle():
    sql = ";".join([
        "SELECT COUNT(*) FROM ai_video_task_items WHERE status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING')",
        "SELECT COUNT(*) FROM video_scripts WHERE status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
        "SELECT COUNT(*) FROM product_image_processing_jobs WHERE status IN ('PENDING','QUEUED','RUNNING','PROCESSING')",
        "SELECT COUNT(*) FROM product_publish_workflows WHERE status IN ('PENDING','QUEUED','RUNNING','PUBLISHING','PROCESSING_IMAGES')",
        "SELECT COUNT(*) FROM commerce_batch_executions WHERE status IN ('QUEUED','RUNNING','PAUSING')",
    ])
    counts = run("docker", "exec", "ai-commerce-mysql", "sh", "-c",
                 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB -e "$1"',
                 "query", sql).decode().split()
    assert not any(int(value) for value in counts), "Active business operation"
    with sqlite3.connect("file:/opt/heyi/boss-agent/runtime/talent-pool.sqlite3?mode=ro", uri=True) as db:
        for table, condition in [
            ("recruitment_demands", "ai_status='running'"), ("hr_resume_pool", "ai_state='running'"),
            ("hr_sourcing_runs", "state='running'"), ("hr_sourcing_jobs", "profile_state='running'"),
            ("hr_sourcing_contacts", "status='sending'"), ("hr_talent_messages", "status='sending'"),
            ("hr_resume_commands", "status='sending'"),
            ("hr_interview_handoffs", "status IN ('preparing','sending')"),
            ("hr_onboarding_notices", "status IN ('preparing','sending')"),
        ]:
            assert db.execute(f"SELECT COUNT(*) FROM {table} WHERE {condition}").fetchone()[0] == 0, table


before = inspect("ai-commerce-app")
preserved = {info["Name"]: info["Id"] for info in json.loads(
    run("docker", "inspect", *run("docker", "ps", "-q").decode().split()))
    if info["Name"] != "/ai-commerce-app"}
assert shutil.disk_usage(root).free >= 2 * 1024**3
require_idle()
run("docker", "cp", "ai-commerce-app:/app/app.jar", str(backup / "app.jar"))
assert sha((backup / "app.jar").read_bytes()) == expected["jar"], "Live JAR changed"
changed = {
    "BOOT-INF/classes/com/yingdao/aicommerce/recruitment/RecruitmentAiController.class":
        root / "target/classes/com/yingdao/aicommerce/recruitment/RecruitmentAiController.class",
    "BOOT-INF/classes/db/migration/V106__hr_user_access.sql":
        root / "src/main/resources/db/migration/V106__hr_user_access.sql",
}
image_dir = root / "app-image"
image_dir.mkdir(exist_ok=False)
with zipfile.ZipFile(backup / "app.jar") as old, zipfile.ZipFile(image_dir / "app.jar", "w") as new:
    assert sha(old.read(next(iter(changed)))) == expected["controller"], "Controller drift"
    assert list(changed)[1] not in old.namelist(), "Migration collision"
    new.comment = old.comment
    for info in old.infolist():
        if info.filename not in changed:
            new.writestr(info, old.read(info.filename))
    for name, source in changed.items():
        new.writestr(name, source.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
with zipfile.ZipFile(backup / "app.jar") as old, zipfile.ZipFile(image_dir / "app.jar") as new:
    assert set(new.namelist()) == set(old.namelist()) | set(changed)
    assert all(old.read(name) == new.read(name) for name in old.namelist() if name not in changed)
image = "ai-commerce-platform:" + root.name
(image_dir / "Dockerfile").write_text("FROM " + before["Image"] + "\nCOPY app.jar /app/app.jar\n")
run("docker", "build", "-t", image, str(image_dir))
labels = before["Config"]["Labels"]
command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
           "-p", labels["com.docker.compose.project"]]
for name in labels["com.docker.compose.project.config_files"].split(","):
    command += ["-f", name]
override = root / "compose.app.json"


def activate(tag):
    override.write_text(json.dumps({"services": {labels["com.docker.compose.service"]: {"image": tag}}}))
    run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never",
        labels["com.docker.compose.service"])
    for attempt in range(50):
        try:
            health = json.loads(run("docker", "exec", "ai-commerce-app", "curl", "-fsS", "--max-time", "2",
                                    "http://127.0.0.1:8080/actuator/health"))
            if health.get("status") == "UP":
                return
        except (subprocess.CalledProcessError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError("Application did not become healthy")


require_idle()
try:
    activate(image)
    after = inspect("ai-commerce-app")
    mounts = lambda value: sorted((m["Source"], m["Destination"], m["RW"]) for m in value["Mounts"])
    assert mounts(after) == mounts(before)
    assert sorted(after["Config"]["Env"]) == sorted(before["Config"]["Env"])
    assert all(inspect(name)["Id"] == identifier for name, identifier in preserved.items())
    assert run("systemctl", "is-active", "heyi-boss-connector").decode().strip() == "active"
except BaseException:
    activate(before["Config"]["Image"])
    raise
(root / "activation.json").write_text(json.dumps({
    "image": image, "entries": {name: sha(source.read_bytes()) for name, source in changed.items()},
    "preservedContainers": preserved, "unrelatedJarEntriesPreserved": True,
}, indent=2))
shutil.rmtree(image_dir)
(root / "baseline.jar").unlink()
print("HR AI permissions published; app healthy; unrelated JAR entries and services preserved")
