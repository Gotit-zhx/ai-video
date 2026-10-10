"""Local Poisson compositing. Never generates imagery or modifies outside declared regions."""
import base64,hashlib,io,math
import cv2
import numpy as np
from PIL import Image

def decode(data):
    raw=base64.b64decode(data,validate=True)
    if len(raw)>9_000_000:raise ValueError('image too large')
    with Image.open(io.BytesIO(raw)) as image:
        if image.width*image.height>4_194_304:raise ValueError('compositing supports up to 4 megapixels')
        pixels=np.asarray(image.convert('RGB')).copy()
    return raw,cv2.cvtColor(pixels,cv2.COLOR_RGB2BGR)

def composite(body):
    source,a=decode(body['source']);candidate,b=decode(body['candidate'])
    h,w=a.shape[:2]
    if b.shape[0]!=b.shape[1]:raise ValueError('candidate must retain square reference canvas')
    regions=body['regions']
    if not isinstance(regions,list) or not 1<=len(regions)<=8:raise ValueError('invalid regions')
    mask=np.zeros((h,w),np.uint8);area=0
    for r in regions:
        x,y,bw,bh=[r[k] for k in ['x','y','width','height']]
        if not all(isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) for v in [x,y,bw,bh]):raise ValueError('invalid coordinates')
        if x<0 or y<0 or bw<=0 or bh<=0 or x+bw>1.00000001 or y+bh>1.00000001:raise ValueError('out of bounds')
        area+=bw*bh
        mask[math.floor(y*h):min(h,math.ceil((y+bh)*h)),math.floor(x*w):min(w,math.ceil((x+bw)*w))]=255
    if area>.25:raise ValueError('edit exceeds 25 percent')
    # Invert the same centered square letterbox sent to the generator.
    size=b.shape[0];scale=min(size/w,size/h);ox=(size-w*scale)/2;oy=(size-h*scale)/2
    xs=np.clip(np.floor(ox+(np.arange(w)+.5)*scale).astype(int),0,size-1)
    ys=np.clip(np.floor(oy+(np.arange(h)+.5)*scale).astype(int),0,size-1)
    b=b[ys[:,None],xs[None,:]]
    pad=32
    ap=cv2.copyMakeBorder(a,pad,pad,pad,pad,cv2.BORDER_REFLECT_101)
    bp=cv2.copyMakeBorder(b,pad,pad,pad,pad,cv2.BORDER_REFLECT_101)
    mp=cv2.copyMakeBorder(mask,pad,pad,pad,pad,cv2.BORDER_CONSTANT)
    x,y,bw,bh=cv2.boundingRect(mp)
    if bw<3 or bh<3:raise ValueError('mask too small')
    out=cv2.seamlessClone(bp,ap,mp,(x+bw//2,y+bh//2),cv2.NORMAL_CLONE)[pad:-pad,pad:-pad]
    out[mask==0]=a[mask==0]
    ok,png=cv2.imencode('.png',out)
    if not ok:raise ValueError('encoding failed')
    result=png.tobytes()
    if len(result)>8*1024*1024:raise ValueError('output exceeds archive limit')
    return {'data':base64.b64encode(result).decode(),'sourceSha256':hashlib.sha256(source).hexdigest(),
            'candidateSha256':hashlib.sha256(candidate).hexdigest(),'outputSha256':hashlib.sha256(result).hexdigest(),
            'width':w,'height':h,'method':'LOCAL_POISSON_V1','outsideMaskPreserved':True}
