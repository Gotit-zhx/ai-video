"""Publish actual shipment evidence and return credits over the exact current live assets."""
import gzip
import importlib.util
import subprocess
import sys

spec = importlib.util.spec_from_file_location(
    "actual_cost_base", "/opt/heyi/releases/finance-settlement-export-20261029/release-finance-settlement-export.py")
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
release = previous.release
release.TAG = "finance-actual-costs-20261029"
release.MIGRATION = "BOOT-INF/classes/db/migration/V135__finance_actual_fulfillment_costs.sql"
release.FAMILIES = {
    "FinanceActualCosts", "FinanceOrderCosts", "FinanceOrderCharges", "FinanceCostReport",
    "FinanceCostExport", "FinanceProfitReport", "FinanceService", "FinanceCostController",
    "FinanceCostWorkspace", "FinanceShopDeletion", "FinanceReadCache",
}
release.FILES = {"index.html", "finance-workspace.js", "finance-costs.js"}


def verify():
    core = release.core
    prepared, manifest = core.read_json("prepared.json"), core.read_json("manifest.json")
    core.health(release.APP)
    assert release.base.sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == "135"
    assert release.base.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('finance_actual_order_costs','finance_return_cost_receipts')") == "2"
    assert core.sha(core.run("docker", "exec", release.APP, "cat", "/app/app.jar")) == prepared["jarHash"]
    core.run("docker", "exec", release.FRONT, "nginx", "-t")
    for name, image in prepared["images"].items():
        assert core.inspect(name)["Config"]["Image"] == image
    for name, digest in manifest["files"].items():
        for accept in ("identity", "gzip"):
            value = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "10",
                "--resolve", "hoyee.org.cn:443:127.0.0.1", "-H", "Accept-Encoding: " + accept, "https://hoyee.org.cn/" + name)
            if value.startswith(b"\x1f\x8b"):
                value = gzip.decompress(value)
            assert core.sha(value) == digest, name
    for endpoint in ("costs/returns", "costs/orders", "costs/orders/export"):
        status = core.run("curl", "--noproxy", "*", "-sS", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "10",
            "--resolve", "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/api/v1/finance/workspace/" + endpoint).decode()
        assert status == "401", "Anonymous financial evidence access must remain denied"


def deploy():
    release.deploy()
    result = release.core.read_json("activation.json")
    result["schemaChanges"] = "V135 additive actual shipment and return receipt evidence"
    result["changedClasses"] = sorted(release.FAMILIES)
    release.core.write_json(release.ROOT / "activation.json", result)


release.verify = verify
release.base.deploy = deploy
if __name__ == "__main__":
    try:
        {"snapshot": previous.snapshot, "build": release.build,
         "verify": verify, "drain-deploy": release.base.drain_deploy}[sys.argv[2]]()
    except subprocess.CalledProcessError:
        raise SystemExit("Actual cost release failed; activation rolls back automatically.")
