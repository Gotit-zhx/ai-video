import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from timing import TimingRecorder


class TimingTest(unittest.TestCase):
    def test_measure_uses_elapsed_clock_and_aggregates_bytes(self):
        ticks = iter([10.0, 10.25, 20.0, 20.75])
        recorder = TimingRecorder(clock=lambda: next(ticks))
        for size in (12, 20):
            with recorder.measure('serverReceive') as sample:
                sample.add_bytes(size)
        self.assertEqual([{'phase': 'serverReceive', 'durationMs': 1000,
                           'bytes': 32, 'status': 'passed', 'count': 2}],
                         recorder.snapshot()['phases'])

    def test_failure_does_not_export_exception_or_clear_previous_failure(self):
        ticks = iter([1.0, 1.1])
        recorder = TimingRecorder(clock=lambda: next(ticks))
        with self.assertRaisesRegex(RuntimeError, 'synthetic-secret'):
            with recorder.measure('ociVerify'):
                raise RuntimeError('synthetic-secret')
        recorder.add('ociVerify', 2, status='passed')
        value = recorder.snapshot()
        self.assertEqual('failed', value['phases'][0]['status'])
        self.assertNotIn('synthetic-secret', str(value))

    def test_unknown_phase_invalid_size_and_duration_are_rejected(self):
        recorder = TimingRecorder()
        for duration in (-1, True, math.inf, math.nan):
            with self.assertRaises(ValueError):
                recorder.add('sshUpload', duration)
        for size in (-1, True, 1.5):
            with self.assertRaises(ValueError):
                recorder.add('sshUpload', 1, bytes=size)
        with self.assertRaises(ValueError):
            recorder.add('https://private.example/?token=secret', 1)
        self.assertEqual([], recorder.snapshot()['phases'])

    def test_snapshots_are_detached_and_skip_is_not_a_success(self):
        recorder = TimingRecorder()
        recorder.add('imageLoad', 0, status='skipped')
        first = recorder.snapshot()
        first['phases'][0]['status'] = 'passed'
        self.assertEqual('skipped', recorder.snapshot()['phases'][0]['status'])


if __name__ == '__main__':
    unittest.main()
