"""Runtime-only timeout overlay; preserve the exact live image and Compose configuration."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import time

ROOT = Path(sys.argv[1]).resolve()
MODE = sys.argv[2]
PI = "heyi-pi-runtime"
FILE = "src/product-main-image-style.js"
TAG = "heyi-pi-runtime:main-image-timeout-20261019"
TESTS = ["product-main-image-agent.integration.mjs", "product-main-image-style.test.mjs", "product-analysis.test.mjs"]


def run(*args, data=None, env=None):
    return subprocess.check_output(args, input=data, stderr=subprocess.STDOUT, env=env)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def save(name, value):
    path = ROOT / name
    path.write_text(json.dumps(value, indent=2))
    path.chmod(0o600)


def load(name):
    return json.loads((ROOT / name).read_text())


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def live_hash():
    return sha(run("docker", "exec", PI, "cat", "/app/" + FILE))


def configuration(info):
    return (sorted(info["Config"]["Env"]),
            sorted((m["Source"], m["Destination"], m["RW"]) for m in info["Mounts"]),
            info["HostConfig"]["PortBindings"], sorted(info["NetworkSettings"]["Networks"]))


def pack():
    source, baseline = Path(sys.argv[3]).resolve(), sys.argv[4]
    run("git", "-C", str(source), "diff", "--exit-code", "HEAD", "--", "services/pi-runtime/" + FILE,
        *["services/pi-runtime/test/" + test for test in TESTS], "scripts/release/release-main-image-timeout.py")
    ROOT.mkdir(exist_ok=False)
    (ROOT / "image/src").mkdir(parents=True)
    (ROOT / "tests").mkdir()
    shutil.copy2(source / "services/pi-runtime" / FILE, ROOT / "image" / FILE)
    for test in TESTS:
        shutil.copy2(source / "services/pi-runtime/test" / test, ROOT / "tests" / test)
    shutil.copy2(source / "scripts/release/release-main-image-timeout.py", ROOT / "release.py")
    save("manifest.json", {
        "sourceCommit": run("git", "-C", str(source), "rev-parse", "HEAD").decode().strip(),
        "expectedHash": sha(run("git", "-C", str(source), "show", baseline + ":pi-runtime/" + FILE)),
        "hash": sha((ROOT / "image" / FILE).read_bytes()),
        "tests": {test: sha((ROOT / "tests" / test).read_bytes()) for test in TESTS},
    })
    print("Packed the single runtime module and focused SDK tests.")


def build():
    assert not (ROOT / "prepared.json").exists()
    manifest = load("manifest.json")
    assert live_hash() == manifest["expectedHash"], "Runtime module changed concurrently"
    before = inspect(PI)
    save("before-private.json", before)
    assert sha((ROOT / "image" / FILE).read_bytes()) == manifest["hash"]
    for test, digest in manifest["tests"].items():
        assert sha((ROOT / "tests" / test).read_bytes()) == digest
    (ROOT / "image/Dockerfile").write_text("FROM " + before["Image"] + "\nCOPY " + FILE + " /app/" + FILE + "\n")
    run("docker", "build", "-t", TAG, str(ROOT / "image"))
    result = run("docker", "run", "--rm", "--network", "none",
                 "-v", str(ROOT / "tests") + ":/app/test:ro", TAG,
                 "node", "--test", *["/app/test/" + test for test in TESTS])
    (ROOT / "sdk-test.log").write_bytes(result)
    save("prepared.json", {"imageId": json.loads(run("docker", "image", "inspect", TAG))[0]["Id"],
                           "sdkTestsPassed": True})
    print("Exact-image overlay built; actual Pi SDK tests passed without external networking.")


def health():
    for _ in range(60):
        try:
            result = json.loads(run("docker", "exec", PI, "node", "-e",
                "fetch('http://127.0.0.1:8090/health').then(r=>r.json()).then(r=>console.log(JSON.stringify(r)))"))
            if result["status"] == "UP":
                return result
        except (subprocess.CalledProcessError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError("Runtime health check failed")


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
    assert not any(int(count) for count in counts), "Active platform tasks; deployment deferred"
    with sqlite3.connect("file:/opt/heyi/boss-agent/runtime/talent-pool.sqlite3?mode=ro", uri=True) as db:
        for table, condition in [
            ("recruitment_demands", "ai_status='running'"), ("hr_resume_pool", "ai_state='running'"),
            ("hr_sourcing_runs", "state='running'"), ("hr_sourcing_jobs", "profile_state='running'"),
            ("hr_sourcing_contacts", "status='sending'"), ("hr_talent_messages", "status='sending'"),
            ("hr_resume_commands", "status='sending'"), ("hr_interview_handoffs", "status='preparing'"),
            ("hr_onboarding_notices", "status='preparing'"),
        ]:
            assert db.execute("SELECT COUNT(*) FROM " + table + " WHERE " + condition).fetchone()[0] == 0, "Active HR task"
    status = health()
    assert not status.get("chatSessions") and not status.get("automationAgentSessions"), "Live SDK sessions; deployment deferred"


def activate(before, image, suffix):
    labels = before["Config"]["Labels"]
    command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f", file]
    name = "compose-" + suffix + ".json"
    save(name, {"services": {labels["com.docker.compose.service"]: {"image": image}}})
    env = dict(os.environ, HR_FRONTEND_IMAGE=inspect("ai-commerce-frontend")["Config"]["Image"])
    run(*command, "-f", str(ROOT / name), "up", "-d", "--no-deps", "--no-build", "--pull", "never",
        labels["com.docker.compose.service"], env=env)


def deploy():
    assert not (ROOT / "activation.json").exists()
    before, prepared = load("before-private.json"), load("prepared.json")
    assert inspect(PI)["Id"] == before["Id"], "Runtime container changed after preparation"
    assert live_hash() == load("manifest.json")["expectedHash"]
    assert json.loads(run("docker", "image", "inspect", TAG))[0]["Id"] == prepared["imageId"]
    idle()
    preserved = {item["Name"]: item["Id"] for item in json.loads(
        run("docker", "inspect", *run("docker", "ps", "-q").decode().split())) if item["Name"] != "/" + PI}
    rollback = TAG + "-before"
    run("docker", "tag", before["Image"], rollback)
    try:
        activate(before, TAG, "timeout")
        health()
        assert configuration(inspect(PI)) == configuration(before), "Runtime configuration drift"
        assert live_hash() == load("manifest.json")["hash"]
        assert all(inspect(name)["Id"] == value for name, value in preserved.items()), "Other containers changed"
        save("activation.json", {"preservedContainers": preserved, "rollbackImage": rollback})
        verify()
    except BaseException:
        activate(before, rollback, "rollback")
        health()
        raise
    print("Runtime-only deployment complete; other services, configuration and task data unchanged.")


def verify():
    before, prepared, activation = load("before-private.json"), load("prepared.json"), load("activation.json")
    assert inspect(PI)["Image"] == prepared["imageId"]
    assert live_hash() == load("manifest.json")["hash"]
    assert configuration(inspect(PI)) == configuration(before)
    assert all(inspect(name)["Id"] == value for name, value in activation["preservedContainers"].items())
    health()
    save("verification.json", {"healthy": True, "runtimeHashMatches": True, "configurationPreserved": True,
                              "otherContainersPreserved": True, "databaseModified": False, "sdkTestsPassed": True})
    print("Verified deployed source, service health and unchanged surrounding containers.")


try:
    {"pack": pack, "build": build, "deploy": deploy, "verify": verify}[MODE]()
except subprocess.CalledProcessError as error:
    path = ROOT / ("failure-" + MODE + ".log")
    path.write_bytes(error.output or b"")
    path.chmod(0o600)
    print("Command failed; diagnostics retained privately at " + str(path), file=sys.stderr)
    raise SystemExit(1)
