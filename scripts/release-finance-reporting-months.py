"""Publish durable user-confirmed reporting months over the exact live images."""
import gzip
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(sys.argv[1]).resolve()
BASE_SCHEMA = sys.argv[3] if len(sys.argv)>3 else "133"
assert BASE_SCHEMA in {"133", "134"}
spec = importlib.util.spec_from_file_location("finance_base", "/opt/heyi/releases/finance-groups-20261022/release-finance-company-groups.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
core, APP, FRONT = base.core, base.APP, base.FRONT
FILES = {"index.html", "finance-workspace.js", "finance-costs.js"}
FAMILIES = {'FinanceRepository', 'FinanceReportingMonths', 'FinanceReadCache', 'FinanceCostReport', 'FinanceCostExport', 'FinanceFields', 'FinanceService', 'FinanceShopDeletion'}
MIGRATION = "BOOT-INF/classes/db/migration/V134__finance_reporting_months.sql"
PREFIX = "BOOT-INF/classes/com/yingdao/aicommerce/finance/"
TAG = "finance-reporting-months-20261028"
if BASE_SCHEMA=="134":
    TAG = "finance-reporting-months-fast-20261028"
    FAMILIES = {"FinanceReportingMonths"}

def replaced(name):
    return name == MIGRATION or any(name == PREFIX + n + ".class" or name.startswith(PREFIX + n + "$") for n in FAMILIES)

def snapshot():
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    assert not (ROOT / "snapshot.json").exists()
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    core.write_json(ROOT / "before-private.json", before)
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    (ROOT / "previous.jar").write_bytes(previous)
    (ROOT / "previous.jar").chmod(0o600)
    with zipfile.ZipFile(io.BytesIO(previous)) as jar:
        expected = {"jar": core.sha(previous), "expectedClasses": {n: core.sha(jar.read(n)) for n in jar.namelist() if replaced(n)}, "expectedFiles": {}}
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == BASE_SCHEMA
    for name in FILES:
        path = "/usr/share/nginx/html/" + name
        exists = subprocess.run(["docker", "exec", FRONT, "test", "-e", path]).returncode == 0
        value = core.run("docker", "exec", FRONT, "cat", path) if exists else None
        expected["expectedFiles"][name] = core.sha(value) if exists else None
        if exists:
            destination = ROOT / "baseline-frontend" / name
            destination.parent.mkdir(exist_ok=True)
            destination.write_bytes(value)
    core.write_json(ROOT / "snapshot.json", expected)
    print("Captured exact affected production files/classes; no deployment performed.")

def build():
    assert not (ROOT / "prepared.json").exists()
    manifest, before, snapshot = (core.read_json(n) for n in ("manifest.json", "before-private.json", "snapshot.json"))
    for name, info in before.items():
        assert core.inspect(name)["Id"] == info["Id"], "Live container changed"
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    assert core.sha(previous) == snapshot["jar"]
    folder = ROOT / "app-image"
    folder.mkdir()
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(folder / "app.jar", "w") as new:
        assert {n: core.sha(old.read(n)) for n in old.namelist() if replaced(n)} == manifest["expectedClasses"]
        new.comment = old.comment
        for entry in old.infolist():
            if not replaced(entry.filename):
                new.writestr(entry, old.read(entry.filename))
        for name, digest in manifest["classes"].items():
            assert replaced(name)
            value = (ROOT / "classes" / name.removeprefix("BOOT-INF/classes/")).read_bytes()
            assert core.sha(value) == digest
            new.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(folder / "app.jar") as new:
        assert set(new.namelist()) == {n for n in old.namelist() if not replaced(n)} | set(manifest["classes"])
        assert all(old.read(n) == new.read(n) for n in old.namelist() if not replaced(n))
    images = {APP: "ai-commerce-platform:" + TAG, FRONT: "ai-commerce-frontend:" + TAG}
    (folder / "Dockerfile").write_text("FROM " + before[APP]["Image"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "-t", images[APP], str(folder))
    folder = ROOT / "frontend-image"
    folder.mkdir()
    lines = ["FROM " + before[FRONT]["Image"]]
    assert set(manifest["files"]) == FILES
    for name, digest in manifest["files"].items():
        destination = "/usr/share/nginx/html/" + name
        expected = manifest["expectedFiles"][name]
        if expected is None:
            core.run("docker", "exec", FRONT, "test", "!", "-e", destination)
        else:
            assert core.sha(core.run("docker", "exec", FRONT, "cat", destination)) == expected, "Live file changed: " + name
        source = ROOT / "frontend" / name
        assert core.sha(source.read_bytes()) == digest
        shutil.copy2(source, folder / name)
        (folder / (name + ".gz")).write_bytes(gzip.compress(source.read_bytes(), mtime=0))
        lines.extend(["COPY " + name + " " + destination, "COPY " + name + ".gz " + destination + ".gz"])
    (folder / "Dockerfile").write_text("\n".join(lines) + "\n")
    core.run("docker", "build", "-t", images[FRONT], str(folder))
    core.write_json(ROOT / "prepared.json", {"images": images, "jarHash": core.sha((ROOT / "app-image/app.jar").read_bytes())})
    print("Reporting-month overlays built; unrelated JAR and frontend content preserved.")

def verify():
    prepared, manifest = core.read_json("prepared.json"), core.read_json("manifest.json")
    core.health(APP)
    assert base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == "134"
    assert base.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='product_listing_warehouses'") == "1"
    assert base.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('finance_cost_books','finance_cost_settings','finance_cost_mappings','finance_expenses','finance_expense_rules','finance_workspace_audit')") == "6"
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["jarHash"]
    core.run("docker", "exec", FRONT, "nginx", "-t")
    for name, image in prepared["images"].items():
        assert core.inspect(name)["Config"]["Image"] == image
    for file, digest in manifest["files"].items():
        for accept in ("identity", "gzip"):
            value = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "10", "--resolve", "hoyee.org.cn:443:127.0.0.1", "-H", "Accept-Encoding: " + accept, "https://hoyee.org.cn/" + file)
            if value.startswith(b"\x1f\x8b"):
                value = gzip.decompress(value)
            assert core.sha(value) == digest, file
    for endpoint in ("finance/workspace/costs/orders/export", "finance/workspace/costs/orders"):
        code = core.run("curl", "--noproxy", "*", "-sS", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "10", "--resolve", "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/api/v1/" + endpoint).decode()
        assert code == "401", "Anonymous cost access must remain denied"
    assert base.sql("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='finance_records' AND index_name IN ('idx_finance_company_kind_date','idx_finance_settled_order')") == "2"

def deploy():
    assert not (ROOT / "activation.json").exists()
    before, prepared = core.read_json("before-private.json"), core.read_json("prepared.json")
    for name, info in before.items():
        assert core.inspect(name)["Id"] == info["Id"], "Live container changed during preparation"
    base.release.idle()
    assert base.sql("SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)") == "0", "Finance request in progress"
    assert base.sql("SELECT COUNT(*) FROM finance_ad_accounts WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)") == "0", "Advertising request in progress"
    assert base.sql("SELECT COUNT(*) FROM product_publish_workflows WHERE status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)") == "0", "Product workflow in progress"
    assert base.sql("SELECT COUNT(*) FROM finance_authorization_sessions WHERE status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)") == "0", "Authorization callback in progress"
    preserved = {item["Name"]: item["Id"] for item in json.loads(core.run("docker", "inspect", *core.run("docker", "ps", "-q").decode().split())) if item["Name"].lstrip("/") not in before}
    rollback = {}
    for name, info in before.items():
        rollback[name] = prepared["images"][name] + "-before"
        core.run("docker", "tag", info["Image"], rollback[name])
    try:
        core.activate(before, APP, prepared["images"][APP])
        core.health(APP)
        core.activate(before, FRONT, prepared["images"][FRONT])
        verify()
        assert all(core.configuration(core.inspect(name)) == core.configuration(info) for name, info in before.items())
        assert all(core.inspect(name)["Id"] == identifier for name, identifier in preserved.items())
        core.write_json(ROOT / "activation.json", {"images": prepared["images"], "rollbackImages": rollback, "unrelatedJarEntriesPreserved": True, "configurationPreserved": True, "schemaChanges": "V134 additive reporting-month registry"})
    except BaseException:
        for name in (APP, FRONT):
            core.activate(before, name, rollback[name])
        core.health(APP)
        raise
    print("Reporting-month support deployed; exact assets, additive schema and service health verified. Scoped correction runs separately.")

base.deploy = deploy
if __name__ == "__main__":
    try:
        {"snapshot": snapshot, "build": build, "verify": verify, "drain-deploy": base.drain_deploy}[sys.argv[2]]()
    except subprocess.CalledProcessError as error:
        (ROOT / "failure.private.log").write_bytes(error.output or b"")
        (ROOT / "failure.private.log").chmod(0o600)
        raise SystemExit("Release failed; private diagnostics retained.")
