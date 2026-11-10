import copy
import hashlib
import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import evidence


class EvidenceTests(unittest.TestCase):
    def fixture(self):
        request = dict(repository='Gotit-zhx/ai-video', tree='a'*40,
                       policyDigest='b'*64, toolchainDigest='c'*64,
                       suiteInputDigests={'core-policy':'d'*64}, requiredSuites=['core-policy'])
        record = dict(request, runId=123, runAttempt=2, head='e'*40,
                      workflow='.github/workflows/ci.yml', passedSuites=['core-policy'],
                      result='passed', unexpectedSkips=0)
        raw = json.dumps(record).encode()
        metadata = dict(id=123, run_attempt=2, repository={'full_name':request['repository']},
                        head_repository={'full_name':request['repository']}, head_sha='e'*40,
                        path='.github/workflows/ci.yml', event='workflow_dispatch',
                        status='completed', conclusion='success', tree='a'*40,
                        artifact={'id':9, 'digest':'sha256:'+hashlib.sha256(raw).hexdigest(),
                                  'expired':False, 'workflow_run':{'id':123, 'head_sha':'e'*40}})
        return record, request, metadata, raw

    def test_verified_same_tree_can_reuse(self):
        record, request, metadata, raw = self.fixture()
        self.assertTrue(evidence.can_reuse(evidence.verify_evidence(record, request, metadata, raw), request))

    def test_self_assertion_cannot_reuse(self):
        record, request, _, _ = self.fixture()
        record['trusted'] = True
        self.assertFalse(evidence.can_reuse(record, request))

    def test_api_failures_reject_evidence(self):
        for field, value in [('conclusion','failure'), ('tree','f'*40), ('path','.github/workflows/evil.yml'),
                             ('head_sha','f'*40), ('run_attempt',3), ('event','pull_request')]:
            record, request, metadata, raw = self.fixture()
            metadata[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                evidence.verify_evidence(record, request, metadata, raw)
        record, request, metadata, raw = self.fixture()
        metadata['head_repository']['full_name'] = 'evil/fork'
        with self.assertRaises(ValueError):evidence.verify_evidence(record, request, metadata, raw)

    def test_changed_bytes_and_missing_digest_reject(self):
        record, request, metadata, raw = self.fixture()
        with self.assertRaises(ValueError):evidence.verify_evidence(record, request, metadata, raw+b' ')
        metadata['artifact'].pop('digest')
        with self.assertRaises(ValueError):evidence.verify_evidence(record, request, metadata, raw)

    def test_missing_suite_or_changed_recipe_is_not_reusable(self):
        record, request, metadata, raw = self.fixture()
        verified = evidence.verify_evidence(record, request, metadata, raw)
        for changes in [dict(requiredSuites=['core-policy','java-unit']),dict(policyDigest='f'*64),
                        dict(toolchainDigest='f'*64),dict(suiteInputDigests={'core-policy':'f'*64})]:
            self.assertFalse(evidence.can_reuse(verified, dict(request, **changes)))

    def test_failed_report_and_unexpected_skip_reject(self):
        for changes in [dict(result='failed'),dict(unexpectedSkips=1),dict(passedSuites=[])]:
            record, request, metadata, _ = self.fixture();record.update(changes)
            raw=json.dumps(record).encode();metadata['artifact']['digest']='sha256:'+hashlib.sha256(raw).hexdigest()
            with self.assertRaises(ValueError):evidence.verify_evidence(record, request, metadata, raw)
    def test_default_branch_scheduler_cannot_prove_checked_out_develop(self):
        record, request, metadata, raw=self.fixture()
        metadata.update(event='schedule',head_sha='f'*40)
        with self.assertRaises(ValueError):evidence.verify_evidence(record,request,metadata,raw)
