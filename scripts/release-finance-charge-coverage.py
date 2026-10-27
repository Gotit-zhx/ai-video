"""Expose missing label configuration without reporting it as a confirmed zero cost."""
import importlib.util
from pathlib import Path
import sys

source = Path("/opt/heyi/releases/finance-settled-goods-20261026/release-finance-settled-goods.py")
spec = importlib.util.spec_from_file_location("charge_coverage_release", source)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
release.FILES = {"index.html", "finance-workspace.js", "finance-charges.js"}
release.FAMILIES = {"FinanceOrderCharges", "FinanceService", "FinanceProfitReport"}
release.TAG = "finance-charge-coverage-20261027"
if __name__ == "__main__":
    {"snapshot": release.snapshot, "build": release.build,
     "verify": release.verify, "drain-deploy": release.base.drain_deploy}[sys.argv[2]]()
