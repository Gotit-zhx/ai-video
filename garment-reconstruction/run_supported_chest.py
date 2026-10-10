"""Build and validate the user-requested supported chest without a GPU search."""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import uuid

from geometry_v3 import build_mesh
from run_gpu_repair import ROOT, BASE, save
from validate_gpu_repair import validate_candidate


def main():
    output = ROOT / 'output/garment-gpu-repair-runs' / (datetime.now().strftime('%Y%m%d-%H%M%S') + '-support-' + uuid.uuid4().hex[:6])
    output.mkdir(parents=True, exist_ok=False)
    original = json.loads((BASE / 'real-after.json').read_text())
    mesh = build_mesh({**original['params'], 'chest_relaxation': 1, 'chest_support': 1, 'back_drape': 1})
    save(output / 'mesh.json', mesh)
    validation = validate_candidate(output / 'mesh.json', output)
    if (validation['structure']['status'] != 'passed' or validation['collision']['status'] != 'passed'
            or not validation['aperture'].get('actualOpeningsNondegenerate')):
        raise RuntimeError(f'Structural check failed; preview unchanged. See {output}')
    digest = hashlib.sha256((output / 'mesh.json').read_bytes()).hexdigest()
    save(output / 'fit-summary.json', {'status': 'SUPPORTED_CHEST_PREVIEW', 'backend': 'structural_prior',
         'meshSha256': digest, 'chestSupport': mesh['chestSupport'], 'backDrape': mesh['backDrape'], 'qualityChecks': validation,
         'realGarmentAccepted': False, 'params': mesh['params']})
    previous = ROOT / 'output/garment-gpu-repair-latest.json'
    if previous.exists():
        save(output / 'previous-latest.json', json.loads(previous.read_text()))
    prefix = '/' + output.relative_to(ROOT).as_posix()
    manifest = {'schemaVersion': 1, 'jobId': output.name, 'meshUrl': prefix + '/mesh.json',
                'reportUrl': prefix + '/repair-validation.json', 'fitUrl': prefix + '/fit-summary.json',
                'meshSha256': digest, 'backend': 'structural_prior', 'modelLabel': '胸腔支撑 · 后背顺形'}
    temp = previous.with_suffix('.tmp')
    save(temp, manifest)
    temp.replace(previous)
    print(json.dumps({'output': str(output), 'support': mesh['chestSupport'],
                     'structure': validation['structure']['status'], 'collision': validation['collision']['status']}))


if __name__ == '__main__':
    main()
