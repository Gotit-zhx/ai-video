"""Deploy the pi SDK recruitment route before the reviewed HR application patch.

Stage format extends release-sourcing.py with staged/pi/{server.js,recruitment-analysis.js},
expected-baseline.json["pi-server.js"], and a bundled release-sourcing.py.
Only the two reviewed runtime files change; existing image/video files stay intact.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

root = Path(sys.argv[1]).resolve()
expected = json.loads((root / "expected-baseline.json").read_text())
manifest = json.loads((root / "manifest.json").read_text())


def run(*args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT)


def inspect():
    return json.loads(run("docker", "inspect", "heyi-pi-runtime"))[0]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require_idle():
    query = ";".join([
        "SELECT COUNT(*) FROM ai_video_task_items WHERE status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING')",
        "SELECT COUNT(*) FROM video_scripts WHERE status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
        "SELECT COUNT(*) FROM product_image_processing_jobs WHERE status IN ('PENDING','QUEUED','RUNNING','PROCESSING')",
        "SELECT COUNT(*) FROM product_publish_workflows WHERE status IN ('PENDING','QUEUED','RUNNING','PUBLISHING','PROCESSING_IMAGES')",
        "SELECT COUNT(*) FROM commerce_batch_executions WHERE status IN ('QUEUED','RUNNING','PAUSING')",
    ])
    counts = run("docker", "exec", "ai-commerce-mysql", "sh", "-c",
                'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB -e "$1"', "q", query)
    assert not any(int(value) for value in counts.split()), "Wait for active business jobs"
    health = json.loads(run("docker", "exec", "heyi-pi-runtime", "node", "-e",
                            'fetch("http://127.0.0.1:8090/health").then(r=>r.text()).then(console.log)'))
    assert not health.get("chatSessions") and not health.get("automationAgentSessions"), "Runtime sessions are active"
    for file in Path("/var/lib/docker/volumes/heyi_image_operation_data/_data").glob("*.json"):
        if file.stat().st_mtime >= time.time() - 86400:
            assert json.loads(file.read_text()).get("status") != "RUNNING", "Recent image operation is active"
    import sqlite3
    with sqlite3.connect("/opt/heyi/boss-agent/runtime/talent-pool.sqlite3") as db:
        assert db.execute("SELECT count(*) FROM recruitment_demands WHERE ai_status='running'").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM hr_resume_pool WHERE ai_state='running'").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM hr_sourcing_runs WHERE state='running'").fetchone()[0] == 0


before = inspect()
assert not (root / "pi-activation.json").exists(), "Release already activated"
require_idle()
live = run("docker", "exec", "heyi-pi-runtime", "cat", "/app/src/server.js")
assert digest(live) == expected["pi-server.js"], "Runtime baseline changed"
backup = root / "pi-rollback"
backup.mkdir(mode=0o700)
(backup / "server.js").write_bytes(live)
for name in ("server.js", "recruitment-analysis.js"):
    assert digest((root / "staged/pi" / name).read_bytes()) == manifest["pi/" + name]
image_dir = root / "pi-image"
image_dir.mkdir()
import shutil
for name in ("server.js", "recruitment-analysis.js"):
    shutil.copy2(root / "staged/pi" / name, image_dir / name)
(image_dir / "Dockerfile").write_text(
    "FROM " + before["Image"] + "\nCOPY server.js recruitment-analysis.js /app/src/\n")
image = "heyi-pi-runtime:" + root.name
run("docker", "build", "-t", image, str(image_dir))
run("docker", "run", "--rm", "--entrypoint", "node", image, "--check", "/app/src/server.js")
run("docker", "run", "--rm", "--entrypoint", "node", image, "--check", "/app/src/recruitment-analysis.js")


def activate(target):
    labels = before["Config"]["Labels"]
    override = root / "compose.pi.json"
    override.write_text(json.dumps({"services": {labels["com.docker.compose.service"]: {"image": target}}}))
    command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f", file]
    run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never",
        labels["com.docker.compose.service"])
    after = inspect()
    mounts = lambda info: sorted((m["Source"], m["Destination"], m["RW"]) for m in info["Mounts"])
    assert sorted(after["Config"]["Env"]) == sorted(before["Config"]["Env"]), "Runtime environment changed"
    assert mounts(after) == mounts(before), "Runtime mounts changed"
    assert after["HostConfig"]["PortBindings"] == before["HostConfig"]["PortBindings"]
    for _ in range(30):
        try:
            health = json.loads(run("docker", "exec", "heyi-pi-runtime", "node", "-e",
                                    'fetch("http://127.0.0.1:8090/health").then(r=>r.text()).then(console.log)'))
            if health["status"] == "UP":
                return
        except (subprocess.CalledProcessError, ValueError, KeyError):
            pass
        time.sleep(1)
    raise RuntimeError("Runtime health failed")


require_idle()
try:
    activate(image)
    for name in ("server.js", "recruitment-analysis.js"):
        assert digest(run("docker", "exec", "heyi-pi-runtime", "cat", "/app/src/" + name)) == manifest["pi/" + name]
    run("python3", str(root / "release-sourcing.py"), str(root))
except BaseException:
    activate(before["Image"])
    raise
(root / "pi-activation.json").write_text(json.dumps({
    "image": image, "previousImage": before["Config"]["Image"], "sdk": "@earendil-works/pi-ai",
    "maxRetries": 0, "fallback": False, "unrelatedRuntimeFilesPreserved": True,
    "activatedAt": time.time(),
}, indent=2))
shutil.rmtree(image_dir)
print("Recruitment pi SDK route and HR progress activated; environment and mounts preserved")
