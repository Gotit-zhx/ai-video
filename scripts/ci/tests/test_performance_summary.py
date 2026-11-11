import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from performance_summary import nearest_rank, summarize


class PerformanceTest(unittest.TestCase):
    def sample(self, elapsed, **changes):
        self.serial = getattr(self, 'serial', 0) + 1
        return dict({'operationId': format(self.serial, '032x'),
                     'releaseType': 'frontend', 'profile': 'module',
                     'cacheState': 'warm', 'category': 'normal', 'result': 'accepted',
                     'timeToAcceptedMs': elapsed, 'flowCompleteMs': elapsed+1000 if elapsed is not None else None,
                     'uploadedBytes': 1024, 'phases': []}, **changes)

    def test_nearest_rank_and_input_protection(self):
        values = list(range(10, 0, -1))
        self.assertEqual(5, nearest_rank(values, .5))
        self.assertEqual(10, nearest_rank(values, .95))
        self.assertEqual(list(range(10, 0, -1)), values)
        for values, probability in [([], .5), ([True], .5), ([float('nan')], .5), ([1], 0)]:
            with self.assertRaises(ValueError): nearest_rank(values, probability)

    def test_samples_are_not_combined_across_profiles_or_cache_states(self):
        rows = [self.sample(1000+i) for i in range(9)] + [self.sample(100, cacheState='cold')]
        report = summarize(rows)
        self.assertEqual(2, len(report['groups']))
        self.assertTrue(all(g['status'] == 'insufficient_samples' for g in report['groups']))

    def test_network_failure_is_retained_and_prevents_success_claim(self):
        rows = [self.sample(1000+i) for i in range(10)]
        rows += [self.sample(None, result='failed', flowCompleteMs=None)]
        group = summarize(rows)['groups'][0]
        self.assertEqual(11, group['attemptCount'])
        self.assertEqual(1, group['failedCount'])
        self.assertEqual('has_failures', group['status'])

    def test_valid_target_uses_accepted_time_and_reports_full_flow_separately(self):
        group = summarize([self.sample(200000, flowCompleteMs=900000) for _ in range(10)])['groups'][0]
        self.assertEqual('target_met', group['status'])
        self.assertEqual(200000, group['timeToAcceptedMs']['p50'])
        self.assertEqual(900000, group['flowCompleteMs']['p95'])

    def test_duplicate_operations_cannot_inflate_sample_count(self):
        row = self.sample(100)
        with self.assertRaises(ValueError):
            summarize([row, dict(row)])

    def test_phase_metrics_include_failures_without_exporting_unknown_fields(self):
        row = self.sample(100, phases=[{'phase':'serverReceive', 'durationMs':50,
                                     'bytes':300, 'status':'passed', 'private':'secret'}])
        phase = summarize([row])['groups'][0]['phases'][0]
        self.assertEqual(50, phase['durationMs']['p50'])
        self.assertEqual(300, phase['bytes']['p95'])
        self.assertNotIn('secret', str(phase))
