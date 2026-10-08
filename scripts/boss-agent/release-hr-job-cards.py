"""Publish reviewed HR cards without restarting the application or BOSS."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

root = Path(sys.argv[1]).resolve()
expected = json.loads((root / "expected.json").read_text())
manifest = json.loads((root / "manifest.json").read_text())
files = ("index.html", "hr-workspace.js", "hr-workspace.css", "hr-passive.js")
if "app.js" in manifest:
    files += ("app.js",)
for optional in ("hr-demands.js", "hr-sourcing.js", "hr-pool.js"):
    if optional in manifest:
        files += (optional,)
assert not (root / "activation.json").exists()
assert shutil.disk_usage(root).free >= 2 * 1024**3
backup = root / "rollback"
backup.mkdir(exist_ok=True)


def run(*args, **kwargs):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, **kwargs)


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def sha(data):
    return hashlib.sha256(data).hexdigest()


before = inspect("ai-commerce-frontend")
preserved = {
    info["Name"]: info["Id"] for info in json.loads(
        run("docker", "inspect", *run("docker", "ps", "-q").decode().split()))
    if info["Name"] != "/ai-commerce-frontend"
}
image_directory = root / "frontend-image"
image_directory.mkdir(exist_ok=True)
for name in files:
    live = run("docker", "exec", "ai-commerce-frontend", "cat", "/usr/share/nginx/html/" + name)
    assert sha(live) == expected[name], "Live frontend changed: " + name
    (backup / name).write_bytes(live)
    staged = root / "staged" / name
    assert sha(staged.read_bytes()) == manifest[name], "Staged mismatch: " + name
    shutil.copy2(staged, image_directory / name)
image = "ai-commerce-frontend:" + root.name
(image_directory / "Dockerfile").write_text(
    "FROM " + before["Image"] + "\nCOPY " + " ".join(files) + " /usr/share/nginx/html/\n"
)
run("docker", "build", "-t", image, str(image_directory))
labels = before["Config"]["Labels"]
service = labels["com.docker.compose.service"]
command = ["docker", "compose", "--project-directory",
           labels["com.docker.compose.project.working_dir"], "-p", labels["com.docker.compose.project"]]
for file in labels["com.docker.compose.project.config_files"].split(","):
    command += ["-f", file]
environment = {**os.environ, "HR_FRONTEND_IMAGE": before["Config"]["Image"]}
override = root / "compose.cards.json"


def activate(tag):
    override.write_text(json.dumps({"services": {service: {"image": tag}}}))
    run(*command, "-f", str(override), "config", "-q", env=environment)
    run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build",
        "--pull", "never", service, env=environment)


try:
    activate(image)
    for name in files:
        for attempt in range(15):
            try:
                served = run("curl", "--noproxy", "*", "-fsS", "--max-time", "5",
                             "--resolve", "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/" + name)
                assert sha(served) == manifest[name]
                break
            except (subprocess.CalledProcessError, AssertionError):
                if attempt == 14:
                    raise
                time.sleep(1)
    after = inspect("ai-commerce-frontend")
    mount_signature = lambda info: sorted(
        (mount["Source"], mount["Destination"], mount["RW"]) for mount in info["Mounts"])
    assert mount_signature(after) == mount_signature(before)
    assert sorted(after["Config"]["Env"]) == sorted(before["Config"]["Env"])
    assert all(inspect(name)["Id"] == identifier for name, identifier in preserved.items())
except BaseException:
    activate(before["Config"]["Image"])
    raise
(root / "activation.json").write_text(json.dumps(
    {"frontendImage": image, "sha256": manifest, "preservedContainers": preserved}, indent=2))
shutil.rmtree(image_directory)
archive = Path("/tmp") / (root.name + ".tar.gz")
if archive.is_file() and not archive.is_symlink():
    archive.unlink()
print("HR cards published; frontend hashes verified; application and BOSS services preserved")
