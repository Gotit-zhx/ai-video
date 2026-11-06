"""Stage and activate only the two installer routes on the exact live frontend."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import subprocess
import urllib.error
import urllib.request

CONTAINER = "ai-commerce-frontend"
FILES = {"nginx.conf": "/etc/nginx/conf.d/default.conf",
         "hr-workspace.js": "/usr/share/nginx/html/hr-workspace.js",
         "index.html": "/usr/share/nginx/html/index.html"}
NAMES = ("Heyi-Recruiter-mac-arm64.dmg", "Heyi-Recruiter-windows-x64-Setup.exe")
IMAGE = "ai-commerce-frontend:ai-boss-downloads-20261019"


def run(*args, **kwargs):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, **kwargs)


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def live_files():
    return {name: run("docker", "exec", CONTAINER, "cat", path) for name, path in FILES.items()}


def assert_no_unrelated_frontend_overlay():
    changes = run("docker", "diff", CONTAINER).decode().splitlines()
    if any("/usr/share/nginx/html" in line for line in changes):
        raise ValueError("Live frontend has uncommitted asset changes; preserve before staging")


def patch_nginx(text):
    for name in NAMES:
        block = ("    location = /downloads/" + name + " {\n"
                 "        access_log off;\n"
                 "        proxy_pass http://172.18.0.1:8767;\n"
                 "        proxy_set_header Cookie $http_cookie;\n"
                 "        proxy_set_header Host $host;\n"
                 "        proxy_connect_timeout 5s;\n"
                 "        proxy_read_timeout 15s;\n"
                 "        proxy_cache off;\n"
                 "        proxy_redirect off;\n"
                 '        add_header Cache-Control "private, no-store" always;\n'
                 "    }")
        pattern = r"(?m)^    location = /downloads/" + re.escape(name) + r" \{[^}]*\}"
        text, count = re.subn(pattern, lambda _: block, text)
        if count != 1:
            raise ValueError("Exactly one existing installer route required")
    return text


def stage(root):
    if (root / "baseline.json").exists():
        raise ValueError("Already staged; inspect the existing baseline")
    assert_no_unrelated_frontend_overlay()
    before = inspect(CONTAINER)
    data = live_files()
    backup, image = root / "rollback", root / "image"
    backup.mkdir()
    image.mkdir()
    for name, raw in data.items():
        (backup / name).write_bytes(raw)
    (image / "nginx.conf").write_text(patch_nginx(data["nginx.conf"].decode()))
    js = data["hr-workspace.js"].decode()
    lines = [line for line in js.splitlines() if all(name in line for name in NAMES)]
    if len(lines) != 1 or lines[0].count("'0.1.24'") != 2:
        raise ValueError("Unexpected live installer menu; review before changing")
    js = js.replace(lines[0], lines[0].replace("'0.1.24'", "'0.1.24-c11aab0'"))
    if js.count("link.title='赫逸招聘助手 '+version;") != 1:
        raise ValueError("Unexpected download label")
    js = js.replace("link.title='赫逸招聘助手 '+version;", "link.title='Ai boss 0.1.24';")
    (image / "hr-workspace.js").write_text(js)
    index, count = re.subn(r'(\bsrc=["\']/?hr-workspace\.js)(?:\?[^"\']*)?(["\'])',
                          r"\g<1>?v=20261019-ai-boss-downloads\2", data["index.html"].decode())
    if count != 1:
        raise ValueError("Exactly one HR workspace script required")
    (image / "index.html").write_text(index)
    labels = before["Config"]["Labels"]
    baseline = {"id": before["Id"], "image": before["Image"], "imageName": before["Config"]["Image"],
                "hashes": {name: sha(raw) for name, raw in data.items()},
                "binds": before["HostConfig"]["Binds"],
                "compose": {key: labels[key] for key in (
                    "com.docker.compose.project.working_dir", "com.docker.compose.project",
                    "com.docker.compose.project.config_files", "com.docker.compose.service")},
                "otherServices": {name: inspect(name)["Id"] for name in
                                  ("ai-commerce-app", "heyi-pi-runtime", "ai-commerce-mysql")},
                "connectorPid": run("systemctl", "show", "heyi-boss-connector", "-p", "MainPID", "--value").decode().strip()}
    (root / "baseline.json").write_text(json.dumps(baseline, indent=2))
    (image / "Dockerfile").write_text(
        "FROM " + before["Image"] + "\nCOPY nginx.conf /etc/nginx/conf.d/default.conf\n"
        "COPY index.html hr-workspace.js /usr/share/nginx/html/\n")
    run("docker", "build", "-t", IMAGE, str(image))
    run("docker", "run", "--rm", "--entrypoint", "nginx",
        "-v", "/etc/letsencrypt:/etc/letsencrypt:ro",
        "-v", "/opt/heyi/shared/certbot-webroot:/var/www/certbot:ro", IMAGE, "-t")
    print("Download route image staged and nginx syntax verified; live services unchanged")


def activate(root):
    baseline = json.loads((root / "baseline.json").read_text())
    assert inspect(CONTAINER)["Id"] == baseline["id"], "Frontend was replaced after staging"
    assert_no_unrelated_frontend_overlay()
    assert {name: sha(raw) for name, raw in live_files().items()} == baseline["hashes"], "Frontend changed"
    reports = {os_name: json.loads((root / (os_name + "-oss.json")).read_text()) for os_name in ("mac", "windows")}
    commit = reports["mac"]["commit"]
    assert commit == "c11aab05813dbfca18d5a43a7104a927b5542ffd" == reports["windows"]["commit"]
    manifest = {"schemaVersion": 1, "commit": commit,
                "artifacts": {name: report["artifact"] for name, report in reports.items()}}
    # Import only the new download service, never the running recruitment connector.
    import sys
    sys.path.insert(0, "/opt/heyi/boss-agent/bin")
    sys.path.insert(0, str(root))
    import desktop_installers
    staged_manifest = root / "verified-downloads.json"
    staged_manifest.write_text(json.dumps(manifest, indent=2))
    desktop_installers.read_manifest(staged_manifest)
    owner = pwd.getpwnam("heyi-boss")
    directory = Path("/opt/heyi/desktop-installers")
    directory.mkdir(exist_ok=True, mode=0o750)
    os.chown(directory, 0, owner.pw_gid)
    current = directory / "current.json"
    if current.exists():
        raise ValueError("Download service already configured; review before replacing it")
    unit = Path("/etc/systemd/system/heyi-desktop-downloads.service")
    if unit.exists():
        raise ValueError("Download service already exists; review its configuration")
    target = Path("/opt/heyi/boss-agent/bin/desktop_installers.py")
    if target.exists():
        raise ValueError("Download module already exists; review before replacing it")
    shutil.copy2(root / "desktop_installers.py", target)
    os.chown(target, 0, owner.pw_gid)
    target.chmod(0o640)
    temporary = directory / "current.json.new"
    shutil.copy2(staged_manifest, temporary)
    os.chown(temporary, 0, owner.pw_gid)
    temporary.chmod(0o640)
    temporary.replace(current)
    shutil.copy2(root / "heyi-desktop-downloads.service", unit)
    unit.chmod(0o644)
    labels = baseline["compose"]
    compose = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        compose += ["-f", file]
    service = labels["com.docker.compose.service"]
    override, rollback = root / "compose.downloads.json", root / "compose.rollback.json"
    override.write_text(json.dumps({"services": {service: {"image": IMAGE}}}))
    rollback.write_text(json.dumps({"services": {service: {"image": baseline["imageName"]}}}))
    env = {**os.environ, "HR_FRONTEND_IMAGE": baseline["imageName"]}
    run(*compose, "-f", str(override), "config", "-q", env=env)
    switched = False
    try:
        run("systemctl", "daemon-reload")
        run("systemctl", "enable", "--now", "heyi-desktop-downloads")
        run("systemctl", "is-active", "--quiet", "heyi-desktop-downloads")
        # A request with no session must never yield a storage URL.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        import time
        for attempt in range(20):
            try:
                opener.open("http://172.18.0.1:8767/downloads/" + NAMES[0], timeout=2)
                raise ValueError("Unauthenticated downloads must be rejected")
            except urllib.error.HTTPError as error:
                assert error.code == 401 and not error.headers.get("Location")
                break
            except urllib.error.URLError:
                if attempt == 19:
                    raise
                time.sleep(0.2)
        assert inspect(CONTAINER)["Id"] == baseline["id"]
        assert {name: sha(raw) for name, raw in live_files().items()} == baseline["hashes"]
        switched = True
        run(*compose, "-f", str(override), "up", "-d", "--no-deps", "--no-build", service, env=env)
        run("docker", "exec", CONTAINER, "nginx", "-t")
        assert all(inspect(name)["Id"] == value for name, value in baseline["otherServices"].items())
        assert run("systemctl", "show", "heyi-boss-connector", "-p", "MainPID", "--value").decode().strip() == baseline["connectorPid"]
        assert inspect(CONTAINER)["HostConfig"]["Binds"] == baseline["binds"]
        hashes = {name: sha(raw) for name, raw in live_files().items()}
        assert hashes == {name: sha((root / "image" / name).read_bytes()) for name in FILES}
        (root / "activation.json").write_text(json.dumps({
            "ok": True, "commit": commit, "image": IMAGE, "hashes": hashes,
            "otherServicesUnchanged": True, "recruitmentConnectorUnchanged": True,
            "databasesModified": False, "installersPrivate": True,
        }, indent=2))
        print("Both installer routes activated; recruitment, commerce, Pi and database services preserved")
    except BaseException:
        if switched:
            run(*compose, "-f", str(rollback), "up", "-d", "--no-deps", "--no-build", service, env=env)
            for name, destination in FILES.items():
                run("docker", "cp", str(root / "rollback" / name), CONTAINER + ":" + destination)
            run("docker", "exec", CONTAINER, "nginx", "-s", "reload")
        run("systemctl", "disable", "--now", "heyi-desktop-downloads")
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("stage", "activate"))
    parser.add_argument("root", type=Path)
    options = parser.parse_args()
    globals()[options.operation](options.root.resolve())
