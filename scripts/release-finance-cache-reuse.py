"""Release bounded BI caches and long-running query UI over exact live images."""
import gzip
import importlib.util
import os
from pathlib import Path
import sys
import zipfile

os.umask(0o077)
spec = importlib.util.spec_from_file_location("storage", "/opt/heyi/releases/finance-storage-recovery-20261102/release-finance-storage-recovery.py")
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)
core, ROOT, APP, FRONT = storage.core, storage.ROOT, storage.APP, storage.FRONT
IMAGES = {APP: "ai-commerce-platform:finance-cache-reuse-20261102",
          FRONT: "ai-commerce-frontend:finance-cache-reuse-20261102"}
FILES = {"index.html", "finance-workspace.js"}
FAMILIES = {"FinanceService", "FinanceRepository", "FinanceReadCache", "FinanceSnapshotCache"}
MIGRATION = "BOOT-INF/classes/db/migration/V141__finance_cost_date_covering.sql"


def replaced(name):
    return name.startswith("BOOT-INF/classes/com/yingdao/aicommerce/finance/") and name.endswith(".class") and name.rsplit("/", 1)[-1].removesuffix(".class").split("$")[0] in FAMILIES


def baseline():
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.sha((ROOT / "previous.jar").read_bytes())
    for name in FILES:
        assert core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + name) == (ROOT / "baseline-frontend" / name).read_bytes()
    return {name: core.inspect(name) for name in IMAGES}


def build():
    before = baseline()
    folder = ROOT / "app-image"
    folder.mkdir(exist_ok=True)
    entries = {"BOOT-INF/classes/" + str(p.relative_to(ROOT / "classes")): p.read_bytes() for p in (ROOT / "classes").rglob("*.class")}
    assert entries and all(replaced(n) for n in entries)
    assert {n.rsplit("/", 1)[-1].removesuffix(".class").split("$")[0] for n in entries} == FAMILIES
    entries[MIGRATION] = (ROOT / "V141__finance_cost_date_covering.sql").read_bytes()
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
    (folder / "Dockerfile").write_text("FROM " + before[APP]["Config"]["Image"] + "\nCOPY app.jar /app/app.jar\n")
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
    print("Reviewed finance classes, idempotent migration and two frontend assets prepared.")


def sql(text):
    return core.run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -NB "$MYSQL_DATABASE"', data=text.encode()).decode()


def promote_index():
    assert sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='finance_records' AND index_name='idx_finance_company_kind_cover'").strip() == "16"
    # The app is drained. Enable the candidate, then atomically replace the old
    # name; MySQL resolves visibility against renamed keys within a single DDL.
    sql("SET SESSION lock_wait_timeout=30; ALTER TABLE finance_records"
        " ALTER INDEX idx_finance_company_kind_cover VISIBLE, ALGORITHM=INPLACE, LOCK=NONE;")
    sql("SET SESSION lock_wait_timeout=30; ALTER TABLE finance_records"
        " DROP INDEX idx_finance_company_kind_date,"
        " RENAME INDEX idx_finance_company_kind_cover TO idx_finance_company_kind_date,"
        " ALGORITHM=INPLACE, LOCK=NONE;")
    assert sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='finance_records' AND index_name='idx_finance_company_kind_date' AND is_visible='YES'").strip() == "16"


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
    for name in (APP, FRONT, "ai-commerce-mysql", "ai-commerce-redis"):
        assert core.inspect(name)["HostConfig"]["LogConfig"]["Type"] == "none"
    assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='141' AND success=1").strip() == "1"
    print("Live finance release, assets, migration and no-log settings verified.")


def deploy():
    qa = core.read_json("qa-passed.json")
    assert qa["unitTests"] >= 43 and qa["databaseTests"] == 4 and qa["scopeCurrencyUI"] and qa["longWaitUI"]
    assert qa["baselineBytecodeEqual"] and qa["liveCostEquality"] and qa["redisReuseEquality"]
    assert not (ROOT / "activation.json").exists()
    before = baseline()
    rollback = {name: image + "-before" for name, image in IMAGES.items()}
    for name in IMAGES:
        core.run("docker", "tag", before[name]["Image"], rollback[name])
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        promote_index()
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
        "configurationPreserved": True, "unrelatedJarEntriesPreserved": True, "persistentLogs": False,
        "oldCostIndexReplaced": True})


def drain_deploy():
    storage.queue.deploy = deploy
    storage.queue.drain_deploy()


if __name__ == "__main__":
    {"build": build, "drain-deploy": drain_deploy, "verify": verify}[sys.argv[2]]()
