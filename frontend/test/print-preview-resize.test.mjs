import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const url=code=>`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`;
const load=path=>readFileSync(new URL(path,import.meta.url),'utf8');
const THREE=await import(url(load('../vendor/three/three.module.js')));
const frameUrl=url(load('../vendor/three/surface-decal.js'));
const resizeUrl=url(load('../vendor/three/surface-resize.js').replace("'./surface-decal.js'",JSON.stringify(frameUrl)));
let renderer,frames=new Map(),frameNumber=0;
class Canvas{
 constructor(){this.handlers=new Map();this.style={};this.dataset={};this.captured=new Set();}
 addEventListener(type,handler){this.handlers.set(type,handler);}
 removeEventListener(type){this.handlers.delete(type);}
 getBoundingClientRect(){return{left:20,top:30,width:800,height:800};}
 focus(){}setAttribute(){}remove(){}
 setPointerCapture(id){this.captured.add(id);}hasPointerCapture(id){return this.captured.has(id);}
 releasePointerCapture(id){this.captured.delete(id);this.emit('lostpointercapture',{pointerId:id});}
 emit(type,fields={}){this.handlers.get(type)?.({type,button:0,pointerId:7,preventDefault(){},...fields});}
 toDataURL(){return'data:image/png;base64,iVBORw0KGgo=';}
 getContext(){return{putImageData(){}};}
}
class Renderer{
 constructor(){renderer=this;this.domElement=new Canvas();this.capabilities={getMaxAnisotropy:()=>4};this.size=new THREE.Vector2(800,800);this.ratio=1;this.renders=[];}
 setPixelRatio(value){this.ratio=value;}getPixelRatio(){return this.ratio;}
 setSize(x,y){this.size.set(x,y);}getSize(target){return target.copy(this.size);}
 render(scene,camera){this.scene=scene;this.camera=camera;scene.updateMatrixWorld(true);camera.updateMatrixWorld(true);this.renders.push({size:this.size.x,selection:scene.getObjectByProperty('type','LineLoop')?.parent.visible});}
 setClearColor(){}setRenderTarget(){}readRenderTargetPixels(_target,_x,_y,_w,_h,array){array.fill(0);}dispose(){}
}
class TextureLoader{load(_url,loaded){queueMicrotask(()=>loaded(new THREE.Texture()));}}
globalThis.__resizeTestThree={...THREE,WebGLRenderer:Renderer,TextureLoader};
globalThis.window={devicePixelRatio:1};globalThis.document={createElement:()=>new Canvas()};
globalThis.ImageData=class{constructor(data,width,height){this.data=data;this.width=width;this.height=height;}};
globalThis.ResizeObserver=class{observe(){}disconnect(){}};
globalThis.requestAnimationFrame=callback=>{const id=++frameNumber;frames.set(id,callback);return id;};
globalThis.cancelAnimationFrame=id=>frames.delete(id);
const source=load('../vendor/three/print-preview.js').replace("import * as THREE from './three.module.js';","const THREE=globalThis.__resizeTestThree;")
 .replace("'./surface-decal.js'",JSON.stringify(frameUrl)).replace("'./surface-resize.js'",JSON.stringify(resizeUrl));
const{createPreview}=await import(url(source));
const model={vertices:[[-1,-1,-.1],[1,-1,-.1],[1,1,-.1],[-1,1,-.1],[-1,-1,.1],[1,-1,.1],[1,1,.1],[-1,1,.1]],faces:[[4,5,6],[4,6,7],[0,2,1],[0,3,2],[0,4,7],[0,7,3],[1,2,6],[1,6,5],[0,1,5],[0,5,4],[3,7,6],[3,6,2]]};
const print={id:'flower',artworkId:'a',position:[0,0,.1],normal:[0,0,1],tangent:[1,0,0],width:.6,height:.4,rotation:-Math.PI/18,meshPart:'body'};
const second={...print,id:'second',artworkId:'b',position:[-.65,.6,.1],width:.2,height:.2,rotation:.4};
const clone=p=>({...p,position:[...p.position],normal:[...p.normal],tangent:[...p.tangent]});
const assets=new Map([['a','image:a'],['b','image:b']]);
function setup(){
 frames.clear();const events=[],host={clientWidth:800,clientHeight:800,dataset:{},append(){},dispatchEvent(event){events.push(event);}};
 const api=createPreview(host);api.setModel(model);api.setSurfacePrints([print,second],assets);api.setSelectedPrint(print.id);
 return{api,events,canvas:renderer.domElement,renderer};
}
function flush(){for(const [id,callback]of [...frames]){frames.delete(id);callback();}}
function screen(point,context){
 const group=context.renderer.scene.children.find(object=>object.type==='Group');group.updateMatrixWorld(true);
 const projected=group.localToWorld(new THREE.Vector3(...point).multiplyScalar(1.2)).project(context.renderer.camera);
 return{clientX:20+(projected.x+1)*400,clientY:30+(1-projected.y)*400};
}
function corner(scale,context){
 const c=Math.cos(print.rotation),s=Math.sin(print.rotation),x=print.width/2,y=print.height/2;
 return screen([scale*(x*c-y*s),scale*(x*s+y*c),.1],context);
}
function dimensions(context,id='flower'){
 let signature;context.renderer.scene.traverse(object=>{if(object.userData.surfacePrintId===id)signature=JSON.parse(object.userData.signature);});
 return signature?.slice(4,6);
}
const close=(a,b)=>assert.ok(Math.abs(a-b)<1e-6,`${a} ≠ ${b}`);

test('renderer prioritizes corner resize, renders before committing, and emits exactly once',async()=>{
 const ctx=setup();await Promise.resolve();
 ctx.canvas.emit('pointerdown',corner(1,ctx));ctx.canvas.emit('pointermove',corner(1.5,ctx));flush();
 close(dimensions(ctx)[0],.9);close(dimensions(ctx)[1],.6);assert.deepEqual(dimensions(ctx,'second'),[.2,.2]);
 assert.equal(ctx.events.filter(e=>e.type==='print-surface-change').length,0);
 ctx.canvas.emit('pointerup',corner(1.5,ctx));ctx.canvas.emit('lostpointercapture');
 const changes=ctx.events.filter(e=>e.type==='print-surface-change');assert.equal(changes.length,1);assert.equal(changes[0].detail.reason,'resize');
 assert.deepEqual(changes[0].detail.previous,print);assert.deepEqual(changes[0].detail.print.position,print.position);
 assert.equal(changes[0].detail.id,'flower');assert.equal(changes[0].detail.commit,true);
 ctx.api.dispose();
});

test('renderer cancels pending or rendered resize on pointercancel/lostcapture without a commit',async()=>{
 for(const type of ['pointercancel','lostpointercapture'])for(const renderFirst of [false,true]){
  const ctx=setup();await Promise.resolve();ctx.canvas.emit('pointerdown',corner(1,ctx));ctx.canvas.emit('pointermove',corner(1.7,ctx));if(renderFirst)flush();
  ctx.canvas.emit(type,corner(1.7,ctx));flush();assert.deepEqual(dimensions(ctx),[.6,.4]);assert.deepEqual(dimensions(ctx,'second'),[.2,.2]);
  assert.equal(ctx.events.filter(e=>e.type==='print-surface-change').length,0);ctx.api.dispose();
 }
});

test('transformed garment and camera preserve raw dimensions, and other pointer IDs cannot end a resize',async()=>{
 const ctx=setup();await Promise.resolve();
 for(let i=0;i<4;i++)ctx.canvas.emit('keydown',{key:'ArrowLeft'});ctx.canvas.emit('keydown',{key:'ArrowUp'});ctx.canvas.emit('wheel',{deltaY:90});
 ctx.canvas.emit('pointerdown',corner(1,ctx));ctx.canvas.emit('pointermove',{...corner(1.4,ctx),pointerId:22});flush();assert.deepEqual(dimensions(ctx),[.6,.4]);
 ctx.canvas.emit('pointerup',{...corner(1.4,ctx),pointerId:22});ctx.canvas.emit('pointermove',corner(1.4,ctx));flush();ctx.canvas.emit('pointerup',corner(1.4,ctx));
 close(dimensions(ctx)[0],.84);close(dimensions(ctx)[1],.56);assert.equal(ctx.events.filter(e=>e.type==='print-surface-change').length,1);ctx.api.dispose();
});

test('external replacement cancels stale drag and history snapshots restore dimensions',async()=>{
 const ctx=setup();await Promise.resolve();ctx.canvas.emit('pointerdown',corner(1,ctx));ctx.canvas.emit('pointermove',corner(1.5,ctx));flush();ctx.canvas.emit('pointerup',corner(1.5,ctx));
 const changed=ctx.events.find(e=>e.type==='print-surface-change').detail.print;ctx.api.setSurfacePrints([changed,second],assets);
 ctx.api.setSurfacePrints([print,second],assets);assert.deepEqual(dimensions(ctx),[.6,.4]);ctx.api.setSurfacePrints([changed,second],assets);close(dimensions(ctx)[0],.9);
 ctx.api.setSurfacePrints([print,second],assets);ctx.canvas.emit('pointerdown',corner(1,ctx));ctx.canvas.emit('pointermove',corner(1.5,ctx));
 ctx.api.setSurfacePrints([{...clone(print),width:.5,height:.3},second],assets);flush();ctx.canvas.emit('pointerup',corner(1.5,ctx));assert.deepEqual(dimensions(ctx),[.5,.3]);
 assert.equal(ctx.events.filter(e=>e.type==='print-surface-change').length,1);ctx.api.dispose();
});

test('back-facing selection has no handles, and all export passes omit the selected frame',async()=>{
 const ctx=setup();await Promise.resolve();const line=ctx.renderer.scene.getObjectByProperty('type','LineLoop');assert.equal(line.parent.visible,true);
 ctx.api.update([],new Map(),'BACK');assert.equal(line.parent.visible,false);ctx.api.reset();assert.equal(line.parent.visible,true);
 await ctx.api.exportSurfaceGuides({size:128});
 const exported=ctx.renderer.renders.filter(render=>render.size===128);assert.ok(exported.length>=16);assert.ok(exported.every(render=>render.selection===false));
 assert.equal(line.parent.visible,true);ctx.api.dispose();
});
