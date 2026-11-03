"""Check unfinished month ranges/open bills before showing shop completion."""
import gzip
import importlib.util
import json
import os
from pathlib import Path
import sys
import zipfile

os.umask(0o077)
PREVIOUS = Path("/opt/heyi/releases/finance-shop-month-20261103")
spec = importlib.util.spec_from_file_location("month", PREVIOUS / "release-finance-shop-month.py")
month = importlib.util.module_from_spec(spec)
spec.loader.exec_module(month)
core, storage = month.core, month.storage
ROOT, APP, FRONT = month.ROOT, month.APP, month.FRONT
IMAGES = {APP: "ai-commerce-platform:finance-shop-month-check-20261103",
          FRONT: "ai-commerce-frontend:finance-shop-month-check-20261103"}
FILES = month.FILES
FAMILIES = {"FinanceService", "FinanceSyncMonth"}


def replaced(name):
    return (name.startswith("BOOT-INF/classes/com/yingdao/aicommerce/finance/")
            and name.endswith(".class")
            and name.rsplit("/", 1)[-1].removesuffix(".class").split("$")[0] in FAMILIES)


def baseline():
    previous = json.loads((PREVIOUS / "prepared.json").read_text())
    month.verify(previous)
    assert core.sha((ROOT / "previous.jar").read_bytes()) == previous["jarHash"]
    return {name: core.inspect(name) for name in IMAGES}


def build():
    qa = core.read_json("qa-passed.json")
    assert qa["unitTests"] >= 8 and qa["databaseTests"] >= 2 and qa["uiTests"] >= 4
    assert qa["baselineBytecodeEqual"] and qa["indexedMonthCheck"]
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
    core.write_json(ROOT / "app-image-guard.json", guard)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGES[APP], str(folder))
    frontend = ROOT / "frontend"
    assert {p.name for p in frontend.iterdir()} == FILES
    hashes = {}
    for name in sorted(FILES):
        value = (frontend / name).read_bytes()
        hashes[name] = core.sha(value)
        (frontend / (name + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        (frontend / name).chmod(0o644)
        (frontend / (name + ".gz")).chmod(0o644)
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
        "--prepare", before[FRONT]["Image"], IMAGES[FRONT] + "-base").decode())
    core.write_json(ROOT / "frontend-image-guard.json", guard)
    files = [p for name in sorted(FILES) for p in (name, name + ".gz")]
    (frontend / "Dockerfile").write_text(
        "FROM " + guard["baseImage"] + "\nCOPY " + " ".join(files) + " /usr/share/nginx/html/\n"
        + "RUN rm -f " + " ".join("/usr/share/nginx/html/" + name + ".br" for name in sorted(FILES)) + "\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGES[FRONT], str(frontend))
    core.write_json(ROOT / "prepared.json", {"jarHash": core.sha((folder / "app.jar").read_bytes()),
        "files": hashes, "unrelatedEntriesPreserved": True})
    print("Verified month-check classes and UI prepared.", flush=True)


def verify():
    month.priority.verify(IMAGES, core.read_json("prepared.json"))


def deploy():
    assert not (ROOT / "activation.json").exists()
    before = baseline()
    rollback = {name: before[name]["Config"]["Image"] for name in IMAGES}
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
