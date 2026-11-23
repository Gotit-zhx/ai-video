"""Fixed V157 local guard drills; no Docker, SSH or database connection."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SCRIPT = HERE / 'finance_projection_baseline_activation.py'
sys.path.insert(0, str(ROOT / 'scripts'))
import shared_dev
from scripts.maintenance.test_finance_query_projection_activation import Host, Store


def digest(data): return hashlib.sha256(data).hexdigest()


class ProtectedHost(Host):
    def __init__(self, root, tables):
        super().__init__(root)
        self.version = '156'
        self.history = '1\t156\tSQL\tV156__finance_ad_campaign_metadata.sql\t7\t1'
        self.tables = tables
        self.queries = []
        self.fault = None

    def sql(self, query):
        self.queries.append(query)
        if 'installed_rank' in query: return self.history
        if self.fault == 'timeout': raise subprocess.TimeoutExpired('protected-sentinel', 15)
        if 'information_schema.columns' in query:
            return '\n'.join(name + '\t1\tid\tchar(36)\tNO\tNULL\t\tutf8mb4_0900_ai_ci' for name in self.tables)
        if 'information_schema.statistics' in query:
            return '\n'.join(name + '\tPRIMARY\t0\t1\tid\tA\tNULL\tBTREE' for name in self.tables)
        if 'information_schema.tables' in query:
            return '\n'.join(name + '\tInnoDB\tutf8mb4_0900_ai_ci' for name in self.tables)
        if self.fault == 'overflow': return '\n'.join('a' * 64 for _ in range(17))
        if self.fault == 'malformed': return 'synthetic-secret-must-not-reach-receipt'
        return ('b' if self.fault == 'data' else 'a') * 64


class BaselineActivationDrills(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), 'Fixed V157 activation implementation is missing')
        spec = importlib.util.spec_from_file_location('finance_projection_baseline_activation', SCRIPT)
        self.m = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.m)
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve(); self.root = self.base / 'source'; self.root.mkdir()
        self.op = self.base / 'operation'; self.op.mkdir(mode=0o700)
        (self.root / 'target/classes').mkdir(parents=True)
        (self.root / 'target/classes/Ready.class').write_bytes(b'old')
        self.config = {'sourceRoot': str(self.root), 'appContainer': 'test-app', 'uid': os.getuid(), 'gid': os.getgid(),
                       'javaHome': '/jdk', 'schemaFingerprint': '156', 'migrationFiles': {}, 'reloadTimeout': .02}
        self.config_path = self.base / 'runtime.json'; self.config_path.write_text(json.dumps(self.config))
        self.manifest = {}
        for name, data in [('src/main/java/New.java', b'new'), (self.m.SQL, (ROOT / self.m.SQL).read_bytes())]:
            payload = self.op / 'payload' / name; payload.parent.mkdir(parents=True, exist_ok=True); payload.write_bytes(data)
            self.manifest[name] = {'beforeHash': None, 'targetHash': digest(data)}
        (self.op / 'manifest.json').write_text(json.dumps(self.manifest))
        helper = ROOT / 'scripts/qa/java/FinanceProjectionBaselineSchemaMaintenance.java'
        inputs = {'operationId': 'd' * 32, 'runtimeHash': digest(self.config_path.read_bytes()),
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
                self.host.version = '157'
                if failure == 'ddl': raise RuntimeError('Partial DDL')
                self.host.history += '\n2\t157\tSQL\tV157__finance_projection_baseline_revisions.sql\t8\t1'
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

    def test_only_frozen_unapplied_v157_manifest_is_accepted(self):
        self.a.verify(self.config)
        self.assertEqual('157', self.a.VERSION)
        self.assertEqual('156', self.a.PREVIOUS_VERSION)
        for entry in ({'beforeHash': 'a' * 64, 'targetHash': self.m.SQL_SHA},
                      {'beforeHash': None, 'targetHash': 'b' * 64}):
            self.manifest[self.m.SQL] = entry
            (self.op / 'manifest.json').write_text(json.dumps(self.manifest))
            with self.assertRaises(RuntimeError): self.m.Activation(self.op, self.config_path, self.root, self.a.helper)

    def test_payload_drift_is_rejected_before_gate_or_migration(self):
        self.setup_execution()
        (self.op / 'payload' / self.m.SQL).write_text('SELECT 1;')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertFalse(self.host.marker.exists()); self.assertEqual([], self.events)

    def test_configuration_drift_is_rejected_before_gate_or_migration(self):
        self.setup_execution()
        self.config_path.write_text(json.dumps(dict(self.config, marker='foreign')))
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertFalse(self.host.marker.exists()); self.assertEqual([], self.events)

    def test_source_drift_is_preserved_and_rejected(self):
        self.setup_execution()
        source = self.root / 'src/main/java/New.java'; source.parent.mkdir(parents=True); source.write_bytes(b'foreign')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual(b'foreign', source.read_bytes()); self.assertEqual([], self.events)

    def test_wrong_schema_is_rejected_before_watcher_stop(self):
        self.setup_execution(); self.host.version = '155'
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual([], self.events); self.assertFalse(self.host.marker.exists())

    def test_foreign_maintenance_is_never_taken_over(self):
        self.setup_execution(); self.host.gate('foreign')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertTrue(self.host.owns_gate('foreign')); self.assertEqual([], self.events)

    def test_failed_drain_never_migrates_or_restarts(self):
        self.setup_execution()
        def drain(owner): raise RuntimeError('No safe point')
        self.host.drain = drain
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertNotIn('migrate', self.events); self.assertNotIn('trigger', self.events)

    def test_protected_receipt_is_bounded_and_contains_no_business_payload(self):
        self.a.save_before(); self.a.check_static()
        before = json.loads((self.op / self.a.SNAPSHOT).read_text())
        self.assertEqual(before, json.loads((self.op / self.a.AFTER_SNAPSHOT).read_text()))
        self.assertEqual('bounded-primary-key-sentinels', before['scope'])
        for table in before['tables'].values():
            self.assertEqual({'first', 'last'}, set(table))
            self.assertEqual({'sampledRows', 'sha256'}, set(table['first']))
        queries = [q for q in self.host.queries if 'SHA2(' in q]
        self.assertEqual(len(self.a.TABLES) * 4, len(queries))
        for q in queries:
            self.assertIn('FORCE INDEX (`PRIMARY`)', q); self.assertIn('LIMIT 16', q)
            self.assertIn('MAX_EXECUTION_TIME(5000)', q); self.assertNotIn('COUNT(', q)
        self.assertNotIn('char(36)', json.dumps(before))

    def test_changed_sentinel_or_schema_prevents_success(self):
        self.a.save_before()
        self.host.fault = 'data'
        with self.assertRaises(RuntimeError): self.a.check_static()
        self.host.fault = None
        original = self.host.sql
        def schema_drift(query):
            result = original(query)
            return result.replace('char(36)', 'char(40)') if 'information_schema.columns' in query else result
        self.host.sql = schema_drift
        with self.assertRaises(RuntimeError): self.a.check_static()

    def test_invalid_metadata_identifier_cannot_reach_dynamic_sql(self):
        original = self.host.sql
        def invalid(query):
            result = original(query)
            return result.replace('\tid\t', '\tid` secret\t') if 'information_schema.columns' in query else result
        self.host.sql = invalid
        with self.assertRaises(RuntimeError): self.a.protected_snapshot()
        self.assertFalse(any('SHA2(' in q for q in self.host.queries))

    def test_missing_table_rejects_protection_without_silent_omission(self):
        original = self.host.sql
        self.host.sql = lambda query: '\n'.join(original(query).splitlines()[1:]) if 'information_schema.columns' in query else original(query)
        with self.assertRaises(RuntimeError): self.a.protected_snapshot()

    def test_snapshot_timeout_overflow_and_malformed_hash_retain_owned_gate(self):
        for fault in ('timeout', 'overflow', 'malformed'):
            with self.subTest(fault=fault):
                # Each fault needs a distinct fresh operation, as replay is refused.
                if fault != 'timeout': self.tearDown(); self.doCleanups(); self.setUp()
                self.setup_execution(); self.host.fault = fault
                with self.assertRaises((RuntimeError, subprocess.TimeoutExpired)): self.a.deploy()
                self.assertTrue(self.host.owns_gate(self.r.state['owner']))
                self.assertEqual(['stop'], self.events)
                self.assertTrue((self.op / 'classes-before/Ready.class').is_file())
                self.assertNotIn('synthetic-secret', ''.join(p.read_text() for p in self.op.glob('*.json')))

    def test_partial_ddl_retains_owned_gate_and_refuses_replay(self):
        self.setup_execution('ddl')
        with self.assertRaises(RuntimeError): self.a.deploy()
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual(1, self.events.count('migrate')); self.assertNotIn('compile', self.events)
        self.assertTrue(self.host.marker.exists()); self.assertNotIn('start', self.events)

    def test_compile_failure_restores_previous_source_classes_without_restart(self):
        self.setup_execution('compile')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual(b'old', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertFalse((self.root / 'src/main/java/New.java').exists())
        self.assertTrue((self.root / self.m.SQL).is_file())
        self.assertEqual('157', json.loads(self.config_path.read_text())['schemaFingerprint'])
        self.assertTrue(self.host.owns_gate(self.r.state['owner']))
        self.assertNotIn('trigger', self.events); self.assertNotIn('start', self.events)
        self.assertFalse((self.op / 'verified.json').exists())

    def test_late_failure_reestablishes_gate_and_keeps_independent_backup(self):
        self.setup_execution('late')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertTrue(self.host.marker.exists())
        self.assertEqual(b'old', (self.op / 'classes-before/Ready.class').read_bytes())
        self.assertFalse((self.op / 'verified.json').exists())

    def test_success_requires_drain_single_migration_and_new_healthy_context(self):
        self.setup_execution(); result = self.a.deploy()
        self.assertEqual('157', result['schemaVersion']); self.assertEqual('final', result['contextAfter'])
        self.assertEqual(['stop', 'validate', 'migrate', 'compile', 'trigger', 'start', 'watcher-ready'], self.events)
        self.assertFalse(self.host.marker.exists())
        self.assertEqual(401, self.host.request('/api/v1/finance/shops')[0])
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual(1, self.events.count('migrate'))

    def test_cli_inspect_is_local_read_only(self):
        result = subprocess.run([sys.executable, str(SCRIPT), '--operation', str(self.op), '--root', str(self.root),
            '--config', str(self.config_path), '--helper', str(self.a.helper)], capture_output=True, text=True, check=True)
        receipt = json.loads(result.stdout)
        self.assertEqual('157', receipt['migrationVersion']); self.assertFalse((self.op / 'state.json').exists())


@unittest.skipUnless(os.environ.get('FINANCE_ACTIVATION_JAVA_HOME') and os.environ.get('FINANCE_ACTIVATION_CLASSPATH'),
                     'Set explicit local Java 21 and cached dependency classpath for helper drills')
class V157JavaHelperDrills(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(); cls.base = Path(cls.tmp.name).resolve()
        cls.java = Path(os.environ['FINANCE_ACTIVATION_JAVA_HOME']) / 'bin/java'
        cls.cp = os.environ['FINANCE_ACTIVATION_CLASSPATH']
        harness = cls.base / 'BaselineOwnedConnectionHarness.java'
        harness.write_text(r"""
import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
public class BaselineOwnedConnectionHarness {
    static int opened=0; static boolean closed=false; static String mode; static Path token;
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
                if(sm.getName().equals("executeQuery")) {
                    if(mode.equals("mid-revoke")) Files.writeString(token,"null\n");
                    return proxy(ResultSet.class,(rp,rm,ra)-> {
                        if(rm.getName().equals("next")) return !mode.equals("missing-id");
                        if(rm.getName().equals("getLong")) return 4242L;
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
        Path record=directory.resolve(owner+".connections"); boolean accepted=false;
        var source=new FinanceProjectionBaselineSchemaMaintenance.OwnedDataSource("jdbc:owned-fake:unused","test","test",owner,record,token);
        try(var c=source.getConnection()) { accepted=true; }
        catch(SQLException expected) {}
        System.out.println(new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
            "opened",opened,"closed",closed,"accepted",accepted,"recorded",Files.exists(record)?Files.readString(record):"")));
    }
}
""")
        result = subprocess.run([str(Path(os.environ['FINANCE_ACTIVATION_JAVA_HOME']) / 'bin/javac'), '-cp', cls.cp,
                    '-d', str(cls.base), str(ROOT / 'scripts/qa/java/FinanceProjectionBaselineSchemaMaintenance.java'), str(harness)], capture_output=True, text=True)
        if result.returncode: raise AssertionError('Independent V157 helper compilation failed: ' + result.stderr)
        cls.cp = str(cls.base) + os.pathsep + cls.cp

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def harness(self, mode):
        directory = self.base / mode; directory.mkdir()
        result = subprocess.run([str(self.java), '-cp', self.cp, 'BaselineOwnedConnectionHarness', mode, str(directory)], capture_output=True, text=True, check=True)
        return json.loads(result.stdout)

    def test_connections_are_registered_and_revocation_closes_or_prevents_them(self):
        self.assertEqual({'opened': 1, 'closed': True, 'accepted': True, 'recorded': '4242\n'}, self.harness('valid'))
        self.assertEqual({'opened': 0, 'closed': False, 'accepted': False, 'recorded': ''}, self.harness('revoked'))
        self.assertEqual({'opened': 1, 'closed': True, 'accepted': False, 'recorded': '4242\n'}, self.harness('mid-revoke'))
        self.assertEqual({'opened': 1, 'closed': True, 'accepted': False, 'recorded': ''}, self.harness('missing-id'))

    def test_fixed_sql_and_redacted_failure(self):
        directory = self.base / 'current-sql'; migration = directory / 'migration'; migration.mkdir(parents=True)
        (migration / 'V157__finance_projection_baseline_revisions.sql').write_bytes((ROOT / 'src/main/resources/db/migration/V157__finance_projection_baseline_revisions.sql').read_bytes())
        token = directory / 'launch.json'; token.write_text('null\n'); owner = 'cd' * 16
        accepted = subprocess.run([str(self.java), '-Dheyi.financeProjectionSchemaOwner=' + owner, '-cp', self.cp,
             'FinanceProjectionBaselineSchemaMaintenance', 'validate', str(migration), str(token)],
             input=json.dumps({'url': 'jdbc:mysql://127.0.0.1:1/never-connected', 'user': 'synthetic', 'password': 'synthetic', 'owner': owner}),
             capture_output=True, text=True)
        self.assertEqual(1, accepted.returncode)
        self.assertEqual({'success': False, 'failureType': 'SQLException'}, json.loads(accepted.stdout))
        bad = self.base / 'bad-sql'; bad.mkdir(); (bad / 'V157__finance_projection_baseline_revisions.sql').write_text('SELECT 1;')
        rejected = subprocess.run([str(self.java), '-cp', self.cp, 'FinanceProjectionBaselineSchemaMaintenance', 'migrate', str(bad), str(token)],
            input='{"password":"synthetic-secret-never-echo"}', capture_output=True, text=True)
        self.assertEqual(1, rejected.returncode)
        self.assertEqual({'success': False, 'failureType': 'IllegalArgumentException'}, json.loads(rejected.stdout))
        self.assertNotIn('synthetic-secret-never-echo', rejected.stdout + rejected.stderr)



if __name__ == '__main__': unittest.main()
