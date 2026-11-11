"""Use the same timing contract in local/Actions clients and installed operators."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'ops/heyi-ops'))
from heyi_ops.timing import PHASES, STATUSES, Measurement, TimingRecorder

__all__ = ['PHASES', 'STATUSES', 'Measurement', 'TimingRecorder']
