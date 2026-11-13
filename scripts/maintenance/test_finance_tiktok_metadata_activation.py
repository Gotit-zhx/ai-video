"""Local V156 activation guard drills; no Docker, SSH or real database access."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SCRIPT = HERE / 'finance_tiktok_metadata_activation.py'
sys.path.insert(0, str(ROOT / 'scripts'))
import shared_dev
from scripts.maintenance.test_finance_query_projection_activation import Host, Store


def digest(data): return hashlib.sha256(data).hexdigest()


class GuardHost:
    def __init__(self):
        self.changed = False
        self.schema_changed = False
        self.overflow = False
        self.timeout = False
        self.queries = []
        self.limit = 0

    def sql(self, query):
        self.queries.append(query)
        if self.timeout and 'information_schema.columns' in query:
            raise subprocess.TimeoutExpired('protected-metadata', 15)
        if 'information_schema.columns' in query:
            suffix = '\tEXTRA' if self.schema_changed else ''
            return '\n'.join(name + '\t1\tid\tchar(36)\tNO' + suffix for name in (
                'finance_records', 'finance_ad_accounts', 'finance_ad_stores', 'finance_ad_streams',
                'finance_ad_coverage', 'finance_ad_bc_sync', 'finance_ad_bc_costs',
                'finance_ad_spend_imports', 'finance_ad_spend_allocation_sets',
                'finance_ad_spend_allocations', 'finance_connections',
                'finance_authorization_sessions', 'finance_sync_states'))
        if 'information_schema.statistics' in query:
            primary = [name + '\t1\tid\tPRIMARY' for name in (
                'finance_records', 'finance_ad_accounts', 'finance_ad_stores', 'finance_ad_streams',
                'finance_ad_coverage', 'finance_ad_bc_sync', 'finance_ad_bc_costs',
                'finance_ad_spend_imports', 'finance_ad_spend_allocation_sets',
                'finance_ad_spend_allocations', 'finance_connections',
                'finance_authorization_sessions', 'finance_sync_states')]
            page = ['finance_records\t' + str(index) + '\t' + column + '\tidx_finance_page'
                    for index, column in enumerate(('organization_id','kind','record_date','id','shop_id','platform',
                                                    'currency','source','status','category','order_id'), 1)]
            return '\n'.join(primary + page)
        if 'DISTINCT HEX(organization_id)' in query:
            return '6F7267\n6F726732'
        table = next(name for name in (
            'finance_records', 'finance_ad_accounts', 'finance_ad_stores', 'finance_ad_streams',
            'finance_ad_coverage', 'finance_ad_bc_sync', 'finance_ad_bc_costs',
            'finance_ad_spend_imports', 'finance_ad_spend_allocation_sets',
            'finance_ad_spend_allocations', 'finance_connections',
            'finance_authorization_sessions', 'finance_sync_states') if ('FROM `' + name + '`') in query)
        if self.overflow and table == 'finance_ad_accounts':
            return '\n'.join('a' * 64 for _ in range(self.limit + 1))
        value = ('b' if self.changed and table == 'finance_ad_spend_imports' else 'a') * 64
        return value + '\n' + value


class DeploymentHost(Host, GuardHost):
    def __init__(self, root):
        Host.__init__(self, root); GuardHost.__init__(self)
        self.version = '155'; self.history = '1\t155\tSQL\tV155__finance_query_projections.sql\t7\t1'

    def sql(self, query):
        if 'installed_rank' in query: return self.history
        return GuardHost.sql(self, query)


class V156ActivationDrills(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), 'V156 activation implementation is missing')
        spec = importlib.util.spec_from_file_location('finance_tiktok_metadata_activation', SCRIPT)
        self.m = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.m)
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve(); self.root = self.base / 'source'; self.root.mkdir()
        (self.root / 'target/classes').mkdir(parents=True)
        (self.root / 'target/classes/Ready.class').write_bytes(b'old')
        self.op = self.base / 'operation'; self.op.mkdir(mode=0o700)
        self.config_path = self.base / 'runtime.json'
        config = {'sourceRoot': str(self.root), 'appContainer': 'test-app', 'uid': os.getuid(), 'gid': os.getgid(),
                  'javaHome': '/jdk', 'schemaFingerprint': '155', 'migrationFiles': {}, 'reloadTimeout': .02}
        self.config_path.write_text(json.dumps(config))
        sql = ROOT / self.m.SQL
        payload = self.op / 'payload' / self.m.SQL; payload.parent.mkdir(parents=True); payload.write_bytes(sql.read_bytes())
        manifest = {self.m.SQL: {'beforeHash': None, 'targetHash': digest(sql.read_bytes())}}
        (self.op / 'manifest.json').write_text(json.dumps(manifest))
        helper = ROOT / 'scripts/qa/java' / self.m.HELPER
        inputs = {'operationId': 'c' * 32, 'runtimeHash': digest(self.config_path.read_bytes()),
                  'appId': 'app', 'helperSha256': digest(helper.read_bytes())}
        (self.op / 'inputs.json').write_text(json.dumps(inputs))
        self.activation = self.m.Activation(self.op, self.config_path, self.root, helper)
        self.store = Store(); self.host = DeploymentHost(self.root)
        self.host.limit = self.activation.ROW_LIMIT
        self.coordinator = shared_dev.ReloadCoordinator(config, self.store, self.host)
        self.coordinator.state_file.parent.mkdir()
        shared_dev.atomic(self.coordinator.state_file, {'phase': 'ready', 'owner': 'previous'})
        self.activation.runtime = lambda config: self.coordinator
        self.activation.inspect = lambda config: {'Id': 'app'}

    def test_fixed_v156_properties_and_manifest_acceptance(self):
        self.assertEqual('src/main/resources/db/migration/V156__finance_ad_campaign_metadata.sql', self.m.SQL)
        self.assertEqual('0a8268cd9b78cdbb24c2dee18b1ecbdf47a3f1965d4d75e06c5d098115b99abd', self.m.SQL_SHA)
        self.assertEqual('156', self.activation.VERSION)
        self.assertEqual('155', self.activation.PREVIOUS_VERSION)

    def test_guard_receipt_contains_only_counts_and_digests_and_matches_afterward(self):
        self.activation.save_before()
        receipt = json.loads((self.op / 'protected-ad-data-before.json').read_text())
        self.assertEqual({'version', 'metadataSha256', 'tables'}, set(receipt))
        self.assertEqual({'rows', 'sha256'}, set(receipt['tables']['finance_ad_accounts']))
        self.assertNotIn('organization_id', json.dumps(receipt))
        self.activation.check_static()
        record_queries = [query for query in self.host.queries if 'FROM `finance_records`' in query]
        self.assertEqual(8, len(record_queries))
        for query in record_queries:
            self.assertIn('FORCE INDEX (`idx_finance_page`)', query)
            self.assertRegex(query, "organization_id=CONVERT\\(0x[0-9A-F]+ USING utf8mb4\\)")
            self.assertRegex(query, "kind='AD(?:_GMV)?'")
            self.assertIn('ORDER BY `id`', query)
        self.assertFalse(any('SELECT DISTINCT organization_id FROM' in query for query in record_queries))

    def test_guard_retains_gate_on_data_schema_or_row_limit_drift(self):
        self.activation.save_before()
        for drift in ('changed', 'schema_changed', 'overflow'):
            with self.subTest(drift=drift):
                setattr(self.host, drift, True)
                with self.assertRaises(RuntimeError): self.activation.check_static()
                setattr(self.host, drift, False)

    def deploy_guard_failure(self, fault):
        shared_dev.atomic(self.op / 'preflight.json', {'phase': 'passed',
            'manifestHash': digest((self.op / 'manifest.json').read_bytes()),
            'inputsHash': digest((self.op / 'inputs.json').read_bytes())})
        events = []
        self.activation.stop_watcher = lambda: events.append('stop')
        self.activation.start_watcher = lambda: events.append('start')
        self.activation.unrelated_sources = lambda: {'staging': 'unchanged'}
        self.activation.run_java = lambda *args: self.fail('Guard rejection must precede helper validation')
        setattr(self.host, fault, True)
        with self.assertRaises((RuntimeError, subprocess.TimeoutExpired)):
            self.activation.deploy()
        self.assertTrue(self.host.owns_gate(self.coordinator.state['owner']))
        self.assertEqual(['stop'], events)
        state = json.loads((self.op / 'state.json').read_text())
        self.assertFalse(state['migrationAttempted'])
        self.assertTrue((self.op / 'classes-before/Ready.class').is_file())

    def test_before_snapshot_overflow_retains_gate_and_stopped_watcher(self):
        self.deploy_guard_failure('overflow')

    def test_before_snapshot_metadata_rejection_retains_gate_and_stopped_watcher(self):
        self.deploy_guard_failure('schema_changed')

    def test_before_snapshot_timeout_retains_gate_and_stopped_watcher(self):
        self.deploy_guard_failure('timeout')


@unittest.skipUnless(os.environ.get('FINANCE_ACTIVATION_JAVA_HOME') and os.environ.get('FINANCE_ACTIVATION_CLASSPATH'),
                     'Set explicit local Java 21 and cached dependency classpath for helper drills')
class V156JavaHelperDrills(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(); cls.base = Path(cls.tmp.name).resolve()
        cls.java = Path(os.environ['FINANCE_ACTIVATION_JAVA_HOME']) / 'bin/java'
        cls.cp = os.environ['FINANCE_ACTIVATION_CLASSPATH']
        harness = cls.base / 'TikTokOwnedConnectionHarness.java'
        harness.write_text(r"""
import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
public class TikTokOwnedConnectionHarness {
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
        var source=new FinanceTikTokMetadataSchemaMaintenance.OwnedDataSource("jdbc:owned-fake:unused","test","test",owner,record,token);
        try(var c=source.getConnection()) { accepted=true; }
        catch(SQLException expected) {}
        System.out.println(new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
            "opened",opened,"closed",closed,"accepted",accepted,"recorded",Files.exists(record)?Files.readString(record):"")));
    }
}
""")
        result = subprocess.run([str(Path(os.environ['FINANCE_ACTIVATION_JAVA_HOME']) / 'bin/javac'), '-cp', cls.cp,
                    '-d', str(cls.base), str(ROOT / 'scripts/qa/java/FinanceTikTokMetadataSchemaMaintenance.java'), str(harness)], capture_output=True, text=True)
        if result.returncode: raise AssertionError('Independent V156 helper compilation failed: ' + result.stderr)
        cls.cp = str(cls.base) + os.pathsep + cls.cp

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def harness(self, mode):
        directory = self.base / mode; directory.mkdir()
        result = subprocess.run([str(self.java), '-cp', self.cp, 'TikTokOwnedConnectionHarness', mode, str(directory)], capture_output=True, text=True, check=True)
        return json.loads(result.stdout)

    def test_connections_are_registered_and_revocation_closes_or_prevents_them(self):
        self.assertEqual({'opened': 1, 'closed': True, 'accepted': True, 'recorded': '4242\n'}, self.harness('valid'))
        self.assertEqual({'opened': 0, 'closed': False, 'accepted': False, 'recorded': ''}, self.harness('revoked'))
        self.assertEqual({'opened': 1, 'closed': True, 'accepted': False, 'recorded': '4242\n'}, self.harness('mid-revoke'))
        self.assertEqual({'opened': 1, 'closed': True, 'accepted': False, 'recorded': ''}, self.harness('missing-id'))

    def test_fixed_sql_and_redacted_failure(self):
        directory = self.base / 'current-sql'; migration = directory / 'migration'; migration.mkdir(parents=True)
        (migration / 'V156__finance_ad_campaign_metadata.sql').write_bytes((ROOT / 'src/main/resources/db/migration/V156__finance_ad_campaign_metadata.sql').read_bytes())
        token = directory / 'launch.json'; token.write_text('null\n'); owner = 'cd' * 16
        accepted = subprocess.run([str(self.java), '-Dheyi.financeProjectionSchemaOwner=' + owner, '-cp', self.cp,
             'FinanceTikTokMetadataSchemaMaintenance', 'validate', str(migration), str(token)],
             input=json.dumps({'url': 'jdbc:mysql://127.0.0.1:1/never-connected', 'user': 'synthetic', 'password': 'synthetic', 'owner': owner}),
             capture_output=True, text=True)
        self.assertEqual(1, accepted.returncode)
        self.assertEqual({'success': False, 'failureType': 'SQLException'}, json.loads(accepted.stdout))
        bad = self.base / 'bad-sql'; bad.mkdir(); (bad / 'V156__finance_ad_campaign_metadata.sql').write_text('SELECT 1;')
        rejected = subprocess.run([str(self.java), '-cp', self.cp, 'FinanceTikTokMetadataSchemaMaintenance', 'migrate', str(bad), str(token)],
            input='{"password":"synthetic-secret-never-echo"}', capture_output=True, text=True)
        self.assertEqual(1, rejected.returncode)
        self.assertEqual({'success': False, 'failureType': 'IllegalArgumentException'}, json.loads(rejected.stdout))
        self.assertNotIn('synthetic-secret-never-echo', rejected.stdout + rejected.stderr)


if __name__ == '__main__': unittest.main()
