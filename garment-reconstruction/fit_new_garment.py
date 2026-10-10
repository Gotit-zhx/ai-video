"""Independent source-image experiment; no old product targets or output reuse."""
from pathlib import Path
import json, time, hashlib, sys, subprocess
import cv2
import numpy as np
from geometry_v3 import DEFAULT_PARAMS
from geometry_offshoulder import build_mesh
from fit_v2 import fit_stage, ScoreBackendError
from gpu_fit_server import GpuBroker, RemoteGpuScorer, handler_class, ThreadingHTTPServer
from image_fit import render_mask, silhouette_metrics, overlay
from evaluator import evaluate_mesh

ROOT = Path(__file__).resolve().parent.parent
ASSET = ROOT / 'output/garment-etmall-20260911'


def save(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False))


def run(broker, job_id):
    started = time.monotonic()
    out = ASSET / 'runs' / job_id
    out.mkdir(parents=True, exist_ok=False)
    refs = json.loads((ASSET / 'reference-manifest.json').read_text())
    masks = [cv2.imread(str(ASSET / f'{r["id"]}-mask.png'), 0) for r in refs]
    cams = [r['camera'] for r in refs]
    p = {**DEFAULT_PARAMS, 'torso_width': .9, 'body_length': 1.15, 'depth': .32,
         'neck_width': .56, 'neck_depth': .10, 'back_neck_depth': .07,
         'sleeve_length': .21, 'sleeve_drop': .19, 'cuff_width': .34,
         'shoulder_drop': .1, 'hem_flare': -.06, 'body_taper': -.08,
         'chest_depth': 1.15, 'waist_depth': 1.10, 'hem_depth': 1.05,
         'chest_support': .65, 'back_drape': .65, 'shoulder_slope': .45, 'low_cuff_scale':1.35}
    initial = build_mesh(p)
    save(out/'initial.json', initial)
    names = ['torso_width','body_length','shoulder_slope','sleeve_length','sleeve_drop','cuff_width','hem_flare','body_taper','depth','low_cuff_scale']
    bounds = [(.78,1.12),(1.0,1.45),(.2,.72),(.15,.36),(.08,.32),(.30,.44),(-.10,.08),(-.16,.08),(.23,.40),(1.1,1.65)]
    size = 192
    targets = [cv2.resize(m,(size,size),interpolation=cv2.INTER_NEAREST) for m in masks]
    cameras = [{**c,**{k:c[k]*size/512 for k in ('cx','cy','scale')}} for c in cams]
    builder = lambda x: build_mesh(x,'fit')
    backend, reason, scorer = 'webgpu', None, None
    def progress(v): broker.update(phase='fitting',progress=.1+.7*v['evaluations']/1000,backend=backend,**v)
    try:
        scorer=RemoteGpuScorer(broker,builder(p),targets,cameras,size)
        fitted,trace=fit_stage(builder,p,names,bounds,targets,cameras,size,1000,'offshoulder',score_meshes=scorer,on_progress=progress)
    except ScoreBackendError as e:
        reason,backend=str(e),'cpu_fallback'
        fitted,trace=fit_stage(builder,p,names,bounds,targets,cameras,size,1000,'offshoulder',on_progress=progress)
    save(out/'trace.json',trace)
    mesh=build_mesh(fitted)
    structure=evaluate_mesh(mesh)
    if not structure['implementedStructuralChecksPassed']:
        raise ValueError('Structure rejected: '+str(structure['failedChecks']))
    save(out/'mesh.json',mesh)
    save(out/'structure.json',structure)
    views=[]
    for ref,target,camera in zip(refs,masks,cams):
        projection=render_mask(mesh,camera,512)
        metrics=silhouette_metrics(target,projection)
        cv2.imwrite(str(out/f'{ref["id"]}-overlay.png'),overlay(cv2.imread(str(ASSET/f'{ref["id"]}.png')),target,projection))
        views.append({'id':ref['id'],'metrics':metrics,'camera':camera})
    for checker in ['collision_check.py','aperture_check.py']:
        subprocess.run([sys.executable,str(Path(__file__).with_name(checker)),str(out/'mesh.json'),'--output',str(out/(checker[:-3]+'.json'))],capture_output=True,check=False)
    summary={'jobId':job_id,'backend':backend,'fallbackReason':reason,'seconds':time.monotonic()-started,
             'evaluations':trace['evaluations'],'gpuEvaluations':len(scorer.measurements) if scorer else 0,
             'initialLoss':trace['initial_loss'],'bestLoss':trace['best_loss'],'views':views,'params':fitted,
             'meshSha256':hashlib.sha256((out/'mesh.json').read_bytes()).hexdigest(),'realGarmentAccepted':False,
             'referenceType':'AI-inferred turnaround from supplied photograph; hidden surfaces unverified'}
    save(out/'summary.json',summary)
    save(ASSET/'latest.json',{'meshUrl':'/'+str((out/'mesh.json').relative_to(ROOT)),
                             'summaryUrl':'/'+str((out/'summary.json').relative_to(ROOT))})
    return {'resultUrl':'/garment-reconstruction/new-garment.html','backend':backend,'seconds':summary['seconds']}


class NewGarmentBroker(GpuBroker):
    def _run(self,job_id):
        try:
            if (ASSET/'safe-refit-config.json').exists() and json.loads((ASSET/'safe-refit-config.json').read_text()).get('enabled') is True:
                from safe_refit import run_cached
                self.update(phase='bounded_refit',progress=.2)
                result=run_cached()
            else:
                result=run(self,job_id)
            self.update(state='completed',phase='finished',progress=1,**result)
        except Exception as e:
            self.update(state='failed',phase='failed',error=f'{type(e).__name__}: {e}')
        finally:
            with self.condition:
                self.pending=None
                self.condition.notify_all()


if __name__=='__main__':
    server=ThreadingHTTPServer(('127.0.0.1',8769),handler_class(NewGarmentBroker()))
    print('New garment GPU: http://127.0.0.1:8769/garment-reconstruction/gpu-fit.html',flush=True)
    server.serve_forever()
