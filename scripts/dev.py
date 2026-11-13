#!/usr/bin/env python3
"""Run editable sources with Java 21; never package or build an application image."""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / '.cache/heyi-toolchains'
PUBLIC_EXTENSIONS = {'.html', '.js', '.mjs', '.css', '.json', '.svg', '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.woff', '.woff2', '.ttf', '.txt', '.zip'}


def read_env(path):
    """Parse literal KEY=value data, without shell evaluation or interpolation."""
    values = {}
    for number, line in enumerate(Path(path).read_text().splitlines(), 1):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, separator, value = line.partition('=')
        if not separator or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key):
            raise ValueError('Invalid environment key on line %s' % number)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
            value = value[1:-1]
        values[key] = value
    return values


def init_env(root):
    path = Path(root) / '.env.development'
    values = {
        'MYSQL_DATABASE': 'ai_commerce_dev', 'MYSQL_USER': 'devuser',
        'DEV_MYSQL_PORT': '3307', 'DEV_REDIS_PORT': '6380', 'APP_PORT': '8080',
        'APP_SESSION_COOKIE_SECURE': 'false', 'APP_ALLOW_INSECURE_PUBLIC_HTTP': 'true',
        'APP_PUBLIC_BASE_URL': 'http://localhost:8080',
        'APP_SHOP_AUTH_FRONTEND_BASE_URL': 'http://localhost:8080',
        'APP_DISPATCH_ENABLED': 'false',
    }
    for key in ('MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'REDIS_PASSWORD', 'APP_ADMIN_PASSWORD',
                'APP_MODEL_SECRET_KEY', 'APP_SHOP_CREDENTIAL_KEY', 'APP_AUTOMATION_AGENT_TOKEN',
                'APP_PI_RUNTIME_TOKEN', 'APP_VIDEO_REFERENCE_LINK_SECRET'):
        values[key] = secrets.token_hex(32)
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        return path
    with os.fdopen(descriptor, 'w') as stream:
        stream.write('# Private local development values. Never source this file in a shell.\n')
        stream.writelines(key + '=' + value + '\n' for key, value in values.items())
    return path


def runtime_env(root):
    env = read_env(init_env(root))
    env.update(os.environ)
    env.setdefault('DB_URL', 'jdbc:mysql://127.0.0.1:%s/%s?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false' % (env.get('DEV_MYSQL_PORT', '3307'), env.get('MYSQL_DATABASE', 'ai_commerce_dev')))
    env.setdefault('DB_USERNAME', env.get('MYSQL_USER', 'devuser'))
    env.setdefault('DB_PASSWORD', env['MYSQL_PASSWORD'])
    env.setdefault('REDIS_HOST', '127.0.0.1')
    env.setdefault('REDIS_PORT', env.get('DEV_REDIS_PORT', '6380'))
    # Local business files are never written to production-like /data directories.
    for key, directory in (('APP_AUTOMATION_SKILL_STORAGE', 'automation-skills'),
                           ('APP_AUTOMATION_ATTACHMENT_STORAGE', 'automation-attachments'),
                           ('APP_VIDEO_SCRIPT_STORAGE', 'video-scripts'), ('APP_AI_VIDEO_TASK_STORAGE', 'ai-video-tasks')):
        env.setdefault(key, str(Path(root) / '.dev/data' / directory))
    env['HEYI_DEV_STATE_FILE'] = str(Path(root) / '.dev/source-state.json')
    # Remote DevTools must stay disabled, including inherited shell variables.
    for key in ('SPRING_DEVTOOLS_REMOTE_SECRET', 'SPRING_DEVTOOLS_REMOTE_SECRET_HEADER_NAME'):
        env.pop(key, None)
    return env


def toolchain(env):
    fallback = CACHE / 'temurin-21.0.9+10-macos-aarch64/jdk-21.0.9+10/Contents/Home'
    home = Path(env.get('JAVA_HOME', str(fallback)))
    java = home / 'bin/java'
    if not java.is_file():
        raise RuntimeError('Java 21 unavailable: set JAVA_HOME or install the documented cached JDK.')
    result = subprocess.run([str(java), '-version'], capture_output=True, text=True)
    if result.returncode or not re.search(r'version "21(?:[.\"])', result.stderr + result.stdout):
        raise RuntimeError('JAVA_HOME must select Java 21.')
    env['JAVA_HOME'] = str(home)
    env['PATH'] = str(home / 'bin') + os.pathsep + env.get('PATH', '')
    configured = env.get('MAVEN') or env.get('MAVEN_HOME')
    if configured:
        candidate = Path(configured)
        maven = str(candidate / 'bin/mvn') if candidate.is_dir() else (shutil.which(configured) or configured)
    else:
        maven = str(CACHE / 'apache-maven-3.9.11/bin/mvn')
    version = subprocess.run([maven, '-version'], env=env, capture_output=True, text=True)
    if version.returncode or not re.search(r'Apache Maven 3\.9\.11(?:\s|$)', version.stdout):
        raise RuntimeError('Maven 3.9.11 is required; set MAVEN or MAVEN_HOME.')
    repository = Path(env.get('MAVEN_REPOSITORY', str(CACHE / 'maven-repository')))
    repository.mkdir(parents=True, exist_ok=True)
    return [maven, '-B', '-ntp', '-s', str(ROOT / 'maven-settings.xml'), '-Dmaven.repo.local=' + str(repository), '-Pdev']


def ensure_modern_frontend(root, env):
    """Compile optional React sources before launch; no Node server runs in production."""
    root = Path(root)
    modern = root / 'frontend/modern'
    if not (modern / 'package.json').is_file():
        return False
    state = root / '.dev'
    state.mkdir(parents=True, exist_ok=True)
    lock_hash = hashlib.sha256((modern / 'package-lock.json').read_bytes()).hexdigest()
    installed = state / 'finance-modern-deps.sha256'
    source_state = state / 'finance-modern-source.sha256'
    if not (modern / 'node_modules/vite/package.json').is_file() or not installed.is_file() or installed.read_text() != lock_hash:
        subprocess.run(['npm', '--prefix', str(modern), 'ci', '--ignore-scripts'], cwd=root, env=env, check=True)
        installed.write_text(lock_hash)
    candidates = list(modern.glob('*')) + list((modern / 'src').rglob('*'))
    sources = {str(path.relative_to(modern)): hashlib.sha256(path.read_bytes()).hexdigest()
               for path in sorted(candidates) if path.is_file() and not path.is_symlink()
               and not any(part.startswith('.') for part in path.relative_to(modern).parts)}
    for path in (root / 'frontend/modern-build.mjs', root / 'frontend/i18n/catalog.json'):
        if path.is_file():
            sources[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    revision = hashlib.sha256(json.dumps(sources, sort_keys=True).encode()).hexdigest()
    if source_state.is_file() and source_state.read_text() == revision and (root / 'frontend/assets/finance-modern/manifest.json').is_file():
        return False
    subprocess.run(['npm', '--prefix', str(modern), 'run', 'build'], cwd=root, env=env, check=True)
    source_state.write_text(revision)
    return True


class SourceWatcher:
    """One owner watches source; callbacks provide optional shared-host safe points."""
    debounce_seconds = .7

    def __init__(self, root, compile_action, state_file=None, before_reload=None,
                 after_reload=None, trigger_file=None):
        self.root = Path(root).resolve()
        self.compile_action = compile_action
        self.before_reload = before_reload
        self.after_reload = after_reload
        self.state_file = Path(state_file) if state_file else self.root / '.dev/source-state.json'
        self.trigger_file = Path(trigger_file) if trigger_file else self.root / 'target/classes/.reloadtrigger'
        self.restart_required = False
        self.compile_error = None
        self.backup_path = None
        self._fingerprints = {}
        self._observed = self.scan()
        self._applied = self._observed
        self._compiled_sources = self._observed[0]
        self._pending_since = None
        self._write_revision(self._observed[1])

    def _snapshot(self, directory, frontend=False):
        found = {}
        if not directory.exists():
            return found
        candidates = []
        for folder, children, names in os.walk(directory, followlinks=False):
            parent = Path(folder)
            children[:] = sorted(name for name in children
                if not name.startswith('.') and name not in ('node_modules', 'test', 'tests', '__pycache__')
                and not (parent / name).is_symlink()
                and not (frontend and ((parent / name).relative_to(directory).parts[0] == 'modern'
                    or (parent / name).relative_to(directory).parts[:2] == ('assets', 'finance-modern'))))
            candidates.extend(parent / name for name in sorted(names))
        for path in candidates:
            relative = path.relative_to(directory)
            if path.is_symlink() or not path.is_file() or any(part.startswith('.') or part in ('node_modules', 'test', 'tests', '__pycache__') for part in relative.parts):
                continue
            if frontend and path.suffix.lower() not in PUBLIC_EXTENSIONS:
                continue
            # React assets are published atomically and picked up on the next visit.
            # Rebuilding this optional UI must not reload tabs with financial drafts.
            if frontend and (relative.parts[0] == 'modern' or relative.parts[:2] == ('assets', 'finance-modern')):
                continue
            stat = path.stat()
            stamp = (stat.st_mtime_ns, stat.st_size)
            key = str(path)
            previous = self._fingerprints.get(key)
            digest = previous[1] if previous and previous[0] == stamp else hashlib.sha256(path.read_bytes()).hexdigest()
            self._fingerprints[key] = (stamp, digest)
            found[path.relative_to(self.root).as_posix()] = digest
        return found

    def scan(self):
        sources = self._snapshot(self.root / 'src/main')
        pom = self.root / 'pom.xml'
        if pom.is_file():
            sources['pom.xml'] = hashlib.sha256(pom.read_bytes()).hexdigest()
        frontend = self._snapshot(self.root / 'frontend', frontend=True)
        revision = hashlib.sha256(json.dumps(frontend, sort_keys=True).encode()).hexdigest()
        return sources, revision

    def _write_revision(self, revision):
        content = json.dumps({'frontendRevision': revision}) + '\n'
        if self.state_file.is_file() and self.state_file.read_text() == content:
            return
        self.state_file.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.state_file.with_name(self.state_file.name + '.tmp')
        temporary.write_text(content)
        temporary.replace(self.state_file)

    def _remove_deleted(self, previous, current, classes):
        for relative in previous.keys() - current.keys():
            if relative.startswith('src/main/java/') and relative.endswith('.java'):
                stem = relative[len('src/main/java/'):-5]
                output = classes / (stem + '.class')
                output.unlink(missing_ok=True)
                for inner in output.parent.glob(output.stem + '$*.class'):
                    inner.unlink()
            elif relative.startswith('src/main/resources/'):
                output = classes / relative[len('src/main/resources/'):]
                if output.is_file():
                    output.unlink()

    def _compile(self, previous, current, fresh=False):
        success = False
        classes = self.root / 'target/classes'
        if classes.is_symlink() or classes.resolve() != self.root / 'target/classes':
            raise RuntimeError('Refusing a redirected target/classes directory.')
        classes.mkdir(parents=True, exist_ok=True)
        backups = self.root / '.dev/reload-backups'
        backups.mkdir(parents=True, exist_ok=True, mode=0o700)
        backups.chmod(0o700)
        try:
            with tempfile.TemporaryDirectory(prefix='classes-', dir=backups) as temporary:
                try:
                    if self.before_reload:
                        self.before_reload()
                    # Compiler output only; the host adapter may journal this path for crash recovery.
                    backup = Path(temporary) / 'classes'
                    shutil.copytree(classes, backup)
                    self.backup_path = backup
                    interrupted = None
                    try:
                        if fresh:
                            shutil.rmtree(classes)
                            classes.mkdir()
                        else:
                            self._remove_deleted(previous, current, classes)
                        result = self.compile_action()
                        success = result if isinstance(result, bool) else (result.returncode == 0 if hasattr(result, 'returncode') else result == 0)
                    except BaseException as error:
                        self.compile_error = type(error).__name__
                        success = False
                        if not isinstance(error, Exception):
                            interrupted = error
                    if success:
                        self.trigger_file.parent.mkdir(parents=True, exist_ok=True)
                        self.trigger_file.write_text(str(time.time_ns()) + '\n')
                        self.compile_error = None
                    else:
                        # Preserve the complete last valid output, including the untouched trigger.
                        shutil.rmtree(classes)
                        shutil.copytree(backup, classes)
                        self.compile_error = self.compile_error or 'Compilation failed'
                    if interrupted is not None:
                        raise interrupted
                    return success
                finally:
                    if self.after_reload:
                        self.after_reload(success)
        finally:
            self.backup_path = None

    def prepare(self):
        """Reconcile stopped-time deletions before launch; never clear other target output."""
        return self._compile(self._compiled_sources, self._observed[0], fresh=True)

    def step(self):
        current = self.scan()
        now = time.monotonic()
        if current != self._observed:
            self._observed = current
            self._pending_since = now
            return False
        if self._pending_since is None or now - self._pending_since < self.debounce_seconds:
            return False
        previous = self._applied
        self._pending_since = None
        self._applied = current  # A bad edit is retried only after another edit.
        if current[1] != previous[1]:
            self._write_revision(current[1])
        if current[0] != previous[0]:
            self.restart_required = current[0].get('pom.xml') != self._compiled_sources.get('pom.xml')
            success = self._compile(self._compiled_sources, current[0])
            if success:
                self._compiled_sources = current[0]
            else:
                self.restart_required = False
            print('Source compile succeeded; reload triggered.' if success else 'Compile failed; last valid classes restored.', flush=True)
            return success
        return False

    def run(self, stop_event=None):
        while stop_event is None or not stop_event.is_set():
            self.step()
            time.sleep(.25)


def stop_process(process):
    """Only call for a Popen created by this launcher with start_new_session=True."""
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


@contextmanager
def workspace_lock(root):
    """One CLI runner/watcher owns this worktree's compiler output until exit."""
    directory = Path(root) / '.dev'
    directory.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(directory / 'runner.lock', os.O_CREAT | os.O_RDWR, 0o600)
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('Another source runner or watcher owns this workspace.') from None
        yield
    finally:
        os.close(descriptor)


def require_free_port(port):
    # macOS permits wildcard SO_REUSEADDR binds beside an existing loopback listener.
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as connection:
        connection.settimeout(.25)
        if connection.connect_ex(('127.0.0.1', port)) == 0:
            raise RuntimeError('Application port is already in use.')
    # Refuse other conflicting binds before touching compiler output or starting a child.
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(('0.0.0.0', port))
            probe.listen(1)
        except OSError:
            raise RuntimeError('Application port is already in use.') from None


def wait_until_ready(process, port, token, timeout=120):
    """Wait past Maven's forked test-compile; authenticate this launch's private context."""
    deadline = time.monotonic() + timeout
    while process.poll() is None:
        try:
            request = Request('http://127.0.0.1:%s/_heyi/ops/status' % port,
                              headers={'X-Heyi-Ops-Token': token})
            with urlopen(request, timeout=1) as response:
                status = json.loads(response.read(4096))
            if status.get('protocol') == 1 and isinstance(status.get('contextId'), str) and status['contextId']:
                with urlopen('http://127.0.0.1:%s/actuator/health' % port, timeout=1) as response:
                    health = json.loads(response.read(4096))
                if health.get('status') == 'UP' and process.poll() is None:
                    return status['contextId']
        except (OSError, ValueError):
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError('Owned application did not become healthy before startup timeout.')
        time.sleep(.1)
    raise RuntimeError('Owned Maven application exited before it became healthy.')


@contextmanager
def running_application(command, env, port):
    require_free_port(port)
    # A fresh file path each launch prevents an older context from authenticating readiness.
    with tempfile.TemporaryDirectory(prefix='runner-', dir=ROOT / '.dev') as temporary:
        token_file = Path(temporary) / 'status-token'
        token = secrets.token_hex(32)
        descriptor = os.open(token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as stream:
            stream.write(token)
        child_env = dict(env, HEYI_OPS_TOKEN_FILE=str(token_file))
        process = subprocess.Popen(command, cwd=ROOT, env=child_env, start_new_session=True)
        try:
            wait_until_ready(process, port, token)
            yield process
        finally:
            stop_process(process)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', nargs='?', choices=('init', 'run', 'watch', 'check'), default='run')
    parser.add_argument('--no-deps', action='store_true', help='Use existing MySQL/Redis; do not start dependency containers')
    args = parser.parse_args(argv)
    path = init_env(ROOT)
    if args.action == 'init':
        print('Private development configuration ready: ' + str(path))
        return 0
    env = runtime_env(ROOT)
    maven = toolchain(env)
    if args.action == 'check':
        print('Java 21 and Maven 3.9.11 available; private development configuration parsed.')
        print('Application URL: ' + env.get('APP_PUBLIC_BASE_URL', 'http://localhost:8080'))
        return 0
    compile_action = lambda: subprocess.run(maven + ['-Dmaven.test.skip=true', 'compile'], cwd=ROOT, env=env)
    def interrupted(_signum, _frame):
        raise KeyboardInterrupt
    prior = signal.signal(signal.SIGTERM, interrupted)
    try:
        with workspace_lock(ROOT):
            ensure_modern_frontend(ROOT, env)
            # Keep this baseline before any initial compilation or child startup. Saves made
            # while Maven starts are then coalesced by step() after owned readiness.
            watcher = SourceWatcher(ROOT, compile_action)
            if args.action == 'watch':
                watcher.run()
                return 0
            port = int(env.get('SERVER_PORT', env.get('APP_PORT', '8080')))
            require_free_port(port)
            if not args.no_deps:
                project = 'heyi-dev-' + hashlib.sha256(str(ROOT).encode()).hexdigest()[:10]
                subprocess.run(['docker', 'compose', '--env-file', str(path), '-f', str(ROOT / 'compose.dev.yaml'), '-p', project,
                                'up', '-d', '--wait', 'mysql', 'redis'], cwd=ROOT, env=env, check=True)
            if not watcher.prepare():
                print('Initial compile failed; previous output restored and application not started.', flush=True)
                return 1
            command = maven + ['-Dmaven.test.skip=true', '-Dmaven.main.skip=true', '-Dmaven.resources.skip=true',
                               'spring-boot:run', '-Dspring-boot.run.profiles=dev']
            while True:
                with running_application(command, env, port) as process:
                    while process.poll() is None:
                        watcher.step()
                        if watcher.restart_required:
                            print('Dependencies changed; restarting the owned Maven application process.', flush=True)
                            break
                        time.sleep(.25)
                    if not watcher.restart_required:
                        return process.returncode
                watcher.restart_required = False
    except KeyboardInterrupt:
        return 0
    finally:
        signal.signal(signal.SIGTERM, prior)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        # Do not print child command/environment: it can contain private values.
        print('Development launcher failed: ' + type(error).__name__ + '. Check toolchain/services and private configuration.', file=sys.stderr)
        raise SystemExit(1)
