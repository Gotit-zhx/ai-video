"""Release orchestration contract: all remote side effects stay behind the adapter."""
import copy
import io
from contextlib import redirect_stdout
import json
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    import release_flow as flow
except ImportError:
    flow = None


class PureFlowTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(flow, 'The release state machine must exist')

    def test_version_reservations_and_date_rollover(self):
        cases = [([], [], 'v2026.11.10'), (['v2026.11.10'], [], 'v2026.11.10.1'),
                 (['v2026.11.10.3'], ['v2026.11.10.4'], 'v2026.11.10.5'),
                 (['v2026.11.9.99'], ['release/2026.11.10.4-fix'], 'v2026.11.10.5')]
        for tags, occupied, expected in cases:
            self.assertEqual(expected, flow.next_version(tags, occupied, '2026-11-10'))

    def test_receipt_pending_never_redeploys(self):
        journal = {'phase': 'deploying', 'version': 'v2026.11.10', 'operationId': 'a' * 32}
        observed = {'deploymentPhase': 'succeeded', 'version': journal['version'],
                    'operationId': journal['operationId'], 'receiptPublished': False}
        self.assertEqual('publish_receipt', flow.next_action(journal, observed))
        observed['receiptPublished'] = True
        self.assertEqual('backmerge', flow.next_action(journal, observed))
        observed['backmergesComplete'] = True
        self.assertEqual('complete', flow.next_action(journal, observed))

    def test_wrong_operation_cannot_backmerge(self):
        journal = {'phase': 'deploying', 'version': 'v2026.11.10', 'operationId': 'a' * 32}
        for change in ({'version': 'v2026.11.10.1'}, {'operationId': 'b' * 32}):
            observed = {'deploymentPhase': 'succeeded', 'version': journal['version'],
                        'operationId': journal['operationId'], **change}
            self.assertEqual('operation_mismatch', flow.next_action(journal, observed))

    def test_fail_closed_gates(self):
        for key, result in [('conflict', 'conflict'), ('baselineChanged', 'baseline_changed'),
                            ('permissionsMissing', 'permissions_missing')]:
            self.assertEqual(result, flow.next_action({}, {key: True}))
        self.assertEqual('requires_maintenance', flow.next_action({}, {'candidateReady': True, 'maintenanceRequired': True}))
        self.assertEqual('wait_validation', flow.next_action({}, {'branchExists': True, 'pr': 10}))
        self.assertEqual('wait_candidate', flow.next_action({}, {'branchExists': True, 'pr': 10, 'ciReady': True, 'candidateDispatched': True}))


class MemoryAPI:
    """A remote resource store, including effects that outlive a client crash."""
    def __init__(self, observation):
        self.state = copy.deepcopy(observation)
        self.effects = []
        self.fail_after_write = False

    def observe(self, journal):
        return copy.deepcopy(self.state)

    def perform(self, action, journal, observed):
        self.effects.append(action)
        if action == 'create_branch':
            self.state['branchExists'] = True
        elif action == 'open_pr':
            self.state['pr'] = 17
        elif action == 'dispatch_candidate':
            self.state['candidateDispatched'] = True
        elif action == 'publish_receipt':
            self.state['receiptPublished'] = True
        elif action == 'backmerge':
            self.state['backmergesComplete'] = True
        else:
            raise AssertionError('Unexpected mutation: ' + action)
        if self.fail_after_write:
            self.fail_after_write = False
            raise OSError('lost response after accepted write')
        return {}


class EngineTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(flow, 'The release state machine must exist')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = flow.JournalStore(Path(self.tmp.name))
        self.journal = {'id': 'a' * 32, 'phase': 'planned', 'source': 'develop', 'version': 'v2026.11.10'}

    def test_docs_only_is_remote_noop(self):
        api = MemoryAPI({'documentationOnly': True})
        result = flow.advance(self.journal, api, self.store, enabled=True)
        self.assertEqual('no_runtime_release', result)
        self.assertEqual([], api.effects)

    def test_disabled_resume_is_read_only(self):
        api = MemoryAPI({})
        self.assertEqual('disabled', flow.advance(self.journal, api, self.store, enabled=False))
        self.assertEqual([], api.effects)

    def test_dirty_worktree_does_not_create_release_branch(self):
        api = MemoryAPI({'dirty': True})
        self.assertEqual('dirty_worktree', flow.advance(self.journal, api, self.store, enabled=True))
        self.assertEqual([], api.effects)

    def test_crash_after_branch_creation_is_reconciled(self):
        api = MemoryAPI({})
        api.fail_after_write = True
        with self.assertRaises(OSError):
            flow.advance(self.journal, api, self.store, enabled=True)
        saved = self.store.read(self.journal['id'])
        self.assertEqual('create_branch', saved['pendingAction'])
        self.assertEqual('open_pr', flow.advance(saved, api, self.store, enabled=True))
        self.assertEqual(['create_branch', 'open_pr'], api.effects)

    def test_receipt_recovery_publishes_then_backmerges_without_build(self):
        self.journal.update(phase='deploying', operationId='b' * 32)
        api = MemoryAPI({'deploymentPhase': 'succeeded', 'version': self.journal['version'],
                         'operationId': 'b' * 32, 'receiptPublished': False})
        for expected in ['publish_receipt', 'backmerge', 'complete']:
            self.assertEqual(expected, flow.advance(self.journal, api, self.store, enabled=True))
        self.assertEqual(['publish_receipt', 'backmerge'], api.effects)
        self.assertEqual('complete', self.store.read(self.journal['id'])['phase'])

    def test_common_directory_lock_rejects_concurrent_operator(self):
        with self.store.lock():
            with self.assertRaises(flow.FlowError):
                with self.store.lock():
                    pass

    def test_journal_rejects_traversal(self):
        with self.assertRaises(flow.FlowError):
            self.store.read('../secrets')


class ProofTest(unittest.TestCase):
    def test_noff_requires_both_parents_and_exact_tree(self):
        self.assertTrue(hasattr(flow, 'verify_merge'), 'no-ff verification must exist')
        expected = {'base': 'a'*40, 'head': 'b'*40, 'tree': 'c'*40}
        commit = {'sha': 'd'*40, 'parents': [{'sha': 'a'*40}, {'sha': 'b'*40}], 'commit': {'tree': {'sha': 'c'*40}}}
        self.assertEqual('d'*40, flow.verify_merge(commit, expected))
        for bad in [dict(commit, parents=[{'sha': 'b'*40}]),
                    dict(commit, parents=[{'sha': 'e'*40}, {'sha': 'b'*40}]),
                    dict(commit, commit={'tree': {'sha': 'e'*40}})]:
            with self.assertRaises(flow.FlowError):
                flow.verify_merge(bad, expected)

    def test_trusted_ci_rejects_pr_tree_and_failed_attempt(self):
        self.assertTrue(hasattr(flow, 'trusted_run'), 'trusted run binding must exist')
        run = {'id': 44, 'run_attempt': 2, 'event': 'workflow_dispatch', 'head_sha': 'a'*40,
               'head_branch': 'release/2026.11.10-flow', 'path': '.github/workflows/ci.yml',
               'status': 'completed', 'conclusion': 'success',
               'repository': {'full_name': flow.REPOSITORY}, 'head_repository': {'full_name': flow.REPOSITORY}}
        self.assertTrue(flow.trusted_run(run, 'ci.yml', 'a'*40))
        for change in [{'event': 'pull_request'}, {'head_sha': 'b'*40}, {'conclusion': 'failure'},
                       {'head_repository': {'full_name': 'attacker/ai-video'}}, {'path': '.github/workflows/evil.yml'}]:
            self.assertFalse(flow.trusted_run({**run, **change}, 'ci.yml', 'a'*40))

    def test_host_check_requires_fresh_exact_inputs_and_protocol(self):
        self.assertTrue(hasattr(flow, 'maintenance_required'), 'host maintenance proof must exist')
        candidate = {'schemaVersion': 2, 'source': {'inputs': {'operations': 'a'*64, 'migrations': 'b'*64}}}
        status = {'version': 'v2026.11.9', 'observedAt': 1794304800,
                  'sourceCommit': 'c'*40, 'controlPlaneRevision': 'd'*40,
                  'operationsInputs': 'a'*64, 'migrationInputs': 'b'*64,
                  'schemaFingerprint': 'e'*64, 'releaseSchemas': [1, 2], 'transferProtocols': [1, 2]}
        self.assertFalse(flow.maintenance_required(candidate, status, 'v2026.11.9', 1794304860))
        for change in [{'operationsInputs': 'f'*64}, {'migrationInputs': 'f'*64},
                       {'releaseSchemas': [1]}, {'version': 'v2026.11.8'}, {'observedAt': 1794218400}]:
            self.assertTrue(flow.maintenance_required(candidate, {**status, **change}, 'v2026.11.9', 1794304860))


class FakeTransport:
    def __init__(self, responses):
        self.responses = responses
        self.writes = []

    def api(self, path, method='GET', payload=None, missing=False):
        if method != 'GET':
            self.writes.append((method, path, payload))
        key = (method, path)
        value = self.responses.get(key)
        if value is None:
            if missing:
                return None
            raise AssertionError('Unexpected API request ' + repr(key))
        if isinstance(value, Exception):
            raise value
        return copy.deepcopy(value)


class AdapterMutationTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(hasattr(flow, 'GitHubAPI'), 'real GitHub adapter must exist')
        self.j = {'id': 'a'*32, 'source': 'develop', 'head': 'b'*40, 'base': 'c'*40,
                  'tree': 'd'*40, 'version': 'v2026.11.10', 'branch': 'release/2026.11.10-flow', 'pr': 20}

    def test_candidate_dispatch_binds_exact_validation_attempt(self):
        endpoint = '/actions/workflows/candidate.yml/dispatches'
        transport = FakeTransport({('POST', endpoint): {}})
        adapter = flow.GitHubAPI(transport)
        adapter.perform('dispatch_candidate', self.j, {'validationRun': 123, 'validationAttempt': 2})
        self.assertEqual([('POST', endpoint, {'ref': 'release/2026.11.10-flow', 'inputs': {
            'expected_head': 'b'*40, 'validation_run': '123', 'validation_attempt': '2'}})], transport.writes)

    def test_merge_api_cannot_bypass_head_or_use_squash(self):
        endpoint = '/pulls/20/merge'
        transport = FakeTransport({('GET', '/pulls/20'): {
            'head': {'sha': 'b'*40}, 'base': {'sha': 'c'*40}, 'mergeable': True, 'mergeable_state': 'clean'},
            ('GET', '/git/ref/heads/main-tree'): {'object': {'sha': 'c'*40}},
            ('PUT', endpoint): {'merged': True, 'sha': 'e'*40}})
        adapter = flow.GitHubAPI(transport)
        result = adapter.perform('merge_production', self.j, {'ciReady': True, 'releaseReady': True})
        self.assertEqual('e'*40, result['mergeCommit'])
        self.assertEqual({'sha': 'b'*40, 'merge_method': 'merge'}, transport.writes[0][2])

    def test_base_drift_stops_before_remote_merge(self):
        transport = FakeTransport({('GET', '/pulls/20'): {
            'head': {'sha': 'b'*40}, 'base': {'sha': 'f'*40}, 'mergeable': True, 'mergeable_state': 'clean'},
            ('GET', '/git/ref/heads/main-tree'): {'object': {'sha': 'f'*40}}})
        adapter = flow.GitHubAPI(transport)
        with self.assertRaises(flow.FlowError):
            adapter.perform('merge_production', self.j, {'ciReady': True, 'releaseReady': True})
        self.assertEqual([], transport.writes)

    def test_protected_merge_denial_remains_a_stop(self):
        transport = FakeTransport({('GET', '/pulls/20'): {
            'head': {'sha': 'b'*40}, 'base': {'sha': 'c'*40}, 'mergeable': True, 'mergeable_state': 'clean'},
            ('GET', '/git/ref/heads/main-tree'): {'object': {'sha': 'c'*40}},
            ('PUT', '/pulls/20/merge'): flow.FlowError('GitHub refused operation (HTTP 403)')})
        with self.assertRaises(flow.FlowError):
            flow.GitHubAPI(transport).perform('merge_production', self.j, {'ciReady': True, 'releaseReady': True})
        self.assertEqual(1, len(transport.writes))


class WorkflowDecisionsTest(unittest.TestCase):
    def test_pr_ci_alone_does_not_authorize_candidate_build(self):
        observed = {'branchExists': True, 'pr': 3, 'ciReady': True, 'needsTrustedValidation': True}
        self.assertEqual('dispatch_validation', flow.next_action({}, observed))
        observed['validationDispatched'] = True
        self.assertEqual('wait_validation', flow.next_action({}, observed))

    def test_missing_host_observation_dispatches_read_only_check(self):
        observed = {'branchExists': True, 'pr': 3, 'ciReady': True, 'candidateReady': True,
                    'releaseReady': True, 'hostCheckNeeded': True}
        self.assertEqual('dispatch_host_check', flow.next_action({}, observed))
        observed['hostCheckDispatched'] = True
        self.assertEqual('wait_host_check', flow.next_action({}, observed))


class PlanningTest(unittest.TestCase):
    def make_api(self, files=None, source='develop'):
        data = {
            ('GET', ''): {'permissions': {'push': True}, 'allow_merge_commit': True},
            ('GET', '/git/ref/heads/' + flow.quote(source, safe='')): {'object': {'sha': 'a'*40}},
            ('GET', '/git/ref/heads/main-tree'): {'object': {'sha': 'b'*40}},
            ('GET', '/commits/' + 'a'*40): {'sha': 'a'*40, 'commit': {'tree': {'sha': 'c'*40}}},
            ('GET', '/tags?per_page=100&page=1'): [{'name': 'v2026.11.9', 'commit': {'sha': 'b'*40}}],
            ('GET', '/branches?per_page=100&page=1'): [{'name': source}, {'name': 'main-tree'}],
            ('GET', '/compare/' + 'b'*40 + '...' + 'a'*40): {
                'status': 'ahead', 'merge_base_commit': {'sha': 'b'*40},
                'files': [{'filename': p} for p in (files if files is not None else ['src/main/Foo.java'])]},
        }
        return flow.GitHubAPI(FakeTransport(data)), data

    def test_docs_only_plan_reads_remote_without_writes(self):
        self.assertTrue(hasattr(flow.GitHubAPI, 'plan'), 'remote planning must exist')
        api, _ = self.make_api(['docs/guides/example.md', 'README.md'])
        plan = api.plan('develop', '2026-11-10')
        self.assertTrue(plan['documentationOnly'])
        self.assertEqual('a'*40, plan['head'])
        self.assertEqual([], api.transport.writes)

    def test_versioned_hotfix_keeps_its_reserved_version(self):
        self.assertTrue(hasattr(flow.GitHubAPI, 'plan'), 'remote planning must exist')
        source = 'hotfix/2026.11.10.7-cost'
        api, _ = self.make_api(source=source)
        plan = api.plan(source, '2026-11-10')
        self.assertEqual('v2026.11.10.7', plan['version'])
        self.assertEqual(source, plan['branch'])

    def test_unrelated_hotfix_ancestry_is_rejected(self):
        self.assertTrue(hasattr(flow.GitHubAPI, 'plan'), 'remote planning must exist')
        api, data = self.make_api(source='hotfix/fix')
        key = ('GET', '/compare/' + 'b'*40 + '...' + 'a'*40)
        data[key]['merge_base_commit']['sha'] = 'd'*40
        with self.assertRaises(flow.FlowError):
            api.plan('hotfix/fix', '2026-11-10')

    def test_missing_write_permission_fails_before_version_reservation(self):
        self.assertTrue(hasattr(flow.GitHubAPI, 'plan'), 'remote planning must exist')
        api, data = self.make_api()
        data[('GET', '')]['permissions']['push'] = False
        with self.assertRaises(flow.FlowError):
            api.plan('develop', '2026-11-10')
        self.assertEqual([], api.transport.writes)


class ArtifactTest(unittest.TestCase):
    def bundle(self, files):
        import io, zipfile
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            for name, content in files.items():
                archive.writestr(name, json.dumps(content))
        return buffer.getvalue()

    def adapter(self, files):
        import hashlib
        raw = self.bundle(files)
        descriptor = {'id': 90, 'name': 'deployment-receipt', 'expired': False,
                      'digest': 'sha256:' + hashlib.sha256(raw).hexdigest(),
                      'created_at': '2026-11-10T10:00:10Z'}
        transport = FakeTransport({('GET', '/actions/runs/50/artifacts?per_page=100&page=1'): {'artifacts': [descriptor]}})
        transport.archive = lambda identity: raw
        return flow.GitHubAPI(transport), descriptor

    def test_artifact_digest_and_path_are_verified_before_reading_receipt(self):
        self.assertTrue(hasattr(flow.GitHubAPI, 'artifact_files'), 'artifact verification must exist')
        api, descriptor = self.adapter({'receipt.json': {'phase': 'succeeded'}})
        run = {'id': 50, 'run_started_at': '2026-11-10T10:00:00Z', 'updated_at': '2026-11-10T10:01:00Z'}
        self.assertEqual({'phase': 'succeeded'}, json.loads(api.artifact_files(run, 'deployment-receipt')['receipt.json']))
        descriptor['digest'] = 'sha256:' + '0'*64
        with self.assertRaises(flow.FlowError):
            api.artifact_files(run, 'deployment-receipt')
        api, _ = self.adapter({'../receipt.json': {'phase': 'succeeded'}})
        with self.assertRaises(flow.FlowError):
            api.artifact_files(run, 'deployment-receipt')

    def test_prior_attempt_artifact_cannot_prove_current_attempt(self):
        self.assertTrue(hasattr(flow.GitHubAPI, 'artifact_files'), 'artifact verification must exist')
        api, _ = self.adapter({'receipt.json': {'phase': 'succeeded'}})
        run = {'id': 50, 'run_started_at': '2026-11-10T11:00:00Z', 'updated_at': '2026-11-10T11:01:00Z'}
        self.assertIsNone(api.artifact_files(run, 'deployment-receipt'))


class ObservationTest(unittest.TestCase):
    def test_changed_source_branch_stops_before_any_mutation(self):
        self.assertTrue(hasattr(flow.GitHubAPI, 'observe'), 'remote observation must exist')
        j = {'head': 'a'*40, 'base': 'b'*40, 'branch': 'release/2026.11.10-flow',
             'source': 'develop', 'version': 'v2026.11.10', 'documentationOnly': False}
        transport = FakeTransport({
            ('GET', '/git/ref/heads/main-tree'): {'object': {'sha': 'b'*40}},
            ('GET', '/git/ref/heads/release%2F2026.11.10-flow'): {'object': {'sha': 'c'*40}}})
        result = flow.GitHubAPI(transport).observe(j)
        self.assertTrue(result['baselineChanged'])
        self.assertEqual([], transport.writes)


class CompletionTest(unittest.TestCase):
    def test_backmerge_requires_ci_for_combined_tree_and_noff(self):
        self.assertTrue(hasattr(flow.GitHubAPI, 'backmerge'), 'backmerge implementation must exist')
        journal = {'head': 'a'*40, 'mergeCommit': 'b'*40, 'branch': 'release/2026.11.10-flow',
                   'version': 'v2026.11.10', 'id': 'c'*32}
        transport = FakeTransport({('GET', '/git/ref/heads/develop'): {'object': {'sha': 'd'*40}},
            ('GET', '/pulls/21'): {'head': {'sha': 'b'*40}, 'base': {'sha': 'd'*40},
                                  'mergeable': True, 'mergeable_state': 'clean'},
            ('PUT', '/pulls/21/merge'): {'merged': True, 'sha': 'e'*40}})
        api = flow.GitHubAPI(transport)
        observed = {'backmergeTarget': 'develop', 'backmergePR': 21, 'backmergeBase': 'd'*40, 'backmergeReady': False}
        with self.assertRaises(flow.FlowError):
            api.backmerge(journal, observed)
        self.assertEqual([], transport.writes)
        observed['backmergeReady'] = True
        api.backmerge(journal, observed)
        self.assertEqual({'sha': 'b'*40, 'merge_method': 'merge'}, transport.writes[0][2])

    def test_publish_receipt_only_uploads_verified_original_bundle(self):
        self.assertTrue(hasattr(flow.GitHubAPI, 'publish_receipt'), 'receipt recovery must exist')
        transport = FakeTransport({('GET', '/releases/tags/v2026.11.10'): None})
        commands = []
        def command(*args, **kwargs):
            commands.append(args)
            if args[:2] == ('release', 'create'):
                paths = [Path(arg) for arg in args if str(arg).endswith('.json')]
                self.assertEqual({'release.json', 'receipt.json'}, {p.name for p in paths})
                self.assertEqual({'phase': 'succeeded', 'id': 'a'*32}, json.loads(next(p for p in paths if p.name == 'receipt.json').read_bytes()))
            return b''
        transport.command = command
        api = flow.GitHubAPI(transport)
        api.receipt_files = {'release.json': b'{}', 'receipt.json': json.dumps({'phase': 'succeeded', 'id': 'a'*32}).encode()}
        api.publish_receipt({'version': 'v2026.11.10'})
        self.assertEqual([('release', 'create')], [c[:2] for c in commands])

    def test_successful_dispatch_is_not_repeated_during_discovery_lag(self):
        j = {'phase': 'validating', 'candidateDispatchPending': True}
        observed = {'branchExists': True, 'pr': 1, 'ciReady': True}
        self.assertEqual('wait_candidate', flow.next_action(j, observed))


class CLITest(unittest.TestCase):
    def test_disabled_new_release_does_not_even_plan_remote_writes(self):
        self.assertTrue(hasattr(flow, 'main'), 'CLI entrypoint must exist')
        from unittest.mock import patch
        with redirect_stdout(io.StringIO()), patch.dict(flow.os.environ, {'HEYI_RELEASE_FLOW_ENABLED': 'false'}):
            with patch.object(flow, 'GitHubAPI', side_effect=AssertionError('No remote access needed')):
                self.assertEqual(2, flow.main(['--source', 'develop']))

    def test_help_describes_supported_recovery_without_network(self):
        self.assertTrue(hasattr(flow, 'main'), 'CLI entrypoint must exist')
        from unittest.mock import patch
        with redirect_stdout(io.StringIO()), patch.object(flow, 'GitHubAPI', side_effect=AssertionError('No remote access needed')):
            with self.assertRaises(SystemExit) as exit:
                flow.main(['--help'])
            self.assertEqual(0, exit.exception.code)


class ProofRegressionTest(unittest.TestCase):
    def test_pr_validation_rejects_success_on_different_base(self):
        pr = {'number': 10, 'head': {'sha': 'a'*40}, 'base': {'sha': 'b'*40}}
        run = {'id': 50, 'run_attempt': 1, 'status': 'completed', 'conclusion': 'success',
               'event': 'pull_request', 'head_sha': 'a'*40, 'path': '.github/workflows/ci.yml',
               'repository': {'full_name': flow.REPOSITORY}, 'head_repository': {'full_name': flow.REPOSITORY},
               'pull_requests': [{'number': 10, 'head': {'sha': 'a'*40}, 'base': {'sha': 'c'*40}}]}
        endpoint = '/actions/workflows/ci.yml/runs?head_sha=' + 'a'*40 + '&per_page=100&page=1'
        api = flow.GitHubAPI(FakeTransport({('GET', endpoint): {'workflow_runs': [run]}}))
        self.assertFalse(api.pr_validation(pr))

    def test_dirty_recovery_does_not_publish_receipts(self):
        j = {'version': 'v2026.11.10', 'operationId': 'a'*32}
        observation = {'dirty': True, 'deploymentPhase': 'succeeded', 'version': 'v2026.11.10',
                       'operationId': 'a'*32, 'receiptPublished': False}
        self.assertEqual('dirty_worktree', flow.next_action(j, observation))

    def test_backmerge_dispatch_wait_survives_process_interruption(self):
        j = {'pendingAction': 'dispatch_validation'}
        observed = {'branchExists': True, 'pr': 3, 'ciReady': True, 'needsTrustedValidation': True}
        self.assertEqual('wait_validation', flow.next_action(j, observed))


class FinalGuardTest(unittest.TestCase):
    def test_candidate_without_host_proof_never_merges(self):
        observed = {'branchExists': True, 'pr': 1, 'ciReady': True, 'candidateReady': True, 'releaseReady': True}
        self.assertEqual('dispatch_host_check', flow.next_action({}, observed))

    def test_merged_release_branches_are_not_active_hotfix_targets(self):
        source = 'hotfix/fix'
        api, data = PlanningTest().make_api(source=source)
        data[('GET', '/branches?per_page=100&page=1')].append({'name': 'release/2026.11.9-old', 'commit': {'sha': 'd'*40}})
        data[('GET', '/compare/' + 'd'*40 + '...' + 'b'*40)] = {'merge_base_commit': {'sha': 'd'*40}}
        self.assertEqual([], api.plan(source, '2026-11-10')['activeReleases'])

    def test_new_release_does_not_run_parallel_to_unmerged_release(self):
        api, data = PlanningTest().make_api()
        data[('GET', '/branches?per_page=100&page=1')].append({'name': 'release/2026.11.10-other', 'commit': {'sha': 'd'*40}})
        data[('GET', '/compare/' + 'd'*40 + '...' + 'b'*40)] = {'merge_base_commit': {'sha': 'b'*40}}
        plan = api.plan('develop', '2026-11-10')
        self.assertTrue(plan['parallelRelease'])

    def test_resume_identifier_cannot_inject_other_repository_targets(self):
        self.assertTrue(hasattr(flow, 'validate_journal'), 'recovered identities require validation')
        j = {'id': 'a'*32, 'head': 'b'*40, 'base': 'c'*40, 'tree': 'd'*40,
             'version': 'v2026.11.10', 'source': 'develop', 'branch': 'release/2026.11.10-flow',
             'baselineVersion': 'v2026.11.9'}
        flow.validate_journal(j)
        for key, value in [('head', '../foo'), ('version', '--repo=evil/repo'), ('branch', 'main-tree')]:
            with self.assertRaises(flow.FlowError):
                flow.validate_journal({**j, key: value})


class EvidenceSelectionTest(unittest.TestCase):
    def fixture(self):
        import hashlib
        run = {'id': 123, 'run_attempt': 2, 'event': 'push', 'head_sha': 'a'*40,
               'path': '.github/workflows/ci.yml', 'status': 'completed', 'conclusion': 'success',
               'repository': {'full_name': flow.REPOSITORY}, 'head_repository': {'full_name': flow.REPOSITORY}}
        request = {'repository': flow.REPOSITORY, 'tree': 'b'*40, 'policyDigest': 'c'*64,
                   'toolchainDigest': 'd'*64, 'requiredSuites': ['java-unit'], 'suiteInputDigests': {'java-unit': 'e'*64}}
        value = {**request, 'head': 'a'*40, 'workflow': '.github/workflows/ci.yml', 'runId': 123,
                 'runAttempt': 2, 'profile': 'backend', 'passedSuites': ['java-unit'], 'result': 'passed', 'unexpectedSkips': 0,
                 'build': {'archiveSha256': '1'*64, 'compiledArtifactSha256': '2'*64, 'buildRecipeDigest': '3'*64}}
        raw = ArtifactTest().bundle({'evidence.json': value})
        artifact = {'id': 321, 'expired': False, 'workflow_run': {'id': 123, 'head_sha': 'a'*40},
                    'digest': 'sha256:' + hashlib.sha256(raw).hexdigest()}
        transport = FakeTransport({('GET', '/commits/' + 'a'*40): {'commit': {'tree': {'sha': 'b'*40}}},
            ('GET', '/actions/runs/123/artifacts?per_page=100&page=1'): {'artifacts': [{
                'name': 'compiled-2', 'expired': False, 'size_in_bytes': 1000, 'digest': 'sha256:' + '4'*64,
                'workflow_run': {'id': 123, 'head_sha': 'a'*40}}]}})
        api = flow.GitHubAPI(transport)
        api.artifact_bundle = lambda *a, **k: (artifact, raw, {'evidence.json': json.dumps(value).encode()})
        return api, run, request

    def test_narrow_ci_cannot_be_reused_for_broader_release(self):
        self.assertTrue(hasattr(flow.GitHubAPI, 'qualified_ci'), 'coverage screening must exist')
        api, run, request = self.fixture()
        self.assertTrue(api.qualified_ci(run, {'head': 'a'*40, 'tree': 'b'*40}, request))
        broader = {**request, 'requiredSuites': ['java-unit', 'finance-db'],
                   'suiteInputDigests': {**request['suiteInputDigests'], 'finance-db': 'f'*64}}
        self.assertFalse(api.qualified_ci(run, {'head': 'a'*40, 'tree': 'b'*40}, broader))

    def test_missing_compiled_artifact_falls_back_to_new_validation(self):
        api, run, request = self.fixture()
        api.transport.responses[('GET', '/actions/runs/123/artifacts?per_page=100&page=1')] = {'artifacts': []}
        self.assertFalse(api.qualified_ci(run, {'head': 'a'*40, 'tree': 'b'*40}, request))

    def test_missing_request_does_not_reuse_partial_evidence(self):
        self.assertTrue(hasattr(flow.GitHubAPI, 'qualified_ci'), 'coverage screening must exist')
        api, run, _ = self.fixture()
        self.assertFalse(api.qualified_ci(run, {'head': 'a'*40, 'tree': 'b'*40}, None))


class MissingProofTest(unittest.TestCase):
    def test_missing_candidate_after_merge_prevents_tagging(self):
        self.assertEqual('candidate_mismatch', flow.next_action({}, {'productionMerged': True, 'candidateReady': False}))

    def test_branch_reservation_requires_strict_protection(self):
        transport = FakeTransport({('GET', '/git/ref/heads/develop'): {'object': {'sha': 'a'*40}},
                                   ('GET', '/git/ref/heads/main-tree'): {'object': {'sha': 'b'*40}}})
        api = flow.GitHubAPI(transport)
        j = {'source': 'develop', 'head': 'a'*40, 'base': 'b'*40, 'branch': 'release/2026.11.10-flow'}
        with self.assertRaises(flow.FlowError):
            api.perform('create_branch', j, {})
        self.assertEqual([], transport.writes)


class RecoveryPinTest(unittest.TestCase):
    def test_mismatch_does_not_overwrite_original_run_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            journal = {'id': 'a'*32, 'version': 'v2026.11.10', 'phase': 'deploying',
                       'deployRun': 123, 'deployAttempt': 1, 'operationId': 'b'*32}
            api = MemoryAPI({'deployRun': 999, 'deployAttempt': 2, 'operationId': 'c'*32,
                             'deploymentPhase': 'succeeded', 'version': journal['version']})
            self.assertEqual('operation_mismatch', flow.advance(journal, api, flow.JournalStore(Path(directory)), enabled=True))
            self.assertEqual((123, 1, 'b'*32), (journal['deployRun'], journal['deployAttempt'], journal['operationId']))

    def test_disabled_terminal_observation_does_not_write_journal(self):
        with tempfile.TemporaryDirectory() as directory:
            store = flow.JournalStore(Path(directory))
            journal = {'id': 'a'*32, 'phase': 'planned'}
            self.assertEqual('no_runtime_release', flow.advance(journal, MemoryAPI({'documentationOnly': True}), store, enabled=False))
            self.assertFalse(store.root.exists())

    def test_attempt_endpoint_response_must_match_pinned_attempt(self):
        journal = {'version': 'v2026.11.10', 'mergeCommit': 'a'*40, 'deployRun': 123, 'deployAttempt': 1}
        wrong = {'id': 123, 'run_attempt': 2, 'event': 'push', 'head_branch': 'v2026.11.10',
                 'path': '.github/workflows/deploy.yml', 'head_sha': 'a'*40, 'status': 'in_progress',
                 'repository': {'full_name': flow.REPOSITORY}, 'head_repository': {'full_name': flow.REPOSITORY}}
        api = flow.GitHubAPI(FakeTransport({('GET', '/actions/runs/123/attempts/1'): wrong}))
        self.assertTrue(api.deploy_observation(journal).get('operationMismatch'))


class BackmergeProofTest(unittest.TestCase):
    def test_fast_forward_cannot_masquerade_as_completed_gitflow_backmerge(self):
        journal = {'branch': 'release/2026.11.10-flow', 'version': 'v2026.11.10', 'mergeCommit': 'a'*40}
        transport = FakeTransport({
            ('GET', '/branches?per_page=100&page=1'): [],
            ('GET', '/git/ref/heads/release%2F2026.11.10-backmerge'): {'object': {'sha': 'a'*40}},
            ('GET', '/git/ref/heads/develop'): {'object': {'sha': 'a'*40}},
            ('GET', '/compare/' + 'a'*40 + '...' + 'a'*40): {'merge_base_commit': {'sha': 'a'*40}},
            ('GET', '/pulls?head=Gotit-zhx%3Arelease%2F2026.11.10-backmerge&base=develop&state=all&per_page=100&page=1'): []})
        with self.assertRaises(flow.FlowError):
            flow.GitHubAPI(transport).backmerge_observation(journal)


class RepositoryServer:
    """REST-shaped in-memory GitHub; no production/network calls are possible."""
    def __init__(self):
        import test_release
        self.refs = {'heads/develop': 'a'*40, 'heads/main-tree': 'b'*40, 'tags/v2026.11.9': 'b'*40}
        self.commits = {'a'*40: self.commit('a'*40, 'c'*40, ['b'*40]),
                        'b'*40: self.commit('b'*40, 'd'*40, [])}
        self.prs = {}
        self.releases = {}
        self.workflow_runs = {'ci.yml': [], 'candidate.yml': [], 'operations.yml': [], 'deploy.yml': []}
        self.artifacts = {}
        self.archives = {}
        self.effects = []
        self.manifest = test_release.ReleaseTest().manifest()
        self.manifest.update(version='v2026.11.10')
        self.manifest['source'].update(commit='a'*40, tree='c'*40, ref='refs/heads/release/2026.11.10-flow')
        self.manifest_raw = json.dumps(self.manifest).encode()
        self.run('ci.yml', 100, 'a'*40, 'develop', 'push')
        self.evidence(100, 'a'*40, 'c'*40)

    def commit(self, sha, tree, parents):
        return {'sha': sha, 'commit': {'tree': {'sha': tree}}, 'parents': [{'sha': p} for p in parents]}

    def run(self, workflow, identity, head, branch, event, prs=None):
        value = {'id': identity, 'run_attempt': 1, 'head_sha': head, 'head_branch': branch, 'event': event,
                 'path': '.github/workflows/' + workflow, 'status': 'completed', 'conclusion': 'success',
                 'repository': {'full_name': flow.REPOSITORY}, 'head_repository': {'full_name': flow.REPOSITORY},
                 'run_started_at': '2026-11-10T10:00:00Z', 'updated_at': '2026-11-10T10:01:00Z',
                 'pull_requests': prs or []}
        self.workflow_runs[workflow].append(value)
        return value

    def bundle(self, run_id, name, files):
        import hashlib
        raw = ArtifactTest().bundle(files)
        descriptor = {'id': run_id, 'name': name, 'expired': False, 'created_at': '2026-11-10T10:00:30Z',
                      'digest': 'sha256:' + hashlib.sha256(raw).hexdigest(),
                      'workflow_run': {'id': run_id, 'head_sha': next(r['head_sha'] for runs in self.workflow_runs.values() for r in runs if r['id'] == run_id)}}
        self.artifacts[run_id] = [descriptor]
        self.archives[run_id] = raw

    def evidence(self, run_id, head, tree):
        import scope
        value = {'repository': flow.REPOSITORY, 'tree': tree, 'head': head, 'policyDigest': 'e'*64,
                 'toolchainDigest': 'f'*64, 'requiredSuites': scope.FULL, 'passedSuites': scope.FULL,
                 'suiteInputDigests': {s: '1'*64 for s in scope.FULL}, 'profile': 'full',
                 'workflow': '.github/workflows/ci.yml', 'runId': run_id, 'runAttempt': 1,
                 'result': 'passed', 'unexpectedSkips': 0,
                 'build': {'archiveSha256': '1'*64, 'compiledArtifactSha256': '2'*64, 'buildRecipeDigest': '3'*64}}
        self.bundle(run_id, 'validation-evidence-1', {'evidence.json': value})
        self.artifacts[run_id].append({'name': 'compiled-1', 'expired': False, 'size_in_bytes': 1000,
            'digest': 'sha256:' + '4'*64, 'workflow_run': {'id': run_id, 'head_sha': head}})

    def archive(self, identity):
        return self.archives[identity]

    def command(self, *args, **kwargs):
        if args[:2] == ('attestation', 'verify'):
            return b''
        if args[:2] == ('release', 'download'):
            tag, pattern = args[2], args[args.index('--pattern') + 1]
            directory = Path(args[args.index('--dir') + 1])
            directory.joinpath(pattern).write_bytes(self.releases[tag][pattern])
            return b''
        if args[:2] == ('release', 'create'):
            self.effects.append(('publish_receipt', args[2]))
            self.releases[args[2]] = {Path(a).name: Path(a).read_bytes() for a in args if a.endswith('.json')}
            return b''
        raise AssertionError(('unexpected gh command', args))

    def api(self, path, method='GET', payload=None, missing=False):
        from urllib.parse import urlsplit, parse_qs, unquote
        route, query = urlsplit(path).path, parse_qs(urlsplit(path).query)
        if method != 'GET':
            self.effects.append((method, route, copy.deepcopy(payload)))
        if route == '' and method == 'GET':
            return {'permissions': {'push': True}, 'allow_merge_commit': True}
        if route.startswith('/git/ref/'):
            sha = self.refs.get(unquote(route[len('/git/ref/'):]))
            return {'object': {'sha': sha, 'type': 'commit'}} if sha else None
        if route == '/git/refs' and method == 'POST':
            name = payload['ref'].removeprefix('refs/')
            if name in self.refs:
                raise flow.FlowError('ref already exists')
            self.refs[name] = payload['sha']
            if name.startswith('tags/v'):
                run = self.run('deploy.yml', 400, payload['sha'], name.removeprefix('tags/'), 'push')
                run['conclusion'] = 'failure'  # Host succeeded; later receipt publication job failed.
                operation = flow.hashlib.sha256(('400:1:' + self.manifest['version']).encode()).hexdigest()[:32]
                self.bundle(400, 'deployment-receipt', {'release.json': self.manifest,
                            'receipt.json': {'id': operation, 'version': self.manifest['version'], 'phase': 'succeeded'},
                            'timings.json': {'schemaVersion': 1}})
            return {'ref': payload['ref']}
        if route == '/tags':
            return [{'name': n[5:], 'commit': {'sha': sha}} for n, sha in self.refs.items() if n.startswith('tags/v')]
        if route == '/branches':
            return [{'name': n[6:], 'commit': {'sha': sha}} for n, sha in self.refs.items() if n.startswith('heads/')]
        if route.endswith('/protection/required_status_checks'):
            return {'strict': True, 'contexts': ['CI required', 'Release ready']}
        if route.startswith('/commits/') and route.endswith('/check-runs'):
            return {'check_runs': [{'name': n, 'app': {'slug': 'github-actions'}, 'status': 'completed', 'conclusion': 'success'}
                                   for n in ['CI required', *(['Release ready'] if 'candidate-' + 'a'*40 in self.releases else [])]]}
        if route.startswith('/commits/'):
            return copy.deepcopy(self.commits[route.split('/')[-1]])
        if route.startswith('/compare/'):
            base, head = route.split('/')[-1].split('...')
            ancestors = set()
            todo = [head]
            while todo:
                current = todo.pop()
                if current in ancestors:
                    continue
                ancestors.add(current)
                todo.extend(p['sha'] for p in self.commits[current]['parents'])
            merge_base = base if base in ancestors else head
            return {'merge_base_commit': {'sha': merge_base}, 'files': [{'filename': 'src/main/Foo.java'}]}
        if route == '/pulls' and method == 'GET':
            head = query['head'][0].split(':', 1)[1]
            return [copy.deepcopy(p) for p in self.prs.values() if p['head']['ref'] == head and p['base']['ref'] == query['base'][0]]
        if route == '/pulls' and method == 'POST':
            number = 20 + len(self.prs)
            pr = {'number': number, 'head': {'sha': self.refs['heads/' + payload['head']], 'ref': payload['head']},
                  'base': {'sha': self.refs['heads/' + payload['base']], 'ref': payload['base']},
                  'state': 'open', 'merged': False, 'merged_at': None, 'mergeable': True, 'mergeable_state': 'clean'}
            self.prs[number] = pr
            self.run('ci.yml', 200 + number, pr['head']['sha'], payload['head'], 'pull_request', [copy.deepcopy(pr)])
            self.evidence(200 + number, pr['head']['sha'], self.commits[pr['head']['sha']]['commit']['tree']['sha'])
            return copy.deepcopy(pr)
        if route.startswith('/pulls/'):
            number = int(route.split('/')[2])
            pr = self.prs[number]
            if method == 'PUT':
                if payload != {'sha': pr['head']['sha'], 'merge_method': 'merge'}:
                    raise AssertionError('wrong merge constraints')
                sha = ('5' if number == 20 else '6')*40
                self.commits[sha] = self.commit(sha, self.commits[pr['head']['sha']]['commit']['tree']['sha'], [pr['base']['sha'], pr['head']['sha']])
                pr.update(merged=True, state='closed', merged_at='2026-11-10T10:01:00Z', merge_commit_sha=sha)
                self.refs['heads/' + pr['base']['ref']] = sha
                return {'merged': True, 'sha': sha}
            return copy.deepcopy(pr)
        if route.startswith('/releases/tags/'):
            tag = route.removeprefix('/releases/tags/')
            return {'assets': [{'name': n} for n in self.releases[tag]]} if tag in self.releases else None
        if route.startswith('/actions/workflows/'):
            workflow = route.split('/')[3]
            if method == 'GET':
                return {'workflow_runs': [copy.deepcopy(r) for r in self.workflow_runs[workflow]
                                         if not query.get('head_sha') or r['head_sha'] == query['head_sha'][0]]}
            if workflow == 'candidate.yml':
                if payload['inputs'] != {'expected_head': 'a'*40, 'validation_run': '100', 'validation_attempt': '1'}:
                    raise AssertionError('candidate evidence inputs mismatch')
                self.run(workflow, 300, 'a'*40, payload['ref'], 'workflow_dispatch')
                self.refs['tags/candidate-' + 'a'*40] = 'a'*40
                self.releases['candidate-' + 'a'*40] = {'release.json': self.manifest_raw}
                return {}
            if workflow == 'operations.yml':
                run = self.run(workflow, 350, 'b'*40, 'main-tree', 'workflow_dispatch')
                run['display_title'] = 'Operations release-check'
                self.bundle(350, 'release-check', {'status.json': {
                    'version': 'v2026.11.9', 'observedAt': 1794304800, 'sourceCommit': 'b'*40,
                    'controlPlaneRevision': 'd'*40, 'operationsInputs': 'a'*64, 'migrationInputs': 'a'*64,
                    'schemaFingerprint': 'e'*64, 'releaseSchemas': [1, 2], 'transferProtocols': [1, 2],
                    'maintenance': False, 'operations': []}})
                return {}
        if route.startswith('/actions/runs/'):
            identity = int(route.split('/')[3])
            if route.endswith('/artifacts'):
                return {'artifacts': copy.deepcopy(self.artifacts.get(identity, []))}
            return copy.deepcopy(next(r for runs in self.workflow_runs.values() for r in runs if r['id'] == identity))
        raise AssertionError(('unexpected API', method, path, payload))


class EndToEndTest(unittest.TestCase):
    def test_real_adapter_finishes_gitflow_without_redeploy_on_receipt_failure(self):
        server = RepositoryServer()
        adapter = flow.GitHubAPI(server, clock=lambda: 1794304860)
        journal = adapter.plan('develop', '2026-11-10')
        actions = []
        with tempfile.TemporaryDirectory() as directory:
            store = flow.JournalStore(Path(directory))
            for _ in range(20):
                action = flow.advance(journal, adapter, store, enabled=True)
                actions.append(action)
                if action == 'complete':
                    break
            self.assertEqual('complete', actions[-1], actions)
            self.assertEqual('complete', store.read(journal['id'])['phase'])
        self.assertEqual(1, len(server.workflow_runs['deploy.yml']))
        self.assertEqual(1, actions.count('dispatch_candidate'))
        self.assertEqual(1, actions.count('publish_receipt'))
        self.assertEqual('5'*40, server.refs['tags/v2026.11.10'])
        self.assertEqual('6'*40, server.refs['heads/develop'])


class DispatchRecoveryTest(unittest.TestCase):
    def test_validation_dispatch_intent_pins_previous_run_before_network_call(self):
        class LostResponse(MemoryAPI):
            def perform(self, action, journal, observed):
                raise OSError('connection interrupted after dispatch')
        journal = {'id': 'a'*32, 'phase': 'validating'}
        api = LostResponse({'branchExists': True, 'pr': 20, 'ciReady': True,
                            'needsTrustedValidation': True, 'validationObservedRun': 100})
        with tempfile.TemporaryDirectory() as directory:
            store = flow.JournalStore(Path(directory))
            with self.assertRaises(OSError):
                flow.advance(journal, api, store, enabled=True)
            saved = store.read(journal['id'])
            self.assertEqual(100, saved.get('validationAfterRun'))
            self.assertEqual('dispatch_validation', saved['pendingAction'])


class SampleTest(unittest.TestCase):
    def test_acceptance_and_completion_update_one_receipt_bound_sample_across_resume(self):
        self.assertTrue(hasattr(flow.JournalStore, 'write_sample'), 'durable operation samples must exist')
        with tempfile.TemporaryDirectory() as directory:
            store = flow.JournalStore(Path(directory))
            journal = {'id': 'a'*32, 'operationId': 'b'*32, 'version': 'v2026.11.10', 'phase': 'deploying',
                       'startedAtMs': 1000, 'releaseType': 'backend'}
            observation = {'deploymentPhase': 'succeeded', 'version': journal['version'], 'operationId': 'b'*32,
                           'receiptPublished': False, 'validationProfile': 'module', 'uploadedBytes': 42,
                           'phases': [{'phase': 'sshUpload', 'durationMs': 123, 'bytes': 42, 'status': 'passed'}]}
            api = MemoryAPI(observation)
            self.assertEqual('publish_receipt', flow.advance(journal, api, store, enabled=True, clock=lambda: 6))
            resumed = store.read(journal['id'])
            api.state.update(backmergesComplete=True)
            self.assertEqual('complete', flow.advance(resumed, api, store, enabled=True, clock=lambda: 10))
            files = list((store.root/'samples').glob('*.json'))
            self.assertEqual(1, len(files))
            sample = json.loads(files[0].read_text())
            self.assertEqual(('b'*32, 5000, 9000, 'unknown', 42, 'module'),
                             (sample['operationId'], sample['timeToAcceptedMs'], sample['flowCompleteMs'],
                              sample['cacheState'], sample['uploadedBytes'], sample['profile']))
            import performance_summary
            self.assertEqual('insufficient_samples', performance_summary.summarize([sample])['groups'][0]['status'])

    def test_failed_deployment_remains_in_sample_without_acceptance_time(self):
        self.assertTrue(hasattr(flow.JournalStore, 'write_sample'), 'durable operation samples must exist')
        with tempfile.TemporaryDirectory() as directory:
            store = flow.JournalStore(Path(directory))
            j = {'id': 'a'*32, 'operationId': 'b'*32, 'version': 'v2026.11.10', 'startedAtMs': 1000, 'releaseType': 'high-risk'}
            api = MemoryAPI({'deploymentPhase': 'rolled_back', 'operationId': 'b'*32, 'version': j['version'], 'validationProfile': 'full'})
            self.assertEqual('deployment_failed', flow.advance(j, api, store, enabled=True, clock=lambda: 5))
            sample = json.loads((store.root/'samples'/('b'*32+'.json')).read_text())
            self.assertEqual('failed', sample['result'])
            self.assertIsNone(sample['timeToAcceptedMs'])
            self.assertEqual([], sample['phases'])
            self.assertIsNone(sample['uploadedBytes'])


class CandidateDiscoveryTest(unittest.TestCase):
    def test_new_release_reports_existing_immutable_candidate_before_reserving_version(self):
        server = RepositoryServer()
        server.releases['candidate-' + 'a'*40] = {'release.json': server.manifest_raw}
        api = flow.GitHubAPI(server)
        journal = api.plan('develop', '2026-11-10')
        observed = api.observe(journal)
        self.assertEqual('candidate-' + 'a'*40, observed.get('existingCandidate'))
        self.assertEqual('candidate_mismatch', flow.next_action(journal, observed))
        self.assertEqual([], server.effects)


if __name__ == '__main__':
    unittest.main()
