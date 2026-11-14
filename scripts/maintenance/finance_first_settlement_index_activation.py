#!/usr/bin/env python3
"""Fixed V159 index activation using the canonical owned maintenance protocol.

Inspect is local. Host preflight and deploy remain restricted by the inherited
coordinator. The protected snapshot covers all existing metadata and bounded
primary-key sentinels while normalizing only the exact V159 target index.
"""
import hashlib
import json
from pathlib import Path
import re
import signal
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import finance_projection_baseline_activation as fixed157

SQL = 'src/main/resources/db/migration/V159__finance_first_settlement_order.sql'
SQL_SHA = '51c6e8398351aacf81d79bb299a59eeb8d5d59f3182d80ffe2850fdeb59afabf'
JAVA = 'src/main/java/com/yingdao/aicommerce/finance/FinanceLabelCosts.java'
JAVA_BEFORE_SHA = 'a0ee02187babace9309c452c4580948d851507a8b49409bfcfb8c1fc9e1b71e3'
JAVA_TARGET_SHA = '8d503c53ba01d8bfaad1c069f5ea5c6c82b2e4f3164e1f432ccfa1ab7e49b738'
HELPER = 'FinanceFirstSettlementIndexSchemaMaintenance.java'


class Activation(fixed157.Activation):
    SQL = SQL
    SQL_SHA = SQL_SHA
    HELPER = HELPER
    VERSION = '159'
    PREVIOUS_VERSION = '158'
    JAVA = JAVA
    JAVA_BEFORE_SHA = JAVA_BEFORE_SHA
    JAVA_TARGET_SHA = JAVA_TARGET_SHA
    TARGET_INDEX = 'idx_finance_first_settlement'
    OLD_INDEX = 'idx_finance_settled_order'
    TARGET_COLUMNS = (
        'organization_id', 'shop_id', 'kind', 'order_id', 'source', 'status',
        'record_date', 'id', 'platform', 'category', 'currency',
    )
    OLD_COLUMNS = (
        'organization_id', 'shop_id', 'kind', 'order_id', 'source', 'status',
        'record_date', 'platform', 'category', 'currency', 'amount', 'revenue',
        'impressions', 'clicks', 'conversions',
    )
    TABLES = fixed157.Activation.TABLES + ('finance_shopee_gms_sync', 'finance_shopee_gms_daily')

    def __init__(self, operation, config=fixed157.fixed155.CANONICAL_CONFIG,
                 root=fixed157.fixed155.CANONICAL_ROOT, helper=None):
        super().__init__(operation, config, root, helper)
        expected = {
            self.SQL: {'beforeHash': None, 'targetHash': self.SQL_SHA},
            self.JAVA: {'beforeHash': self.JAVA_BEFORE_SHA, 'targetHash': self.JAVA_TARGET_SHA},
        }
        if self.manifest != expected:
            raise RuntimeError('Only the fixed V159 SQL and frozen FinanceLabelCosts source are accepted')

    @staticmethod
    def _index_shape(rows):
        return [(int(fields[3]), fields[4], fields[2], fields[5], fields[6], fields[7], fields[8])
                for fields in rows]

    def _metadata(self, host, require_target=False):
        names = ','.join("'" + name + "'" for name in self.TABLES)
        where = ' WHERE table_schema=DATABASE() AND table_name IN (' + names + ') '
        hint = f'SELECT /*+ MAX_EXECUTION_TIME({self.QUERY_TIMEOUT_MS}) */ '
        queries = {
            'columns': hint + "table_name,ordinal_position,column_name,column_type,is_nullable,COALESCE(column_default,'NULL'),extra,COALESCE(collation_name,'NULL') FROM information_schema.columns" + where + f'ORDER BY table_name,ordinal_position LIMIT {self.METADATA_LIMIT}',
            'indexes': hint + "table_name,index_name,non_unique,seq_in_index,COALESCE(column_name,'NULL'),COALESCE(collation,'NULL'),COALESCE(sub_part,'NULL'),index_type,is_visible FROM information_schema.statistics" + where + f'ORDER BY table_name,index_name,seq_in_index LIMIT {self.METADATA_LIMIT}',
            'tables': hint + "table_name,engine,table_collation FROM information_schema.tables" + where + f'ORDER BY table_name LIMIT {self.METADATA_LIMIT}',
        }
        raw = {key: self._lines(host.sql(query)) for key, query in queries.items()}
        if any(len(rows) >= self.METADATA_LIMIT for rows in raw.values()):
            raise RuntimeError('Protected metadata limit exceeded')

        identifier = re.compile('[a-zA-Z_][a-zA-Z0-9_]*')
        parsed_indexes = []
        for line in raw['indexes']:
            fields = line.split('\t')
            if (len(fields) != 9 or fields[0] not in self.TABLES or fields[2] not in {'0', '1'}
                    or not fields[3].isdigit() or not identifier.fullmatch(fields[1])
                    or not identifier.fullmatch(fields[4]) or fields[5] not in {'A', 'D', 'NULL'}
                    or (fields[6] != 'NULL' and not fields[6].isdigit())
                    or not identifier.fullmatch(fields[7]) or fields[8] not in {'YES', 'NO'}):
                raise RuntimeError('Protected index metadata is malformed')
            parsed_indexes.append(fields)

        def exact_index(name, columns):
            rows = sorted((fields for fields in parsed_indexes
                           if fields[0] == 'finance_records' and fields[1] == name),
                          key=lambda fields: int(fields[3]))
            expected = [(position, column, '1', 'A', 'NULL', 'BTREE', 'YES')
                        for position, column in enumerate(columns, 1)]
            if self._index_shape(rows) != expected:
                raise RuntimeError('Protected finance index shape is invalid: ' + name)
            return rows

        exact_index(self.OLD_INDEX, self.OLD_COLUMNS)
        target_rows = [fields for fields in parsed_indexes
                       if fields[0] == 'finance_records' and fields[1] == self.TARGET_INDEX]
        if require_target:
            exact_index(self.TARGET_INDEX, self.TARGET_COLUMNS)
        elif target_rows:
            raise RuntimeError('V159 target index must be absent before migration')

        normalized = dict(raw)
        normalized['indexes'] = ['\t'.join(fields) for fields in parsed_indexes
                                 if not (fields[0] == 'finance_records' and fields[1] == self.TARGET_INDEX)]
        columns = {name: [] for name in self.TABLES}
        keys = {name: [] for name in self.TABLES}
        for line in normalized['columns']:
            fields = line.split('\t')
            if (len(fields) != 8 or fields[0] not in columns or not fields[1].isdigit()
                    or not identifier.fullmatch(fields[2])):
                raise RuntimeError('Protected column metadata is malformed')
            columns[fields[0]].append((int(fields[1]), fields[2]))
        for fields in parsed_indexes:
            if fields[1] == 'PRIMARY':
                if fields[2] != '0' or fields[5] != 'A' or fields[6] != 'NULL' or fields[7] != 'BTREE':
                    raise RuntimeError('Protected primary index is unsupported')
                keys[fields[0]].append((int(fields[3]), fields[4]))
        tables = set()
        for line in normalized['tables']:
            fields = line.split('\t')
            if len(fields) != 3 or fields[0] not in columns or fields[0] in tables or fields[1] != 'InnoDB':
                raise RuntimeError('Protected table metadata is malformed')
            tables.add(fields[0])
        if tables != set(self.TABLES):
            raise RuntimeError('Protected table metadata is incomplete')
        for name in self.TABLES:
            columns[name].sort(); keys[name].sort()
            colnames = [column for _, column in columns[name]]
            keynames = [column for _, column in keys[name]]
            if (not colnames or not keynames or len(colnames) != len(set(colnames))
                    or len(keynames) != len(set(keynames)) or not set(keynames).issubset(colnames)
                    or [ordinal for ordinal, _ in columns[name]] != list(range(1, len(colnames) + 1))
                    or [ordinal for ordinal, _ in keys[name]] != list(range(1, len(keynames) + 1))):
                raise RuntimeError('Protected table or primary key metadata is incomplete: ' + name)
        finance_columns = {column for _, column in columns['finance_records']}
        if not set(self.OLD_COLUMNS + self.TARGET_COLUMNS).issubset(finance_columns):
            raise RuntimeError('Protected finance index columns are incomplete')
        metadata_hash = hashlib.sha256(json.dumps(
            normalized, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        return columns, keys, metadata_hash

    def protected_snapshot(self, require_target=False):
        host = self.runtime(fixed157.fixed155.read_json(self.config_path)).host
        columns, keys, metadata_hash = self._metadata(host, require_target=require_target)
        tables = {name: {'first': self._sentinels(host, name, columns[name], keys[name], 'ASC'),
                         'last': self._sentinels(host, name, columns[name], keys[name], 'DESC')}
                  for name in self.TABLES}
        return {'version': 1, 'scope': 'bounded-primary-key-sentinels',
                'sentinelLimitPerEdge': self.SENTINEL_LIMIT,
                'migrationSha256': self.SQL_SHA, 'metadataSha256': metadata_hash,
                'tables': tables}

    def check_static(self):
        fixed157.fixed155.Activation.check_static(self)
        before = fixed157.fixed155.read_json(self.op / self.SNAPSHOT)
        after = self.protected_snapshot(require_target=True)
        if before != after:
            raise RuntimeError('Protected finance schema or sentinels changed during V159 activation')
        fixed157.fixed155.shared_dev.atomic(self.op / self.AFTER_SNAPSHOT, after)


def main(argv=None, activation_type=None):
    fixed157.main(argv, Activation if activation_type is None else activation_type)


if __name__ == '__main__':
    def interrupted(number, _): raise InterruptedError('Maintenance interrupted')
    for number in (signal.SIGHUP, signal.SIGTERM): signal.signal(number, interrupted)
    try: main()
    except BaseException as failure:
        if isinstance(failure, SystemExit): raise
        print(json.dumps({'phase': 'needs-attention', 'failureType': type(failure).__name__})); sys.exit(1)
