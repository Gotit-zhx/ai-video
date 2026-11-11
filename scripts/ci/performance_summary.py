"""Report comparable release samples without inventing a performance guarantee."""
import argparse
import json
import math
import re
from pathlib import Path
from timing import PHASES, STATUSES

TARGETS = {'frontend': 300000, 'backend': 600000, 'high-risk': 900000}
CATEGORIES = {'normal', 'platform_incident', 'maintenance', 'manual_conflict'}


def number(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise ValueError('Invalid nonnegative measurement')
    return value


def nearest_rank(values, probability):
    if not values or type(probability) not in (int, float) or not 0 < probability <= 1:
        raise ValueError('Invalid percentile request')
    ordered = sorted(number(value) for value in values)
    return ordered[math.ceil(probability * len(ordered)) - 1]


def distribution(values):
    return {'count': len(values), 'p50': nearest_rank(values, .5) if values else None,
            'p95': nearest_rank(values, .95) if values else None}


def summarize(samples, minimum_samples=10):
    if type(minimum_samples) is not int or minimum_samples < 10:
        raise ValueError('At least ten comparable observations are required')
    groups = {}; seen = set()
    for row in samples:
        if not isinstance(row, dict): raise ValueError('Invalid observation')
        identity = row.get('operationId', '')
        if not re.fullmatch(r'[a-f0-9]{32}', identity) or identity in seen:
            raise ValueError('Missing or duplicate operation identity')
        seen.add(identity)
        key = (row.get('releaseType'), row.get('profile'), row.get('cacheState'), row.get('category', 'normal'))
        if (key[0] not in TARGETS or key[1] not in {'docs', 'module', 'full'}
                or key[2] not in {'warm', 'cold', 'unknown'} or key[3] not in CATEGORIES
                or row.get('result') not in {'accepted', 'failed'}):
            raise ValueError('Unknown observation classification')
        if key[3] != 'normal' and not row.get('categoryEvidence'):
            raise ValueError('Exceptional grouping requires evidence')
        item = groups.setdefault(key, {'attemptCount': 0, 'failedCount': 0,
                                      'accepted': [], 'complete': [], 'uploaded': [], 'phases': {}})
        item['attemptCount'] += 1
        if row['result'] == 'failed':
            item['failedCount'] += 1
        else:
            item['accepted'].append(number(row.get('timeToAcceptedMs')))
        if row.get('flowCompleteMs') is not None:
            elapsed = number(row['flowCompleteMs'])
            if row.get('timeToAcceptedMs') is not None and elapsed < row['timeToAcceptedMs']:
                raise ValueError('Flow completed before acceptance')
            item['complete'].append(elapsed)
        if row.get('uploadedBytes') is not None:
            item['uploaded'].append(number(row['uploadedBytes']))
        phases = row.get('phases', [])
        if not isinstance(phases, list) or len(phases) > len(PHASES):
            raise ValueError('Invalid phase summaries')
        phase_names = set()
        for phase in phases:
            if (not isinstance(phase, dict) or phase.get('phase') not in PHASES
                    or phase.get('status') not in STATUSES or phase['phase'] in phase_names):
                raise ValueError('Invalid phase summary')
            phase_names.add(phase['phase'])
            aggregate = item['phases'].setdefault(phase['phase'], {'duration': [], 'bytes': [], 'failedCount': 0})
            aggregate['duration'].append(number(phase.get('durationMs')))
            aggregate['bytes'].append(number(phase.get('bytes')))
            aggregate['failedCount'] += phase['status'] == 'failed'
    output = []
    for key, item in sorted(groups.items()):
        count = len(item['accepted']); accepted = distribution(item['accepted'])
        if count < minimum_samples:
            status = 'insufficient_samples'
        elif item['failedCount']:
            status = 'has_failures'
        elif key[2:] != ('warm', 'normal'):
            status = 'reported_separately'
        else:
            status = 'target_met' if accepted['p50'] <= TARGETS[key[0]] else 'target_not_met'
        output.append(dict(zip(('releaseType', 'profile', 'cacheState', 'category'), key),
                           sampleCount=count, attemptCount=item['attemptCount'],
                           failedCount=item['failedCount'], status=status,
                           timeToAcceptedMs=accepted, flowCompleteMs=distribution(item['complete']),
                           uploadedBytes=distribution(item['uploaded']),
                           phases=[{'phase': name, 'durationMs': distribution(values['duration']),
                                    'bytes': distribution(values['bytes']), 'failedCount': values['failedCount']}
                                   for name, values in sorted(item['phases'].items())]))
    return {'schemaVersion': 1, 'minimumSamples': minimum_samples, 'groups': output,
            'percentileMethod': 'nearest-rank', 'p95SlaClaimed': False}


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('files', nargs='+', type=Path)
    args = parser.parse_args(); samples = []
    for file in args.files:
        value = json.loads(file.read_text())
        samples.extend(value if isinstance(value, list) else [value])
    print(json.dumps(summarize(samples), sort_keys=True, indent=2))


if __name__ == '__main__':
    main()
