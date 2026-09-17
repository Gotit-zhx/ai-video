import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const source=readFileSync(new URL('../vendor/photo-print-preview.js',import.meta.url),'utf8');
const {photoPoint,fitPhotoPrint,photoLocator}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const print=(id='p',side='FRONT')=>({id,artworkId:'a',side,x:.3,y:.25,width:.4,height:.3,rotation:0});
test('locator uses image coordinates and vertical UV orientation without mixing sides',()=>{
 const p=print(),back=print('back','BACK'),data=photoLocator([p,back],'FRONT',100,100);
 assert.deepEqual([...data.visible],['p']);assert.equal(data.data[(30*100+35)*4+2],1);
 assert.equal(data.data[(10*100+35)*4+3],0);
 assert.ok(data.data[(30*100+35)*4+1]>data.data[(50*100+35)*4+1]);
 const backData=photoLocator([p,back],'BACK',100,100);assert.deepEqual([...backData.visible],['back']);assert.equal(backData.data[(30*100+35)*4+2],2);
});
test('rotation is around print center in source pixels, including non-square photographs',()=>{
 const p={...print(),rotation:Math.PI/2};
 const center=photoPoint(p,.5,.4,2);assert.ok(Math.abs(center.u-.5)<1e-10);assert.ok(Math.abs(center.v-.5)<1e-10);
 const above=photoPoint(p,.5,.2,2);assert.ok(Math.abs(above.u-.25)<1e-10);assert.ok(Math.abs(above.v-.5)<1e-10);
 const data=photoLocator([p],'FRONT',200,100);assert.equal(data.visible.size,1);
});
test('clamping keeps rotated and unrotated bounds on the actual photograph',()=>{
 for(const aspect of [.6,1,1.7])for(const rotation of [0,.4,Math.PI/2,2.5]){
  const p=fitPhotoPrint({...print(),x:-.6,y:.95,width:1.7,height:.8,rotation},aspect);
  const c=Math.abs(Math.cos(rotation)),s=Math.abs(Math.sin(rotation)),w=p.width*c+p.height*s/aspect,h=p.width*s*aspect+p.height*c,cx=p.x+p.width/2,cy=p.y+p.height/2;
  assert.ok(p.x>=-1e-10&&p.y>=-1e-10&&p.x+p.width<=1+1e-10&&p.y+p.height<=1+1e-10);
  assert.ok(cx-w/2>=-1e-10&&cy-h/2>=-1e-10&&cx+w/2<=1+1e-10&&cy+h/2<=1+1e-10);
 }
});
test('overlap visibility is measured after topmost slot wins and slot 32 remains exact',()=>{
 const prints=Array.from({length:32},(_,i)=>print('p'+i));const data=photoLocator(prints,'FRONT',100,100);
 assert.deepEqual([...data.visible],['p31']);assert.equal(data.data[(30*100+35)*4+2],32);
 for(let i=0;i<data.data.length;i+=4)if(data.data[i+3])assert.equal(data.data[i+3],255);
});
