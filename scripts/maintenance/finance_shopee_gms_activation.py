#!/usr/bin/env python3
"""Fixed V158 activation. Requires frozen PR65 V155-157 maintenance and schema first.

Reuses owned helper sessions, source CAS, gate/drain, history-prefix checks,
compile-before-reload, bounded protected sentinels and explicit recovery.
"""
import signal
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import finance_projection_baseline_activation as baseline

class Activation(baseline.Activation):
    VERSION = '158'
    PREVIOUS_VERSION = '157'
    SQL = 'src/main/resources/db/migration/V158__finance_shopee_gms_reference.sql'
    SQL_SHA = '0aac50cd3cd29824adfa83c6171668e8086d81456ab8e811f68cf29f589e92e6'
    HELPER = 'FinanceShopeeGmsSchemaMaintenance.java'

if __name__ == '__main__':
    def interrupted(number, _): raise InterruptedError('Maintenance interrupted')
    for number in (signal.SIGHUP, signal.SIGTERM): signal.signal(number, interrupted)
    baseline.main(activation_type=Activation)
