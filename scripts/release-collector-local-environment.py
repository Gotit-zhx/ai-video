"""Publish only the extension download and version label over the live frontend."""
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import sys
import time
import urllib.request
import zipfile

ROOT = Path(sys.argv[1]).resolve()
SOURCE = Path("/opt/heyi/releases/finance-settled-goods-20261026/release-finance-settled-goods.py")
spec = importlib.util.spec_from_file_location("collector_release_base", SOURCE)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
core = release.core
FRONT = "ai-commerce-frontend"
OTHER = ("ai-commerce-app", "ai-commerce-mysql", "ai-commerce-redis")
TAG = "ai-commerce-frontend:collector-local-env-028-20261027"
PARENT = "ai-commerce-frontend:collector-parent-028-20261027"
PACKAGE = "hoyee-assistant-0.2.8.zip"
CONFIG = "/etc/nginx/conf.d/default.conf"
INDEX = "/usr/share/nginx/html/index.html"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify_public():
    expected = (ROOT / PACKAGE).read_bytes()
    request = urllib.request.Request(
        "https://hoyee.org.cn/downloads/hoyee-assistant-latest.zip",
        headers={"Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=25) as response:
        actual = response.read()
        assert response.geturl().endswith("/" + PACKAGE), "Latest redirect mismatch"
        assert "no-store" in response.headers.get("Cache-Control", "")
    assert sha(actual) == sha(expected), "Published archive mismatch"
    with zipfile.ZipFile(io.BytesIO(actual)) as archive:
        assert archive.testzip() is None
        assert json.loads(archive.read("hoyee-assistant/manifest.json"))["version"] == "0.2.8"
    with urllib.request.urlopen("https://hoyee.org.cn/", timeout=25) as response:
        assert "v0.2.8 · 电脑端 Chrome / Edge" in response.read().decode()
    print(json.dumps({"published": PACKAGE, "sha256": sha(actual)}), flush=True)


def build():
    ROOT.mkdir(parents=True, exist_ok=True)
    snapshot = ROOT / "before.json"
    assert not snapshot.exists(), "Release already prepared; do not replace its rollback snapshot"
    before = {name: core.inspect(name) for name in (FRONT, *OTHER)}
    snapshot.write_text(json.dumps(before))
    snapshot.chmod(0o600)
    stage = ROOT / "overlay"
    stage.mkdir()
    package = (ROOT / PACKAGE).read_bytes()
    with zipfile.ZipFile(io.BytesIO(package)) as archive:
        assert archive.testzip() is None
        assert json.loads(archive.read("hoyee-assistant/manifest.json"))["version"] == "0.2.8"
    (stage / PACKAGE).write_bytes(package)
    config = core.run("docker", "exec", FRONT, "cat", CONFIG).decode()
    old_route = re.search(
        r"    location = /downloads/hoyee-assistant-0\.2\.7\.zip \{[^}]*\}", config)
    assert old_route and "hoyee-assistant-0.2.8.zip" not in config
    config = config[:old_route.end()] + "\n\n" + old_route.group().replace(
        "0.2.7", "0.2.8") + config[old_route.end():]
    old_redirect = "return 302 /downloads/hoyee-assistant-0.2.7.zip;"
    assert config.count(old_redirect) == 1
    (stage / "default.conf").write_text(config.replace(
        old_redirect, "return 302 /downloads/hoyee-assistant-0.2.8.zip;"))
    index = core.run("docker", "exec", FRONT, "cat", INDEX)
    old_label = "v0.2.7 · 电脑端 Chrome / Edge".encode()
    assert index.count(old_label) == 1
    index = index.replace(old_label, "v0.2.8 · 电脑端 Chrome / Edge".encode())
    (stage / "index.html").write_bytes(index)
    (stage / "index.html.gz").write_bytes(gzip.compress(index, mtime=0))
    (stage / "Dockerfile").write_text(
        f"FROM {PARENT}\n"
        f"COPY {PACKAGE} /usr/share/nginx/html/plugin-downloads/{PACKAGE}\n"
        "COPY default.conf /etc/nginx/conf.d/default.conf\n"
        "COPY index.html index.html.gz /usr/share/nginx/html/\n")
    core.run("docker", "image", "tag", before[FRONT]["Image"], PARENT)
    core.run("docker", "build", "--pull=false", "-t", TAG, str(stage))
    command = ["docker", "run", "--rm", "--entrypoint", "nginx",
               "--network", next(iter(before[FRONT]["NetworkSettings"]["Networks"]))]
    for mount in before[FRONT]["Mounts"]:
        command += ["-v", mount["Source"] + ":" + mount["Destination"]
                    + ("" if mount["RW"] else ":ro")]
    core.run(*command, TAG, "-t")
    print("Overlay built and nginx configuration validated.", flush=True)


def deploy():
    before = json.loads((ROOT / "before.json").read_text())
    assert core.inspect(FRONT)["Id"] == before[FRONT]["Id"], "Live frontend changed since snapshot"
    for name in OTHER:
        assert core.inspect(name)["Id"] == before[name]["Id"], f"{name} changed since snapshot"
    try:
        core.activate(before, FRONT, TAG)
        after = core.inspect(FRONT)
        assert core.configuration(after) == core.configuration(before[FRONT]), "Runtime config changed"
        assert after["State"]["Running"]
        core.run("docker", "exec", FRONT, "nginx", "-t")
        for name in OTHER:
            assert core.inspect(name)["Id"] == before[name]["Id"], f"{name} was unexpectedly replaced"
        for attempt in range(12):
            try:
                verify_public()
                break
            except Exception:
                if attempt == 11:
                    raise
                time.sleep(2)
        print("Frontend published; app, database and Redis container identities unchanged.", flush=True)
    except Exception:
        current = {FRONT: core.inspect(FRONT)}
        core.activate(current, FRONT, PARENT)
        print("Frontend rolled back to the exact pre-release image.", flush=True)
        raise


if __name__ == "__main__":
    {"build": build, "deploy": deploy, "verify": verify_public}[sys.argv[2]]()
