import base64
import json
import io
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
    def test_demand_analysis_is_exact_job_scoped_reused_without_ai_and_invalidated_on_edit(self):
        import datetime
        import recruitment_demands as demands
        row = demands.save(USER, {"createId": str(uuid.uuid4()), "date": "2026-10-08",
            "position": self.job["jobName"], "department": "技术", "headcount": 1, "years": 0,
            "responsibilities": "", "requirements": "", "jobId": self.job["jobId"],
            "jobAccountKey": KEY, "jobDescription": self.job["description"],
            "jobFetchedAt": datetime.datetime.now(datetime.timezone.utc).isoformat()})
        result = {"portrait": {"summary": "业务开发", "mustHave": ["Java 开发"], "experience": ["项目交付"]},
                  "competencies": [{"name": "工程交付", "evidence": "上线记录"}],
                  "sourceVersion": row["version"], "sourceJobId": self.job["jobId"],
                  "sourceJobAccountKey": KEY, "modelId": "fixture"}
        with pool.database() as db:
            db.execute("UPDATE recruitment_demands SET ai_status='complete',ai_version=version,ai_result=? WHERE id=?",
                       (json.dumps(result), row["id"]))
        with mock.patch.object(s, "driver") as driver:
            value = s.demand(USER, {**self.params(), "demandId": row["id"]})
            self.assertEqual(value["profileState"], "complete")
            self.assertEqual(value["sourceDemand"]["referenceCount"], 0)
            self.assertFalse(value["criteria"]["confirmed"])
            driver.assert_not_called()
        self.job = value
        self.assertEqual(s.demand(USER, {**self.params(), "demandId": row["id"]})["version"], value["version"])
        with self.assertRaises(ValueError):
            s.demand(USER, {**self.params(), "accountKey": "b"*64, "demandId": row["id"]})
        with pool.database() as db:
            db.execute("UPDATE recruitment_demands SET ai_job='new-generation' WHERE id=?", (row["id"],))
        stale = s.listing(USER, self.params())
        self.assertEqual(stale["profileState"], "stale", "A new analysis at the same demand version invalidates sourcing")
        self.job = s.demand(USER, {**self.params(), "version": stale["version"], "demandId": row["id"]})
        value = self.job
        demands.save(USER, {**row, "headcount": 2})
        changed = s.listing(USER, self.params())
        self.assertEqual(changed["profileState"], "stale")
        self.assertEqual(changed["version"], value["version"]+1)
        with self.assertRaises(ValueError):
            s.demand(USER, {**self.params(), "version": changed["version"], "demandId": row["id"]})

    def test_driver_reports_known_failure_codes_without_exposing_response_details_or_retrying(self):
        for body, expected in [
            ({'message': 'private response [HR_AI_OUTPUT_LIMIT]'}, '输出达到长度上限'),
            ({'message': 'private response [HR_AI_INVALID_RESULT]'}, '未完整返回候选人'),
            ({'message': 'private response [UNKNOWN]'}, '返回不完整或服务异常'),
            (['private response'], '返回不完整或服务异常'),
        ]:
            error = s.urllib.error.HTTPError('http://private', 502, 'bad gateway', {},
                                            io.BytesIO(json.dumps(body).encode()))
            with mock.patch.object(s.urllib.request, 'build_opener') as opener:
                opener.return_value.open.side_effect = error
                with self.assertRaisesRegex(ValueError, expected) as raised:
                    s.driver('sourcing-match', {}, 'private-cookie')
                self.assertNotIn('private', str(raised.exception))
                opener.return_value.open.assert_called_once()

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
        self.job = s.criteria(USER, {**self.params(), 'conditions': self.job['criteria']['conditions']})

    def card(self, geek='geek1', **extra):
        return {'encryptGeekId': geek, 'encryptJobId': 'job1', 'expectId': 'expect1',
                'lid': 'lid1', 'securityId': 'security1', 'geekCard': {'geekName': '测试人才',
                  'geekDesc': {'content': '熟悉订单系统与 Java 开发'}, 'matches': ['Java', 'MySQL'],
                  'geekWorks': [{'positionName': '后端开发', 'description': '交付库存系统'}],
                  'age': 28, 'gender': '女', 'geekEdus': [{'school': '名校'}]}, **extra}

    def scored(self, identity, score=80):
        return {'id': identity, 'score': score, 'summary': '工作经历相关', 'evidence': ['Java 工作经历'],
                'gaps': ['待看完整简历'], 'scoreBasis': 'known_conditions',
                'matchState': 'matched' if score >= 80 else 'below_threshold',
                'matchedConditions': 2, 'pendingConditions': 1,
                'checks': [{'criterionId': f'k{i+1}', 'status': 'matched' if i < 2 else 'unknown',
                            'evidence': '实际工作经历' if i < 2 else '待核实'} for i in range(3)]}

    def test_jd_suggestions_are_domain_specific_without_extra_model_calls(self):
        self.job = s.save(USER, {'accountKey': KEY, 'jobId': 'vision', 'jobName': '图像算法工程师',
                                'description': '计算机视觉、目标检测，使用 Python 和 OpenCV 交付项目。'})
        with mock.patch.object(s, 'driver') as driver:
            value = s.listing(USER, self.params())['criteria']
            driver.assert_not_called()
        self.assertEqual(len(value['conditions']), 3)
        self.assertFalse(value['confirmed'])
        self.assertTrue(value['conditions'][0]['required'])
        self.assertIn('计算机视觉', value['conditions'][0]['text'])
        self.assertIn('OpenCV', value['conditions'][1]['text'])
        self.assertNotIn('Java', value['conditions'][1]['text'])
        self.assertEqual(s.direction_terms('JavaScript React 开发')[0], '前端开发')

    def test_legacy_criteria_keep_hr_text_but_require_review(self):
        self.ready()
        original = self.job['criteria']['conditions']
        with pool.database() as db:
            db.execute('UPDATE hr_sourcing_jobs SET criteria=? WHERE id=?',
                       (json.dumps({'confirmed': True, 'conditions': original}), self.job['id']))
        value = s.listing(USER, self.params())['criteria']
        self.assertEqual(value['conditions'], original)
        self.assertTrue(value['needsReview'])
        self.assertFalse(value['confirmed'])
        self.assertEqual(len(value['suggestedConditions']), 3)

    def test_explicit_job_minimums_and_ambiguous_ranges_are_not_guessed(self):
        requirements = s.basic_requirements({'education': '本科', 'experience': '3-5年', 'description': ''})
        self.assertEqual([r['minimum'] for r in requirements], [3, 3])
        self.assertEqual(s.basic_requirements({'education': '学历不限', 'experience': '经验不限',
                                             'description': '参与本科学生项目，有3年工作经验'}), [])
        self.assertEqual([r['minimum'] for r in s.basic_requirements({'description': '本科及以上学历，3年以上工作经验'})], [3, 3])
        for value, expected in [('1-3年', 'unknown'), ('1-2年', 'unmatched'), ('3-5年', 'matched'),
                                ('5年以上', 'matched'), ('应届生', 'unmatched'), ('2020年毕业', 'unknown')]:
            # Graduation dates are not read as experience: no tenure field given.
            facts = {} if '毕业' in value else {'years': list(s.year_range(value)), 'yearsText': value}
            if facts.get('years') and facts['years'][1] == float('inf'):
                facts['years'][1] = None
            with self.subTest(value=value):
                self.assertEqual(s.basic_checks({'basicFacts': facts}, requirements)[1]['status'], expected)
        self.assertIsNone(s.degree_level('本科在读'))
        self.assertIsNone(s.degree_level('本科 / 硕士'))
        self.assertEqual(s.degree_level('博士研究生'), 5)

    def test_restoring_job_requirement_labels_preserves_profile_and_approved_core_conditions(self):
        self.ready()
        previous = self.job
        with mock.patch.object(s, 'driver') as driver:
            restored = s.save(USER, {**previous, 'education': '本科', 'experience': '经验不限'})
            driver.assert_not_called()
        self.assertEqual(restored['version'], previous['version']+1)
        self.assertEqual(restored['profileState'], 'complete')
        self.assertEqual(restored['profile'], previous['profile'])
        self.assertEqual(restored['criteria'], previous['criteria'])
        self.assertEqual([row['id'] for row in restored['basicRequirements']], ['education'])

    def test_degree_and_tenure_fields_are_normalized_without_age_or_school_inference(self):
        value = s.candidate(self.card(geekCard={'geekName': '人才', 'degreeName': '硕士', 'workYearDesc': '5年以上',
            'age': 30, 'school': '名校', 'gender': '女'}), 'job1')
        self.assertEqual(value['basicFacts'], {'education': 4, 'years': [5, None], 'yearsText': '5年以上'})
        self.assertNotIn('硕士', value['evidenceText'], 'Degree checks stay outside model input')
        missing = s.candidate(self.card(geekCard={'age': 35, 'geekWorkYear': 104,
            'geekEdus': [{'school': '大学', 'graduationYear': '2020'}]}), 'job1')
        self.assertIsNone(missing['basicFacts']['education'])
        self.assertIsNone(missing['basicFacts']['years'])

    def test_high_ai_score_cannot_override_degree_or_tenure_and_legacy_greet_is_blocked(self):
        self.job = s.save(USER, {'accountKey': KEY, 'jobId': 'job1', 'jobName': '开发工程师',
                                'description': '开发业务系统，熟悉 Java 并有实际工程交付经验。',
                                'education': '本科', 'experience': '3-5年'})
        self.ready()
        cards = [self.card('degree-low', geekCard={'degreeName': '大专', 'workYearDesc': '5年'}),
                 self.card('years-low', geekCard={'degreeName': '硕士', 'workYearDesc': '1-2年'}),
                 self.card('years-unknown', geekCard={'degreeName': '本科', 'workYearDesc': '1-3年'}),
                 self.card('degree-unknown', geekCard={'workYearDesc': '5年'}),
                 self.card('match', geekCard={'degreeName': '硕士', 'workYearDesc': '5年'})]
        request_id = str(uuid.uuid4())
        with mock.patch.object(s.threading, 'Thread') as worker:
            started = s.search(USER, {**self.params(), 'count': 5, 'candidates': cards,
                                     'requestId': request_id}, 'fixture')
            snapshot = worker.call_args.kwargs['args'][2]
        scored = [self.scored('c'+str(i+1), 100) for i in range(5)]
        with mock.patch.object(s, 'driver', return_value={'candidates': scored}) as driver:
            s.search_worker('heyi', started['run']['id'], snapshot, 'fixture')
            self.assertNotIn('basicChecks', driver.call_args.args[1]['candidates'][0])
        run = s.listing(USER, self.params())['run']
        self.assertEqual(len(run['candidates']), 1)
        self.assertEqual(run['needsInfo'], 2)
        self.assertEqual(run['belowThreshold'], 2)
        self.assertTrue(all(check['status'] == 'matched' for check in run['candidates'][0]['basicChecks']))
        candidate_id = run['candidates'][0]['id']
        with pool.database() as db:
            row = db.execute('SELECT body FROM hr_sourcing_candidates WHERE id=?', (candidate_id,)).fetchone()
            detail = json.loads(row['body']); detail.pop('basicChecks')
            db.execute('UPDATE hr_sourcing_candidates SET body=? WHERE id=?', (json.dumps(detail), candidate_id))
        with self.assertRaisesRegex(ValueError, '学历、工作年限'):
            s.greet(USER, {**self.params(), 'candidateId': candidate_id, 'message': '你好', 'confirmed': True})
        with pool.database() as db:
            result = json.loads(db.execute('SELECT result FROM hr_sourcing_runs WHERE id=?', (run['id'],)).fetchone()[0])
            result.pop('basicRequirements')
            db.execute('UPDATE hr_sourcing_runs SET result=? WHERE id=?', (json.dumps(result), run['id']))
        with mock.patch.object(s.threading, 'Thread') as worker, self.assertRaisesRegex(pool.Conflict, '重新开始获取'):
            s.search(USER, {**self.params(), 'count': 5, 'candidates': [self.card('next-page')],
                'requestId': request_id,
                'batchIndex': 2, 'page': 2}, 'fixture')
        worker.assert_not_called()

    def test_high_score_with_insufficient_or_required_unknown_evidence_is_not_collected(self):
        self.ready()
        for statuses in [('matched', 'unknown', 'unknown'), ('unknown', 'matched', 'matched'),
                         ('partial', 'matched', 'matched')]:
            with self.subTest(statuses=statuses):
                with mock.patch.object(s.threading, 'Thread') as worker:
                    self.job = s.search(USER, {**self.params(), 'count': 5, 'candidates': [self.card()],
                                              'requestId': str(uuid.uuid4())}, 'fixture')
                    snapshot = worker.call_args.kwargs['args'][2]
                row = self.scored('c1', 100)
                for check, status in zip(row['checks'], statuses): check['status'] = status
                with mock.patch.object(s, 'driver', return_value={'candidates': [row]}):
                    s.search_worker('heyi', self.job['run']['id'], snapshot, 'fixture')
                result = s.listing(USER, self.params())['run']
                self.assertEqual(result['candidates'], [])
                self.assertEqual(result['needsInfo'], 1)
                self.assertEqual(result['reviewExamples'][0]['matchState'], 'needs_info')

    def searched(self):
        self.ready()
        with mock.patch.object(s.threading, 'Thread') as worker:
            self.job = s.search(USER, {**self.params(), 'count': 5, 'candidates': [self.card()],
                                      'requestId': str(uuid.uuid4())}, 'fixture')
            snapshot = worker.call_args.kwargs['args'][2]
        cid = snapshot['candidates'][0]['id']
        with mock.patch.object(s, 'driver', return_value={'candidates': [self.scored('c1', 86)]}):
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

    def test_confirmed_conditions_are_scoped_and_reference_changes_require_confirmation(self):
        self.ready()
        self.assertTrue(self.job['criteria']['confirmed'])
        version = self.job['version']
        conditions = [{'text': '具备 Java 开发经验', 'weight': 70, 'required': True},
                      {'text': '有接口或数据库经验，具备其中一项即可', 'weight': 30, 'required': False}]
        self.job = s.criteria(USER, {**self.params(), 'conditions': conditions})
        self.assertEqual(self.job['version'], version+1)
        self.assertEqual(self.job['criteria']['conditions'][0]['id'], 'k1')
        with self.assertRaises(ValueError):
            s.criteria(USER, {**self.params(), 'accountKey': 'b'*64, 'conditions': conditions})
        with self.assertRaisesRegex(ValueError, '个人属性'):
            s.criteria(USER, {**self.params(), 'conditions': [{'text': '需要女生', 'weight': 100}]})
        self.upload(b'%PDF-1.4 new reference')
        self.assertFalse(self.job['criteria']['confirmed'])
        self.assertEqual(self.job['profileState'], 'stale')

    def test_missing_information_is_visible_and_does_not_become_a_match(self):
        self.ready()
        with mock.patch.object(s.threading, 'Thread') as worker:
            self.job = s.search(USER, {**self.params(), 'count': 5, 'candidates': [self.card()],
                                      'requestId': str(uuid.uuid4())}, 'fixture')
            snapshot = worker.call_args.kwargs['args'][2]
        unknown = self.scored('c1', 0)
        unknown.update(matchState='needs_info', matchedConditions=0, pendingConditions=3)
        for check in unknown['checks']: check['status'] = 'unknown'
        with mock.patch.object(s, 'driver', return_value={'candidates': [unknown]}) as driver:
            s.search_worker('heyi', self.job['run']['id'], snapshot, 'fixture')
            self.assertEqual(driver.call_args.args[1]['criteria'], self.job['criteria']['conditions'])
            self.assertNotIn('suggestedJd', driver.call_args.args[1]['profile'])
        result = s.listing(USER, self.params())['run']
        self.assertEqual(result['state'], 'complete')
        self.assertEqual(result['candidates'], [])
        self.assertEqual(result['needsInfo'], 1)
        self.assertEqual(result['belowThreshold'], 0)
        self.assertEqual(result['reviewExamples'][0]['name'], '测试人才')

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
        with mock.patch.object(s, 'driver', return_value={'candidates': [self.scored('c1')]}):
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
        for batch in range(1, 13):
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
                return {'candidates': [self.scored(v['id'], 80 if i == 0 else 79.9)
                                       for i, v in enumerate(model['candidates'])]}
            with mock.patch.object(s, 'driver', side_effect=scores):
                s.search_worker('heyi', self.job['run']['id'], snapshot, 'fixture')
        result = s.listing(USER, self.params())['run']
        self.assertEqual(result['pagesRead'], 60)
        self.assertEqual(result['examined'], 120)
        self.assertEqual(len(result['candidates']), 12)
        self.assertNotIn('stopReason', result)
        self.assertTrue(all(c['score'] >= 80 for c in result['candidates']))
        with pool.database() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM hr_sourcing_seen').fetchone()[0], 120)
            self.assertEqual(len(json.loads(db.execute('SELECT body FROM hr_sourcing_runs').fetchone()[0])), 10)
        with self.assertRaises(pool.Conflict):
            s.search(USER, {**request, 'batchIndex': 13, 'count': 5}, 'fixture')

    def test_zero_matches_stops_at_one_hundred_and_preserves_condition_advice(self):
        self.ready()
        token = str(uuid.uuid4())
        for batch in range(1, 21):
            request = {**self.params(), 'count': 5, 'requestId': token, 'batchIndex': batch, 'page': batch,
                       'candidates': [self.card(f'none{batch}_{i}') for i in range(8 if batch == 20 else 5)]}
            with mock.patch.object(s.threading, 'Thread') as worker:
                self.job = s.search(USER, request, 'fixture')
                snapshot = worker.call_args.kwargs['args'][2]
            def missing(kind, model, cookie):
                candidates = []
                for candidate in model['candidates']:
                    row = self.scored(candidate['id'], 0)
                    row['matchState'] = 'needs_info'
                    for check in row['checks']: check['status'] = 'unknown'
                    candidates.append(row)
                return {'candidates': candidates}
            with mock.patch.object(s, 'driver', side_effect=missing):
                s.search_worker('heyi', self.job['run']['id'], snapshot, 'fixture')
        result = s.listing(USER, self.params())['run']
        self.assertEqual(result['examined'], 100)
        self.assertEqual(result['stopReason'], 'no_matches_100')
        self.assertEqual(result['conditionStats']['k1']['unknown'], 100)
        self.assertTrue(any('100 人的卡片未说明' in item for item in result['suggestions']))
        with mock.patch.object(s.threading, 'Thread') as worker:
            with self.assertRaisesRegex(pool.Conflict, '调整并保存匹配条件'):
                s.search(USER, {**request, 'batchIndex': 21}, 'fixture')
            worker.assert_not_called()
        self.assertEqual(s.search(USER, request, 'fixture')['run']['examined'], 100)

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
                    self.scored('c1' if batch == 1 else 'invented', 90)]}):
                s.search_worker('heyi', self.job['run']['id'], snapshot, 'fixture')
        result = s.listing(USER, self.params())['run']
        self.assertEqual(result['state'], 'complete')
        self.assertEqual(len(result['candidates']), 1)
        self.assertTrue(result['partial'])
        self.assertIn('标识不完整', result['error'])
        with pool.database() as db:
            before_read=db.execute("SELECT read_at FROM hr_sourcing_candidates WHERE geek='new2'").fetchone()[0]
        request={**self.params(),'count':5,'requestId':str(uuid.uuid4()),'batchIndex':2,'page':2,
                 'resumeRunId':result['id'],'resumeStoredBatch':True,'candidates':[]}
        with mock.patch.object(s.threading,'Thread') as worker:
            unchanged=s.search(USER,request,'fixture')
            worker.assert_not_called()
            self.assertEqual(unchanged['run']['error'],result['error'])
            retried=s.search(USER,{**request,'retryFailedBatch':True},'fixture')
            worker.assert_called_once()
            snapshot=worker.call_args.kwargs['args'][2]
            self.assertEqual(len(retried['run']['candidates']),1)
            self.assertEqual(retried['run']['pagesRead'],2)
            duplicate=s.search(USER,{**request,'retryFailedBatch':True},'fixture')
            self.assertEqual(duplicate['run']['state'],'running')
            worker.assert_called_once()
        with mock.patch.object(s,'driver',return_value={'candidates':[self.scored('c1',90)]}):
            s.search_worker('heyi',retried['run']['id'],snapshot,'fixture')
        final=s.listing(USER,self.params())['run']
        self.assertEqual(final['error'],'')
        self.assertEqual(len(final['candidates']),2)
        self.assertEqual(final['examined'],2,'A failed batch is counted only after a successful analysis')
        self.assertFalse(final['partial']);self.assertFalse(final['stopped'])
        with pool.database() as db:
            self.assertEqual(before_read,db.execute("SELECT read_at FROM hr_sourcing_candidates WHERE geek='new2'").fetchone()[0],
                             'Read-only recovery must not make an expired BOSS greeting card fresh')

    def test_greeting_requires_confirmation_capable_desktop_and_exact_account(self):
        cid = self.searched()
        with self.assertRaises(ValueError): s.greet(USER, {**self.params(), 'candidateId': cid, 'message': '你好'})
        command = s.greet(USER, {**self.params(), 'candidateId': cid, 'message': '你好', 'confirmed': True})
        duplicate = s.greet(USER, {**self.params(), 'candidateId': cid, 'message': '你好', 'confirmed': True})
        self.assertEqual(command, duplicate)
        self.assertEqual(command['accountKey'],KEY)
        exact=passive.device_config(USER,{'accountKey':KEY,'commandId':command['id']})
        self.assertEqual(exact['commands'][0]['kind'],'active_greet')
        self.assertEqual(passive.device_config(USER,{'accountKey':'b'*64,'commandId':command['id']})['commands'],[])
        self.assertEqual(passive.device_config(USER, {'accountKey': KEY})['commands'], [])
        config = passive.device_config(USER, {'accountKey': KEY, 'capabilities': ['active-sourcing']})
        self.assertEqual(config['commands'][0]['kind'], 'active_greet')
        self.assertEqual(config['rules'], [], 'Active contact must not enable passive job-wide discovery')
        self.assertFalse(passive.claim_command(USER, {'accountKey': 'b'*64, 'id': command['id']})['claimed'])
        self.assertTrue(passive.claim_command(USER, {'accountKey': KEY, 'id': command['id']})['claimed'])
        self.assertFalse(passive.claim_command(USER, {'accountKey': KEY, 'id': command['id']})['claimed'])
        passive.device_report(USER, {'accountKey': KEY, 'commandId': command['id'], 'status': 'uncertain'})
        self.assertFalse(passive.device_config(USER, {'accountKey': KEY, 'capabilities': ['active-sourcing']})['commands'])
        self.assertEqual(passive.device_config(USER, {'accountKey': KEY, 'capabilities': ['active-sourcing']})['activeContacts'], [])
        receipts = passive.device_config(USER, {'accountKey': KEY, 'capabilities': ['active-sourcing', 'greeting-receipts-v1']})
        self.assertEqual(receipts['activeContacts'][0]['id'], command['id'])
        self.assertEqual(receipts['activeContacts'][0]['status'], 'uncertain')
        self.assertEqual(passive.device_config(USER, {'accountKey': 'b'*64,
            'capabilities': ['greeting-receipts-v1']})['activeContacts'], [])
        passive.device_report(USER, {'accountKey': KEY, 'commandId': command['id'], 'status': 'sent'})
        passive.device_report(USER, {'accountKey': KEY, 'commandId': command['id'], 'status': 'uncertain'})
        self.assertEqual(passive.device_config(USER, {'accountKey': KEY,
            'capabilities': ['greeting-receipts-v1']})['activeContacts'][0]['status'], 'sent',
            'A late uncertain acknowledgement cannot undo an already confirmed send')

    def test_wait_reason_is_account_scoped_and_expired_queue_does_not_block_new_contacts(self):
        cid = self.searched()
        command = s.greet(USER, {**self.params(), 'candidateId': cid, 'message': '你好', 'confirmed': True})
        body = {'accountKey':KEY,'waitingContactId':command['id'],'reasonCode':'SEND_INTERVAL',
                'retryAt':int(time.time()+60)}
        passive.device_report(USER,{**body,'accountKey':'b'*64})
        self.assertEqual(s.listing(USER,self.params())['run']['candidates'][0]['contact']['waitInfo'],{})
        passive.device_report(USER,body)
        contact=s.listing(USER,self.params())['run']['candidates'][0]['contact']
        self.assertEqual(contact['waitInfo']['reasonCode'],'SEND_INTERVAL')
        self.assertNotIn('body',contact)
        with pool.database() as db:
            old=json.loads(db.execute('SELECT body FROM hr_sourcing_contacts WHERE id=?',(command['id'],)).fetchone()[0])
            old['readAt']=time.time()-1801
            db.execute('UPDATE hr_sourcing_contacts SET body=? WHERE id=?',(json.dumps(old),command['id']))
        self.assertEqual(s.listing(USER,self.params())['run']['candidates'][0]['contact']['status'],'expired')
        self.assertEqual(passive.device_config(USER,{'accountKey':KEY,'capabilities':['active-sourcing']})['commands'],[])

    def test_daily_150_claims_are_shared_across_desktops_and_uncertain_attempts_count(self):
        cid=self.searched()
        command=s.greet(USER,{**self.params(),'candidateId':cid,'message':'你好','confirmed':True})
        with pool.database() as db:
            row=db.execute('SELECT * FROM hr_sourcing_contacts WHERE id=?',(command['id'],)).fetchone()
            payload=json.loads(row['body']);payload['attemptedAt']=time.time()
            db.executemany("INSERT INTO hr_sourcing_contacts(id,owner,account,job,candidate,body,status,created_at,updated_at) VALUES(?,?,?,?,?,?,'uncertain',?,?)",
                           [(str(uuid.uuid4()),row['owner'],row['account'],row['job'],str(uuid.uuid4()),
                             json.dumps(payload),s.now(),s.now()) for _ in range(150)])
        result=passive.claim_command(USER,{'accountKey':KEY,'id':command['id']})
        self.assertFalse(result['claimed'])
        self.assertEqual(result['reasonCode'],'DAILY_GREETING_LIMIT')
        self.assertEqual(s.listing(USER,self.params())['run']['candidates'][0]['contact']['waitInfo']['reasonCode'],
                         'DAILY_GREETING_LIMIT')

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
        passive.sync_jobs(USER, {'accountKey': KEY, 'snapshot': {'source':'job-management','hasMore':False,
            'jobs':[{'encryptJobId':'job1','jobStatus':2,'jobAuditStatus':1,'statusDesc':'开放中'}]}})
        approved = passive.review(USER, {'id': result['id'], 'action': 'approve', 'version': active['items'][0]['version']})
        talent = pool.listing(USER, {})['items'][0]
        self.assertEqual(talent['stage'], 'invited'); self.assertEqual(talent['source'], 'active')
        # Existing reviewed-talent contact entry remains usable for active-origin resumes.
        invitation = passive.invitation_messages(USER, {'talentId': approved['talentId'], 'message': '面试时间可以沟通',
                                                        'requestId': str(uuid.uuid4())}, write=True)
        self.assertEqual(invitation['status'], 'queued')


if __name__ == '__main__': unittest.main()
