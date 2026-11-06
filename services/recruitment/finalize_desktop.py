"""Persist the reviewed desktop frontend and verified OS installers.

Run with the staged release directory. Only the frontend container is replaced;
the app, image runtime, browser and active provider jobs keep their processes.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
expected = json.loads((root / "expected-baseline.json").read_text())
artifacts = json.loads((root / "artifacts.json").read_text())
downloads = Path("/opt/heyi/shared/hr-desktop-downloads")
container = "ai-commerce-frontend"


def run(*args, **kwargs):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, **kwargs)


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def sha(data):
    return hashlib.sha256(data).hexdigest()


before = inspect(container)
preserved = {name: inspect(name)["Id"] for name in ("ai-commerce-app", "heyi-pi-runtime")}
backup = root / "rollback"
backup.mkdir(exist_ok=True)
(backup / "frontend-inspect.json").write_text(json.dumps(before))
(backup / "frontend-inspect.json").chmod(0o600)
image_dir = root / "frontend-image"
image_dir.mkdir(exist_ok=True)
for name in ("index.html", "hr-workspace.js", "hr-workspace.css", "hr-desktop-bridge.js", "nginx.conf"):
    source = "/etc/nginx/conf.d/default.conf" if name == "nginx.conf" else "/usr/share/nginx/html/" + name
    live = run("docker", "exec", container, "cat", source)
    if name in expected and sha(live) != expected[name]:
        raise RuntimeError("Frontend changed since review: " + name)
    (backup / name).write_bytes(live)
    staged = root / "staged" / name
    (image_dir / name).write_bytes(staged.read_bytes() if staged.exists() else live)

required = {"Heyi-Recruiter-mac-arm64.dmg", "Heyi-Recruiter-windows-x64-Setup.exe"}
assert set(artifacts) == required
for name, record in artifacts.items():
    file = root / "artifacts" / name
    assert file.stat().st_size == record["size"] and record["size"] > 10_000_000
    assert sha(file.read_bytes()) == record["sha256"], "Installer checksum mismatch"
downloads.mkdir(exist_ok=True, parents=True)
for name in required:
    temporary = downloads / (name + ".new")
    shutil.copyfile(root / "artifacts" / name, temporary)
    temporary.chmod(0o644)
    temporary.replace(downloads / name)
(downloads / "checksums.json").write_text(json.dumps(artifacts, indent=2))

image = "ai-commerce-frontend:hr-desktop-direct-20260928"
(image_dir / "Dockerfile").write_text(
    "FROM " + before["Image"] + "\n"
    "COPY index.html hr-workspace.js hr-workspace.css hr-desktop-bridge.js /usr/share/nginx/html/\n"
    "COPY nginx.conf /etc/nginx/conf.d/default.conf\n")
run("docker", "build", "-t", image, str(image_dir))
labels = before["Config"]["Labels"]
compose = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
           "-p", labels["com.docker.compose.project"]]
for file in labels["com.docker.compose.project.config_files"].split(","):
    compose += ["-f", file]
env = {**os.environ, "HR_FRONTEND_IMAGE": before["Config"]["Image"]}
override = root / "compose.desktop.json"
override.write_text(json.dumps({"services": {labels["com.docker.compose.service"]: {
    "image": image, "volumes": [str(downloads) + ":/usr/share/nginx/html/downloads:ro"]}}}, indent=2))
service = labels["com.docker.compose.service"]
run(*compose, "-f", str(override), "config", "-q", env=env)
# Validate TLS paths, upstream names and syntax with the existing Docker network.
network = next(iter(before["NetworkSettings"]["Networks"]))
run("docker", "run", "--rm", "--network", network, "--entrypoint", "nginx",
    "-v", "/etc/letsencrypt:/etc/letsencrypt:ro",
    "-v", "/opt/heyi/shared/certbot-webroot:/var/www/certbot:ro", image, "-t")
try:
    run(*compose, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never", service, env=env)
    run("docker", "exec", container, "nginx", "-t")
    for name in ("index.html", "hr-workspace.js", "hr-workspace.css", "hr-desktop-bridge.js"):
        assert sha(run("docker", "exec", container, "cat", "/usr/share/nginx/html/" + name)) == sha((image_dir / name).read_bytes())
    for name, record in artifacts.items():
        headers = run("curl", "-fsSI", "--max-time", "10", "--resolve", "hoyee.org.cn:443:127.0.0.1",
                      "https://hoyee.org.cn/downloads/" + name).decode().lower()
        assert "content-length: " + str(record["size"]) in headers
        assert "content-type: application/octet-stream" in headers
    assert preserved == {name: inspect(name)["Id"] for name in preserved}
except Exception:
    # The old container included hot frontend files. Build a rollback image that
    # retains those files instead of reverting to an older, unpatched image.
    for name in ("index.html", "hr-workspace.js", "hr-workspace.css", "hr-desktop-bridge.js", "nginx.conf"):
        shutil.copy2(backup / name, image_dir / name)
    rollback_image = image + "-rollback"
    run("docker", "build", "-t", rollback_image, str(image_dir))
    override.write_text(json.dumps({"services": {service: {"image": rollback_image}}}))
    run(*compose, "-f", str(override), "up", "-d", "--no-deps", "--no-build", service, env=env)
    raise

record = {"version": "0.1.1", "frontendImage": inspect(container)["Image"],
          "preservedContainers": preserved, "artifacts": artifacts}
(root / "activation.json").write_text(json.dumps(record, indent=2))
print("Desktop frontend persisted; Mac/Windows installers verified; video workers preserved")
