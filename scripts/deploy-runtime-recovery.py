#!/usr/bin/env python3
"""Narrow runtime-only build/deploy/rollback; depends on the sibling recovery helper.

Reads existing private/state.json solely for Compose context. Writes a separate
private/runtime-update-state.json, archives source hashes, and never restarts app
or frontend. Build is safe while work runs; deploy requires an idle window.
"""
import argparse
import copy
import json
from pathlib import Path
import re
import runpy
import shutil
import sys
import time
import urllib.request
import uuid

RUNTIME = 'pi-runtime'
CONTAINER = 'heyi-pi-runtime'
SOCKET_COUNTS = """const fs = require('fs'); let established = 0;
for (const path of ['/proc/net/tcp', '/proc/net/tcp6']) {
  for (const line of fs.readFileSync(path, 'utf8').trim().split('\\n').slice(1)) {
    const columns = line.trim().split(/\\s+/); if (columns[3] === '01') established++;
  }
} process.stdout.write(JSON.stringify({ established }));"""


class RuntimeUpdate:
    def __init__(self, args, support):
        self.base = support['Recovery'](args.root)
        self.require, self.digest = support['require'], support['digest']
        self.require(self.base.state is not None, 'Existing deployment private/state.json is required')
        self.args = args
        self.file = self.base.private / 'runtime-update-state.json'
        self.state = json.loads(self.file.read_text()) if self.file.exists() else None

    def save(self):
        self.base.save_json('runtime-update-state.json', self.state)
        self.base.save_json(self.state['snapshot'] + '/manifest.json', self.state)

    def files(self, release=True):
        prefix = self.base.private / self.state['snapshot']
        return [prefix / 'live-images.json', prefix / ('release.json' if release else 'rollback.json')]

    def archive_sources(self, label):
        directory = self.base.private / self.state['snapshot'] / (label + '-' + str(time.time_ns()))
        self.base.run(['docker', 'cp', CONTAINER + ':/app/src', str(directory)], 'archive-runtime-sources')
        hashes = {}
        for path in directory.rglob('*'):
            path.chmod(0o700 if path.is_dir() else 0o600)
            if path.is_file():
                hashes[str(path.relative_to(directory))] = self.digest(path)
        return hashes

    @staticmethod
    def assert_only_runtime_image_changed(before, after, require):
        comparison = copy.deepcopy(after)
        comparison['services'][RUNTIME]['image'] = before['services'][RUNTIME]['image']
        require(comparison == before, 'Resolved Compose changes more than the runtime image')

    def build(self):
        self.require(self.args.commit and re.fullmatch(r'[0-9a-fA-F]{7,40}', self.args.commit), 'Build requires --commit with the reviewed source commit')
        suffix = time.strftime('%Y%m%d-%H%M%S', time.gmtime()) + '-' + uuid.uuid4().hex[:6]
        snapshot = 'runtime-stream-' + suffix
        directory = self.base.private / snapshot
        directory.mkdir(mode=0o700)
        live = self.base.live()
        self.state = {'snapshot': snapshot, 'phase': 'building', 'commit': self.args.commit,
                      'containers': {key: value['Id'] for key, value in live.items()},
                      'image_ids': {key: value['Image'] for key, value in live.items()},
                      'tag': 'heyi-pi-runtime:video-script-stream-recovery-20260915-' + suffix,
                      'rollback_tag': 'heyi-pi-runtime:before-stream-recovery-' + suffix}
        self.base.save_json(snapshot + '/inspect-before.json', live)
        self.base.override(snapshot + '/live-images.json', {key: value['Config']['Image'] for key, value in live.items()})
        baseline = self.base.config([directory / 'live-images.json'])
        self.base.check_live_config(baseline, live)
        self.base.save_json(snapshot + '/compose-before.json', baseline)
        self.state['before_hashes'] = self.archive_sources('before')
        self.base.run(['docker', 'tag', live[RUNTIME]['Image'], self.state['rollback_tag']], 'pin-runtime-rollback')
        context = directory / 'build'
        (context / 'src').mkdir(parents=True)
        sources = Path(self.args.sources or self.base.root / 'pi-runtime/src')
        files = sorted(sources.glob('*.js'))
        self.require(files, 'No runtime JavaScript uploads found')
        self.state['upload_directory'] = str(sources.resolve())
        self.state['uploads'] = {path.name: self.digest(path) for path in files}
        expected = dict(self.state['before_hashes'])
        expected.update(self.state['uploads'])
        self.state['after_hashes'] = expected
        for path in files:
            shutil.copyfile(path, context / 'src' / path.name)
            (context / 'src' / path.name).chmod(0o644)
        dockerfile = f'FROM {self.state["rollback_tag"]}\nCOPY --chown=node:node src/ /app/src/\n'
        self.state['release_image_id'] = self.base.build_image(context, self.state['tag'], dockerfile)
        self.base.override(snapshot + '/release.json', {RUNTIME: self.state['tag']})
        self.base.override(snapshot + '/rollback.json', {RUNTIME: self.state['rollback_tag']})
        for released, name in [(True, 'compose-release.json'), (False, 'compose-rollback.json')]:
            resolved = self.base.config(self.files(released))
            self.assert_only_runtime_image_changed(baseline, resolved, self.require)
            self.base.save_json(snapshot + '/' + name, resolved)
        self.state['phase'] = 'built'
        self.save()
        print(json.dumps({'result': 'runtime_image_built', 'tag': self.state['tag'], 'commit': self.state['commit']}), flush=True)

    def idle(self):
        counts = self.base.active_counts()
        automation = self.base.sql("SELECT 'workflow_runs', COUNT(*) FROM workflow_runs WHERE status IN ('QUEUED','RUNNING') UNION ALL SELECT 'automation_commands', COUNT(*) FROM automation_commands WHERE status IN ('RUNNING','PAUSE_REQUESTED','INTERRUPT_REQUESTED','CANCEL_REQUESTED');")
        counts.update({line.split('\t')[0]: int(line.split('\t')[1]) for line in automation.splitlines()})
        sockets = json.loads(self.base.run(['docker', 'exec', CONTAINER, 'node', '-e', SOCKET_COUNTS], 'runtime-socket-count'))
        counts['runtime_established_sockets'] = sockets['established']
        # Session map sizes include retained idle conversations and are not activity gauges.
        print(json.dumps({'result': 'idle_check', 'active': counts}), flush=True)
        self.require(not any(counts.values()), 'Runtime/database work is active; no services changed')

    def siblings_unchanged(self, live):
        for service in ('app', 'frontend'):
            self.require(live[service]['Id'] == self.state['containers'][service] and
                         live[service]['Image'] == self.state['image_ids'][service], service + ' changed; runtime-only invariant violated')

    def verify_config(self, release):
        name = 'compose-release.json' if release else 'compose-rollback.json'
        saved = json.loads((self.base.private / self.state['snapshot'] / name).read_text())
        self.require(self.base.config(self.files(release)) == saved, 'Runtime Compose configuration drift')
        return saved

    def health(self):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            try:
                for service, port, path in [(RUNTIME, 8090, '/health'), ('app', 8080, '/actuator/health')]:
                    url = self.base.endpoint(service, port, path)
                    with opener.open(urllib.request.Request(url, headers={'Connection': 'close'}), timeout=5) as response:
                        self.require(json.loads(response.read())['status'] == 'UP', 'Service health is not UP')
                return
            except Exception:
                time.sleep(2)
        self.require(False, 'Runtime update health timed out')

    def up(self, release):
        self.base.run(self.base.compose(self.files(release)) +
                      ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate', RUNTIME],
                      'runtime-only-recreate', timeout=180, capture=False)
        self.health()
        self.siblings_unchanged(self.base.live())
        self.base.check_live_config(self.verify_config(release), self.base.live())
        expected_id = self.state['release_image_id'] if release else self.state['image_ids'][RUNTIME]
        self.require(self.base.inspect(CONTAINER)['Image'] == expected_id, 'Runtime image ID differs from the pinned build')
        self.require(self.archive_sources('verified') == self.state['after_hashes' if release else 'before_hashes'],
                     'Deployed runtime source hashes do not match the manifest')

    def deploy(self):
        self.require(self.state and self.state['phase'] == 'built', 'Build the runtime update first')
        live = self.base.live()
        self.siblings_unchanged(live)
        self.require(live[RUNTIME]['Id'] == self.state['containers'][RUNTIME] and live[RUNTIME]['Image'] == self.state['image_ids'][RUNTIME], 'Runtime changed after build')
        self.require(self.archive_sources('pre-deploy') == self.state['before_hashes'], 'Runtime source changed after build')
        for name, expected in self.state['uploads'].items():
            self.require(self.digest(Path(self.state['upload_directory']) / name) == expected, 'Runtime upload changed after build')
        self.require(self.base.inspect(self.state['tag'], 'image')['Id'] == self.state['release_image_id'], 'Runtime release tag changed')
        self.verify_config(True)
        # Final live check after preparation; failure here must never trigger a restart/rollback.
        self.idle()
        self.state['phase'] = 'deploying'
        self.save()
        try:
            self.up(True)
            self.state['phase'] = 'deployed'
            self.save()
            print(json.dumps({'result': 'runtime_deployed', 'tag': self.state['tag'], 'commit': self.state['commit'], 'app_frontend_restarted': False}), flush=True)
        except Exception:
            self.rollback(automatic=True)
            self.require(False, 'Runtime update failed and was rolled back; inspect private logs')

    def rollback(self, automatic=False):
        self.require(self.state and 'rollback_tag' in self.state, 'No runtime rollback snapshot exists')
        self.siblings_unchanged(self.base.live())
        self.verify_config(False)
        if not automatic:
            self.idle()
        self.base.run(['docker', 'tag', self.state['image_ids'][RUNTIME], self.state['rollback_tag']], 'restore-runtime-rollback-tag')
        self.up(False)
        self.state['phase'] = 'rolled-back'
        self.save()
        print(json.dumps({'result': 'runtime_rolled_back', 'app_frontend_restarted': False}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('build', 'idle', 'deploy', 'rollback'))
    parser.add_argument('--root', default='/opt/heyi/deployments/video-script-recovery-20260915')
    parser.add_argument('--support-script', default=str(Path(__file__).with_name('deploy-video-script-recovery.py')))
    parser.add_argument('--sources')
    parser.add_argument('--commit')
    args = parser.parse_args()
    try:
        support = runpy.run_path(args.support_script)
        getattr(RuntimeUpdate(args, support), args.mode)()
        return 0
    except Exception as error:
        safe = str(error) if error.__class__.__name__ == 'Stop' else 'Runtime helper failed; inspect private logs'
        print(json.dumps({'error': safe}), file=sys.stderr, flush=True)
        return 1


if __name__ == '__main__':
    sys.exit(main())
