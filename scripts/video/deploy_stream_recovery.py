"""Patch reviewed image classes/runtime onto the exact live images; preserve all other work."""
import hashlib
import json
import pathlib
import subprocess
import sys
import time
import zipfile

root = pathlib.Path(sys.argv[1]).resolve()
manifest = json.loads((root / "manifest.json").read_text())
families = {"AiVideoImageProcessor", "AiVideoImageGateway", "AiVideoImageAttempts"}
prefix = "BOOT-INF/classes/com/yingdao/aicommerce/video/application/"


def run(*args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def idle():
    query = ";".join([
        "SELECT COUNT(*) FROM ai_video_task_items WHERE status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING')",
        "SELECT COUNT(*) FROM video_scripts WHERE status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
        "SELECT COUNT(*) FROM product_image_processing_jobs WHERE status IN ('PENDING','QUEUED','RUNNING','PROCESSING')",
        "SELECT COUNT(*) FROM product_publish_workflows WHERE status IN ('PENDING','QUEUED','RUNNING','PUBLISHING','PROCESSING_IMAGES')",
        "SELECT COUNT(*) FROM commerce_batch_executions WHERE status IN ('QUEUED','RUNNING','PAUSING')",
    ])
    counts = run("docker", "exec", "ai-commerce-mysql", "sh", "-c",
                 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB -e "$1"', "q", query)
    if any(int(value) for value in counts.split()):
        raise RuntimeError("Workers are active; do not restart")
    # Conservatively retain every recent operation that has not reached a terminal state.
    for file in pathlib.Path("/var/lib/docker/volumes/heyi_image_operation_data/_data").glob("*.json"):
        if file.stat().st_mtime < time.time() - 86400:
            continue
        record = json.loads(file.read_text())
        if record.get("status") == "RUNNING":
            # Running records are conservatively retained even if a previous process died.
            raise RuntimeError("A recent image operation is still RUNNING; review before activation")


def activate(before, image, name):
    labels = before["Config"]["Labels"]
    override = root / (name + ".compose.json")
    override.write_text(json.dumps({"services": {labels["com.docker.compose.service"]: {"image": image}}}))
    command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f", file]
    run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never",
        labels["com.docker.compose.service"])
    after = inspect(name)
    if (sorted(before["Config"]["Env"]) != sorted(after["Config"]["Env"])
            or sorted(before["Mounts"], key=lambda v: v["Destination"]) != sorted(after["Mounts"], key=lambda v: v["Destination"])
            or before["HostConfig"]["PortBindings"] != after["HostConfig"]["PortBindings"]):
        raise RuntimeError("Container configuration changed")


before = {name: inspect(name) for name in ("ai-commerce-app", "heyi-pi-runtime")}
for name, info in before.items():
    (root / (name + ".before.json")).write_text(json.dumps(info))
idle()
run("docker", "cp", "ai-commerce-app:/app/app.jar", str(root / "activation-baseline.jar"))
if digest((root / "activation-baseline.jar").read_bytes()) != manifest["jar"]:
    raise RuntimeError("Live JAR changed since review")
runtime_before = run("docker", "exec", "heyi-pi-runtime", "cat", "/app/src/server.js")
if digest(runtime_before) != manifest["runtimeServer"]:
    raise RuntimeError("Runtime changed since review")
for file, sha in manifest["files"].items():
    if digest((root / file).read_bytes()) != sha:
        raise RuntimeError("Patch changed: " + file)
classes = {}
for file in (root / "classes").glob("*.class"):
    if file.stem.split("$")[0] not in families:
        raise RuntimeError("Unexpected class")
    classes[prefix + file.name] = file.read_bytes()
if {pathlib.Path(name).stem.split("$")[0] for name in classes} != families:
    raise RuntimeError("Incomplete class patch")
app = root / "app-image"
app.mkdir(exist_ok=True)
with zipfile.ZipFile(root / "activation-baseline.jar") as original, zipfile.ZipFile(app / "app.jar", "w") as target:
    for info in original.infolist():
        target.writestr(info, classes.get(info.filename, original.read(info.filename)))
    for name in classes.keys() - set(original.namelist()):
        target.writestr(name, classes[name])
    target.comment = original.comment
with zipfile.ZipFile(root / "activation-baseline.jar") as original, zipfile.ZipFile(app / "app.jar") as patched:
    for name in original.namelist():
        if name not in classes and original.read(name) != patched.read(name):
            raise RuntimeError("Unrelated JAR content changed: " + name)
(app / "Dockerfile").write_text("FROM " + before["ai-commerce-app"]["Image"] + "\nCOPY app.jar /app/app.jar\n")
runtime = root / "runtime-image"
(runtime / "Dockerfile").write_text("FROM " + before["heyi-pi-runtime"]["Image"]
    + "\nCOPY server.js image-stream-response.js /app/src/\n")
images = {"ai-commerce-app": "ai-commerce-platform:video-stream-20260929",
          "heyi-pi-runtime": "heyi-pi-runtime:video-stream-20260929"}
run("docker", "build", "-t", images["ai-commerce-app"], str(app))
run("docker", "build", "-t", images["heyi-pi-runtime"], str(runtime))
idle()
activated = []
try:
    for name in ("heyi-pi-runtime", "ai-commerce-app"):
        activated.append(name)
        activate(before[name], images[name], name)
    for _ in range(60):
        try:
            value = json.loads(run("docker", "exec", "ai-commerce-app", "curl", "-fsS", "--max-time", "3",
                                   "http://127.0.0.1:8080/actuator/health"))
            if value["status"] == "UP":
                break
        except Exception:
            pass
        time.sleep(1)
    else:
        raise RuntimeError("App health check failed")
    (root / "activation.json").write_text(json.dumps({
        "images": images, "jar": digest((app / "app.jar").read_bytes()),
        "unchangedOtherJarEntries": True, "activatedAt": time.time()}, indent=2))
    print("Activated: health UP; original environment, volumes, SSH and unrelated JAR entries preserved")
except Exception:
    for name in reversed(activated):
        activate(before[name], before[name]["Image"], name)
    raise
