#!/usr/bin/env python3
"""Shared source watcher: keep real data, serialize reloads, never package code."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
from dev import SourceWatcher, workspace_lock


# The argument follows the launcher shell, Maven script, and JVM. A revoked
# single-workspace token also prevents a delayed docker exec from starting later.
LAUNCH_COMPILER = r'''
owner=$1
token=$2
shift 2
[ "$(cat "$token")" = "\"$owner\"" ] || exit 75
exec "$@"
'''

# Never kill Maven/Java by name: the app and other sessions live here too.
STOP_COMPILER = r'''
owner=$1
matches() {
  for file in /proc/[0-9]*/cmdline; do
    [ -r "$file" ] || continue
    pid=${file#/proc/}; pid=${pid%/cmdline}
    exe=$(readlink "/proc/$pid/exe" 2>/dev/null) || continue
    case "$exe" in */java|*/sh|*/dash|*/bash) ;; *) continue ;; esac
    if tr '\000' '\n' < "$file" 2>/dev/null | grep -Fqx -- "-Dheyi.sharedCompileOwner=$owner"; then
      printf '%s\n' "$pid"
    fi
  done
}
for pid in $(matches); do kill -TERM "$pid" 2>/dev/null || true; done
attempt=0
while [ -n "$(matches)" ] && [ "$attempt" -lt 20 ]; do
  sleep .1; attempt=$((attempt + 1))
done
for pid in $(matches); do kill -KILL "$pid" 2>/dev/null || true; done
attempt=0
while [ -n "$(matches)" ] && [ "$attempt" -lt 20 ]; do
  sleep .1; attempt=$((attempt + 1))
done
[ -z "$(matches)" ]
'''


def atomic(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.' + path.name, dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, sort_keys=True); stream.write('\n')
            stream.flush(); os.fsync(stream.fileno())
        os.replace(name, path)
        descriptor = os.open(path.parent, os.O_RDONLY)
        try: os.fsync(descriptor)
        finally: os.close(descriptor)
    finally:
        Path(name).unlink(missing_ok=True)


def tree_files(root):
    root = Path(root)
    if root.is_symlink(): raise RuntimeError('Redirected compiler output')
    result = {}
    if not root.exists(): return result
    for path in sorted(root.rglob('*')):
        if path.is_symlink(): raise RuntimeError('Linked compiler output')
        if path.is_file(): result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def migration_files(root):
    result = {}
    for directory in ('src/main/resources/db/migration', 'src/main/java/db/migration'):
        for relative, checksum in tree_files(Path(root) / directory).items():
            result[directory + '/' + relative] = checksum
    return result


class ReloadCoordinator:
    def __init__(self, config, store, host, runner=subprocess.run,
                 inspect_runtime=None, runtime_hash=None):
        self.config, self.store, self.host, self.runner = config, store, host, runner
        self.root = Path(config['sourceRoot']).resolve()
        self.state_file = self.root / '.dev/shared-reload.json'
        self.compiler_token = self.root / '.dev/compiler-owner.json'
        self.classes = self.root / 'target/classes'
        self.inspect_runtime, self.runtime_hash = inspect_runtime, runtime_hash
        self.lock = None; self.active = False; self.state = {}; self.watcher = None

    def _save(self, phase, **fields):
        self.state.update(phase=phase, updatedAt=int(time.time()), **fields)
        atomic(self.state_file, self.state)

    def _release_lock(self):
        if self.lock is not None:
            lock, self.lock = self.lock, None
            lock.__exit__(None, None, None)

    def _status(self):
        code, body = self.host.request('/_heyi/ops/status', authenticated=True)
        value = json.loads(body) if code == 200 else {}
        if value.get('protocol') != 1 or not isinstance(value.get('contextId'), str) or not value['contextId']:
            raise RuntimeError('Source application context is not ready')
        return value

    def _healthy(self):
        code, body = self.host.request('/actuator/health')
        return code == 200 and json.loads(body).get('status') == 'UP'

    def _wait(self, previous=None):
        excluded = {previous} if isinstance(previous, str) else set(previous or [])
        deadline = time.monotonic() + self.config.get('reloadTimeout', 120)
        while True:
            try:
                state = self._status()
                healthy = self._healthy()
            except (OSError, ValueError, RuntimeError):
                state, healthy = None, False
            if state and healthy and state['contextId'] not in excluded:
                if not state.get('maintenance'): raise RuntimeError('Reload gate was removed externally')
                return state
            if time.monotonic() >= deadline: raise RuntimeError('Source context restart timeout')
            time.sleep(.2)

    def _check(self):
        if self.host.schema() != self.config['schemaFingerprint']:
            raise RuntimeError('Database history changed; validate schema separately')
        if migration_files(self.root) != self.config['migrationFiles']:
            raise RuntimeError('Shared schema source changed; automatic DDL is disabled')
        if self.inspect_runtime and self.runtime_hash(self.inspect_runtime()) != self.config['runtimeConfigurationHash']:
            raise RuntimeError('Another session changed the runtime configuration')

    def before_reload(self):
        self.lock = self.store.exclusive(); self.lock.__enter__()
        try:
            if self.store.incomplete(): raise RuntimeError('Other maintenance needs recovery')
            if self.classes.resolve() != self.root / 'target/classes':
                raise RuntimeError('Redirected compiler output')
            self._check()
            previous = self._status()
            if previous.get('maintenance'): raise RuntimeError('Existing maintenance gate must be recovered first')
            owner = uuid.uuid4().hex
            self.state = {'owner': owner, 'createdAt': int(time.time()), 'contextBefore': previous['contextId'],
                          'classesBefore': tree_files(self.classes), 'compilerStopped': True}
            self._save('gating')
            self.host.gate(owner)
            self.active = True
            self._save('draining')
            self.host.drain(owner)
            backup = self.root / '.dev/shared-recovery' / owner / 'classes'
            backup.parent.mkdir(parents=True, mode=0o700)
            shutil.copytree(self.classes, backup)
            self._save('prepared', backupPath=str(backup))
            self._backup()
        except BaseException:
            if not self.active: self._release_lock()
            raise

    def compile(self):
        if not self.active: raise RuntimeError('Compile requires an owned safe point')
        self._backup()
        self._save('compiling', compilerStopped=False)
        if self.compiler_token.exists() and json.loads(self.compiler_token.read_text()) not in {None, self.state['owner']}:
            raise RuntimeError('Another compiler owns the workspace launch token')
        atomic(self.compiler_token, self.state['owner'])
        self.compiler_token.chmod(0o644)
        command = ['docker', 'exec', '-w', '/workspace', '-e', 'JAVA_HOME=' + self.config['javaHome'],
                   '-e', 'MAVEN_OPTS=-Xmx768m -Djava.awt.headless=true -Dheyi.sharedCompileOwner=' + self.state['owner'], self.config['appContainer'],
                   'sh', '-ec', LAUNCH_COMPILER, 'launch-owned-compiler', self.state['owner'],
                   '/workspace/.dev/compiler-owner.json', self.config['maven'],
                   '-Dheyi.sharedCompileOwner=' + self.state['owner'], '-B', '-ntp', '-s', '/workspace/maven-settings.xml',
                   '-Dmaven.repo.local=' + self.config['mavenRepository'], '-Pdev',
                   '-Dmaven.test.skip=true', 'compile']
        try:
            result = self.runner(command, check=False, timeout=180)
        finally:
            # docker exec client exit/timeout does not prove the container JVM died.
            # A failure here must leave classes untouched and maintenance gated.
            self._stop_compiler()
        self._save('compiled' if result.returncode == 0 else 'compile_failed',
                   classesAfter=tree_files(self.classes) if result.returncode == 0 else None)
        return result

    def _stop_compiler(self):
        owner = self.state.get('owner', '')
        if not re.fullmatch(r'[a-f0-9]{32}', owner): raise RuntimeError('Invalid compiler owner')
        if self.compiler_token.exists() and json.loads(self.compiler_token.read_text()) not in {None, owner}:
            raise RuntimeError('Another compiler owns the workspace launch token')
        atomic(self.compiler_token, None)
        self.compiler_token.chmod(0o644)
        info = self.inspect_runtime() if self.inspect_runtime else {}
        if info.get('State', {}).get('Running') is not False:
            self.runner(['docker', 'exec', self.config['appContainer'], 'sh', '-ec',
                         STOP_COMPILER, 'stop-owned-compiler', owner], check=True, timeout=15,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self._save(self.state['phase'], compilerStopped=True)

    def _backup(self):
        owner = self.state.get('owner', '')
        if not re.fullmatch(r'[a-f0-9]{32}', owner): raise RuntimeError('Invalid recovery owner')
        expected = self.root / '.dev/shared-recovery' / owner / 'classes'
        backup = Path(self.state.get('backupPath', ''))
        if (backup != expected or backup.resolve() != expected or not backup.is_dir()
                or backup.parent.stat().st_uid != os.geteuid()
                or backup.parent.stat().st_mode & 0o077
                or tree_files(backup) != self.state['classesBefore']):
            raise RuntimeError('Verified owned compiler-output backup is unavailable')
        return backup

    def _clear_classes(self):
        self.classes.mkdir(parents=True, exist_ok=True)
        for output in self.classes.iterdir():
            if output.name == '.reloadtrigger': continue
            if output.is_dir() and not output.is_symlink(): shutil.rmtree(output)
            else: output.unlink()

    def _restore(self, keep_trigger=False):
        if not self.state.get('compilerStopped'): raise RuntimeError('Compiler may still be writing output')
        backup = self._backup()
        if self.classes.resolve() != self.root / 'target/classes' or self.classes.is_symlink():
            raise RuntimeError('Redirected compiler output')
        self._save('restoring')  # Durable intent precedes any destructive class-output change.
        if keep_trigger:
            current_trigger = tree_files(self.classes).get('.reloadtrigger')
            if current_trigger != self.state['classesBefore'].get('.reloadtrigger'):
                raise RuntimeError('Trigger changed during failed compilation; explicit recovery required')
            self._clear_classes()
            shutil.copytree(backup, self.classes, dirs_exist_ok=True,
                            ignore=lambda directory, names: ['.reloadtrigger'] if Path(directory) == backup else [])
        else:
            if self.classes.exists(): shutil.rmtree(self.classes)
            shutil.copytree(backup, self.classes)
        self._ownership()
        if tree_files(self.classes) != self.state['classesBefore']:
            raise RuntimeError('Compiler output restore differs from verified backup')
        self._save('restored')

    def _restart_app(self):
        self._check()
        before = self.inspect_runtime() if self.inspect_runtime else {}
        command = ['docker', 'compose', '--env-file', '/dev/null', '-p', self.config['composeProject']]
        for file in self.config['composeFiles']: command += ['-f', file]
        command += ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate', 'app']
        self.runner(command, check=True, timeout=240)
        self._check()
        after = self.inspect_runtime() if self.inspect_runtime else {}
        if before.get('Id') and before['Id'] == after.get('Id'):
            raise RuntimeError('Application container was not recreated')

    def _finish(self, phase, context, **fields):
        self._save('releasing', terminalPhase=phase, contextAfter=context['contextId'],
                   classesVerified=tree_files(self.classes), **fields)
        self.host.ungate(self.state['owner'])
        self._save(phase)
        self.active = False
        self._cleanup_backup()

    def _cleanup_backup(self):
        if self.state.get('backupPath'):
            # Only this journal's validated backup; never scan/delete other sessions.
            # A cleanup failure cannot turn a verified, ungated context back into
            # a nonterminal recovery operation. Preserve the path for inspection.
            try: shutil.rmtree(self._backup().parent)
            except (OSError, RuntimeError): pass

    def _ownership(self):
        for path in [self.classes, *self.classes.rglob('*')]:
            if path.is_symlink(): raise RuntimeError('Linked compiler output')
            if os.geteuid() == 0:
                os.chown(path, self.config['uid'], self.config['gid'])
        state = self.root / '.dev/source-state.json'
        state.parent.chmod(0o755)
        if state.exists(): state.chmod(0o644)

    def _retain_backup(self):
        # The owner-specific backup is already durable before output is touched.
        self._save('needs_attention', interruptedPhase=self.state.get('interruptedPhase', self.state['phase']))

    def after_reload(self, success):
        if not self.active:
            self._release_lock(); return
        try:
            self._ownership()
            if success:
                self._save('restarting')
                if getattr(self.watcher, 'restart_required', False):
                    self._restart_app()
                state = self._wait(self.state['contextBefore'])
            else:
                state = self._wait()
                if state['contextId'] != self.state['contextBefore']:
                    raise RuntimeError('Failed compile no longer has its previous application context')
            self._finish('ready', state, lastCompileSucceeded=success)
        except BaseException:
            self._retain_backup()
            raise
        finally:
            self._release_lock()

    def recover(self):
        if not self.state_file.exists(): return {'phase': 'idle'}
        state = json.loads(self.state_file.read_text())
        with self.store.exclusive():
            self.state = state
            if state.get('phase') in {'ready', 'recovered'}:
                self._cleanup_backup()
                return {'phase': state['phase']}
            owner = state['owner']
            self._check()
            phase = state.get('interruptedPhase', state['phase']) if state['phase'] == 'needs_attention' else state['phase']
            if phase == 'releasing':
                current = self._status()
                if (current['contextId'] != state['contextAfter'] or not self._healthy()
                        or tree_files(self.classes) != state['classesVerified']):
                    raise RuntimeError('Previously verified context changed before gate release')
                self._finish(state['terminalPhase'], current)
                return {'phase': state['terminalPhase']}
            if phase in {'gating', 'draining'}:
                # The journal is written before gating/backup, and no compiler
                # output is touched until the durable prepared phase exists.
                current = self._status()
                if (current['contextId'] != state['contextBefore'] or not self._healthy()
                        or tree_files(self.classes) != state['classesBefore']):
                    raise RuntimeError('Precompile state changed during interruption')
                self._finish('recovered', current)
                return {'phase': 'recovered'}
            if not self.host.owns_gate(owner):
                raise RuntimeError('Interrupted source reload has no owned gate')
            self._stop_compiler()
            self._restore()
            excluded = {state['contextBefore']}
            try: excluded.add(self._status()['contextId'])
            except (OSError, ValueError, RuntimeError): pass
            self._save('recovery_restarting')
            self._restart_app()
            current = self._wait(excluded)
            self._finish('recovered', current)
            return {'phase': 'recovered'}


class SharedSourceWatcher(SourceWatcher):
    """Shared output cannot roll back until the container's compiler is stopped."""
    def __init__(self, root, coordinator):
        self.coordinator = coordinator
        super().__init__(root, coordinator.compile)

    def _write_revision(self, revision):
        super()._write_revision(revision)
        self.state_file.parent.chmod(0o755)
        self.state_file.chmod(0o644)

    def _compile(self, previous, current, fresh=False):
        coordinator = self.coordinator
        success = False
        try:
            coordinator.before_reload()
            self.backup_path = coordinator._backup()
            coordinator._save('mutating')
            if fresh:
                coordinator._clear_classes()
            else:
                self._remove_deleted(previous, current, coordinator.classes)
            try:
                success = coordinator.compile().returncode == 0
            except Exception as error:
                self.compile_error = type(error).__name__
                if not coordinator.state.get('compilerStopped'): raise
            if success:
                self.trigger_file.write_text(str(time.time_ns()) + '\n')
                self.compile_error = None
            else:
                coordinator._restore(keep_trigger=True)
                self.compile_error = self.compile_error or 'Compilation failed'
            coordinator.after_reload(success)
            return success
        except BaseException:
            if coordinator.active:
                coordinator._retain_backup()
            raise
        finally:
            coordinator._release_lock()
            self.backup_path = None


def context(config):
    sys.path.insert(0, config['opsProgram'])
    from heyi_ops.state import Store, read
    from heyi_ops.host import Host, inspect, config_hash
    current = read(config['opsConfig'])
    store = Store(current['root'], current['lockPath'], current.get('compatibilityLockPath'))
    host = Host(current)
    return ReloadCoordinator(config, store, host,
        inspect_runtime=lambda: inspect(config['appContainer']), runtime_hash=config_hash)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['watch', 'recover', 'status'])
    parser.add_argument('--config', default='/etc/heyi-dev/runtime.json')
    args = parser.parse_args(argv)
    config = json.loads(Path(args.config).read_text())
    root = Path(config['sourceRoot']).resolve()
    coordinator = context(config)
    if args.action == 'recover':
        print(json.dumps(coordinator.recover())); return 0
    if args.action == 'status':
        print(json.dumps({'mode': 'source-development', 'sourceRoot': str(root),
            'healthy': coordinator._healthy(), 'context': coordinator._status(),
            'legacyRelease': config.get('legacyRelease'),
            'reload': {key: value for key, value in json.loads(coordinator.state_file.read_text()).items() if key in {'phase','owner','updatedAt','lastCompileSucceeded'}} if coordinator.state_file.exists() else None})); return 0
    with workspace_lock(root):
        coordinator.recover()
        watcher = SharedSourceWatcher(root, coordinator)
        coordinator.watcher = watcher
        if not watcher.prepare(): raise RuntimeError('Initial shared source compilation failed')
        watcher.run()
    return 0


if __name__ == '__main__':
    try: raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(json.dumps({'phase': 'source_reload_stopped', 'errorType': type(error).__name__}), file=sys.stderr)
        raise SystemExit(1)
