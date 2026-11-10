import copy
import hashlib
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import evidence
import scope
import test_evidence

class PrReuseTests(unittest.TestCase):
    def fixture(self):
        record,request,metadata,_=test_evidence.EvidenceTests().fixture()
        suites=['core-policy','java-unit','container-smoke']
        request.update(requiredSuites=suites,suiteInputDigests={s:'d'*64 for s in suites})
        record.update(request,profile='module',passedSuites=suites,
                      build={k:'f'*64 for k in ['archiveSha256','compiledArtifactSha256','buildRecipeDigest']})
        raw=json.dumps(record).encode();metadata['artifact']['digest']='sha256:'+hashlib.sha256(raw).hexdigest()
        metadata['head_branch']='develop'
        plan=dict(request,profile='module',reasonCodes=['mapped_module'],jobs={'unit':True,'database':False,'frontend':False,'container':True,'systemd':False})
        environment={'GITHUB_EVENT_NAME':'pull_request','GITHUB_REPOSITORY':'Gotit-zhx/ai-video',
                     'PR_HEAD_REPOSITORY':'Gotit-zhx/ai-video','GITHUB_HEAD_REF':'release/2026.11.10',
                     'HEYI_FAST_CI_ENABLED':'true','GH_TOKEN':'synthetic'}
        return record,request,metadata,raw,plan,environment
    def discover(self,metadata,path,**kwargs):
        if '/actions/workflows/' in path:return {'workflow_runs':[metadata]}
        if '/git/commits/' in path:return {'tree':{'sha':metadata['tree']}}
        raise AssertionError(path)
    def test_identical_tree_pr_skips_only_covered_execution(self):
        record,request,metadata,raw,plan,environment=self.fixture()
        with patch.object(evidence,'api',side_effect=lambda path,**kw:self.discover(metadata,path,**kw)),patch.object(evidence,'_fetch_record',return_value=(record,metadata,raw)),patch.object(evidence,'compiled_available',return_value=True):
            scope.apply_pr_reuse(plan,environment)
        self.assertEqual(123,plan['reusedExecution']['runId'])
        self.assertTrue(all(not selected for selected in plan['jobs'].values()))
        self.assertEqual(request['requiredSuites'],plan['requiredSuites'])
    def test_changed_tree_policy_digest_or_missing_artifact_executes(self):
        for change in ['tree','policyDigest','artifactDigest','missingArtifact']:
            record,request,metadata,raw,plan,environment=self.fixture()
            if change in ['tree','policyDigest']:plan[change]='f'*(40 if change=='tree' else 64)
            if change=='artifactDigest':metadata['artifact']['digest']='sha256:'+'f'*64
            with patch.object(evidence,'api',side_effect=lambda path,**kw:self.discover(metadata,path,**kw)),patch.object(evidence,'_fetch_record',side_effect=ValueError('missing') if change=='missingArtifact' else None,return_value=(record,metadata,raw)),patch.object(evidence,'compiled_available',return_value=True):
                scope.apply_pr_reuse(plan,environment)
            self.assertNotIn('reusedExecution',plan,change);self.assertTrue(plan['jobs']['unit'],change)
    def test_missing_compiled_artifact_executes(self):
        record,request,metadata,raw,plan,environment=self.fixture()
        with patch.object(evidence,'api',side_effect=lambda path,**kw:self.discover(metadata,path,**kw)),patch.object(evidence,'_fetch_record',return_value=(record,metadata,raw)),patch.object(evidence,'compiled_available',return_value=False):scope.apply_pr_reuse(plan,environment)
        self.assertTrue(plan['jobs']['container']);self.assertNotIn('reusedExecution',plan)
    def test_push_force_full_and_feature_pr_execute_without_discovery(self):
        for change in [{'GITHUB_EVENT_NAME':'push'},{'FORCE_FULL':'true'},{'GITHUB_HEAD_REF':'feature/a'},{'HEYI_FAST_CI_ENABLED':'false'}]:
            *_,plan,environment=self.fixture();environment.update(change)
            with patch.object(evidence,'find_reusable_execution') as find:scope.apply_pr_reuse(plan,environment)
            find.assert_not_called();self.assertTrue(plan['jobs']['unit'])
    def test_final_gate_reverifies_and_emits_reference_without_fake_execution(self):
        record,request,metadata,raw,plan,environment=self.fixture()
        verified=evidence.verify_evidence(record,request,metadata,raw)
        plan['reusedExecution']=evidence.execution_reference(verified)
        with patch.object(scope,'inputs',return_value=request),patch.object(evidence,'fetch_verified',return_value=verified),patch.object(evidence,'compiled_available',return_value=True):
            summary=evidence.reused_scope_summary(plan)
        self.assertEqual('reused',summary['execution']);self.assertEqual(123,summary['originalExecution']['runId'])
        for field in ['passedSuites','build','result','runId','runAttempt']:
            self.assertNotIn(field,summary)
        with patch.object(scope,'inputs',return_value=request),patch.object(evidence,'fetch_verified',side_effect=ValueError('expired')):
            with self.assertRaises(ValueError):evidence.reused_scope_summary(plan)
