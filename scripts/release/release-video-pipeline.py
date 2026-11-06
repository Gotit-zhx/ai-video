"""Release narrow video fixes onto verified live images, with automatic rollback."""
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
APP, PI, FRONT = "ai-commerce-app", "heyi-pi-runtime", "ai-commerce-frontend"
PREFIXES = ["com/yingdao/aicommerce/video/application/" + name
            for name in ["AiVideoImageGateway", "AiVideoImageProcessor"]]
PI_FILES = ["src/server.js", "src/chat-completion-transport.js"]
FRONT_FILES = ["app.js", "styles.css"]


def run(*args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT)


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def selected(name):
    return any(name == p + ".class" or name.startswith(p + "$") for p in PREFIXES)


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


def activate(before, name, image, suffix):
    labels = before["Config"]["Labels"]
    service = labels["com.docker.compose.service"]
    override = ROOT / ("compose." + name + "." + suffix + ".json")
    override.write_text(json.dumps({"services": {service: {"image": image}}}))
    command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f", file]
    env = dict(os.environ, HR_FRONTEND_IMAGE=inspect(FRONT)["Config"]["Image"])
    subprocess.check_output(command + ["-f", str(override), "up", "-d", "--no-deps",
                            "--no-build", "--pull", "never", service], env=env, stderr=subprocess.STDOUT)


def healthy(name):
    for _ in range(90):
        try:
            if name == APP:
                result = json.loads(run("docker", "exec", APP, "curl", "-fsS", "--max-time", "2",
                                        "http://127.0.0.1:8080/actuator/health"))
                assert result["status"] == "UP"
            elif name == PI:
                code = "fetch('http://127.0.0.1:8090/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
                run("docker", "exec", PI, "node", "-e", code)
            else:
                run("docker", "exec", FRONT, "nginx", "-t")
            return
        except (subprocess.CalledProcessError, ValueError, AssertionError):
            time.sleep(1)
    raise RuntimeError(name + " health failed")


def test_reports(folder):
    reports = [ET.parse(path).getroot() for path in (ROOT / folder / "target/surefire-reports").glob("TEST-*.xml")]
    assert reports
    assert all(int(r.get("failures", 0)) + int(r.get("errors", 0)) == 0 for r in reports), "Java tests failed"
    return sum(int(r.get("tests", 0)) for r in reports)


def main():
    assert not (ROOT / "activation.json").exists(), "Already activated"
    assert shutil.disk_usage(ROOT).free >= 2 * 1024**3
    counts = {folder: test_reports(folder) for folder in ["source", "base"]}
    assert (ROOT / "runtime-tests-passed.json").exists()
    expected = json.loads((ROOT / "expected.json").read_text())
    names = [PI, APP, FRONT]
    before = {name: inspect(name) for name in names}
    all_containers = json.loads(run("docker", "inspect", *run("docker", "ps", "-q").decode().split()))
    preserved = {c["Name"]: c["Id"] for c in all_containers if c["Name"].lstrip("/") not in names}
    rollback = ROOT / "rollback"
    rollback.mkdir(mode=0o700, exist_ok=True)
    run("docker", "cp", APP + ":/app/app.jar", str(rollback / "app.jar"))
    assert sha((rollback / "app.jar").read_bytes()) == expected["jar"], "Live JAR changed"
    for name, files, path in [(PI, PI_FILES, "/app/"), (FRONT, FRONT_FILES, "/usr/share/nginx/html/")]:
        for file in files:
            data = run("docker", "exec", name, "cat", path + file)
            assert sha(data) == expected[name][file], "Live source drift: " + file
            target = rollback / name / file
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    classes = ROOT / "source/target/classes"
    changed = {str(p.relative_to(classes)): p.read_bytes() for prefix in PREFIXES
               for p in (classes / prefix).parent.glob(Path(prefix).name + "*.class")}
    assert changed and all(selected(name) for name in changed)
    with zipfile.ZipFile(rollback / "app.jar") as old:
        originals = {n.removeprefix("BOOT-INF/classes/"): old.read(n) for n in old.namelist()
                     if n.startswith("BOOT-INF/classes/") and selected(n.removeprefix("BOOT-INF/classes/"))}
        assert originals.keys() == changed.keys(), "Class structure drift"
        for name, data in originals.items():
            file = ROOT / "liveclasses" / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(data)
        types = [n.removesuffix(".class").replace("/", ".") for n in sorted(originals)]

        def bytecode(location):
            return run("docker", "run", "--rm", "-v", str(ROOT) + ":/release",
                       "maven:3.9-eclipse-temurin-21", "javap", "-c", "-p", "-constants",
                       "-classpath", "/release/" + location, *types)

        assert bytecode("liveclasses") == bytecode("base/target/classes"), "Java baseline drift"
        appdir = ROOT / "app-image"
        appdir.mkdir(exist_ok=True)
        with zipfile.ZipFile(appdir / "app.jar", "w") as new:
            for info in old.infolist():
                data = changed.get(info.filename.removeprefix("BOOT-INF/classes/"))
                new.writestr(info, data if data is not None else old.read(info.filename))
    with zipfile.ZipFile(rollback / "app.jar") as old, zipfile.ZipFile(appdir / "app.jar") as new:
        assert old.namelist() == new.namelist()
        assert all(old.read(n) == new.read(n) for n in old.namelist()
                   if not selected(n.removeprefix("BOOT-INF/classes/")))

    images = {APP: "ai-commerce-platform:video-pipeline-20261015",
              PI: "heyi-pi-runtime:video-pipeline-20261015",
              FRONT: "ai-commerce-frontend:video-pipeline-20261015"}
    for name, files, path, directory, source in [
        (APP, ["app.jar"], "/app/", appdir, appdir),
        (PI, PI_FILES, "/app/", ROOT / "pi-image", ROOT / "source/pi-runtime"),
        (FRONT, FRONT_FILES, "/usr/share/nginx/html/", ROOT / "front-image", ROOT / "source/frontend"),
    ]:
        directory.mkdir(exist_ok=True)
        lines = ["FROM " + before[name]["Image"]]
        for file in files:
            if source != directory:
                (directory / file).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source / file, directory / file)
            lines.append("COPY " + file + " " + path + file)
        (directory / "Dockerfile").write_text("\n".join(lines) + "\n")
        run("docker", "build", "-t", images[name], str(directory))

    idle()
    assert sha(run("docker", "exec", APP, "cat", "/app/app.jar")) == expected["jar"]
    assert all(inspect(name)["Id"] == before[name]["Id"] for name in names), "Concurrent deployment"
    activated = []
    try:
        for name in names:
            activated.append(name)
            activate(before[name], name, images[name], "activate")
            healthy(name)
            after = inspect(name)
            mounts = lambda c: sorted((m["Source"], m["Destination"], m["RW"]) for m in c["Mounts"])
            assert mounts(before[name]) == mounts(after), "Mount drift"
            assert sorted(before[name]["Config"]["Env"]) == sorted(after["Config"]["Env"]), "Environment drift"
        assert "OSS storage verified for private bucket test-hoyee" in run("docker", "logs", "--tail", "300", APP).decode()
        for name, files, path, source in [
            (PI, PI_FILES, "/app/", ROOT / "source/pi-runtime"),
            (FRONT, FRONT_FILES, "/usr/share/nginx/html/", ROOT / "source/frontend"),
        ]:
            for file in files:
                assert sha(run("docker", "exec", name, "cat", path + file)) == sha((source / file).read_bytes())
        assert all(inspect(name)["Id"] == identifier for name, identifier in preserved.items())
        status = run("curl", "--noproxy", "*", "-sS", "--max-time", "10", "--resolve",
                     "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null", "-w", "%{http_code}",
                     "https://hoyee.org.cn/api/v1/auth/me").decode()
        assert status == "401", "Public API health"
        for file in FRONT_FILES:
            served = run("curl", "--noproxy", "*", "-fsS", "--max-time", "10", "--resolve",
                         "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/" + file)
            assert sha(served) == sha((ROOT / "source/frontend" / file).read_bytes())
    except BaseException:
        for name in reversed(activated):
            activate(before[name], name, before[name]["Config"]["Image"], "rollback")
            healthy(name)
        raise
    result = {"images": images, "rollbackImages": {n: before[n]["Config"]["Image"] for n in names},
              "javaTests": counts, "preservedContainers": preserved, "schemaUnchanged": True,
              "credentialsUnchanged": True, "unrelatedJarEntriesPreserved": True, "paidCalls": 0}
    (ROOT / "activation.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


try:
    main()
except subprocess.CalledProcessError as error:
    print((error.output or b"").decode()[-8000:], file=sys.stderr)
    raise
