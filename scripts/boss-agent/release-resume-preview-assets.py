"""Apply only reviewed resume assets on top of the currently running frontend.

Usage on the server: python3 release-resume-preview-assets.py RELEASE_DIRECTORY
The directory contains expected-baseline.json and staged/frontend.
"""
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
other_ids = {name: inspect(name)["Id"] for name in ("ai-commerce-app", "heyi-pi-runtime")}
image_dir = root / "image"
backup = root / "rollback"
image_dir.mkdir(exist_ok=True)
backup.mkdir(exist_ok=True)
for name in ("index.html", "hr-passive.js", "hr-resume-preview.js"):
    data = run("docker", "exec", container, "cat", "/usr/share/nginx/html/" + name)
    assert sha(data) == expected[name], "Frontend baseline changed: " + name
    (backup / name).write_bytes(data)
    if name != "index.html":
        shutil.copy2(root / "staged/frontend" / name, image_dir / name)

index = (backup / "index.html").read_text()
for name in ("hr-passive.js", "hr-resume-preview.js"):
    pattern = r'(\bsrc=["\']/?' + re.escape(name) + r')(?:\?[^"\']*)?(["\'])'
    index, count = re.subn(pattern, r"\g<1>?v=20261016-resume-compat\2", index)
    assert count == 1, "Expected one script tag: " + name
(image_dir / "index.html").write_text(index)
shutil.copytree(root / "staged/frontend/vendor/pdfjs-legacy", image_dir / "pdfjs-legacy", dirs_exist_ok=True)
image = "ai-commerce-frontend:resume-compat-20261016"
(image_dir / "Dockerfile").write_text(
    "FROM " + before["Image"] + "\n"
    "COPY index.html hr-passive.js hr-resume-preview.js /usr/share/nginx/html/\n"
    "COPY pdfjs-legacy /usr/share/nginx/html/vendor/pdfjs-legacy/\n")
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
override = root / "compose.resume.json"
rollback = root / "compose.rollback.json"
override.write_text(json.dumps({"services": {service: {"image": image}}}))
rollback.write_text(json.dumps({"services": {service: {"image": before["Config"]["Image"]}}}))
env = {**os.environ, "HR_FRONTEND_IMAGE": before["Config"]["Image"]}
run(*compose, "-f", str(override), "config", "-q", env=env)
try:
    run(*compose, "-f", str(override), "up", "-d", "--no-deps", "--no-build", service, env=env)
    run("docker", "exec", container, "nginx", "-t")
    files = {
        "index.html": image_dir / "index.html",
        "hr-passive.js": image_dir / "hr-passive.js",
        "hr-resume-preview.js": image_dir / "hr-resume-preview.js",
        **{"vendor/pdfjs-legacy/" + file.name: file for file in (image_dir / "pdfjs-legacy").iterdir() if file.is_file()},
    }
    hashes = {}
    for name, file in files.items():
        actual = run("docker", "exec", container, "cat", "/usr/share/nginx/html/" + name)
        assert sha(actual) == sha(file.read_bytes()), "Asset verification failed: " + name
        hashes[name] = sha(actual)
    assert all(inspect(name)["Id"] == value for name, value in other_ids.items())
    assert inspect(container)["HostConfig"]["Binds"] == before["HostConfig"]["Binds"]
except BaseException:
    run(*compose, "-f", str(rollback), "up", "-d", "--no-deps", "--no-build", service, env=env)
    raise
result = {"ok": True, "image": image, "previousImage": before["Config"]["Image"],
          "assets": hashes, "otherServicesUnchanged": True, "databaseModified": False}
(root / "result.json").write_text(json.dumps(result, indent=2))
print(json.dumps(result, indent=2))
