"""Fast shared 3D cage fitting against immutable orthographic silhouettes.

Opposite views share one surface: inconsistent targets are reported, never
silently replaced. An invertible row-wise affine cage keeps indexed topology.
"""
from copy import deepcopy
import hashlib, json, time, sys, subprocess, uuid
from pathlib import Path
import cv2
import numpy as np
from scipy.ndimage import gaussian_filter1d
from image_fit import render_mask, silhouette_metrics, overlay
from evaluator import evaluate_mesh


def fair_interior(mesh, iterations=35):
    """Smooth interior cage distortion while holding actual outline and openings."""
    from scipy.sparse import coo_matrix
    out=deepcopy(mesh);v=np.asarray(mesh['vertices'],dtype=float);f=np.asarray(mesh['faces'])
    edges=np.unique(np.sort(np.concatenate([f[:,[0,1]],f[:,[1,2]],f[:,[2,0]]]),axis=1),axis=0)
    rows=np.concatenate([edges[:,0],edges[:,1]]);cols=np.concatenate([edges[:,1],edges[:,0]])
    counts=np.bincount(rows,minlength=len(v))
    average=coo_matrix((1/counts[rows],(rows,cols)),shape=(len(v),len(v))).tocsr()
    pinned=np.zeros(len(v),dtype=bool)
    pinned[[i for loop in mesh['openings'].values() for i in loop]]=True
    from image_fit import project
    for yaw in (0,90):
        camera={'yaw':yaw,'pitch':0,'scale':300,'cx':256,'cy':480}
        mask=render_mask(mesh,camera,512)
        boundary=cv2.morphologyEx(mask,cv2.MORPH_GRADIENT,np.ones((3,3),np.uint8))
        distance=cv2.distanceTransform((boundary==0).astype(np.uint8),cv2.DIST_L2,5)
        xy=np.clip(np.rint(project(v,camera)).astype(int),0,511)
        pinned |= distance[xy[:,1],xy[:,0]]<=1.5
    for _ in range(iterations):
        next_v=v+.45*(average@v-v);next_v[pinned]=v[pinned];v=next_v
    out['vertices']=v.tolist();out['interiorFairing']={'iterations':iterations,'outlineAndOpeningPinnedVertices':int(pinned.sum())}
    return out


def subdivide(mesh):
    """One conforming midpoint refinement; preserve semantics and real loops."""
    out=deepcopy(mesh);vertices=list(out['vertices']);faces=[];midpoints={}
    def mid(a,b):
        key=tuple(sorted((a,b)))
        if key not in midpoints:
            midpoints[key]=len(vertices)
            vertices.append(((np.asarray(vertices[a])+vertices[b])*.5).tolist())
        return midpoints[key]
    for a,b,c in mesh['faces']:
        ab,bc,ca=mid(a,b),mid(b,c),mid(c,a)
        faces.extend([[a,ab,ca],[ab,b,bc],[ca,bc,c],[ab,bc,ca]])
    out['vertices'],out['faces']=vertices,faces
    for name,ids in mesh['semantic_regions'].items():
        members=set(ids)
        out['semantic_regions'][name]=ids+[i for (a,b),i in midpoints.items() if a in members and b in members]
    out['face_regions']={name:[4*i+j for i in ids for j in range(4)] for name,ids in mesh['face_regions'].items()}
    out['openings']={name:[i for a,b in zip(loop,loop[1:]+loop[:1]) for i in (a,midpoints[tuple(sorted((a,b)))])]
                     for name,loop in mesh['openings'].items()}
    return out


def row_profile(mask, camera, sign, ys):
    values=[]
    for row in np.flatnonzero((mask>0).any(axis=1)):
        xs=np.flatnonzero(mask[row]>0)
        lo,hi=sorted([sign*(xs[0]-camera['cx'])/camera['scale'],sign*(xs[-1]-camera['cx'])/camera['scale']])
        values.append(((camera['cy']-row)/camera['scale'],lo,hi))
    a=np.asarray(sorted(values))
    return np.column_stack([np.interp(ys,a[:,0],a[:,i]) for i in (1,2)])


def section_bounds(vertices, faces, ys):
    edges=np.unique(np.sort(np.concatenate([faces[:,[0,1]],faces[:,[1,2]],faces[:,[2,0]]]),axis=1),axis=0)
    a,b=vertices[edges[:,0]],vertices[edges[:,1]]
    result=[]
    for y in ys:
        valid=(np.minimum(a[:,1],b[:,1])<=y+1e-9)&(np.maximum(a[:,1],b[:,1])>=y-1e-9)
        aa,bb=a[valid],b[valid]
        t=np.clip((y-aa[:,1])/np.where(np.abs(bb[:,1]-aa[:,1])>1e-12,bb[:,1]-aa[:,1],1),0,1)
        points=aa+(bb-aa)*t[:,None]
        if not len(points):raise ValueError('Missing mesh cross-section')
        result.append([points[:,0].min(),points[:,0].max(),points[:,2].min(),points[:,2].max()])
    return np.asarray(result)


def fit_cage(mesh, refs, masks, smoothing=1.0):
    out=deepcopy(mesh)
    v=np.asarray(mesh['vertices'],dtype=float)
    heights=[np.ptp(np.where(mask>0)[0])/r['camera']['scale'] for r,mask in zip(refs,masks)]
    height=float(np.median(heights))
    v[:,1]=(v[:,1]-v[:,1].min())*height/np.ptp(v[:,1])
    ys=np.linspace(0,height,513)
    bounds=section_bounds(v,np.asarray(mesh['faces']),ys)
    profiles={r['id']:row_profile(m,r['camera'],-1 if r['id'] in ('back','left') else 1,ys) for r,m in zip(refs,masks)}
    desired=np.column_stack([(profiles['front']+profiles['back'])*.5,(profiles['left']+profiles['right'])*.5])
    desired=gaussian_filter1d(desired,smoothing,axis=0,mode='nearest')
    for axis,start in [(0,0),(2,2)]:
        span=bounds[:,start+1]-bounds[:,start]
        scale=np.clip((desired[:,start+1]-desired[:,start])/np.maximum(span,.025),.4,2.5)
        scale=gaussian_filter1d(scale,8,mode='nearest')
        offset=(desired[:,start+1]+desired[:,start])*.5-scale*(bounds[:,start+1]+bounds[:,start])*.5
        offset=gaussian_filter1d(offset,8,mode='nearest')
        v[:,axis]=v[:,axis]*np.interp(v[:,1],ys,scale)+np.interp(v[:,1],ys,offset)
    out['vertices']=v.tolist()
    out['geometryVersion']='offshoulder/profile-cage-0.1'
    out['limitations'].append('Shared silhouette cage fits external envelopes only; interior folds and true body shape are not reconstructed.')
    return out,{'height':height,'sampleY':ys.tolist(),'sourceBounds':bounds.tolist(),'targetBounds':desired.tolist()}


def opposite_consistency(refs,masks,size=2048):
    aligned={}
    scale=size/2
    for r,m in zip(refs,masks):
        c=r['camera'];sign=-1 if r['id'] in ('back','right') else 1
        a=scale/c['scale']
        matrix=np.asarray([[sign*a,0,size/2-sign*a*c['cx']],[0,a,size*.85-a*c['cy']]])
        aligned[r['id']]=cv2.warpAffine(m,matrix,(size,size),flags=cv2.INTER_NEAREST)>0
    result=[]
    for x,y in [('front','back'),('left','right')]:
        a,b=aligned[x],aligned[y];iou=float((a&b).sum()/(a|b).sum())
        result.append({'views':[x,y],'alignedIoU':iou,'commonSilhouetteMinIoUUpperBound':(1+iou)/2,
                       'reason':'Jaccard distance triangle inequality for opposing orthographic silhouettes',
                       'registration':'existing cameras, shared world grid; raster approximation'})
    return result


def run_profile_job(asset, force=False):
    root=Path(__file__).resolve().parents[2]
    asset=Path(asset).resolve()
    config=json.loads((asset/'profile-config.json').read_text())
    source=(root/config['sourceMeshUrl'].lstrip('/')).resolve()
    if not source.is_relative_to(asset):raise ValueError('Seed mesh must belong to this product')
    refs=json.loads((asset/'reference-manifest.json').read_text())
    masks=[cv2.imread(str(asset/f'{r["id"]}-mask.png'),0) for r in refs]
    start=time.monotonic()
    paths=[source,asset/'reference-manifest.json',Path(__file__),
           *[Path(__file__).with_name(n) for n in ['image_fit.py','evaluator.py','collision_check.py','aperture_check.py']],
           *[asset/f'{r["id"]}-mask.png' for r in refs]]
    inputs={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    key=hashlib.sha256(json.dumps(inputs,sort_keys=True).encode()).hexdigest()
    cache=asset/'profile-cache.json'
    if cache.exists() and not force:
        cached=json.loads(cache.read_text())
        if cached.get('key')==key and all((root/name.lstrip('/')).is_file() and hashlib.sha256((root/name.lstrip('/')).read_bytes()).hexdigest()==digest
                                         for name,digest in cached.get('artifacts',{}).items()) and cached.get('artifacts'):
            return {'resultUrl':'/experiments/garment-reconstruction/new-garment.html','backend':'direct_profile','cacheHit':True,
                    'seconds':time.monotonic()-start,'output':cached['output']}
    mesh,cage=fit_cage(json.loads(source.read_text()),refs,masks)
    mesh=fair_interior(mesh)
    out=asset/'profile-runs'/(time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]);out.mkdir(parents=True,exist_ok=False)
    def save(name,value):(out/name).write_text(json.dumps(value,indent=2,ensure_ascii=False,allow_nan=False))
    save('mesh.json',mesh);save('cage.json',cage)
    views=[]
    for r,m in zip(refs,masks):
        actual=render_mask(mesh,r['camera'],512)
        views.append({'id':r['id'],'camera':r['camera'],'metrics':silhouette_metrics(m,actual)})
        cv2.imwrite(str(out/f'{r["id"]}-overlay.png'),overlay(cv2.imread(str(asset/f'{r["id"]}.png')),m,actual))
    structure=evaluate_mesh(mesh);save('structure.json',structure)
    for checker in ['collision_check.py','aperture_check.py']:
        result=subprocess.run([sys.executable,str(Path(__file__).with_name(checker)),str(out/'mesh.json'),'--output',str(out/(checker[:-3]+'.json'))],capture_output=True,timeout=120)
        if result.returncode not in (0,1):raise ValueError(f'{checker} did not finish correctly')
    collision=json.loads((out/'collision_check.json').read_text())
    aperture=json.loads((out/'aperture_check.json').read_text())
    structurally_usable=structure['implementedStructuralChecksPassed'] and collision.get('scopeStatus')=='passed' and aperture.get('allActualOpeningsNumericallyNondegenerate') is True
    summary={'backend':'direct_profile','seconds':time.monotonic()-start,'evaluations':1,'views':views,
             'referenceConsistency':opposite_consistency(refs,masks),'sourceMesh':str(source),
             'meshSha256':hashlib.sha256((out/'mesh.json').read_bytes()).hexdigest(),'realGarmentAccepted':False,
             'qualityTarget':{'iou':.95,'allViewsPassed':all(v['metrics']['iou']>=.95 for v in views)},
             'structurallyUsable':structurally_usable,'inputSha256':inputs,'computeStrategy':'one direct cage solve + interior fairing + independent checks'}
    save('summary.json',summary)
    if not structurally_usable:raise ValueError(f'Candidate failed structural checks, retained at {out}')
    for name,digest in inputs.items():
        if hashlib.sha256((root/name).read_bytes()).hexdigest()!=digest:raise ValueError('Inputs changed during fitting')
    latest={'meshUrl':'/'+str((out/'mesh.json').relative_to(root)),'summaryUrl':'/'+str((out/'summary.json').relative_to(root))}
    old=asset/'latest.json'
    if old.exists():save('previous-latest.json',json.loads(old.read_text()))
    temp=old.with_suffix('.tmp');temp.write_text(json.dumps(latest));temp.replace(old)
    artifacts={'/'+str(f.relative_to(root)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [out/'mesh.json',out/'summary.json',out/'collision_check.json',out/'aperture_check.json']}
    temp=cache.with_suffix('.tmp');temp.write_text(json.dumps({'key':key,'artifacts':artifacts,'output':str(out)}));temp.replace(cache)
    return {'resultUrl':'/experiments/garment-reconstruction/new-garment.html','output':str(out),'backend':'direct_profile',
            'cacheHit':False,'seconds':time.monotonic()-start,'views':views,'qualityTarget':summary['qualityTarget']}


if __name__=='__main__':
    root=Path(__file__).resolve().parents[2]
    print(json.dumps(run_profile_job(root/'output/garment-etmall-20260911'),ensure_ascii=False))
