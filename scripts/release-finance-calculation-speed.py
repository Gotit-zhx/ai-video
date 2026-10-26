"""Reuse the verified, no-schema finance overlay publisher with the narrowly changed assets."""
import importlib.util
from pathlib import Path
import sys

source = Path('/opt/heyi/releases/finance-settled-goods-20261026/release-finance-settled-goods.py')
spec = importlib.util.spec_from_file_location('settled_goods_release', source)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
release.FILES = {'index.html', 'finance-workspace.js', 'finance-workspace.css'}
release.FAMILIES = {'FinanceRepository', 'FinanceCostReport', 'FinanceService', 'FinanceReadCache'}
release.TAG = 'finance-calculation-speed-20261026'
if __name__ == '__main__':
    {'snapshot': release.snapshot, 'build': release.build,
     'verify': release.verify, 'drain-deploy': release.base.drain_deploy}[sys.argv[2]]()
