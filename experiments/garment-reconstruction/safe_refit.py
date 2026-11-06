"""Bounded smooth cage refit from an immutable seed, never a damaged candidate."""
from copy import deepcopy
from pathlib import Path
import json,time,hashlib,subprocess,sys
import numpy as np
import cv2
from scipy.ndimage import gaussian_filter1d
from scipy.interpolate import PchipInterpolator
from profile_fit import row_profile, section_bounds, opposite_consistency
from image_fit import render_mask,silhouette_metrics,overlay
from evaluator import evaluate_mesh

ROOT=Path(__file__).resolve().parents[2]
ASSET=ROOT/'output/garment-etmall-20260911'
SEED=ASSET/'runs/20260911-165538-e33c9e/mesh.json'


def fit(mesh,refs,masks,strength=1):
    out=deepcopy(mesh);v=np.asarray(mesh['vertices'],float)
    height=1.35;v[:,1]=(v[:,1]-v[:,1].min())*height/np.ptp(v[:,1])
    ys=np.linspace(0,height,257);bounds=section_bounds(v,np.asarray(mesh['faces']),ys)
    profiles={r['id']:row_profile(m,r['camera'],-1 if r['id'] in ('back','left') else 1,ys) for r,m in zip(refs,masks)}
    desired=np.column_stack([(profiles['front']+profiles['back'])*.5,(profiles['left']+profiles['right'])*.5])
    fields=[]
    for axis,start in [(0,0),(2,2)]:
        scale=np.clip((desired[:,start+1]-desired[:,start])/np.maximum(bounds[:,start+1]-bounds[:,start],.05),.75,1.35)
        offset=np.clip((desired[:,start+1]+desired[:,start]-scale*(bounds[:,start+1]+bounds[:,start]))*.5,-.12,.12)
        # The last axial slice contains a single vertex: never derive scale
        # from that ill-conditioned slice and fold the collar to a point.
        top=np.searchsorted(ys,1.24);bottom=np.searchsorted(ys,.07)
        scale[:bottom]=scale[bottom];offset[:bottom]=offset[bottom]
        scale[top:]=scale[top];offset[top:]=offset[top]
        if axis==2:
            # Four broad body-volume controls, not hundreds of thin axial bands.
            knots=np.array([.1,.5,.9,1.23])
            s=[np.median(scale[np.abs(ys-y)<.09]) for y in knots]
            o=[np.median(offset[np.abs(ys-y)<.09]) for y in knots]
            scale=PchipInterpolator(knots,s)(np.clip(ys,knots[0],knots[-1]))
            offset=PchipInterpolator(knots,o)(np.clip(ys,knots[0],knots[-1]))
        else:
            scale=gaussian_filter1d(scale,5);offset=gaussian_filter1d(offset,5)
        delta=v[:,axis]*(np.interp(v[:,1],ys,scale)-1)+np.interp(v[:,1],ys,offset)
        v[:,axis]+=strength*delta
        fields.append({'axis':axis,'scale':scale.tolist(),'offset':offset.tolist()})
    out['vertices']=v.tolist();out['geometryVersion']='offshoulder/bounded-cage-0.1'
    out['refit']={'source':'immutable original GPU seed','strength':strength,'fields':fields,'sampleY':ys.tolist(),
                  'limits':'positive scale [.75,1.35], translation <= .12; four smooth depth controls; no Laplacian smoothing'}
    return out


def run(strength=1):
    started=time.monotonic();mesh=json.loads(SEED.read_text());refs=json.loads((ASSET/'reference-manifest.json').read_text())
    masks=[cv2.imread(str(ASSET/f'{r["id"]}-mask.png'),0) for r in refs]
    out=ASSET/'safe-runs'/time.strftime('%Y%m%d-%H%M%S');out.mkdir(parents=True,exist_ok=False)
    def save(name,value):(out/name).write_text(json.dumps(value,indent=2,ensure_ascii=False,allow_nan=False))
    fitted=fit(mesh,refs,masks,strength);save('mesh.json',fitted)
    structure=evaluate_mesh(fitted);save('structure.json',structure)
    for checker in ['collision_check.py','aperture_check.py']:
        subprocess.run([sys.executable,str(Path(__file__).with_name(checker)),str(out/'mesh.json'),'--output',str(out/(checker[:-3]+'.json'))],capture_output=True,check=False)
    collision=json.loads((out/'collision_check.json').read_text());aperture=json.loads((out/'aperture_check.json').read_text())
    usable=structure['implementedStructuralChecksPassed'] and collision.get('scopeStatus')=='passed' and aperture.get('allActualOpeningsNumericallyNondegenerate') is True
    views=[]
    for r,m in zip(refs,masks):
        actual=render_mask(fitted,r['camera'],512);metrics=silhouette_metrics(m,actual)
        views.append({'id':r['id'],'camera':r['camera'],'metrics':metrics})
        cv2.imwrite(str(out/f'{r["id"]}-overlay.png'),overlay(cv2.imread(str(ASSET/f'{r["id"]}.png')),m,actual))
    summary={'backend':'bounded_refit','seconds':time.monotonic()-started,'evaluations':1,'views':views,
             'meshSha256':hashlib.sha256((out/'mesh.json').read_bytes()).hexdigest(),'seedSha256':hashlib.sha256(SEED.read_bytes()).hexdigest(),
             'structurallyUsable':usable,'realGarmentAccepted':False,'referenceConsistency':opposite_consistency(refs,masks)}
    save('summary.json',summary)
    print(json.dumps({'path':str(out),'usable':usable,'collision':collision.get('scopeStatus'),'views':views,'seconds':summary['seconds']}))
    return out


def run_cached():
    """Reuse verified artifacts only when seed, evidence and implementation match."""
    started=time.monotonic()
    files=[SEED,ASSET/'reference-manifest.json',Path(__file__),
           *[Path(__file__).with_name(n) for n in ['profile_fit.py','image_fit.py','evaluator.py','collision_check.py','aperture_check.py']],
           *[ASSET/f'{view}-mask.png' for view in ['front','back','left','right']]]
    inputs={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    key=hashlib.sha256(json.dumps(inputs,sort_keys=True).encode()).hexdigest()
    cache=ASSET/'safe-cache.json';hit=False;out=None
    if cache.exists():
        try:
            c=json.loads(cache.read_text())
            if c['key']==key and len(c.get('artifacts',{}))==5 and all((ROOT/f.lstrip('/')).is_file() and hashlib.sha256((ROOT/f.lstrip('/')).read_bytes()).hexdigest()==h for f,h in c['artifacts'].items()):
                out=Path(c['output']);hit=True
        except (ValueError,KeyError,OSError):pass
    if out is None:out=run()
    summary=json.loads((out/'summary.json').read_text())
    if summary.get('structurallyUsable') is not True:raise ValueError('Rejected candidate remains isolated')
    if any(hashlib.sha256((ROOT/p).read_bytes()).hexdigest()!=h for p,h in inputs.items()):raise ValueError('Inputs changed during refit')
    manifest={'meshUrl':'/'+str((out/'mesh.json').relative_to(ROOT)),'summaryUrl':'/'+str((out/'summary.json').relative_to(ROOT))}
    latest=ASSET/'latest.json'
    if not (out/'previous-latest.json').exists() and latest.exists():
        (out/'previous-latest.json').write_bytes(latest.read_bytes())
    tmp=latest.with_suffix('.tmp');tmp.write_text(json.dumps(manifest));tmp.replace(latest)
    artifacts={'/'+str((out/n).relative_to(ROOT)):hashlib.sha256((out/n).read_bytes()).hexdigest()
               for n in ['mesh.json','summary.json','structure.json','collision_check.json','aperture_check.json']}
    cache.write_text(json.dumps({'key':key,'output':str(out),'artifacts':artifacts}))
    return {'resultUrl':'/experiments/garment-reconstruction/new-garment.html','backend':'bounded_refit','cacheHit':hit,
            'seconds':time.monotonic()-started,'output':str(out)}


if __name__=='__main__':
    if '--publish' in sys.argv:print(json.dumps(run_cached()))
    else:run(float(sys.argv[1]) if len(sys.argv)>1 else 1)
