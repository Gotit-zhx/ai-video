import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const moduleAt = async path => import('data:text/javascript;base64,' + Buffer.from(readFileSync(new URL(path, import.meta.url), 'utf8')).toString('base64'));
const { buildCapPanels } = await moduleAt('../vendor/three/garment-panels.js');
const { maskBounds, garmentStructureEvidence, validateMesh, scorePanelModel } = await moduleAt('../vendor/three/garment-coarse.js');
function polygon(points, side) {
  const width = 192, height = 192, mask = new Uint8Array(width * height);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    let inside = false;
    for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
      const a = points[i], b = points[j];
      if ((a[1] > y) !== (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside;
    }
    mask[y * width + x] = Number(inside);
  }
  return {mask, width, height, side, id:side, bounds:maskBounds(mask, width, height)};
}
const outline = [[77,20],[96,25],[115,20],[134,30],[151,57],[179,107],[150,123],[145,121],[150,162],[130,180],[62,180],[42,162],[47,121],[42,123],[13,107],[41,57],[58,30]];
function references(upperSleeve = false) {
  const side = [[94,20],[104,20],[upperSleeve ? 126 : 114,65],[123,100],[126,125],[130,180],[62,180],[66,125],[69,100],[upperSleeve ? 66 : 78,65]];
  return [polygon(outline,'FRONT'),polygon(outline.map(([x,y])=>[191-x,y]),'BACK'),polygon(side,'LEFT'),polygon(side.map(([x,y])=>[191-x,y]),'RIGHT')];
}
const refs = references(), model = await buildCapPanels(refs,{construction:'round-neck'});
function openingArea(ids) {
  const n=[0,0,0]; for(let i=0;i<ids.length;i++) {const a=model.vertices[ids[i]],b=model.vertices[ids[(i+1)%ids.length]];
    for(let k=0;k<3;k++)n[k]+=(a[(k+1)%3]-b[(k+1)%3])*(a[(k+2)%3]+b[(k+2)%3]);}
  return Math.hypot(...n)/2;
}
test('symmetric dropped sleeves keep a round collar and four actual noncollapsed openings', async () => {
  assert.equal(garmentStructureEvidence(refs).family,'SYMMETRIC_UPPER_OUTLINE');
  const checks=validateMesh(model.vertices,model.faces,model.openings);
  assert.equal(checks.topology,true);assert.equal(checks.openings,true);assert.equal(checks.degenerateFaces,0);
  for(const ids of Object.values(model.openings))assert.ok(openingArea(ids)>.012*model.params.body_length**2);
  const neck=model.openings.neck.map(i=>model.vertices[i]);
  assert.ok(Math.max(...neck.map(p=>p[0]))-Math.min(...neck.map(p=>p[0]))<model.params.torso_width*.7);
  assert.ok(model.faces.length<=30000);assert.ok(model.vertices.length<=15000);
  const scored=await scorePanelModel(model,refs);
  assert.equal(scored.quality.seedFamily,'image-defined-round-neck-panels');
  assert.ok(scored.quality.views.find(v=>v.side==='FRONT').iou>.97);
  assert.equal(scored.quality.targetIoU,.95);
});
test('upper side sleeve occlusion cannot inflate or dent the reconstructed torso', async () => {
  const widened=await buildCapPanels(references(true),{construction:'round-neck'});
  assert.deepEqual(widened.panelEvidence.depthSupport,model.panelEvidence.depthSupport);
  assert.ok(widened.vertices.every((v,i)=>v.every((x,k)=>x===model.vertices[i][k])));
  assert.equal(model.panelEvidence.depthSupport.upperSilhouetteIgnored,true);
});
test('a symmetric sleeveless silhouette is not silently fitted as a short-sleeve shirt', async () => {
  const tank=polygon([[72,20],[83,35],[108,35],[121,20],[127,74],[142,96],[145,180],[47,180],[50,96],[65,74]],'FRONT');
  await assert.rejects(buildCapPanels([tank],{construction:'symmetric-top'}),/袖口|无袖|开口/);
});
test('an unconfirmed neckline retains its input edge rather than being recut as a round neck', async () => {
  const unconfirmed=await buildCapPanels(refs,{construction:'symmetric-top'});
  assert.ok(unconfirmed.geometryVersion.includes('symmetric-top'));
  assert.deepEqual(unconfirmed.faces,model.faces);
  const changed=unconfirmed.vertices.filter((v,i)=>v[1]!==model.vertices[i][1]);
  assert.ok(changed.length>0,'only the explicitly confirmed round neckline receives the front collar cut');
});
test('the thickness solve converges and neither front nor back panel folds in projection', () => {
  assert.equal(model.panelEvidence.fieldMethod,'positive-squared-thickness-screened-membrane');
  assert.ok(model.panelEvidence.solve.every(s=>s.converged&&s.residual<model.params.body_length**2*1e-8));
  model.faces.forEach((face,i)=>{
    const [a,b,c]=face.map(j=>model.vertices[j]);
    const signed=(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);
    assert.ok((i%2===0?1:-1)*signed>0,'each physical sheet must retain its orientation');
  });
});
test('a nearly level hem receives a common boundary band without raising the side seams or folding the original sheets', async () => {
  const fixture=JSON.parse(readFileSync(new URL('./fixtures/garment-straight-hem-mask.json',import.meta.url)));
  const mask=new Uint8Array(fixture.width*fixture.height);fixture.rows.forEach((runs,y)=>{for(let i=0;i<runs.length;i+=2)for(let x=runs[i];x<runs[i+1];x++)mask[y*fixture.width+x]=1;});
  const front={width:fixture.width,height:fixture.height,mask,side:'FRONT',id:'near-level-hem',bounds:maskBounds(mask,fixture.width,fixture.height)};
  const levelRefs=[front,...refs.slice(2)];
  const straight=await buildCapPanels(levelRefs,{construction:'symmetric-top'});
  assert.equal(straight.panelEvidence.levelHem.method,'near-level-reference-common-opening');
  assert.equal(straight.panelEvidence.levelHem.construction,'boundary-extension-with-original-panels-preserved');
  assert.ok(straight.panelEvidence.levelHem.maximumAdjustment>0);
  const heights=straight.openings.hem.map(i=>straight.vertices[i][1]);
  assert.ok(Math.max(...heights)-Math.min(...heights)<1e-7,'both sheets and both side seams must share one hem height');
  assert.equal(straight.panelEvidence.structureChecks.sheetOrientation,true,'the original sheets must keep their orientation');
  assert.equal(validateMesh(straight.vertices,straight.faces,straight.openings).topology,true);
  assert.equal(model.panelEvidence.levelHem,undefined,'an intentionally sloped hem must keep its evidence instead of being flattened');
});
test('the actual app entry selects protected symmetric panels without requiring an offline construction option', async () => {
  const {buildCoarseModel}=await import('../vendor/three/garment-coarse.js');
  const globals=['fetch','location','createImageBitmap','OffscreenCanvas'],saved=globals.map(key=>Object.getOwnPropertyDescriptor(globalThis,key));
  const requests=[];
  try{
    globalThis.location={href:'http://localhost/',origin:'http://localhost'};
    globalThis.fetch=async url=>{requests.push(url);const side=new URL(url).pathname.slice(1),r=refs.find(r=>r.side===side);
      assert.ok(r,'the symmetric route must not fetch the legacy offshoulder seed');return{ok:true,blob:async()=>r};};
    globalThis.createImageBitmap=async r=>({...r,close(){}});
    globalThis.OffscreenCanvas=class{constructor(width,height){this.width=width;this.height=height;}getContext(){let source;const{width,height}=this;return{drawImage(bitmap){source=bitmap;},getImageData(){
      const data=new Uint8ClampedArray(width*height*4);for(let y=0;y<height;y++)for(let x=0;x<width;x++){
        const inside=source.mask[Math.floor(y/height*source.height)*source.width+Math.floor(x/width*source.width)];data.set(inside?[220,210,190,255]:[255,255,255,255],(y*width+x)*4);
      }return{width,height,data};}};}};
    const result=await buildCoarseModel(Object.fromEntries(refs.map(r=>[r.side,{id:r.side,url:`http://localhost/${r.side}`}])));
    assert.equal(requests.length,4);
    assert.equal(result.quality.seedFamily,'image-defined-symmetric-top-panels');
    assert.equal(result.quality.structureEvidence.family,'SYMMETRIC_UPPER_OUTLINE');
    assert.equal(result.quality.panelEvidence.structureChecks.sheetOrientation,true);
    assert.equal(result.quality.panelEvidence.structureChecks.solverConverged,true);
    assert.equal(result.quality.checks.openings,true);
  }finally{globals.forEach((key,i)=>saved[i]?Object.defineProperty(globalThis,key,saved[i]):delete globalThis[key]);}
});
