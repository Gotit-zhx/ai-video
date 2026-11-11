import contextlib
import hashlib
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('shared_dev', ROOT / 'scripts/shared_dev.py')
shared = importlib.util.module_from_spec(SPEC)
if SPEC.loader and SPEC.origin and Path(SPEC.origin).exists():
    SPEC.loader.exec_module(shared)


class Store:
    def __init__(self): self.locked = False
    @contextlib.contextmanager
    def exclusive(self):
        if self.locked: raise RuntimeError('busy')
        self.locked = True
        try: yield
        finally: self.locked = False
    def incomplete(self): return []


class Host:
    def __init__(self, root):
        self.marker = root / 'maintenance.json'
        self.context = 'before'; self.calls = []; self.schema_value = 'schema'
    def schema(self): return self.schema_value
    def gate(self, owner):
        if self.marker.exists() and json.loads(self.marker.read_text())['owner'] != owner:
            raise RuntimeError('foreign')
        self.marker.write_text(json.dumps({'owner': owner})); self.calls.append('gate')
    def drain(self, owner): self.calls.append('drain')
    def owns_gate(self, owner):
        if not self.marker.exists(): return False
        if json.loads(self.marker.read_text())['owner'] != owner: raise RuntimeError('foreign')
        return True
    def ungate(self, owner):
        if self.owns_gate(owner): self.marker.unlink()
        self.calls.append('ungate')
    def request(self, path, authenticated=False):
        if path.endswith('health'): return 200, b'{"status":"UP"}'
        return 200, json.dumps({'protocol': 1, 'contextId': self.context,
            'maintenance': self.marker.exists(), 'activeRequests': 0, 'activeWorkers': 0}).encode()


class SharedReloadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'target/classes').mkdir(parents=True)
        (self.root / 'target/classes/Ready.class').write_bytes(b'old')
        self.store = Store(); self.host = Host(self.root); self.commands = []
        self.config = {'sourceRoot': str(self.root), 'appContainer': 'ai-commerce-app',
            'maven': '/opt/dev-maven/bin/mvn', 'javaHome': '/opt/dev-jdk', 'mavenRepository': '/maven-cache',
            'composeFiles': ['/config/base.json', '/config/source.json'], 'composeProject': 'heyi-production',
            'uid': __import__('os').getuid(), 'gid': __import__('os').getgid(),
            'schemaFingerprint': 'schema', 'migrationFiles': {}, 'reloadTimeout': .05,
            'runtimeConfigurationHash': 'unchanged'}
        self.reload = shared.ReloadCoordinator(self.config, self.store, self.host,
            runner=self.run_command,
            inspect_runtime=lambda: {'hash': 'unchanged'}, runtime_hash=lambda info: info['hash'])
        backup = self.root / '.dev/reload-backups/test/classes'
        backup.parent.mkdir(parents=True)
        shutil.copytree(self.root / 'target/classes', backup)
        self.reload.watcher = SimpleNamespace(backup_path=backup, restart_required=False)

    def run_command(self, args, **kwargs):
        self.commands.append(args)
        if args[:2] == ['docker', 'compose']: self.host.context = 'recreated'
        return SimpleNamespace(returncode=0)

    def test_source_reload_holds_lock_until_new_context_is_healthy(self):
        self.reload.before_reload()
        self.assertTrue(self.store.locked)
        self.assertEqual(['gate', 'drain'], self.host.calls)
        self.reload.compile()
        self.assertNotIn('package', self.commands[0])
        self.assertIn('compile', self.commands[0])
        self.host.context = 'after'
        self.reload.after_reload(True)
        self.assertFalse(self.store.locked)
        self.assertFalse(self.host.marker.exists())
        self.assertEqual('ready', json.loads(self.reload.state_file.read_text())['phase'])

    def test_failed_compile_reopens_only_own_healthy_previous_context(self):
        self.reload.before_reload()
        self.reload.after_reload(False)
        self.assertFalse(self.host.marker.exists())
        self.assertFalse(self.store.locked)
        self.assertEqual(b'old', (self.root / 'target/classes/Ready.class').read_bytes())

    def test_foreign_gate_is_preserved_without_running_compile(self):
        self.host.marker.write_text('{"owner":"foreign"}')
        with self.assertRaises(RuntimeError): self.reload.before_reload()
        self.reload.after_reload(False)
        self.assertEqual('foreign', json.loads(self.host.marker.read_text())['owner'])
        self.assertEqual([], self.commands)
        self.assertFalse(self.store.locked)

    def test_schema_or_source_migration_drift_never_triggers_automatic_ddl(self):
        self.host.schema_value = 'changed'
        with self.assertRaises(RuntimeError): self.reload.before_reload()
        self.reload.after_reload(False)
        self.assertFalse(self.host.marker.exists())
        self.assertEqual([], self.commands)

    def test_runtime_drift_does_not_replace_another_session_configuration(self):
        self.reload.inspect_runtime = lambda: {'hash': 'foreign'}
        with self.assertRaises(RuntimeError): self.reload.before_reload()
        self.reload.after_reload(False)
        self.assertEqual([], self.commands)
        self.assertFalse(self.store.locked)

    def test_pom_restart_recreates_only_application_service(self):
        self.reload.before_reload(); self.reload.compile()
        self.reload.watcher.restart_required = True
        self.host.context = 'after'
        self.reload.after_reload(True)
        compose = next(argv for argv in self.commands if argv[:2] == ['docker', 'compose'])
        self.assertEqual('app', compose[-1])
        self.assertNotIn('frontend', compose)

    def test_interrupted_compile_recovers_only_owned_class_output(self):
        self.reload.before_reload(); self.reload.compile()
        (self.root / 'target/classes/Ready.class').write_bytes(b'partial')
        self.reload._release_lock()
        replacement = shared.ReloadCoordinator(self.config, self.store, self.host,
            runner=self.reload.runner, inspect_runtime=self.reload.inspect_runtime, runtime_hash=self.reload.runtime_hash)
        self.assertEqual('recovered', replacement.recover()['phase'])
        self.assertEqual(b'old', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertFalse(self.host.marker.exists())
        self.assertFalse(self.store.locked)

    def test_context_not_ready_is_retried_while_restart_is_in_progress(self):
        self.config['reloadTimeout'] = 1
        self.reload.before_reload()
        original = self.host.request
        pending = [True]
        def request(path, authenticated=False):
            if path.endswith('/status') and pending:
                pending.pop(); return 503, b'{}'
            self.host.context = 'after'
            return original(path, authenticated=authenticated)
        self.host.request = request
        self.reload.after_reload(True)
        self.assertFalse(self.host.marker.exists())
        self.assertFalse(self.store.locked)


class SharedRecoveryCrashTest(SharedReloadTest):
    def setUp(self):
        super().setUp()
        self.config['composeProject'] = 'heyi-production'
        self.events = []
        self.dead = False
        self.restart_count = 0
        self.original_request = self.host.request
        self.host.request = self.request
        self.reload.runner = self.run_command

    def request(self, path, authenticated=False):
        if self.dead: return 503, b'{}'
        if path.endswith('/status'):
            self.assertTrue(authenticated)
        return self.original_request(path, authenticated=authenticated)

    def run_command(self, command, **kwargs):
        self.commands.append(command)
        if command[:2] == ['docker', 'compose']:
            self.events.append('restart')
            self.restart_count += 1
            self.dead = False
            self.host.context = 'recovered-' + str(self.restart_count)
        elif 'compile' in command:
            self.events.append('compile')
        else:
            self.events.append('stop-compiler')
        return SimpleNamespace(returncode=0)

    def replacement(self):
        return shared.ReloadCoordinator(self.config, self.store, self.host,
            runner=self.run_command, inspect_runtime=self.reload.inspect_runtime,
            runtime_hash=self.reload.runtime_hash)

    def interrupted(self):
        self.reload.before_reload(); self.reload.compile(); self.reload._release_lock()
        (self.root / 'target/classes/Ready.class').write_bytes(b'new')

    def test_dead_new_context_is_recreated_from_restored_output(self):
        self.interrupted(); self.dead = True
        self.assertEqual('recovered', self.replacement().recover()['phase'])
        self.assertEqual(b'old', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertEqual(1, self.restart_count)
        self.assertFalse(self.host.marker.exists())

    def test_restored_bytes_do_not_authorize_unknown_running_context(self):
        self.interrupted()
        (self.root / 'target/classes/Ready.class').write_bytes(b'old')
        self.host.context = 'unknown-running-code'
        self.replacement().recover()
        self.assertEqual('recovered-1', self.host.context)
        self.assertFalse(self.host.marker.exists())

    def test_missing_class_directory_is_restored(self):
        self.interrupted(); shutil.rmtree(self.root / 'target/classes')
        self.replacement().recover()
        self.assertEqual(b'old', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertFalse(self.host.marker.exists())

    def test_restore_intent_precedes_delete_and_second_recovery_restarts(self):
        from unittest.mock import patch
        self.interrupted()
        remove = shutil.rmtree
        def crash_after_removal(path, *args, **kwargs):
            if Path(path).resolve() == (self.root / 'target/classes').resolve():
                self.assertEqual('restoring', json.loads(self.reload.state_file.read_text())['phase'])
                remove(path, *args, **kwargs)
                raise KeyboardInterrupt()
            return remove(path, *args, **kwargs)
        with patch.object(shared.shutil, 'rmtree', side_effect=crash_after_removal):
            with self.assertRaises(KeyboardInterrupt): self.replacement().recover()
        self.assertTrue(self.host.marker.exists())
        self.replacement().recover()
        self.assertEqual('recovered-1', self.host.context)
        self.assertFalse(self.host.marker.exists())

    def test_restore_copy_completed_before_crash_still_recreates_next_attempt(self):
        from unittest.mock import patch
        self.interrupted()
        copy = shutil.copytree
        def crash_after_copy(source, destination, *args, **kwargs):
            value = copy(source, destination, *args, **kwargs)
            if Path(destination).resolve() == (self.root / 'target/classes').resolve(): raise KeyboardInterrupt()
            return value
        with patch.object(shared.shutil, 'copytree', side_effect=crash_after_copy):
            with self.assertRaises(KeyboardInterrupt): self.replacement().recover()
        self.replacement().recover()
        self.assertEqual('recovered-1', self.host.context)
        self.assertFalse(self.host.marker.exists())

    def test_matching_backup_from_another_operation_is_rejected(self):
        self.interrupted()
        foreign = self.root / '.dev/shared-recovery' / ('f' * 32) / 'classes'
        foreign.parent.mkdir(parents=True)
        shutil.copytree(self.reload.watcher.backup_path, foreign)
        state = json.loads(self.reload.state_file.read_text()); state['backupPath'] = str(foreign)
        self.reload.state_file.write_text(json.dumps(state))
        with self.assertRaises(RuntimeError): self.replacement().recover()
        self.assertEqual(b'new', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertTrue(self.host.marker.exists())

    def test_orphan_shutdown_failure_prevents_any_class_restore(self):
        self.interrupted()
        def failed_shutdown(command, **kwargs):
            if command[:2] == ['docker', 'exec'] and 'compile' not in command:
                raise RuntimeError('cannot prove compiler stopped')
            return self.run_command(command, **kwargs)
        recovery = self.replacement(); recovery.runner = failed_shutdown
        with self.assertRaises(RuntimeError): recovery.recover()
        self.assertEqual(b'new', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertTrue(self.host.marker.exists())

    def test_compiler_jvm_is_uniquely_marked_and_stopped_before_restore(self):
        self.interrupted()
        owner = json.loads(self.reload.state_file.read_text())['owner']
        compile_command = next(command for command in self.commands if 'compile' in command)
        self.assertTrue(any('-Dheyi.sharedCompileOwner=' + owner in value for value in compile_command))
        self.events.clear(); self.replacement().recover()
        self.assertEqual('stop-compiler', self.events[0])
        self.assertEqual('restart', self.events[-1])

    def test_watch_start_reconciles_downtime_edit_and_resource_deletion(self):
        from unittest.mock import patch
        (self.root / 'src/main/java').mkdir(parents=True)
        (self.root / 'src/main/java/Ready.java').write_text('new source')
        (self.root / 'target/classes/deleted-resource.txt').write_text('stale')
        config_file = self.root / 'config.json'; config_file.write_text(json.dumps(self.config))
        def compile_changed(command, **kwargs):
            result = self.run_command(command, **kwargs)
            if 'compile' in command:
                (self.root / 'target/classes/Ready.class').write_bytes(b'new source')
                self.host.context = 'compiled-during-startup'
            return result
        self.reload.runner = compile_changed
        with patch.object(shared, 'context', return_value=self.reload), patch.object(shared.SourceWatcher, 'run'):
            shared.main(['watch', '--config', str(config_file)])
        self.assertEqual(b'new source', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertFalse((self.root / 'target/classes/deleted-resource.txt').exists())
        self.assertFalse(self.host.marker.exists())

    def test_compile_timeout_stops_orphan_before_restoring_last_output(self):
        import subprocess
        def timeout(command, **kwargs):
            if 'compile' in command:
                (self.root / 'target/classes/Ready.class').write_bytes(b'partial')
                raise subprocess.TimeoutExpired(command, 180)
            if command[:2] == ['docker', 'exec']:
                self.assertEqual(b'partial', (self.root / 'target/classes/Ready.class').read_bytes())
            return self.run_command(command, **kwargs)
        self.reload.runner = timeout
        watcher = shared.SharedSourceWatcher(self.root, self.reload); self.reload.watcher = watcher
        self.assertFalse(watcher.prepare())
        self.assertEqual(b'old', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertFalse(self.host.marker.exists())
        self.assertEqual('ready', json.loads(self.reload.state_file.read_text())['phase'])

    def test_compiler_stop_failure_preserves_partial_output_for_gated_recovery(self):
        def timeout_and_cannot_stop(command, **kwargs):
            if 'compile' in command:
                (self.root / 'target/classes/Ready.class').write_bytes(b'partial')
                raise RuntimeError('client disconnected')
            raise RuntimeError('cannot prove compiler exited')
        self.reload.runner = timeout_and_cannot_stop
        watcher = shared.SharedSourceWatcher(self.root, self.reload); self.reload.watcher = watcher
        with self.assertRaises(RuntimeError): watcher.prepare()
        self.assertEqual(b'partial', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertTrue(self.host.marker.exists())
        state = json.loads(self.reload.state_file.read_text())
        self.assertEqual('needs_attention', state['phase'])
        self.assertEqual(b'old', (Path(state['backupPath']) / 'Ready.class').read_bytes())
        self.replacement().recover()
        self.assertEqual(b'old', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertFalse(self.host.marker.exists())

    def test_recreate_must_prove_a_changed_context_before_ungating(self):
        self.interrupted()
        recovery = self.replacement()
        recovery.runner = lambda command, **kwargs: SimpleNamespace(returncode=0)
        with self.assertRaises(RuntimeError): recovery.recover()
        self.assertTrue(self.host.marker.exists())
        self.assertEqual(b'old', (self.root / 'target/classes/Ready.class').read_bytes())

    def test_finished_recovery_cleans_only_its_own_backup(self):
        self.interrupted()
        unrelated = self.root / '.dev/shared-recovery' / ('a' * 32)
        unrelated.mkdir(); (unrelated / 'keep.txt').write_text('other session')
        owner = json.loads(self.reload.state_file.read_text())['owner']
        self.replacement().recover()
        self.assertTrue((unrelated / 'keep.txt').exists())
        self.assertFalse((self.root / '.dev/shared-recovery' / owner).exists())

    def test_frontend_only_revision_remains_readable_with_private_umask(self):
        import os
        previous = os.umask(0o077)
        try:
            watcher = shared.SharedSourceWatcher(self.root, self.reload)
            watcher._write_revision('changed')
        finally: os.umask(previous)
        self.assertEqual(0o644, (self.root / '.dev/source-state.json').stat().st_mode & 0o777)

    def test_crash_after_ungate_finishes_journal_without_second_restart(self):
        self.interrupted()
        recovery = self.replacement()
        save = recovery._save
        def interrupted_save(phase, **fields):
            if phase == 'recovered': raise KeyboardInterrupt()
            return save(phase, **fields)
        recovery._save = interrupted_save
        with self.assertRaises(KeyboardInterrupt): recovery.recover()
        self.assertFalse(self.host.marker.exists())
        self.assertEqual('recovered', self.replacement().recover()['phase'])
        self.assertEqual(1, self.restart_count)

    def test_crash_during_backup_without_class_mutation_can_release_old_context(self):
        from unittest.mock import patch
        with patch.object(shared.shutil, 'copytree', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt): self.reload.before_reload()
        self.reload._release_lock()
        self.assertTrue(self.host.marker.exists())
        self.assertEqual('recovered', self.replacement().recover()['phase'])
        self.assertEqual(b'old', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertFalse(self.host.marker.exists())
        self.assertEqual(0, self.restart_count)

    def test_cancel_revokes_launch_token_before_stopping_processes(self):
        self.reload.before_reload()
        token = self.root / '.dev/compiler-owner.json'
        def interrupted(command, **kwargs):
            if 'compile' in command:
                self.assertEqual(self.reload.state['owner'], json.loads(token.read_text()))
                raise RuntimeError('client disconnected before Java spawn')
            self.assertIsNone(json.loads(token.read_text()))
            return self.run_command(command, **kwargs)
        self.reload.runner = interrupted
        with self.assertRaises(RuntimeError): self.reload.compile()
        self.assertIsNone(json.loads(token.read_text()))
        self.reload._release_lock()

    def test_cancel_refuses_another_compiler_owner_token(self):
        self.interrupted()
        (self.root / '.dev/compiler-owner.json').write_text(json.dumps('f' * 32))
        with self.assertRaises(RuntimeError): self.replacement().recover()
        self.assertEqual(b'new', (self.root / 'target/classes/Ready.class').read_bytes())
        self.assertTrue(self.host.marker.exists())

    def test_delayed_launch_cannot_execute_after_token_revocation(self):
        import subprocess
        import sys
        token = self.root / 'owner.json'; output = self.root / 'compiler-ran'
        owner = 'b' * 32
        command = ['sh', '-ec', shared.LAUNCH_COMPILER, 'launch-owned-compiler', owner,
                   str(token), sys.executable, '-c',
                   'from pathlib import Path; import sys; Path(sys.argv[1]).write_text("compiled")', str(output)]
        token.write_text(json.dumps(owner))
        self.assertEqual(0, subprocess.run(command, capture_output=True).returncode)
        self.assertEqual('compiled', output.read_text()); output.unlink()
        token.write_text('null')
        self.assertEqual(75, subprocess.run(command, capture_output=True).returncode)
        self.assertFalse(output.exists())

    def test_compiler_stop_shell_is_syntactically_valid(self):
        import subprocess
        result = subprocess.run(['sh', '-n'], input=shared.STOP_COMPILER, text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_failed_startup_compile_keeps_existing_trigger_unchanged(self):
        trigger = self.root / 'target/classes/.reloadtrigger'
        trigger.write_text('already-running\n'); original = trigger.stat()
        def failed_compile(command, **kwargs):
            if 'compile' in command:
                self.assertEqual('already-running\n', trigger.read_text())
                return SimpleNamespace(returncode=1)
            return self.run_command(command, **kwargs)
        self.reload.runner = failed_compile
        watcher = shared.SharedSourceWatcher(self.root, self.reload); self.reload.watcher = watcher
        self.assertFalse(watcher.prepare())
        self.assertEqual(original.st_ino, trigger.stat().st_ino)
        self.assertEqual(original.st_mtime_ns, trigger.stat().st_mtime_ns)
        self.assertFalse(self.host.marker.exists())

    def test_terminal_receipt_retries_its_own_backup_cleanup_after_interruption(self):
        from unittest.mock import patch
        self.interrupted()
        owner = self.reload.state['owner']
        remove = shutil.rmtree
        def fail_cleanup(path, *args, **kwargs):
            if Path(path).name == owner: raise OSError('cleanup interrupted')
            return remove(path, *args, **kwargs)
        with patch.object(shared.shutil, 'rmtree', side_effect=fail_cleanup):
            self.replacement().recover()
        backup = self.root / '.dev/shared-recovery' / owner
        self.assertTrue(backup.exists())
        self.replacement().recover()
        self.assertFalse(backup.exists())
