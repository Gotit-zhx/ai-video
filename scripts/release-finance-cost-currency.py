"""Publish consistent operating-cost drilldown currencies without changing ledger or cost evidence."""
import importlib.util
import io
import subprocess
import sys
import zipfile

spec = importlib.util.spec_from_file_location(
    "cost_currency_base", "/opt/heyi/releases/finance-actual-costs-20261029/release-finance-actual-costs.py")
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
release = previous.release
release.BASE_SCHEMA = "135"
release.MIGRATION = None
release.TAG = "finance-cost-currency-20261029"
release.FAMILIES = {"FinanceProfitReport", "FinanceReadCache"}
release.FILES = {"index.html", "finance-workspace.js"}

def snapshot():
    root, core = release.ROOT, release.core
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    assert not (root / "snapshot.json").exists()
    assert release.base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == "135"
    before = {name: core.inspect(name) for name in (release.APP, release.FRONT)}
    core.write_json(root / "before-private.json", before)
    value = core.run("docker", "exec", release.APP, "cat", "/app/app.jar")
    (root / "previous.jar").write_bytes(value)
    (root / "previous.jar").chmod(0o600)
    with zipfile.ZipFile(io.BytesIO(value)) as jar:
        expected = {"jar": core.sha(value),
                    "expectedClasses": {n: core.sha(jar.read(n)) for n in jar.namelist() if release.replaced(n)},
                    "expectedFiles": {}}
    for name in release.FILES:
        value = core.run("docker", "exec", release.FRONT, "cat", "/usr/share/nginx/html/" + name)
        expected["expectedFiles"][name] = core.sha(value)
        target = root / "baseline-frontend" / name
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(value)
    core.write_json(root / "snapshot.json", expected)
    print("Captured exact live operating-cost currency assets; no schema or data changes.")


def deploy():
    release.deploy()
    result = release.core.read_json("activation.json")
    result["schemaChanges"] = False
    result["changedClasses"] = sorted(release.FAMILIES)
    release.core.write_json(release.ROOT / "activation.json", result)


release.base.deploy = deploy
if __name__ == "__main__":
    try:
        {"snapshot": snapshot, "build": release.build,
         "verify": release.verify, "drain-deploy": release.base.drain_deploy}[sys.argv[2]]()
    except subprocess.CalledProcessError:
        raise SystemExit("Cost currency release failed; activation rolls back automatically.")
