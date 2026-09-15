#!/usr/bin/env python3
"""App-only update; preserve the currently running frontend and runtime.

Uses the preceding recovery's Compose context. Private inspection, backups and
rollback data go to this release's directory. Accepted external video jobs may
continue at their provider; local model/image work and unaccepted submits block.
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
import uuid


def assert_app_only(before, after, require):
    comparison = copy.deepcopy(after)
    comparison['services']['app']['image'] = before['services']['app']['image']
    require(comparison == before, 'Compose changes more than the app image')


class AppUpdate:
    def __init__(self, args):
        support = runpy.run_path(str(Path(args.context) / 'deploy-video-script-recovery.py'))
        runtime = runpy.run_path(str(Path(args.context) / 'deploy-runtime-recovery.py'))
        self.base = support['Recovery'](args.context)
        self.require, self.digest = support['require'], support['digest']
        self.require(self.base.state is not None, 'Existing recovery context is required')
        self.base.root = Path(args.root).resolve()
        self.base.private = self.base.root / 'private'
        self.base.private.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.base.private.chmod(0o700)
        self.base.logs = self.base.private / 'deploy-logs'
        self.base.logs.mkdir(mode=0o700, exist_ok=True)
        self.base.state_file = self.base.private / 'compose-context.json'
        self.path = self.base.private / 'app-update-state.json'
        self.state = json.loads(self.path.read_text()) if self.path.exists() else None
        self.activity_query = runtime['EXTERNAL_VIDEO_ACTIVITY_QUERY']
        self.socket_script = runtime['SOCKET_COUNTS']
        self.args = args

    def save(self):
        self.base.save_json('app-update-state.json', self.state)

    def files(self, release=True):
        return [self.base.private / 'live-images.json',
                self.base.private / ('app-release.json' if release else 'app-rollback.json')]

    def window(self):
        rows = self.base.sql(self.activity_query)
        counts = {row.split('\t')[0]: int(row.split('\t')[1]) for row in rows.splitlines()}
        self.require(set(counts) == {'video_scripts', 'ai_video_tasks', 'ai_video_task_items',
                                    'ai_video_generation_jobs', 'pending_image_results'}, 'Incomplete activity snapshot')
        extra = self.base.sql("""SELECT 'unsafe_video_jobs', COUNT(*) FROM ai_video_generation_jobs
WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED')
AND (status NOT IN ('RUNNING','QUEUED') OR request_state <> 'ACCEPTED'
     OR NULLIF(TRIM(provider_task_id),'') IS NULL)
UNION ALL SELECT 'workflow_runs', COUNT(*) FROM workflow_runs WHERE status IN ('QUEUED','RUNNING')
UNION ALL SELECT 'automation_commands', COUNT(*) FROM automation_commands
WHERE status IN ('RUNNING','PAUSE_REQUESTED','INTERRUPT_REQUESTED','CANCEL_REQUESTED');""")
        extra_counts = {row.split('\t')[0]: int(row.split('\t')[1]) for row in extra.splitlines()}
        self.require(set(extra_counts) == {'unsafe_video_jobs', 'workflow_runs', 'automation_commands'}, 'Incomplete submission snapshot')
        counts.update(extra_counts)
        sockets = json.loads(self.base.run(['docker', 'exec', 'heyi-pi-runtime', 'node', '-e',
                                            self.socket_script], 'runtime-connections'))
        counts['runtime_connections'] = sockets['established']
        print(json.dumps({'result': 'restart_window', 'blocking': counts}), flush=True)
        self.require(not any(counts.values()), 'Local generation or unaccepted requests are active; update refused')

    def siblings(self, live):
        for service in ('pi-runtime', 'frontend'):
            self.require(live[service]['Id'] == self.state['containers'][service]
                         and live[service]['Image'] == self.state['image_ids'][service],
                         service + ' changed during app-only update')

    def verify_config(self, release):
        name = 'compose-app-release.json' if release else 'compose-app-rollback.json'
        saved = json.loads((self.base.private / name).read_text())
        self.require(self.base.config(self.files(release)) == saved, 'Compose configuration drift')
        return saved

    def build(self):
        self.require(self.args.commit and re.fullmatch(r'[0-9a-f]{7,40}', self.args.commit), 'Reviewed commit is required')
        self.require(self.state is None, 'This release already has an app build; use a new release directory')
        self.base.check_migrations()
        live = self.base.live()
        suffix = time.strftime('%Y%m%d-%H%M%S', time.gmtime()) + '-' + uuid.uuid4().hex[:6]
        self.state = {'phase': 'building', 'commit': self.args.commit,
                      'containers': {k: v['Id'] for k, v in live.items()},
                      'image_ids': {k: v['Image'] for k, v in live.items()},
                      'tag': 'ai-commerce-platform:video-reference-recovery-' + suffix,
                      'rollback_tag': 'ai-commerce-platform:before-video-reference-' + suffix}
        self.base.save_json('inspect-app-before.json', live)
        self.base.override('live-images.json', {k: v['Config']['Image'] for k, v in live.items()})
        baseline = self.base.config([self.base.private / 'live-images.json'])
        self.base.check_live_config(baseline, live)
        self.base.save_json('compose-app-before.json', baseline)
        old = self.base.archive_copy('ai-commerce-app', '/app/app.jar', 'app-before.jar')
        self.state['before_sha256'] = self.digest(old)
        self.state['after_sha256'] = self.digest(self.base.root / 'app.jar')
        self.base.run(['docker', 'tag', live['app']['Image'], self.state['rollback_tag']], 'pin-app-rollback')
        context = self.base.private / 'app-build'
        context.mkdir(mode=0o700)
        shutil.copyfile(self.base.root / 'app.jar', context / 'app.jar')
        (context / 'app.jar').chmod(0o644)
        dockerfile = f'FROM {self.state["rollback_tag"]}\nCOPY --chown=spring:spring app.jar /app/app.jar\n'
        self.state['release_image_id'] = self.base.build_image(context, self.state['tag'], dockerfile)
        self.base.override('app-release.json', {'app': self.state['tag']})
        self.base.override('app-rollback.json', {'app': self.state['rollback_tag']})
        for release, name in [(True, 'compose-app-release.json'), (False, 'compose-app-rollback.json')]:
            resolved = self.base.config(self.files(release))
            assert_app_only(baseline, resolved, self.require)
            self.base.save_json(name, resolved)
        self.state['phase'] = 'built'
        self.save()
        print(json.dumps({'result': 'app_built', 'tag': self.state['tag'], 'sha256': self.state['after_sha256']}), flush=True)

    def up(self, release):
        config = self.verify_config(release)
        if release:
            self.require(self.base.inspect(self.state['tag'], 'image')['Id'] == self.state['release_image_id'], 'Release tag changed before startup')
        else:
            self.base.run(['docker', 'tag', self.state['image_ids']['app'], self.state['rollback_tag']], 'repin-app-rollback')
        self.base.run(self.base.compose(self.files(release)) +
                      ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate', 'app'],
                      'app-only-recreate', timeout=180, capture=False)
        self.base.health()
        live = self.base.live()
        self.siblings(live)
        self.base.check_live_config(config, live)
        expected = self.state['release_image_id'] if release else self.state['image_ids']['app']
        self.require(live['app']['Image'] == expected, 'App image differs from pinned build')
        jar = self.base.archive_copy('ai-commerce-app', '/app/app.jar', 'app-verified.jar')
        self.require(self.digest(jar) == self.state['after_sha256' if release else 'before_sha256'], 'App JAR checksum mismatch')

    def deploy(self):
        self.require(self.state and self.state['phase'] == 'built', 'Build app first')
        live = self.base.live()
        self.siblings(live)
        self.require(live['app']['Id'] == self.state['containers']['app']
                     and live['app']['Image'] == self.state['image_ids']['app'], 'App changed since build')
        self.require(self.digest(self.base.root / 'app.jar') == self.state['after_sha256'], 'Uploaded JAR changed')
        self.verify_config(True)
        self.base.check_migrations()
        self.window()
        self.base.backup_database()
        self.window()
        self.state['phase'] = 'deploying'
        self.save()
        try:
            self.base.run(['docker', 'stop', '--time', '60', 'ai-commerce-app'], 'stop-app', timeout=100)
            self.window()
            self.up(True)
            self.state['phase'] = 'deployed'
            self.save()
            print(json.dumps({'result': 'app_deployed', 'commit': self.state['commit'],
                              'runtimeFrontendRestarted': False}), flush=True)
        except Exception:
            self.up(False)
            self.state['phase'] = 'rolled_back'
            self.save()
            raise RuntimeError('App update failed and was rolled back; inspect private logs') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'deploy', 'status'])
    parser.add_argument('--root', required=True)
    parser.add_argument('--context', required=True)
    parser.add_argument('--commit')
    args = parser.parse_args()
    try:
        update = AppUpdate(args)
        if args.action == 'build':
            update.build()
        elif args.action == 'deploy':
            update.deploy()
        else:
            update.window()
        return 0
    except Exception:
        print(json.dumps({'error': 'App update could not complete; inspect private deploy logs'}), flush=True)
        return 1


if __name__ == '__main__':
    sys.exit(main())
