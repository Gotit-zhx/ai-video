"""Local failure drills; no Docker, SSH or real database access."""
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SCRIPT = HERE / 'finance_query_projection_activation.py'
sys.path.insert(0, str(ROOT / 'scripts'))
import shared_dev


def digest(data): return hashlib.sha256(data).hexdigest()


class Store:
    def __init__(self): self.locked = False; self.depth = 0; self.entries = 0
    @contextlib.contextmanager
    def exclusive(self):
        if self.locked: raise RuntimeError('Lock reentry')
        self.locked = True; self.depth += 1; self.entries += 1
        try: yield
        finally: self.depth -= 1; self.locked = False
    def incomplete(self): return []


class Host:
    def __init__(self, root):
        self.marker = root / 'gate.json'; self.context = 'before'; self.version = '154'
        self.history = '1\t154\tSQL\tV154.sql\t2\t1'; self.calls = []
    def schema(self): return self.version
    def owns_gate(self, owner):
        if not self.marker.exists(): return False
        if json.loads(self.marker.read_text())['owner'] != owner: raise RuntimeError('foreign')
        return True
    def gate(self, owner):
        if self.marker.exists() and not self.owns_gate(owner): raise RuntimeError('foreign')
        self.marker.write_text(json.dumps({'owner': owner})); self.calls.append('gate')
    def ungate(self, owner):
        if self.owns_gate(owner): self.marker.unlink()
        self.calls.append('ungate')
    def drain(self, owner): self.calls.append('drain')
    def sql(self, query):
        if 'installed_rank' in query: return self.history
        return '0'
    def request(self, path, authenticated=False):
        if path == '/actuator/health': return 200, b'{"status":"UP"}'
        if path == '/api/v1/finance/shops': return (503 if self.marker.exists() else 401), b''
        return 200, json.dumps({'protocol': 1, 'contextId': self.context, 'maintenance': self.marker.exists()}).encode()


class ActivationDrills(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), 'V155 activation implementation is missing')
        spec = importlib.util.spec_from_file_location('finance_activation', SCRIPT)
        self.m = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.m)
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve(); self.root = self.base / 'source'; self.root.mkdir()
        self.op = self.base / 'operation'; self.op.mkdir(mode=0o700)
        (self.root / 'target/classes').mkdir(parents=True)
        (self.root / 'target/classes/Ready.class').write_bytes(b'old')
        self.config = {'sourceRoot': str(self.root), 'appContainer': 'test-app', 'uid': os.getuid(), 'gid': os.getgid(),
                       'javaHome': '/jdk', 'schemaFingerprint': '154', 'migrationFiles': {}, 'reloadTimeout': .02}
        self.config_path = self.base / 'runtime.json'; self.config_path.write_text(json.dumps(self.config))
        self.manifest = {}
        for name, data in [('src/main/java/New.java', b'new'), (self.m.SQL, (ROOT / self.m.SQL).read_bytes())]:
            payload = self.op / 'payload' / name; payload.parent.mkdir(parents=True, exist_ok=True); payload.write_bytes(data)
            self.manifest[name] = {'beforeHash': None, 'targetHash': digest(data)}
        (self.op / 'manifest.json').write_text(json.dumps(self.manifest))
        helper = ROOT / 'scripts/qa/java/FinanceQueryProjectionSchemaMaintenance.java'
        self.inputs = {'operationId': 'a' * 32, 'runtimeHash': digest(self.config_path.read_bytes()),
                       'appId': 'app', 'helperSha256': digest(helper.read_bytes())}
        (self.op / 'inputs.json').write_text(json.dumps(self.inputs))
        self.store = Store(); self.host = Host(self.root)
        self.r = shared_dev.ReloadCoordinator(self.config, self.store, self.host)
        self.r.state_file.parent.mkdir()
        shared_dev.atomic(self.r.state_file, {'phase': 'ready', 'owner': 'previous'})
        self.a = self.m.Activation(self.op, self.config_path, self.root, helper)
        self.events = []
        self.a.inspect = lambda c: {'Id': 'app'}
        self.a.runtime = lambda c: self.r

    def test_stop_occurs_outside_store_lock_and_after_reservation(self):
        self.a.reserve_idle_watcher(self.r, self.config)
        def stop(args, **kwargs):
            self.assertFalse(self.store.locked)
            self.assertTrue(self.host.owns_gate('a' * 32))
            return subprocess.CompletedProcess(args, 3 if 'is-active' in args else 0, stdout='inactive\n')
        with patch.object(self.m.subprocess, 'run', side_effect=stop): self.a.stop_watcher()
        self.a.release_idle_reservation(self.r)
        self.assertFalse(self.host.marker.exists())

    def test_inactive_three_is_normal_but_failed_is_rejected(self):
        for status, success in [('inactive\n', True), ('failed\n', False)]:
            with patch.object(self.m.subprocess, 'run', side_effect=[subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 3, stdout=status)]):
                if success: self.a.stop_watcher()
                else:
                    with self.assertRaises(RuntimeError): self.a.stop_watcher()

    def test_foreign_gate_cannot_be_reserved_or_removed(self):
        self.host.gate('foreign')
        with self.assertRaises(RuntimeError): self.a.reserve_idle_watcher(self.r, self.config)
        with self.assertRaises(RuntimeError): self.a.release_idle_reservation(self.r)
        self.assertEqual('foreign', json.loads(self.host.marker.read_text())['owner'])

    def test_source_cas_rejects_foreign_edit_without_overwriting(self):
        self.a.verify(self.config)
        path = self.root / 'src/main/java/New.java'; path.parent.mkdir(parents=True); path.write_bytes(b'foreign')
        with self.assertRaises(RuntimeError): self.a.apply_sources(self.config)
        self.assertEqual(b'foreign', path.read_bytes())

    def setup_execution(self, failure=None):
        shared_dev.atomic(self.op / 'preflight.json', {'phase': 'passed', 'manifestHash': digest((self.op / 'manifest.json').read_bytes()), 'inputsHash': digest((self.op / 'inputs.json').read_bytes())})
        def stopped():
            self.assertFalse(self.store.locked, 'watcher stop must not hold Store lock')
            if not self.host.marker.exists():
                self.assertEqual('late', failure)
            self.events.append('stop')
        self.a.stop_watcher = stopped
        self.a.start_watcher = lambda: self.events.append('start')
        self.a.unrelated_sources = lambda: {'staging': 'unchanged'}
        def migrate(c, mode):
            self.events.append(mode)
            if mode == 'migrate':
                self.host.version = '155'
                if failure == 'ddl': raise RuntimeError('Partial DDL')
                self.host.history += '\n2\t155\tSQL\tV155__finance_query_projections.sql\t7\t1'
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

    def test_partial_ddl_retains_gate_backup_and_refuses_replay(self):
        self.setup_execution('ddl')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertTrue(self.host.marker.exists())
        self.assertTrue((self.op / 'classes-before/Ready.class').is_file())
        self.assertNotIn('compile', self.events); self.assertNotIn('start', self.events)
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual(1, self.events.count('migrate'))

    def test_compile_failure_restores_classes_without_trigger_and_keeps_sql(self):
        self.setup_execution('compile')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertNotIn('trigger', self.events)
        self.assertNotIn('start', self.events)
        self.assertTrue(self.host.owns_gate(self.r.state['owner']))
        self.assertEqual(503, self.host.request('/api/v1/finance/shops')[0])
        failure = json.loads((self.op / 'state.json').read_text())
        self.assertTrue(failure['explicitRecoveryRequired'])
        self.assertTrue(failure['recoveryGateRetained'])
        self.assertEqual(b'old', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertTrue((self.root / self.m.SQL).is_file())
        self.assertFalse((self.root / 'src/main/java/New.java').exists())
        self.assertEqual('155', json.loads(self.config_path.read_text())['schemaFingerprint'])
        self.assertFalse((self.op / 'verified.json').exists())


    def test_compile_failure_never_starts_watcher_even_if_active_flag_is_false(self):
        self.setup_execution('compile')
        original = self.r._restore
        def restore(**kwargs):
            original(**kwargs); self.r.active = False
        self.r._restore = restore
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertNotIn('trigger', self.events); self.assertNotIn('start', self.events)
        self.assertTrue(self.host.owns_gate(self.r.state['owner']))
        self.assertEqual(b'old', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertFalse((self.op / 'verified.json').exists())

    def test_late_failure_creates_own_recovery_gate_and_retains_backup(self):
        self.setup_execution('late')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual('a' * 32, json.loads(self.host.marker.read_text())['owner'])
        self.assertEqual(b'old', (self.op / 'classes-before/Ready.class').read_bytes())
        self.assertEqual('needs-attention', json.loads((self.op / 'state.json').read_text())['phase'])

    def test_success_requires_final_watcher_context_and_preserves_config(self):
        self.setup_execution()
        self.a.deploy()
        receipt = json.loads((self.op / 'verified.json').read_text())
        self.assertEqual('final', receipt['contextAfter'])
        self.assertEqual(['stop', 'validate', 'migrate', 'compile', 'trigger', 'start', 'watcher-ready'], self.events)
        updated = json.loads(self.config_path.read_text())
        self.assertEqual({key: value for key, value in self.config.items() if key not in {'schemaFingerprint', 'migrationFiles'}},
                         {key: value for key, value in updated.items() if key not in {'schemaFingerprint', 'migrationFiles'}})
        self.assertFalse(self.host.marker.exists())


    def test_terminal_journal_failure_never_leaves_verified_receipt(self):
        self.setup_execution('late')
        self.a.wait_watcher = lambda *args: {'contextId': 'final', 'maintenance': False}
        original = self.a.state
        def fail_activated(phase, **fields):
            if phase == 'activated': raise OSError('Synthetic final journal failure')
            return original(phase, **fields)
        self.a.state = fail_activated
        self.r._status = lambda: {'protocol': 1, 'contextId': 'final' if not self.r.active else 'middle', 'maintenance': self.host.marker.exists()}
        with self.assertRaises(OSError): self.a.deploy()
        self.assertFalse((self.op / 'verified.json').exists())
        self.assertTrue(self.host.owns_gate(self.a.owner))

    def test_final_status_rejects_maintenance_reappearing_after_watcher_ready(self):
        self.setup_execution('late')
        def wait(*args):
            self.r._status = lambda: {'contextId': 'final', 'maintenance': True}
            return {'contextId': 'final', 'maintenance': False}
        self.a.wait_watcher = wait
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertFalse((self.op / 'verified.json').exists())
        self.assertTrue(self.host.owns_gate(self.a.owner))

    def test_watcher_wait_rejects_old_owner_old_context_and_gate(self):
        states = [({'phase': 'ready', 'owner': 'old', 'contextAfter': 'new'}, {'contextId': 'new', 'maintenance': False}),
                  ({'phase': 'ready', 'owner': 'new', 'contextAfter': 'middle'}, {'contextId': 'middle', 'maintenance': False}),
                  ({'phase': 'ready', 'owner': 'new', 'contextAfter': 'new'}, {'contextId': 'new', 'maintenance': True}),
                  ({'phase': 'ready', 'owner': 'new', 'contextAfter': 'final'}, {'contextId': 'final', 'maintenance': False})]
        def status():
            journal, current = states.pop(0); shared_dev.atomic(self.r.state_file, journal); return current
        self.r._status = status
        with patch.object(self.m.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, stdout='active\n')), patch.object(self.m.time, 'sleep'):
            self.assertEqual('final', self.a.wait_watcher(self.r, 'middle', 'old')['contextId'])
        self.assertEqual([], states)




    def test_default_cli_inspection_is_local_and_read_only(self):
        before = sorted(str(path.relative_to(self.op)) for path in self.op.rglob('*'))
        result = subprocess.run([sys.executable, str(SCRIPT), '--operation', str(self.op), '--root', str(self.root),
                                 '--config', str(self.base / 'missing-runtime.json'), '--helper', str(self.a.helper)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('local-inputs-verified', json.loads(result.stdout)['phase'])
        self.assertEqual(before, sorted(str(path.relative_to(self.op)) for path in self.op.rglob('*')))

    def test_concurrent_operation_claim_cannot_remove_first_reservation(self):
        self.a.claim_operation()
        self.host.gate(self.a.owner)
        with self.assertRaises(FileExistsError): self.a.claim_operation()
        self.assertTrue(self.host.owns_gate(self.a.owner))

    def test_foreign_gate_deploy_failure_never_stops_watcher(self):
        self.setup_execution(); self.host.gate('foreign')
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertEqual([], self.events)
        self.assertEqual('foreign', json.loads(self.host.marker.read_text())['owner'])
        self.assertEqual('needs-attention', json.loads((self.op / 'state.json').read_text())['phase'])

    def test_signal_after_release_before_return_is_contained(self):
        self.setup_execution('late')
        original = self.r.after_reload
        def interrupted(success):
            original(success)
            if success: raise InterruptedError('Synthetic signal after release')
        self.r.after_reload = interrupted
        with self.assertRaises(InterruptedError): self.a.deploy()
        self.assertTrue(self.host.owns_gate(self.a.owner))
        self.assertNotIn('start', self.events)
        self.assertFalse((self.op / 'verified.json').exists())

    def test_gate_release_journal_failure_recreates_own_gate(self):
        self.setup_execution()
        original_gate = self.host.gate; original_retain = self.r._retain_backup
        def gate(owner):
            self.assertEqual(1, self.store.depth, 'Recovery gate requires the Store dual lock')
            return original_gate(owner)
        def retain():
            self.assertEqual(1, self.store.depth, 'Recovery journal requires the Store dual lock')
            return original_retain()
        self.host.gate = gate; self.r._retain_backup = retain
        original = self.r._save
        def fail_ready(phase, **fields):
            if phase == 'ready': raise OSError('Synthetic journal fsync failure')
            return original(phase, **fields)
        self.r._save = fail_ready
        with self.assertRaises(OSError): self.a.deploy()
        self.assertTrue(self.host.owns_gate(self.r.state['owner']))
        self.assertNotIn('start', self.events)
        self.assertEqual(b'old', (self.op / 'classes-before/Ready.class').read_bytes())


    def test_reacquired_recovery_lock_preserves_foreign_gate_and_journal(self):
        self.setup_execution()
        original_save = self.r._save; original_after = self.r.after_reload
        def fail_ready(phase, **fields):
            if phase == 'ready': raise OSError('Synthetic ready journal failure')
            return original_save(phase, **fields)
        self.r._save = fail_ready
        foreign = {'owner': 'foreign', 'phase': 'prepared', 'foreignField': 'preserved'}
        handoff_entries = []
        def after(success):
            try: return original_after(success)
            except OSError:
                self.assertEqual(0, self.store.depth)
                with self.store.exclusive():
                    self.host.gate('foreign')
                    shared_dev.atomic(self.r.state_file, foreign)
                handoff_entries.append(self.store.entries)
                raise
        self.r.after_reload = after
        original_owns = self.host.owns_gate
        def owns(owner):
            self.assertEqual(1, self.store.depth, 'Ownership recheck must run inside reacquired lock')
            return original_owns(owner)
        self.host.owns_gate = owns
        original_read = self.m.read_json
        def read(path):
            if handoff_entries and Path(path) in {self.host.marker, self.r.state_file}:
                self.assertEqual(1, self.store.depth, 'Recovery owner reads require the Store dual lock')
            return original_read(path)
        self.m.read_json = read
        with self.assertRaises(OSError): self.a.deploy()
        self.assertGreater(self.store.entries, handoff_entries[0])
        self.assertEqual('foreign', json.loads(self.host.marker.read_text())['owner'])
        self.assertEqual(foreign, json.loads(self.r.state_file.read_text()))
        self.assertNotIn('start', self.events)
        self.assertFalse((self.op / 'verified.json').exists())

    def test_persistent_receipt_write_failure_still_establishes_late_recovery_gate(self):
        self.setup_execution('late')
        def wait(*args):
            self.host.context = 'final'
            return {'contextId': 'final', 'maintenance': False}
        self.a.wait_watcher = wait
        original_atomic = self.m.shared_dev.atomic
        unavailable = [False]
        def atomic(path, value):
            path = Path(path)
            if path == self.op / 'state.json' and value.get('phase') == 'activated': unavailable[0] = True
            if unavailable[0] and self.op in path.parents: raise OSError('Synthetic operation receipt volume unavailable')
            return original_atomic(path, value)
        with patch.object(self.m.shared_dev, 'atomic', side_effect=atomic):
            with self.assertRaises(OSError): self.a.deploy()
        self.assertTrue(self.host.marker.is_file(), 'Safety containment must not depend on operation receipts')
        self.assertEqual('a' * 32, json.loads(self.host.marker.read_text())['owner'])
        self.assertEqual('stop', self.events[-1])
        self.assertEqual(b'old', (self.op / 'classes-before/Ready.class').read_bytes())
        self.assertFalse((self.op / 'verified.json').exists())

    def test_partial_source_cas_failure_keeps_gate_and_never_compiles(self):
        self.setup_execution()
        original = self.a.put
        def fail_after_sql(name, data, config, expected):
            if name != self.m.SQL: raise RuntimeError('Concurrent source change')
            return original(name, data, config, expected)
        self.a.put = fail_after_sql
        with self.assertRaises(RuntimeError): self.a.deploy()
        self.assertTrue(self.host.marker.exists())
        self.assertTrue((self.root / self.m.SQL).is_file())
        self.assertNotIn('compile', self.events)
        self.assertNotIn('start', self.events)

    def test_timeout_revokes_token_before_verifying_jvm_and_connections(self):
        owner = 'b' * 32
        commands = []
        def runner(args, **kwargs):
            commands.append(args)
            if 'read-owned-connections' in args: return subprocess.CompletedProcess(args, 0, stdout='12\n')
            return subprocess.CompletedProcess(args, 0)
        sql = []
        def query(query):
            sql.append(query)
            if 'session_connect_attrs' in query: return '1'
            if query.startswith('KILL'): return ''
            return '1' if len(sql) == 1 else '0'
        self.host.sql = query
        with patch.object(self.m.subprocess, 'run', side_effect=runner):
            self.a.stop_java(self.config, owner, '/owned/token', '/owned')
        self.assertIn('revoke-owned-helper', commands[0])
        self.assertIn(str(self.config['uid']) + ':' + str(self.config['gid']), commands[0])
        self.assertIn('stop-owned-projection-helper', commands[1])
        self.assertIn('KILL CONNECTION 12', sql)
        self.assertIn("ATTR_VALUE='" + owner + "'", sql[1])

    def test_helper_cleanup_does_not_kill_unowned_reused_connection_id(self):
        sql = []
        def query(query):
            sql.append(query)
            return '0' if 'session_connect_attrs' in query else '1'
        self.host.sql = query
        def runner(args, **kwargs):
            return subprocess.CompletedProcess(args, 0, stdout='19\n' if 'read-owned-connections' in args else '')
        with patch.object(self.m.subprocess, 'run', side_effect=runner):
            with self.assertRaises(RuntimeError): self.a.stop_java(self.config, 'b'*32, '/owned/token', '/owned')
        self.assertFalse(any(query.startswith('KILL') for query in sql))

    def test_java_mode_timeouts_still_clean_up_random_owned_helper(self):
        self.a.inspect = lambda c: {'Id': 'app', 'Config': {'Env': ['DB_URL=jdbc:mysql://127.0.0.1/unused', 'DB_USERNAME=synthetic', 'DB_PASSWORD=synthetic']}}
        self.a.classpath = lambda c: '/running/dependencies'
        for mode, seconds in [('validate', 180), ('migrate', 900)]:
            with self.subTest(mode=mode):
                seen = []
                self.a.stop_java = lambda c, owner, token, directory: seen.append((owner, token, directory))
                def runner(args, **kwargs):
                    if 'launch-owned-projection-helper' in args:
                        self.assertEqual(seconds, kwargs['timeout'])
                        raise subprocess.TimeoutExpired('synthetic', kwargs['timeout'])
                    return subprocess.CompletedProcess(args, 0)
                with patch.object(self.m.subprocess, 'run', side_effect=runner):
                    with self.assertRaises(subprocess.TimeoutExpired): self.a.run_java(self.config, mode)
                self.assertEqual(1, len(seen)); self.assertRegex(seen[0][0], '^[a-f0-9]{32}$')
                self.assertEqual(seen[0][2] + '/launch.json', seen[0][1])

    def test_manifest_rejects_applied_sql_and_path_escape(self):
        for name, value in [(self.m.SQL, {'beforeHash': '0'*64, 'targetHash': self.m.SQL_SHA}),
                            ('src/main/java/../../private.properties', {'beforeHash': None, 'targetHash': '0'*64})]:
            altered = dict(self.manifest); altered[name] = value
            shared_dev.atomic(self.op / 'manifest.json', altered)
            with self.assertRaises(RuntimeError): self.m.Activation(self.op, self.config_path, self.root, self.a.helper)



@unittest.skipUnless(os.environ.get('FINANCE_ACTIVATION_JAVA_HOME') and os.environ.get('FINANCE_ACTIVATION_CLASSPATH'),
                     'Set explicit local Java 21 and cached dependency classpath for helper drills')
class JavaHelperDrills(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(); cls.base = Path(cls.tmp.name).resolve()
        cls.java = Path(os.environ['FINANCE_ACTIVATION_JAVA_HOME']) / 'bin/java'
        cls.cp = os.environ['FINANCE_ACTIVATION_CLASSPATH']
        harness = cls.base / 'OwnedConnectionHarness.java'
        harness.write_text(r"""
import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
public class OwnedConnectionHarness {
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
                        if(rm.getName().equals("getLong")) return 31337L;
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
        String owner="abababababababababababababababab";
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
        var source=new FinanceQueryProjectionSchemaMaintenance.OwnedDataSource("jdbc:owned-fake:unused","test","test",owner,record,token);
        try(var c=source.getConnection()) { accepted=true; }
        catch(SQLException expected) {}
        System.out.println(new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
            "opened",opened,"closed",closed,"accepted",accepted,"recorded",Files.exists(record)?Files.readString(record):"")));
    }
}
""")
        result = subprocess.run([str(Path(os.environ['FINANCE_ACTIVATION_JAVA_HOME']) / 'bin/javac'), '-cp', cls.cp,
                    '-d', str(cls.base), str(ROOT / 'scripts/qa/java/FinanceQueryProjectionSchemaMaintenance.java'), str(harness)], capture_output=True, text=True)
        if result.returncode: raise AssertionError('Independent helper compilation failed: ' + result.stderr)
        cls.cp = str(cls.base) + os.pathsep + cls.cp

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def harness(self, mode):
        directory = self.base / mode; directory.mkdir()
        result = subprocess.run([str(self.java), '-cp', self.cp, 'OwnedConnectionHarness', mode, str(directory)], capture_output=True, text=True, check=True)
        return json.loads(result.stdout)

    def test_every_connection_is_recorded_before_it_reaches_flyway(self):
        self.assertEqual({'opened': 1, 'closed': True, 'accepted': True, 'recorded': '31337\n'}, self.harness('valid'))

    def test_revoked_launch_never_opens_a_database_connection(self):
        self.assertEqual({'opened': 0, 'closed': False, 'accepted': False, 'recorded': ''}, self.harness('revoked'))

    def test_revocation_during_connect_closes_recorded_connection(self):
        self.assertEqual({'opened': 1, 'closed': True, 'accepted': False, 'recorded': '31337\n'}, self.harness('mid-revoke'))

    def test_missing_connection_identity_is_closed_before_flyway(self):
        self.assertEqual({'opened': 1, 'closed': True, 'accepted': False, 'recorded': ''}, self.harness('missing-id'))


    def test_fixed_sql_acceptance_reaches_revoked_token_without_database(self):
        directory = self.base / 'current-sql'; migration = directory / 'migration'; migration.mkdir(parents=True)
        (migration / 'V155__finance_query_projections.sql').write_bytes((ROOT / 'src/main/resources/db/migration/V155__finance_query_projections.sql').read_bytes())
        token = directory / 'launch.json'; token.write_text('null\n')
        owner = 'cd' * 16
        result = subprocess.run([str(self.java), '-Dheyi.financeProjectionSchemaOwner=' + owner, '-cp', self.cp,
             'FinanceQueryProjectionSchemaMaintenance', 'validate', str(migration), str(token)],
             input=json.dumps({'url': 'jdbc:mysql://127.0.0.1:1/never-connected', 'user': 'synthetic', 'password': 'synthetic', 'owner': owner}),
             capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertEqual({'success': False, 'failureType': 'SQLException'}, json.loads(result.stdout))

    def test_sql_hash_rejection_never_echoes_input_credentials(self):
        directory = self.base / 'bad-sql'; directory.mkdir()
        (directory / 'V155__finance_query_projections.sql').write_text('SELECT 1;')
        result = subprocess.run([str(self.java), '-cp', self.cp, 'FinanceQueryProjectionSchemaMaintenance', 'migrate', str(directory), str(self.base / 'launch.json')],
            input='{"password":"synthetic-secret-never-echo"}', capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertEqual({'success': False, 'failureType': 'IllegalArgumentException'}, json.loads(result.stdout))
        self.assertNotIn('synthetic-secret-never-echo', result.stdout + result.stderr)


if __name__ == '__main__': unittest.main()
