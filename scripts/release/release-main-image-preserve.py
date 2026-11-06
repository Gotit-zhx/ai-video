"""Preserve product-free references using exact-image overlays; no migrations."""
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
from html.parser import HTMLParser

ROOT = Path(sys.argv[1]).resolve()
MODE = sys.argv[2]
APP, PI, FRONT = "ai-commerce-app", "heyi-pi-runtime", "ai-commerce-frontend"
FILES = {
    FRONT: ["product-main-image-styles.js"],
    PI: ["src/product-main-image-style.js"],
}
PREFIXES = ["com/yingdao/aicommerce/product/application/" + name for name in
            ["ProductMainImageStylePolicy", "ProductMainImageTemplateService", "ProductMainImageStyleService", "ProductMainImageAssets"]]
TAG = "main-image-preserve-20261019"
OLD_ASSET = "/product-main-image-styles.js?v=20261019-progress-1"
NEW_ASSET = "/product-main-image-styles.js?v=20261019-preserve-1"


class ScriptAssets(HTMLParser):
    def __init__(self):
        super().__init__()
        self.sources = []

    def handle_starttag(self, tag, attrs):
        if tag == "script":
            self.sources.append(dict(attrs).get("src"))


def run(*args, data=None, env=None):
    return subprocess.check_output(args, input=data, stderr=subprocess.STDOUT, env=env)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2))
    path.chmod(0o600)


def read_json(name):
    return json.loads((ROOT / name).read_text())


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def remote_path(name, file):
    return ("/app/" if name == PI else "/usr/share/nginx/html/") + file


def replaced(name):
    return any(name == "BOOT-INF/classes/" + prefix + ".class"
               or name.startswith("BOOT-INF/classes/" + prefix + "$") for prefix in PREFIXES)


def pack():
    source, baseline = Path(sys.argv[3]).resolve(), Path(sys.argv[4]).resolve()
    ROOT.mkdir(exist_ok=False)
    manifest = {"files": {}, "expectedFiles": {}, "classes": {}, "expectedClasses": {},
                "sourceCommit": run("git", "-C", str(source), "rev-parse", "HEAD").decode().strip()}
    for name, files in FILES.items():
        folder = "frontend" if name == FRONT else "pi-runtime"
        for file in files:
            target = ROOT / name / file
            target.parent.mkdir(parents=True, exist_ok=True)
            source_folder = "services/pi-runtime" if name == PI else "frontend"
            shutil.copy2(source / source_folder / file, target)
            manifest["files"][name + "/" + file] = sha(target.read_bytes())
            manifest["expectedFiles"][name + "/" + file] = sha(run("git", "-C", str(source), "show", "fbfe7f3:" + folder + "/" + file))
    for prefix in PREFIXES:
        for folder, key in [(baseline, "expectedClasses"), (source / "target/classes", "classes")]:
            file = folder / prefix
            matches = list(file.parent.glob(file.name + ".class")) + list(file.parent.glob(file.name + "$*.class"))
            assert matches, prefix
            for item in matches:
                relative = str(item.relative_to(folder))
                manifest[key]["BOOT-INF/classes/" + relative] = sha(item.read_bytes())
                if key == "classes":
                    target = ROOT / "classes" / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(item, target)
    tests = ROOT / "runtime-test"
    tests.mkdir()
    for name in ["product-main-image-agent.integration.mjs", "product-main-image-style.test.mjs", "product-analysis.test.mjs"]:
        shutil.copy2(source / "services/pi-runtime/test" / name, tests / name)
    shutil.copy2(source / "scripts/release/release-main-image-preserve.py", ROOT / "release.py")
    write_json(ROOT / "manifest.json", manifest)
    print("Prepared reviewed source files and class families.")


def baseline():
    manifest = read_json("manifest.json")
    for key, digest in manifest["expectedFiles"].items():
        name, file = key.split("/", 1)
        assert sha(run("docker", "exec", name, "cat", remote_path(name, file))) == digest, "Concurrent production change: " + key


def configuration(info):
    return (sorted(info["Config"]["Env"]), sorted((m["Source"], m["Destination"], m["RW"]) for m in info["Mounts"]),
            info["HostConfig"]["PortBindings"], sorted(info["NetworkSettings"]["Networks"]))


def activate(before, name, tag):
    labels = before[name]["Config"]["Labels"]
    command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f", file]
    override = ROOT / ("compose." + name + ".json")
    write_json(override, {"services": {labels["com.docker.compose.service"]: {"image": tag}}})
    env = dict(os.environ, HR_FRONTEND_IMAGE=before[FRONT]["Config"]["Image"])
    run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never",
        labels["com.docker.compose.service"], env=env)


def health(name):
    for _ in range(90):
        try:
            if name == APP:
                body = run("docker", "exec", name, "curl", "-fsS", "--max-time", "2", "http://127.0.0.1:8080/actuator/health")
                assert json.loads(body)["status"] == "UP"
            elif name == PI:
                body = run("docker", "exec", name, "node", "-e", "fetch('http://127.0.0.1:8090/health').then(r=>r.json()).then(r=>process.stdout.write(JSON.stringify(r)))")
                assert json.loads(body)["status"] == "UP"
            else:
                run("curl", "--noproxy", "*", "-fsS", "--max-time", "3", "--resolve", "hoyee.org.cn:443:127.0.0.1",
                    "https://hoyee.org.cn/product-main-image-styles.js")
            return
        except (subprocess.CalledProcessError, ValueError, AssertionError):
            time.sleep(1)
    raise RuntimeError("Health check failed: " + name)


def idle():
    tables = {
        "product_main_image_template_images": "status IN ('PENDING','QUEUED','ANALYZING')",
        "product_publish_workflows": "status IN ('PENDING','QUEUED','RUNNING','PUBLISHING','PROCESSING_IMAGES')",
        "product_image_processing_jobs": "status IN ('PENDING','QUEUED','RUNNING','PROCESSING')",
        "product_watermark_queue": "status IN ('QUEUED','RUNNING')",
        "commerce_batch_executions": "status IN ('QUEUED','RUNNING','PAUSING')",
        "ai_video_task_items": "status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING')",
        "video_scripts": "status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
    }
    sql = ";".join("SELECT COUNT(*) FROM " + table + " WHERE " + condition for table, condition in tables.items())
    counts = run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
                 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$MYSQL_DATABASE"', data=sql.encode()).decode().split()
    assert not any(int(count) for count in counts), "Active jobs remain; deployment deferred"
    with sqlite3.connect("file:/opt/heyi/boss-agent/runtime/talent-pool.sqlite3?mode=ro", uri=True) as db:
        for table, condition in [
            ("recruitment_demands", "ai_status='running'"), ("hr_resume_pool", "ai_state='running'"),
            ("hr_sourcing_runs", "state='running'"), ("hr_sourcing_jobs", "profile_state='running'"),
            ("hr_sourcing_contacts", "status='sending'"), ("hr_talent_messages", "status='sending'"),
            ("hr_resume_commands", "status='sending'"), ("hr_interview_handoffs", "status='preparing'"),
            ("hr_onboarding_notices", "status='preparing'"),
        ]:
            assert db.execute("SELECT COUNT(*) FROM " + table + " WHERE " + condition).fetchone()[0] == 0, "Active HR job: " + table
    state = json.loads(run("docker", "exec", PI, "node", "-e",
        "fetch('http://127.0.0.1:8090/health').then(r=>r.json()).then(r=>console.log(JSON.stringify(r)))"))
    assert not state.get("chatSessions") and not state.get("automationAgentSessions"), "Live SDK sessions; deployment deferred"


def build():
    baseline()
    assert not (ROOT / "prepared.json").exists()
    before = {name: inspect(name) for name in [APP, PI, FRONT]}
    write_json(ROOT / "before-private.json", before)
    manifest = read_json("manifest.json")
    # Patch only this script URL in the current HTML, preserving other releases.
    index = run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/index.html")
    parser = ScriptAssets()
    parser.feed(index.decode())
    assert parser.sources.count(OLD_ASSET) == 1 and index.count(OLD_ASSET.encode()) == 1, "Frontend asset version changed"
    updated_index = index.replace(OLD_ASSET.encode(), NEW_ASSET.encode())
    manifest["expectedFiles"][FRONT + "/index.html"] = sha(index)
    manifest["files"][FRONT + "/index.html"] = sha(updated_index)
    (ROOT / FRONT / "index.html").write_bytes(updated_index)
    write_json(ROOT / "manifest.json", manifest)
    previous = ROOT / "previous.jar"
    previous.write_bytes(run("docker", "exec", APP, "cat", "/app/app.jar"))
    previous.chmod(0o600)
    appdir = ROOT / (APP + "-image")
    appdir.mkdir()
    with zipfile.ZipFile(previous) as old, zipfile.ZipFile(appdir / "app.jar", "w") as new:
        assert {name: sha(old.read(name)) for name in old.namelist() if replaced(name)} == manifest["expectedClasses"], "Backend class family changed"
        new.comment = old.comment
        for info in old.infolist():
            if not replaced(info.filename):
                new.writestr(info, old.read(info.filename))
        for name, digest in manifest["classes"].items():
            data = (ROOT / "classes" / name.removeprefix("BOOT-INF/classes/")).read_bytes()
            assert sha(data) == digest
            new.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(previous) as old, zipfile.ZipFile(appdir / "app.jar") as new:
        unchanged = {name for name in old.namelist() if not replaced(name)}
        assert set(new.namelist()) == unchanged | set(manifest["classes"])
        assert all(old.read(name) == new.read(name) for name in unchanged)
    images = {}
    for name in before:
        directory = ROOT / (name + "-image")
        directory.mkdir(exist_ok=True)
        lines = ["FROM " + before[name]["Image"]]
        if name == APP:
            lines.append("COPY app.jar /app/app.jar")
        else:
            for file in FILES[name] + (["index.html"] if name == FRONT else []):
                data = (ROOT / name / file).read_bytes()
                assert sha(data) == manifest["files"][name + "/" + file]
                target = directory / file
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                lines.append("COPY " + file + " " + remote_path(name, file))
        (directory / "Dockerfile").write_text("\n".join(lines) + "\n")
        images[name] = ("ai-commerce-platform" if name == APP else name) + ":" + TAG
        run("docker", "build", "-t", images[name], str(directory))
    output = run("docker", "run", "--rm", "--network", "none", "-v", str(ROOT / "runtime-test") + ":/app/test:ro",
                 images[PI], "node", "--test", "/app/test/product-main-image-agent.integration.mjs",
                 "/app/test/product-main-image-style.test.mjs", "/app/test/product-analysis.test.mjs")
    (ROOT / "sdk-test.log").write_bytes(output)
    write_json(ROOT / "prepared.json", {"images": images, "oldJarHash": sha(previous.read_bytes()),
                                      "jarHash": sha((appdir / "app.jar").read_bytes()), "sdkFixturePassed": True})
    print("Exact-image overlays built; installed Pi SDK passed isolated streaming tests; unrelated JAR entries preserved.")


def deploy():
    assert not (ROOT / "activation.json").exists()
    baseline()
    prepared, before = read_json("prepared.json"), read_json("before-private.json")
    for name, info in before.items():
        assert inspect(name)["Id"] == info["Id"], "Container changed after prepare: " + name
    assert sha(run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["oldJarHash"]
    idle()
    preserved = {item["Name"]: item["Id"] for item in json.loads(run("docker", "inspect", *run("docker", "ps", "-q").decode().split()))
                 if item["Name"].lstrip("/") not in before}
    rollback = {}
    for name, info in before.items():
        rollback[name] = ("ai-commerce-platform" if name == APP else name) + ":" + TAG + "-before"
        run("docker", "tag", info["Image"], rollback[name])
    run("docker", "stop", "--time", "60", APP)
    try:
        for name in [PI, APP, FRONT]:
            activate(before, name, prepared["images"][name])
            health(name)
            assert configuration(inspect(name)) == configuration(before[name]), "Configuration drift: " + name
        assert all(inspect(name)["Id"] == value for name, value in preserved.items())
        write_json(ROOT / "activation.json", {"images": prepared["images"], "rollbackImages": rollback,
            "preservedContainers": preserved, "configurationPreserved": True, "databaseModified": False})
        verify()
    except BaseException:
        for name in [PI, APP, FRONT]:
            activate(before, name, rollback[name])
            health(name)
        raise
    print("Original-preservation update deployed; existing data, configuration and unrelated containers preserved.")


def verify():
    activation, manifest, prepared = read_json("activation.json"), read_json("manifest.json"), read_json("prepared.json")
    for name, image in activation["images"].items():
        assert inspect(name)["Config"]["Image"] == image
        health(name)
    for key, digest in manifest["files"].items():
        name, file = key.split("/", 1)
        assert sha(run("docker", "exec", name, "cat", remote_path(name, file))) == digest
        if name == FRONT:
            body = run("curl", "--noproxy", "*", "-fsS", "--max-time", "5", "--resolve", "hoyee.org.cn:443:127.0.0.1",
                       "https://hoyee.org.cn/" + file)
            assert sha(body) == digest
    assert sha(run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["jarHash"]
    assert all(inspect(name)["Id"] == value for name, value in activation["preservedContainers"].items())
    write_json(ROOT / "verification.json", {"healthy": True, "servedHashesMatch": True, "unrelatedContainersPreserved": True,
                                          "sdkFixturePassed": True, "paidTestCalls": False})
    print("Verified HTTPS assets, exact runtime files, JAR hash and service health.")


try:
    {"pack": pack, "build": build, "deploy": deploy, "verify": verify}[MODE]()
except subprocess.CalledProcessError as error:
    log = ROOT / ("failure-" + MODE + ".log")
    log.write_bytes(error.output or b"")
    log.chmod(0o600)
    print("Command failed; private diagnostics: " + str(log), file=sys.stderr)
    raise SystemExit(1)
