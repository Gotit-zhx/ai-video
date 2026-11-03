"""Release bounded raw-snapshot reuse and covered detail pagination."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import zipfile

os.umask(0o077)
PREVIOUS = Path("/opt/heyi/releases/finance-shop-month-check-20261103")
spec = importlib.util.spec_from_file_location("previous", PREVIOUS / "release-finance-shop-month-check.py")
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
core, storage = previous.core, previous.storage
ROOT, APP, FRONT = previous.ROOT, previous.APP, previous.FRONT
IMAGES = {APP: "ai-commerce-platform:finance-query-reuse-20261103",
          FRONT: previous.IMAGES[FRONT]}
FAMILIES = {"FinanceReadCache", "FinanceService", "FinanceRepository"}


def replaced(name):
    return (name.startswith("BOOT-INF/classes/com/yingdao/aicommerce/finance/")
            and name.endswith(".class")
            and name.rsplit("/", 1)[-1].removesuffix(".class").split("$")[0] in FAMILIES)


def baseline():
    prepared = json.loads((PREVIOUS / "prepared.json").read_text())
    previous.month.priority.verify(previous.IMAGES, prepared)
    assert core.sha((ROOT / "previous.jar").read_bytes()) == prepared["jarHash"]
    return {name: core.inspect(name) for name in IMAGES}


def build():
    qa = core.read_json("qa-passed.json")
    assert qa["unitTests"] >= 25 and qa["databaseTests"] >= 5
    assert qa["baselineBytecodeEqual"] and qa["livePageEquality"] and qa["liveProjectionEquality"]
    before = baseline()
    folder = ROOT / "app-image"
    folder.mkdir()
    entries = {"BOOT-INF/classes/" + str(p.relative_to(ROOT / "classes")): p.read_bytes()
               for p in (ROOT / "classes").rglob("*.class")}
    assert entries and all(replaced(n) for n in entries)
    assert {n.rsplit("/", 1)[-1].removesuffix(".class").split("$")[0] for n in entries} == FAMILIES
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
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
        "--prepare", before[APP]["Image"], IMAGES[APP] + "-base").decode())
    core.write_json(ROOT / "image-guard.json", guard)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGES[APP], str(folder))
    old = json.loads((PREVIOUS / "prepared.json").read_text())
    core.write_json(ROOT / "prepared.json", {**old, "jarHash": core.sha((folder / "app.jar").read_bytes()),
        "unrelatedEntriesPreserved": True})
    print("Verified cache/page classes prepared; no frontend or schema change.", flush=True)


def verify():
    previous.month.priority.verify(IMAGES, core.read_json("prepared.json"))


def deploy():
    assert not (ROOT / "activation.json").exists()
    before = baseline()
    rollback = before[APP]["Config"]["Image"]
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        storage.activate(before, APP, IMAGES[APP])
        core.health(APP)
        verify()
        assert core.inspect(FRONT)["Id"] == before[FRONT]["Id"]
        for name in IMAGES:
            assert core.configuration(core.inspect(name)) == core.configuration(before[name])
    except BaseException:
        storage.activate(before, APP, rollback)
        core.health(APP)
        raise
    core.write_json(ROOT / "activation.json", {"images": IMAGES, "rollbackImage": rollback,
        "configurationPreserved": True, "unrelatedJarEntriesPreserved": True,
        "frontendUnchanged": True, "persistentLogs": False})


def drain_deploy():
    storage.queue.deploy = deploy
    storage.queue.drain_deploy()


if __name__ == "__main__":
    {"build": build, "drain-deploy": drain_deploy, "verify": verify}[sys.argv[2]]()
