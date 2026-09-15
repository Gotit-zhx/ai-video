#!/usr/bin/env python3
"""Server-side, narrow recovery deployment. Run on the server; never connects by SSH.

Upload app.jar, frontend/app.js, and the changed pi-runtime/src/*.js beneath --root.
Run preflight, build, status, deploy, or rollback. Build only creates local images and
backups. Deploy refuses active work; there is deliberately no force-active switch.
All Docker output/configuration/database backups stay in a private directory.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile
import zlib

SERVICES = ('app', 'pi-runtime', 'frontend')
CONTAINERS = {'app': 'ai-commerce-app', 'pi-runtime': 'heyi-pi-runtime',
              'frontend': 'ai-commerce-frontend'}
EXPECTED_IMAGES = {'app': 'ai-commerce-platform:timeout-1000-1c09e00',
                   'pi-runtime': 'heyi-pi-runtime:timeout-1000-1c09e00',
                   'frontend': 'heyi-ai-frontend:company-footer-20260905'}
MYSQL_CONTAINER = 'ai-commerce-mysql'
RELEASE = 'video-script-recovery-20260915'


class Stop(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise Stop(message)


def digest(path):
    checksum = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            checksum.update(chunk)
    return checksum.hexdigest()


def envmap(values):
    return dict(value.split('=', 1) for value in (values or []) if '=' in value)


def flyway_checksum(content):
    # Flyway CRC32 consumes UTF-8 lines without their CR/LF delimiters and first BOM.
    checksum = 0
    for line in re.split(r'\r\n|\r|\n', content.decode('utf-8-sig')):
        checksum = zlib.crc32(line.encode('utf-8'), checksum)
    return checksum if checksum < 2 ** 31 else checksum - 2 ** 32


def jar_migrations(path):
    result = {}
    with zipfile.ZipFile(path) as jar:
        for name in jar.namelist():
            match = re.fullmatch(r'BOOT-INF/classes/db/migration/(V(\d+)__[^/]+\.sql)', name)
            if match:
                version = match.group(2)
                require(version not in result, 'Duplicate migration version in app.jar')
                result[version] = {'script': match.group(1), 'checksum': flyway_checksum(jar.read(name))}
    return result


class Recovery:
    def __init__(self, root, resume_preanalysis_only=False, resume_scripts=False):
        os.umask(0o077)
        self.root = Path(root).resolve()
        require(self.root.is_dir(), 'Upload directory does not exist')
        self.private = self.root / 'private'
        self.private.mkdir(mode=0o700, exist_ok=True)
        self.private.chmod(0o700)
        self.logs = self.private / 'logs'
        self.logs.mkdir(mode=0o700, exist_ok=True)
        self.sequence = 0
        self.state_file = self.private / 'state.json'
        self.state = json.loads(self.state_file.read_text()) if self.state_file.exists() else None
        self.resume_preanalysis_only = resume_preanalysis_only
        self.resume_scripts = resume_scripts
        self.interrupted_script_ids = None

    def save_json(self, name, value):
        path = self.private / name
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
        path.chmod(0o600)
        return path

    def save_state(self):
        self.save_json('state.json', self.state)

    def run(self, argv, label, *, stdin=None, timeout=180, output=None, capture=True):
        self.sequence += 1
        name = f'{time.time_ns()}-{self.sequence:03d}-{label}'
        outpath = Path(output) if output else self.logs / (name + '.out')
        errpath = self.logs / (name + '.err')
        try:
            with outpath.open('wb') as stdout, errpath.open('wb') as stderr:
                result = subprocess.run(argv, input=stdin, stdout=stdout, stderr=stderr,
                                        cwd=self.root, timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise Stop(f'{label} could not finish; inspect private logs') from error
        require(result.returncode == 0, f'{label} failed (exit {result.returncode}); inspect private logs')
        outpath.chmod(0o600)
        return outpath.read_bytes() if capture else b''

    def inspect(self, target, kind='container'):
        return json.loads(self.run(['docker', kind, 'inspect', target], 'inspect-' + kind))[0]

    def compose(self, extra_files=()):
        require(self.state is not None, 'Run preflight first')
        args = ['docker', 'compose', '--project-directory', self.state['working_dir'],
                '-p', self.state['project']]
        for path in [*self.state['compose_files'], *extra_files]:
            args += ['-f', str(path)]
        return args

    def config(self, extra_files=()):
        return json.loads(self.run(self.compose(extra_files) + ['config', '--format', 'json'], 'compose-config'))

    def override(self, name, images):
        return self.save_json(name, {'services': {service: {'image': image} for service, image in images.items()}})

    def sql(self, query):
        # Password remains in the existing container environment, never argv/stdout.
        shell = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw --skip-column-names "$MYSQL_DATABASE"'
        return self.run(['docker', 'exec', '-i', MYSQL_CONTAINER, 'sh', '-c', shell], 'mysql-query',
                        stdin=query.encode()).decode().strip()

    def live(self):
        return {service: self.inspect(CONTAINERS[service]) for service in SERVICES}

    def check_labels(self, live):
        specs = []
        compose_files = []
        for service, container in live.items():
            labels = container['Config'].get('Labels') or {}
            require(labels.get('com.docker.compose.service') == service, 'Live Compose service label mismatch')
            files = labels.get('com.docker.compose.project.config_files', '').split(',')
            directory = labels.get('com.docker.compose.project.working_dir', '')
            project = labels.get('com.docker.compose.project', '')
            require(project and directory and all(Path(path).is_file() for path in files),
                    'Cannot recover complete live Compose files/project from labels')
            specs.append((project, directory))
            # A service may have been recreated with an additional override.
            # Preserve first-seen order so the frontend footer override stays last.
            for path in files:
                if path not in compose_files:
                    compose_files.append(path)
        require(all(spec == specs[0] for spec in specs), 'Services use different Compose projects/working directories')
        return (*specs[0], compose_files)

    def check_live_config(self, config, live):
        """Fail closed on config drift instead of silently changing live settings."""
        for service in SERVICES:
            value = config['services'][service]
            container = live[service]
            require(value['image'] == container['Config']['Image'], service + ': image reference drift')
            image = self.inspect(container['Image'], 'image')['Config']
            predicted_env = envmap(image.get('Env'))
            predicted_env.update({key: str(value) for key, value in (value.get('environment') or {}).items()
                                  if value is not None})
            require(predicted_env == envmap(container['Config'].get('Env')), service + ': environment drift')
            ports = set()
            for port in value.get('ports') or []:
                require(isinstance(port, dict) and 'published' in port, service + ': unsupported port configuration')
                host = port.get('host_ip', '')
                host = '*' if host in ('', '0.0.0.0') else host
                ports.add((str(port['target']) + '/' + port.get('protocol', 'tcp'), host, str(port['published'])))
            actual_ports = set()
            for target, bindings in (container['HostConfig'].get('PortBindings') or {}).items():
                for binding in bindings or []:
                    host = binding.get('HostIp', '')
                    host = '*' if host in ('', '0.0.0.0') else host
                    actual_ports.add((target, host, binding['HostPort']))
            require(ports == actual_ports, service + ': published port drift')
            mounts = set()
            for mount in value.get('volumes') or []:
                require(isinstance(mount, dict), service + ': unsupported mount configuration')
                kind = mount.get('type', 'volume')
                source = mount.get('source', '')
                if kind == 'volume':
                    source = config.get('volumes', {}).get(source, {}).get('name', source)
                elif kind == 'bind':
                    source = os.path.realpath(source)
                else:
                    raise Stop(service + ': unsupported mount type')
                mounts.add((kind, source, mount['target'], not mount.get('read_only', False)))
            actual_mounts = {(mount['Type'], mount.get('Name') if mount['Type'] == 'volume'
                              else os.path.realpath(mount['Source']), mount['Destination'], mount['RW'])
                             for mount in container.get('Mounts', [])}
            require(mounts == actual_mounts, service + ': mount drift')
            networks = {config['networks'][key]['name'] for key in (value.get('networks') or {'default': {}})}
            require(networks == set(container['NetworkSettings']['Networks']), service + ': network drift')
            for compose_key, docker_key in [('user', 'User'), ('working_dir', 'WorkingDir')]:
                expected = value.get(compose_key, image.get(docker_key)) or ''
                require(expected == (container['Config'].get(docker_key) or ''), service + ': startup identity drift')
            for compose_key, docker_key in [('entrypoint', 'Entrypoint'), ('command', 'Cmd')]:
                expected = value.get(compose_key)
                if expected is None:
                    expected = image.get(docker_key)
                actual = container['Config'].get(docker_key)
                require(expected == actual, service + ': command/entrypoint drift')
            restart = value.get('restart', 'no')
            require(restart == container['HostConfig']['RestartPolicy']['Name'], service + ': restart policy drift')

    def archive_copy(self, container, remote, name):
        destination = self.private / name
        self.run(['docker', 'cp', f'{container}:{remote}', str(destination)], 'archive-file')
        destination.chmod(0o600)
        return destination

    def preflight(self):
        require(self.state is None, 'Preflight already captured; use status/build; do not overwrite a rollback baseline')
        live = self.live()
        project, directory, files = self.check_labels(live)
        for service in SERVICES:
            require(live[service]['Config']['Image'] == EXPECTED_IMAGES[service], service + ': unexpected production image')
            require(live[service]['State']['Running'], service + ': production container is stopped')
        self.state = {'project': project, 'working_dir': directory, 'compose_files': files,
                      'phase': 'preflight', 'containers': {key: value['Id'] for key, value in live.items()},
                      'live_images': {key: value['Config']['Image'] for key, value in live.items()},
                      'image_ids': {key: value['Image'] for key, value in live.items()}}
        self.save_json('live-inspect.json', live)
        database = envmap(self.inspect(MYSQL_CONTAINER)['Config'].get('Env')).get('MYSQL_DATABASE')
        jdbc = envmap(live['app']['Config'].get('Env')).get('DB_URL', '')
        database_match = re.match(r'jdbc:mysql://[^/]+/([^?]+)', jdbc)
        require(database_match and database_match.group(1) == database, 'App and backup database names differ')
        baseline = self.override('live-images.json', self.state['live_images'])
        raw_config = self.config()
        config = self.config([baseline])
        self.save_json('compose-before-alignment.json', raw_config)
        self.check_live_config(config, live)
        self.save_json('compose-aligned.json', config)
        for index, path in enumerate(files):
            shutil.copyfile(path, self.private / f'compose-source-{index:02d}.yaml')
        old_jar = self.archive_copy(CONTAINERS['app'], '/app/app.jar', 'app-before.jar')
        nginx = self.archive_copy(CONTAINERS['frontend'], '/etc/nginx/conf.d/default.conf', 'nginx-before.conf')
        index = self.archive_copy(CONTAINERS['frontend'], '/usr/share/nginx/html/index.html', 'index-before.html')
        runtime_archive = self.private / 'runtime-before'
        self.run(['docker', 'cp', CONTAINERS['pi-runtime'] + ':/app/src', str(runtime_archive)], 'archive-runtime')
        for path in runtime_archive.rglob('*'):
            path.chmod(0o700 if path.is_dir() else 0o600)
        self.state['baseline_hashes'] = {'app.jar': digest(old_jar), 'nginx.conf': digest(nginx),
                                         'index.html': digest(index),
                                         'runtime': {str(path.relative_to(runtime_archive)): digest(path)
                                                     for path in runtime_archive.rglob('*') if path.is_file()}}
        self.check_migrations()
        self.save_state()
        print('preflight: Compose/live settings match; nginx/index/runtime/jar baseline archived privately', flush=True)
        self.status()

    def check_migrations(self):
        migrations = jar_migrations(self.root / 'app.jar')
        require(set(migrations) == {str(i) for i in range(1, 44)}, 'Release must contain exactly migrations V1 through V43')
        rows = self.sql('SELECT version, script, checksum, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank;')
        applied = {}
        for row in rows.splitlines():
            version, filename, checksum, success = row.split('\t')
            require(success == '1', 'Database has unsuccessful Flyway migration')
            require(version not in applied, 'Database has duplicate Flyway migration version')
            applied[version] = {'script': filename, 'checksum': int(checksum)}
        require(migrations == applied, 'All 43 release Flyway names/checksums must exactly match the database; no new migrations allowed')
        self.save_json('flyway-verified.json', migrations)

    def status(self):
        live = self.live()
        for service in SERVICES:
            state = live[service]['State']
            print(f'{service}: running={state["Running"]} health={state.get("Health", {}).get("Status", "not-configured")}', flush=True)
        counts = self.active_counts()
        print('active work: ' + ', '.join(f'{key}={value}' for key, value in counts.items()), flush=True)
        return counts

    def active_counts(self):
        query = """SELECT 'video_scripts', COUNT(*) FROM video_scripts WHERE status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')
UNION ALL SELECT 'ai_video_tasks', COUNT(*) FROM ai_video_tasks WHERE status NOT IN ('DRAFT','READY','FAILED','IMAGE_READY','VIDEO_READY','CANCELLED')
UNION ALL SELECT 'ai_video_task_items', COUNT(*) FROM ai_video_task_items WHERE status NOT IN ('DRAFT','READY','FAILED','IMAGE_READY','VIDEO_READY','VIDEO_FAILED','CANCELLED')
UNION ALL SELECT 'ai_video_generation_jobs', COUNT(*) FROM ai_video_generation_jobs WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED');"""
        return {row.split('\t')[0]: int(row.split('\t')[1]) for row in self.sql(query).splitlines()}

    def check_baseline_unchanged(self):
        live = self.live()
        require({key: value['Id'] for key, value in live.items()} == self.state['containers'], 'Production containers changed since preflight')
        require({key: value['Image'] for key, value in live.items()} == self.state['image_ids'], 'Production images changed since preflight')
        aligned = self.config([self.private / 'live-images.json'])
        require(aligned == json.loads((self.private / 'compose-aligned.json').read_text()), 'Compose config changed since preflight')
        self.check_live_config(aligned, live)
        for service, remote, name, expected in [
            ('app', '/app/app.jar', 'app-current.jar', self.state['baseline_hashes']['app.jar']),
            ('frontend', '/etc/nginx/conf.d/default.conf', 'nginx-current.conf', self.state['baseline_hashes']['nginx.conf']),
            ('frontend', '/usr/share/nginx/html/index.html', 'index-current.html', self.state['baseline_hashes']['index.html'])
        ]:
            require(digest(self.archive_copy(CONTAINERS[service], remote, name)) == expected, service + ': live file changed since preflight')

        current_runtime = self.private / ('runtime-current-' + str(time.time_ns()))
        self.run(['docker', 'cp', CONTAINERS['pi-runtime'] + ':/app/src', str(current_runtime)], 'verify-runtime-baseline')
        current_hashes = {}
        for path in current_runtime.rglob('*'):
            path.chmod(0o700 if path.is_dir() else 0o600)
            if path.is_file():
                current_hashes[str(path.relative_to(current_runtime))] = digest(path)
        require(current_hashes == self.state['baseline_hashes']['runtime'], 'Runtime source changed since preflight')

    def check_restart_window(self):
        counts = self.active_counts()
        if counts['video_scripts']:
            require(self.resume_scripts, 'Script analysis is active; deploy refused')
            script_ids = self.sql("SELECT id FROM video_scripts WHERE status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING') ORDER BY id;").splitlines()
            if self.interrupted_script_ids is None:
                self.interrupted_script_ids = script_ids
            require(set(script_ids) <= set(self.interrupted_script_ids), 'New script appeared before maintenance')
            self.save_json('interrupted-script-ids.json', self.interrupted_script_ids)
            counts['video_scripts'] = 0
        if not any(counts.values()):
            return []
        require(self.resume_preanalysis_only, 'Active work exists; deploy refused')
        require(counts['ai_video_generation_jobs'] == 0,
                'Video submission is active; deploy refused')
        require(self.sql("SELECT COUNT(*) FROM ai_video_tasks WHERE status NOT IN ('DRAFT','READY','FAILED','IMAGE_READY','VIDEO_READY','CANCELLED','IMAGE_GENERATING')") == '0',
                'Work outside initial image analysis is active')
        rows = self.sql("""SELECT t.id,i.id,i.status,
            IF(i.product_function_analysis_json IS NULL OR TRIM(i.product_function_analysis_json)='',0,1),
            (SELECT COUNT(*) FROM ai_video_task_assets a WHERE a.item_id=i.id),
            (SELECT COUNT(*) FROM ai_video_pending_image_results p WHERE p.item_id=i.id)
            FROM ai_video_tasks t JOIN ai_video_task_items i ON i.task_id=t.id
            WHERE t.status='IMAGE_GENERATING' ORDER BY t.id,i.id;""")
        identities = []
        for row in rows.splitlines():
            task, item, status, analysis, assets, pending = row.split('\t')
            require(status in ('READY', 'GENERATING_MODEL', 'FAILED') and
                    analysis == assets == pending == '0',
                    'A running image task has advanced beyond initial analysis; deploy refused')
            identities.append((task, item))
        require(identities, 'Active work is not a recoverable initial-analysis batch')
        self.save_json('preanalysis-resume-identities.json', identities)
        return identities

    def resume_stopped_preanalysis(self, identities):
        if not identities:
            require(not any(self.active_counts().values()), 'New work appeared while stopping')
            return
        require(not self.inspect(CONTAINERS['app'])['State']['Running'], 'Backend must be stopped before restoring interrupted task state')
        task_ids = sorted({task for task, _ in identities})
        require(all(re.fullmatch(r'[0-9a-f-]{36}', task) for task in task_ids), 'Unexpected task ID')
        literal = ','.join("'" + task + "'" for task in task_ids)
        rows = self.sql("""SELECT t.id,i.id,t.status,
            IF(i.product_function_analysis_json IS NULL OR TRIM(i.product_function_analysis_json)='',0,1),
            (SELECT COUNT(*) FROM ai_video_task_assets a WHERE a.item_id=i.id),
            (SELECT COUNT(*) FROM ai_video_pending_image_results p WHERE p.item_id=i.id)
            FROM ai_video_tasks t JOIN ai_video_task_items i ON i.task_id=t.id
            WHERE t.id IN (""" + literal + ") ORDER BY t.id,i.id;")
        verified = []
        for row in rows.splitlines():
            task, item, status, analysis, assets, pending = row.split('\t')
            require(status in ('IMAGE_GENERATING', 'FAILED') and analysis == assets == pending == '0',
                    'A task advanced beyond initial analysis during shutdown; rollback required')
            verified.append((task, item))
        require(verified == identities, 'Interrupted task identities changed')
        # The old executor may mark an interrupted initial-analysis task FAILED
        # during graceful shutdown. Restore only its in-progress state; preserve
        # its frozen script, source images, selected models, and publication flag.
        self.sql("UPDATE ai_video_tasks SET status='IMAGE_GENERATING', updated_at=UTC_TIMESTAMP(6)-INTERVAL 31 SECOND "
                 "WHERE id IN (" + literal + ") AND status='FAILED';")
        self.save_json('preanalysis-resume-verified.json', verified)

    def preserve_interrupted_scripts(self):
        if not self.interrupted_script_ids:
            return
        require(not self.inspect(CONTAINERS['app'])['State']['Running'], 'Backend must be stopped before preserving interrupted scripts')
        require(all(re.fullmatch(r'[0-9a-f-]{36}', value) for value in self.interrupted_script_ids), 'Unexpected script ID')
        literal = ','.join("'" + value + "'" for value in self.interrupted_script_ids)
        self.sql("UPDATE video_scripts SET status='FAILED',progress=100,error_message='DEPLOYMENT_INTERRUPTED: service upgrade; original video preserved for continuation',completed_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) "
                 "WHERE id IN (" + literal + ") AND status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING');")

    def backup_database(self):
        destination = self.private / f'database-{time.strftime("%Y%m%d-%H%M%S")}.sql'
        shell = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --events --set-gtid-purged=OFF --no-tablespaces "$MYSQL_DATABASE"'
        self.run(['docker', 'exec', MYSQL_CONTAINER, 'sh', '-c', shell], 'database-backup',
                 timeout=900, output=destination, capture=False)
        require(destination.stat().st_size > 1024, 'Database backup is unexpectedly small')
        self.state['database_backup'] = str(destination)
        self.state['database_backup_sha256'] = digest(destination)
        self.save_state()
        print('database: backup complete (private, mode 0600)', flush=True)

    def build_image(self, context, tag, dockerfile):
        context.mkdir(parents=True, exist_ok=True)
        (context / 'Dockerfile').write_text(dockerfile)
        self.run(['docker', 'build', '--pull=false', '-t', tag, str(context)], 'build-image', timeout=1200, capture=False)
        return self.inspect(tag, 'image')['Id']

    def build(self):
        require(self.state is not None and self.state['phase'] in ('preflight', 'built'), 'Run preflight before build')
        self.check_baseline_unchanged()
        self.check_migrations()
        require((self.root / 'frontend/app.js').is_file(), 'Missing frontend/app.js upload')
        runtime_files = sorted((self.root / 'pi-runtime/src').glob('*.js'))
        require({'server.js', 'video-script-session.js'} <= {path.name for path in runtime_files}, 'Missing runtime source uploads')
        self.backup_database()
        base = {}
        for service in SERVICES:
            tag = f'heyi-recovery-{service}:{RELEASE}-base'
            self.run(['docker', 'tag', self.state['image_ids'][service], tag], 'pin-baseline-image')
            base[service] = tag
        images = {'app': f'ai-commerce-platform:{RELEASE}', 'pi-runtime': f'heyi-pi-runtime:{RELEASE}',
                  'frontend': f'heyi-ai-frontend:{RELEASE}'}
        context_root = self.private / 'build'
        app = context_root / 'app'
        app.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(self.root / 'app.jar', app / 'app.jar')
        self.build_image(app, images['app'], f'FROM {base["app"]}\nCOPY --chown=10001:10001 app.jar /app/app.jar\n')
        runtime = context_root / 'runtime'
        (runtime / 'src').mkdir(parents=True, exist_ok=True)
        for path in runtime_files:
            shutil.copyfile(path, runtime / 'src' / path.name)
        self.build_image(runtime, images['pi-runtime'], f'FROM {base["pi-runtime"]}\nCOPY --chown=node:node src/ /app/src/\n')
        frontend = context_root / 'frontend'
        frontend.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(self.root / 'frontend/app.js', frontend / 'app.js')
        shutil.copyfile(self.private / 'nginx-before.conf', frontend / 'default.conf')
        shutil.copyfile(self.private / 'index-before.html', frontend / 'index.html')
        # The private archive stays 0600; nginx workers need readable static files in the image.
        for path in frontend.iterdir():
            if path.is_file():
                path.chmod(0o644)
        frontend_base = f'FROM {base["frontend"]}\nCOPY default.conf /etc/nginx/conf.d/default.conf\nCOPY index.html /usr/share/nginx/html/index.html\n'
        self.build_image(frontend, images['frontend'], frontend_base + 'COPY app.js /usr/share/nginx/html/app.js\n')
        rollback_images = dict(base)
        rollback_images['frontend'] = f'heyi-ai-frontend:{RELEASE}-rollback'
        self.build_image(frontend, rollback_images['frontend'], frontend_base)
        self.state['rollback_image_ids'] = {key: self.inspect(value, 'image')['Id']
                                            for key, value in rollback_images.items()}
        self.state['release_images'] = images
        self.state['release_image_ids'] = {key: self.inspect(value, 'image')['Id'] for key, value in images.items()}
        self.state['rollback_images'] = rollback_images
        self.state['upload_hashes'] = {str(path.relative_to(self.root)): digest(path)
                                      for path in [self.root / 'app.jar', self.root / 'frontend/app.js', *runtime_files]}
        release_override = self.override('release-images.json', images)
        self.override('rollback-images.json', rollback_images)
        baseline = json.loads((self.private / 'compose-aligned.json').read_text())
        release = self.config([release_override])
        comparison = copy.deepcopy(release)
        for service in SERVICES:
            comparison['services'][service]['image'] = baseline['services'][service]['image']
        require(comparison == baseline, 'Release Compose changes more than the three image references')
        self.save_json('compose-release.json', release)
        rollback_config = self.config([self.private / 'rollback-images.json'])
        comparison = copy.deepcopy(rollback_config)
        for service in SERVICES:
            comparison['services'][service]['image'] = baseline['services'][service]['image']
        require(comparison == baseline, 'Rollback Compose changes more than the image references')
        self.save_json('compose-rollback.json', rollback_config)
        self.state['phase'] = 'built'
        self.save_state()
        print('build: three release images and nginx-preserving rollback image ready; no services restarted', flush=True)

    def endpoint(self, service, port, path):
        live = self.inspect(CONTAINERS[service])
        bindings = live['HostConfig']['PortBindings'].get(f'{port}/tcp') or []
        require(bindings, service + ': health endpoint has no published port')
        bind = bindings[0]
        host = bind.get('HostIp') or '127.0.0.1'
        if host in ('0.0.0.0', '::'):
            host = '127.0.0.1'
        return f'http://{host}:{bind["HostPort"]}{path}'

    def health(self, timeout=180):
        endpoints = [self.endpoint('app', 8080, '/actuator/health'),
                     self.endpoint('pi-runtime', 8090, '/health')]
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                for endpoint in endpoints:
                    with urllib.request.urlopen(endpoint, timeout=5) as response:
                        require(json.loads(response.read()).get('status') == 'UP', 'Health status is not UP')
                require(all(value['State']['Running'] for value in self.live().values()), 'A service is not running')
                self.run(['docker', 'exec', CONTAINERS['frontend'], 'nginx', '-t'], 'nginx-health')
                return
            except (Stop, OSError, ValueError):
                time.sleep(3)
        raise Stop('Release health check timed out')

    def verify_frontend(self):
        for remote, name, expected in [
            ('/etc/nginx/conf.d/default.conf', 'nginx-verified.conf', self.state['baseline_hashes']['nginx.conf']),
            ('/usr/share/nginx/html/index.html', 'index-verified.html', self.state['baseline_hashes']['index.html'])
        ]:
            require(digest(self.archive_copy(CONTAINERS['frontend'], remote, name)) == expected,
                    'Frontend nginx/index changed from the archived production baseline')

    def deploy(self):
        require(self.state is not None and self.state['phase'] == 'built', 'Run build successfully before deploy')
        self.check_baseline_unchanged()
        self.check_migrations()
        require(self.config([self.private / 'release-images.json']) ==
                json.loads((self.private / 'compose-release.json').read_text()), 'Release Compose changed after build')
        for path, expected in self.state['upload_hashes'].items():
            require(digest(self.root / path) == expected, 'An upload changed after build')
        for service, tag in self.state['release_images'].items():
            require(self.inspect(tag, 'image')['Id'] == self.state['release_image_ids'][service], 'Release image tag changed after build')
        self.status()
        identities = self.check_restart_window()
        self.backup_database()
        require(self.check_restart_window() == identities, 'Active work changed during backup; deploy refused')
        self.state['phase'] = 'deploying'
        self.save_state()
        try:
            self.run(['docker', 'stop', '--time', '30', CONTAINERS['app']], 'stop-app', timeout=90)
            self.preserve_interrupted_scripts()
            self.resume_stopped_preanalysis(identities)
            for service in ('pi-runtime', 'app', 'frontend'):
                self.run(self.compose([self.private / 'release-images.json']) +
                         ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate', service],
                         'deploy-' + service, timeout=180, capture=False)
            self.health()
            self.verify_frontend()
            self.check_live_config(json.loads((self.private / 'compose-release.json').read_text()), self.live())
            self.state['phase'] = 'deployed'
            self.save_state()
            print('deploy: app/runtime healthy; nginx/index preserved; all three service settings verified', flush=True)
        except (Stop, OSError, ValueError):
            print('deploy: failed; rolling back all three services', flush=True)
            self.rollback()
            raise Stop('Release failed and was rolled back; inspect private logs')

    def rollback(self):
        require(self.state is not None and self.state.get('rollback_images'), 'No completed rollback image set is available')
        for service, tag in self.state['rollback_images'].items():
            self.run(['docker', 'tag', self.state['rollback_image_ids'][service], tag], 'restore-rollback-image-tag')
        require(self.config([self.private / 'rollback-images.json']) ==
                json.loads((self.private / 'compose-rollback.json').read_text()),
                'Rollback Compose settings changed; restore the archived Compose sources first')
        self.run(self.compose([self.private / 'rollback-images.json']) +
                 ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate', *SERVICES],
                 'rollback-services', timeout=360, capture=False)
        self.health()
        self.verify_frontend()
        self.check_live_config(json.loads((self.private / 'compose-rollback.json').read_text()), self.live())
        self.state['phase'] = 'rolled-back'
        self.save_state()
        print('rollback: all three baseline services restored with archived nginx/index; database unchanged', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('preflight', 'build', 'status', 'deploy', 'rollback'))
    parser.add_argument('--root', default='/opt/heyi/deployments/video-script-recovery-20260915')
    parser.add_argument('--resume-preanalysis-only', action='store_true',
                        help='Permit restart only for image tasks with no structure result, generated assets, or pending image response; preserve them for the existing recovery scanner')
    parser.add_argument('--resume-scripts', action='store_true',
                        help='Preserve interrupted pure-analysis scripts for authenticated continuation after deployment')
    args = parser.parse_args()
    try:
        recovery = Recovery(args.root, args.resume_preanalysis_only, args.resume_scripts)
        getattr(recovery, args.mode)()
    except (Stop, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        # Never echo raw Docker/DB output, environment values, or exception payloads from those tools.
        message = str(error) if isinstance(error, Stop) else type(error).__name__ + '; inspect private logs/state'
        print('STOP: ' + message, file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
