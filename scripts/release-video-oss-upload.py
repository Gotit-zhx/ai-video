"""Deploy only the OSS client after baseline, unit, and real-storage checks."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(sys.argv[1]).resolve()
APP = "ai-commerce-app"
FRONT = "ai-commerce-frontend"
PREFIX = "com/yingdao/aicommerce/storage/OssStorageService"


def run(*args, data=None, env=None):
    return subprocess.check_output(args, input=data, stderr=subprocess.STDOUT, env=env)


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def selected(name):
    return name == PREFIX + ".class" or name.startswith(PREFIX + "$")


def idle():
    queries = [
        "SELECT COUNT(*) FROM ai_video_task_items WHERE status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING','VIDEO_GENERATING')",
        "SELECT COUNT(*) FROM ai_video_generation_jobs j JOIN ai_video_tasks t ON t.id=j.task_id WHERE t.status='VIDEO_GENERATING' AND j.status IN ('SUBMITTING','QUEUED','RUNNING','REVIEWING')",
        "SELECT COUNT(*) FROM video_scripts WHERE status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
        "SELECT COUNT(*) FROM product_image_processing_jobs WHERE status IN ('PENDING','QUEUED','RUNNING','PROCESSING')",
        "SELECT COUNT(*) FROM product_publish_workflows WHERE status IN ('PENDING','QUEUED','RUNNING','PUBLISHING','PROCESSING_IMAGES')",
        "SELECT COUNT(*) FROM product_watermark_queue WHERE status IN ('QUEUED','RUNNING')",
        "SELECT COUNT(*) FROM commerce_batch_executions WHERE status IN ('QUEUED','RUNNING','PAUSING')",
    ]
    values = run("docker", "exec", "ai-commerce-mysql", "sh", "-c",
                 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB -e "$1"',
                 "query", ";".join(queries)).decode().split()
    assert len(values) == len(queries) and not any(map(int, values)), "Business work is active"
    with sqlite3.connect("file:/opt/heyi/boss-agent/runtime/talent-pool.sqlite3?mode=ro", uri=True) as db:
        for table, condition in [
            ("recruitment_demands", "ai_status='running'"), ("hr_resume_pool", "ai_state='running'"),
            ("hr_sourcing_runs", "state='running'"), ("hr_sourcing_jobs", "profile_state='running'"),
            ("hr_sourcing_contacts", "status='sending'"), ("hr_talent_messages", "status='sending'"),
            ("hr_resume_commands", "status='sending'"), ("hr_interview_handoffs", "status IN ('preparing','sending')"),
            ("hr_onboarding_notices", "status IN ('preparing','sending')"),
        ]:
            assert db.execute(f"SELECT COUNT(*) FROM {table} WHERE {condition}").fetchone()[0] == 0, table


def activate(before, image, suffix):
    labels = before["Config"]["Labels"]
    service = labels["com.docker.compose.service"]
    override = ROOT / ("compose." + suffix + ".json")
    override.write_text(json.dumps({"services": {service: {"image": image}}}))
    command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f", file]
    env = dict(os.environ, HR_FRONTEND_IMAGE=inspect(FRONT)["Config"]["Image"])
    run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never", service, env=env)


def healthy(require_storage=False):
    for _ in range(120):
        try:
            result = json.loads(run("docker", "exec", APP, "curl", "-fsS", "--max-time", "2",
                                    "http://127.0.0.1:8080/actuator/health"))
            assert result.get("status") == "UP"
            if require_storage:
                logs = run("docker", "logs", "--tail", "300", APP).decode()
                assert "OSS storage verified for private bucket " in logs
            return
        except (subprocess.CalledProcessError, ValueError, AssertionError):
            time.sleep(1)
    raise RuntimeError("Application or OSS startup verification failed")


def failures(reports):
    return sorted((report.get("name"), case.get("name"), failure.get("type"), failure.get("message"))
                  for report in reports for case in report.findall("testcase") for failure in case.findall("failure"))


def main():
    assert not (ROOT / "activation.json").exists(), "Already deployed"
    assert shutil.disk_usage(ROOT).free >= 2 * 1024**3
    expected = json.loads((ROOT / "expected.json").read_text())
    reports = [ET.parse(path).getroot() for path in (ROOT / "source/target/surefire-reports").glob("TEST-*.xml")]
    baseline_reports = [ET.parse(path).getroot() for path in (ROOT / "base/target/surefire-reports").glob("TEST-*.xml")]
    assert reports and baseline_reports and all(int(r.get("errors", 0)) == 0 for r in reports + baseline_reports)
    assert failures(reports) == failures(baseline_reports), "New test failures differ from the unchanged baseline"
    live = next(r for r in reports if r.get("name", "").endswith(".DayanOssLiveTest"))
    assert int(live.get("tests", 0)) > 0 and int(live.get("skipped", 0)) == 0, "Real OSS checks required"
    tests = {key: sum(int(r.get(key, 0)) for r in reports) for key in ["tests", "failures", "errors", "skipped"]}
    tests["baselineFailures"] = len(failures(baseline_reports))
    tests["newFailures"] = 0
    before = inspect(APP)
    containers = json.loads(run("docker", "inspect", *run("docker", "ps", "-q").decode().split()))
    preserved = {c["Name"]: c["Id"] for c in containers if c["Name"] != "/" + APP}
    backup = ROOT / "rollback"
    backup.mkdir(mode=0o700, exist_ok=True)
    run("docker", "cp", APP + ":/app/app.jar", str(backup / "app.jar"))
    assert sha((backup / "app.jar").read_bytes()) == expected["jar"], "Live JAR drift"
    assert sha(run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/app.js")) == expected["frontend"], "Frontend drift"
    classes = ROOT / "source/target/classes"
    changed = {str(p.relative_to(classes)): p.read_bytes() for p in (classes / PREFIX).parent.glob("OssStorageService*.class")}
    assert changed and all(selected(name) for name in changed), "Unexpected overlay classes"
    with zipfile.ZipFile(backup / "app.jar") as old:
        originals = {name.removeprefix("BOOT-INF/classes/"): old.read(name) for name in old.namelist()
                     if name.startswith("BOOT-INF/classes/") and selected(name.removeprefix("BOOT-INF/classes/"))}
        assert originals.keys() == changed.keys(), "Unexpected class structure change"
        for name, data in originals.items():
            base = ROOT / "base/target/classes" / name
            assert base.exists(), "Missing baseline class"
            if base.read_bytes() != data:
                target = ROOT / "liveclasses" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                cls = name.removesuffix(".class").replace("/", ".")
                def disassemble(location):
                    return run("docker", "run", "--rm", "-v", str(ROOT) + ":/release",
                               "maven:3.9-eclipse-temurin-21", "javap", "-c", "-p",
                               "-classpath", "/release/" + location, cls)
                assert disassemble("liveclasses") == disassemble("base/target/classes"), "Live Java baseline differs: " + name
        image_dir = ROOT / "app-image"
        image_dir.mkdir(exist_ok=True)
        with zipfile.ZipFile(image_dir / "app.jar", "w") as new:
            for info in old.infolist():
                replacement = changed.get(info.filename.removeprefix("BOOT-INF/classes/"))
                new.writestr(info, replacement if replacement is not None else old.read(info.filename))
    with zipfile.ZipFile(backup / "app.jar") as old, zipfile.ZipFile(image_dir / "app.jar") as new:
        assert old.namelist() == new.namelist()
        assert all(old.read(name) == new.read(name) for name in old.namelist()
                   if not selected(name.removeprefix("BOOT-INF/classes/"))), "Unrelated JAR changes"
    image = "ai-commerce-platform:" + ROOT.name
    (image_dir / "Dockerfile").write_text("FROM " + before["Image"] + "\nCOPY app.jar /app/app.jar\n")
    run("docker", "build", "-t", image, str(image_dir))
    idle()
    assert sha(run("docker", "exec", APP, "cat", "/app/app.jar")) == expected["jar"], "Concurrent deployment"
    activated = False
    try:
        activated = True
        activate(before, image, "activate")
        healthy(require_storage=True)
        after = inspect(APP)
        mounts = lambda c: sorted((m["Source"], m["Destination"], m["RW"]) for m in c["Mounts"])
        assert mounts(before) == mounts(after), "Mount drift"
        assert sorted(before["Config"]["Env"]) == sorted(after["Config"]["Env"]), "Environment drift"
        assert all(inspect(name)["Id"] == identifier for name, identifier in preserved.items()), "Other container restarted"
        status = run("curl", "--noproxy", "*", "-sS", "--max-time", "10", "--resolve",
                     "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null", "-w", "%{http_code}",
                     "https://hoyee.org.cn/api/v1/auth/me").decode()
        assert status == "401", "Public API unavailable: " + status
    except BaseException:
        if activated:
            activate(before, before["Config"]["Image"], "rollback")
            healthy()
        raise
    result = {
        "image": image, "rollbackImage": before["Config"]["Image"], "tests": tests,
        "classes": {name: sha(data) for name, data in changed.items()},
        "frontendUnchanged": True, "schemaUnchanged": True, "storageStartupVerified": True,
        "preservedContainers": preserved, "unrelatedJarEntriesPreserved": True,
    }
    (ROOT / "activation.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


try:
    main()
except subprocess.CalledProcessError as error:
    print((error.output or b"").decode()[-12000:], file=sys.stderr)
    raise
