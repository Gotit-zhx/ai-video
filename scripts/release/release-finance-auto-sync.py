"""Publish explicit finance HTTP bindings and atomic authorization/sync initialization."""
import importlib.util
import subprocess
import sys

spec = importlib.util.spec_from_file_location(
    "auto_sync_base",
    "/opt/heyi/releases/product-size-chart-repair-20261029/release-product-size-chart-repair.py")
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
release = previous.release
release.TAG = "finance-auto-sync-20261029"
release.PREFIX = "BOOT-INF/classes/com/yingdao/aicommerce/finance/"
release.FAMILIES = {"FinanceController", "FinanceService", "FinanceShopAuthorization", "FinanceSyncChannels"}
release.FILES = {"index.html", "finance-workspace.js"}

if __name__ == "__main__":
    try:
        {"snapshot": previous.previous.snapshot, "build": previous.build,
         "verify": previous.deployment_verify, "drain-deploy": release.base.drain_deploy}[sys.argv[2]]()
    except subprocess.CalledProcessError:
        raise SystemExit("Finance auto-sync release failed; activation rolls back automatically.")
