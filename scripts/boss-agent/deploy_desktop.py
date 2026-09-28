"""Run on production after copying staged files and expected-baseline.json.

Only the HR connector and frontend are changed; commerce and Chrome stay up.
Artifacts are copied separately after their OS-specific checks pass.
"""
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import sys

release = Path(sys.argv[1]).resolve()
staged, backup = release / "staged", release / "rollback"
backup.mkdir(parents=True, exist_ok=True)
expected = json.loads((release / "expected-baseline.json").read_text())
container = "ai-commerce-frontend"
files = ["index.html", "hr-workspace.js", "hr-workspace.css"]


def run(*args):
    return subprocess.check_output(args)


for name in files:
    content = run("docker", "exec", container, "cat", "/usr/share/nginx/html/" + name)
    if hashlib.sha256(content).hexdigest() != expected[name]:
        raise SystemExit("Production file changed since review: " + name)
    (backup / name).write_bytes(content)
connector = Path("/opt/heyi/boss-agent/bin/connector.py")
if hashlib.sha256(connector.read_bytes()).hexdigest() != expected["connector.py"]:
    raise SystemExit("Production connector changed since review")
shutil.copy2(connector, backup / "connector.py")
nginx = run("docker", "exec", container, "cat", "/etc/nginx/conf.d/default.conf")
if hashlib.sha256(nginx).hexdigest() != expected["nginx.conf"]:
    raise SystemExit("Production nginx changed since review")
(backup / "nginx.conf").write_bytes(nginx)

preserved = {
    "commerce_started": run("docker", "inspect", "--format", "{{.State.StartedAt}}", "ai-commerce-app").decode().strip(),
    "chrome_pid": run("systemctl", "show", "-p", "MainPID", "--value", "heyi-boss-chrome").decode().strip(),
}
for name in ["connector.py", "desktop_bridge.py"]:
    run("/opt/heyi/boss-agent/venv/bin/python", "-m", "py_compile", str(staged / "connector" / name))

# Do not silently resume any existing platform operation during migration.
pause = Path("/opt/heyi/boss-agent/runtime/resume-auto-pause")
pause.touch(mode=0o600, exist_ok=True)
owner = pwd.getpwnam("heyi-boss")
os.chown(pause, owner.pw_uid, owner.pw_gid)
dropin = Path("/etc/systemd/system/heyi-boss-connector.service.d/desktop.conf")
dropin.parent.mkdir(parents=True, exist_ok=True)
if dropin.exists():
    shutil.copy2(dropin, backup / "desktop.conf")
dropin.write_text("[Service]\nEnvironment=APP_HR_DESKTOP_ONLY=1\n")
for name in ["connector.py", "desktop_bridge.py"]:
    target = connector.parent / name
    shutil.copy2(staged / "connector" / name, target)
    os.chown(target, owner.pw_uid, owner.pw_gid)
    target.chmod(0o640)
run("systemctl", "daemon-reload")
run("systemctl", "restart", "heyi-boss-connector")
run("systemctl", "is-active", "--quiet", "heyi-boss-connector")

for name in files + ["hr-desktop-bridge.js"]:
    run("docker", "cp", str(staged / "frontend" / name), container + ":/usr/share/nginx/html/" + name)
run("docker", "cp", str(staged / "frontend/nginx.conf"), container + ":/etc/nginx/conf.d/default.conf")
try:
    run("docker", "exec", container, "nginx", "-t")
except subprocess.CalledProcessError:
    run("docker", "cp", str(backup / "nginx.conf"), container + ":/etc/nginx/conf.d/default.conf")
    raise
run("docker", "exec", container, "nginx", "-s", "reload")
after = {
    "commerce_started": run("docker", "inspect", "--format", "{{.State.StartedAt}}", "ai-commerce-app").decode().strip(),
    "chrome_pid": run("systemctl", "show", "-p", "MainPID", "--value", "heyi-boss-chrome").decode().strip(),
}
assert after == preserved, "Unrelated service changed during release"
(release / "preserved-services.json").write_text(json.dumps(preserved, indent=2))
print("HR desktop bridge deployed; commerce and Chrome preserved")
