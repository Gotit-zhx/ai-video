import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const code=readFileSync(new URL('../vendor/three/surface-decal.js',import.meta.url),'utf8');
const{surfaceFrame,prepareSurfaceMesh,buildSurfaceDecal}=await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
const base={id:'one',artworkId:'hash',position:[0,0,0],normal:[0,0,1],tangent:[1,0,0],width:.8,height:.4,rotation:0,meshPart:'outer'};
const planes=prepareSurfaceMesh([[-1,-1,0],[1,-1,0],[1,1,0],[-1,1,0],[-1,-1,-.025],[1,-1,-.025],[1,1,-.025],[-1,1,-.025]],
 [[0,1,2],[0,2,3],[4,6,5],[4,7,6]],[{name:'outer',start:0,count:2},{name:'inner',start:2,count:2}]);

test('actual triangles are clipped to the print rectangle with bounded UV and no opposite-layer projection',()=>{
 const patch=buildSurfaceDecal(planes,base,{offset:0});
 assert.ok(patch.positions.length>0);assert.ok(patch.sourceFaces.every(i=>i<2));
 for(let i=0;i<patch.positions.length;i+=3){assert.ok(Math.abs(patch.positions[i])<=.400001);assert.ok(Math.abs(patch.positions[i+1])<=.200001);assert.equal(patch.positions[i+2],0);}
 assert.ok(patch.uv.every(v=>v>=-1e-8&&v<=1+1e-8));assert.ok(Math.abs(patch.coverage-1)<1e-8);
 const back=buildSurfaceDecal(planes,{...base,position:[0,0,-.025],normal:[0,0,-1],tangent:[-1,0,0],meshPart:'inner'},{offset:0});
 assert.ok(back.sourceFaces.length>0&&back.sourceFaces.every(i=>i>=2));
});

test('rotation changes the real surface footprint rather than just rotating the image pixels',()=>{
 const rotated=buildSurfaceDecal(planes,{...base,rotation:Math.PI/2},{offset:0});
 const xs=rotated.positions.filter((_,i)=>i%3===0),ys=rotated.positions.filter((_,i)=>i%3===1);
 assert.ok(Math.abs(Math.max(...xs)-.2)<1e-8);assert.ok(Math.abs(Math.max(...ys)-.4)<1e-8);
});

test('a nearby disconnected cloth patch cannot inherit an anchor from another patch',()=>{
 const mesh=prepareSurfaceMesh([[-.4,-.3,0],[-.05,-.3,0],[-.05,.3,0],[-.4,.3,0],[.05,-.3,0],[.4,-.3,0],[.4,.3,0],[.05,.3,0]],[[0,1,2],[0,2,3],[4,5,6],[4,6,7]]);
 const decal=buildSurfaceDecal(mesh,{...base,position:[-.2,0,0],width:1.2,height:.4,meshPart:'body'},{offset:0});
 assert.ok(decal.sourceFaces.every(i=>i<2));assert.ok(decal.positions.filter((_,i)=>i%3===0).every(x=>x<0));
});

test('an arbitrary side anchor conforms to curved source triangles',()=>{
 const vertices=[],faces=[],count=64;
 for(let j=0;j<=count;j++){const angle=j/count*Math.PI*2;vertices.push([Math.sin(angle),-.5,Math.cos(angle)],[Math.sin(angle),.5,Math.cos(angle)]);}
 for(let j=0;j<count;j++){const a=j*2,b=a+2;faces.push([a,b,a+1],[b,b+1,a+1]);}
 const mesh=prepareSurfaceMesh(vertices,faces),print={...base,meshPart:'body',position:[1,0,0],normal:[1,0,0],tangent:[0,0,-1],width:.7};
 const patch=buildSurfaceDecal(mesh,print,{offset:0});assert.ok(patch.positions.length>0);
 for(let i=0;i<patch.positions.length;i+=3){const[x,,z]=patch.positions.slice(i,i+3);assert.ok(x>.8);assert.ok(Math.abs(Math.hypot(x,z)-1)<.002);}
});

test('invalid or parallel surface axes are rejected instead of producing non-finite geometry',()=>{
 assert.throws(()=>surfaceFrame({...base,tangent:[0,0,1]}));assert.throws(()=>surfaceFrame({...base,width:0}));
 assert.throws(()=>surfaceFrame({...base,position:[NaN,0,0]}));
});
