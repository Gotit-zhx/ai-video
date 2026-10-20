import contextlib
import json
from pathlib import Path
import sqlite3
import tempfile
import time
import unittest
from unittest import mock

import boss_chat_receipts as receipts
import passive_runtime as flow


class ResumeReuseTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name).resolve()
        self.c = mock.Mock()
        self.c.RESUME_DIR = self.root / 'downloads'
        self.c.RESUME_DIR.mkdir()
        self.c.held_code.return_value = ''
        self.c.passive_delay = lambda **kw: 0
        self.c.accept_incoming_resume = None
        self.downloads = []

        @contextlib.contextmanager
        def resume_db():
            with contextlib.closing(sqlite3.connect(self.root / 'resumes.sqlite3')) as db:
                db.row_factory = sqlite3.Row
                with db:
                    db.execute('CREATE TABLE IF NOT EXISTS resume_requests(friend_id TEXT PRIMARY KEY,state TEXT,message_id TEXT)')
                    db.execute('CREATE TABLE IF NOT EXISTS resume_files(friend_id TEXT,message_id TEXT,original_name TEXT)')
                    yield db
        self.c.resume_db = resume_db
        with resume_db() as db:
            db.execute("INSERT INTO resume_requests VALUES('1','received','101')")
        self.c.download_resume.side_effect = self.invitation_download
        self.c.cli_json.side_effect = self.collector_download

    def save(self, file, friend, mid):
        self.downloads.append((friend, mid))
        file.write_bytes(b'%PDF-1.4\nisolated resume fixture\n%%EOF')
        return file

    def invitation_download(self, friend, mid):
        return self.save(self.c.RESUME_DIR / f'invitation-{friend}-{mid}.pdf', friend, mid)

    def collector_download(self, *args):
        self.assertEqual(args[:2], ('hr', 'download-resume'))
        self.save(Path(args[-1]), args[2], args[4])
        return {'ok': True}

    def person(self, db, *, state='requested', file='', mid='', origin='active', friend='1'):
        detail = {'origin': origin, 'jobId': 'invitation-job', 'name': '测试候选人',
                  'originalFileName': 'original.pdf'}
        db.execute('INSERT INTO people(friend,rule,body,state,updated,file,mid) VALUES(?,?,?,?,0,?,?)',
                   (friend, 'invitation-rule', json.dumps(detail), state, str(file), mid))
        return detail

    def receipt(self, db, *, state='found', file='', friend='1', mid='101'):
        receipts.schema(db)
        detail = {'origin': 'boss_chat', 'jobId': 'message-job', 'originalFileName': '收到的简历.pdf'}
        db.execute('INSERT INTO chat_resume_receipts(friend,mid,rule,body,state,file) VALUES(?,?,?,?,?,?)',
                   (friend, mid, 'chat-rule', json.dumps(detail), state, str(file)))
        return db.execute('SELECT * FROM chat_resume_receipts WHERE friend=? AND mid=?', (friend, mid)).fetchone()

    def test_invitation_first_reuses_after_restart_without_changing_source_or_cloud_state(self):
        with flow.database(self.root) as db:
            detail = self.person(db)
            flow.receive(self.c, db, friend='1')
            original = dict(db.execute('SELECT * FROM people').fetchone())
            self.assertEqual(original['state'], 'export')
        with flow.database(self.root) as db:
            row = self.receipt(db)
            self.assertTrue(receipts.download(self.c, db, row)['ok'])
            reused = db.execute('SELECT * FROM chat_resume_receipts').fetchone()
            self.assertEqual(reused['file'], original['file'])
            self.assertEqual(reused['state'], 'export')
            self.assertEqual(reused['body'], row['body'])
            self.assertEqual(reused['rule'], 'chat-rule')
            self.assertEqual(json.loads(db.execute('SELECT body FROM people').fetchone()[0]), detail)
            receipts.acknowledge(db, '1', '999')
            self.assertEqual(receipts.exports(db)[0]['state'], 'export')
            receipts.acknowledge(db, '1', '101')
            self.assertEqual(db.execute('SELECT state FROM chat_resume_receipts').fetchone()[0], 'stored')
            self.assertEqual(db.execute('SELECT state FROM people').fetchone()[0], 'export')
        self.assertEqual(self.downloads, [('1', '101')])
        self.c.cli_json.assert_not_called()
        self.c.request_resume.assert_not_called()

    def test_collector_first_preserves_active_and_passive_job_and_requires_separate_cloud_ack(self):
        for origin in ('active', 'passive'):
            with self.subTest(origin=origin), flow.database(self.root) as db:
                db.execute('DELETE FROM people')
                receipts.schema(db)
                db.execute('DELETE FROM chat_resume_receipts')
                self.downloads.clear()
                row = self.receipt(db)
                receipts.download(self.c, db, row)
                receipts.acknowledge(db, '1', '101')
                original = dict(db.execute('SELECT * FROM chat_resume_receipts').fetchone())
                detail = self.person(db, origin=origin)
                flow.receive(self.c, db, friend='1')
                person = db.execute('SELECT * FROM people').fetchone()
                self.assertEqual(person['state'], 'export')
                self.assertEqual(person['file'], original['file'])
                self.assertEqual(person['mid'], '101')
                self.assertEqual(person['rule'], 'invitation-rule')
                detail['originalFileName'] = '收到的简历.pdf'
                self.assertEqual(json.loads(person['body']), detail)
                self.assertEqual(dict(db.execute('SELECT * FROM chat_resume_receipts').fetchone()), original)
                self.assertEqual(self.downloads, [('1', '101')])
        self.c.download_resume.assert_not_called()
        self.c.poll_resumes_once.assert_not_called()
        self.c.request_resume.assert_not_called()

    def test_other_message_or_candidate_still_downloads(self):
        with flow.database(self.root) as db:
            self.person(db)
            flow.receive(self.c, db, friend='1')
            for friend, mid in [('1', '102'), ('2', '101')]:
                row = self.receipt(db, friend=friend, mid=mid)
                receipts.download(self.c, db, row)
            self.assertEqual(self.downloads, [('1', '101'), ('1', '102'), ('2', '101')])

    def test_invalid_cache_falls_back_to_one_download_in_either_direction(self):
        good = self.c.RESUME_DIR / 'valid.pdf'
        good.write_bytes(b'%PDF-1.4\nfixture')
        empty = self.c.RESUME_DIR / 'empty.pdf'
        empty.touch()
        invalid = self.c.RESUME_DIR / 'error.pdf'
        invalid.write_bytes(b'<html>login required</html>')
        outside = self.root / 'other-account.pdf'
        outside.write_bytes(good.read_bytes())
        link = self.c.RESUME_DIR / 'escape.pdf'
        link.symlink_to(outside)
        folder = self.c.RESUME_DIR / 'directory.pdf'
        folder.mkdir()
        cases = [(good, 'found'), (self.c.RESUME_DIR / 'missing.pdf', 'stored'),
                 (empty, 'stored'), (invalid, 'export'), (outside, 'stored'),
                 (link, 'stored'), (folder, 'stored')]
        for direction in ('invitation-first', 'collector-first'):
            for file, state in cases:
                with self.subTest(direction=direction, file=file.name, state=state), flow.database(self.root) as db:
                    receipts.schema(db)
                    db.execute('DELETE FROM people')
                    db.execute('DELETE FROM chat_resume_receipts')
                    self.downloads.clear()
                    if direction == 'invitation-first':
                        self.person(db, state=state, file=file, mid='101')
                        row = self.receipt(db)
                        receipts.download(self.c, db, row)
                        saved = db.execute('SELECT state,file FROM chat_resume_receipts').fetchone()
                    else:
                        self.receipt(db, state=state, file=file)
                        self.person(db)
                        flow.receive(self.c, db, friend='1')
                        saved = db.execute('SELECT state,file FROM people').fetchone()
                    self.assertEqual(self.downloads, [('1', '101')])
                    self.assertEqual(saved['state'], 'export')
                    self.assertNotEqual(Path(saved['file']), file)

    def test_resume_directory_cannot_point_to_another_account(self):
        other = self.root / 'other-account'
        other.mkdir()
        file = other / 'resume.pdf'
        file.write_bytes(b'%PDF-1.4\nfixture')
        self.c.RESUME_DIR.rmdir()
        self.c.RESUME_DIR.symlink_to(other, target_is_directory=True)
        with flow.database(self.root) as db:
            self.person(db, state='stored', file=file, mid='101')
            self.assertIsNone(flow.reusable_resume(self.c, db, '1', '101'))
        self.c.RESUME_DIR = self.root.parent
        with flow.database(self.root) as db:
            self.assertIsNone(flow.reusable_resume(self.c, db, '1', '101'))

    def test_supported_document_and_image_attachments_can_be_reused(self):
        headers = [b'%PDF-1.4', b'PK\x03\x04document', b'\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1',
                   b'\x89PNG\r\n\x1a\n', b'\xff\xd8\xff\xe0JPEG']
        file = self.c.RESUME_DIR / 'attachment'
        with flow.database(self.root) as db:
            self.person(db, state='stored', file=file, mid='101')
            for header in headers:
                with self.subTest(header=header):
                    file.write_bytes(header)
                    self.assertEqual(flow.reusable_resume(self.c, db, '1', '101')['file'], str(file))
        self.assertEqual(self.downloads, [])

    def test_reuse_keeps_existing_download_pacing_and_does_not_burst_next_network_read(self):
        now = time.time()
        with flow.database(self.root) as db:
            self.person(db)
            flow.receive(self.c, db, friend='1')
            self.receipt(db)
            self.receipt(db, mid='102')
            flow.put_state(db, 'chat-resumes-last-request-at', now-599)
            with mock.patch.object(receipts.time, 'time', return_value=now):
                self.assertIsNone(receipts.step(self.c, db, {}))
                self.assertEqual(receipts.exports(db), [])
            with mock.patch.object(receipts.time, 'time', return_value=now+1):
                self.assertTrue(receipts.step(self.c, db, {})['data']['reused'])
                self.assertIsNone(receipts.step(self.c, db, {}))
            with mock.patch.object(receipts.time, 'time', return_value=now+600):
                self.assertIsNone(receipts.step(self.c, db, {}))
            self.assertEqual(self.downloads, [('1', '101')])
            with mock.patch.object(receipts.time, 'time', return_value=now+601):
                self.assertTrue(receipts.step(self.c, db, {})['ok'])
            self.assertEqual(self.downloads, [('1', '101'), ('1', '102')])


if __name__ == '__main__':
    unittest.main()
