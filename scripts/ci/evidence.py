#!/usr/bin/env python3
"""Validate CI evidence against GitHub metadata; payload trust flags are ignored."""
import hashlib
import io
import json
import re
import subprocess
import zipfile
from collections.abc import Mapping
from pathlib import Path
import release

WORKFLOW = '.github/workflows/ci.yml'

class VerifiedEvidence(Mapping):
    """In-process capability, never reconstructed from a serialized trust flag."""
    def __init__(self, value, token=None):
        if token is not _TOKEN: raise ValueError('Use verify_evidence')
        self.__value = json.loads(json.dumps(value))
    def __getitem__(self, key):return json.loads(json.dumps(self.__value[key]))
    def __iter__(self):return iter(self.__value)
    def __len__(self):return len(self.__value)

_TOKEN = object()

def can_reuse(verified_evidence, request):
    if not isinstance(verified_evidence, VerifiedEvidence):return False
    value = verified_evidence
    return (all(value.get(k)==request.get(k) for k in ('repository','tree','policyDigest','toolchainDigest'))
            and value.get('result')=='passed' and value.get('unexpectedSkips')==0
            and bool(request.get('requiredSuites'))
            and set(request['requiredSuites']).issubset(value.get('passedSuites', []))
            and all(value.get('suiteInputDigests',{}).get(s)==request.get('suiteInputDigests',{}).get(s)
                    and re.fullmatch('[a-f0-9]{64}',request.get('suiteInputDigests',{}).get(s,''))
                    for s in request['requiredSuites']))


def verify_evidence(evidence, request, run_metadata, artifact_bytes):
    run=run_metadata;artifact=run.get('artifact',{})
    checks = [run.get('repository',{}).get('full_name')==release.REPOSITORY,
              run.get('head_repository',{}).get('full_name')==release.REPOSITORY,
              request.get('repository')==release.REPOSITORY,
              run.get('path')==WORKFLOW, evidence.get('workflow')==WORKFLOW,
              run.get('event') in {'push','workflow_dispatch','schedule'},
              run.get('status')=='completed',run.get('conclusion')=='success',
              str(run.get('id'))==str(evidence.get('runId')),
              str(run.get('run_attempt'))==str(evidence.get('runAttempt')),
              run.get('head_sha')==evidence.get('head'),
              run.get('tree')==evidence.get('tree'),
              artifact.get('expired') is False,
              str(artifact.get('workflow_run',{}).get('id'))==str(run.get('id')),
              artifact.get('workflow_run',{}).get('head_sha')==run.get('head_sha'),
              artifact.get('digest')=='sha256:'+hashlib.sha256(artifact_bytes).hexdigest()]
    if not all(checks):raise ValueError('Unqualified CI run or artifact')
    # Bind the decoded record to those exact API-checked bytes as well.
    if artifact_bytes.startswith(b'PK'):
        with zipfile.ZipFile(io.BytesIO(artifact_bytes)) as archive:
            if archive.namelist()!=['evidence.json']:raise ValueError('Unexpected evidence artifact members')
            if archive.getinfo('evidence.json').file_size>1024*1024:raise ValueError('Oversized evidence')
            decoded=json.loads(archive.read('evidence.json'))
    else:decoded=json.loads(artifact_bytes)
    if decoded!=evidence:raise ValueError('Evidence record differs from artifact')
    verified=VerifiedEvidence(dict(evidence, artifactId=artifact.get('id'), artifactDigest=artifact['digest']), _TOKEN)
    if not can_reuse(verified,request):raise ValueError('CI evidence does not cover requested tree and suites')
    return verified


def api(path, raw=False):
    data=subprocess.check_output(['gh','api',path],stderr=subprocess.DEVNULL,timeout=120 if raw else 30)
    return data if raw else json.loads(data)


def fetch_verified(run_id, attempt, request):
    if not re.fullmatch('[1-9][0-9]*',str(run_id)) or not re.fullmatch('[1-9][0-9]*',str(attempt)):
        raise ValueError('Invalid run identity')
    prefix='repos/'+release.REPOSITORY
    run=api(f'{prefix}/actions/runs/{run_id}/attempts/{attempt}')
    if str(run.get('run_attempt'))!=str(attempt):raise ValueError('Run attempt differs')
    # A historical attempt must not override a failed or running later attempt.
    latest=api(f'{prefix}/actions/runs/{run_id}')
    if latest.get('run_attempt')!=run.get('run_attempt'):raise ValueError('Superseded CI attempt')
    commit=api(f"{prefix}/git/commits/{run['head_sha']}")
    run['tree']=commit['tree']['sha']
    artifacts=api(f'{prefix}/actions/runs/{run_id}/artifacts?per_page=100')
    matches=[a for a in artifacts['artifacts'] if a['name']==f'validation-evidence-{attempt}']
    if len(matches)!=1:raise ValueError('Missing unique validation evidence')
    if matches[0].get('size_in_bytes',0)>1024*1024:raise ValueError('Oversized validation artifact')
    run['artifact']=matches[0]
    raw=api(f"{prefix}/actions/artifacts/{matches[0]['id']}/zip",raw=True)
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        if archive.namelist()!=['evidence.json'] or archive.getinfo('evidence.json').file_size>1024*1024:
            raise ValueError('Invalid evidence archive')
        value=json.loads(archive.read('evidence.json'))
    return verify_evidence(value,request,run,raw)


def audit_reports(folder, suites):
    import scope
    prefixes={'java-unit':'java-unit-','all-db':'database-','all-frontend':'frontend-',
              'finance-frontend':'frontend-','frontend-assets':'frontend-','container-smoke':'container-smoke-',
              'systemd-recovery':'systemd-recovery-'}
    catalog=scope.catalog()
    for suite in suites:
        if suite=='core-policy':continue  # required job independently checks policy job result
        prefix='database-' if suite.endswith('-db') else prefixes[suite]
        reports=[p for p in folder.rglob('summary.json') if p.relative_to(folder).parts[0].startswith(prefix)]
        if not reports:raise ValueError('Missing suite summaries: '+suite)
        for report in reports:
            value=json.loads(report.read_text())
            if value.get('result')!='passed' or value.get('unexpectedSkips',0)!=0:raise ValueError('Failed/skipped suite: '+suite)
        if suite=='all-db' or suite.endswith('-db'):
            reported={p.parent.name for p in reports}
            if not set(catalog[suite]['classes']).issubset(reported):raise ValueError('Missing DB class summaries')
    return list(suites)


def emit(plan_path, reports, output):
    import os
    plan=json.loads(plan_path.read_text())
    passed=audit_reports(reports,plan['requiredSuites'])
    value={k:plan[k] for k in ['repository','tree','policyDigest','toolchainDigest','suiteInputDigests','requiredSuites','profile']}
    value.update(runId=int(os.environ['GITHUB_RUN_ID']),runAttempt=int(os.environ['GITHUB_RUN_ATTEMPT']),
                 head=release.run('git','rev-parse','HEAD'),workflow=WORKFLOW,passedSuites=passed,result='passed',unexpectedSkips=0)
    value['dbDurations']={p.parent.name:json.loads(p.read_text()).get('durationSeconds',1) for p in reports.rglob('summary.json') if p.relative_to(reports).parts[0].startswith('database-')}
    metadata=reports/('compiled-'+os.environ['GITHUB_RUN_ATTEMPT'])/'build.json'
    if metadata.exists():
        compiled=json.loads(metadata.read_text())
        if compiled['tree']!=value['tree']:raise ValueError('Compilation tree differs')
        value['build']={key:compiled[key] for key in ['compiledArtifactSha256','buildRecipeDigest','archiveSha256']}
    output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(value,sort_keys=True,indent=2)+'\n')
    return value

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('action',choices=['emit']);p.add_argument('--plan',type=Path,default=Path('target/ci-plan.json'));p.add_argument('--reports',type=Path,default=Path('target/downloaded'));p.add_argument('--output',type=Path,default=Path('target/evidence/evidence.json'));a=p.parse_args()
    emit(a.plan,a.reports,a.output)
