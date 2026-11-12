import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import threading
import socket
import textwrap

SPEC = importlib.util.spec_from_file_location('heyi_dev', Path(__file__).parents[1] / 'dev.py')
dev = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(dev)


class SourceWatcherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for directory in ('frontend', 'src/main/java/example', 'src/main/resources', 'target/classes/example'):
            (self.root / directory).mkdir(parents=True)
        (self.root / 'src/main/java/example/Main.java').write_text('class Main {}')
        (self.root / 'frontend/app.css').write_text('body {}')
        self.compiles = []
        self.watcher = dev.SourceWatcher(self.root, lambda: self.compiles.append(True) or True)
        self.watcher.debounce_seconds = .01

    def settle(self):
        self.watcher.step()
        time.sleep(.02)
        return self.watcher.step()

    def test_frontend_changes_refresh_revision_without_java_compile(self):
        state = self.root / '.dev/source-state.json'
        original = state.read_bytes()
        (self.root / 'frontend/app.css').write_text('body {color:red}')
        self.settle()
        self.assertNotEqual(original, state.read_bytes())
        self.assertEqual([], self.compiles)
        self.assertEqual(['frontendRevision'], list(json.loads(state.read_text())))
        stamp = state.stat().st_mtime_ns
        self.settle()
        self.assertEqual(stamp, state.stat().st_mtime_ns)

    def test_private_files_do_not_change_frontend_revision(self):
        original = (self.root / '.dev/source-state.json').read_bytes()
        for name in ('.env', 'secret.env', 'private.pem', '.private.js'):
            (self.root / 'frontend' / name).write_text('SECRET')
        self.settle()
        self.assertEqual(original, (self.root / '.dev/source-state.json').read_bytes())

    def test_modern_builds_never_reload_tabs_with_classic_financial_drafts(self):
        original = (self.root / '.dev/source-state.json').read_bytes()
        modern = self.root / 'frontend/modern/src'
        assets = self.root / 'frontend/assets/finance-modern/assets'
        modern.mkdir(parents=True)
        assets.mkdir(parents=True)
        (modern / 'page.tsx').write_text('export const page = 1')
        (self.root / 'frontend/modern/package.json').write_text('{}')
        (assets / 'page-hash.js').write_text('export const page = 2')
        (assets.parent / 'manifest.json').write_text('{}')
        self.settle()
        self.assertEqual(original, (self.root / '.dev/source-state.json').read_bytes())
        self.assertEqual([], self.compiles)

    def test_java_burst_debounces_and_triggers_once_after_success(self):
        source = self.root / 'src/main/java/example/Main.java'
        source.write_text('class Main { int a; }')
        self.watcher.step()
        source.write_text('class Main { int b; }')
        self.watcher.step()
        self.assertEqual([], self.compiles)
        time.sleep(.02)
        self.watcher.step()
        self.assertEqual([True], self.compiles)
        self.assertTrue((self.root / 'target/classes/.reloadtrigger').exists())

    def test_failed_compile_restores_classes_without_trigger_and_releases_gate(self):
        compiled = self.root / 'target/classes/example/Main.class'
        compiled.write_bytes(b'valid')
        trigger = self.root / 'target/classes/.reloadtrigger'
        trigger.write_text('before')
        events = []
        def compile_action():
            events.append('compile')
            compiled.write_bytes(b'broken')
            (compiled.parent / 'Partial.class').write_bytes(b'partial')
            return False
        watcher = dev.SourceWatcher(self.root, compile_action,
            before_reload=lambda: events.append('gate'), after_reload=lambda ok: events.append(ok))
        watcher.debounce_seconds = 0
        (self.root / 'src/main/java/example/Main.java').write_text('broken')
        watcher.step(); watcher.step()
        self.assertEqual(['gate', 'compile', False], events)
        self.assertEqual(b'valid', compiled.read_bytes())
        self.assertFalse((compiled.parent / 'Partial.class').exists())
        self.assertEqual('before', trigger.read_text())

    def test_deleted_source_removes_only_owned_stale_classes(self):
        for name in ('Main.class', 'Main$Inner.class', 'Other.class'):
            (self.root / 'target/classes/example' / name).write_bytes(b'class')
        (self.root / 'src/main/java/example/Main.java').unlink()
        self.settle()
        self.assertFalse((self.root / 'target/classes/example/Main.class').exists())
        self.assertFalse((self.root / 'target/classes/example/Main$Inner.class').exists())
        self.assertTrue((self.root / 'target/classes/example/Other.class').exists())


    def test_pom_change_is_visible_to_safe_point_callbacks(self):
        (self.root / 'pom.xml').write_text('<project/>')
        events = []
        watcher = dev.SourceWatcher(self.root, lambda: True,
            after_reload=lambda success: events.append((success, watcher.restart_required)))
        watcher.debounce_seconds = 0
        (self.root / 'pom.xml').write_text('<project><dependencies/></project>')
        watcher.step(); watcher.step()
        self.assertEqual([(True, True)], events)

    def test_deleted_class_is_removed_after_a_failed_compile_is_fixed(self):
        compiled = self.root / 'target/classes/example/Main.class'
        compiled.write_bytes(b'valid')
        results = iter([False, True])
        watcher = dev.SourceWatcher(self.root, lambda: next(results))
        watcher.debounce_seconds = 0
        (self.root / 'src/main/java/example/Main.java').unlink()
        watcher.step(); watcher.step()
        self.assertTrue(compiled.exists())
        (self.root / 'src/main/java/example/Replacement.java').write_text('class Replacement {}')
        watcher.step(); watcher.step()
        self.assertFalse(compiled.exists())

    def test_resource_delete_restores_failure_and_removes_on_success(self):
        source = self.root / 'src/main/resources/example.txt'
        output = self.root / 'target/classes/example.txt'
        source.write_text('original'); output.write_text('original')
        watcher = dev.SourceWatcher(self.root, lambda: True)
        watcher.debounce_seconds = 0
        source.unlink()
        watcher.step(); watcher.step()
        self.assertFalse(output.exists())

    def test_compile_exception_releases_safe_point_and_keeps_trigger(self):
        trigger = self.root / 'target/classes/.reloadtrigger'; trigger.write_text('original')
        results = []
        def failing_compile():
            raise RuntimeError('compiler could not start')
        watcher = dev.SourceWatcher(self.root, failing_compile, after_reload=results.append)
        watcher.debounce_seconds = 0
        (self.root / 'src/main/java/example/Main.java').write_text('different')
        watcher.step(); watcher.step()
        self.assertEqual([False], results)
        self.assertEqual('original', trigger.read_text())


    def test_backup_is_private_and_available_through_recovery_callback(self):
        compiled = self.root / 'target/classes/example/Main.class'
        compiled.write_bytes(b'valid')
        paths = []
        def compile_action():
            paths.append(watcher.backup_path)
            self.assertEqual(b'valid', (watcher.backup_path / 'example/Main.class').read_bytes())
            return True
        def recovered(success):
            self.assertTrue(success)
            self.assertTrue(watcher.backup_path.is_dir())
        watcher = dev.SourceWatcher(self.root, compile_action, after_reload=recovered)
        watcher.debounce_seconds = 0
        (self.root / 'src/main/java/example/Main.java').write_text('change')
        watcher.step(); watcher.step()
        self.assertEqual(1, len(paths))
        self.assertFalse(paths[0].exists())
        self.assertIsNone(watcher.backup_path)
        self.assertEqual(0o700, (self.root / '.dev/reload-backups').stat().st_mode & 0o777)


    def test_interrupt_restores_partial_compile_before_releasing_gate(self):
        compiled = self.root / 'target/classes/example/Main.class'; compiled.write_bytes(b'valid')
        released = []
        def interrupted_compile():
            compiled.write_bytes(b'partial')
            raise KeyboardInterrupt()
        watcher = dev.SourceWatcher(self.root, interrupted_compile, after_reload=released.append)
        watcher.debounce_seconds = 0
        (self.root / 'src/main/java/example/Main.java').write_text('edit')
        watcher.step()
        with self.assertRaises(KeyboardInterrupt):
            watcher.step()
        self.assertEqual(b'valid', compiled.read_bytes())
        self.assertEqual([False], released)


    def test_relaunch_discards_resources_removed_while_watcher_was_stopped(self):
        stale = self.root / 'target/classes/removed.yml'; stale.write_text('old config')
        other = self.root / 'target/keep.txt'; other.write_text('unrelated target output')
        def compile_action():
            (self.root / 'target/classes/current.txt').write_text('current resource')
            return True
        watcher = dev.SourceWatcher(self.root, compile_action)
        self.assertTrue(watcher.prepare())
        self.assertFalse(stale.exists())
        self.assertEqual('current resource', (self.root / 'target/classes/current.txt').read_text())
        self.assertEqual('unrelated target output', other.read_text())

    def test_failed_initial_compile_restores_previous_output_and_trigger(self):
        stale = self.root / 'target/classes/removed.yml'; stale.write_text('previous good output')
        trigger = self.root / 'target/classes/.reloadtrigger'; trigger.write_text('previous trigger')
        def compile_action():
            (self.root / 'target/classes/partial.txt').write_text('partial')
            return False
        watcher = dev.SourceWatcher(self.root, compile_action)
        self.assertFalse(watcher.prepare())
        self.assertEqual('previous good output', stale.read_text())
        self.assertEqual('previous trigger', trigger.read_text())
        self.assertFalse((self.root / 'target/classes/partial.txt').exists())


class ModernFrontendTest(unittest.TestCase):
    def test_fresh_checkout_compiles_once_then_rebuilds_only_changed_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            modern = root / 'frontend/modern'
            (modern / 'src').mkdir(parents=True)
            (modern / 'package.json').write_text('{}')
            (modern / 'package-lock.json').write_text('{"lockfileVersion":3}')
            source = modern / 'src/page.tsx'
            source.write_text('export const page=1')
            calls = []
            def run(command, **options):
                calls.append(command)
                (modern / 'node_modules/vite').mkdir(parents=True, exist_ok=True)
                (modern / 'node_modules/vite/package.json').write_text('{}')
                assets = root / 'frontend/assets/finance-modern'
                assets.mkdir(parents=True, exist_ok=True)
                (assets / 'manifest.json').write_text('{}')
                return subprocess.CompletedProcess(command, 0)
            with patch.object(dev.subprocess, 'run', side_effect=run):
                self.assertTrue(dev.ensure_modern_frontend(root, {}))
                self.assertEqual(2, len(calls))
                self.assertIn('ci', calls[0])
                self.assertIn('build', calls[1])
                self.assertFalse(dev.ensure_modern_frontend(root, {}))
                self.assertEqual(2, len(calls))
                source.write_text('export const page=2')
                self.assertTrue(dev.ensure_modern_frontend(root, {}))
                self.assertEqual(3, len(calls))
                self.assertIn('build', calls[-1])

    def test_failed_frontend_build_keeps_previous_success_marker(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            modern = root / 'frontend/modern'
            modern.mkdir(parents=True)
            (modern / 'package.json').write_text('{}')
            (modern / 'package-lock.json').write_text('{}')
            with patch.object(dev.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'npm')):
                with self.assertRaises(subprocess.CalledProcessError):
                    dev.ensure_modern_frontend(root, {})
            self.assertFalse((root / '.dev/finance-modern-source.sha256').exists())


class LauncherTest(unittest.TestCase):
    def test_workspace_lock_excludes_another_owner_and_releases_normally(self):
        with tempfile.TemporaryDirectory() as temp:
            with dev.workspace_lock(Path(temp)):
                with self.assertRaises(RuntimeError):
                    with dev.workspace_lock(Path(temp)):
                        self.fail('another launcher acquired the workspace')
            with dev.workspace_lock(Path(temp)):
                pass

    def test_saves_during_initial_and_dependency_startup_wait_for_owned_ready_process(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'src/main/java').mkdir(parents=True)
            source = root / 'src/main/java/App.java'; source.write_text('before')
            pom = root / 'pom.xml'; pom.write_text('<project/>')
            with socket.socket() as free:
                free.bind(('127.0.0.1', 0)); port = free.getsockname()[1]
            fixture = root / 'fake_maven.py'
            fixture.write_text(textwrap.dedent("""
                import http.server, json, os, pathlib, sys, threading, time
                root = pathlib.Path.cwd()
                active = root / 'initial-compile-active'
                if 'spring-boot:run' not in sys.argv:
                    if active.exists(): (root / 'overlap').touch()
                    with (root / 'compiled-sources').open('a') as output:
                        output.write((root / 'src/main/java/App.java').read_text() + '\\n')
                    raise SystemExit(0)
                counter = root / 'launch-count'
                count = int(counter.read_text()) + 1 if counter.exists() else 1
                counter.write_text(str(count))
                active.touch()
                time.sleep(1.5)
                active.unlink()
                class Handler(http.server.BaseHTTPRequestHandler):
                    def log_message(self, *args): pass
                    def do_GET(self):
                        if self.path == '/_heyi/ops/status':
                            token_file = os.environ.get('HEYI_OPS_TOKEN_FILE')
                            if token_file and self.headers.get('X-Heyi-Ops-Token') != pathlib.Path(token_file).read_text().strip():
                                self.send_response(404); self.end_headers(); return
                            body = {'protocol': 1, 'contextId': str(os.getpid()), 'maintenance': False}
                        else: body = {'status': 'UP'}
                        self.send_response(200); self.end_headers(); self.wfile.write(json.dumps(body).encode())
                server = http.server.HTTPServer(('127.0.0.1', int(os.environ['APP_PORT'])), Handler)
                threading.Thread(target=lambda: (time.sleep(8 if count == 1 else 2), server.shutdown()), daemon=True).start()
                server.serve_forever(poll_interval=.05)
            """))
            def edit_during_start():
                deadline = time.monotonic() + 8
                while not (root / 'initial-compile-active').exists():
                    if time.monotonic() > deadline: return
                    time.sleep(.01)
                source.write_text('saved during initial compile')
                while (root / 'initial-compile-active').exists():
                    if time.monotonic() > deadline: return
                    time.sleep(.01)
                pom.write_text('<project><dependencies/></project>')
                while not ((root / 'launch-count').read_text() == '2' and (root / 'initial-compile-active').exists()):
                    if time.monotonic() > deadline: return
                    time.sleep(.01)
                source.write_text('saved during dependency startup')
            editor = threading.Thread(target=edit_during_start)
            editor.start()
            with patch.object(dev, 'ROOT', root), patch.object(dev, 'toolchain', return_value=[sys.executable, str(fixture)]), patch.dict(os.environ, {'APP_PORT': str(port)}):
                result = dev.main(['run', '--no-deps'])
            editor.join(timeout=2)
            self.assertEqual(0, result)
            self.assertFalse((root / 'overlap').exists(), 'watch compiler overlapped spring-boot:run test-compile')
            self.assertIn('saved during initial compile', (root / 'compiled-sources').read_text())
            self.assertIn('saved during dependency startup', (root / 'compiled-sources').read_text())
            self.assertEqual('2', (root / 'launch-count').read_text())

    def test_existing_listener_is_rejected_before_startup(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0)); listener.listen()
            with self.assertRaises(RuntimeError):
                dev.require_free_port(listener.getsockname()[1])

    def test_toolchain_rejects_a_different_maven_patch_release(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / 'bin').mkdir()
            (root / 'bin/java').touch()
            responses = [subprocess.CompletedProcess([], 0, '', 'openjdk version "21.0.9"'),
                         subprocess.CompletedProcess([], 0, 'Apache Maven 3.9.12', '')]
            with patch.object(dev.subprocess, 'run', side_effect=responses):
                with self.assertRaisesRegex(RuntimeError, '3.9.11'):
                    dev.toolchain({'JAVA_HOME': str(root), 'MAVEN': '/unused/mvn'})

    def test_env_parser_never_executes_or_expands_shell(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / '.env.development'
            marker = Path(temp) / 'executed'
            path.write_text('PLAIN=value\nQUOTED="two words"\nPAYLOAD=$(touch ' + str(marker) + ')\n')
            parsed = dev.read_env(path)
            self.assertEqual('two words', parsed['QUOTED'])
            self.assertEqual('$(touch ' + str(marker) + ')', parsed['PAYLOAD'])
            self.assertFalse(marker.exists())

    def test_init_is_private_and_preserves_existing_secrets(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = dev.init_env(root)
            original = path.read_bytes()
            dev.init_env(root)
            self.assertEqual(original, path.read_bytes())
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            values = dev.read_env(path)
            self.assertNotEqual(values['APP_MODEL_SECRET_KEY'], values['APP_SHOP_CREDENTIAL_KEY'])
            self.assertGreaterEqual(len(values['APP_MODEL_SECRET_KEY']), 32)

    def test_stop_only_terminates_the_owned_process_group(self):
        owned = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], start_new_session=True)
        other = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], start_new_session=True)
        try:
            dev.stop_process(owned)
            self.assertIsNotNone(owned.poll())
            self.assertIsNone(other.poll())
        finally:
            for process in (owned, other):
                if process.poll() is None: process.terminate()
                process.wait(timeout=5)

if __name__ == '__main__':
    unittest.main()
