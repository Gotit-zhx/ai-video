"""Publish operating-cost grouping, on-demand subsets and the compact expenses toolbar."""
import importlib.util
from pathlib import Path
import sys

source = Path("/opt/heyi/releases/finance-settled-goods-20261026/release-finance-settled-goods.py")
spec = importlib.util.spec_from_file_location("operating_release", source)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
release.FILES = {"index.html", "finance-workspace.js", "finance-workspace.css",
                 "finance-charges.js", "finance-costs.js"}
release.FAMILIES = {"FinanceCostReport", "FinanceCostExport", "FinanceCostWorkspace",
                    "FinanceCostController", "FinanceProfitReport"}
release.TAG = "finance-operating-costs-20261027"
if __name__ == "__main__":
    {"snapshot": release.snapshot, "build": release.build,
     "verify": release.verify, "drain-deploy": release.base.drain_deploy}[sys.argv[2]]()
