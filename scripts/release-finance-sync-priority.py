"""Release shop priority, observable queues, and bounded dispatcher throughput."""
import gzip
import importlib.util
import json
import os
from pathlib import Path
import sys
import zipfile

os.umask(0o077)
spec = importlib.util.spec_from_file_location(
    "storage", "/opt/heyi/releases/finance-storage-recovery-20261102/release-finance-storage-recovery.py")
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)
core, ROOT, APP, FRONT = storage.core, storage.ROOT, storage.APP, storage.FRONT
IMAGES = {APP: "ai-commerce-platform:finance-sync-priority-20261103",
          FRONT: "ai-commerce-frontend:finance-sync-priority-ui-20261103"}
FILES = {"index.html", "finance-workspace.js", "finance-workspace.css"}
FAMILIES = {"FinanceService", "FinanceSync", "FinanceSyncClaims", "FinanceSyncProgress", "FinanceController"}
MIGRATION = "BOOT-INF/classes/db/migration/V142__finance_sync_priority.sql"


def replaced(name):
    return (name.startswith("BOOT-INF/classes/com/yingdao/aicommerce/finance/")
            and name.endswith(".class")
            and name.rsplit("/", 1)[-1].removesuffix(".class").split("$")[0] in FAMILIES)


def baseline():
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.sha((ROOT / "previous.jar").read_bytes())
    for name in FILES:
        live = core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + name)
        assert live == (ROOT / "baseline-frontend" / name).read_bytes()
        assert live == (ROOT / "original-frontend" / name).read_bytes(), "Frontend source baseline differs"
    return {name: core.inspect(name) for name in IMAGES}


def build():
    qa = core.read_json("qa-passed.json")
    assert qa["unitTests"] >= 6 and qa["databaseTests"] >= 13
    assert qa["baselineBytecodeEqual"] and qa["uiTests"] >= 4 and qa["browserLayout"]
    before = baseline()
    folder = ROOT / "app-image"
    folder.mkdir(exist_ok=True)
    entries = {"BOOT-INF/classes/" + str(p.relative_to(ROOT / "classes")): p.read_bytes()
               for p in (ROOT / "classes").rglob("*.class")}
    assert entries and all(replaced(n) for n in entries)
    assert {n.rsplit("/", 1)[-1].removesuffix(".class").split("$")[0] for n in entries} == FAMILIES
    entries[MIGRATION] = (ROOT / "resources/db/migration/V142__finance_sync_priority.sql").read_bytes()
    with zipfile.ZipFile(ROOT / "previous.jar") as old, zipfile.ZipFile(folder / "app.jar", "w") as new:
        new.comment = old.comment
        assert MIGRATION not in old.namelist()
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
    frontend_base = json.loads(core.run(
        "python3", "/usr/local/lib/heyi/compact-release-image.py", "--prepare",
        before[FRONT]["Image"], IMAGES[FRONT] + "-base").decode())["baseImage"]
    (frontend / "Dockerfile").write_text("FROM " + frontend_base + "\n" +
        "\n".join("COPY " + name + " " + name + ".gz /usr/share/nginx/html/" for name in sorted(FILES)) +
        "\nRUN rm -f " + " ".join("/usr/share/nginx/html/" + name + ".br" for name in sorted(FILES)) + "\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGES[FRONT], str(frontend))
    core.write_json(ROOT / "prepared.json", {"jarHash": core.sha((folder / "app.jar").read_bytes()),
        "files": hashes, "unrelatedEntriesPreserved": True})
    print("Exact-live sync classes, small-state migration, and frontend assets prepared.", flush=True)


def sql(text):
    return core.run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -NB "$MYSQL_DATABASE"',
        data=text.encode()).decode()


def verify(images=None, prepared=None):
    images = images or IMAGES
    prepared = prepared or core.read_json("prepared.json")
    core.health(APP)
    for name, image in images.items():
        assert core.inspect(name)["Config"]["Image"] == image
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["jarHash"]
    for name, digest in prepared["files"].items():
        for encoding in ("identity", "gzip"):
            value = core.run("curl", "--noproxy", "*", "-fsS", "--retry", "2", "--retry-all-errors", "--max-time", "10",
                "--resolve", "hoyee.org.cn:443:127.0.0.1", "-H", "Accept-Encoding: " + encoding,
                "https://hoyee.org.cn/" + name)
            if encoding == "gzip" and value.startswith(b"\x1f\x8b"):
                value = gzip.decompress(value)
            assert core.sha(value) == digest
    for name in (APP, FRONT, "ai-commerce-mysql", "ai-commerce-redis"):
        assert core.inspect(name)["HostConfig"]["LogConfig"]["Type"] == "none"
    assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='142' AND success=1").strip() == "1"
    for endpoint, expected in (("/", "200"), ("/api/v1/finance/shops", "401")):
        assert core.run("curl", "--noproxy", "*", "-sS", "--max-time", "10", "--resolve",
            "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null", "-w", "%{http_code}",
            "https://hoyee.org.cn" + endpoint).decode() == expected
    print("Live release, schema, assets, health, access guards and no-log settings verified.", flush=True)


def deploy_frontend():
    """Apply only the compact shop table to the verified priority release."""
    previous = core.read_json("prepared.json")
    activation = core.read_json("activation.json")
    assert activation["images"][APP] == IMAGES[APP]
    assert activation["images"][FRONT] == "ai-commerce-frontend:finance-sync-priority-20261103-compact"
    qa = core.read_json("ui-qa-passed.json")
    assert qa["uiTests"] >= 4 and qa["browserLayout"] and qa["shopCountsPreserved"]
    verify(activation["images"], previous)
    before = {name: core.inspect(name) for name in IMAGES}
    assert not core.run("docker", "image", "ls", "--quiet", IMAGES[FRONT]).strip(), "Release tag already exists"
    frontend = ROOT / "ui-frontend"
    assert {p.name for p in frontend.iterdir()} == FILES
    hashes = {}
    for name in sorted(FILES):
        value = (frontend / name).read_bytes()
        hashes[name] = core.sha(value)
        (frontend / (name + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        (frontend / name).chmod(0o644)
        (frontend / (name + ".gz")).chmod(0o644)
    prepared = {**previous, "files": hashes}
    guard = json.loads(core.run(
        "python3", "/usr/local/lib/heyi/compact-release-image.py", "--prepare",
        before[FRONT]["Image"], IMAGES[FRONT] + "-base").decode())
    core.write_json(ROOT / "ui-image-guard.json", guard)
    files = [p for name in sorted(FILES) for p in (name, name + ".gz")]
    (frontend / "Dockerfile").write_text(
        "FROM " + guard["baseImage"] + "\nCOPY " + " ".join(files) + " /usr/share/nginx/html/\n"
        + "RUN rm -f " + " ".join("/usr/share/nginx/html/" + name + ".br" for name in sorted(FILES)) + "\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGES[FRONT], str(frontend))
    try:
        storage.activate(before, FRONT, IMAGES[FRONT])
        verify(IMAGES, prepared)
        assert core.inspect(APP)["Id"] == before[APP]["Id"], "Backend must remain running"
        for name in IMAGES:
            assert core.configuration(core.inspect(name)) == core.configuration(before[name])
        core.write_json(ROOT / "prepared.json", prepared)
        core.write_json(ROOT / "activation.json", {
            **activation, "images": IMAGES,
            "compactLayoutRollback": before[FRONT]["Config"]["Image"],
            "compactShopLayout": True, "shopCountsPreserved": True, "backendRestartedForLayout": False})
        core.write_json(ROOT / "ui-activation.json", {
            "image": IMAGES[FRONT], "rollbackImage": before[FRONT]["Config"]["Image"],
            "files": hashes, "configurationPreserved": True, "backendContainerUnchanged": True,
            "persistentLogs": False})
    except BaseException:
        storage.activate(before, FRONT, before[FRONT]["Config"]["Image"])
        core.write_json(ROOT / "prepared.json", previous)
        core.write_json(ROOT / "activation.json", activation)
        raise
    print("Compact shop rows and preserved shop counts activated without a backend restart.", flush=True)


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
    {"build": build, "drain-deploy": drain_deploy, "deploy-frontend": deploy_frontend, "verify": verify}[sys.argv[2]]()
