"""Fixed V159 local guard drills; no Docker, SSH or database connection."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SCRIPT = HERE / 'finance_first_settlement_index_activation.py'
JAVA = 'src/main/java/com/yingdao/aicommerce/finance/FinanceLabelCosts.java'
sys.path.insert(0, str(ROOT / 'scripts'))
import shared_dev
from scripts.maintenance.test_finance_query_projection_activation import Host, Store


def digest(data): return hashlib.sha256(data).hexdigest()


OLD_INDEX = (
    'organization_id', 'shop_id', 'kind', 'order_id', 'source', 'status',
    'record_date', 'platform', 'category', 'currency', 'amount', 'revenue',
    'impressions', 'clicks', 'conversions',
)
TARGET_INDEX = (
    'organization_id', 'shop_id', 'kind', 'order_id', 'source', 'status',
    'record_date', 'id', 'platform', 'category', 'currency',
)


class ProtectedHost(Host):
    def __init__(self, root, tables):
        super().__init__(root)
        self.version = '158'
        self.history = '1\t158\tSQL\tV158__finance_shopee_gms_reference.sql\t7\t1'
        self.tables = tables
        self.queries = []
        self.fault = None
        self.target = False
        self.target_columns = TARGET_INDEX
        self.target_non_unique = '1'
        self.target_collation = 'A'
        self.target_sub_part = 'NULL'
        self.target_type = 'BTREE'
        self.target_visible = 'YES'
        self.old_columns = OLD_INDEX
        self.old_visible = 'YES'
        self.extra_index = False
        self.extra_visible = 'YES'

    @staticmethod
    def index_rows(name, index, columns, non_unique='1', index_type='BTREE', sub_part='NULL', visible='YES'):
        return [f'{name}\t{index}\t{non_unique}\t{position}\t{column}\tA\t{sub_part}\t{index_type}\t{visible}'
                for position, column in enumerate(columns, 1)]

    def sql(self, query):
        self.queries.append(query)
        if 'installed_rank' in query: return self.history
        if self.fault == 'timeout': raise subprocess.TimeoutExpired('protected-sentinel', 15)
        if 'information_schema.columns' in query:
            rows = []
            for name in self.tables:
                columns = ('id',) if name != 'finance_records' else tuple(dict.fromkeys(('id',) + OLD_INDEX))
                rows.extend(f'{name}\t{position}\t{column}\tchar(36)\tNO\tNULL\t\tutf8mb4_0900_ai_ci'
                            for position, column in enumerate(columns, 1))
            return '\n'.join(rows)
        if 'information_schema.statistics' in query:
            rows = []
            for name in self.tables:
                rows.extend(self.index_rows(name, 'PRIMARY', ('id',), non_unique='0'))
            rows.extend(self.index_rows('finance_records', 'idx_finance_settled_order', self.old_columns,
                visible=self.old_visible))
            if self.target:
                rows.extend(self.index_rows('finance_records', 'idx_finance_first_settlement', self.target_columns,
                    non_unique=self.target_non_unique, index_type=self.target_type, sub_part=self.target_sub_part,
                    visible=self.target_visible))
                if self.target_collation != 'A':
                    rows = [row.replace('\tA\t', '\t' + self.target_collation + '\t')
                            if '\tidx_finance_first_settlement\t' in row else row for row in rows]
            if self.extra_index:
                rows.extend(self.index_rows('finance_records', 'idx_foreign_new', ('organization_id',),
                    visible=self.extra_visible))
            if self.fault == 'metadata-overflow':
                rows.extend(self.index_rows('finance_records', 'idx_padding', ('organization_id',)) * 8192)
            return '\n'.join(rows)
        if 'information_schema.tables' in query:
            return '\n'.join(name + '\tInnoDB\tutf8mb4_0900_ai_ci' for name in self.tables)
        if self.fault == 'overflow': return '\n'.join('a' * 64 for _ in range(17))
        if self.fault == 'malformed': return 'synthetic-secret-must-not-reach-receipt'
        return ('b' if self.fault == 'data' else 'a') * 64


class FirstSettlementActivationDrills(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), 'Fixed V159 activation implementation is missing')
        spec = importlib.util.spec_from_file_location('finance_first_settlement_index_activation', SCRIPT)
        self.m = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.m)
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve(); self.root = self.base / 'source'; self.root.mkdir()
        self.op = self.base / 'operation'; self.op.mkdir(mode=0o700)
        (self.root / 'target/classes').mkdir(parents=True)
        (self.root / 'target/classes/Ready.class').write_bytes(b'old')
        self.config = {'sourceRoot': str(self.root), 'appContainer': 'test-app', 'uid': os.getuid(), 'gid': os.getgid(),
                       'javaHome': '/jdk', 'schemaFingerprint': '158', 'migrationFiles': {}, 'reloadTimeout': .02}
        self.config_path = self.base / 'runtime.json'; self.config_path.write_text(json.dumps(self.config))
        old_java = subprocess.check_output(['git', 'show', '363a7bda^:' + JAVA], cwd=ROOT)
        new_java = (ROOT / JAVA).read_bytes()
        java_source = self.root / JAVA; java_source.parent.mkdir(parents=True); java_source.write_bytes(old_java)
        self.manifest = {
            self.m.SQL: {'beforeHash': None, 'targetHash': digest((ROOT / self.m.SQL).read_bytes())},
            JAVA: {'beforeHash': digest(old_java), 'targetHash': digest(new_java)},
        }
        for name, data in ((self.m.SQL, (ROOT / self.m.SQL).read_bytes()), (JAVA, new_java)):
            payload = self.op / 'payload' / name; payload.parent.mkdir(parents=True, exist_ok=True); payload.write_bytes(data)
        (self.op / 'manifest.json').write_text(json.dumps(self.manifest))
        helper = ROOT / 'scripts/qa/java/FinanceFirstSettlementIndexSchemaMaintenance.java'
        inputs = {'operationId': 'e' * 32, 'runtimeHash': digest(self.config_path.read_bytes()),
                  'appId': 'app', 'helperSha256': digest(helper.read_bytes())}
        (self.op / 'inputs.json').write_text(json.dumps(inputs))
        self.a = self.m.Activation(self.op, self.config_path, self.root, helper)
        self.store = Store(); self.host = ProtectedHost(self.root, self.a.TABLES)
        self.r = shared_dev.ReloadCoordinator(self.config, self.store, self.host)
        self.r.state_file.parent.mkdir()
        shared_dev.atomic(self.r.state_file, {'phase': 'ready', 'owner': 'previous'})
        self.a.runtime = lambda config: self.r
        self.a.inspect = lambda config: {'Id': 'app'}
        self.events = []

    def rewrite_manifest(self, manifest):
        (self.op / 'manifest.json').write_text(json.dumps(manifest))

    def fresh_activation(self):
        return self.m.Activation(self.op, self.config_path, self.root, self.a.helper)

    def setup_execution(self, failure=None):
        shared_dev.atomic(self.op / 'preflight.json', {'phase': 'passed',
            'manifestHash': digest((self.op / 'manifest.json').read_bytes()),
            'inputsHash': digest((self.op / 'inputs.json').read_bytes())})
        def stopped():
            self.assertFalse(self.store.locked)
            self.events.append('stop')
        self.a.stop_watcher = stopped
        self.a.start_watcher = lambda: self.events.append('start')
        self.a.unrelated_sources = lambda: {'staging': 'unchanged'}
        def migrate(config, mode):
            self.events.append(mode)
            self.assertTrue(self.host.marker.exists())
            self.assertIn('drain', self.host.calls)
            if mode == 'migrate':
                self.host.version = '159'; self.host.target = True
                if failure == 'ddl': raise RuntimeError('Partial DDL')
                self.host.history += '\n2\t159\tSQL\tV159__finance_first_settlement_order.sql\t8\t1'
            return {'success': True, 'oldRuntimeValidationPassed': True}
        self.a.run_java = migrate
        def compile_source():
            self.events.append('compile')
            (self.root / 'target/classes/Ready.class').write_bytes(b'partial' if failure == 'compile' else b'new')
            return subprocess.CompletedProcess([], 1 if failure == 'compile' else 0)
        self.r.compile = compile_source
        def trigger(r): self.events.append('trigger'); self.host.context = 'middle'
        self.a.trigger = trigger
        def wait(r, context, owner):
            self.events.append('watcher-ready')
            if failure == 'late': raise RuntimeError('Watcher failed')
            self.host.context = 'final'; return {'contextId': 'final', 'maintenance': False}
        self.a.wait_watcher = wait

    def test_only_exact_frozen_two_file_manifest_is_accepted(self):
        self.a.verify(self.config)
        self.assertEqual(('159', '158'), (self.a.VERSION, self.a.PREVIOUS_VERSION))
        self.assertEqual({self.m.SQL, JAVA}, set(self.a.manifest))
        variants = []
        changed = json.loads(json.dumps(self.manifest)); changed[self.m.SQL]['beforeHash'] = 'a' * 64; variants.append(changed)
        changed = json.loads(json.dumps(self.manifest)); changed[JAVA]['beforeHash'] = None; variants.append(changed)
        changed = json.loads(json.dumps(self.manifest)); changed[JAVA]['targetHash'] = 'b' * 64; variants.append(changed)
        changed = json.loads(json.dumps(self.manifest)); changed['src/main/java/Foreign.java'] = {'beforeHash': None, 'targetHash': 'c' * 64}; variants.append(changed)
        for manifest in variants:
            with self.subTest(manifest=manifest):
                self.rewrite_manifest(manifest)
                with self.assertRaises(RuntimeError): self.fresh_activation()

    def test_payload_runtime_and_source_drift_are_rejected_without_overwrite(self):
        self.setup_execution()
        (self.op / 'payload' / self.m.SQL).write_text('SELECT 1;')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertFalse(self.host.marker.exists()); self.assertEqual([], self.events)
        self.tearDown(); self.doCleanups(); self.setUp(); self.setup_execution()
        self.config_path.write_text(json.dumps(dict(self.config, marker='foreign')))
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertFalse(self.host.marker.exists()); self.assertEqual([], self.events)
        self.tearDown(); self.doCleanups(); self.setUp(); self.setup_execution()
        source = self.root / JAVA; source.write_bytes(b'foreign')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual(b'foreign', source.read_bytes()); self.assertEqual([], self.events)

    def test_snapshot_normalizes_only_exact_target_index(self):
        self.a.save_before()
        before = json.loads((self.op / self.a.SNAPSHOT).read_text())
        self.host.target = True
        self.a.check_static()
        after = json.loads((self.op / self.a.AFTER_SNAPSHOT).read_text())
        self.assertEqual(before, after)
        self.assertEqual(46, len(before['tables']))
        self.assertIn('finance_shopee_gms_sync', before['tables'])
        self.assertIn('finance_shopee_gms_daily', before['tables'])
        self.assertNotIn('idx_finance_first_settlement', json.dumps(before))
        queries = [query for query in self.host.queries if 'SHA2(' in query]
        self.assertEqual(len(self.a.TABLES) * 4, len(queries))
        self.assertTrue(all('MAX_EXECUTION_TIME(5000)' in query and 'LIMIT 16' in query for query in queries))

    def test_target_presence_phase_shape_and_raw_metadata_limit_are_enforced(self):
        self.host.target = True
        with self.assertRaises(RuntimeError): self.a.save_before()
        self.host.target = False; self.a.save_before()
        with self.assertRaises(RuntimeError): self.a.check_static()
        for mutation in (
            TARGET_INDEX[:-1],
            TARGET_INDEX[:-1] + ('amount',),
            ('shop_id',) + TARGET_INDEX[1:],
        ):
            with self.subTest(columns=mutation):
                self.host.target = True; self.host.target_columns = mutation
                with self.assertRaises(RuntimeError): self.a.check_static()
        self.host.target_columns = TARGET_INDEX; self.host.fault = 'metadata-overflow'
        with self.assertRaises(RuntimeError): self.a.check_static()

    def test_target_requires_nonunique_ascending_btree_nonprefix_metadata(self):
        self.a.save_before(); self.host.target = True
        for attribute, value in (
            ('target_non_unique', '0'), ('target_collation', 'D'),
            ('target_sub_part', '5'), ('target_type', 'HASH'),
        ):
            with self.subTest(attribute=attribute):
                setattr(self.host, attribute, value)
                with self.assertRaises(RuntimeError): self.a.check_static()
                setattr(self.host, attribute, {'target_non_unique': '1', 'target_collation': 'A',
                    'target_sub_part': 'NULL', 'target_type': 'BTREE'}[attribute])

    def test_old_target_and_other_index_visibility_are_protected(self):
        self.host.old_visible = 'NO'
        with self.assertRaises(RuntimeError): self.a.save_before()
        self.host.old_visible = 'MAYBE'
        with self.assertRaises(RuntimeError): self.a.save_before()
        self.host.old_visible = 'YES'; self.a.save_before(); self.host.target = True
        self.host.target_visible = 'NO'
        with self.assertRaises(RuntimeError): self.a.check_static()
        self.host.target_visible = 'MAYBE'
        with self.assertRaises(RuntimeError): self.a.check_static()

        self.tearDown(); self.doCleanups(); self.setUp()
        self.host.extra_index = True; self.a.save_before()
        self.host.target = True; self.host.extra_visible = 'NO'
        with self.assertRaises(RuntimeError): self.a.check_static()

    def test_old_index_other_index_column_and_sentinel_drift_are_rejected(self):
        self.a.save_before(); self.host.target = True
        self.host.old_columns = OLD_INDEX[:-1]
        with self.assertRaises(RuntimeError): self.a.check_static()
        self.host.old_columns = OLD_INDEX; self.host.extra_index = True
        with self.assertRaises(RuntimeError): self.a.check_static()
        self.host.extra_index = False; self.host.fault = 'data'
        with self.assertRaises(RuntimeError): self.a.check_static()
        self.host.fault = None
        original = self.host.sql
        self.host.sql = lambda query: original(query).replace('char(36)', 'char(40)') if 'information_schema.columns' in query else original(query)
        with self.assertRaises(RuntimeError): self.a.check_static()

    def test_invalid_metadata_cannot_reach_dynamic_sql_and_missing_table_is_rejected(self):
        original = self.host.sql
        self.host.sql = lambda query: original(query).replace('\tid\t', '\tid` secret\t') if 'information_schema.columns' in query else original(query)
        with self.assertRaises(RuntimeError): self.a.protected_snapshot()
        self.assertFalse(any('SHA2(' in query for query in self.host.queries))
        self.host.sql = lambda query: '\n'.join(original(query).splitlines()[1:]) if 'information_schema.tables' in query else original(query)
        with self.assertRaises(RuntimeError): self.a.protected_snapshot()

    def test_snapshot_failure_retains_gate_and_receipts_do_not_contain_business_values(self):
        for fault in ('timeout', 'overflow', 'malformed'):
            with self.subTest(fault=fault):
                if fault != 'timeout': self.tearDown(); self.doCleanups(); self.setUp()
                self.setup_execution(); self.host.fault = fault
                with self.assertRaises((RuntimeError, subprocess.TimeoutExpired)): self.a.deploy()
                self.assertTrue(self.host.owns_gate(self.r.state['owner']))
                self.assertEqual(['stop'], self.events)
                self.assertNotIn('synthetic-secret', ''.join(path.read_text() for path in self.op.glob('*.json')))

    def test_wrong_schema_foreign_gate_and_failed_drain_never_migrate(self):
        self.setup_execution(); self.host.version = '157'
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual([], self.events); self.assertFalse(self.host.marker.exists())
        self.tearDown(); self.doCleanups(); self.setUp(); self.setup_execution(); self.host.gate('foreign')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertTrue(self.host.owns_gate('foreign')); self.assertEqual([], self.events)
        self.tearDown(); self.doCleanups(); self.setUp(); self.setup_execution()
        self.host.drain = lambda owner: (_ for _ in ()).throw(RuntimeError('No safe point'))
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertNotIn('migrate', self.events); self.assertNotIn('trigger', self.events)

    def test_safe_point_order_partial_ddl_replay_and_compile_failure(self):
        self.setup_execution('ddl')
        with self.assertRaises(RuntimeError): self.a.deploy()
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual(1, self.events.count('migrate')); self.assertNotIn('compile', self.events)
        self.assertTrue(self.host.marker.exists()); self.assertNotIn('start', self.events)
        self.tearDown(); self.doCleanups(); self.setUp(); self.setup_execution('compile')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual(b'old', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertEqual(self.m.JAVA_BEFORE_SHA, digest((self.root / JAVA).read_bytes()))
        self.assertTrue((self.root / self.m.SQL).is_file())
        restored_config = json.loads(self.config_path.read_text())
        self.assertEqual('159', restored_config['schemaFingerprint'])
        self.assertEqual({key: value for key, value in self.config.items() if key not in {'schemaFingerprint', 'migrationFiles'}},
                         {key: value for key, value in restored_config.items() if key not in {'schemaFingerprint', 'migrationFiles'}})
        self.assertTrue(self.host.owns_gate(self.r.state['owner']))
        self.assertEqual(['stop', 'validate', 'migrate', 'compile'], self.events)
        self.assertFalse((self.op / 'verified.json').exists())

    def test_success_uses_single_migration_and_restores_no_unrelated_configuration(self):
        self.setup_execution(); result = self.a.deploy()
        self.assertEqual(('159', 'final'), (result['schemaVersion'], result['contextAfter']))
        self.assertEqual(['stop', 'validate', 'migrate', 'compile', 'trigger', 'start', 'watcher-ready'], self.events)
        updated = json.loads(self.config_path.read_text())
        self.assertEqual({key: value for key, value in self.config.items() if key not in {'schemaFingerprint', 'migrationFiles'}},
                         {key: value for key, value in updated.items() if key not in {'schemaFingerprint', 'migrationFiles'}})
        self.assertFalse(self.host.marker.exists())
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual(1, self.events.count('migrate'))

    def test_cli_inspect_is_local_read_only(self):
        result = subprocess.run([sys.executable, str(SCRIPT), '--operation', str(self.op), '--root', str(self.root),
            '--config', str(self.config_path), '--helper', str(self.a.helper)], capture_output=True, text=True, check=True)
        receipt = json.loads(result.stdout)
        self.assertEqual({'phase', 'operationId', 'sourceFiles', 'migrationVersion', 'migrationSha256'}, set(receipt))
        self.assertEqual(('159', 2), (receipt['migrationVersion'], receipt['sourceFiles']))
        self.assertFalse((self.op / 'state.json').exists())


class V159JavaHelperDrills(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        report = ROOT / 'target/surefire-reports/TEST-com.yingdao.aicommerce.finance.FinanceCostWorkspaceTest.xml'
        if not report.is_file(): raise unittest.SkipTest('Actual-GREEN Surefire classpath report is unavailable')
        properties = {item.attrib['name']: item.attrib['value'] for item in ET.parse(report).iter('property')
                      if item.attrib.get('name') in {'java.class.path', 'java.home'}}
        cls.java_home = Path(properties['java.home'])
        if not (cls.java_home / 'bin/javac').is_file(): raise unittest.SkipTest('Recorded Java 21 toolchain is unavailable')
        cls.tmp = tempfile.TemporaryDirectory(); cls.base = Path(cls.tmp.name).resolve()
        harness = cls.base / 'FirstSettlementOwnedConnectionHarness.java'
        harness.write_text(r"""
import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
public class FirstSettlementOwnedConnectionHarness {
    static int opened=0; static boolean closed=false; static String mode; static Path token,record;
    static List<String> settings=new ArrayList<>();
    static boolean firstSetObserved=false, registeredBeforeFirstSet=true;
    static Object defaultValue(Class<?> type) {
        if (type==boolean.class) return false;
        if (type==int.class) return 0;
        if (type==long.class) return 0L;
        return null;
    }
    static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
    static Connection connection() {
        return (Connection)proxy(Connection.class,(p,m,a)-> {
            if(m.getName().equals("close")) { closed=true; return null; }
            if(m.getName().equals("createStatement")) return proxy(Statement.class,(sp,sm,sa)-> {
                if(sm.getName().equals("execute")) {
                    if(!firstSetObserved) {
                        firstSetObserved=true;
                        registeredBeforeFirstSet=Files.exists(record) && Files.readString(record).equals("4242\n");
                    }
                    settings.add((String)sa[0]);
                    if(mode.equals("set1-fail") && settings.size()==1) throw new SQLException("first setting failed");
                    if(mode.equals("set2-fail") && settings.size()==2) throw new SQLException("second setting failed");
                    return true;
                }
                if(sm.getName().equals("executeQuery")) {
                    return proxy(ResultSet.class,(rp,rm,ra)-> {
                        if(rm.getName().equals("next")) return !mode.equals("missing-id");
                        if(rm.getName().equals("getLong")) return 4242L;
                        if(rm.getName().equals("close") && mode.equals("revoke-after-id")) {
                            Files.writeString(token,"null\n"); return null;
                        }
                        return defaultValue(rm.getReturnType());
                    });
                }
                return defaultValue(sm.getReturnType());
            });
            return defaultValue(m.getReturnType());
        });
    }
    public static void main(String[] args) throws Exception {
        mode=args[0]; Path directory=Path.of(args[1]); token=directory.resolve("launch.json");
        String owner="efefefefefefefefefefefefefefefef";
        Files.writeString(token,mode.equals("revoked")?"null\n":"\""+owner+"\"\n");
        DriverManager.registerDriver(new Driver() {
            public Connection connect(String u,Properties p) { if(!acceptsURL(u)) return null; opened++; return connection(); }
            public boolean acceptsURL(String u) { return u.startsWith("jdbc:owned-fake:"); }
            public DriverPropertyInfo[] getPropertyInfo(String u,Properties p) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion(){return 1;} public int getMinorVersion(){return 0;}
            public boolean jdbcCompliant(){return false;}
            public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
        });
        record=directory.resolve(owner+".connections"); boolean accepted=false;
        var source=new FinanceFirstSettlementIndexSchemaMaintenance.OwnedDataSource(
            "jdbc:owned-fake:unused","test","test",owner,record,token);
        try(var c=source.getConnection()) { accepted=true; }
        catch(SQLException expected) {}
        System.out.println(new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
            "opened",opened,"closed",closed,"accepted",accepted,"settings",settings,
            "firstSetObserved",firstSetObserved,"registeredBeforeFirstSet",registeredBeforeFirstSet,
            "recorded",Files.exists(record)?Files.readString(record):"")));
    }
}
""")
        index_harness = cls.base / 'FirstSettlementIndexVisibilityHarness.java'
        index_harness.write_text(r"""
import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
public class FirstSettlementIndexVisibilityHarness {
    static String mode;
    static Object defaultValue(Class<?> type) {
        if(type==boolean.class) return false;
        if(type==int.class) return 0;
        if(type==long.class) return 0L;
        return null;
    }
    static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
    static ResultSet identity() {
        int[] row={-1};
        return (ResultSet)proxy(ResultSet.class,(p,m,a)-> {
            if(m.getName().equals("next")) return ++row[0]==0;
            if(m.getName().equals("getLong")) return 4242L;
            return defaultValue(m.getReturnType());
        });
    }
    static List<String[]> metadata() {
        var rows=new ArrayList<String[]>();
        var old=List.of("organization_id","shop_id","kind","order_id","source","status","record_date",
            "platform","category","currency","amount","revenue","impressions","clicks","conversions");
        var target=List.of("organization_id","shop_id","kind","order_id","source","status","record_date",
            "id","platform","category","currency");
        String oldVisible=mode.equals("old-invisible")?"NO":"YES";
        for(int i=0;i<old.size();i++) rows.add(new String[]{"idx_finance_settled_order","1",Integer.toString(i+1),old.get(i),"A","NULL","BTREE",oldVisible});
        if(!mode.equals("visible-before") && !mode.equals("old-invisible")) {
            String targetVisible=mode.equals("target-invisible")?"NO":"YES";
            for(int i=0;i<target.size();i++) rows.add(new String[]{"idx_finance_first_settlement","1",Integer.toString(i+1),target.get(i),"A","NULL","BTREE",targetVisible});
        }
        return rows;
    }
    static ResultSet metadataRows() {
        var rows=metadata(); int[] row={-1};
        return (ResultSet)proxy(ResultSet.class,(p,m,a)-> {
            if(m.getName().equals("next")) return ++row[0]<rows.size();
            if(m.getName().equals("close")) return null;
            int column=(Integer)a[0]-1; String value=rows.get(row[0])[column];
            if(m.getName().equals("getInt")) return Integer.parseInt(value);
            if(m.getName().equals("getObject")) return value.equals("NULL")?null:value;
            if(m.getName().equals("getString")) return value.equals("NULL")?null:value;
            return defaultValue(m.getReturnType());
        });
    }
    static Connection connection() {
        return (Connection)proxy(Connection.class,(p,m,a)-> {
            if(m.getName().equals("createStatement")) return proxy(Statement.class,(sp,sm,sa)-> {
                if(sm.getName().equals("execute")) return true;
                if(sm.getName().equals("executeQuery"))
                    return ((String)sa[0]).contains("CONNECTION_ID")?identity():metadataRows();
                return defaultValue(sm.getReturnType());
            });
            return defaultValue(m.getReturnType());
        });
    }
    public static void main(String[] args) throws Exception {
        mode=args[0]; Path directory=Path.of(args[1]); String owner="abababababababababababababababab";
        Path token=directory.resolve("launch.json"), record=directory.resolve(owner+".connections");
        Files.writeString(token,"\""+owner+"\"\n");
        DriverManager.registerDriver(new Driver() {
            public Connection connect(String u,Properties p) { return acceptsURL(u)?connection():null; }
            public boolean acceptsURL(String u) { return u.startsWith("jdbc:index-fake:"); }
            public DriverPropertyInfo[] getPropertyInfo(String u,Properties p) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion(){return 1;} public int getMinorVersion(){return 0;}
            public boolean jdbcCompliant(){return false;}
            public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
        });
        var source=new FinanceFirstSettlementIndexSchemaMaintenance.OwnedDataSource(
            "jdbc:index-fake:unused","test","test",owner,record,token);
        boolean accepted=true;
        try { FinanceFirstSettlementIndexSchemaMaintenance.verifyIndexes(source,!mode.equals("visible-before")&&!mode.equals("old-invisible")); }
        catch(SQLException expected) { accepted=false; }
        System.out.println(new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("accepted",accepted)));
    }
}
""")
        helper = ROOT / 'scripts/qa/java/FinanceFirstSettlementIndexSchemaMaintenance.java'
        result = subprocess.run([str(cls.java_home / 'bin/javac'), '-cp', properties['java.class.path'],
                    '-d', str(cls.base), str(helper), str(harness), str(index_harness)], capture_output=True, text=True)
        if result.returncode: raise AssertionError('Independent V159 helper compilation failed: ' + result.stderr)
        cls.cp = str(cls.base) + os.pathsep + properties['java.class.path']

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def harness(self, mode):
        directory = self.base / mode; directory.mkdir()
        result = subprocess.run([str(self.java_home / 'bin/java'), '-cp', self.cp,
            'FirstSettlementOwnedConnectionHarness', mode, str(directory)], capture_output=True, text=True, check=True)
        return json.loads(result.stdout)

    def test_connections_receive_bounded_lock_timeouts_and_respect_revocation(self):
        settings = ['SET SESSION lock_wait_timeout=5', 'SET SESSION innodb_lock_wait_timeout=5']
        self.assertEqual({'opened': 1, 'closed': True, 'accepted': True, 'settings': settings, 'recorded': '4242\n',
                          'firstSetObserved': True, 'registeredBeforeFirstSet': True},
                         self.harness('valid'))
        self.assertEqual({'opened': 0, 'closed': False, 'accepted': False, 'settings': [], 'recorded': '',
                          'firstSetObserved': False, 'registeredBeforeFirstSet': True},
                         self.harness('revoked'))
        self.assertEqual({'opened': 1, 'closed': True, 'accepted': False, 'settings': [], 'recorded': '4242\n',
                          'firstSetObserved': False, 'registeredBeforeFirstSet': True},
                         self.harness('revoke-after-id'))
        self.assertEqual({'opened': 1, 'closed': True, 'accepted': False, 'settings': [], 'recorded': '',
                          'firstSetObserved': False, 'registeredBeforeFirstSet': True},
                         self.harness('missing-id'))

    def test_setting_failures_close_a_durably_registered_connection(self):
        first = self.harness('set1-fail')
        self.assertEqual((False, True, True, '4242\n'),
                         (first['accepted'], first['closed'], first['registeredBeforeFirstSet'], first['recorded']))
        self.assertEqual(['SET SESSION lock_wait_timeout=5'], first['settings'])
        second = self.harness('set2-fail')
        self.assertEqual((False, True, True, '4242\n'),
                         (second['accepted'], second['closed'], second['registeredBeforeFirstSet'], second['recorded']))
        self.assertEqual(['SET SESSION lock_wait_timeout=5', 'SET SESSION innodb_lock_wait_timeout=5'], second['settings'])

    def test_java_index_guard_requires_visible_old_and_target_shapes(self):
        for mode, accepted in (('visible-before', True), ('visible-after', True),
                               ('old-invisible', False), ('target-invisible', False)):
            with self.subTest(mode=mode):
                directory = self.base / ('index-' + mode); directory.mkdir()
                result = subprocess.run([str(self.java_home / 'bin/java'), '-cp', self.cp,
                    'FirstSettlementIndexVisibilityHarness', mode, str(directory)],
                    capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual({'accepted': accepted}, json.loads(result.stdout))

    def test_fixed_sql_hash_and_failure_output_are_sanitized(self):
        directory = self.base / 'bad-sql'; directory.mkdir()
        (directory / 'V159__finance_first_settlement_order.sql').write_text('SELECT 1;')
        token = self.base / 'launch.json'; token.write_text('null\n')
        result = subprocess.run([str(self.java_home / 'bin/java'), '-cp', self.cp,
            'FinanceFirstSettlementIndexSchemaMaintenance', 'migrate', str(directory), str(token)],
            input='{"password":"synthetic-secret-never-echo"}', capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertEqual({'success': False, 'failureType': 'IllegalArgumentException'}, json.loads(result.stdout))
        self.assertNotIn('synthetic-secret-never-echo', result.stdout + result.stderr)


if __name__ == '__main__': unittest.main()
