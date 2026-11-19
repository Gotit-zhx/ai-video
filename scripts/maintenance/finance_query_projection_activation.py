#!/usr/bin/env python3
"""Explicit V155 maintenance. Default inspect mode is local and never changes a host.

The coordinator consumes an operation directory prepared by the source integrator:
  inputs.json: operationId (fresh 32 hex), runtimeHash, appId, helperSha256
  manifest.json: {relative source path: {beforeHash: sha256|null, targetHash: sha256}}
  payload/<relative path>: exactly the approved replacement bytes
No worktree upload, credentials, runtime overrides, schema repair or DDL replay.
Run --help for explicit host modes. A nonterminal state requires human inspection.
"""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import shared_dev

SQL = 'src/main/resources/db/migration/V155__finance_query_projections.sql'
SQL_SHA = '9038f70e0d19bcf03cd6609c0e43827f76cf8eea9c7e67d7734cd2d3cfe9a14d'
HELPER = 'FinanceQueryProjectionSchemaMaintenance.java'
HISTORY = "SELECT installed_rank,COALESCE(version,''),type,script,COALESCE(checksum,''),success FROM flyway_schema_history ORDER BY installed_rank"
CANONICAL_ROOT = Path('/opt/heyi/dev/ai-video')
CANONICAL_CONFIG = Path('/etc/heyi-dev/runtime.json')
SERVICE = 'heyi-source-watch.service'
OWNER_PROPERTY = 'heyi.financeProjectionSchemaOwner'


def sha(data): return hashlib.sha256(data).hexdigest()


def read_json(path): return json.loads(Path(path).read_text())


def file_hash(path):
    if path.is_symlink(): raise RuntimeError('Symlink source refused')
    return sha(path.read_bytes()) if path.is_file() else None


class Activation:
    SQL = SQL
    SQL_SHA = SQL_SHA
    HELPER = HELPER
    VERSION = '155'
    PREVIOUS_VERSION = '154'

    def __init__(self, operation, config=CANONICAL_CONFIG, root=CANONICAL_ROOT, helper=None):
        requested_op = Path(operation).absolute()
        if requested_op.is_symlink(): raise RuntimeError('Linked operation directory refused')
        self.op = requested_op.resolve(); self.root = Path(root).absolute(); self.config_path = Path(config).absolute()
        self.helper = Path(helper) if helper else self.root / 'scripts/qa/java' / self.HELPER
        if self.op.is_symlink() or self.op.resolve() != self.op or not self.op.is_dir():
            raise RuntimeError('A real fresh operation directory is required')
        if self.op.stat().st_mode & 0o077 or self.op.stat().st_uid != os.geteuid():
            raise RuntimeError('Operation directory must be private (0700) and owned by this process user')
        self.manifest = read_json(self.op / 'manifest.json'); self.inputs = read_json(self.op / 'inputs.json')
        if not re.fullmatch('[a-f0-9]{32}', self.inputs.get('operationId', '')): raise RuntimeError('Invalid operation identity')
        self.owner = self.inputs['operationId']
        if not self.manifest or self.SQL not in self.manifest:
            raise RuntimeError('V' + self.VERSION + ' and an explicit source manifest are required')
        for name, item in self.manifest.items():
            path = PurePosixPath(name)
            if (path.is_absolute() or '..' in path.parts or str(path) != name
                    or not (name.startswith('src/main/java/') or name.startswith('frontend/') or name == self.SQL)):
                raise RuntimeError('Manifest path outside approved source boundary')
            if set(item) != {'beforeHash', 'targetHash'}: raise RuntimeError('Unexpected manifest fields')
            if item['beforeHash'] is not None and not re.fullmatch('[a-f0-9]{64}', item['beforeHash']): raise RuntimeError('Invalid before hash')
            if not re.fullmatch('[a-f0-9]{64}', item['targetHash']): raise RuntimeError('Invalid target hash')
            self.safe_path(self.root, name); self.safe_path(self.op / 'payload', name)
        if self.manifest[self.SQL] != {'beforeHash': None, 'targetHash': self.SQL_SHA}:
            raise RuntimeError('Only the fixed additive V' + self.VERSION + ' is accepted')
        for name in ('runtimeHash', 'helperSha256'):
            if not re.fullmatch('[a-f0-9]{64}', self.inputs.get(name, '')): raise RuntimeError('Missing input hash')
        if not self.inputs.get('appId'): raise RuntimeError('Application identity is required')
        self.manifest_hash = sha((self.op / 'manifest.json').read_bytes())
        self.inputs_hash = sha((self.op / 'inputs.json').read_bytes())
        self.runtime = shared_dev.context

    @staticmethod
    def safe_path(root, relative):
        path = root / relative
        if path.resolve() != path.absolute(): raise RuntimeError('Redirected source or payload path')
        return path

    def state(self, phase, **fields):
        path = self.op / 'state.json'; value = read_json(path) if path.exists() else {}
        value.update(phase=phase, updatedAt=int(time.time()), **fields); shared_dev.atomic(path, value)

    def claim_operation(self):
        # O_EXCL prevents two launchers sharing an operation ID from removing each other's reservation.
        descriptor = os.open(self.op / 'state.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as stream:
            json.dump({'phase': 'reserving', 'operationId': self.owner}, stream); stream.write('\n')
            stream.flush(); os.fsync(stream.fileno())
        directory = os.open(self.op, os.O_RDONLY)
        try: os.fsync(directory)
        finally: os.close(directory)

    def inspect(self, config):
        return json.loads(subprocess.check_output(['docker', 'inspect', config['appContainer']], timeout=15))[0]

    def verify_inputs(self):
        if sha((self.op / 'manifest.json').read_bytes()) != self.manifest_hash or sha((self.op / 'inputs.json').read_bytes()) != self.inputs_hash:
            raise RuntimeError('Operation inputs changed')
        if file_hash(self.helper) != self.inputs['helperSha256']: raise RuntimeError('Maintenance helper changed')
        for name, item in self.manifest.items():
            if file_hash(self.safe_path(self.op / 'payload', name)) != item['targetHash']:
                raise RuntimeError('Approved payload changed')

    def verify(self, config):
        self.verify_inputs()
        if file_hash(self.config_path) != self.inputs['runtimeHash']: raise RuntimeError('Runtime configuration changed')
        if config['sourceRoot'] != str(self.root): raise RuntimeError('Unexpected source root')
        if self.inspect(config)['Id'] != self.inputs['appId']: raise RuntimeError('Application identity changed')
        for name, item in self.manifest.items():
            if file_hash(self.safe_path(self.root, name)) != item['beforeHash']:
                raise RuntimeError('Source baseline changed: ' + name)

    def stop_watcher(self):
        subprocess.run(['systemctl', 'stop', SERVICE], check=True, capture_output=True, timeout=60)
        result = subprocess.run(['systemctl', 'is-active', SERVICE], check=False, capture_output=True, text=True, timeout=15)
        if result.returncode != 3 or result.stdout.strip() != 'inactive': raise RuntimeError('Watcher did not stop cleanly')

    def start_watcher(self):
        subprocess.run(['systemctl', 'start', SERVICE], check=True, capture_output=True, timeout=60)

    def reserve_idle_watcher(self, r, config):
        # ExecStopPost invokes recover(), which takes this same Store lock.
        # Never call systemctl stop from within this short reservation lock.
        with r.store.exclusive():
            self.verify(config); r._check()
            journal = read_json(r.state_file) if r.state_file.exists() else {}
            if (r.store.incomplete() or journal.get('phase') not in {'ready', 'recovered'}
                    or r._status().get('maintenance') or not r._healthy() or r.host.marker.exists()):
                raise RuntimeError('Existing maintenance must finish before watcher stop')
            shared_dev.atomic(self.op / 'reservation.json', {'owner': self.owner, 'phase': 'gating'})
            r.host.gate(self.owner)
            shared_dev.atomic(self.op / 'reservation.json', {'owner': self.owner, 'phase': 'gated'})

    def release_idle_reservation(self, r, required=True):
        with r.store.exclusive():
            marker = read_json(r.host.marker) if r.host.marker.exists() else None
            if not required and (not marker or marker.get('owner') != self.owner): return
            if not r.host.owns_gate(self.owner): raise RuntimeError('Owned watcher reservation disappeared')
            r.host.ungate(self.owner)

    def wait_watcher(self, r, previous_context, previous_owner):
        deadline = time.monotonic() + 240
        while True:
            service = subprocess.run(['systemctl', 'is-active', SERVICE], check=False, capture_output=True, text=True, timeout=15)
            if service.stdout.strip() == 'failed': raise RuntimeError('Watcher preparation failed')
            try:
                current = r._status(); journal = read_json(r.state_file)
                if (service.returncode == 0 and service.stdout.strip() == 'active'
                        and journal.get('phase') == 'ready' and journal.get('owner')
                        and journal['owner'] != previous_owner and current['contextId'] != previous_context
                        and journal.get('contextAfter') == current['contextId']
                        and not current.get('maintenance') and not r.host.marker.exists() and r._healthy()):
                    return current
            except (OSError, ValueError, RuntimeError): pass
            if time.monotonic() >= deadline: raise RuntimeError('Watcher readiness timeout')
            time.sleep(.5)

    def classpath(self, config):
        for line in subprocess.check_output(['docker', 'top', config['appContainer'], '-eo', 'pid,comm'], text=True, timeout=15).splitlines()[1:]:
            fields = line.split()
            if len(fields) < 2 or fields[1] != 'java': continue
            args = (Path('/proc') / fields[0] / 'cmdline').read_bytes().decode().split('\0')
            for flag in ('-cp', '-classpath'):
                if flag in args and args[args.index(flag) + 1].startswith('/workspace/target/classes'):
                    return args[args.index(flag) + 1]
        raise RuntimeError('Running application dependency classpath unavailable')

    def stop_java(self, config, owner, token, directory):
        if not re.fullmatch('[a-f0-9]{32}', owner): raise RuntimeError('Invalid helper owner')
        # Revoke before process inspection: delayed docker exec cannot launch later.
        subprocess.run(['docker', 'exec', '-u', str(config['uid']) + ':' + str(config['gid']), config['appContainer'],
                        'sh', '-ec', 'printf "null\\n" > "$1"', 'revoke-owned-helper', token],
                       check=True, timeout=15, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        stop = shared_dev.STOP_COMPILER.replace('heyi.sharedCompileOwner', OWNER_PROPERTY)
        subprocess.run(['docker', 'exec', config['appContainer'], 'sh', '-ec', stop, 'stop-owned-projection-helper', owner],
                       check=True, timeout=15, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        recorded = subprocess.run(['docker', 'exec', config['appContainer'], 'sh', '-ec',
                                   'if [ -f "$1" ]; then cat "$1"; fi', 'read-owned-connections', directory + '/' + owner + '.connections'],
                                  check=True, capture_output=True, text=True, timeout=15)
        ids = sorted(set(recorded.stdout.splitlines()))
        if any(not re.fullmatch('[1-9][0-9]*', identifier) for identifier in ids): raise RuntimeError('Invalid owned connection record')
        host = self.runtime(config).host
        for identifier in ids:
            alive = 'SELECT COUNT(*) FROM information_schema.processlist WHERE ID=' + identifier
            if host.sql(alive) == '0': continue
            owned = ("SELECT COUNT(*) FROM performance_schema.session_connect_attrs WHERE PROCESSLIST_ID=" + identifier
                     + " AND ATTR_NAME='heyi_finance_projection_owner' AND ATTR_VALUE='" + owner + "'")
            if host.sql(owned) != '1': raise RuntimeError('Live connection ownership cannot be proven')
            try: host.sql('KILL CONNECTION ' + identifier)
            except Exception:
                if host.sql(alive) != '0': raise
        deadline = time.monotonic() + 15
        while ids and host.sql('SELECT COUNT(*) FROM information_schema.processlist WHERE ID IN (' + ','.join(ids) + ')') != '0':
            if time.monotonic() >= deadline: raise RuntimeError('Owned helper connection did not terminate')
            time.sleep(.2)

    def run_java(self, config, mode):
        if mode not in {'validate', 'migrate'}: raise RuntimeError('Invalid Flyway mode')
        self.verify_inputs()
        owner = os.urandom(16).hex(); directory = '/tmp/heyi-finance-projection-' + self.owner + '-' + owner
        token = directory + '/launch.json'; app_user = str(config['uid']) + ':' + str(config['gid'])
        base = ['docker', 'exec', '-u', app_user, '-i', config['appContainer']]
        subprocess.run(base + ['sh', '-ec', 'umask 077; mkdir "$1"; mkdir "$1/migration"', 'create-owned-helper', directory],
                       check=True, capture_output=True, timeout=15)
        files = [(directory + '/' + self.HELPER, self.helper.read_bytes()),
                 (directory + '/migration/' + Path(self.SQL).name, (self.op / 'payload' / self.SQL).read_bytes()),
                 (token, json.dumps(owner).encode() + b'\n')]
        for target, data in files:
            subprocess.run(base + ['sh', '-ec', 'umask 077; set -C; cat > "$1"', 'write-owned-helper', target],
                           input=data, check=True, capture_output=True, timeout=15)
        args = base + ['sh', '-ec', shared_dev.LAUNCH_COMPILER, 'launch-owned-projection-helper', owner, token,
                       config['javaHome'] + '/bin/java', '-D' + OWNER_PROPERTY + '=' + owner, '-Xms16m', '-Xmx192m',
                       '-XX:MaxMetaspaceSize=128m', '-XX:ActiveProcessorCount=2', '--class-path', self.classpath(config),
                       directory + '/' + self.HELPER, mode, directory + '/migration', token]
        env = dict(value.split('=', 1) for value in self.inspect(config)['Config']['Env'])
        values = {key: env[name] for key, name in [('url', 'DB_URL'), ('user', 'DB_USERNAME'), ('password', 'DB_PASSWORD')]}
        values['owner'] = owner
        shared_dev.atomic(self.op / 'helper-launch.json', {'owner': owner, 'directory': directory, 'token': token, 'mode': mode})
        try:
            result = subprocess.run(args, input=json.dumps(values).encode(), capture_output=True, timeout=900 if mode == 'migrate' else 180)
        finally:
            blocked = {signal.SIGHUP, signal.SIGTERM, signal.SIGINT}
            previous = signal.pthread_sigmask(signal.SIG_BLOCK, blocked)
            try: self.stop_java(config, owner, token, directory)
            finally: signal.pthread_sigmask(signal.SIG_SETMASK, previous)
        summaries = []
        for line in result.stdout.decode(errors='replace').splitlines():
            if line.startswith('{'):
                try: summaries.append(json.loads(line))
                except ValueError: pass
        summary = summaries[-1] if summaries else {'success': False, 'failureType': 'NoSanitizedResult'}
        allowed = {'success', 'mode', 'beforeVersion', 'executed', 'oldRuntimeValidationPassed', 'historyPrefixPreserved', 'failureType'}
        summary = {key: value for key, value in summary.items() if key in allowed}
        shared_dev.atomic(self.op / ('flyway-' + mode + '.json'), summary)
        if result.returncode or not summary.get('success'): raise RuntimeError('Flyway helper rejected operation')
        return summary

    def preserve_classes(self, r):
        destination = self.op / 'classes-before'
        if destination.exists(): raise RuntimeError('Independent recovery output already exists')
        shutil.copytree(r._backup(), destination)
        if shared_dev.tree_files(destination) != r.state['classesBefore']: raise RuntimeError('Independent class backup differs')
        shared_dev.atomic(self.op / 'classes-before.json', r.state['classesBefore'])

    def unrelated_sources(self):
        names = set(subprocess.check_output(['git', 'diff', 'HEAD', '--name-only'], cwd=self.root, text=True).splitlines()
                    + subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=self.root, text=True).splitlines()) - set(self.manifest)
        result = {}
        for name in sorted(names):
            path = self.root / name
            result[name] = {'link': sha(os.readlink(path).encode())} if path.is_symlink() else {'file': file_hash(path)}
        result['[git-staging-digest]'] = sha(subprocess.check_output(['git', 'diff', '--cached', '--binary'], cwd=self.root))
        return result

    def put(self, name, data, config, expected):
        path = self.safe_path(self.root, name)
        if file_hash(path) != expected: raise RuntimeError('CAS refused changed source: ' + name)
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '.projection-', dir=path.parent)
        try:
            with os.fdopen(fd, 'wb') as stream: stream.write(data); stream.flush(); os.fsync(stream.fileno())
            os.chmod(temporary, 0o644)
            if os.geteuid() == 0: os.chown(temporary, config['uid'], config['gid'])
            if file_hash(path) != expected: raise RuntimeError('CAS refused late source drift: ' + name)
            os.replace(temporary, path)
            descriptor = os.open(path.parent, os.O_RDONLY)
            try: os.fsync(descriptor)
            finally: os.close(descriptor)
        finally: Path(temporary).unlink(missing_ok=True)

    def save_before(self):
        for name, item in self.manifest.items():
            if item['beforeHash'] is not None:
                source = self.safe_path(self.root, name)
                if file_hash(source) != item['beforeHash']: raise RuntimeError('Source drift before backup')
                target = self.op / 'before' / name; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(source.read_bytes())

    def apply_sources(self, config):
        self.verify_inputs()
        for name, item in self.manifest.items():
            if file_hash(self.safe_path(self.root, name)) != item['beforeHash']: raise RuntimeError('CAS baseline changed')
        # SQL is installed first and is never removed after an attempted migration.
        for name in [self.SQL] + [name for name in self.manifest if name != self.SQL]:
            item = self.manifest[name]
            self.put(name, (self.op / 'payload' / name).read_bytes(), config, item['beforeHash'])

    def restore_sources(self, config):
        for name, item in self.manifest.items():
            if name == self.SQL: continue
            path = self.safe_path(self.root, name); current = file_hash(path)
            if current == item['beforeHash']: continue
            if current != item['targetHash']: raise RuntimeError('Foreign source drift prevents restoration')
            if item['beforeHash'] is None: path.unlink()
            else:
                before = (self.op / 'before' / name).read_bytes()
                if sha(before) != item['beforeHash']: raise RuntimeError('Before-source backup changed')
                self.put(name, before, config, item['targetHash'])

    def update_runtime_schema(self, r, original):
        # Compare-and-swap config, then change only these two guard fields.
        if file_hash(self.config_path) != self.inputs['runtimeHash']: raise RuntimeError('Runtime configuration drift')
        updated = dict(original, schemaFingerprint=r.host.schema(), migrationFiles=shared_dev.migration_files(self.root))
        metadata = self.config_path.stat()
        shared_dev.atomic(self.config_path, updated); self.config_path.chmod(metadata.st_mode & 0o777)
        if os.geteuid() == 0: os.chown(self.config_path, metadata.st_uid, metadata.st_gid)
        r.config = updated; r._check()
        return updated

    def trigger(self, r):
        r._ownership(); r._check(); r.watcher = shared_dev.SharedSourceWatcher(self.root, r)
        r.watcher.trigger_file.write_text(str(time.time_ns()) + '\n')

    def check_static(self):
        for name, item in self.manifest.items():
            if not name.startswith('frontend/'): continue
            result = subprocess.check_output(['curl', '--fail', '--silent', '--show-error', '--max-time', '15', '--compressed',
                        '--resolve', 'hoyee.org.cn:443:127.0.0.1', 'https://hoyee.org.cn/' + name.removeprefix('frontend/')], timeout=20)
            if sha(result) != item['targetHash']: raise RuntimeError('Served frontend differs')

    def retain_pre_migration_failure(self, failure):
        return False

    def contain_late_failure(self, config):
        stopped = True
        try: self.stop_watcher()
        except (OSError, RuntimeError, subprocess.SubprocessError): stopped = False
        recovery = self.runtime(config)
        with recovery.store.exclusive():
            existing = read_json(recovery.host.marker) if recovery.host.marker.exists() else None
            # The control marker is the durable safety record. An unavailable
            # operation receipt volume must never block the actual gate.
            if not existing: recovery.host.gate(self.owner)
            details = {'phase': 'gated', 'owner': existing['owner'] if existing else self.owner,
                       'existingGatePreserved': bool(existing), 'watcherStopConfirmed': stopped,
                       'independentBeforeClasses': str(self.op / 'classes-before')}
            try: shared_dev.atomic(self.op / 'late-failure-gate.json', details)
            except OSError: pass

    def retain_owned_failure(self, r):
        # active describes the reload phase, not lock ownership: after_reload
        # always releases its Store lock, including on a failed ready journal.
        lock = contextlib.nullcontext() if r.lock is not None else r.store.exclusive()
        with lock:
            owner = r.state.get('owner')
            if not owner or not re.fullmatch('[a-f0-9]{32}', owner): raise RuntimeError('Missing recovery owner')
            marker = read_json(r.host.marker) if r.host.marker.exists() else None
            journal = read_json(r.state_file) if r.state_file.exists() else {}
            # Another owner may have acquired the Store while our lock was
            # released. Preserve its marker and shared recovery journal.
            if (marker and marker.get('owner') != owner) or journal.get('owner') != owner: return
            if not marker: r.host.gate(owner)
            elif not r.host.owns_gate(owner): raise RuntimeError('Owned recovery gate disappeared')
            r._retain_backup()

    def preflight(self):
        if (self.op / 'state.json').exists() or (self.op / 'preflight.json').exists(): raise RuntimeError('Use a fresh operation directory')
        config = read_json(self.config_path); r = self.runtime(config)
        with r.store.exclusive():
            self.verify(config); r._check()
            journal = read_json(r.state_file) if r.state_file.exists() else {}
            if (r.store.incomplete() or journal.get('phase') not in {'ready', 'recovered'}
                    or r.host.marker.exists() or r._status().get('maintenance') or not r._healthy()):
                raise RuntimeError('Maintenance baseline is not ready')
            result = self.run_java(config, 'validate')
            shared_dev.atomic(self.op / 'preflight.json', {'phase': 'passed', 'manifestHash': self.manifest_hash,
                'inputsHash': self.inputs_hash, 'schemaFingerprint': r.host.schema(), 'flyway': result})

    def deploy(self):
        if (self.op / 'state.json').exists() or (self.op / 'verified.json').exists(): raise RuntimeError('Existing operation is never replayed')
        preflight = read_json(self.op / 'preflight.json')
        if preflight.get('phase') != 'passed' or preflight.get('manifestHash') != self.manifest_hash or preflight.get('inputsHash') != self.inputs_hash:
            raise RuntimeError('Matching preflight acceptance is required')
        config = read_json(self.config_path); r = self.runtime(config)
        self.verify(config); r._check()
        workspace = None; reservation = False; stopped = False; attempted = False; migrated = False; applied = False; released = False; release_started = False; completed = False
        self.claim_operation()
        try:
            # Mark intent before gate creation, so an interrupted reservation is inspectable.
            reservation = True; self.reserve_idle_watcher(r, config)
            self.state('stopping-watcher'); stopped = True; self.stop_watcher()
            pending_workspace = shared_dev.workspace_lock(self.root); pending_workspace.__enter__(); workspace = pending_workspace
            self.release_idle_reservation(r); reservation = False
            r.before_reload(); self.preserve_classes(r)
            self.state('gated', gateOwner=r.state['owner'], contextBefore=r.state['contextBefore'])
            self.verify(config); self.save_before(); unrelated = self.unrelated_sources()
            history = r.host.sql(HISTORY).splitlines()
            self.run_java(config, 'validate')
            self.state('migrating', migrationAttempted=True); attempted = True
            result = self.run_java(config, 'migrate')
            after = r.host.sql(HISTORY).splitlines()
            if (len(after) != len(history) + 1 or after[:-1] != history
                    or after[-1].split('\t')[1:4] != [self.VERSION, 'SQL', Path(self.SQL).name] or after[-1].split('\t')[-1] != '1'):
                raise RuntimeError('Migration history prefix or target changed')
            shared_dev.atomic(self.op / 'schema-verified.json', {'addedVersion': self.VERSION, 'historyPrefixPreserved': True,
                'beforeSchemaFingerprint': config['schemaFingerprint'], 'afterSchemaFingerprint': r.host.schema(),
                'oldRuntimeValidationPassed': result['oldRuntimeValidationPassed']})
            migrated = True
            self.apply_sources(config); applied = True
            updated = self.update_runtime_schema(r, config)
            if unrelated != self.unrelated_sources(): raise RuntimeError('Other-session source changed')
            self.state('compiling', migrationVerified=True, sourceApplied=True)
            original_runner = r.runner
            def bounded(args, **kwargs):
                if 'compile' in args: kwargs.update(stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return original_runner(args, **kwargs)
            r.runner = bounded
            try: compiled = r.compile()
            finally: r.runner = original_runner
            if compiled.returncode:
                self.restore_sources(updated); r._restore(keep_trigger=True)
                self.state('source-restored', schemaRetained=True, recoveryGateRetained=True, explicitRecoveryRequired=True)
                raise RuntimeError('Compile failed; V' + self.VERSION + ' and owned recovery gate retained with previous source/classes')
            self.trigger(r); self.state('restarting'); current = r._wait(r.state['contextBefore'])
            if self.inspect(updated)['Id'] != self.inputs['appId']: raise RuntimeError('Application identity changed')
            for name, item in self.manifest.items():
                if file_hash(self.safe_path(self.root, name)) != item['targetHash']: raise RuntimeError('Activated source changed')
            self.check_static()
            if unrelated != self.unrelated_sources(): raise RuntimeError('Other-session source changed during reload')
            if not r._healthy() or r.host.request('/api/v1/finance/shops')[0] != 503: raise RuntimeError('Maintenance guard or health failed')
            # Independent backup predates DDL and survives after_reload cleanup and watcher prepare.
            release_started = True
            r.after_reload(True); released = True
            self.state('context-ready', contextId=current['contextId'], gateReleased=True)
            workspace.__exit__(None, None, None); workspace = None
            self.start_watcher(); stopped = False
            current = self.wait_watcher(r, current['contextId'], r.state['owner'])
            r._check()
            if (read_json(self.config_path) != updated or self.inspect(updated)['Id'] != self.inputs['appId']
                    or unrelated != self.unrelated_sources()
                    or any(file_hash(self.safe_path(self.root, name)) != item['targetHash'] for name, item in self.manifest.items())):
                raise RuntimeError('Final source or runtime baseline changed')
            if r.host.request('/actuator/health')[0] != 200 or r.host.request('/api/v1/finance/shops')[0] != 401:
                raise RuntimeError('Final health or authentication failed')
            final = r._status()
            if (final['contextId'] != current['contextId'] or final.get('maintenance')
                    or r.host.marker.exists() or not r._healthy()):
                raise RuntimeError('Final ready context or maintenance changed')
            receipt = {'phase': 'activated', 'operationId': self.owner, 'schemaVersion': self.VERSION, 'schemaFingerprint': r.host.schema(),
                'contextBefore': r.state['contextBefore'], 'contextAfter': current['contextId'], 'watcherResumed': True,
                'sourceFiles': {name: item['targetHash'] for name, item in self.manifest.items()},
                'maintenance': False, 'checkedAt': int(time.time()), 'credentialsRedacted': True}
            self.state('activated')
            shared_dev.atomic(self.op / 'verified.json', receipt); completed = True
            return receipt
        except BaseException as failure:
            if not completed and (self.op / 'verified.json').exists():
                try: os.replace(self.op / 'verified.json', self.op / 'verification-interrupted.json')
                except OSError: pass
            # after_reload may remove its gate before a journal write fails; r.active then remains true.
            if (released or (release_started and not r.active)) and not completed:
                self.contain_late_failure(r.config); stopped = True; released = True
            elif attempted or self.retain_pre_migration_failure(failure):
                self.retain_owned_failure(r)
            elif r.active:
                r.after_reload(False)
            elif reservation:
                self.release_idle_reservation(r, required=False); reservation = False
            try:
                self.state('needs-attention', failureType=type(failure).__name__, migrationAttempted=attempted,
                           migrationVerified=migrated, sourceApplied=applied, watcherStopped=stopped)
            except OSError: pass  # Safety containment does not depend on this diagnostic receipt.
            if workspace is not None: workspace.__exit__(None, None, None); workspace = None
            if stopped and not attempted and not released and not r.active: self.start_watcher()
            raise
        finally:
            r._release_lock()
            if workspace is not None: workspace.__exit__(None, None, None)


def main(argv=None, activation_type=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('inspect', 'preflight', 'deploy'), nargs='?', default='inspect')
    parser.add_argument('--operation', type=Path, required=True)
    parser.add_argument('--root', type=Path, default=CANONICAL_ROOT)
    parser.add_argument('--config', type=Path, default=CANONICAL_CONFIG)
    parser.add_argument('--helper', type=Path)
    args = parser.parse_args(argv)
    activation_type = Activation if activation_type is None else activation_type
    activation = activation_type(args.operation, args.config, args.root, args.helper)
    if args.mode == 'inspect':
        activation.verify_inputs()
        print(json.dumps({'phase': 'local-inputs-verified', 'operationId': activation.owner,
                          'sourceFiles': len(activation.manifest), 'migrationVersion': activation.VERSION,
                          'migrationSha256': activation.SQL_SHA}))
        return
    if args.root != CANONICAL_ROOT or args.config != CANONICAL_CONFIG or os.geteuid() != 0:
        raise RuntimeError('Host modes require the canonical on-host source root and runtime configuration')
    if args.mode == 'preflight': activation.preflight()
    else: print(json.dumps(activation.deploy()))


if __name__ == '__main__':
    def interrupted(number, _): raise InterruptedError('Maintenance interrupted')
    for number in (signal.SIGHUP, signal.SIGTERM): signal.signal(number, interrupted)
    try: main()
    except BaseException as failure:
        if isinstance(failure, SystemExit): raise
        print(json.dumps({'phase': 'needs-attention', 'failureType': type(failure).__name__})); sys.exit(1)
