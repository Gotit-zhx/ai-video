"""Scoped image recovery release, run on the host with a reviewed staged bundle.

Patches four Java class families and two runtime files onto the exact live images.
No database writes, server reboot, frontend replacement or network configuration.
"""
import hashlib
import json
import pathlib
import subprocess
import sys
import time
import zipfile

root = pathlib.Path(sys.argv[1]).resolve()
manifest = json.loads((root / "patch/manifest.json").read_text())
families = ("AiVideoImageProcessor", "ImageRuntimeProgress", "AiVideoImageGateway", "AiVideoImageAttempts")
prefix = "BOOT-INF/classes/com/yingdao/aicommerce/video/application/"


def run(*args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def sql(query):
    return run("docker", "exec", "ai-commerce-mysql", "sh", "-c",
               'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB -e "$1"',
               "query", query).decode().strip()


def require_idle():
    queries = [
        "SELECT COUNT(*) FROM ai_video_task_items WHERE status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING')",
        "SELECT COUNT(*) FROM video_scripts WHERE status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
        "SELECT COUNT(*) FROM product_image_processing_jobs WHERE status IN ('PENDING','QUEUED','RUNNING','PROCESSING')",
        "SELECT COUNT(*) FROM product_publish_workflows WHERE status IN ('PENDING','QUEUED','RUNNING','PUBLISHING','PROCESSING_IMAGES')",
        "SELECT COUNT(*) FROM commerce_batch_executions WHERE status IN ('QUEUED','RUNNING','PAUSING')",
    ]
    if any(int(v) for v in sql(";".join(queries)).split()):
        raise RuntimeError("Active workers must finish before activation")
    # A timed-out legacy Java caller can be idle while its paid image is still
    # running. Do not kill the runtime before it saves that original response.
    after = manifest["activeAfterEpochMs"]
    operations = pathlib.Path("/var/lib/docker/volumes/heyi_image_operation_data/_data")
    for file in operations.glob("*.json"):
        if file.stat().st_mtime * 1000 < after:
            continue
        record = json.loads(file.read_text())
        if record.get("createdAt", 0) >= after and record.get("status") == "RUNNING":
            raise RuntimeError("Original paid image requests are still returning; retain the runtime")


def compose(before, image, suffix):
    labels = before["Config"]["Labels"]
    override = root / ("compose." + suffix + ".json")
    override.write_text(json.dumps({"services": {labels["com.docker.compose.service"]: {"image": image}}}))
    command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f", file]
    run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never",
        labels["com.docker.compose.service"])


def equivalent(before, after):
    mounts = lambda d: sorted(d["Mounts"], key=lambda v: v["Destination"])
    return (mounts(before) == mounts(after)
            and sorted(before["Config"]["Env"]) == sorted(after["Config"]["Env"])
            and before["HostConfig"]["PortBindings"] == after["HostConfig"]["PortBindings"])


before = {name: inspect(name) for name in ("ai-commerce-app", "heyi-pi-runtime")}
for name, data in before.items():
    if data["Id"] != json.loads((root / (name + "-before.json")).read_text())["Id"]:
        raise RuntimeError("Baseline container changed: " + name)
for path, digest in manifest["files"].items():
    if sha((root / "patch" / path).read_bytes()) != digest:
        raise RuntimeError("Staged patch changed: " + path)
require_idle()
run("docker", "cp", "ai-commerce-app:/app/app.jar", str(root / "activation-before.jar"))
if sha((root / "activation-before.jar").read_bytes()) != sha((root / "before.jar").read_bytes()):
    raise RuntimeError("Live JAR differs from reviewed baseline")
replacements = {}
for file in (root / "patch/classes").glob("*.class"):
    if file.stem.split("$")[0] not in families:
        raise RuntimeError("Unexpected Java class")
    replacements[prefix + file.name] = file.read_bytes()
if {pathlib.Path(p).stem.split("$")[0] for p in replacements} != set(families):
    raise RuntimeError("Incomplete class patch")
with zipfile.ZipFile(root / "before.jar") as source, zipfile.ZipFile(root / "app.jar", "w") as target:
    for name, digest in manifest["baselineClasses"].items():
        if sha(source.read(prefix + name)) != digest:
            raise RuntimeError("Baseline class differs: " + name)
    for info in source.infolist():
        target.writestr(info, replacements.get(info.filename, source.read(info.filename)))
    for name in replacements.keys() - set(source.namelist()):
        target.writestr(name, replacements[name])
    target.comment = source.comment
app_image = root / "app-image"
app_image.mkdir(exist_ok=True)
(app_image / "app.jar").write_bytes((root / "app.jar").read_bytes())
(app_image / "Dockerfile").write_text("FROM " + before["ai-commerce-app"]["Image"] + "\nCOPY app.jar /app/app.jar\n")
runtime_image = root / "runtime-image"
runtime_image.mkdir(exist_ok=True)
for name in ("server.js", "image-model-fallback.js"):
    live = run("docker", "exec", "heyi-pi-runtime", "cat", "/app/src/" + name)
    if sha(live) != manifest["baselineRuntime"][name]:
        raise RuntimeError("Live runtime file changed: " + name)
    (runtime_image / name).write_bytes((root / "patch/runtime" / name).read_bytes())
(runtime_image / "Dockerfile").write_text("FROM " + before["heyi-pi-runtime"]["Image"]
                                        + "\nCOPY server.js image-model-fallback.js /app/src/\n")
images = {"heyi-pi-runtime": "heyi-pi-runtime:video-grid-recovery-20260928",
          "ai-commerce-app": "ai-commerce-platform:video-grid-recovery-20260928"}
run("docker", "build", "-t", images["heyi-pi-runtime"], str(runtime_image))
run("docker", "build", "-t", images["ai-commerce-app"], str(app_image))
require_idle()
activated = []
try:
    for name in ("heyi-pi-runtime", "ai-commerce-app"):
        compose(before[name], images[name], name)
        activated.append(name)
        if not equivalent(before[name], inspect(name)):
            raise RuntimeError("Runtime configuration changed: " + name)
    for attempt in range(60):
        try:
            health = json.loads(run("docker", "exec", "ai-commerce-app", "curl", "-fsS", "--max-time", "3",
                                    "http://127.0.0.1:8080/actuator/health"))
            if health["status"] == "UP":
                break
        except Exception:
            pass
        time.sleep(1)
    else:
        raise RuntimeError("Application did not become healthy")
    if sha(run("docker", "exec", "ai-commerce-app", "cat", "/app/app.jar")) != sha((root / "app.jar").read_bytes()):
        raise RuntimeError("Activated JAR mismatch")
    (root / "activation.json").write_text(json.dumps({
        name: {"image": inspect(name)["Image"], "startedAt": inspect(name)["State"]["StartedAt"]}
        for name in images}, indent=2))
    print("Image recovery activated; service health UP; existing environment and volumes preserved")
except Exception:
    for name in reversed(activated):
        compose(before[name], before[name]["Image"], name + "-rollback")
    raise
