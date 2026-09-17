import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {addGarmentLayers} from '../vendor/three/garment-layered.js';
import {validateMesh} from '../vendor/three/garment-coarse.js';
const seed=JSON.parse(fs.readFileSync(new URL('../vendor/three/garment-seed-offshoulder.json',import.meta.url)));
const base=()=>({vertices:structuredClone(seed.vertices),faces:structuredClone(seed.faces),quality:{views:[{side:'FRONT',camera:{scale:220,cx:192,cy:360,rasterSize:384}}]}});
test('layers are independent real finite surfaces and preserve outer shell and four openings',()=>{
 const model=base(),snapshot=JSON.stringify(model);const layered=addGarmentLayers(model,{detected:true,strapSide:'RIGHT',hemFraction:.05});
 assert.equal(JSON.stringify(model),snapshot);assert.ok(layered.vertices.length>model.vertices.length);assert.deepEqual(layered.faces.slice(0,model.faces.length),model.faces);
 const checks=validateMesh(layered.vertices,layered.faces,seed.openings);
 assert.equal(checks.finite,true);assert.equal(checks.topology,true);assert.equal(checks.openings,true);assert.equal(checks.degenerateFaces,0);
 assert.equal(layered.quality.meshParts.reduce((n,p)=>n+p.count,0),layered.faces.length);
 assert.equal(layered.quality.meshParts[2].name,'strap');assert.ok(Math.min(...layered.vertices.map(v=>v[1]))<Math.min(...model.vertices.map(v=>v[1])));
 assert.ok(layered.vertices.length<=15000&&layered.faces.length<=30000);
});
test('wearer-side, measured strap placement and width affect geometry without swapping physical sides',()=>{
 const model=base(),right=addGarmentLayers(model,{detected:true,strapSide:'RIGHT'}),left=addGarmentLayers(model,{detected:true,strapSide:'LEFT'});
 const first=shape=>Math.min(...shape.faces.slice(shape.quality.meshParts[2].start).flat());
 const meanX=shape=>shape.vertices.slice(first(shape)).reduce((n,v)=>n+v[0],0)/(shape.vertices.length-first(shape));
 assert.ok(meanX(right)<0);assert.ok(meanX(left)>0);
 const measured=addGarmentLayers(model,{detected:true,strapSide:'RIGHT',strapBox:{left:.27,top:.15,width:.055,height:.14}});
 assert.equal(measured.quality.layeredGarment.placementFromSource,true);
 assert.notDeepEqual(measured.vertices,right.vertices);
 assert.ok(measured.quality.layeredGarment.strapApex<Math.max(...model.vertices.map(v=>v[1])));
});
test('unrecognized layers keep the input; unsafe material strings never become a material value',()=>{
 const model=base();assert.equal(addGarmentLayers(model,{detected:false}),model);
 const result=addGarmentLayers(model,{detected:true,outerColor:'url(secret)',innerColor:'<script>'});
 assert.match(result.quality.meshParts[0].color,/^#[0-9a-f]{6}$/);assert.match(result.quality.meshParts[1].color,/^#[0-9a-f]{6}$/);
});
test('visible strap follows its measured slope instead of spreading the lean over the hidden arch',()=>{
 const model=base(),spec={detected:true,strapSide:'RIGHT',strapBox:{left:.26,top:.15,width:.10,height:.16},strapWidthFraction:.05,
  strapTopX:.285,strapTopY:.17,strapBottomX:.33,strapBottomY:.29};
 const result=addGarmentLayers(model,spec),part=result.quality.meshParts.find(p=>p.name==='strap');
 const first=Math.min(...result.faces.slice(part.start).flat()),centers=[];
 for(let i=first;i<result.vertices.length;i+=4)centers.push([0,1,2].map(k=>result.vertices.slice(i,i+4).reduce((n,v)=>n+v[k],0)/4));
 const visible=centers.slice(0,25).filter(p=>p[1]>1.12&&p[1]<1.30);
 assert.ok(visible.length>=2);
 const a=visible[0],b=visible.at(-1),actual=(b[0]-a[0])/(b[1]-a[1]),expected=(spec.strapTopX-spec.strapBottomX)/(spec.strapBottomY-spec.strapTopY);
 assert.ok(Math.abs(actual-expected)<1e-4,`visible centerline slope ${actual} vs ${expected}`);
 assert.ok(centers[35][0]>centers[13][0]+.005,'the visible rear segment has its own inner anchor instead of collapsing onto the front ribbon');
 assert.equal(validateMesh(result.vertices,result.faces,seed.openings).degenerateFaces,0);
});
