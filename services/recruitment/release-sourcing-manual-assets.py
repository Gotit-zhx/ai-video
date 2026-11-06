"""Publish only the reviewed sourcing script on the exact live frontend image."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def run(*args, **kwargs):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, **kwargs)


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def sha(data):
    return hashlib.sha256(data).hexdigest()


root = Path(sys.argv[1]).resolve()
expected = json.loads((root / "expected-baseline.json").read_text())
container = "ai-commerce-frontend"
before = inspect(container)
preserved = {name: inspect(name)["Id"] for name in ("ai-commerce-app", "heyi-pi-runtime")}
image_dir, backup = root / "image", root / "rollback"
image_dir.mkdir(exist_ok=True)
backup.mkdir(exist_ok=True)
for name in ("index.html", "hr-sourcing.js"):
    data = run("docker", "exec", container, "cat", "/usr/share/nginx/html/" + name)
    assert sha(data) == expected[name], "Frontend baseline changed: " + name
    (backup / name).write_bytes(data)
shutil.copy2(root / "staged/frontend/hr-sourcing.js", image_dir / "hr-sourcing.js")
index, count = re.subn(r'(\bsrc=["\']/?hr-sourcing\.js)(?:\?[^"\']*)?(["\'])',
                      r"\g<1>?v=20261016-manual-sourcing\2", (backup / "index.html").read_text())
assert count == 1, "Expected exactly one sourcing script"
(image_dir / "index.html").write_text(index)
image = "ai-commerce-frontend:manual-sourcing-20261016"
(image_dir / "Dockerfile").write_text(
    "FROM " + before["Image"] + "\nCOPY index.html hr-sourcing.js /usr/share/nginx/html/\n")
run("docker", "build", "-t", image, str(image_dir))
assert inspect(container)["Id"] == before["Id"], "Frontend changed during staging"
for name, digest in expected.items():
    assert sha(run("docker", "exec", container, "cat", "/usr/share/nginx/html/" + name)) == digest
labels = before["Config"]["Labels"]
compose = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
           "-p", labels["com.docker.compose.project"]]
for file in labels["com.docker.compose.project.config_files"].split(","):
    compose += ["-f", file]
service = labels["com.docker.compose.service"]
override, rollback = root / "compose.sourcing.json", root / "compose.rollback.json"
override.write_text(json.dumps({"services": {service: {"image": image}}}))
rollback.write_text(json.dumps({"services": {service: {"image": before["Config"]["Image"]}}}))
env = {**os.environ, "HR_FRONTEND_IMAGE": before["Config"]["Image"]}
run(*compose, "-f", str(override), "config", "-q", env=env)
try:
    run(*compose, "-f", str(override), "up", "-d", "--no-deps", "--no-build", service, env=env)
    run("docker", "exec", container, "nginx", "-t")
    hashes = {}
    for name in ("index.html", "hr-sourcing.js"):
        actual = run("docker", "exec", container, "cat", "/usr/share/nginx/html/" + name)
        assert sha(actual) == sha((image_dir / name).read_bytes()), "Asset mismatch"
        hashes[name] = sha(actual)
    assert all(inspect(name)["Id"] == value for name, value in preserved.items())
    assert inspect(container)["HostConfig"]["Binds"] == before["HostConfig"]["Binds"]
except BaseException:
    run(*compose, "-f", str(rollback), "up", "-d", "--no-deps", "--no-build", service, env=env)
    raise
result = {"ok": True, "image": image, "previousImage": before["Config"]["Image"],
          "assets": hashes, "otherServicesUnchanged": True, "databaseModified": False}
(root / "result.json").write_text(json.dumps(result, indent=2))
print(json.dumps(result, indent=2))
