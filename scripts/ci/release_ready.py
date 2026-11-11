#!/usr/bin/env python3
"""Eligible PR readiness check: verify an existing candidate, never build or publish."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path
import release

REPOSITORY=release.REPOSITORY
CANDIDATE_WORKFLOW='.github/workflows/candidate.yml'
GIT=re.compile(r'[a-f0-9]{40}\Z')
BRANCH=re.compile(r'(?:release|hotfix)/([0-9]+(?:\.[0-9]+){2,3})(?:-[A-Za-z0-9._-]+)?\Z')

class GateError(ValueError):pass
class Pending(Exception):pass


def expected_from_event(event):
    try:
        pr=event['pull_request'];head=pr['head'];base=pr['base'];match=BRANCH.fullmatch(head['ref'])
        if (event['repository']['full_name']!=REPOSITORY or head['repo']['full_name']!=REPOSITORY
            or base['repo']['full_name']!=REPOSITORY or base['ref']!='main-tree' or not match
            or not GIT.fullmatch(head['sha']) or not GIT.fullmatch(base['sha'])
            or type(event['number']) is not int or event['number']<=0):raise GateError('unsupported production PR source')
        return {'number':event['number'],'head':head['sha'],'base':base['sha'],
                'sourceRef':'refs/heads/'+head['ref'],'version':'v'+match[1]}
    except (KeyError,TypeError):raise GateError('invalid production PR event') from None


def current_pr(api,expected):
    pr=api.get('/pulls/'+str(expected['number']))
    actual=expected_from_event({'repository':{'full_name':REPOSITORY},'number':pr['number'],'pull_request':pr})
    if actual!=expected or pr.get('state')!='open' or pr.get('merged') or pr.get('draft'):
        raise GateError('PR head/base/source/state changed; a new PR gate is required')
    if pr.get('mergeable') is False:raise GateError('production PR has merge conflicts')
    if pr.get('mergeable') is not True or not GIT.fullmatch(pr.get('merge_commit_sha') or ''):raise Pending('merge_tree_pending')
    return pr


def candidate_identity(run,expected):
    return (run.get('repository',{}).get('full_name')==REPOSITORY
            and run.get('head_repository',{}).get('full_name')==REPOSITORY
            and run.get('path')==CANDIDATE_WORKFLOW and run.get('event')=='workflow_dispatch'
            and run.get('head_sha')==expected['head'] and run.get('head_branch')==expected['sourceRef'].removeprefix('refs/heads/'))


def missing_candidate(api,expected):
    runs=api.get('/actions/workflows/candidate.yml/runs?head_sha='+expected['head']+'&per_page=20')['workflow_runs']
    matching=[r for r in runs if candidate_identity(r,expected)]
    if matching:
        latest=max(matching,key=lambda r:(r['id'],r['run_attempt']))
        if latest.get('status')=='completed' and latest.get('conclusion')!='success':raise GateError('candidate workflow failed; no candidate can authorize readiness')
    raise Pending('immutable_candidate_pending')


def attested_invocation(proofs,expected,manifest_sha):
    """Only call with successful `gh attestation verify --format json` output."""
    repo='https://github.com/'+REPOSITORY;signer=repo+'/'+CANDIDATE_WORKFLOW+'@'+expected['sourceRef']
    invocations=set()
    if not isinstance(proofs,list):raise GateError('invalid verified attestation output')
    for proof in proofs:
        try:
            result=proof['verificationResult'];cert=result['signature']['certificate'];statement=result['statement']
            expected_cert={'issuer':'https://token.actions.githubusercontent.com','runnerEnvironment':'github-hosted','buildTrigger':'workflow_dispatch',
                           'sourceRepositoryURI':repo,'sourceRepositoryDigest':expected['head'],'sourceRepositoryRef':expected['sourceRef'],
                           'buildSignerURI':signer,'buildSignerDigest':expected['head'],'buildConfigURI':signer,'buildConfigDigest':expected['head']}
            if any(cert.get(k)!=v for k,v in expected_cert.items()) or not result.get('verifiedTimestamps'):continue
            if statement.get('_type')!='https://in-toto.io/Statement/v1' or statement.get('predicateType')!='https://slsa.dev/provenance/v1':continue
            subjects=[s for s in statement['subject'] if s.get('name')=='release.json']
            if len(subjects)!=1 or subjects[0].get('digest',{}).get('sha256')!=manifest_sha:continue
            predicate=statement['predicate']
            if predicate['buildDefinition']['externalParameters']['workflow']!={'repository':repo,'path':CANDIDATE_WORKFLOW,'ref':expected['sourceRef']}:continue
            invocation=cert['runInvocationURI']
            if predicate['runDetails']['metadata']['invocationId']!=invocation:continue
            match=re.fullmatch(re.escape(repo)+r'/actions/runs/([1-9][0-9]*)/attempts/([1-9][0-9]*)',invocation)
            if match:invocations.add((int(match[1]),int(match[2])))
        except (KeyError,TypeError):continue
    if len(invocations)!=1:raise GateError('attestation does not uniquely bind the candidate run and source')
    return next(iter(invocations))


def verify_run(api,expected,run_id,attempt):
    latest=api.get('/actions/runs/'+str(run_id))
    run=api.get('/actions/runs/'+str(run_id)+'/attempts/'+str(attempt))
    if (latest.get('id')!=run_id or latest.get('run_attempt')!=attempt or run.get('id')!=run_id
        or run.get('run_attempt')!=attempt or not candidate_identity(latest,expected) or not candidate_identity(run,expected)):
        raise GateError('signed candidate run is stale or has a different source')
    if run.get('status')!='completed' or latest.get('status')!='completed':raise Pending('signed_candidate_run_pending')
    if run.get('conclusion')!='success' or latest.get('conclusion')!='success':raise GateError('signed candidate run did not succeed')


def verify_tag(api,expected):
    tag=api.get('/git/ref/tags/candidate-'+expected['head'])['object']
    if tag.get('type')!='commit' or tag.get('sha')!=expected['head']:raise GateError('immutable candidate tag differs from expected source')


def check_once(api,expected,directory):
    pr=current_pr(api,expected)
    published=api.get('/releases/tags/candidate-'+expected['head'],missing=True)
    if published is None:missing_candidate(api,expected)
    if (published.get('tag_name')!='candidate-'+expected['head'] or published.get('draft') is not False
        or published.get('prerelease') is not True):raise GateError('invalid immutable candidate release')
    assets=published.get('assets',[])
    if len(assets)!=2 or {a.get('name') for a in assets}!={'release.json','source.tar.gz'}:raise GateError('candidate assets are missing or ambiguous')
    descriptor=next(a for a in assets if a['name']=='release.json')
    if (type(descriptor.get('id')) is not int or descriptor['id']<=0 or type(descriptor.get('size')) is not int
        or not 0<descriptor['size']<=1024**2 or descriptor.get('state')!='uploaded'):
        raise GateError('invalid candidate manifest asset budget')
    raw=api.asset(descriptor['id']);digest=hashlib.sha256(raw).hexdigest()
    if len(raw)!=descriptor['size'] or descriptor.get('digest')!='sha256:'+digest:raise GateError('candidate manifest asset digest differs')
    try:manifest=release.validate_manifest(json.loads(raw))
    except (ValueError,KeyError,TypeError):raise GateError('candidate manifest did not pass schema and validation checks') from None
    if (manifest['version']!=expected['version'] or manifest['source']['commit']!=expected['head']
        or manifest['source']['ref']!=expected['sourceRef']):raise GateError('candidate version or source differs from PR')
    source_asset=next(a for a in assets if a['name']=='source.tar.gz')
    if source_asset.get('size')!=manifest['sourceArchiveBytes'] or source_asset.get('digest')!='sha256:'+manifest['sourceArchiveSha256']:
        raise GateError('candidate source asset differs from manifest')
    verify_tag(api,expected)
    source=api.get('/git/commits/'+expected['head'])
    combined=api.get('/git/commits/'+pr['merge_commit_sha'])
    if (source['tree']['sha']!=manifest['source']['tree'] or combined['tree']['sha']!=manifest['source']['tree']
        or [p['sha'] for p in combined.get('parents',[])]!=[expected['base'],expected['head']]):
        raise GateError('candidate tree does not cover the exact PR merge combination')
    file=directory/'release.json';file.write_bytes(raw)
    run_id,attempt=attested_invocation(api.attest(file,expected),expected,digest)
    verify_run(api,expected,run_id,attempt)
    current_pr(api,expected);verify_tag(api,expected)
    return {'result':'ready','repository':REPOSITORY,'pr':expected['number'],'head':expected['head'],'base':expected['base'],
            'tree':manifest['source']['tree'],'version':manifest['version'],'schemaVersion':manifest['schemaVersion'],
            'manifestSha256':digest,'candidateRun':run_id,'candidateAttempt':attempt,'releaseId':published['id']}


class GitHub:
    def _call(self,arguments,maximum=4*1024**2):
        try:result=subprocess.run(['gh',*arguments],capture_output=True,timeout=90)
        except subprocess.TimeoutExpired:raise GateError('GitHub verification request timed out') from None
        if result.returncode:
            if re.search(rb'HTTP 404',result.stderr):raise FileNotFoundError('GitHub object absent')
            raise GateError('GitHub read or cryptographic verification failed')
        if len(result.stdout)>maximum:raise GateError('GitHub response exceeds readiness budget')
        return result.stdout
    def get(self,path,missing=False):
        try:return json.loads(self._call(['api','repos/'+REPOSITORY+path]))
        except FileNotFoundError:
            if missing:return None
            raise GateError('required GitHub proof object is absent') from None
    def asset(self,identity):
        return self._call(['api','repos/'+REPOSITORY+'/releases/assets/'+str(identity),'-H','Accept: application/octet-stream'],maximum=1024**2)
    def attest(self,path,expected):
        try:
            raw=self._call(['attestation','verify',str(path),'--repo',REPOSITORY,'--signer-workflow',REPOSITORY+'/'+CANDIDATE_WORKFLOW,
                            '--source-digest',expected['head'],'--source-ref',expected['sourceRef'],'--deny-self-hosted-runners','--format','json','--limit','10'])
        except FileNotFoundError:raise GateError('candidate cryptographic attestation is absent') from None
        return json.loads(raw)


def wait_until_ready(api,expected,directory,timeout=2700,poll=30,clock=time.monotonic,sleep=time.sleep):
    if not 0<timeout<=3600 or not 0<poll<=60:raise GateError('invalid readiness wait budget')
    deadline=clock()+timeout;last=None
    while clock()<deadline:
        try:return check_once(api,expected,directory)
        except Pending as pending:
            if str(pending)!=last:
                last=str(pending);print(json.dumps({'releaseReadiness':'waiting','reason':last}),flush=True)
        remaining=deadline-clock()
        if remaining>0:sleep(min(poll,remaining))
    raise GateError('candidate readiness timeout; rerun this PR workflow after Candidate completes')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--wait-seconds',type=int,default=2700);parser.add_argument('--poll-seconds',type=int,default=30)
    args=parser.parse_args()
    try:
        if os.environ.get('GITHUB_EVENT_NAME')!='pull_request':raise GateError('Release ready requires an eligible pull_request event')
        expected=expected_from_event(json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text()))
        with tempfile.TemporaryDirectory(prefix='heyi-release-ready-') as temporary:
            result=wait_until_ready(GitHub(),expected,Path(temporary),args.wait_seconds,args.poll_seconds)
        output=Path('target/release-readiness/summary.json');output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,sort_keys=True,indent=2)+'\n')
        print(json.dumps(result,sort_keys=True))
    except (GateError,KeyError,TypeError,json.JSONDecodeError) as error:
        print('::error::Release readiness verification failed: '+(str(error) if isinstance(error,GateError) else type(error).__name__))
        raise SystemExit(1)
