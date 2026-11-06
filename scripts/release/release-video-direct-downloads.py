"""Selectively release direct MP4 downloads over the exact running images."""
import gzip
import importlib.util
import json
import os
from pathlib import Path
import sys
import zipfile

os.umask(0o077)
spec = importlib.util.spec_from_file_location("storage", "/opt/heyi/releases/finance-storage-recovery-20261102/release-finance-storage-recovery.py")
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)
core, ROOT, APP, FRONT = storage.core, storage.ROOT, storage.APP, storage.FRONT
IMAGES = {
    APP: "ai-commerce-platform:video-direct-downloads-20261102",
    FRONT: "ai-commerce-frontend:video-direct-downloads-20261102",
}
FILES = {"app.js", "index.html", "video-direct-downloads.js"}
EXISTING = FILES - {"video-direct-downloads.js"}
FAMILIES = {
    "BOOT-INF/classes/com/yingdao/aicommerce/video/application/AiVideoTaskService",
    "BOOT-INF/classes/com/yingdao/aicommerce/video/web/AiVideoTaskController",
}


def replaced(name):
    return name.endswith(".class") and name.removesuffix(".class").split("$")[0] in FAMILIES


def baseline():
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.sha((ROOT / "previous.jar").read_bytes())
    for name in EXISTING:
        assert core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + name) == (ROOT / "baseline-frontend" / name).read_bytes()
    return {name: core.inspect(name) for name in IMAGES}


def build():
    before = baseline()
    folder = ROOT / "app-image"
    folder.mkdir(exist_ok=True)
    entries = {"BOOT-INF/classes/" + str(p.relative_to(ROOT / "classes")): p.read_bytes()
               for p in (ROOT / "classes").rglob("*.class")}
    assert entries and all(replaced(n) for n in entries)
    assert {n.removesuffix(".class").split("$")[0] for n in entries} == FAMILIES
    with zipfile.ZipFile(ROOT / "previous.jar") as old, zipfile.ZipFile(folder / "app.jar", "w") as new:
        new.comment = old.comment
        for item in old.infolist():
            if not replaced(item.filename):
                new.writestr(item, old.read(item.filename))
        for name, value in entries.items():
            new.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(ROOT / "previous.jar") as old, zipfile.ZipFile(folder / "app.jar") as new:
        assert set(new.namelist()) == {n for n in old.namelist() if not replaced(n)} | set(entries)
        assert all(new.read(n) == old.read(n) for n in old.namelist() if not replaced(n))
    (folder / "app.jar").chmod(0o644)
    image_base = json.loads(core.run(
        "python3", "/usr/local/lib/heyi/compact-release-image.py", "--prepare",
        before[APP]["Image"], IMAGES[APP] + "-base").decode())["baseImage"]
    (folder / "Dockerfile").write_text("FROM " + image_base + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGES[APP], str(folder))
    frontend = ROOT / "frontend"
    assert {p.name for p in frontend.iterdir()} == FILES
    hashes = {}
    for name in FILES:
        value = (frontend / name).read_bytes()
        hashes[name] = core.sha(value)
        (frontend / (name + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        (frontend / name).chmod(0o644)
        (frontend / (name + ".gz")).chmod(0o644)
    (frontend / "Dockerfile").write_text("FROM " + before[FRONT]["Config"]["Image"] + "\n" +
        "\n".join("COPY " + name + " " + name + ".gz /usr/share/nginx/html/" for name in sorted(FILES)) +
        "\nRUN rm -f " + " ".join("/usr/share/nginx/html/" + name + ".br" for name in sorted(FILES)) + "\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGES[FRONT], str(frontend))
    core.write_json(ROOT / "prepared.json", {"jarHash": core.sha((folder / "app.jar").read_bytes()), "files": hashes})
    print("Two video class families and three frontend assets built over exact live images.")


def verify():
    core.health(APP)
    for name, image in IMAGES.items():
        assert core.inspect(name)["Config"]["Image"] == image
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.read_json("prepared.json")["jarHash"]
    for name, digest in core.read_json("prepared.json")["files"].items():
        for encoding in ("identity", "gzip"):
            value = core.run("curl", "--noproxy", "*", "-fsS", "--retry", "3", "--retry-all-errors", "--max-time", "10",
                "--resolve", "hoyee.org.cn:443:127.0.0.1", "-H", "Accept-Encoding: " + encoding, "https://hoyee.org.cn/" + name)
            if encoding == "gzip" and value.startswith(b"\x1f\x8b"):
                value = gzip.decompress(value)
            assert core.sha(value) == digest
    status = core.run("curl", "--noproxy", "*", "-sS", "--max-time", "10", "-o", "/dev/null", "-w", "%{http_code}",
        "--resolve", "hoyee.org.cn:443:127.0.0.1",
        "https://hoyee.org.cn/api/v1/ai-video-tasks/test/assets/test?download=true").decode()
    assert status == "401"
    for name in (APP, FRONT, "ai-commerce-mysql", "ai-commerce-redis"):
        assert core.inspect(name)["HostConfig"]["LogConfig"]["Type"] == "none"
    print("Live asset hashes, download access guard, application health and no-log configuration verified.")


def deploy():
    qa = core.read_json("qa-passed.json")
    assert qa["javaTests"] == 9 and qa["directDownloadUI"] and qa["baselineBytecodeEqual"]
    assert not (ROOT / "activation.json").exists()
    before = baseline()
    rollback = {name: image + "-before" for name, image in IMAGES.items()}
    for name in IMAGES:
        core.run("docker", "tag", before[name]["Image"], rollback[name])
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        storage.activate(before, APP, IMAGES[APP])
        core.health(APP)
        storage.activate(before, FRONT, IMAGES[FRONT])
        verify()
        for name in IMAGES:
            assert core.configuration(core.inspect(name)) == core.configuration(before[name])
    except BaseException:
        for name in IMAGES:
            storage.activate(before, name, rollback[name])
        core.health(APP)
        raise
    core.write_json(ROOT / "activation.json", {"images": IMAGES, "rollbackImages": rollback,
        "configurationPreserved": True, "unrelatedJarEntriesPreserved": True, "persistentLogs": False})


def drain_deploy():
    storage.queue.deploy = deploy
    storage.queue.drain_deploy()


if __name__ == "__main__":
    {"build": build, "drain-deploy": drain_deploy, "verify": verify}[sys.argv[2]]()
