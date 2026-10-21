"""Guarded finance-only class overlay; preserve every other JAR entry and service."""
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
PREFIX = "BOOT-INF/classes/com/yingdao/aicommerce/finance/FinanceMarketplace"
IMAGE = "ai-commerce-platform:finance-order-terminal-20261021"


def relevant(name):
    return name == PREFIX + ".class" or name.startswith(PREFIX + "$")


def sql(text):
    return core.run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -NB "$MYSQL_DATABASE"',
                    data=text.encode()).decode().strip()


def build():
    assert not (ROOT / "prepared.json").exists()
    before = {APP: core.inspect(APP)}
    core.write_json(ROOT / "before-private.json", before)
    manifest = core.read_json("manifest.json")
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    (ROOT / "previous.jar").write_bytes(previous)
    (ROOT / "previous.jar").chmod(0o600)
    folder = ROOT / "app-image"
    folder.mkdir()
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(folder / "app.jar", "w") as new:
        assert {n: core.sha(old.read(n)) for n in old.namelist() if relevant(n)} == manifest["expectedClasses"]
        assert set(manifest["classes"]) == {n for n in old.namelist() if relevant(n)}
        new.comment = old.comment
        for entry in old.infolist():
            value = old.read(entry.filename)
            if relevant(entry.filename):
                value = (ROOT / "classes" / entry.filename.removeprefix("BOOT-INF/classes/")).read_bytes()
                assert core.sha(value) == manifest["classes"][entry.filename]
            new.writestr(entry, value)
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(folder / "app.jar") as new:
        assert set(old.namelist()) == set(new.namelist())
        assert all(old.read(n) == new.read(n) for n in old.namelist() if not relevant(n))
    (folder / "Dockerfile").write_text("FROM " + before[APP]["Image"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "-t", IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {"image": IMAGE, "jarHash": core.sha((folder / "app.jar").read_bytes())})
    print("Finance connector overlay built; unrelated classes preserved.")


def verify():
    core.health(APP)
    assert core.inspect(APP)["Config"]["Image"] == IMAGE
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.read_json("prepared.json")["jarHash"]


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
        core.activate(before, APP, IMAGE + "-before")
        core.health(APP)
        raise
    # Resume only the confirmed schema error. Keep all dates, cursors and records.
    resumed = sql("UPDATE finance_sync_states s JOIN finance_connections c ON c.id=s.shop_id AND c.organization_id=s.organization_id AND c.user_id=s.user_id "
                  "SET s.next_at=UTC_TIMESTAMP(6) WHERE c.platform='TIKTOK_SHOP' AND c.enabled=TRUE AND c.status='ACTIVE' "
                  "AND s.channel='ORDER' AND s.status='ERROR' AND s.error_message='平台财务响应结构异常：orders' "
                  "AND (s.lease_until IS NULL OR s.lease_until<UTC_TIMESTAMP(6)); SELECT ROW_COUNT();")
    core.write_json(ROOT / "activation.json", {"image": IMAGE, "rollbackImage": IMAGE + "-before",
                    "preservedContainers": preserved, "resumedOrderSyncs": int(resumed), "databaseMigrationChanged": False})
    print("Finance connector active; failed order sync resumed from original checkpoint.")


if __name__ == "__main__":
    try:
        {"build": build, "deploy": deploy, "verify": verify}[sys.argv[2]]()
    except subprocess.CalledProcessError as error:
        (ROOT / "failure.private.log").write_bytes(error.output or b"")
        (ROOT / "failure.private.log").chmod(0o600)
        raise SystemExit("Release failed; private diagnostics retained.")
