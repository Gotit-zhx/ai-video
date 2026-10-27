"""Publish order placement columns and bounded exact-number lookup. No date or amount backfill."""
import importlib.util
from pathlib import Path
import sys

source = Path("/opt/heyi/releases/finance-settled-goods-20261026/release-finance-settled-goods.py")
spec = importlib.util.spec_from_file_location("finance_date_search_release", source)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
release.FILES = {"index.html", "finance-workspace.js", "finance-workspace.css",
                 "finance-costs.js", "finance-charges.js"}
release.FAMILIES = {"FinanceFields", "FinanceProductSkus", "FinanceRecordSearch",
                    "FinanceRepository", "FinanceController", "FinanceCostController",
                    "FinanceCostWorkspace", "FinanceCostReport", "FinanceCostExport",
                    "FinanceOrderCharges"}
release.TAG = "finance-date-search-20261027"
if __name__ == "__main__":
    {"snapshot": release.snapshot, "build": release.build,
     "verify": release.verify, "drain-deploy": release.base.drain_deploy}[sys.argv[2]]()
