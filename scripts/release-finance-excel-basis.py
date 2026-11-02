"""Publish the verified Excel income and statement-date basis over exact live assets."""
import importlib.util
import subprocess
import sys

spec = importlib.util.spec_from_file_location(
    "excel_basis_release",
    "/opt/heyi/releases/finance-settlement-export-20261029/release-finance-settlement-export.py")
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
release = previous.release
release.TAG = "finance-excel-basis-20261029"
release.FAMILIES = {
    "FinanceSettlementBreakdown", "FinanceSettlementDates", "FinanceReportingMonths",
    "FinanceRepository", "FinanceFields", "FinanceReadCache", "FinanceProfitReport",
    "FinanceService", "FinanceController", "FinanceCostReport", "FinanceCostExport",
}
release.FILES = {"index.html", "finance-workspace.js", "finance-costs.js"}
if __name__ == "__main__":
    try:
        {"snapshot": previous.snapshot, "build": release.build,
         "verify": release.verify, "drain-deploy": release.base.drain_deploy}[sys.argv[2]]()
    except subprocess.CalledProcessError:
        raise SystemExit("Excel basis release failed; activation rolls back automatically.")
