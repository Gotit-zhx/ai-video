"""Bounded, credential-free elapsed-time summaries shared by CI and the host."""
import math
import threading
import time
from contextlib import contextmanager

PHASES = ('registryResolve', 'registryDownload', 'ociExport', 'sourceDownload',
          'sshUpload', 'serverReceive', 'sourceAndProofVerify', 'ociVerify',
          'imageLoad', 'businessDrain', 'containerStart', 'postVerify')
STATUSES = {'passed', 'failed', 'skipped'}


class Measurement:
    def __init__(self):
        self.bytes = 0

    def add_bytes(self, value):
        if type(value) is not int or value < 0:
            raise ValueError('Invalid byte count')
        self.bytes += value


class TimingRecorder:
    def __init__(self, clock=None):
        self._clock = clock or time.monotonic
        self._phases = {}
        self._lock = threading.Lock()

    def add(self, phase, duration_ms, bytes=0, status='passed'):
        if phase not in PHASES or status not in STATUSES:
            raise ValueError('Unknown timing phase or status')
        if (type(duration_ms) not in (int, float) or not math.isfinite(duration_ms)
                or duration_ms < 0 or type(bytes) is not int or bytes < 0):
            raise ValueError('Invalid timing measurement')
        with self._lock:
            item = self._phases.setdefault(phase, {
                'phase': phase, 'durationMs': 0, 'bytes': 0, 'status': 'skipped', 'count': 0})
            item['durationMs'] += round(duration_ms)
            item['bytes'] += bytes
            item['count'] += 1
            if item['status'] == 'failed' or status == 'failed':
                item['status'] = 'failed'
            elif status == 'passed':
                item['status'] = 'passed'

    @contextmanager
    def measure(self, phase):
        if phase not in PHASES:
            raise ValueError('Unknown timing phase')
        measurement = Measurement()
        start = self._clock()
        status = 'passed'
        try:
            yield measurement
        except BaseException:
            status = 'failed'
            raise
        finally:
            self.add(phase, max(0, (self._clock() - start) * 1000),
                     bytes=measurement.bytes, status=status)

    def snapshot(self):
        with self._lock:
            return {'schemaVersion': 1,
                    'phases': [dict(self._phases[p]) for p in PHASES if p in self._phases]}
