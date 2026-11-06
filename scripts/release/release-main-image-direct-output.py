"""Generation-only overlay on exact live images; preserve unrelated releases."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import zipfile
from html.parser import HTMLParser

ROOT = Path(sys.argv[1]).resolve()
MODE = sys.argv[2]
APP, FRONT = "ai-commerce-app", "ai-commerce-frontend"
PREFIXES = ["com/yingdao/aicommerce/product/application/" + name
            for name in ("ProductMainImageStyleService", "ProductMainImageAssets")]
TAG = "main-image-direct-output-20261019"
OLD_URL = "/app.js?v=main-image-detail-20261019-1"
NEW_URL = "/app.js?v=main-image-direct-20261019-1"


class Assets(HTMLParser):
    def __init__(self):
        super().__init__()
        self.urls = []

    def handle_starttag(self, tag, attrs):
        if tag == "script":
            self.urls.append(dict(attrs).get("src"))


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


def replaced(name):
    return any(name == "BOOT-INF/classes/" + prefix + ".class"
               or name.startswith("BOOT-INF/classes/" + prefix + "$") for prefix in PREFIXES)


def pack():
    source, baseline = Path(sys.argv[3]).resolve(), Path(sys.argv[4]).resolve()
    ROOT.mkdir(exist_ok=False, mode=0o700)
    manifest = {"files": {}, "expectedFiles": {}, "classes": {}, "expectedClasses": {},
                "sourceCommit": run("git", "-C", str(source), "rev-parse", "HEAD").decode().strip()}
    target = ROOT / FRONT / "app.js"
    target.parent.mkdir()
    shutil.copy2(source / "frontend/app.js", target)
    manifest["files"]["app.js"] = sha(target.read_bytes())
    manifest["expectedFiles"]["app.js"] = sha(run("git", "-C", str(source), "show", "149c301:frontend/app.js"))
    for folder, key in [(baseline, "expectedClasses"), (source / "target/classes", "classes")]:
        for prefix in PREFIXES:
            file = folder / prefix
            matches = list(file.parent.glob(file.name + ".class")) + list(file.parent.glob(file.name + "$*.class"))
            assert matches, "Missing class family: " + prefix
            for item in matches:
                relative = str(item.relative_to(folder))
                manifest[key]["BOOT-INF/classes/" + relative] = sha(item.read_bytes())
                if key == "classes":
                    target = ROOT / "classes" / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(item, target)
    shutil.copy2(source / "scripts/release/release-main-image-direct-output.py", ROOT / "release.py")
    write_json(ROOT / "manifest.json", manifest)
    print("Packed generation-only service, publishing assets and progress label.")


def baseline():
    for file, digest in read_json("manifest.json")["expectedFiles"].items():
        assert sha(run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)) == digest, "Concurrent frontend change: " + file


def configuration(info):
    config = info["Config"]
    return (sorted(config["Env"]), sorted((m["Source"], m["Destination"], m["RW"]) for m in info["Mounts"]),
            info["HostConfig"]["PortBindings"], sorted(info["NetworkSettings"]["Networks"]),
            {key: config.get(key) for key in ("Entrypoint", "Cmd", "User", "WorkingDir", "Healthcheck")},
            info["HostConfig"]["RestartPolicy"])


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
                assert json.loads(run("docker", "exec", APP, "curl", "-fsS", "--max-time", "2",
                                      "http://127.0.0.1:8080/actuator/health"))["status"] == "UP"
            else:
                run("curl", "--noproxy", "*", "-fsS", "--max-time", "3", "--resolve", "hoyee.org.cn:443:127.0.0.1",
                    "https://hoyee.org.cn/app.js")
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
    assert len(counts) == len(tables)
    assert not any(int(count) for count in counts), "Active jobs remain; no service was stopped"
    print("No active application jobs.")


def build():
    baseline()
    assert not (ROOT / "prepared.json").exists()
    before = {name: inspect(name) for name in [APP, FRONT]}
    write_json(ROOT / "before-private.json", before)
    manifest = read_json("manifest.json")
    index = run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/index.html")
    parser = Assets()
    parser.feed(index.decode())
    assert parser.urls.count(OLD_URL) == 1 and index.count(OLD_URL.encode()) == 1, "Live asset URL changed"
    updated = index.replace(OLD_URL.encode(), NEW_URL.encode())
    manifest["expectedFiles"]["index.html"], manifest["files"]["index.html"] = sha(index), sha(updated)
    (ROOT / FRONT / "index.html").write_bytes(updated)
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
            for file in ("app.js", "index.html"):
                data = (ROOT / FRONT / file).read_bytes()
                assert sha(data) == manifest["files"][file]
                (directory / file).write_bytes(data)
                lines.append("COPY " + file + " /usr/share/nginx/html/" + file)
        (directory / "Dockerfile").write_text("\n".join(lines) + "\n")
        images[name] = ("ai-commerce-platform" if name == APP else name) + ":" + TAG
        run("docker", "build", "-t", images[name], str(directory))
    write_json(ROOT / "prepared.json", {"images": images, "oldJarHash": sha(previous.read_bytes()),
                                      "jarHash": sha((appdir / "app.jar").read_bytes())})
    print("Built overlays; unrelated HTML, classes, runtime and configuration retained.")


def deploy():
    assert not (ROOT / "activation.json").exists()
    baseline()
    prepared, before = read_json("prepared.json"), read_json("before-private.json")
    for name, info in before.items():
        assert inspect(name)["Id"] == info["Id"], "Container changed after build: " + name
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
        for name in [APP, FRONT]:
            activate(before, name, prepared["images"][name])
            health(name)
            assert configuration(inspect(name)) == configuration(before[name]), "Configuration drift: " + name
        assert all(inspect(name)["Id"] == value for name, value in preserved.items())
        write_json(ROOT / "activation.json", {"images": prepared["images"], "rollbackImages": rollback,
            "preservedContainers": preserved, "configurationPreserved": True, "databaseModified": False})
        verify()
    except BaseException:
        for name in [APP, FRONT]:
            activate(before, name, rollback[name])
            health(name)
        raise
    print("Deployed generation-only output; Pi runtime was not restarted.")


def verify():
    activation, manifest, prepared = read_json("activation.json"), read_json("manifest.json"), read_json("prepared.json")
    for name, image in activation["images"].items():
        assert inspect(name)["Config"]["Image"] == image
        health(name)
    for file, digest in manifest["files"].items():
        assert sha(run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)) == digest
        assert sha(run("curl", "--noproxy", "*", "-fsS", "--max-time", "5", "--resolve", "hoyee.org.cn:443:127.0.0.1",
                       "https://hoyee.org.cn/" + file)) == digest
    assert sha(run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["jarHash"]
    assert all(inspect(name)["Id"] == value for name, value in activation["preservedContainers"].items())
    write_json(ROOT / "verification.json", {"healthy": True, "servedHashesMatch": True,
               "unrelatedContainersPreserved": True, "databaseModified": False, "paidTestCalls": False})
    print("Verified HTTPS assets, JAR hash, service health and unchanged runtime.")


if __name__ == "__main__":
    try:
        {"pack": pack, "build": build, "deploy": deploy, "verify": verify, "idle": idle}[MODE]()
    except subprocess.CalledProcessError as error:
        log = ROOT / ("failure-" + MODE + ".log")
        log.write_bytes(error.output or b"")
        log.chmod(0o600)
        print("Command failed; private diagnostics: " + str(log), file=sys.stderr)
        raise SystemExit(1)
