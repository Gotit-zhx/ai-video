import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const moduleUrl=code=>`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`;
const frameUrl=moduleUrl(readFileSync(new URL('../vendor/three/surface-decal.js',import.meta.url),'utf8'));
const source=readFileSync(new URL('../vendor/three/surface-resize.js',import.meta.url),'utf8').replace("'./surface-decal.js'",JSON.stringify(frameUrl));
const{surfacePrintCorners,hitResizeHandle,beginSurfaceResize,resizeSurfacePrint,finishSurfaceResize}=await import(moduleUrl(source));
const base={id:'selected',artworkId:'flower',position:[4,5,6],normal:[0,0,1],tangent:[1,0,0],width:2,height:1,rotation:0,meshPart:'body'};
const close=(actual,expected)=>assert.ok(Math.abs(actual-expected)<1e-9,`${actual} ≠ ${expected}`);
const session=(overrides={})=>beginSurfaceResize({print:base,corner:1,garmentHeight:10,startPoint:[5,5.5,6],startScreen:[180,60],screenCenter:[100,100],screenCorner:[180,60],...overrides});

test('all four corners include local print rotation and arbitrary surface orientation',()=>{
 assert.deepEqual(surfacePrintCorners(base),[[3,5.5,6],[5,5.5,6],[5,4.5,6],[3,4.5,6]]);
 const rotated=surfacePrintCorners({...base,rotation:Math.PI/2});close(rotated[1][0],3.5);close(rotated[1][1],6);close(rotated[1][2],6);
 const side=surfacePrintCorners({...base,normal:[1,0,0],tangent:[0,0,-1]});assert.deepEqual(side[1],[4,5.5,5]);
});

test('handle hit tests select only visible corners in a fixed screen-pixel radius',()=>{
 const handles=[{screen:[20,20],visible:true},{screen:[100,50],visible:false},{screen:[90,90],visible:true}];
 assert.equal(hitResizeHandle([31,20],handles),0);assert.equal(hitResizeHandle([33,20],handles),null);
 assert.equal(hitResizeHandle([100,50],handles),null);assert.equal(hitResizeHandle([94,96],handles),2);
 assert.equal(hitResizeHandle([NaN,1],handles),null);
});

test('dragging each corner scales proportionally about the unchanged model-space center',()=>{
 for(let corner=0;corner<4;corner++){
  const start=surfacePrintCorners(base)[corner],target=start.map((v,i)=>base.position[i]+(v-base.position[i])*1.7);
  const next=resizeSurfacePrint(session({corner,startPoint:start}),{surfacePoint:target});
  close(next.width,3.4);close(next.height,1.7);
  for(const key of Object.keys(base).filter(k=>!['width','height'].includes(k)))assert.deepEqual(next[key],base[key]);
 }
 assert.deepEqual(base.position,[4,5,6]);assert.equal(base.width,2);
});

test('grabbing off the corner does not jump, and edge-on dragging has a stable screen fallback',()=>{
 const start=[5.04,5.55,6],plane=session({startPoint:start});
 close(resizeSurfacePrint(plane,{surfacePoint:start}).width,2);
 close(resizeSurfacePrint(plane,{surfacePoint:[5.54,5.8,6]}).width,3);
 const edge=session({startPoint:null});
 close(resizeSurfacePrint(edge,{screenPoint:[220,40]}).width,3);
 assert.equal(resizeSurfacePrint(plane,{screenPoint:[220,40]}),null);
});

test('both dimensions stay within 1%–150% of garment height without changing aspect ratio',()=>{
 const tiny=resizeSurfacePrint(session(),{surfacePoint:[-500,-500,6]});close(tiny.width,.2);close(tiny.height,.1);
 const large=resizeSurfacePrint(session(),{surfacePoint:[500,500,6]});close(large.width,15);close(large.height,7.5);
 const tall={...base,width:.5,height:5};
 const resize=session({print:tall,startPoint:surfacePrintCorners(tall)[1]});
 const max=resizeSurfacePrint(resize,{surfacePoint:[500,500,6]});close(max.width,1.5);close(max.height,15);
});

test('pointerup commits once with an independent previous value; cancel and lost capture only restore',()=>{
 const s=session(),next=resizeSurfacePrint(s,{surfacePoint:[5.5,5.75,6]});
 const ended=finishSurfaceResize(s,next,'pointerup');assert.equal(ended.detail.reason,'resize');assert.equal(ended.detail.commit,true);assert.equal(ended.detail.id,base.id);
 assert.deepEqual(ended.detail.previous,base);close(ended.detail.print.width,3);
 ended.detail.print.position[0]=99;assert.equal(ended.detail.previous.position[0],4);assert.equal(base.position[0],4);
 assert.equal(finishSurfaceResize(s,next,'lostpointercapture'),null);assert.equal(resizeSurfacePrint(s,{surfacePoint:[6,6,6]}),null);
 for(const type of ['pointercancel','lostpointercapture']){const canceled=finishSurfaceResize(session(),next,type);assert.deepEqual(canceled.print,base);assert.equal(canceled.detail,null);}
 assert.equal(finishSurfaceResize(session(),base,'pointerup').detail,null);
});
