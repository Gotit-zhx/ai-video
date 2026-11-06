"""Warm live screen and finance-owned advertising OAuth; guarded exact-image overlay."""
import importlib.util
import io
import gzip
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
TAG = "finance-live-ads-20261021"
FINANCE = "BOOT-INF/classes/com/yingdao/aicommerce/finance/"
CALLBACK = "BOOT-INF/classes/com/yingdao/aicommerce/shop/web/ShopAuthorizationController"
MIGRATION = "BOOT-INF/classes/db/migration/V119__finance_ad_authorization.sql"
FILES = {"index.html", "finance-workspace.css", "finance-workspace.js"}


def connector(name):
    return name == MIGRATION or any(name == FINANCE + family + ".class" or name.startswith(FINANCE + family + "$") for family in ("FinanceAds", "FinanceController", "FinanceAdAuthorization", "FinanceAdAuthorizationCallback"))


def sql(text):
    return core.run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$MYSQL_DATABASE"', data=text.encode()).decode().strip()


def dump(tables):
    assert all(t.replace("_", "").isalnum() for t in tables)
    return core.run("docker", "exec", "ai-commerce-mysql", "sh", "-c",
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --skip-lock-tables --no-tablespaces --skip-comments --compact --order-by-primary "$MYSQL_DATABASE" ' + " ".join(tables))


def snapshot():
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    assert not (ROOT / "snapshot.json").exists()
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    core.write_json(ROOT / "before-private.json", before)
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    (ROOT / "previous.jar").write_bytes(previous)
    (ROOT / "previous.jar").chmod(0o600)
    with zipfile.ZipFile(io.BytesIO(previous)) as jar:
        assert MIGRATION not in jar.namelist(), "V119 already exists; review migration numbering"
        expected = {"jar": core.sha(previous), "expectedClasses": {n: core.sha(jar.read(n)) for n in jar.namelist() if connector(n)}, "expectedFiles": {}}
    for file in FILES:
        value = core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)
        expected["expectedFiles"][file] = core.sha(value)
        target = ROOT / "baseline-frontend" / file; target.parent.mkdir(exist_ok=True); target.write_bytes(value)
    assert sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == "118"
    core.write_json(ROOT / "snapshot.json", expected)
    print("Captured finance and authorization callback baseline; production unchanged.")


def build():
    assert not (ROOT / "prepared.json").exists()
    manifest = core.read_json("manifest.json")
    before = core.read_json("before-private.json")
    for name, info in before.items():
        assert core.inspect(name)["Id"] == info["Id"], "Container changed during preparation"
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    assert core.sha(previous) == core.read_json("snapshot.json")["jar"]
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
        assert set(new.namelist()) == {n for n in old.namelist() if not connector(n)} | set(manifest["classes"])
        assert all(old.read(n) == new.read(n) for n in old.namelist() if not connector(n))
    assert set(manifest["files"]) == FILES
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
    print("Reviewed finance overlays built; production unchanged.")


def verify():
    prepared, manifest = core.read_json("prepared.json"), core.read_json("manifest.json")
    core.health(APP)
    for name, image in prepared["images"].items():
        assert core.inspect(name)["Config"]["Image"] == image
    core.run("docker", "exec", FRONT, "nginx", "-t")
    assert sql("SELECT success FROM flyway_schema_history WHERE version='119'") == "1"
    assert sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='finance_ad_applications'") == "1"
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
    assert sql("SELECT COUNT(*) FROM finance_ad_accounts WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)") == "0", "Advertising request in progress"
    assert sql("SELECT COUNT(*) FROM finance_authorization_sessions WHERE status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)") == "0", "Authorization callback in progress; wait before restart"
    finance_backup = dump(["finance_shops", "finance_sync_states", "finance_records", "finance_ad_accounts", "finance_imports", "finance_fx_snapshots", "finance_connections", "finance_authorization_sessions", "finance_view_preferences"])
    backup = ROOT / "finance-before.sql.gz"; backup.write_bytes(gzip.compress(finance_backup)); backup.chmod(0o600)
    commerce_before = core.sha(dump(["shops", "shop_credentials"]))
    core.write_json(ROOT / "data-before.json", {"commerceHash": commerce_before, "financeBackupBytes": backup.stat().st_size})
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
        assert core.sha(dump(["shops", "shop_credentials"])) == commerce_before, "Commerce records changed during activation; inspect before proceeding"
        for name in before:
            assert core.configuration(core.inspect(name)) == core.configuration(before[name])
        assert all(core.inspect(name)["Id"] == identifier for name, identifier in preserved.items())
        core.write_json(ROOT / "activation.json", {
            "images": prepared["images"], "rollbackImages": rollback,
            "configurationPreserved": True, "preservedContainers": preserved,
            "databaseMigrationAdded": "V119", "commerceCredentialsPreserved": True, "unrelatedJarEntriesPreserved": True})
    except BaseException:
        for name in (APP, FRONT):
            core.activate(before, name, rollback[name])
        core.health(APP)
        raise
    print("Warm live screen and independent advertising authorization active; commerce credentials and other services preserved.")


if __name__ == "__main__":
    try:
        {"snapshot": snapshot, "build": build, "deploy": deploy, "verify": verify}[MODE]()
    except subprocess.CalledProcessError as error:
        (ROOT / "failure.private.log").write_bytes(error.output or b"")
        (ROOT / "failure.private.log").chmod(0o600)
        raise SystemExit("Release command failed; private diagnostics retained.")
