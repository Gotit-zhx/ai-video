"""Guarded finance overlay: indexed reads and independent current-order sync."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location("finance_release", "/opt/heyi/releases/finance-bi-20261020/release-finance-bi.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
core = release.core
APP = core.APP
IMAGE = "ai-commerce-platform:finance-performance-live-20261021"
PREFIX = "BOOT-INF/classes/com/yingdao/aicommerce/finance/"
NAMES = ("FinanceService", "FinanceRepository", "FinanceSync", "FinanceMarketplace", "FinanceSnapshotCache")
MIGRATIONS = ["BOOT-INF/classes/db/migration/" + file for file in (
    "V120__finance_dashboard_covering_index.sql", "V121__finance_live_order_lane.sql")]

def relevant(name):
    return name in MIGRATIONS or any(name == PREFIX + item + ".class" or name.startswith(PREFIX + item + "$") for item in NAMES)

def sql(text):
    return core.run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -NB "$MYSQL_DATABASE"',
        data=text.encode()).decode().strip()

def build():
    assert not (ROOT / "prepared.json").exists()
    assert sql("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1") == "119"
    before = {APP: core.inspect(APP)}
    core.write_json(ROOT / "before-private.json", before)
    manifest = core.read_json("manifest.json")
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    (ROOT / "previous.jar").write_bytes(previous)
    (ROOT / "previous.jar").chmod(0o600)
    folder = ROOT / "app-image"
    folder.mkdir()
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(folder / "app.jar", "w") as new:
        assert {n: core.sha(old.read(n)) for n in old.namelist() if relevant(n)} == manifest["expectedFiles"]
        assert set(manifest["files"]) >= set(manifest["expectedFiles"])
        assert all(relevant(n) for n in manifest["files"])
        new.comment = old.comment
        for entry in old.infolist():
            value = old.read(entry.filename)
            if relevant(entry.filename):
                value = (ROOT / "files" / entry.filename).read_bytes()
                assert core.sha(value) == manifest["files"][entry.filename]
            new.writestr(entry, value)
        for name in set(manifest["files"]) - set(old.namelist()):
            value = (ROOT / "files" / name).read_bytes()
            assert core.sha(value) == manifest["files"][name]
            new.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(folder / "app.jar") as new:
        assert all(old.read(n) == new.read(n) for n in old.namelist() if not relevant(n))
        assert set(new.namelist()) - set(old.namelist()) == set(manifest["files"]) - set(manifest["expectedFiles"])
    (folder / "Dockerfile").write_text("FROM " + before[APP]["Image"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "-t", IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {"image": IMAGE, "jarHash": core.sha((folder / "app.jar").read_bytes())})
    print("Finance overlay built; unrelated classes and configuration preserved.")

def verify():
    core.health(APP)
    assert core.inspect(APP)["Config"]["Image"] == IMAGE
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.read_json("prepared.json")["jarHash"]
    assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('120','121') AND success=1") == "2"
    assert sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='finance_records' AND index_name='idx_finance_dashboard'") == "15"

def deploy():
    assert not (ROOT / "activation.json").exists()
    before = core.read_json("before-private.json")
    assert core.inspect(APP)["Id"] == before[APP]["Id"]
    release.idle()
    assert sql("SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)") == "0", "Wait for finance checkpoint"
    assert sql("SELECT COUNT(*) FROM finance_ad_accounts WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)") == "0", "Wait for advertising checkpoint"
    assert sql("SELECT COUNT(*) FROM finance_authorization_sessions WHERE status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)") == "0", "Wait for authorization callback"
    preserved = {x["Name"]: x["Id"] for x in json.loads(core.run("docker", "inspect", *core.run("docker", "ps", "-q").decode().split())) if x["Name"].lstrip("/") != APP}
    core.run("docker", "tag", before[APP]["Image"], IMAGE + "-before")
    try:
        core.activate(before, APP, IMAGE)
        verify()
        assert core.configuration(core.inspect(APP)) == core.configuration(before[APP])
        assert all(core.inspect(name)["Id"] == value for name, value in preserved.items())
    except BaseException:
        # Older code cannot dispatch ORDER_LIVE; pause only that newly added lane
        # before rollback, leaving all original checkpoints untouched.
        sql("UPDATE finance_sync_states SET next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 YEAR) WHERE channel='ORDER_LIVE'")
        core.activate(before, APP, IMAGE + "-before")
        core.health(APP)
        raise
    core.write_json(ROOT / "activation.json", {"image": IMAGE, "rollbackImage": IMAGE + "-before",
                    "preservedContainers": preserved, "historicalCheckpointsReset": False, "migrations": [120,121]})
    print("Finance reads and independent current-order sync are active.")

if __name__ == "__main__":
    try:
        {"build": build, "deploy": deploy, "verify": verify}[sys.argv[2]]()
    except subprocess.CalledProcessError as error:
        (ROOT / "failure.private.log").write_bytes(error.output or b"")
        (ROOT / "failure.private.log").chmod(0o600)
        raise SystemExit("Release failed; private diagnostics retained.")
