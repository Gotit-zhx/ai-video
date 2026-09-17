"""Reference-specific overlay localization; reference matches are never editing masks."""
from pathlib import Path
import json
import cv2
import numpy as np

ROOT = Path(__file__).with_name('references')

def feature(gray):
    gray = gray.astype(np.float32)
    return gray - cv2.GaussianBlur(gray, (0, 0), 5)

def detect_reference(rgb):
    h,w = rgb.shape[:2]
    gray = cv2.cvtColor(rgb,cv2.COLOR_RGB2GRAY)
    search=feature(gray)
    result=[]
    for specfile in sorted(ROOT.glob('*.json')):
        spec=json.loads(specfile.read_text())
        if 'fragments' not in spec: continue
        best=None
        for scale in [.65,.8,1.0,1.2,1.45]:
            factor=w/spec['width']*scale
            matches=[]
            for fragment in spec['fragments']:
                template=cv2.imread(str(ROOT/fragment['file']),cv2.IMREAD_GRAYSCALE)
                tw,th=round(template.shape[1]*factor),round(template.shape[0]*factor)
                if tw<16 or th<12 or tw>w or th>h: break
                template=feature(cv2.resize(template,(tw,th)))
                scores=cv2.matchTemplate(search,template,cv2.TM_CCOEFF_NORMED)
                _,score,_,location=cv2.minMaxLoc(scores)
                matches.append((score,location,fragment))
            if len(matches)!=len(spec['fragments']):continue
            origins=[(point[0]-f['x']*factor,point[1]-f['y']*factor) for score,point,f in matches]
            spread=max(np.linalg.norm(np.array(a)-np.array(b)) for a in origins for b in origins)
            score=min(m[0] for m in matches)
            if score < .68 or spread > max(5,12*factor):continue
            ox,oy=np.mean(origins,axis=0)
            x,y,bw,bh=spec['overlayBox']
            left=max(0,ox+x*factor);top=max(0,oy+y*factor)
            right=min(w,ox+(x+bw)*factor);bottom=min(h,oy+(y+bh)*factor)
            if right<=left or bottom<=top:continue
            box={'x':left/w,'y':top/h,'width':(right-left)/w,'height':(bottom-top)/h,
                 'confidence':float(score),'label':'known_overlay_candidate',
                 'evidence':{'reference':spec['id'],'fragmentScores':[float(m[0]) for m in matches],
                             'alignmentErrorPx':float(spread),'scale':scale,
                             'localization':'reference envelope; not a pixel mask'}}
            if best is None or score>best['confidence']:best=box
        if best:result.append(best)
    return result
