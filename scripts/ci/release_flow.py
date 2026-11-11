#!/usr/bin/env python3
"""One-command, fail-closed Gitflow release orchestration for the fixed fork.

The local journal is a recovery hint, never remote proof. Every step re-observes
GitHub. No production shell access, token creation, force push or auto merge.
"""
import argparse
from contextlib import contextmanager
from datetime import date, datetime
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.parse import quote, urlencode
import uuid
import zipfile
from zoneinfo import ZoneInfo

import release

REPOSITORY = 'Gotit-zhx/ai-video'
PRODUCTION = 'main-tree'
INTEGRATION = 'develop'
REMOTE = 'fork'
IDENTITY = re.compile(r'[a-f0-9]{32}\Z')
STOPS = {'conflict', 'baseline_changed', 'permissions_missing', 'operation_mismatch',
         'requires_maintenance', 'dirty_worktree', 'candidate_mismatch', 'validation_failed',
         'deployment_failed', 'evidence_unavailable', 'parallel_maintenance', 'disabled', 'no_runtime_release', 'complete'}


class FlowError(RuntimeError):
    """A safe, actionable stop; details never contain raw subprocess output."""


class GitHubError(FlowError):
    def __init__(self, status):
        self.status = status
        super().__init__('GitHub operation failed (HTTP ' + str(status or 'unknown') + '); check permissions/state and resume')


def version_key(version):
    parts = tuple(int(part) for part in version.removeprefix('v').split('.'))
    return parts + (0,) if len(parts) == 3 else parts


def branch_version(branch):
    match = re.fullmatch(r'(?:release|hotfix)/(\d+(?:\.\d+){2,3})(?:-[A-Za-z0-9._-]+)?', branch)
    return version_key(match[1]) if match else None


def next_version(tags, occupied_versions, shanghai_date):
    day = date.fromisoformat(shanghai_date)
    prefix = f'v{day.year}.{day.month}.{day.day}'
    occupied = []
    for value in [*tags, *occupied_versions]:
        value = re.sub(r'^(?:release|hotfix)/', 'v', value)
        match = re.fullmatch(re.escape(prefix) + r'(?:\.(\d+))?(?:-[A-Za-z0-9._-]+)?', value)
        if match:
            occupied.append(int(match[1] or 0))
    return prefix + ('.' + str(max(occupied) + 1) if occupied else '')


def next_action(journal, observed):
    """Pure decision over freshly observed facts; missing proofs never pass."""
    for key, action in [('operationMismatch', 'operation_mismatch'), ('conflict', 'conflict'),
                        ('baselineChanged', 'baseline_changed'), ('permissionsMissing', 'permissions_missing'),
                        ('parallelMaintenance', 'parallel_maintenance'), ('candidateMismatch', 'candidate_mismatch')]:
        if observed.get(key):
            return action
    if observed.get('deploymentEvidenceUnavailable'):
        return 'evidence_unavailable'
    if observed.get('dirty'):
        return 'dirty_worktree'
    if observed.get('candidateReady') and observed.get('maintenanceRequired'):
        return 'requires_maintenance'
    if observed.get('deploymentPhase'):
        if (observed.get('version') != journal.get('version') or
                observed.get('operationId') != journal.get('operationId')):
            return 'operation_mismatch'
        if observed['deploymentPhase'] == 'succeeded':
            if observed.get('receiptPublished') is False:
                return 'publish_receipt'
            if observed.get('backmergeWaiting'):
                return 'wait_backmerge'
            return 'complete' if observed.get('backmergesComplete') else 'backmerge'
        if observed['deploymentPhase'] in {'rolled_back', 'aborted', 'recovered', 'needs_attention', 'failed'}:
            return 'deployment_failed'
        return 'wait_deployment'
    if observed.get('documentationOnly'):
        return 'no_runtime_release'
    if observed.get('tagged'):
        return 'wait_deployment'
    if observed.get('productionMerged'):
        return 'tag' if observed.get('candidateReady') else 'candidate_mismatch'
    if not observed.get('branchExists'):
        return 'create_branch'
    if not observed.get('pr'):
        return 'open_pr'
    if observed.get('validationFailed'):
        return 'validation_failed'
    if not observed.get('ciReady'):
        return 'wait_validation'
    if observed.get('needsTrustedValidation'):
        return 'wait_validation' if (observed.get('validationDispatched') or journal.get('validationDispatchPending') or journal.get('pendingAction') == 'dispatch_validation') else 'dispatch_validation'
    if not observed.get('candidateReady'):
        return 'wait_candidate' if (observed.get('candidateDispatched') or journal.get('candidateDispatchPending') or journal.get('pendingAction') == 'dispatch_candidate') else 'dispatch_candidate'
    if observed.get('maintenanceRequired'):
        return 'requires_maintenance'
    if observed.get('hostCheckNeeded') or not observed.get('hostReady'):
        return 'wait_host_check' if observed.get('hostCheckDispatched') else 'dispatch_host_check'
    if not observed.get('releaseReady'):
        return 'wait_candidate'
    return 'merge_production'


class JournalStore:
    def __init__(self, common_dir):
        self.root = Path(common_dir) / 'heyi-release-flow'

    def path(self, identity):
        if not isinstance(identity, str) or not IDENTITY.fullmatch(identity):
            raise FlowError('Invalid release journal ID (expected 32 lowercase hex digits)')
        return self.root / (identity + '.json')

    def read(self, identity):
        value = json.loads(self.path(identity).read_text())
        if value.get('id') != identity:
            raise FlowError('Journal identity differs')
        return value

    def write(self, journal):
        target = self.path(journal['id'])
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        # Atomic replacement, including an fsync before publishing the new stage.
        with tempfile.NamedTemporaryFile(mode='w', dir=self.root, delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(journal, stream, sort_keys=True, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)

    def write_sample(self, journal, observed, action, now_ms):
        operation = journal.get('operationId')
        if (not observed.get('receiptVerified') or not operation or not IDENTITY.fullmatch(operation) or action == 'operation_mismatch' or
                observed.get('operationId') != operation or observed.get('version') != journal.get('version')):
            return
        phase = observed.get('deploymentPhase')
        if phase not in {'succeeded', 'failed', 'rolled_back', 'aborted', 'recovered', 'needs_attention'}:
            return
        if now_ms < journal['startedAtMs']:
            raise FlowError('Client clock moved backwards; preserve the original timing before resuming')
        if phase == 'succeeded':
            journal.setdefault('acceptedAtMs', now_ms)
        if action == 'complete':
            journal.setdefault('completedAtMs', now_ms)
        sample = {'schemaVersion': 1, 'operationId': operation, 'releaseType': journal.get('releaseType', 'high-risk'),
                  'profile': observed.get('validationProfile', journal.get('validationProfile', 'full')),
                  'cacheState': 'unknown', 'category': journal.get('category', 'normal'),
                  'result': 'accepted' if phase == 'succeeded' else 'failed',
                  'timeToAcceptedMs': journal['acceptedAtMs'] - journal['startedAtMs'] if 'acceptedAtMs' in journal else None,
                  'flowCompleteMs': journal['completedAtMs'] - journal['startedAtMs'] if 'completedAtMs' in journal else None,
                  'uploadedBytes': observed.get('uploadedBytes'), 'phases': observed.get('phases', [])}
        if sample['category'] != 'normal':
            sample['categoryEvidence'] = journal['categoryEvidence']
        # Persist the original observation times before replacing the one sample
        # per actual host operation. Resume never creates a duplicate sample.
        self.write(journal)
        folder = self.root / 'samples'
        folder.mkdir(mode=0o700, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode='w', dir=folder, delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(sample, stream, sort_keys=True, indent=2)
            stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, folder / (operation + '.json'))

    @contextmanager
    def lock(self):
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        with (self.root / 'lock').open('a') as stream:
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise FlowError('Another local worktree owns the release lock') from None
            try:
                yield
            finally:
                fcntl.flock(stream, fcntl.LOCK_UN)


def advance(journal, api, store, *, enabled, clock=time.time):
    observed = api.observe(journal)
    # Adopt IDs from trusted observations before evaluating receipt identity.
    for key in ('pr', 'validationRun', 'validationAttempt', 'candidateRun', 'candidateAttempt',
                'deployRun', 'deployAttempt', 'operationId', 'expectedOperationId', 'mergeCommit', 'validationProfile'):
        if key in observed:
            if key in {'operationId', 'expectedOperationId', 'deployRun', 'deployAttempt', 'mergeCommit'} and journal.get(key) and journal[key] != observed[key]:
                observed['operationMismatch'] = True
            else:
                journal[key] = observed[key]
    if 'backmergeStates' in observed:
        journal['backmerges'] = observed['backmergeStates']
    action = next_action(journal, observed)
    if not enabled:
        return action if action in {'complete', 'no_runtime_release'} else 'disabled'
    now_ms = int(clock() * 1000)
    journal.setdefault('startedAtMs', now_ms)
    if action in {'requires_maintenance', 'parallel_maintenance', 'conflict'}:
        journal['category'] = 'manual_conflict' if action == 'conflict' else 'maintenance'
        journal['categoryEvidence'] = 'release-journal:' + journal['id'] + ':' + action
    if action == 'publish_receipt':
        journal['phase'] = 'receipt_pending'
    store.write_sample(journal, observed, action, now_ms)
    if action in STOPS:
        journal['phase'] = action
        journal.pop('pendingAction', None)
        store.write(journal)
        return action
    if action.startswith('wait_'):
        journal['phase'] = {'wait_validation': 'validating', 'wait_candidate': 'validating',
                            'wait_deployment': 'deploying', 'wait_host_check': 'candidate_ready',
                            'wait_backmerge': 'backmerging'}[action]
        store.write(journal)
        return action
    # Journal intent first: process/network failure can happen after remote success.
    journal['pendingAction'] = action
    if action == 'backmerge' and observed.get('backmergeUpdate'):
        state = journal['backmerges'][observed['backmergeTarget']]
        state['pendingUpdate'] = {'head': observed['backmergeHead'], 'base': observed['backmergeBase']}
    if action == 'dispatch_validation':
        journal['validationAfterRun'] = observed.get('validationObservedRun', 0)
    store.write(journal)
    try:
        journal.update(api.perform(action, journal, observed) or {})
    except GitHubError as error:
        # A definitive rejection is different from an interrupted response.
        # Only known 4xx non-acceptance clears an update intent for retry.
        if action == 'backmerge' and observed.get('backmergeUpdate') and error.status in {403, 409, 422}:
            journal['backmerges'][observed['backmergeTarget']].pop('pendingUpdate', None)
            journal.pop('pendingAction', None)
            journal['phase'] = {403: 'permissions_missing', 409: 'conflict', 422: 'baseline_changed'}[error.status]
            store.write(journal)
        raise
    journal['phase'] = {'create_branch': 'planned', 'open_pr': 'pr_open',
                        'dispatch_candidate': 'validating', 'dispatch_validation': 'validating',
                        'dispatch_host_check': 'candidate_ready', 'merge_production': 'production_merged',
                        'tag': 'tagged', 'publish_receipt': 'accepted', 'backmerge': 'backmerging'}[action]
    journal.pop('pendingAction', None)
    store.write(journal)
    return action


def verify_merge(commit, journal):
    parents = [p['sha'] for p in commit.get('parents', [])]
    if parents != [journal['base'], journal['head']] or commit['commit']['tree']['sha'] != journal['tree']:
        raise FlowError('Production merge parents/tree differ from the qualified candidate; no tag created')
    return commit['sha']


def trusted_run(run, workflow, head, *, completed=True):
    return (run.get('repository', {}).get('full_name') == REPOSITORY and
            run.get('head_repository', {}).get('full_name') == REPOSITORY and
            run.get('path') == '.github/workflows/' + workflow and
            run.get('event') in {'push', 'workflow_dispatch', 'schedule'} and
            run.get('head_sha') == head and
            (not completed or (run.get('status') == 'completed' and run.get('conclusion') == 'success')))


def maintenance_required(candidate, status, baseline, now):
    """Only an independently trusted, fresh host observation may clear this gate."""
    try:
        observed = status['observedAt']
        if type(observed) is not int:
            return True
        inputs = candidate['source']['inputs']
        return not (0 <= now - observed <= 300 and status['version'] == baseline and
                    re.fullmatch(r'[a-f0-9]{40}', status['sourceCommit']) and
                    re.fullmatch(r'[a-f0-9]{40}', status['controlPlaneRevision']) and
                    re.fullmatch(r'(?:sha256:)?[a-f0-9]{64}', status['schemaFingerprint']) and
                    inputs['operations'] == status['operationsInputs'] and
                    inputs['migrations'] == status['migrationInputs'] and
                    candidate['schemaVersion'] in status['releaseSchemas'] and
                    (candidate['schemaVersion'] == 1 or 2 in status['transferProtocols']))
    except (KeyError, TypeError, ValueError):
        return True


class GhTransport:
    """Existing gh authentication; JSON on stdin, sanitized errors only."""
    def command(self, *arguments, data=None):
        result = subprocess.run(['gh', *arguments], input=data, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=180)
        if result.returncode:
            match = re.search(rb'HTTP (\d{3})', result.stderr)
            raise GitHubError(int(match[1]) if match else None)
        return result.stdout

    def api(self, path, method='GET', payload=None, missing=False):
        args = ['api', 'repos/' + REPOSITORY + path, '--method', method,
                '-H', 'Accept: application/vnd.github+json', '-H', 'X-GitHub-Api-Version: 2022-11-28']
        if payload is not None:
            args += ['--input', '-']
        try:
            raw = self.command(*args, data=json.dumps(payload).encode() if payload is not None else None)
        except FlowError as error:
            if missing and '(HTTP 404)' in str(error):
                return None
            raise
        return json.loads(raw) if raw.strip() else {}

    def archive(self, artifact_id):
        return self.command('api', 'repos/' + REPOSITORY + '/actions/artifacts/' + str(artifact_id) + '/zip')


class GitHubAPI:
    def __init__(self, transport=None, *, dirty=False, clock=time.time):
        self.transport = transport or GhTransport()
        self.api = self.transport.api
        self.dirty = dirty
        self.clock = clock
        self.candidate = None
        self.receipt_files = None

    def release_enabled(self, environment=None):
        environment = os.environ if environment is None else environment
        local = environment.get('HEYI_RELEASE_FLOW_ENABLED')
        # Repository rollout is authoritative. A local false only narrows it;
        # a local true never enables a repository whose rollout is still off.
        if local is not None and local.lower() != 'true':
            return False
        variable = self.api('/actions/variables/HEYI_RELEASE_FLOW_ENABLED', missing=True)
        return bool(variable and str(variable.get('value', '')).lower() == 'true')

    def ref(self, name, kind='heads'):
        value = self.api('/git/ref/' + kind + '/' + quote(name, safe=''), missing=True)
        if value is None:
            return None
        obj = value['object']
        if obj.get('type') == 'tag':
            obj = self.api('/git/tags/' + obj['sha'])['object']
        return obj['sha']

    def pages(self, path, key=None):
        values = []
        for page in range(1, 101):
            value = self.api(path + ('&' if '?' in path else '?') + urlencode({'per_page': 100, 'page': page}))
            items = value[key] if key else value
            values.extend(items)
            if len(items) < 100:
                return values
        raise FlowError('API result exceeds bounded pagination; explicit maintenance required')

    def runs(self, workflow, head=None):
        path = '/actions/workflows/' + workflow + '/runs'
        if head:
            path += '?' + urlencode({'head_sha': head})
        return self.pages(path, 'workflow_runs')

    def exact_pr(self, branch, target):
        found = self.pages('/pulls?' + urlencode({'head': 'Gotit-zhx:' + branch, 'base': target, 'state': 'all'}))
        found = [p for p in found if p.get('state') == 'open' or p.get('merged_at')]
        if len(found) > 1:
            raise FlowError('Ambiguous PR history; select the existing operation for recovery')
        return self.api('/pulls/' + str(found[0]['number'])) if found else None

    def protection(self, branch, production=False):
        policy = self.api('/branches/' + quote(branch, safe='') + '/protection/required_status_checks', missing=True)
        required = {'CI required', 'Release ready'} if production else {'CI required'}
        names = set((policy or {}).get('contexts', [])) | {c['context'] for c in (policy or {}).get('checks', [])}
        if not policy or policy.get('strict') is not True or not required <= names:
            raise FlowError('Strict branch protection missing for ' + branch + ': ' + ', '.join(sorted(required)))

    def check(self, head, name):
        checks = self.pages('/commits/' + head + '/check-runs?filter=latest', 'check_runs')
        matches = [c for c in checks if c['name'] == name and c.get('app', {}).get('slug') == 'github-actions']
        return bool(matches) and all(c['status'] == 'completed' and c['conclusion'] == 'success' for c in matches)

    def perform(self, action, journal, observed):
        if action == 'create_branch':
            self.protection(PRODUCTION, production=True)
            self.protection(INTEGRATION)
            if self.ref(journal['source']) != journal['head'] or self.ref(PRODUCTION) != journal['base']:
                raise FlowError('baseline_changed: source/production moved before branch reservation')
            self.api('/git/refs', 'POST', {'ref': 'refs/heads/' + journal['branch'], 'sha': journal['head']})
        elif action == 'open_pr':
            result = self.api('/pulls', 'POST', {'title': 'Release ' + journal['version'], 'head': journal['branch'],
                                               'base': PRODUCTION, 'body': 'Gitflow release operation `' + journal['id'] + '`. Candidate and host acceptance are required.'})
            return {'pr': result['number']}
        elif action in {'dispatch_candidate', 'dispatch_validation'}:
            workflow = 'candidate.yml' if action == 'dispatch_candidate' else 'ci.yml'
            payload = {'ref': journal['branch']}
            if action == 'dispatch_candidate':
                payload['inputs'] = {'expected_head': journal['head'], 'validation_run': str(observed['validationRun']),
                                     'validation_attempt': str(observed['validationAttempt'])}
            self.api('/actions/workflows/' + workflow + '/dispatches', 'POST', payload)
            return {('candidateDispatchPending' if action == 'dispatch_candidate' else 'validationDispatchPending'): True}
        elif action == 'dispatch_host_check':
            self.api('/actions/workflows/operations.yml/dispatches', 'POST',
                     {'ref': PRODUCTION, 'inputs': {'action': 'release-check'}})
            return {'hostCheckRequestedAt': self.clock()}
        elif action == 'merge_production':
            pr = self.api('/pulls/' + str(journal['pr']))
            if (pr['head']['sha'] != journal['head'] or pr['base']['sha'] != journal['base'] or
                    self.ref(PRODUCTION) != journal['base']):
                raise FlowError('baseline_changed: head/base moved before merge')
            if not (observed.get('ciReady') and observed.get('releaseReady') and pr.get('mergeable') is True
                    and pr.get('mergeable_state') == 'clean'):
                raise FlowError('Production PR is not clean and fully qualified')
            result = self.api('/pulls/' + str(journal['pr']) + '/merge', 'PUT',
                              {'sha': journal['head'], 'merge_method': 'merge'})
            if not result.get('merged'):
                raise FlowError('GitHub did not merge; protections/reviews remain required')
            return {'mergeCommit': result['sha']}
        elif action == 'tag':
            commit = self.api('/commits/' + journal['mergeCommit'])
            verify_merge(commit, journal)
            if self.ref(PRODUCTION) != journal['mergeCommit']:
                raise FlowError('baseline_changed: production moved before tag')
            self.api('/git/refs', 'POST', {'ref': 'refs/tags/' + journal['version'], 'sha': journal['mergeCommit']})
        elif action == 'publish_receipt':
            self.publish_receipt(journal)
        elif action == 'backmerge':
            return self.backmerge(journal, observed)
        else:
            raise FlowError('Unsupported release action')
        return {}

    def active_releases(self, production, records=None):
        records = self.pages('/branches') if records is None else records
        active = []
        for branch in records:
            name = branch['name']
            if not name.startswith('release/') or re.search(r'-backmerge(?:-|$)', name):
                continue
            head = branch.get('commit', {}).get('sha') or self.ref(name)
            compared = self.api('/compare/' + head + '...' + production)
            if compared['merge_base_commit']['sha'] != head:
                active.append(name)
        return sorted(active)

    def plan(self, source, shanghai_date):
        started_at_ms = int(self.clock() * 1000)
        if source != INTEGRATION and not re.fullmatch(r'hotfix/[A-Za-z0-9._-]+', source):
            raise FlowError('Source must be develop or an existing hotfix/<theme>')
        repo = self.api('')
        if not repo.get('permissions', {}).get('push') or not repo.get('allow_merge_commit'):
            raise FlowError('Existing gh login lacks write permission or repository disallows merge commits')
        head, base = self.ref(source), self.ref(PRODUCTION)
        if not head or not base:
            raise FlowError('Source or production branch is missing')
        commit = self.api('/commits/' + head)
        tags = self.pages('/tags')
        baselines = [tag['name'] for tag in tags if release.VERSION.fullmatch(tag['name']) and tag['commit']['sha'] == base]
        if len(baselines) != 1:
            raise FlowError('Production HEAD must have exactly one formal version')
        branch_records = self.pages('/branches')
        branches = [b['name'] for b in branch_records]
        comparison = self.api('/compare/' + base + '...' + head)
        if comparison['merge_base_commit']['sha'] != base:
            raise FlowError('Source does not descend from the current production history; reconcile through Gitflow first')
        files = comparison['files']
        if len(files) >= 300:
            raise FlowError('Compare file list may be truncated; release scope requires explicit review')
        paths = [f['filename'] for f in files]
        versioned = re.fullmatch(r'hotfix/(\d+(?:\.\d+){2,3})(?:-[A-Za-z0-9._-]+)?', source)
        if versioned:
            version = 'v' + versioned[1]
            if version_key(version) in {version_key(t['name']) for t in tags if release.VERSION.fullmatch(t['name'])}:
                raise FlowError('Hotfix version already has a tag; resume its existing release operation')
            conflicts = [name for name in branches if name != source and branch_version(name) == version_key(version)]
            if conflicts:
                raise FlowError('Hotfix version is reserved by: ' + ', '.join(sorted(conflicts)))
            branch = source
        else:
            version = next_version([t['name'] for t in tags], branches, shanghai_date)
            branch = ('hotfix/' if source.startswith('hotfix/') else 'release/') + version[1:] + '-flow'
        active = self.active_releases(base, branch_records)
        components = {release.component(path) for path in paths}
        high_risk = bool(components & {'operations', 'unsupported'}) or any(
            path.startswith(('.github/', 'scripts/ci/', 'src/main/resources/', 'src/main/java/db/migration/')) or '/finance/' in path
            or path in {'pom.xml', 'Dockerfile', 'frontend/Dockerfile'} for path in paths)
        release_type = 'high-risk' if high_risk else ('backend' if 'app' in components else 'frontend')
        return {'id': uuid.uuid4().hex, 'phase': 'planned', 'startedAtMs': started_at_ms, 'releaseType': release_type, 'source': source, 'head': head, 'base': base,
                'tree': commit['commit']['tree']['sha'], 'version': version, 'baselineVersion': baselines[0],
                'branch': branch, 'documentationOnly': release.documentation_only(paths),
                'activeReleases': active, 'parallelRelease': source == INTEGRATION and bool(active), 'changedPaths': paths}

    def artifact_files(self, run, name):
        bundle = self.artifact_bundle(run, name)
        return bundle[2] if bundle else None

    def artifact_bundle(self, run, name, allowed_names=None):
        artifacts = self.pages('/actions/runs/' + str(run['id']) + '/artifacts', 'artifacts')
        matches = [a for a in artifacts if a['name'] == name and not a.get('expired') and
                   run['run_started_at'] <= a['created_at'] <= run['updated_at']]
        if not matches:
            return None
        if len(matches) != 1:
            raise FlowError('Ambiguous artifact identity for the selected run attempt')
        artifact = matches[0]
        raw = self.transport.archive(artifact['id'])
        if len(raw) > 4 * 1024**2 or artifact.get('digest') != 'sha256:' + hashlib.sha256(raw).hexdigest():
            raise FlowError('Artifact digest/size differs from GitHub metadata')
        files = {}
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            if sum(i.file_size for i in archive.infolist()) > 4 * 1024**2:
                raise FlowError('Artifact unpacked size exceeds the receipt budget')
            for member in archive.infolist():
                if member.is_dir():
                    continue
                path = Path(member.filename)
                if path.is_absolute() or '..' in path.parts or path.name in files:
                    raise FlowError('Unsafe or ambiguous receipt artifact path')
                if path.name not in (allowed_names or {'status.json', 'release.json', 'receipt.json', 'timings.json'}):
                    raise FlowError('Unexpected file in receipt artifact')
                files[path.name] = archive.read(member)
        return artifact, raw, files

    def candidate_manifest(self, journal):
        self.candidate = None
        tag = 'candidate-' + journal['head']
        found = self.api('/releases/tags/' + tag, missing=True)
        if found is None:
            return None
        if self.ref(tag, 'tags') != journal['head']:
            raise FlowError('Candidate tag does not identify the expected source')
        with tempfile.TemporaryDirectory(prefix='heyi-flow-candidate-') as directory:
            self.transport.command('release', 'download', tag, '--repo', REPOSITORY,
                                   '--pattern', 'release.json', '--dir', directory)
            path = Path(directory) / 'release.json'
            if path.stat().st_size > 1024**2:
                raise FlowError('Candidate manifest exceeds size budget')
            value = release.validate_manifest(json.loads(path.read_bytes()))
            if (value['version'] != journal['version'] or value['source']['commit'] != journal['head'] or
                    value['source']['tree'] != journal['tree'] or value['source']['ref'] != 'refs/heads/' + journal['branch']):
                raise FlowError('candidate_mismatch: immutable candidate identity differs; do not overwrite it')
            self.transport.command('attestation', 'verify', str(path), '--repo', REPOSITORY,
                                   '--signer-workflow', REPOSITORY + '/.github/workflows/candidate.yml',
                                   '--source-digest', journal['head'], '--source-ref', value['source']['ref'],
                                   '--deny-self-hosted-runners')
            self.candidate = value
            self.candidate_bytes = path.read_bytes()
            return value

    def host_observation(self, journal):
        runs = [r for r in self.runs('operations.yml', journal['base'])
                if r.get('event') == 'workflow_dispatch' and r.get('head_branch') == PRODUCTION
                and r.get('display_title') == 'Operations release-check'
                and trusted_run(r, 'operations.yml', journal['base'], completed=False)]
        runs.sort(key=lambda r: r['id'], reverse=True)
        for run in runs:
            if run['status'] != 'completed':
                return {'hostCheckNeeded': True, 'hostCheckDispatched': True}
            if run['conclusion'] != 'success':
                continue
            files = self.artifact_files(run, 'release-check')
            if not files or set(files) != {'status.json'}:
                continue
            status = json.loads(files['status.json'])
            try:
                if type(status['observedAt']) is not int:
                    continue
                age = self.clock() - status['observedAt']
            except (KeyError, ValueError, TypeError):
                continue
            if not 0 <= age <= 300:
                continue
            return {'hostReady': True, 'hostCheckRun': run['id'], 'hostCheckAttempt': run['run_attempt'],
                    'maintenanceRequired': maintenance_required(self.candidate, status, journal['baselineVersion'], self.clock()),
                    'parallelMaintenance': bool(status.get('maintenance') or status.get('operations'))}
        return {'hostCheckNeeded': True, 'hostCheckDispatched': 0 <= self.clock() - journal.get('hostCheckRequestedAt', 0) < 30}

    def deploy_observation(self, journal):
        if journal.get('deployRun'):
            run = self.api('/actions/runs/' + str(journal['deployRun']) + '/attempts/' + str(journal['deployAttempt']))
            runs = [run]
        else:
            runs = [r for r in self.runs('deploy.yml', journal['mergeCommit']) if
                    r.get('event') == 'push' and r.get('head_branch') == journal['version']]
            if len(runs) > 1:
                raise FlowError('Multiple deployment runs; select the original run through its journal')
        if not runs:
            return {}
        run = runs[0]
        if journal.get('deployRun') and (run['id'] != journal['deployRun'] or run['run_attempt'] != journal['deployAttempt']):
            return {'operationMismatch': True}
        if (not trusted_run(run, 'deploy.yml', journal['mergeCommit'], completed=False) or
                run.get('event') != 'push' or run.get('head_branch') != journal['version']):
            return {'operationMismatch': True}
        operation = hashlib.sha256((str(run['id']) + ':' + str(run['run_attempt']) + ':' + journal['version']).encode()).hexdigest()[:32]
        observed = {'deployRun': run['id'], 'deployAttempt': run['run_attempt'], 'expectedOperationId': operation}
        if journal.get('operationId') and journal['operationId'] != operation:
            return {**observed, 'operationMismatch': True}
        if run['status'] != 'completed':
            return observed
        files = self.artifact_files(run, 'deployment-receipt')
        if files is None:
            # No trusted receipt means no automatic re-run: the host may have accepted.
            return {**observed, 'deploymentEvidenceUnavailable': True, 'version': journal['version']}
        if not {'receipt.json', 'release.json'} <= set(files) or set(files) - {'receipt.json', 'release.json', 'timings.json'}:
            raise FlowError('Missing deployment manifest/receipt pair')
        receipt = json.loads(files['receipt.json'])
        manifest = release.validate_manifest(json.loads(files['release.json']))
        if (receipt.get('id') != operation or receipt.get('version') != journal['version'] or
                manifest != self.candidate or manifest['source']['commit'] != journal['head']):
            return {**observed, 'operationMismatch': True}
        self.receipt_files = {key: files[key] for key in ('receipt.json', 'release.json')}
        observed.update(deployment_measurements(files.get('timings.json'), receipt))
        observed.update(deploymentPhase=receipt['phase'], version=receipt['version'], operationId=operation, receiptVerified=True)
        observed['receiptPublished'] = self.receipt_published(journal, files)
        return observed

    def receipt_published(self, journal, files):
        existing = self.api('/releases/tags/' + journal['version'], missing=True)
        if existing is None:
            return False
        names = {a['name'] for a in existing['assets']}
        if 'release.json' not in names:
            raise FlowError('Formal release exists without its immutable manifest')
        with tempfile.TemporaryDirectory(prefix='heyi-flow-receipt-') as directory:
            self.transport.command('release', 'download', journal['version'], '--repo', REPOSITORY,
                                   '--pattern', 'release.json', '--dir', directory)
            if (Path(directory) / 'release.json').read_bytes() != files['release.json']:
                raise FlowError('Existing formal manifest differs; preserve original evidence')
            operation = json.loads(files['receipt.json'])['id']
            for name in ('receipt-' + operation + '.json', 'receipt.json'):
                if name not in names:
                    continue
                self.transport.command('release', 'download', journal['version'], '--repo', REPOSITORY,
                                       '--pattern', name, '--dir', directory)
                if (Path(directory) / name).read_bytes() == files['receipt.json']:
                    return True
                if name != 'receipt.json':
                    raise FlowError('Operation receipt already exists with different content')
        return False

    def observe(self, journal):
        observed = {'dirty': self.dirty, 'documentationOnly': journal.get('documentationOnly', False),
                    'parallelMaintenance': False}
        production = self.ref(PRODUCTION)
        branch = self.ref(journal['branch'])
        if branch and branch != journal['head']:
            return {**observed, 'baselineChanged': True}
        observed['branchExists'] = bool(branch)
        if journal['source'] == INTEGRATION and not observed['documentationOnly']:
            observed['parallelMaintenance'] = bool(set(self.active_releases(production)) - {journal['branch']})
        if not branch:
            if not observed['documentationOnly']:
                candidate_tag = 'candidate-' + journal['head']
                if self.api('/releases/tags/' + candidate_tag, missing=True):
                    observed.update(existingCandidate=candidate_tag, candidateMismatch=True)
            if production != journal['base'] or self.ref(journal['source']) != journal['head']:
                observed['baselineChanged'] = True
            return observed
        pr = self.exact_pr(journal['branch'], PRODUCTION)
        if pr:
            observed['pr'] = pr['number']
            if pr['head']['sha'] != journal['head']:
                return {**observed, 'baselineChanged': True}
            if pr.get('merged'):
                merge = self.api('/commits/' + pr['merge_commit_sha'])
                observed['mergeCommit'] = verify_merge(merge, journal)
                observed['productionMerged'] = True
                if production != observed['mergeCommit']:
                    return {**observed, 'baselineChanged': True}
            elif production != journal['base'] or pr['base']['sha'] != journal['base']:
                return {**observed, 'baselineChanged': True}
            elif pr.get('mergeable') is False:
                return {**observed, 'conflict': True}
        elif production != journal['base']:
            return {**observed, 'baselineChanged': True}
        if observed['documentationOnly']:
            return observed
        self.protection(PRODUCTION, production=True)
        self.protection(INTEGRATION)
        if not pr:
            return observed
        self.candidate_manifest(journal)
        observed['candidateReady'] = self.candidate is not None
        if self.candidate:
            observed['validationProfile'] = self.candidate['validation'].get('profile', 'full')
        if observed.get('productionMerged'):
            tag = self.ref(journal['version'], 'tags')
            if tag and tag != observed['mergeCommit']:
                return {**observed, 'baselineChanged': True}
            observed['tagged'] = bool(tag)
            if tag:
                context = {**journal, 'mergeCommit': observed['mergeCommit']}
                observed.update(self.deploy_observation(context))
                if observed.get('deploymentPhase') == 'succeeded' and observed.get('receiptPublished'):
                    observed.update(self.backmerge_observation(context))
            return observed
        observed['ciReady'] = self.pr_validation(pr)
        observed['releaseReady'] = self.pr_release_ready(pr)
        if not self.candidate:
            runs = [r for r in self.runs('ci.yml', journal['head']) if trusted_run(r, 'ci.yml', journal['head'], completed=False)]
            observed['validationObservedRun'] = max((r['id'] for r in runs), default=0)
            request = self.validation_request(journal)
            qualified = [r for r in runs if trusted_run(r, 'ci.yml', journal['head']) and self.qualified_ci(r, journal, request)]
            referenced=self.referenced_validation_run(journal,request)
            if referenced is not None:qualified.append(referenced)
            if qualified:
                chosen = max(qualified, key=lambda r: (r['id'], r['run_attempt']))
                observed.update(validationRun=chosen['id'], validationAttempt=chosen['run_attempt'])
            else:
                observed.update(needsTrustedValidation=True, validationDispatched=any(r['status'] != 'completed' for r in runs))
                dispatched = [r for r in runs if r['id'] > journal.get('validationAfterRun', 0)]
                if dispatched and (journal.get('validationDispatchPending') or journal.get('pendingAction') == 'dispatch_validation') and all(r['status'] == 'completed' for r in dispatched):
                    observed['validationFailed'] = True
            candidate_runs = [r for r in self.runs('candidate.yml', journal['head'])
                              if trusted_run(r, 'candidate.yml', journal['head'], completed=False)]
            observed['candidateDispatched'] = bool(candidate_runs)
            if candidate_runs:
                newest = max(candidate_runs, key=lambda r: (r['id'], r['run_attempt']))
                observed.update(candidateRun=newest['id'], candidateAttempt=newest['run_attempt'])
                if newest['status'] == 'completed':
                    observed['validationFailed'] = True
        else:
            observed.update(self.host_observation(journal))
        return observed

    def publish_receipt(self, journal):
        if not self.receipt_files:
            raise FlowError('No trusted successful deployment bundle available')
        receipt = json.loads(self.receipt_files['receipt.json'])
        if receipt.get('phase') != 'succeeded':
            raise FlowError('Only accepted receipts can be published')
        existing = self.api('/releases/tags/' + journal['version'], missing=True)
        with tempfile.TemporaryDirectory(prefix='heyi-flow-publish-') as directory:
            root = Path(directory)
            manifest = root / 'release.json'
            manifest.write_bytes(self.receipt_files['release.json'])
            if existing:
                if self.receipt_published(journal, self.receipt_files):
                    return
                path = root / ('receipt-' + receipt['id'] + '.json')
                path.write_bytes(self.receipt_files['receipt.json'])
                self.transport.command('release', 'upload', journal['version'], '--repo', REPOSITORY, str(path))
            else:
                path = root / 'receipt.json'
                path.write_bytes(self.receipt_files['receipt.json'])
                self.transport.command('release', 'create', journal['version'], '--repo', REPOSITORY,
                                       '--verify-tag', '--latest=true', '--title', journal['version'],
                                       '--notes', 'Accepted by the existing host executor. Operation: ' + receipt['id'],
                                       str(manifest), str(path))

    def pr_release_ready(self, pr):
        """Bind readiness to the eligible PR run and its exact native job check.

        Dispatch-origin Candidate checks are not eligible required PR checks,
        even when their name, app, SHA and pull_requests metadata match.
        """
        try:
            runs = self.runs('release-ready.yml', pr['head']['sha'])
        except GitHubError as error:
            if error.status == 404:
                return False  # New workflow not registered yet; Candidate may proceed.
            raise
        matches = [run for run in runs if
                   run.get('event') == 'pull_request' and
                   run.get('path') == '.github/workflows/release-ready.yml' and
                   run.get('head_sha') == pr['head']['sha'] and
                   run.get('repository', {}).get('full_name') == REPOSITORY and
                   run.get('head_repository', {}).get('full_name') == REPOSITORY and
                   any(item.get('number') == pr['number'] and
                       item.get('head', {}).get('sha') == pr['head']['sha'] and
                       item.get('base', {}).get('sha') == pr['base']['sha']
                       for item in run.get('pull_requests', []))]
        if not matches:
            return False
        current = max(matches, key=lambda run: (run['id'], run['run_attempt']))
        if current.get('status') != 'completed' or current.get('conclusion') != 'success':
            return False
        jobs = self.pages('/actions/runs/' + str(current['id']) + '/attempts/' +
                          str(current['run_attempt']) + '/jobs', 'jobs')
        jobs = [job for job in jobs if job.get('name') == 'Release ready']
        if len(jobs) != 1:
            return False
        job = jobs[0]
        if (job.get('run_id') != current['id'] or job.get('head_sha') != pr['head']['sha'] or
                job.get('status') != 'completed' or job.get('conclusion') != 'success'):
            return False
        url = re.fullmatch(r'https://api\.github\.com/repos/' + re.escape(REPOSITORY) +
                           r'/check-runs/([1-9][0-9]*)', job.get('check_run_url', ''), re.IGNORECASE)
        if not url:
            return False
        check_id = int(url[1])
        check = self.api('/check-runs/' + str(check_id))
        return (check.get('id') == check_id and check.get('name') == 'Release ready' and
                check.get('head_sha') == pr['head']['sha'] and
                type(current.get('check_suite_id')) is int and
                check.get('check_suite', {}).get('id') == current['check_suite_id'] and
                check.get('app', {}).get('id') == 15368 and
                check.get('app', {}).get('slug') == 'github-actions' and
                check.get('status') == 'completed' and check.get('conclusion') == 'success')

    def pr_validation(self, pr):
        """Require CI for this exact PR head/base combination, including backmerges."""
        runs = self.runs('ci.yml', pr['head']['sha'])
        matches = []
        for run in runs:
            if (run.get('event') != 'pull_request' or run.get('repository', {}).get('full_name') != REPOSITORY or
                    run.get('head_repository', {}).get('full_name') != REPOSITORY or
                    run.get('path') != '.github/workflows/ci.yml'):
                continue
            if any(p['number'] == pr['number'] and p['head']['sha'] == pr['head']['sha'] and
                   p['base']['sha'] == pr['base']['sha'] for p in run.get('pull_requests', [])):
                matches.append(run)
        if not matches:
            return False
        current = max(matches, key=lambda r: (r['id'], r['run_attempt']))
        self.pr_run = current
        return current['status'] == 'completed' and current['conclusion'] == 'success' and self.check(pr['head']['sha'], 'CI required')

    def validation_request(self, journal):
        # Synthetic PR evidence is only a requested coverage description. It is
        # never passed to verify_evidence as proof of trusted CI execution.
        self.validation_execution_reference=None
        run = getattr(self, 'pr_run', None)
        if not run or run['status'] != 'completed' or run['conclusion'] != 'success':
            return None
        bundle = self.artifact_bundle(run, 'validation-evidence-' + str(run['run_attempt']), {'evidence.json'})
        if not bundle:
            return None
        value = json.loads(bundle[2]['evidence.json'])
        if value.get('tree') != journal['tree'] or value.get('repository') != REPOSITORY:
            return None
        fields = ('repository', 'tree', 'policyDigest', 'toolchainDigest', 'requiredSuites', 'suiteInputDigests')
        reference=value.get('originalExecution',{})
        if (value.get('kind')=='coverage-reference' and value.get('execution')=='reused'
            and isinstance(reference,dict) and reference.get('tree')==journal['tree']
            and reference.get('workflow')=='.github/workflows/ci.yml'
            and all(re.fullmatch('[1-9][0-9]*',str(reference.get(k,''))) for k in ['runId','runAttempt','artifactId'])
            and re.fullmatch('[a-f0-9]{40}',reference.get('head',''))
            and re.fullmatch('sha256:[a-f0-9]{64}',reference.get('artifactDigest',''))):
            self.validation_execution_reference=reference
        return {key: value[key] for key in fields}

    def referenced_validation_run(self,journal,request):
        # The PR payload is a lookup hint only; real source run/artifacts are reverified.
        reference=getattr(self,'validation_execution_reference',None)
        if not reference or request is None:return None
        run=self.api('/actions/runs/'+str(reference['runId']),missing=True)
        if not run or str(run.get('run_attempt'))!=str(reference['runAttempt']) or run.get('head_sha')!=reference['head']:return None
        if not self.qualified_ci(run,journal,request,reference=reference):return None
        return run

    def qualified_ci(self, run, journal, request, reference=None):
        import evidence
        import scope
        expected_head=reference['head'] if reference else journal['head']
        if not trusted_run(run, 'ci.yml', expected_head):
            return False
        bundle = self.artifact_bundle(run, 'validation-evidence-' + str(run['run_attempt']), {'evidence.json'})
        if not bundle:
            return False
        artifact, raw, files = bundle
        if reference and (str(artifact.get('id'))!=str(reference['artifactId']) or artifact.get('digest')!=reference['artifactDigest']):return False
        value = json.loads(files['evidence.json'])
        tree = self.api('/commits/' + run['head_sha'])['commit']['tree']['sha']
        if tree != journal['tree']:
            return False
        if request is None:
            if value.get('profile') != 'full' or not set(scope.FULL) <= set(value.get('passedSuites', [])):
                return False
            request = {key: value[key] for key in ('repository', 'tree', 'policyDigest', 'toolchainDigest', 'suiteInputDigests')}
            request['requiredSuites'] = scope.FULL
        try:
            evidence.verify_evidence(value, request, {**run, 'tree': tree, 'artifact': artifact}, raw)
        except (ValueError, KeyError, TypeError):
            return False
        proof = value.get('build', {})
        if not all(re.fullmatch(r'[a-f0-9]{64}', proof.get(key, '')) for key in
                   ('archiveSha256', 'compiledArtifactSha256', 'buildRecipeDigest')):
            return False
        compiled = [a for a in self.pages('/actions/runs/' + str(run['id']) + '/artifacts', 'artifacts')
                    if a['name'] == 'compiled-' + str(run['run_attempt']) and a.get('expired') is False]
        if len(compiled) != 1:
            return False
        artifact = compiled[0]
        return (artifact.get('workflow_run', {}).get('id') == run['id'] and
                artifact.get('workflow_run', {}).get('head_sha') == run['head_sha'] and
                isinstance(artifact.get('size_in_bytes'), int) and 0 < artifact['size_in_bytes'] <= 512 * 1024**2 and
                bool(re.fullmatch(r'sha256:[a-f0-9]{64}', artifact.get('digest', ''))))

    def ancestor(self, ancestor, head):
        return self.api('/compare/' + ancestor + '...' + head)['merge_base_commit']['sha'] == ancestor

    def backmerge_branch(self, journal, target):
        stem = journal['branch'].split('/')[0] + '/' + journal['version'][1:] + '-backmerge'
        # Keep the original develop name for resumable pre-upgrade journals.
        return stem if target == INTEGRATION else stem + '-' + hashlib.sha256(target.encode()).hexdigest()[:12]

    def backmerge_observation(self, journal):
        active = self.active_releases(journal['mergeCommit'])
        targets = [INTEGRATION]
        if journal['branch'].startswith('hotfix/'):
            targets = sorted(set(journal.get('activeReleases', [])) | set(active)) + targets
        states = {target: dict(state) for target, state in journal.get('backmerges', {}).items()}
        for target in targets:
            support = self.backmerge_branch(journal, target)
            state = states.setdefault(target, {'branch': support, 'head': journal['mergeCommit']})
            if state.get('branch') != support:
                raise FlowError('Backmerge journal branch differs from its target identity')
            base, head = self.ref(target), self.ref(support)
            if base is None:
                raise FlowError('Required backmerge target disappeared: ' + target)
            if head and head != state['head']:
                pending = state.get('pendingUpdate')
                commit = self.api('/commits/' + head)
                parents = [p['sha'] for p in commit.get('parents', [])]
                if not pending or parents != [pending['head'], pending['base']]:
                    return {'baselineChanged': True, 'backmergeStates': states}
                # An accepted asynchronous update may outlive the client. Adopt
                # only its exact no-ff parents, then require new combination CI.
                state.update(head=head, tree=commit['commit']['tree']['sha'])
                state.pop('pendingUpdate', None)
            if head and not self.ancestor(journal['mergeCommit'], head):
                return {'baselineChanged': True, 'backmergeStates': states}
            if self.ancestor(journal['mergeCommit'], base):
                completed = self.exact_pr(support, target)
                if not completed or not completed.get('merged'):
                    raise FlowError('Backmerge ancestry exists without the required no-ff PR proof')
                commit = self.api('/commits/' + completed['merge_commit_sha'])
                expected_base = state.get('base', completed['base']['sha'])
                parents = [p['sha'] for p in commit.get('parents', [])]
                if (parents != [expected_base, state['head']] or
                        not self.ancestor(completed['merge_commit_sha'], base)):
                    raise FlowError('Completed backmerge differs from its exact no-ff head/base')
                tested = self.api('/commits/' + state['head'])
                if commit['commit']['tree']['sha'] != tested['commit']['tree']['sha']:
                    raise FlowError('Completed backmerge tree differs from its validated combined head')
                state['mergedCommit'] = completed['merge_commit_sha']
                continue
            self.protection(target)
            result = {'backmergeTarget': target, 'backmergeBase': base, 'backmergeHead': state['head'],
                      'backmergeBranch': support, 'backmergeBranchExists': bool(head), 'backmergeStates': states}
            pending = state.get('pendingUpdate')
            if pending:
                if base != pending['base'] or head != pending['head']:
                    return {'baselineChanged': True, 'backmergeStates': states}
                current_pr = self.exact_pr(support, target)
                if not current_pr or current_pr.get('merged'):
                    return {'baselineChanged': True, 'backmergeStates': states}
                if current_pr.get('mergeable') is False:
                    return {**result, 'conflict': True}
                return {**result, 'backmergeWaiting': True}
            state['base'] = base
            if not head:
                if state['head'] != journal['mergeCommit']:
                    return {'baselineChanged': True, 'backmergeStates': states}
                return result
            pr = self.exact_pr(support, target)
            if not pr:
                return result
            state['pr'] = pr['number']
            if pr.get('merged'):
                raise FlowError('Merged backmerge PR is not in the current target history')
            if pr['head']['sha'] != state['head'] or pr['base']['sha'] != base:
                return {'baselineChanged': True, 'backmergeStates': states}
            result['backmergePR'] = pr['number']
            if pr.get('mergeable') is False:
                return {**result, 'conflict': True}
            if pr.get('mergeable') is None:
                return {**result, 'backmergeWaiting': True}
            if not self.ancestor(base, state['head']):
                return {**result, 'backmergeUpdate': True}
            ready = self.pr_validation(pr)
            result.update(backmergeReady=ready, backmergeWaiting=not ready or pr.get('mergeable_state') != 'clean')
            return result
        return {'backmergesComplete': True, 'backmergeStates': states}

    def backmerge(self, journal, observed):
        target = observed['backmergeTarget']
        head = observed.get('backmergeHead', journal['mergeCommit'])
        if self.ref(target) != observed['backmergeBase']:
            raise FlowError('baseline_changed: backmerge target moved; revalidate combined tree')
        if observed.get('backmergePR'):
            pr = self.api('/pulls/' + str(observed['backmergePR']))
            if (pr['head']['sha'] != head or pr['base']['sha'] != observed['backmergeBase'] or
                    pr.get('mergeable') is not True):
                raise FlowError('Backmerge head/base/conflict changed before mutation')
            if observed.get('backmergeUpdate'):
                # GitHub merges the target into this dedicated support branch;
                # expected_head_sha is the update CAS. Never rebase/force push.
                self.api('/pulls/' + str(observed['backmergePR']) + '/update-branch', 'PUT',
                         {'expected_head_sha': head})
                return {}
            if not observed.get('backmergeReady') or pr.get('mergeable_state') != 'clean':
                raise FlowError('Backmerge combination has not passed CI and strict protection')
            result = self.api('/pulls/' + str(observed['backmergePR']) + '/merge', 'PUT',
                              {'sha': head, 'merge_method': 'merge'})
            if not result.get('merged'):
                raise FlowError('Backmerge rejected by GitHub; no protection bypass is available')
            return {'backmergePR': observed['backmergePR'], 'backmergeCommit': result['sha']}
        if not observed.get('backmergeBranchExists'):
            self.api('/git/refs', 'POST', {'ref': 'refs/heads/' + observed['backmergeBranch'], 'sha': head})
        else:
            if self.ref(observed['backmergeBranch']) != head:
                raise FlowError('baseline_changed: backmerge support head moved before opening PR')
            pr = self.api('/pulls', 'POST', {'title': 'Backmerge ' + journal['version'] + ' into ' + target,
                                           'head': observed['backmergeBranch'], 'base': target,
                                           'body': 'Accepted release operation `' + journal['id'] + '`. Validate this combined tree before merging.'})
            return {'backmergePR': pr['number']}
        return {}


def deployment_measurements(raw, receipt):
    from timing import TimingRecorder
    recorder = TimingRecorder()
    value = json.loads(raw) if raw else {}
    # Client phases are independent of server phases. Repeated server receipt
    # polls contain cumulative snapshots: retain only the last per command.
    snapshots = [value.get('client', {})]
    latest = {}
    for item in value.get('server', []):
        latest[item['command']] = item.get('timings', {})
    for name in ('stage-plan', 'stage'):
        if name in latest:
            snapshots.append(latest[name])
    snapshots.append(receipt.get('timings') or latest.get('receipt') or latest.get('deploy') or {})
    for snapshot in snapshots:
        for phase in snapshot.get('phases', []):
            recorder.add(phase['phase'], phase['durationMs'], phase['bytes'], phase['status'])
    uploaded = value.get('uploadedBytes')
    if uploaded is not None and (type(uploaded) is not int or uploaded < 0):
        raise FlowError('Invalid uploaded byte measurement in trusted receipt artifact')
    return {'uploadedBytes': uploaded, 'phases': recorder.snapshot()['phases']}


def validate_journal(journal):
    try:
        valid = (IDENTITY.fullmatch(journal['id']) and
                 all(re.fullmatch(r'[a-f0-9]{40}', journal[k]) for k in ('head', 'base', 'tree')) and
                 all(release.VERSION.fullmatch(journal[k]) for k in ('version', 'baselineVersion')) and
                 re.fullmatch(r'(?:develop|hotfix/[A-Za-z0-9._-]+)', journal['source']) and
                 re.fullmatch(r'(?:release|hotfix)/[A-Za-z0-9._-]+', journal['branch']))
        if not valid:
            raise ValueError('Invalid identity')
        if journal.get('operationId') and not IDENTITY.fullmatch(journal['operationId']):
            raise ValueError('Invalid operation')
        for target in journal.get('activeReleases', []):
            if not re.fullmatch(r'release/[A-Za-z0-9._-]+', target):
                raise ValueError('Invalid active release identity')
        for target, state in journal.get('backmerges', {}).items():
            if target != INTEGRATION and not re.fullmatch(r'release/[A-Za-z0-9._-]+', target):
                raise ValueError('Invalid backmerge target')
            if not re.fullmatch(r'(?:release|hotfix)/[A-Za-z0-9._-]+', state['branch']):
                raise ValueError('Invalid backmerge support branch')
            for key in ('head', 'base', 'tree', 'mergedCommit'):
                if key in state and not re.fullmatch(r'[a-f0-9]{40}', state[key]):
                    raise ValueError('Invalid backmerge commit')
            if 'pendingUpdate' in state:
                if set(state['pendingUpdate']) != {'head', 'base'} or not all(
                        re.fullmatch(r'[a-f0-9]{40}', value) for value in state['pendingUpdate'].values()):
                    raise ValueError('Invalid backmerge update intent')
        for key in ('pr', 'validationRun', 'validationAttempt', 'deployRun', 'deployAttempt'):
            if key in journal and (type(journal[key]) is not int or journal[key] <= 0):
                raise ValueError('Invalid run identity')
    except (KeyError, TypeError, ValueError):
        raise FlowError('Invalid or incomplete release journal; restore the original journal identities') from None


class GitContext:
    """Read-only local Git access; remote mutations are exclusively gh API calls."""
    def __init__(self, root=release.ROOT):
        self.root = root
        remote = self.git('remote', 'get-url', REMOTE)
        if not re.fullmatch(r'(?:git@github\.com:|https://github\.com/|ssh://git@github\.com/)Gotit-zhx/ai-video(?:\.git)?/?', remote):
            raise FlowError('The fork remote must identify the fixed Gotit-zhx/ai-video repository')
        self.common_dir = Path(self.git('rev-parse', '--path-format=absolute', '--git-common-dir'))

    def git(self, *args):
        result = subprocess.run(['git', *args], cwd=self.root, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise FlowError('Local Git inspection failed; run from the project worktree with fork configured')
        return result.stdout.decode().strip()

    def dirty_paths(self):
        return self.git('status', '--porcelain=v1', '--untracked-files=all').splitlines()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--source', help='develop or an existing hotfix/<theme>')
    mode.add_argument('--resume', metavar='32HEX', help='resume or inspect the original release operation')
    parser.add_argument('--dry-run', action='store_true', help='read-only remote/local inspection; never dispatch or mutate GitHub')
    args = parser.parse_args(argv)
    local_switch = os.environ.get('HEYI_RELEASE_FLOW_ENABLED')
    if local_switch is not None and local_switch.lower() != 'true' and not args.dry_run and not args.resume:
        print(json.dumps({'phase': 'disabled', 'message': 'Local release-flow kill switch is off; use --dry-run or --resume for read-only inspection'}))
        return 2
    journal = None
    try:
        local = GitContext()
        dirty_paths = local.dirty_paths()
        store = JournalStore(local.common_dir)
        adapter = GitHubAPI(dirty=bool(dirty_paths))
        enabled = adapter.release_enabled()
        if not enabled and not args.dry_run and not args.resume:
            print(json.dumps({'phase': 'disabled', 'message': 'Repository HEYI_RELEASE_FLOW_ENABLED is off; use --dry-run or --resume for read-only inspection'}))
            return 2
        if args.resume:
            journal = store.read(args.resume)
        else:
            journal = adapter.plan(args.source, datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat())
        validate_journal(journal)
        if args.dry_run or not enabled:
            observed = adapter.observe(journal)
            print(json.dumps({'id': journal['id'], 'readOnly': True, 'enabled': enabled,
                              'source': journal['source'], 'head': journal['head'], 'base': journal['base'],
                              'version': journal['version'], 'branch': journal['branch'],
                              'changedPaths': journal.get('changedPaths', []), 'dirtyPaths': dirty_paths,
                              'activeReleases': journal.get('activeReleases', []),
                              'nextAction': next_action(journal, observed), 'observed': observed,
                              'steps': ['reserve support branch', 'PR CI', 'trusted CI and candidate', 'fresh host check',
                                        'no-ff production merge', 'formal tag', 'CD acceptance/receipt', 'validated backmerges']}))
            return 0
        with store.lock():
            if not args.resume:
                for path in store.root.glob('*.json'):
                    prior = store.read(path.stem)
                    if prior.get('phase') not in {'complete', 'no_runtime_release'}:
                        raise FlowError('An unfinished local release exists; resume ' + prior['id'])
                journal.pop('changedPaths', None)
                store.write(journal)
            deadline = time.monotonic() + 7200
            last = None
            while time.monotonic() < deadline:
                adapter.dirty = bool(local.dirty_paths())
                action = advance(journal, adapter, store, enabled=adapter.release_enabled())
                if action != last:
                    print(json.dumps({'id': journal['id'], 'version': journal['version'], 'phase': journal['phase'],
                                      'action': action, 'resume': '--resume ' + journal['id']}), flush=True)
                    last = action
                if action in STOPS:
                    return 0 if action in {'complete', 'no_runtime_release'} else 2
                time.sleep(10 if action.startswith('wait_') else 1)
            raise FlowError('Timed out waiting for external validation; the journal remains resumable')
    except (FlowError, OSError, ValueError, KeyError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        # Unexpected values are not printed: API/manifest error data may be sensitive.
        message = str(error) if isinstance(error, FlowError) else type(error).__name__ + ': release stopped; inspect the saved operation'
        print(json.dumps({'phase': 'stopped', 'message': message,
                          'resume': '--resume ' + journal['id'] if journal else None}), flush=True)
        return 2
    except KeyboardInterrupt:
        print(json.dumps({'phase': 'interrupted', 'resume': '--resume ' + journal['id'] if journal else None}), flush=True)
        return 130


if __name__ == '__main__':
    raise SystemExit(main())
