"""Local SQLite/query and mock guard tests; never invokes Docker or a server."""
import contextlib
import io
from pathlib import Path
import runpy
import sqlite3
from types import SimpleNamespace
import unittest

MODULE = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'deploy-runtime-recovery.py'))
QUERY = MODULE['EXTERNAL_VIDEO_ACTIVITY_QUERY']


class ExternalVideoGuardTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''
            CREATE TABLE ai_video_tasks (id TEXT, organization_id TEXT, status TEXT);
            CREATE TABLE ai_video_task_items (id TEXT, task_id TEXT, organization_id TEXT, status TEXT);
            CREATE TABLE ai_video_generation_jobs (id TEXT, task_id TEXT, item_id TEXT, organization_id TEXT, status TEXT);
            CREATE TABLE video_scripts (id TEXT, status TEXT);
            CREATE TABLE ai_video_pending_image_results
                (id TEXT, task_id TEXT, item_id TEXT, organization_id TEXT, asset_type TEXT);
            CREATE TABLE ai_video_task_assets
                (id TEXT, task_id TEXT, item_id TEXT, organization_id TEXT,
                 asset_type TEXT, size_bytes INTEGER, file_path TEXT);
        ''')

    def family(self, statuses=('VIDEO_GENERATING', 'VIDEO_READY'), task_status='VIDEO_GENERATING'):
        self.db.execute('INSERT INTO ai_video_tasks VALUES (?,?,?)', ('task-a', 'org-a', task_status))
        for index, status in enumerate(statuses):
            item = f'item-{index}'
            self.db.execute('INSERT INTO ai_video_task_items VALUES (?,?,?,?)', (item, 'task-a', 'org-a', status))
            self.db.execute('INSERT INTO ai_video_generation_jobs VALUES (?,?,?,?,?)',
                            (f'job-{index}', 'task-a', item, 'org-a', 'RUNNING'))

    def counts(self):
        return dict(self.db.execute(QUERY).fetchall())

    def test_pure_external_video_family_is_exempt(self):
        self.family(('VIDEO_SUBMITTING', 'VIDEO_GENERATING', 'VIDEO_READY'))
        self.assertFalse(any(self.counts().values()))

    def test_mixed_image_work_does_not_qualify(self):
        for status in ('GENERATING_STORYBOARD', 'GENERATING_MODEL', 'IMAGE_READY', 'READY', 'FAILED', 'VIDEO_FAILED', None):
            with self.subTest(status=status):
                self.db.executescript('DELETE FROM ai_video_tasks; DELETE FROM ai_video_task_items; DELETE FROM ai_video_generation_jobs;')
                self.family(('VIDEO_GENERATING', status))
                counts = self.counts()
                self.assertEqual(counts['ai_video_tasks'], 1)
                self.assertEqual(counts['ai_video_generation_jobs'], 2)

    def test_empty_parent_and_image_generating_parent_remain_blocked(self):
        self.family(())
        self.assertEqual(self.counts()['ai_video_tasks'], 1)
        self.db.execute('UPDATE ai_video_tasks SET status = ?', ('IMAGE_GENERATING',))
        self.assertEqual(self.counts()['ai_video_tasks'], 1)

    def test_pending_paid_image_and_active_script_remain_blocked(self):
        self.family()
        self.db.execute('INSERT INTO ai_video_pending_image_results (id) VALUES (?)', ('paid-image',))
        self.db.execute('INSERT INTO video_scripts VALUES (?,?)', ('script-a', 'AI_ANALYZING'))
        counts = self.counts()
        self.assertEqual(counts['pending_image_results'], 1)
        self.assertEqual(counts['video_scripts'], 1)

    def completed_pending(self, with_asset=True):
        self.db.execute('INSERT INTO ai_video_tasks VALUES (?,?,?)', ('old-task', 'org-a', 'VIDEO_READY'))
        self.db.execute('INSERT INTO ai_video_task_items VALUES (?,?,?,?)',
                        ('old-item', 'old-task', 'org-a', 'VIDEO_READY'))
        self.db.execute('INSERT INTO ai_video_pending_image_results VALUES (?,?,?,?,?)',
                        ('paid-old', 'old-task', 'old-item', 'org-a', 'STORYBOARD_GRID'))
        if with_asset:
            self.db.execute('INSERT INTO ai_video_task_assets VALUES (?,?,?,?,?,?,?)',
                            ('stored-old', 'old-task', 'old-item', 'org-a', 'STORYBOARD_GRID', 4096, '/data/stored.png'))

    def test_completed_historical_pending_is_retained_and_exempt(self):
        self.completed_pending()
        self.assertEqual(self.counts()['pending_image_results'], 0)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM ai_video_pending_image_results').fetchone()[0], 1)

    def test_completed_pending_without_matching_asset_still_blocks(self):
        self.completed_pending(with_asset=False)
        self.assertEqual(self.counts()['pending_image_results'], 1)

    def test_invalid_or_mismatched_completed_asset_does_not_exempt_pending(self):
        self.completed_pending()
        for column, invalid in [('size_bytes', 0), ('file_path', ''), ('file_path', '   '), ('file_path', None),
                                ('asset_type', 'MODEL_IMAGE'), ('task_id', 'other-task'),
                                ('item_id', 'other-item'), ('organization_id', 'other-org')]:
            with self.subTest(column=column, invalid=invalid):
                self.db.execute('SAVEPOINT fixture')
                self.db.execute(f'UPDATE ai_video_task_assets SET {column} = ?', (invalid,))
                self.assertEqual(self.counts()['pending_image_results'], 1)
                self.db.execute('ROLLBACK TO fixture')
                self.db.execute('RELEASE fixture')

    def test_pending_for_a_running_task_or_unfinished_child_still_blocks(self):
        self.completed_pending()
        self.db.execute("UPDATE ai_video_tasks SET status = 'VIDEO_GENERATING'")
        self.assertEqual(self.counts()['ai_video_tasks'], 0)
        self.assertEqual(self.counts()['pending_image_results'], 1)
        self.db.execute("UPDATE ai_video_tasks SET status = 'VIDEO_READY'")
        self.db.execute("UPDATE ai_video_task_items SET status = 'IMAGE_READY'")
        self.assertEqual(self.counts()['pending_image_results'], 1)

    def test_mismatched_pending_ownership_is_not_exempt(self):
        self.completed_pending()
        self.db.execute("UPDATE ai_video_pending_image_results SET organization_id = 'other-org'")
        self.assertEqual(self.counts()['pending_image_results'], 1)

    def test_unrelated_or_wrong_company_video_job_is_not_exempt(self):
        self.family()
        self.db.execute('UPDATE ai_video_generation_jobs SET item_id = ? WHERE id = ?', ('unrelated-item', 'job-0'))
        self.db.execute('UPDATE ai_video_generation_jobs SET organization_id = ? WHERE id = ?', ('another-org', 'job-1'))
        self.assertEqual(self.counts()['ai_video_generation_jobs'], 2)

    def test_company_mismatched_child_blocks_the_entire_family(self):
        self.family()
        self.db.execute('UPDATE ai_video_task_items SET organization_id = ? WHERE id = ?', ('another-org', 'item-0'))
        self.assertEqual(self.counts()['ai_video_tasks'], 1)

    def helper(self, enabled, sockets=0, automation=0):
        helper = MODULE['RuntimeUpdate'].__new__(MODULE['RuntimeUpdate'])
        helper.args = SimpleNamespace(allow_external_video_work=enabled)
        helper.state = {'phase': 'built', 'commit': '1ebd884'}
        self.sql_calls = []
        def sql(query):
            self.sql_calls.append(query)
            if query == QUERY:
                return '\n'.join(f'{key}\t{count}' for key, count in self.counts().items())
            return f'workflow_runs\t{automation}\nautomation_commands\t0'
        helper.base = SimpleNamespace(sql=sql, active_counts=lambda: {'ai_video_tasks': 1},
                                      run=lambda *args: ('{"established":' + str(sockets) + '}').encode())
        def require(condition, message):
            if not condition:
                raise RuntimeError(message)
        helper.require = require
        helper.save = lambda: self.fail('Idle must not alter runtime-update-state')
        return helper

    def test_default_guard_does_not_use_the_exemption(self):
        self.family()
        helper = self.helper(False)
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError):
            helper.idle()
        self.assertNotIn(QUERY, self.sql_calls)

    def test_optional_guard_allows_only_when_other_activity_is_zero(self):
        self.family()
        with contextlib.redirect_stdout(io.StringIO()):
            helper = self.helper(True)
            helper.idle()
            self.assertEqual(helper.state, {'phase': 'built', 'commit': '1ebd884'})
            for sockets, automation in ((1, 0), (0, 1)):
                with self.assertRaises(RuntimeError):
                    self.helper(True, sockets, automation).idle()


if __name__ == '__main__':
    unittest.main()
