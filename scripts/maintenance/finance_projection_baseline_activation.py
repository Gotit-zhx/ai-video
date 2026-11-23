#!/usr/bin/env python3
"""Fixed additive V157 activation using the existing owned maintenance protocol.

Inspect is local; preflight/deploy require the canonical on-host paths and root.
Inputs, source CAS, gate/drain, owned helper cleanup, history-prefix verification,
compile-before-reload and explicit failure recovery are inherited from V155.
Protection uses schema metadata and bounded primary-key sentinels, not a claim
that every raw financial row was scanned. The immutable SQL changes only the two
small derived metadata tables. No credentials are accepted on the command line.
"""
import hashlib
import json
from pathlib import Path
import re
import signal
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import finance_query_projection_activation as fixed155

SQL = 'src/main/resources/db/migration/V157__finance_projection_baseline_revisions.sql'
SQL_SHA = '69eb1901073fd35c9baf4aefa4c1ad5b66f7a8be76f212ba4685b025ea49939c'
HELPER = 'FinanceProjectionBaselineSchemaMaintenance.java'


class Activation(fixed155.Activation):
    SQL = SQL
    SQL_SHA = SQL_SHA
    HELPER = HELPER
    VERSION = '157'
    PREVIOUS_VERSION = '156'
    SNAPSHOT = 'protected-finance-sentinels-before.json'
    AFTER_SNAPSHOT = 'protected-finance-sentinels-after.json'
    SENTINEL_LIMIT = 16
    METADATA_LIMIT = 8192
    QUERY_TIMEOUT_MS = 5000
    # Only fixed identifiers are allowed. Both changed derived tables are excluded.
    TABLES = (
        'finance_records', 'finance_connections', 'finance_authorization_sessions',
        'finance_sync_states', 'finance_shops', 'finance_imports', 'finance_fx_snapshots',
        'finance_cost_books', 'finance_cost_sources', 'finance_manual_costs',
        'finance_cost_settings', 'finance_cost_mappings', 'finance_expenses',
        'finance_expense_rules', 'finance_workspace_audit', 'finance_order_costs',
        'finance_actual_order_costs', 'finance_return_cost_receipts', 'finance_reporting_months',
        'finance_label_rules', 'finance_charge_imports', 'finance_charge_rows',
        'finance_marketing_orders', 'finance_label_imports', 'finance_label_invoice_rows',
        'finance_label_order_totals', 'finance_qianyi_accounts', 'finance_qianyi_connections',
        'finance_qianyi_returns', 'finance_qianyi_return_costs', 'finance_ad_applications',
        'finance_ad_accounts', 'finance_ad_stores', 'finance_ad_streams', 'finance_ad_coverage',
        'finance_ad_bc_sync', 'finance_ad_bc_costs', 'finance_ad_spend_imports',
        'finance_ad_spend_allocation_sets', 'finance_ad_spend_allocations',
        'finance_shopee_ad_grants', 'finance_shopee_ad_groups', 'finance_shopee_ad_daily',
        'finance_ad_campaign_metadata',
    )

    @staticmethod
    def _lines(value):
        return [] if not value else value.splitlines()

    def _metadata(self, host):
        names = ','.join("'" + name + "'" for name in self.TABLES)
        where = ' WHERE table_schema=DATABASE() AND table_name IN (' + names + ') '
        hint = f'SELECT /*+ MAX_EXECUTION_TIME({self.QUERY_TIMEOUT_MS}) */ '
        queries = {
            'columns': hint + "table_name,ordinal_position,column_name,column_type,is_nullable,COALESCE(column_default,'NULL'),extra,COALESCE(collation_name,'NULL') FROM information_schema.columns" + where + f'ORDER BY table_name,ordinal_position LIMIT {self.METADATA_LIMIT}',
            'indexes': hint + "table_name,index_name,non_unique,seq_in_index,COALESCE(column_name,'NULL'),COALESCE(collation,'NULL'),COALESCE(sub_part,'NULL'),index_type FROM information_schema.statistics" + where + f'ORDER BY table_name,index_name,seq_in_index LIMIT {self.METADATA_LIMIT}',
            'tables': hint + "table_name,engine,table_collation FROM information_schema.tables" + where + f'ORDER BY table_name LIMIT {self.METADATA_LIMIT}',
        }
        metadata = {key: self._lines(host.sql(query)) for key, query in queries.items()}
        if any(len(rows) >= self.METADATA_LIMIT for rows in metadata.values()):
            raise RuntimeError('Protected metadata limit exceeded')
        columns = {name: [] for name in self.TABLES}; keys = {name: [] for name in self.TABLES}
        identifier = re.compile('[a-zA-Z_][a-zA-Z0-9_]*')
        for line in metadata['columns']:
            fields = line.split('\t')
            if (len(fields) != 8 or fields[0] not in columns or not fields[1].isdigit()
                    or not identifier.fullmatch(fields[2])):
                raise RuntimeError('Protected column metadata is malformed')
            columns[fields[0]].append((int(fields[1]), fields[2]))
        for line in metadata['indexes']:
            fields = line.split('\t')
            if (len(fields) != 8 or fields[0] not in keys or not fields[3].isdigit()
                    or not identifier.fullmatch(fields[1]) or not identifier.fullmatch(fields[4])):
                raise RuntimeError('Protected index metadata is malformed')
            if fields[1] == 'PRIMARY':
                if fields[2] != '0' or fields[6] != 'NULL' or fields[7] != 'BTREE':
                    raise RuntimeError('Protected primary index is unsupported')
                keys[fields[0]].append((int(fields[3]), fields[4]))
        tables = set()
        for line in metadata['tables']:
            fields = line.split('\t')
            if len(fields) != 3 or fields[0] not in columns or fields[0] in tables or fields[1] != 'InnoDB':
                raise RuntimeError('Protected table metadata is malformed')
            tables.add(fields[0])
        if tables != set(self.TABLES): raise RuntimeError('Protected table metadata is incomplete')
        for name in self.TABLES:
            columns[name].sort(); keys[name].sort()
            colnames = [column for _, column in columns[name]]
            keynames = [column for _, column in keys[name]]
            if (not colnames or not keynames or len(colnames) != len(set(colnames))
                    or len(keynames) != len(set(keynames)) or not set(keynames).issubset(colnames)
                    or [ordinal for ordinal, _ in columns[name]] != list(range(1, len(colnames) + 1))
                    or [ordinal for ordinal, _ in keys[name]] != list(range(1, len(keynames) + 1))):
                raise RuntimeError('Protected table or primary key metadata is incomplete: ' + name)
        metadata_hash = hashlib.sha256(json.dumps(metadata, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        return columns, keys, metadata_hash

    def _sentinels(self, host, table, columns, keys, direction):
        encoded = ["COALESCE(HEX(CAST(`" + name + "` AS BINARY)),'-')" for _, name in columns]
        row_hash = 'SHA2(CONCAT_WS(CHAR(31),' + ','.join(encoded) + '),256)'
        order = ','.join('`' + name + '` ' + direction for _, name in keys)
        query = (f'SELECT /*+ MAX_EXECUTION_TIME({self.QUERY_TIMEOUT_MS}) */ {row_hash} '
                 f'FROM `{table}` FORCE INDEX (`PRIMARY`) ORDER BY {order} LIMIT {self.SENTINEL_LIMIT}')
        hashes = self._lines(host.sql(query))
        if len(hashes) > self.SENTINEL_LIMIT or any(not re.fullmatch('[a-f0-9]{64}', item) for item in hashes):
            raise RuntimeError('Protected sentinel digest is malformed or exceeds its limit: ' + table)
        return {'sampledRows': len(hashes), 'sha256': hashlib.sha256(
            ('\n'.join(hashes) + ('\n' if hashes else '')).encode()).hexdigest()}

    def protected_snapshot(self):
        host = self.runtime(fixed155.read_json(self.config_path)).host
        columns, keys, metadata_hash = self._metadata(host)
        tables = {name: {'first': self._sentinels(host, name, columns[name], keys[name], 'ASC'),
                         'last': self._sentinels(host, name, columns[name], keys[name], 'DESC')}
                  for name in self.TABLES}
        return {'version': 1, 'scope': 'bounded-primary-key-sentinels', 'sentinelLimitPerEdge': self.SENTINEL_LIMIT,
                'migrationSha256': self.SQL_SHA, 'metadataSha256': metadata_hash, 'tables': tables}

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
        if before != after: raise RuntimeError('Protected finance schema or sentinels changed during V157 activation')
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
