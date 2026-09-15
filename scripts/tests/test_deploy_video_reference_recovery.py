"""Local SQL/config and mocked deployment checks; never contacts production."""
import contextlib
import copy
import io
from pathlib import Path
import runpy
import sqlite3
from types import SimpleNamespace
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
MODULE = runpy.run_path(str(SCRIPTS / 'deploy-video-reference-recovery.py'))
RUNTIME = runpy.run_path(str(SCRIPTS / 'deploy-runtime-recovery.py'))
AppUpdate = MODULE['AppUpdate']


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class AppOnlyConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.before = {
            'name': 'production',
            'services': {
                name: {'image': name + ':live', 'environment': {'SETTING': 'retained'},
                       'ports': [{'target': 8080, 'published': '8080'}],
                       'volumes': [{'source': 'data', 'target': '/data'}],
                       'networks': {'default': None}}
                for name in ('app', 'pi-runtime', 'frontend')
            },
            'networks': {'default': {'name': 'production_default'}},
            'volumes': {'data': {'name': 'production_data'}},
        }

    def test_only_app_image_change_is_allowed_without_mutating_inputs(self):
        after = copy.deepcopy(self.before)
        after['services']['app']['image'] = 'app:reviewed'
        saved = copy.deepcopy(after)
        MODULE['assert_app_only'](self.before, after, require)
        self.assertEqual(after, saved)

    def test_configuration_or_sibling_image_drift_is_rejected(self):
        for service in ('app', 'pi-runtime', 'frontend'):
            for field in ('environment', 'ports', 'volumes', 'networks', 'image'):
                if service == 'app' and field == 'image':
                    continue
                with self.subTest(service=service, field=field):
                    after = copy.deepcopy(self.before)
                    after['services']['app']['image'] = 'app:reviewed'
                    after['services'][service][field] = 'changed'
                    with self.assertRaises(RuntimeError):
                        MODULE['assert_app_only'](self.before, after, require)
        for field in ('name', 'networks', 'volumes'):
            with self.subTest(field=field):
                after = copy.deepcopy(self.before)
                after[field] = 'changed'
                with self.assertRaises(RuntimeError):
                    MODULE['assert_app_only'](self.before, after, require)


class AppWindowTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''
            CREATE TABLE ai_video_tasks (id TEXT, organization_id TEXT, status TEXT);
            CREATE TABLE ai_video_task_items (id TEXT, task_id TEXT, organization_id TEXT, status TEXT);
            CREATE TABLE ai_video_generation_jobs
                (id TEXT, task_id TEXT, item_id TEXT, organization_id TEXT, status TEXT,
                 request_state TEXT NOT NULL, provider_task_id TEXT);
            CREATE TABLE video_scripts (id TEXT, status TEXT);
            CREATE TABLE ai_video_pending_image_results
                (id TEXT, task_id TEXT, item_id TEXT, organization_id TEXT, asset_type TEXT);
            CREATE TABLE ai_video_task_assets
                (id TEXT, task_id TEXT, item_id TEXT, organization_id TEXT,
                 asset_type TEXT, size_bytes INTEGER, file_path TEXT);
            CREATE TABLE workflow_runs (status TEXT);
            CREATE TABLE automation_commands (status TEXT);
            INSERT INTO ai_video_tasks VALUES ('task', 'org', 'VIDEO_GENERATING');
            INSERT INTO ai_video_task_items VALUES ('item', 'task', 'org', 'VIDEO_GENERATING');
            INSERT INTO ai_video_generation_jobs VALUES
                ('job', 'task', 'item', 'org', 'RUNNING', 'ACCEPTED', 'provider-job');
        ''')
        self.helper = AppUpdate.__new__(AppUpdate)
        self.helper.require = require
        self.helper.activity_query = RUNTIME['EXTERNAL_VIDEO_ACTIVITY_QUERY']
        self.helper.socket_script = RUNTIME['SOCKET_COUNTS']
        self.connections = 0
        self.queries = []

        def sql(query):
            self.queries.append(query)
            return '\n'.join(f'{key}\t{count}' for key, count in self.db.execute(query))

        self.helper.base = SimpleNamespace(
            sql=sql, run=lambda *args: '{"established":' + str(self.connections) + '}')
        self.helper.save = lambda: self.fail('A restart-window check must not modify saved state')

    def window(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.helper.window()

    def test_accepted_provider_jobs_survive_restart_window(self):
        for status in ('RUNNING', 'QUEUED'):
            with self.subTest(status=status):
                self.db.execute('UPDATE ai_video_generation_jobs SET status=?', (status,))
                self.window()
        self.assertTrue(all(query.lstrip().startswith(('SELECT', 'WITH')) for query in self.queries))

    def test_unaccepted_or_unidentifiable_provider_jobs_block(self):
        for status, request_state, provider_id in (
            ('RUNNING', 'PENDING', 'provider-job'),
            ('QUEUED', 'SUBMITTING', 'provider-job'),
            ('SUBMITTING', 'ACCEPTED', 'provider-job'),
            ('RUNNING', 'ACCEPTED', None),
            ('RUNNING', 'ACCEPTED', ''),
            ('RUNNING', 'ACCEPTED', '  '),
        ):
            with self.subTest(status=status, request_state=request_state, provider_id=provider_id):
                self.db.execute('UPDATE ai_video_generation_jobs SET status=?,request_state=?,provider_task_id=?',
                                (status, request_state, provider_id))
                with self.assertRaises(RuntimeError):
                    self.window()

    def test_local_image_work_and_unrelated_jobs_block(self):
        for statement in (
            "UPDATE ai_video_tasks SET status='IMAGE_GENERATING'",
            "UPDATE ai_video_task_items SET status='GENERATING_STORYBOARD'",
            "UPDATE ai_video_generation_jobs SET item_id='different-item'",
            "INSERT INTO ai_video_pending_image_results (id) VALUES ('pending')",
            "INSERT INTO video_scripts VALUES ('script', 'AI_ANALYZING')",
        ):
            with self.subTest(statement=statement):
                self.db.execute('SAVEPOINT fixture')
                self.db.execute(statement)
                with self.assertRaises(RuntimeError):
                    self.window()
                self.db.execute('ROLLBACK TO fixture')
                self.db.execute('RELEASE fixture')

    def test_runtime_connections_and_active_automation_block(self):
        self.connections = 1
        with self.assertRaises(RuntimeError):
            self.window()
        self.connections = 0
        for table, statuses in (
            ('workflow_runs', ('QUEUED', 'RUNNING')),
            ('automation_commands', ('RUNNING', 'PAUSE_REQUESTED', 'INTERRUPT_REQUESTED', 'CANCEL_REQUESTED')),
        ):
            for status in statuses:
                with self.subTest(table=table, status=status):
                    self.db.execute('SAVEPOINT fixture')
                    self.db.execute(f'INSERT INTO {table} VALUES (?)', (status,))
                    with self.assertRaises(RuntimeError):
                        self.window()
                    self.db.execute('ROLLBACK TO fixture')
                    self.db.execute('RELEASE fixture')

    def test_missing_or_unexpected_sql_result_keys_fail_closed(self):
        sql = self.helper.base.sql
        for query_group in ('activity', 'submission'):
            for corruption in ('missing', 'unexpected', 'renamed'):
                with self.subTest(query_group=query_group, corruption=corruption):
                    def incomplete(query):
                        rows = sql(query).splitlines()
                        if (query == self.helper.activity_query) == (query_group == 'activity'):
                            if corruption == 'missing':
                                rows.pop()
                            elif corruption == 'unexpected':
                                rows.append('unexpected\t0')
                            else:
                                rows[-1] = 'unexpected\t0'
                        return '\n'.join(rows)
                    self.helper.base.sql = incomplete
                    with self.assertRaisesRegex(RuntimeError, 'Incomplete'):
                        self.window()
        self.helper.base.sql = sql


class AppDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.helper = AppUpdate.__new__(AppUpdate)
        self.helper.require = require
        self.helper.state = {
            'phase': 'built', 'commit': '1234567',
            'containers': {name: name + '-container' for name in ('app', 'pi-runtime', 'frontend')},
            'image_ids': {name: name + '-image' for name in ('app', 'pi-runtime', 'frontend')},
            'release_image_id': 'app-release-image', 'tag': 'app:release',
            'rollback_tag': 'app:rollback', 'before_sha256': 'before', 'after_sha256': 'after',
        }
        self.live = {name: {'Id': name + '-container', 'Image': name + '-image'}
                     for name in ('app', 'pi-runtime', 'frontend')}
        self.events = []
        self.commands = []
        self.helper.verify_config = lambda release: {'release': release}
        self.helper.digest = lambda path: 'before' if str(path).endswith('before') else 'after'
        self.helper.save = lambda: self.events.append('save:' + self.helper.state['phase'])

        def run(command, label, **kwargs):
            self.commands.append(command)
            self.events.append(label)
            return ''

        self.helper.base = SimpleNamespace(
            root=Path('/not-used'), private=Path('/not-used/private'), run=run,
            compose=lambda files: ['docker', 'compose', '-f', str(files[-1])],
            live=lambda: copy.deepcopy(self.live), health=lambda: self.events.append('health'),
            inspect=lambda tag, kind: {'Id': self.helper.state['release_image_id']},
            check_live_config=lambda config, live: self.events.append('check-live-config'),
            archive_copy=lambda *args: Path('after'),
            check_migrations=lambda: self.events.append('migrations'),
            backup_database=lambda: self.events.append('backup'))

    def test_up_recreates_only_app_and_verifies_siblings(self):
        self.live['app']['Image'] = 'app-release-image'
        self.helper.up(True)
        compose = [command for command in self.commands if command[:2] == ['docker', 'compose']]
        self.assertEqual(len(compose), 1)
        self.assertEqual(compose[0][4:],
                         ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate', 'app'])
        self.assertIn('health', self.events)
        for sibling in ('pi-runtime', 'frontend'):
            self.live[sibling]['Id'] = 'unexpected-container'
            with self.assertRaisesRegex(RuntimeError, 'changed during app-only update'):
                self.helper.up(True)
            self.live[sibling]['Id'] = sibling + '-container'

    def test_activity_appearing_after_stop_rolls_back_app_without_starting_release(self):
        checks = 0

        def window():
            nonlocal checks
            checks += 1
            self.events.append('window')
            if checks == 3:
                raise RuntimeError('new local activity')

        self.helper.window = window
        self.helper.up = lambda release: self.events.append('release' if release else 'rollback')
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(RuntimeError, 'was rolled back'):
            self.helper.deploy()
        self.assertEqual(checks, 3)
        self.assertNotIn('release', self.events)
        self.assertEqual(self.helper.state['phase'], 'rolled_back')
        self.assertLess(self.events.index('backup'), self.events.index('stop-app'))
        self.assertLess(self.events.index('stop-app'), self.events.index('rollback'))
        self.assertIn(['docker', 'stop', '--time', '60', 'ai-commerce-app'], self.commands)

    def test_activity_before_stop_leaves_running_containers_untouched(self):
        def window():
            raise RuntimeError('active work')

        self.helper.window = window
        self.helper.up = lambda release: self.fail('No container should be recreated')
        with self.assertRaisesRegex(RuntimeError, 'active work'):
            self.helper.deploy()
        self.assertFalse(self.commands)
        self.assertNotIn('backup', self.events)
        self.assertEqual(self.helper.state['phase'], 'built')

    def test_changed_release_tag_is_rejected_before_container_start(self):
        self.helper.base.inspect = lambda *args: {'Id': 'unexpected-image'}
        with self.assertRaisesRegex(RuntimeError, 'Release tag changed'):
            self.helper.up(True)
        self.assertFalse(self.commands)
        self.assertNotIn('health', self.events)

    def test_rollback_repins_saved_image_before_recreating_app(self):
        self.helper.base.archive_copy = lambda *args: Path('before')
        self.helper.up(False)
        self.assertEqual(self.commands[0], ['docker', 'tag', 'app-image', 'app:rollback'])
        self.assertEqual(self.commands[1][:2], ['docker', 'compose'])
        self.assertEqual(self.commands[1][-1], 'app')
        self.assertIn('--no-deps', self.commands[1])


if __name__ == '__main__':
    unittest.main()
