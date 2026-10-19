"""Exact-image Banana adapter overlay. Default-model selection is a separate guarded operation."""
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

ROOT = Path(sys.argv[1]).resolve()
MODE = sys.argv[2]
APP, PI, FRONT = "ai-commerce-app", "heyi-pi-runtime", "ai-commerce-frontend"
TAG = "banana-default-20261019"
PREFIXES = ["com/yingdao/aicommerce/" + name for name in (
    "saas/application/ModelConfigService",
    "product/application/ProductMainImageStyleService",
    "video/application/AiVideoImageProcessor")]
TESTS = ["image-model-fallback-http.test.mjs", "image-model-fallback.test.mjs",
         "image-operation-store.test.mjs", "image-network-recovery.test.mjs",
         "image-network-fallback-integration.test.mjs", "image-progress.test.mjs",
         "image-public-errors.test.mjs", "image-stream-response.test.mjs"]


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
    manifest = {"classes": {}, "expectedClasses": {},
                "sourceCommit": run("git", "-C", str(source), "rev-parse", "HEAD").decode().strip()}
    shutil.copy2(source / "pi-runtime/src/server.js", ROOT / "server.js")
    manifest["runtimeHash"] = sha((ROOT / "server.js").read_bytes())
    manifest["expectedRuntimeHash"] = sha(run("git", "-C", str(source), "show", "9128d64:pi-runtime/src/server.js"))
    for prefix in PREFIXES:
        for folder, key in [(baseline, "expectedClasses"), (source / "target/classes", "classes")]:
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
    (ROOT / "runtime-tests").mkdir()
    for file in TESTS:
        shutil.copy2(source / "pi-runtime/test" / file, ROOT / "runtime-tests" / file)
    shutil.copy2(source / "scripts/release-banana-default.py", ROOT / "release.py")
    write_json(ROOT / "manifest.json", manifest)
    print("Packed native Gemini adapter and three reviewed backend class families.")


def baseline():
    assert sha(run("docker", "exec", PI, "cat", "/app/src/server.js")) == read_json("manifest.json")["expectedRuntimeHash"], "Runtime changed"


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
    env = dict(os.environ, HR_FRONTEND_IMAGE=inspect(FRONT)["Config"]["Image"])
    run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never",
        labels["com.docker.compose.service"], env=env)


def health(name):
    for _ in range(90):
        try:
            if name == APP:
                body = run("docker", "exec", name, "curl", "-fsS", "--max-time", "2", "http://127.0.0.1:8080/actuator/health")
            else:
                body = run("docker", "exec", name, "node", "-e",
                           "fetch('http://127.0.0.1:8090/health').then(r=>r.json()).then(r=>console.log(JSON.stringify(r)))")
            assert json.loads(body)["status"] == "UP"
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
        "commerce_print_reference_jobs": "status IN ('QUEUED','SUBMITTING','RESULT_RECEIVED')",
        "ai_video_task_items": "status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING')",
        "video_scripts": "status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
    }
    sql = ";".join("SELECT COUNT(*) FROM " + table + " WHERE " + condition for table, condition in tables.items())
    counts = run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
                 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$MYSQL_DATABASE"', data=sql.encode()).decode().split()
    assert len(counts) == len(tables) and not any(int(count) for count in counts), "Active application jobs; activation deferred"
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
    assert not state.get("chatSessions") and not state.get("automationAgentSessions"), "Live SDK sessions; activation deferred"
    print("Application queues and SDK sessions are idle.")


def build():
    baseline()
    assert not (ROOT / "prepared.json").exists()
    before = {name: inspect(name) for name in [APP, PI]}
    write_json(ROOT / "before-private.json", before)
    manifest = read_json("manifest.json")
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
            shutil.copy2(ROOT / "server.js", directory / "server.js")
            lines.append("COPY server.js /app/src/server.js")
        (directory / "Dockerfile").write_text("\n".join(lines) + "\n")
        images[name] = ("ai-commerce-platform" if name == APP else name) + ":" + TAG
        run("docker", "build", "-t", images[name], str(directory))
    tests = run("docker", "run", "--rm", "--network", "none", "-v", str(ROOT / "runtime-tests") + ":/app/test:ro",
                images[PI], "node", "--test", *("/app/test/" + name for name in TESTS))
    (ROOT / "runtime-tests.log").write_bytes(tests)
    write_json(ROOT / "prepared.json", {"images": images, "oldJarHash": sha(previous.read_bytes()),
                                      "jarHash": sha((appdir / "app.jar").read_bytes()), "runtimeTestsPassed": True})
    print("Built exact-image overlays and passed isolated runtime regression tests.")


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
        for name in [PI, APP]:
            activate(before, name, prepared["images"][name])
            health(name)
            assert configuration(inspect(name)) == configuration(before[name]), "Configuration drift: " + name
        assert all(inspect(name)["Id"] == value for name, value in preserved.items())
        write_json(ROOT / "activation.json", {"images": prepared["images"], "rollbackImages": rollback,
            "preservedContainers": preserved, "configurationPreserved": True, "databaseModified": False})
        verify()
    except BaseException:
        for name in [PI, APP]:
            activate(before, name, rollback[name])
            health(name)
        raise
    print("Native image adapter deployed. Default model not changed by this deployment step.")


def verify():
    activation, manifest, prepared = read_json("activation.json"), read_json("manifest.json"), read_json("prepared.json")
    for name, image in activation["images"].items():
        assert inspect(name)["Config"]["Image"] == image
        health(name)
    assert sha(run("docker", "exec", PI, "cat", "/app/src/server.js")) == manifest["runtimeHash"]
    assert sha(run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["jarHash"]
    assert all(inspect(name)["Id"] == value for name, value in activation["preservedContainers"].items())
    write_json(ROOT / "verification.json", {"healthy": True, "deployedHashesMatch": True,
               "unrelatedContainersPreserved": True, "databaseModifiedByDeployment": False})
    print("Verified runtime/backend hashes, health, unchanged frontend and other containers.")


if __name__ == "__main__":
    try:
        {"pack": pack, "build": build, "deploy": deploy, "verify": verify, "idle": idle}[MODE]()
    except subprocess.CalledProcessError as error:
        log = ROOT / ("failure-" + MODE + ".log")
        log.write_bytes(error.output or b"")
        log.chmod(0o600)
        print("Command failed; private diagnostics: " + str(log), file=sys.stderr)
        raise SystemExit(1)
