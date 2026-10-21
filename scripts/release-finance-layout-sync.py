"""Patch only the finance connector and layout on the exact running images."""
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(sys.argv[1]).resolve()
MODE = sys.argv[2]
spec = importlib.util.spec_from_file_location(
    "finance_release", "/opt/heyi/releases/finance-bi-20261020/release-finance-bi.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
core = release.core
APP, FRONT = core.APP, core.FRONT
TAG = "finance-recent-sync-20261021"
PREFIXES = tuple("BOOT-INF/classes/com/yingdao/aicommerce/finance/" + name
                 for name in ("FinanceMarketplace", "FinanceSync"))


def connector(name):
    return any(name == prefix + ".class" or name.startswith(prefix + "$") for prefix in PREFIXES)


def build():
    assert not (ROOT / "prepared.json").exists()
    manifest = core.read_json("manifest.json")
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    core.write_json(ROOT / "before-private.json", before)
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    (ROOT / "previous.jar").write_bytes(previous)
    (ROOT / "previous.jar").chmod(0o600)
    appdir = ROOT / "app-image"
    appdir.mkdir(exist_ok=False)
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(appdir / "app.jar", "w") as new:
        assert {n: core.sha(old.read(n)) for n in old.namelist() if connector(n)} == manifest["expectedClasses"]
        new.comment = old.comment
        for entry in old.infolist():
            if not connector(entry.filename):
                new.writestr(entry, old.read(entry.filename))
        for name, digest in manifest["classes"].items():
            assert connector(name)
            data = (ROOT / "classes" / name.removeprefix("BOOT-INF/classes/")).read_bytes()
            assert core.sha(data) == digest
            new.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(appdir / "app.jar") as new:
        assert set(old.namelist()) == set(new.namelist())
        assert all(old.read(n) == new.read(n) for n in old.namelist() if not connector(n))
    assert set(manifest["files"]) == {"index.html", "finance-workspace.css", "finance-workspace.js"}
    images = {}
    for name in (APP, FRONT):
        folder = appdir if name == APP else ROOT / "frontend-image"
        folder.mkdir(exist_ok=True)
        lines = ["FROM " + before[name]["Image"]]
        if name == APP:
            lines.append("COPY app.jar /app/app.jar")
        else:
            for file, digest in manifest["files"].items():
                live = core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)
                assert core.sha(live) == manifest["expectedFiles"][file], "Changed live file: " + file
                baseline = ROOT / "baseline-frontend" / file
                baseline.parent.mkdir(exist_ok=True)
                baseline.write_bytes(live)
                assert core.sha((ROOT / "frontend" / file).read_bytes()) == digest
                shutil.copy2(ROOT / "frontend" / file, folder / file)
                lines.append("COPY " + file + " /usr/share/nginx/html/" + file)
        (folder / "Dockerfile").write_text("\n".join(lines) + "\n")
        images[name] = ("ai-commerce-platform" if name == APP else name) + ":" + TAG
        core.run("docker", "build", "-t", images[name], str(folder))
    core.write_json(ROOT / "prepared.json", {
        "images": images, "jarHash": core.sha((appdir / "app.jar").read_bytes()),
        "unrelatedJarEntriesPreserved": True})
    print("Reviewed finance connector and layout overlays built; production unchanged.")


def verify():
    prepared, manifest = core.read_json("prepared.json"), core.read_json("manifest.json")
    core.health(APP)
    for name, image in prepared["images"].items():
        assert core.inspect(name)["Config"]["Image"] == image
    core.run("docker", "exec", FRONT, "nginx", "-t")
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["jarHash"]
    for file, digest in manifest["files"].items():
        live = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "10",
                        "--resolve", "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/" + file)
        assert core.sha(live) == digest, file


def deploy():
    assert not (ROOT / "activation.json").exists()
    before, prepared = core.read_json("before-private.json"), core.read_json("prepared.json")
    for name, info in before.items():
        assert core.inspect(name)["Id"] == info["Id"], "Live container changed during build"
    release.idle()
    count = core.run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$MYSQL_DATABASE"',
                     data=b"SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6);")
    assert count.decode().strip() == "0", "Finance request in progress; wait for its checkpoint before restart"
    preserved = {item["Name"]: item["Id"] for item in json.loads(core.run(
        "docker", "inspect", *core.run("docker", "ps", "-q").decode().split()))
        if item["Name"].lstrip("/") not in before}
    rollback = {}
    for name, info in before.items():
        rollback[name] = prepared["images"][name] + "-before"
        core.run("docker", "tag", info["Image"], rollback[name])
    try:
        core.activate(before, APP, prepared["images"][APP])
        core.health(APP)
        core.activate(before, FRONT, prepared["images"][FRONT])
        verify()
        for name in before:
            assert core.configuration(core.inspect(name)) == core.configuration(before[name])
        assert all(core.inspect(name)["Id"] == identifier for name, identifier in preserved.items())
        core.write_json(ROOT / "activation.json", {
            "images": prepared["images"], "rollbackImages": rollback,
            "configurationPreserved": True, "preservedContainers": preserved,
            "databaseMigrationChanged": False, "unrelatedJarEntriesPreserved": True})
    except BaseException:
        for name in (APP, FRONT):
            core.activate(before, name, rollback[name])
        core.health(APP)
        raise
    print("Finance connector and layout active; other services and configuration preserved.")


if __name__ == "__main__":
    try:
        {"build": build, "deploy": deploy, "verify": verify}[MODE]()
    except subprocess.CalledProcessError as error:
        (ROOT / "failure.private.log").write_bytes(error.output or b"")
        (ROOT / "failure.private.log").chmod(0o600)
        raise SystemExit("Release command failed; private diagnostics retained.")
