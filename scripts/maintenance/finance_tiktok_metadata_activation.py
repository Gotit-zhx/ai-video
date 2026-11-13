#!/usr/bin/env python3
"""Explicit fixed V156 TikTok metadata maintenance with protected ad-data guards."""
import hashlib
import json
from pathlib import Path
import re
import signal
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import finance_query_projection_activation as fixed155

SQL = 'src/main/resources/db/migration/V156__finance_ad_campaign_metadata.sql'
SQL_SHA = '0a8268cd9b78cdbb24c2dee18b1ecbdf47a3f1965d4d75e06c5d098115b99abd'
HELPER = 'FinanceTikTokMetadataSchemaMaintenance.java'


class Activation(fixed155.Activation):
    SQL = SQL
    SQL_SHA = SQL_SHA
    HELPER = HELPER
    VERSION = '156'
    PREVIOUS_VERSION = '155'
    ROW_LIMIT = 100000
    COMPANY_LIMIT = 1000
    QUERY_TIMEOUT_MS = 5000
    SNAPSHOT = 'protected-ad-data-before.json'
    AFTER_SNAPSHOT = 'protected-ad-data-after.json'
    TABLES = (
        'finance_records',
        'finance_ad_accounts',
        'finance_ad_stores',
        'finance_ad_streams',
        'finance_ad_coverage',
        'finance_ad_bc_sync',
        'finance_ad_bc_costs',
        'finance_ad_spend_imports',
        'finance_ad_spend_allocation_sets',
        'finance_ad_spend_allocations',
        'finance_connections',
        'finance_authorization_sessions',
        'finance_sync_states',
    )
    FINANCE_PAGE_INDEX = (
        'organization_id', 'kind', 'record_date', 'id', 'shop_id', 'platform',
        'currency', 'source', 'status', 'category', 'order_id',
    )

    @staticmethod
    def _lines(value):
        return [] if not value else value.splitlines()

    def _metadata(self, host):
        tables = ','.join("'" + name + "'" for name in self.TABLES)
        columns_query = (f"SELECT /*+ MAX_EXECUTION_TIME({self.QUERY_TIMEOUT_MS}) */ table_name,ordinal_position,column_name,column_type,is_nullable "
                         "FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN (" + tables + ") "
                         "ORDER BY table_name,ordinal_position LIMIT 4096")
        indexes_query = (f"SELECT /*+ MAX_EXECUTION_TIME({self.QUERY_TIMEOUT_MS}) */ table_name,seq_in_index,column_name,index_name "
                         "FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name IN (" + tables + ") "
                         "AND (index_name='PRIMARY' OR (table_name='finance_records' AND index_name='idx_finance_page')) "
                         "ORDER BY table_name,index_name,seq_in_index LIMIT 4096")
        column_lines = self._lines(host.sql(columns_query)); index_lines = self._lines(host.sql(indexes_query))
        if len(column_lines) >= 4096 or len(index_lines) >= 4096:
            raise RuntimeError('Protected table metadata limit exceeded')
        columns = {name: [] for name in self.TABLES}; primary = {name: [] for name in self.TABLES}; page_index = []
        for line in column_lines:
            fields = line.split('\t')
            if len(fields) != 5 or fields[0] not in columns or not fields[1].isdigit():
                raise RuntimeError('Protected table column metadata is malformed')
            columns[fields[0]].append((int(fields[1]), fields[2], fields[3], fields[4]))
        for line in index_lines:
            fields = line.split('\t')
            if len(fields) != 4 or fields[0] not in primary or not fields[1].isdigit():
                raise RuntimeError('Protected table key metadata is malformed')
            if fields[3] == 'PRIMARY': primary[fields[0]].append((int(fields[1]), fields[2]))
            elif fields[0] == 'finance_records' and fields[3] == 'idx_finance_page': page_index.append((int(fields[1]), fields[2]))
            else: raise RuntimeError('Unexpected protected table index metadata')
        for table in self.TABLES:
            columns[table].sort(); primary[table].sort()
            names = [item[1] for item in columns[table]]
            keys = [item[1] for item in primary[table]]
            if not names or not keys or len(names) != len(set(names)) or any(key not in names for key in keys):
                raise RuntimeError('Protected table or primary key metadata is incomplete: ' + table)
        page_index.sort()
        if tuple(item[1] for item in page_index) != self.FINANCE_PAGE_INDEX:
            raise RuntimeError('Protected finance_records page index changed')
        canonical = {'columns': {name: columns[name] for name in self.TABLES},
                     'primary': {name: primary[name] for name in self.TABLES},
                     'financePageIndex': page_index}
        return canonical, hashlib.sha256(json.dumps(canonical, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

    @staticmethod
    def _digest(hashes):
        return {'rows': len(hashes), 'sha256': hashlib.sha256(
            ('\n'.join(hashes) + ('\n' if hashes else '')).encode()).hexdigest()}

    def _companies(self, host):
        query = (f"SELECT /*+ MAX_EXECUTION_TIME({self.QUERY_TIMEOUT_MS}) */ DISTINCT HEX(organization_id) organization_hex "
                 f"FROM `finance_ad_accounts` ORDER BY organization_hex LIMIT {self.COMPANY_LIMIT + 1}")
        companies = self._lines(host.sql(query))
        if len(companies) > self.COMPANY_LIMIT: raise RuntimeError('Protected ad-account company limit exceeded')
        if (companies != sorted(set(companies))
                or any(not re.fullmatch('[0-9A-F]{2,72}', value) or len(value) % 2 for value in companies)):
            raise RuntimeError('Protected ad-account company result is malformed')
        return companies

    def _finance_records_snapshot(self, host, columns, primary):
        encoded = ["COALESCE(HEX(CAST(`" + column[1] + "` AS BINARY)),'-')" for column in columns]
        row_hash = "SHA2(CONCAT_WS(CHAR(31)," + ','.join(encoded) + "),256)"
        order = ','.join('`' + column[1] + '`' for column in primary)
        hashes = []
        for company in self._companies(host):
            for kind in ('AD', 'AD_GMV'):
                remaining = self.ROW_LIMIT - len(hashes)
                query = (f"SELECT /*+ MAX_EXECUTION_TIME({self.QUERY_TIMEOUT_MS}) */ {row_hash} "
                         "FROM `finance_records` FORCE INDEX (`idx_finance_page`) "
                         f"WHERE organization_id=CONVERT(0x{company} USING utf8mb4) AND kind='{kind}' "
                         f"ORDER BY {order} LIMIT {remaining + 1}")
                rows = self._lines(host.sql(query))
                if len(rows) > remaining: raise RuntimeError('Protected table row limit exceeded: finance_records')
                if any(not re.fullmatch('[a-f0-9]{64}', value) for value in rows):
                    raise RuntimeError('Protected table digest result is malformed: finance_records')
                hashes.extend(rows)
        return self._digest(hashes)

    def _table_snapshot(self, host, table, columns, primary):
        if table == 'finance_records': return self._finance_records_snapshot(host, columns, primary)
        encoded = ["COALESCE(HEX(CAST(`" + column[1] + "` AS BINARY)),'-')" for column in columns]
        row_hash = "SHA2(CONCAT_WS(CHAR(31)," + ','.join(encoded) + "),256)"
        order = ','.join('`' + column[1] + '`' for column in primary)
        query = (f"SELECT /*+ MAX_EXECUTION_TIME({self.QUERY_TIMEOUT_MS}) */ {row_hash} FROM `{table}` "
                 f"ORDER BY {order} LIMIT {self.ROW_LIMIT + 1}")
        hashes = self._lines(host.sql(query))
        if len(hashes) > self.ROW_LIMIT: raise RuntimeError('Protected table row limit exceeded: ' + table)
        if any(not re.fullmatch('[a-f0-9]{64}', value) for value in hashes):
            raise RuntimeError('Protected table digest result is malformed: ' + table)
        return self._digest(hashes)

    def protected_snapshot(self):
        host = self.runtime(fixed155.read_json(self.config_path)).host
        metadata, metadata_hash = self._metadata(host)
        tables = {name: self._table_snapshot(host, name, metadata['columns'][name], metadata['primary'][name])
                  for name in self.TABLES}
        return {'version': 1, 'metadataSha256': metadata_hash, 'tables': tables}

    def save_before(self):
        super().save_before()
        try: fixed155.shared_dev.atomic(self.op / self.SNAPSHOT, self.protected_snapshot())
        except BaseException:
            self.guard_snapshot_rejected = True
            raise

    def retain_pre_migration_failure(self, failure):
        return getattr(self, 'guard_snapshot_rejected', False)

    def check_static(self):
        super().check_static()
        before = fixed155.read_json(self.op / self.SNAPSHOT)
        after = self.protected_snapshot()
        if before != after: raise RuntimeError('Protected ad data changed during V' + self.VERSION + ' activation')
        fixed155.shared_dev.atomic(self.op / self.AFTER_SNAPSHOT, after)


def main(argv=None, activation_type=None):
    fixed155.main(argv, Activation if activation_type is None else activation_type)


if __name__ == '__main__':
    def interrupted(number, _): raise InterruptedError('Maintenance interrupted')
    for number in (signal.SIGHUP, signal.SIGTERM): signal.signal(number, interrupted)
    try: main()
    except BaseException as failure:
        if isinstance(failure, SystemExit): raise
        print(json.dumps({'phase': 'needs-attention', 'failureType': type(failure).__name__})); sys.exit(1)
