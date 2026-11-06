"""Deploy the verified compact runtime and stop automatic cost audit snapshots."""
import importlib.util
import io
import sys
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "storage", "/opt/heyi/releases/finance-storage-recovery-20261102/release-finance-storage-recovery.py")
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)
core, ROOT, APP, FRONT = storage.core, storage.ROOT, storage.APP, storage.FRONT
IMAGE = "ai-commerce-platform:storage-bounded-20261102"
BASE = "ai-commerce-platform:storage-compact-baseline-20261102"
PREVIOUS_CLASS = "acfeb82f48979ea03a1f05590e0874c268479a404a8720659ca76979bc76e490"
CLASS = "BOOT-INF/classes/com/yingdao/aicommerce/finance/FinanceOrderCosts.class"


def replaced(name):
    return name.startswith(CLASS.removesuffix(".class")) and name.endswith(".class") and (
        name == CLASS or name.startswith(CLASS.removesuffix(".class") + "$"))


def build():
    assert core.read_json("qa-passed.json") == {"costUnitTests": 11, "databaseStorageTests": 1}
    before = core.inspect(APP)
    assert before["Config"]["Image"] == "ai-commerce-platform:finance-cache-reuse-20261102"
    # The separate compaction tool verifies every path and all image metadata.
    receipt = core.read_json("compaction.json")
    assert receipt["configurationEqual"] and receipt["filesystemEqual"]
    assert receipt["sourceId"] == before["Image"]
    assert core.inspect(BASE)["Id"] == receipt["targetId"]
    old = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    with zipfile.ZipFile(io.BytesIO(old)) as archive:
        assert core.sha(archive.read(CLASS)) == PREVIOUS_CLASS
    folder = ROOT / "app-image"
    folder.mkdir(exist_ok=True)
    entries = {"BOOT-INF/classes/" + str(p.relative_to(ROOT / "classes")): p.read_bytes()
               for p in (ROOT / "classes").rglob("*.class")}
    assert entries and all(replaced(name) for name in entries)
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar", "w") as target:
        target.comment = source.comment
        for item in source.infolist():
            if not replaced(item.filename):
                target.writestr(item, source.read(item.filename))
        for name, value in entries.items():
            target.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar") as target:
        assert set(target.namelist()) == {n for n in source.namelist() if not replaced(n)} | set(entries)
        assert all(source.read(n) == target.read(n) for n in source.namelist() if not replaced(n))
    (folder / "app.jar").chmod(0o644)
    (folder / "Dockerfile").write_text("FROM " + BASE + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {
        "sourceImageId": before["Image"], "sourceJarHash": core.sha(old),
        "jarHash": core.sha((folder / "app.jar").read_bytes()),
        "classes": {n: core.sha(v) for n, v in entries.items()},
        "unrelatedEntriesPreserved": True})
    print("Compact runtime and one reviewed class family prepared.")


def verify():
    core.health(APP)
    info = core.inspect(APP)
    assert info["Config"]["Image"] == IMAGE
    assert info["HostConfig"]["LogConfig"]["Type"] == "none"
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.read_json("prepared.json")["jarHash"]
    for endpoint, expected in (("/", "200"), ("/api/v1/finance/overview", "401")):
        value = core.run("curl", "--noproxy", "*", "-sS", "--max-time", "10",
                         "--resolve", "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null",
                         "-w", "%{http_code}", "https://hoyee.org.cn" + endpoint).decode()
        assert value == expected
    print("Live compact image, application bytes, health and access guards verified.")


def deploy():
    assert not (ROOT / "activation.json").exists()
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    assert before[APP]["Image"] == core.read_json("prepared.json")["sourceImageId"]
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.read_json("prepared.json")["sourceJarHash"]
    # BASE is the byte-identical previous application in a compact image.
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        storage.activate(before, APP, IMAGE)
        verify()
        assert core.configuration(core.inspect(APP)) == core.configuration(before[APP])
        assert core.inspect(FRONT)["Id"] == before[FRONT]["Id"]
    except BaseException:
        storage.activate(before, APP, BASE)
        core.health(APP)
        raise
    core.write_json(ROOT / "activation.json", {
        "image": IMAGE, "rollbackImage": BASE,
        "previousRollbackImage": "ai-commerce-platform:storage-compact-previous-20261102",
        "configurationPreserved": True, "frontendUnchanged": True,
        "unrelatedJarEntriesPreserved": True, "persistentLogs": False})


def drain_deploy():
    storage.queue.deploy = deploy
    storage.queue.drain_deploy()


if __name__ == "__main__":
    {"build": build, "drain-deploy": drain_deploy, "verify": verify}[sys.argv[2]]()
