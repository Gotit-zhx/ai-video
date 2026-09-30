import base64
import json
from pathlib import Path
import tempfile
import time
import unittest
import uuid
from unittest import mock

import hr_sourcing as s
import passive_pool as passive
import talent_pool as pool
import resume_storage

USER = {'id': 'hr', 'username': '13800000000', 'organizationCode': 'heyi', 'platformAdmin': True, 'roles': ['ADMIN']}
KEY = 'a'*64


class SourcingTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory(); self.addCleanup(folder.cleanup)
        patch = mock.patch.object(pool, 'DB', Path(folder.name)/'test.db')
        patch.start(); self.addCleanup(patch.stop)
        self.job = s.save(USER, {'accountKey': KEY, 'jobId': 'job1', 'jobName': '开发工程师',
                                'description': '开发业务系统，熟悉 Java 并有实际工程交付经验。'})

    def params(self):
        return {k: self.job[k] for k in ('id', 'version', 'accountKey')}

    def upload(self, raw=b'%PDF-1.4 fixture'):
        with mock.patch.object(resume_storage, 'extract', return_value=('负责 Java 业务系统开发，完善接口测试与上线监控。'*5, '正文已提取')), \
                mock.patch.object(resume_storage, 'store', return_value='private/key') as store:
            self.job = s.references(USER, {**self.params(), 'filename': '参考员工.pdf', 'fileBase64': base64.b64encode(raw).decode()})
        return store

    def ready(self):
        self.upload()
        with mock.patch.object(s.threading, 'Thread') as worker:
            self.job = s.profile(USER, self.params(), 'fixture')
            self.assertEqual(worker.call_count, 1)
        with pool.database() as db:
            s.schema(db)
            row = db.execute('SELECT * FROM hr_sourcing_jobs WHERE id=?', (self.job['id'],)).fetchone()
        with mock.patch.object(s, 'driver', return_value={'suggestedJd': '开发、测试和交付', 'summary': '工程能力',
                     'skills': ['Java'], 'evidence': [{'requirement': '开发', 'evidence': '参考简历有交付经历'}]}):
            s.profile_worker('heyi', self.job['id'], row['profile_token'], {}, 'fixture')
        self.job = s.listing(USER, self.params())

    def card(self, geek='geek1', **extra):
        return {'encryptGeekId': geek, 'encryptJobId': 'job1', 'expectId': 'expect1',
                'lid': 'lid1', 'securityId': 'security1', 'geekCard': {'geekName': '测试人才',
                  'geekDesc': {'content': '熟悉订单系统与 Java 开发'}, 'matches': ['Java', 'MySQL'],
                  'geekWorks': [{'positionName': '后端开发', 'description': '交付库存系统'}],
                  'age': 28, 'gender': '女', 'geekEdus': [{'school': '名校'}]}, **extra}

    def searched(self):
        self.ready()
        with mock.patch.object(s.threading, 'Thread') as worker:
            self.job = s.search(USER, {**self.params(), 'count': 5, 'candidates': [self.card()],
                                      'requestId': str(uuid.uuid4())}, 'fixture')
            snapshot = worker.call_args.kwargs['args'][2]
        cid = snapshot['candidates'][0]['id']
        with mock.patch.object(s, 'driver', return_value={'candidates': [{'id': 'c1', 'score': 86,
                                'summary': '工作经历相关', 'evidence': ['有 Java 经验'], 'gaps': ['待看完整简历']}]}):
            s.search_worker('heyi', self.job['run']['id'], snapshot, 'fixture')
        self.job = s.listing(USER, self.params())
        return cid

    def test_upload_deduplicates_and_never_starts_ai(self):
        with mock.patch.object(s.threading, 'Thread') as thread:
            self.upload(); version = self.job['version']
            store = self.upload()
            self.assertEqual(len(self.job['references']), 1)
            self.assertEqual(self.job['version'], version)
            store.assert_not_called(); thread.assert_not_called()
        self.assertNotIn('file_key', self.job['references'][0])
        self.assertNotIn('text', self.job['references'][0])

    def test_corrupt_empty_and_cross_account_uploads_are_rejected(self):
        with mock.patch.object(resume_storage, 'store') as store:
            with self.assertRaises(ValueError): self.upload(b'not a pdf')
            with self.assertRaises(ValueError): s.listing(USER, {**self.params(), 'accountKey': 'b'*64})
            store.assert_not_called()
        with mock.patch.object(resume_storage, 'extract', return_value=('', 'scanned')), \
                mock.patch.object(resume_storage, 'store') as store:
            with self.assertRaisesRegex(ValueError, '正文'):
                s.references(USER, {**self.params(), 'filename': 'scan.pdf', 'fileBase64': base64.b64encode(b'%PDF-1.4 data').decode()})
            store.assert_not_called()

    def test_ai_idempotent_and_version_changes_do_not_overwrite_new_data(self):
        self.upload()
        with mock.patch.object(s.threading, 'Thread') as worker:
            self.job = s.profile(USER, self.params(), 'fixture')
            s.profile(USER, self.params(), 'fixture')
            self.assertEqual(worker.call_count, 1)
            with self.assertRaises(pool.Conflict):
                s.references(USER, {**self.params(), 'referenceId': self.job['references'][0]['id']}, remove=True)
        with mock.patch.object(s, 'driver', return_value={'suggestedJd': 'old', 'skills': ['old'], 'evidence': ['old']}):
            s.profile_worker('heyi', self.job['id'], 'wrong-token', {}, 'fixture')
        self.assertEqual(s.listing(USER, self.params())['profileState'], 'running')

    def test_candidate_evidence_uses_real_card_fields_not_demographics(self):
        value = s.candidate(self.card(), 'job1')
        self.assertIn('订单系统', value['evidenceText'])
        self.assertIn('库存系统', value['evidenceText'])
        self.assertIn('Java', value['evidenceText'])
        self.assertNotIn('名校', value['evidenceText']); self.assertNotIn('测试人才', value['evidenceText'])
        self.assertIsNone(s.candidate(self.card(encryptJobId='another-job'), 'job1'))

    def test_realistic_long_credentials_survive_analysis_and_greeting_without_entering_ai(self):
        self.ready()
        security = 'a'*700+'~'+'B'*45+'=='
        self.assertEqual(len(security), 748)
        card = self.card(securityId=security)
        with mock.patch.object(s.threading, 'Thread') as worker:
            self.job = s.search(USER, {**self.params(), 'count': 5, 'requestId': str(uuid.uuid4()),
                                      'candidates': [card]}, 'fixture')
            worker.assert_called_once()
            snapshot = worker.call_args.kwargs['args'][2]
        self.assertNotIn(security, json.dumps(snapshot))
        cid = snapshot['candidates'][0]['id']
        with mock.patch.object(s, 'driver', return_value={'candidates': [
                {'id': 'c1', 'score': 80, 'summary': '技能相关', 'evidence': ['Java'], 'gaps': []}]}):
            s.search_worker('heyi', self.job['run']['id'], snapshot, 'fixture')
        self.job = s.listing(USER, self.params())
        self.assertTrue(self.job['run']['candidates'][0]['canGreet'])
        contact = s.greet(USER, {**self.params(), 'candidateId': cid, 'message': '测试话术', 'confirmed': True})
        old = passive.device_config(USER, {'accountKey': KEY, 'capabilities': ['active-sourcing']})
        self.assertEqual(old['commands'], [], 'Old clients must not claim long-credential writes')
        current = passive.device_config(USER, {'accountKey': KEY, 'capabilities': ['active-sourcing', 'sourcing-credentials-v2']})
        self.assertEqual(current['commands'][0]['id'], contact['id'])
        self.assertEqual(current['commands'][0]['identifiers']['securityId'], security)

    def test_credential_bounds_and_job_scope_remain_enforced(self):
        self.assertIsNotNone(s.candidate(self.card(securityId='a'*4096), 'job1'))
        for value in ['a'*4097, '--help', 'valid\ninvalid', 'id x', 'id;whoami', 'id$(whoami)', 'id/../../', 'id\x00']:
            with self.subTest(length=len(value)):
                self.assertIsNone(s.candidate(self.card(securityId=value), 'job1'))
        self.assertIsNone(s.candidate(self.card(encryptJobId='other-job', securityId='a'*748), 'job1'))
        self.assertFalse(s.candidate(self.card(securityId=''), 'job1')['canGreet'])

    def test_bounded_search_runs_once_and_rejects_invented_candidates(self):
        self.ready(); request = {**self.params(), 'count': 20, 'requestId': str(uuid.uuid4()), 'candidates': [self.card()]}
        with mock.patch.object(s.threading, 'Thread') as worker:
            self.job = s.search(USER, request, 'fixture'); s.search(USER, request, 'fixture')
            self.assertEqual(worker.call_count, 1)
            snapshot = worker.call_args.kwargs['args'][2]
            with self.assertRaises(ValueError): s.search(USER, {**request, 'count': 21}, 'fixture')
        with mock.patch.object(s, 'driver', return_value={'candidates': [{'id': 'invented', 'score': 100}]}):
            s.search_worker('heyi', self.job['run']['id'], snapshot, 'fixture')
        self.assertEqual(s.listing(USER, self.params())['run']['state'], 'failed')

    def test_continuous_search_over_twenty_pages_and_sixty_candidates(self):
        self.ready()
        token = str(uuid.uuid4())
        for batch in range(1, 8):
            request = {**self.params(), 'count': 20, 'requestId': token,
                       'batchIndex': batch, 'page': batch * 5,
                       'candidates': [self.card(f'geek{batch}_{i}') for i in range(10)]}
            with mock.patch.object(s.threading, 'Thread') as worker:
                self.job = s.search(USER, request, 'fixture')
                s.search(USER, request, 'fixture')
                worker.assert_called_once()
                snapshot = worker.call_args.kwargs['args'][2]
            def scores(kind, model, cookie):
                self.assertNotIn('security1', json.dumps(model))
                self.assertEqual([v['id'] for v in model['candidates']], [f'c{i+1}' for i in range(10)])
                return {'candidates': [{'id': v['id'], 'score': 80 if i == 0 else 79.9}
                                       for i, v in enumerate(model['candidates'])]}
            with mock.patch.object(s, 'driver', side_effect=scores):
                s.search_worker('heyi', self.job['run']['id'], snapshot, 'fixture')
        result = s.listing(USER, self.params())['run']
        self.assertEqual(result['pagesRead'], 35)
        self.assertEqual(result['examined'], 70)
        self.assertEqual(len(result['candidates']), 7)
        self.assertTrue(all(c['score'] >= 80 for c in result['candidates']))
        with pool.database() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM hr_sourcing_seen').fetchone()[0], 70)
            self.assertEqual(len(json.loads(db.execute('SELECT body FROM hr_sourcing_runs').fetchone()[0])), 10)
        with self.assertRaises(pool.Conflict):
            s.search(USER, {**request, 'batchIndex': 8, 'count': 5}, 'fixture')

    def test_later_failed_batch_preserves_matches(self):
        self.searched()
        with pool.database() as db:
            run = db.execute('SELECT id FROM hr_sourcing_runs').fetchone()[0]
        token = str(uuid.uuid4())
        # Create a new session and finish its first batch.
        for batch in (1, 2):
            with mock.patch.object(s.threading, 'Thread') as worker:
                self.job = s.search(USER, {**self.params(), 'count': 5, 'requestId': token,
                    'batchIndex': batch, 'page': batch, 'candidates': [self.card(f'new{batch}')]}, 'fixture')
                snapshot = worker.call_args.kwargs['args'][2]
            with mock.patch.object(s, 'driver', return_value={'candidates': [
                    {'id': 'c1' if batch == 1 else 'invented', 'score': 90}]}):
                s.search_worker('heyi', self.job['run']['id'], snapshot, 'fixture')
        result = s.listing(USER, self.params())['run']
        self.assertEqual(result['state'], 'complete')
        self.assertEqual(len(result['candidates']), 1)
        self.assertTrue(result['partial'])
        self.assertIn('标识不完整', result['error'])

    def test_greeting_requires_confirmation_capable_desktop_and_exact_account(self):
        cid = self.searched()
        with self.assertRaises(ValueError): s.greet(USER, {**self.params(), 'candidateId': cid, 'message': '你好'})
        command = s.greet(USER, {**self.params(), 'candidateId': cid, 'message': '你好', 'confirmed': True})
        duplicate = s.greet(USER, {**self.params(), 'candidateId': cid, 'message': '你好', 'confirmed': True})
        self.assertEqual(command, duplicate)
        self.assertEqual(passive.device_config(USER, {'accountKey': KEY})['commands'], [])
        config = passive.device_config(USER, {'accountKey': KEY, 'capabilities': ['active-sourcing']})
        self.assertEqual(config['commands'][0]['kind'], 'active_greet')
        self.assertEqual(config['rules'], [], 'Active contact must not enable passive job-wide discovery')
        self.assertFalse(passive.claim_command(USER, {'accountKey': 'b'*64, 'id': command['id']})['claimed'])
        self.assertTrue(passive.claim_command(USER, {'accountKey': KEY, 'id': command['id']})['claimed'])
        self.assertFalse(passive.claim_command(USER, {'accountKey': KEY, 'id': command['id']})['claimed'])
        passive.device_report(USER, {'accountKey': KEY, 'commandId': command['id'], 'status': 'uncertain'})
        self.assertFalse(passive.device_config(USER, {'accountKey': KEY, 'capabilities': ['active-sourcing']})['commands'])

    def test_active_receipt_joins_same_review_flow_and_filters_by_origin_and_job(self):
        cid = self.searched()
        command = s.greet(USER, {**self.params(), 'candidateId': cid, 'message': '你好', 'confirmed': True})
        passive.claim_command(USER, {'accountKey': KEY, 'id': command['id']})
        passive.device_report(USER, {'accountKey': KEY, 'commandId': command['id'], 'status': 'sent'})
        passive.device_report(USER, {'accountKey': KEY, 'contactId': command['id'], 'friendId': '123'})
        with mock.patch.object(resume_storage, 'store', return_value='private/key'), \
             mock.patch.object(resume_storage, 'extract', return_value=('姓名：张晓林\n负责 Java 开发。'*8, 'extracted')):
            result = passive.ingest(USER, {'accountKey': KEY, 'friendId': '123', 'messageId': '555',
                'ruleId': 'active-'+command['id'], 'fileBase64': base64.b64encode(b'%PDF-1.4 resume').decode(),
                'extension': 'pdf', 'originalFileName': '张晓林.pdf'})
        active = passive.listing(USER, 'resume-pool', {'jobId': 'job1', 'origin': 'active', 'status': 'pending'})
        self.assertEqual(active['counts']['pending'], 1)
        self.assertEqual(active['items'][0]['name'], '张晓林')
        self.assertEqual(active['items'][0]['origin'], 'active')
        self.assertEqual(passive.listing(USER, 'resume-pool', {'origin': 'passive'})['total'], 0)
        self.assertEqual(passive.listing(USER, 'resume-pool', {'jobId': 'other', 'origin': 'active'})['total'], 0)
        approved = passive.review(USER, {'id': result['id'], 'action': 'approve', 'version': active['items'][0]['version']})
        talent = pool.listing(USER, {})['items'][0]
        self.assertEqual(talent['stage'], 'invited'); self.assertEqual(talent['source'], 'active')
        # Existing reviewed-talent contact entry remains usable for active-origin resumes.
        invitation = passive.invitation_messages(USER, {'talentId': approved['talentId'], 'message': '面试时间可以沟通',
                                                        'requestId': str(uuid.uuid4())}, write=True)
        self.assertEqual(invitation['status'], 'queued')


if __name__ == '__main__': unittest.main()
