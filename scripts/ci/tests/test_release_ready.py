import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import release_ready as gate
import test_release

class FakeGitHub:
    def __init__(self):
        self.manifest=test_release.ReleaseTest().manifest()
        self.raw=json.dumps(self.manifest).encode()
        self.pr={'number':46,'state':'open','draft':False,'merged':False,'mergeable':True,'merge_commit_sha':'d'*40,
                 'head':{'sha':'a'*40,'ref':'release/2026.11.6.2-ops','repo':{'full_name':gate.REPOSITORY}},
                 'base':{'sha':'c'*40,'ref':'main-tree','repo':{'full_name':gate.REPOSITORY}}}
        self.event={'repository':{'full_name':gate.REPOSITORY},'number':46,'pull_request':copy.deepcopy(self.pr)}
        self.expected=gate.expected_from_event(self.event)
        digest=hashlib.sha256(self.raw).hexdigest();ref='refs/heads/'+self.pr['head']['ref'];repo='https://github.com/'+gate.REPOSITORY
        invocation=repo+'/actions/runs/123/attempts/1';workflow=repo+'/'+gate.CANDIDATE_WORKFLOW+'@'+ref
        certificate={'issuer':'https://token.actions.githubusercontent.com','runnerEnvironment':'github-hosted','buildTrigger':'workflow_dispatch',
                     'sourceRepositoryURI':repo,'sourceRepositoryDigest':'a'*40,'sourceRepositoryRef':ref,
                     'buildSignerURI':workflow,'buildSignerDigest':'a'*40,'buildConfigURI':workflow,'buildConfigDigest':'a'*40,
                     'runInvocationURI':invocation}
        statement={'_type':'https://in-toto.io/Statement/v1','predicateType':'https://slsa.dev/provenance/v1',
                   'subject':[{'name':'release.json','digest':{'sha256':digest}}],
                   'predicate':{'buildDefinition':{'externalParameters':{'workflow':{'repository':repo,'path':gate.CANDIDATE_WORKFLOW,'ref':ref}}},
                                'runDetails':{'metadata':{'invocationId':invocation}}}}
        self.proof=[{'verificationResult':{'signature':{'certificate':certificate},'verifiedTimestamps':[{'type':'Tlog'}],'statement':statement}}]
        self.run={'id':123,'run_attempt':1,'status':'completed','conclusion':'success','event':'workflow_dispatch','head_sha':'a'*40,
                  'head_branch':self.pr['head']['ref'],'path':gate.CANDIDATE_WORKFLOW,
                  'repository':{'full_name':gate.REPOSITORY},'head_repository':{'full_name':gate.REPOSITORY}}
        self.published={'id':7,'tag_name':'candidate-'+'a'*40,'prerelease':True,'draft':False,'assets':[
            {'id':9,'name':'release.json','size':len(self.raw),'digest':'sha256:'+digest,'state':'uploaded'},
            {'id':10,'name':'source.tar.gz','size':100,'digest':'sha256:'+'f'*64,'state':'uploaded'}]}
        self.calls=[]
    def get(self,path,missing=False):
        self.calls.append(path)
        if path=='/pulls/46':return copy.deepcopy(self.pr)
        if path=='/git/commits/'+'a'*40:return {'sha':'a'*40,'tree':{'sha':'b'*40}}
        if path=='/git/commits/'+'d'*40:return {'sha':'d'*40,'tree':{'sha':'b'*40},'parents':[{'sha':'c'*40},{'sha':'a'*40}]}
        if path.startswith('/releases/tags/'):return copy.deepcopy(self.published)
        if path.startswith('/git/ref/tags/'):return {'object':{'type':'commit','sha':'a'*40}}
        if path.startswith('/actions/workflows/'):return {'workflow_runs':[copy.deepcopy(self.run)]}
        if path.startswith('/actions/runs/123'):return copy.deepcopy(self.run)
        raise AssertionError(path)
    def asset(self,identity):
        if identity!=9:raise AssertionError('Only the manifest may be downloaded')
        return self.raw
    def attest(self,path,expected):return copy.deepcopy(self.proof)

class ReadyGateTests(unittest.TestCase):
    def test_existing_attested_candidate_is_ready_without_building(self):
        api=FakeGitHub()
        with tempfile.TemporaryDirectory() as d:
            result=gate.check_once(api,api.expected,Path(d))
        self.assertEqual((123,1,'b'*40),(result['candidateRun'],result['candidateAttempt'],result['tree']))
        self.assertEqual('ready',result['result'])
    def test_fork_and_non_release_sources_fail_not_skip(self):
        api=FakeGitHub()
        for change in ['fork','feature']:
            event=copy.deepcopy(api.event)
            if change=='fork':event['pull_request']['head']['repo']['full_name']='attacker/fork'
            else:event['pull_request']['head']['ref']='feature/unsafe'
            with self.assertRaises(gate.GateError):gate.expected_from_event(event)
    def test_head_base_and_combined_tree_drift_reject(self):
        for field in ['head','base']:
            api=FakeGitHub();api.pr[field]['sha']='e'*40
            with tempfile.TemporaryDirectory() as d,self.assertRaises(gate.GateError):gate.check_once(api,api.expected,Path(d))
        api=FakeGitHub();original=api.get
        def changed(path,missing=False):
            result=original(path,missing)
            if path=='/git/commits/'+'d'*40:result['tree']['sha']='e'*40
            return result
        api.get=changed
        with tempfile.TemporaryDirectory() as d,self.assertRaises(gate.GateError):gate.check_once(api,api.expected,Path(d))
    def test_absent_or_running_candidate_waits_but_failed_candidate_rejects(self):
        for state in ['absent','running','failed','published-running','published-failed']:
            api=FakeGitHub()
            if not state.startswith('published-'):api.published=None
            if state.endswith('running'):api.run.update(status='in_progress',conclusion=None)
            if state.endswith('failed'):api.run.update(status='completed',conclusion='failure')
            exception=gate.GateError if state.endswith('failed') else gate.Pending
            with tempfile.TemporaryDirectory() as d,self.assertRaises(exception):gate.check_once(api,api.expected,Path(d))
    def test_tampered_asset_wrong_version_and_stale_signed_attempt_reject(self):
        for change in ['bytes','digest','version','attempt']:
            api=FakeGitHub()
            if change=='bytes':api.raw+=b' '
            if change=='digest':api.published['assets'][0]['digest']='sha256:'+'f'*64
            if change=='version':
                api.manifest['version']='v2026.11.6.3';api.raw=json.dumps(api.manifest).encode()
                api.published['assets'][0].update(size=len(api.raw),digest='sha256:'+hashlib.sha256(api.raw).hexdigest())
            if change=='attempt':api.run['run_attempt']=2
            with tempfile.TemporaryDirectory() as d,self.assertRaises(gate.GateError):gate.check_once(api,api.expected,Path(d))
    def test_predicate_cannot_claim_a_different_run_from_the_certificate(self):
        api=FakeGitHub();api.proof[0]['verificationResult']['statement']['predicate']['runDetails']['metadata']['invocationId']=api.proof[0]['verificationResult']['signature']['certificate']['runInvocationURI'].replace('/123/','/456/')
        with tempfile.TemporaryDirectory() as d,self.assertRaises(gate.GateError):gate.check_once(api,api.expected,Path(d))
    def test_failed_cryptographic_verification_is_not_a_passed_json_blob(self):
        with patch.object(gate.subprocess,'run',return_value=SimpleNamespace(returncode=1,stdout=b'[{"passed":true}]',stderr=b'')),self.assertRaises(gate.GateError):
            gate.GitHub().attest(Path('/tmp/manifest.json'),FakeGitHub().expected)
    def test_wait_has_a_deadline_and_never_dispatches(self):
        api=FakeGitHub();api.published=None;clock=[0]
        def sleep(seconds):clock[0]+=seconds
        with tempfile.TemporaryDirectory() as d,self.assertRaisesRegex(gate.GateError,'timeout'):
            gate.wait_until_ready(api,api.expected,Path(d),timeout=3,poll=1,clock=lambda:clock[0],sleep=sleep)
        self.assertEqual(3,clock[0])

    def test_pr_moving_during_attestation_cannot_inherit_readiness(self):
        api=FakeGitHub();original=api.attest
        def moved(path,expected):
            result=original(path,expected);api.pr['base']['sha']='e'*40;return result
        api.attest=moved
        with tempfile.TemporaryDirectory() as d,self.assertRaisesRegex(gate.GateError,'changed'):
            gate.check_once(api,api.expected,Path(d))
