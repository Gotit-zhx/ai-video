"""Publish verified completion-date attribution; date-only historical repair runs separately."""
import importlib.util
from pathlib import Path
import sys

source = Path("/opt/heyi/releases/finance-settled-goods-20261026/release-finance-settled-goods.py")
spec = importlib.util.spec_from_file_location("finance_completion_release", source)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
release.FILES = {"index.html", "finance-workspace.js"}
release.FAMILIES = {"FinanceSettlementDates", "FinanceMarketplace", "FinanceRepository",
                    "FinanceFields", "FinanceSync", "FinanceService", "FinanceReadCache"}
release.TAG = "finance-settlement-completion-20261027"
if __name__ == "__main__":
    {"snapshot": release.snapshot, "build": release.build,
     "verify": release.verify, "drain-deploy": release.base.drain_deploy}[sys.argv[2]]()
