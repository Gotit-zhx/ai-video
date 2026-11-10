"""Export a bounded read-only host snapshot for the release orchestrator."""
import json
import os
import re
from pathlib import Path
from remote import Remote

VERSION = re.compile(r'v[0-9]+(?:\.[0-9]+){2,3}\Z')


def snapshot(remote):
    value = remote.call('status')
    if (not isinstance(value, dict) or value.get('protocol') != 1
            or not VERSION.fullmatch(str(value.get('version', '')))
            or type(value.get('maintenance')) is not bool
            or not isinstance(value.get('operations'), list)
            or len(value['operations']) > 50):
        raise ValueError('Invalid host status')
    result = {key: value[key] for key in ('protocol', 'version', 'maintenance')}
    result['operations'] = []
    for operation in value['operations']:
        if (not isinstance(operation, dict)
                or not re.fullmatch(r'[a-f0-9]{32}', str(operation.get('id', '')))
                or not re.fullmatch(r'[a-z_]{1,32}', str(operation.get('phase', '')))):
            raise ValueError('Invalid operation status')
        result['operations'].append({key: operation[key] for key in ('id', 'phase')})
    for key in ('sourceCommit', 'controlPlaneRevision', 'operationsInputs',
                'migrationInputs', 'schemaFingerprint'):
        if value.get(key) is None:
            continue
        length = 40 if key.endswith('Commit') or key == 'controlPlaneRevision' else 64
        if not re.fullmatch(r'[a-f0-9]{' + str(length) + '}', str(value[key])):
            raise ValueError('Invalid host fingerprint')
        result[key] = value[key]
    if 'observedAt' in value:
        if type(value['observedAt']) is not int or value['observedAt'] <= 0:
            raise ValueError('Invalid observation timestamp')
        result['observedAt'] = value['observedAt']
    for key in ('releaseSchemas', 'transferProtocols'):
        if key in value:
            versions = value[key]
            if (not isinstance(versions, list) or not versions
                    or any(type(v) is not int or v not in (1, 2) for v in versions)):
                raise ValueError('Invalid protocol capabilities')
            result[key] = sorted(set(versions))
    return result


def main():
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.environ.get('GITHUB_REF') != 'refs/heads/main-tree'):
        raise RuntimeError('Host evidence must come from the protected production workflow')
    with Remote() as remote:
        value = snapshot(remote)
    output = Path('target/release-check'); output.mkdir(parents=True, exist_ok=True)
    (output/'status.json').write_text(json.dumps(value, sort_keys=True) + '\n')
    print(json.dumps({'version': value['version'], 'maintenance': value['maintenance'],
                      'incompleteOperations': len(value['operations'])}))


if __name__ == '__main__':
    main()
