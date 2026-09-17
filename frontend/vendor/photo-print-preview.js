/** Photo-space print placement. Normalized coordinates refer to the original image, never the viewport. */
const SIDES=['FRONT','BACK','LEFT','RIGHT'];
const clamp=(v,min,max)=>Math.max(min,Math.min(max,v));
const assetUrl=id=>/^[a-f0-9]{64}$/.test(id||'')?'/api/v1/commerce-print-templates/base-images/'+id:id;
const clone=p=>({...p});
export function photoPoint(print,x,y,aspect=1){
 const dx=(x-print.x-print.width/2)*aspect,dy=y-print.y-print.height/2,c=Math.cos(print.rotation||0),s=Math.sin(print.rotation||0);
 return {u:(dx*c+dy*s)/(print.width*aspect)+.5,v:(-dx*s+dy*c)/print.height+.5};
}
export function fitPhotoPrint(print,aspect=1){
 const p={...print},c=Math.abs(Math.cos(p.rotation||0)),s=Math.abs(Math.sin(p.rotation||0));
 p.width=clamp(p.width,.01,1);p.height=clamp(p.height,.01,1);
 let w=p.width*c+p.height*s/aspect,h=p.width*s*aspect+p.height*c;
 const shrink=Math.min(1,1/w,1/h);p.width*=shrink;p.height*=shrink;w*=shrink;h*=shrink;
 const hx=Math.max(w,p.width)/2,hy=Math.max(h,p.height)/2;
 const cx=clamp(p.x+print.width/2,hx,1-hx),cy=clamp(p.y+print.height/2,hy,1-hy);
 p.x=cx-p.width/2;p.y=cy-p.height/2;return p;
}
/** Exact RGBA8 locator; slots bypass antialiasing/premultiplied color conversions. */
export function photoLocator(prints,side,width,height){
 const data=new Uint8ClampedArray(width*height*4),visible=new Set(),aspect=width/height;
 prints.forEach((p,index)=>{
  if(p.side!==side)return;
  const c=Math.abs(Math.cos(p.rotation||0)),s=Math.abs(Math.sin(p.rotation||0));
  const halfW=(p.width*c+p.height*s/aspect)/2,halfH=(p.width*s*aspect+p.height*c)/2,cx=p.x+p.width/2,cy=p.y+p.height/2;
  const left=Math.max(0,Math.floor((cx-halfW)*width)),right=Math.min(width-1,Math.ceil((cx+halfW)*width));
  const top=Math.max(0,Math.floor((cy-halfH)*height)),bottom=Math.min(height-1,Math.ceil((cy+halfH)*height));
  for(let y=top;y<=bottom;y++)for(let x=left;x<=right;x++){
   const {u,v}=photoPoint(p,(x+.5)/width,(y+.5)/height,aspect);if(u<0||u>1||v<0||v>1)continue;
   const at=(y*width+x)*4;data[at]=Math.round(u*255);data[at+1]=Math.round((1-v)*255);data[at+2]=index+1;data[at+3]=255;
  }
 });
 for(let i=0;i<data.length;i+=4)if(data[i+3])visible.add(prints[data[i+2]-1].id);
 return {data,visible};
}
export function createPhotoPrintPreview(host){
 const canvas=document.createElement('canvas');canvas.className='photo-print-canvas';canvas.tabIndex=0;
 canvas.setAttribute('aria-label','四面商品图印花编辑：拖动图案移动，拖动角点缩放，拖动圆点旋转');host.append(canvas);
 const context=canvas.getContext('2d');if(!context)throw new Error('当前浏览器不支持图片编辑');
 const cache=new Map();let baseImages={},side='FRONT',prints=[],selected=null,artworkUrls=new Map(),sourceUrl=null,placing=false,disposed=false,revision=0,drag=null,visible=true;
 let width=1,height=1,frame={x:0,y:0,width:1,height:1},currentImage=null,sceneSignature=null;
 function emit(type,detail){host.dispatchEvent(new CustomEvent(type,{detail}));}
 function load(url){
  if(!url)return Promise.resolve(null);url=assetUrl(url);
  if(cache.has(url))return cache.get(url).promise;
  const entry={image:null,promise:null};entry.promise=new Promise((resolve,reject)=>{const img=new Image();img.onload=()=>{entry.image=img;resolve(img);};img.onerror=()=>{cache.delete(url);reject(new Error('图片读取失败，请重试'));};img.src=url;});cache.set(url,entry);return entry.promise;
 }
 function cached(url){return url?cache.get(assetUrl(url))?.image:null;}
 function baseUrl(){return baseImages[side]||sourceUrl;}
 function transformed(ctx,p,rect,action){ctx.save();ctx.translate(rect.x+(p.x+p.width/2)*rect.width,rect.y+(p.y+p.height/2)*rect.height);ctx.rotate(p.rotation||0);action();ctx.restore();}
 function handles(p){const cx=frame.x+(p.x+p.width/2)*frame.width,cy=frame.y+(p.y+p.height/2)*frame.height,c=Math.cos(p.rotation||0),s=Math.sin(p.rotation||0),w=p.width*frame.width/2,h=p.height*frame.height/2;
  const point=(x,y)=>({x:cx+x*c-y*s,y:cy+x*s+y*c});return [...[[-w,-h],[w,-h],[w,h],[-w,h]].map(([x,y])=>({...point(x,y),kind:'resize'})),{...point(0,-h-25),kind:'rotate'}];}
 function draw(){
  if(disposed||!visible)return;
  const ratio=Math.min(globalThis.devicePixelRatio||1,2),rect=host.getBoundingClientRect();width=Math.max(1,rect.width);height=Math.max(1,rect.height);
  if(canvas.width!==Math.round(width*ratio)||canvas.height!==Math.round(height*ratio)){canvas.width=Math.round(width*ratio);canvas.height=Math.round(height*ratio);canvas.style.width=width+'px';canvas.style.height=height+'px';}
  context.setTransform(ratio,0,0,ratio,0,0);context.clearRect(0,0,width,height);
  currentImage=cached(baseUrl());if(!currentImage)return;
  const scale=Math.min(width/currentImage.naturalWidth,height/currentImage.naturalHeight);
  frame={width:currentImage.naturalWidth*scale,height:currentImage.naturalHeight*scale};frame.x=(width-frame.width)/2;frame.y=(height-frame.height)/2;
  context.drawImage(currentImage,frame.x,frame.y,frame.width,frame.height);
  for(const p of prints.filter(p=>p.side===side)){const img=cached(artworkUrls.get(p.artworkId)||assetUrl(p.artworkId));if(!img)continue;
   transformed(context,p,frame,()=>context.drawImage(img,-p.width*frame.width/2,-p.height*frame.height/2,p.width*frame.width,p.height*frame.height));
  }
  const p=prints.find(p=>p.id===selected&&p.side===side);if(p&&!placing){
   context.strokeStyle='#244d3e';context.lineWidth=1.5;
   transformed(context,p,frame,()=>{context.strokeRect(-p.width*frame.width/2,-p.height*frame.height/2,p.width*frame.width,p.height*frame.height);context.beginPath();context.moveTo(0,-p.height*frame.height/2);context.lineTo(0,-p.height*frame.height/2-25);context.stroke();});
   for(const handle of handles(p)){context.fillStyle='#fff';context.beginPath();if(handle.kind==='rotate')context.arc(handle.x,handle.y,6,0,Math.PI*2);else context.rect(handle.x-5,handle.y-5,10,10);context.fill();context.stroke();}
  }
  canvas.style.cursor=placing?'crosshair':drag?'grabbing':'default';
 }
 async function renderScene(){const ticket=revision;await Promise.all([load(baseUrl()),...prints.map(p=>load(artworkUrls.get(p.artworkId)||assetUrl(p.artworkId)))]);if(ticket===revision)draw();}
 function stopDrag(restore=true){if(!drag)return;const active=drag;drag=null;if(restore)prints=prints.map(p=>p.id===active.id?active.previous:p);if(canvas.hasPointerCapture?.(active.pointerId))canvas.releasePointerCapture(active.pointerId);draw();}
 const point=e=>{const rect=canvas.getBoundingClientRect();return{x:e.clientX-rect.left,y:e.clientY-rect.top};};
 const normalized=q=>({x:(q.x-frame.x)/frame.width,y:(q.y-frame.y)/frame.height});
 function down(e){
  if(e.button!==0||!currentImage||disposed||drag)return;const q=point(e),n=normalized(q);if(n.x<0||n.x>1||n.y<0||n.y>1)return;e.preventDefault();canvas.focus();
  const p=prints.find(p=>p.id===selected&&p.side===side),handle=!placing&&p?handles(p).find(h=>Math.hypot(q.x-h.x,q.y-h.y)<13):null;
  if(placing){emit('print-photo-pick',{...n,side});return;}
  const hit=handle?p:[...prints].reverse().find(p=>{if(p.side!==side)return false;const uv=photoPoint(p,n.x,n.y,frame.width/frame.height);return uv.u>=0&&uv.u<=1&&uv.v>=0&&uv.v<=1;});
  if(!hit){selected=null;emit('print-photo-pick',{...n,side,existing:true,id:null,printId:null});draw();return;}
  selected=hit.id;emit('print-photo-pick',{...n,side,existing:true,id:hit.id,printId:hit.id});
  drag={id:hit.id,pointerId:e.pointerId,start:q,previous:clone(hit),kind:handle?.kind||'move',changed:false};canvas.setPointerCapture?.(e.pointerId);draw();
 }
 function move(e){if(!drag||e.pointerId!==drag.pointerId)return;e.preventDefault();const q=point(e),d=drag,p={...d.previous},aspect=frame.width/frame.height,cx=frame.x+(p.x+p.width/2)*frame.width,cy=frame.y+(p.y+p.height/2)*frame.height;
  if(d.kind==='move'){p.x+=(q.x-d.start.x)/frame.width;p.y+=(q.y-d.start.y)/frame.height;}
  else if(d.kind==='rotate'){p.rotation=(d.previous.rotation||0)+Math.atan2(q.y-cy,q.x-cx)-Math.atan2(d.start.y-cy,d.start.x-cx);p.rotation=((p.rotation+Math.PI*3)%(Math.PI*2))-Math.PI;}
  else{const c=Math.cos(p.rotation||0),s=Math.sin(p.rotation||0),dx=q.x-cx,dy=q.y-cy;let factor=Math.max(Math.abs(dx*c+dy*s)/(p.width*frame.width/2),Math.abs(-dx*s+dy*c)/(p.height*frame.height/2));factor=clamp(factor,.03,8);const w=p.width*factor,h=p.height*factor;p.x+=(p.width-w)/2;p.y+=(p.height-h)/2;p.width=w;p.height=h;}
  const next=fitPhotoPrint(p,aspect);prints=prints.map(v=>v.id===d.id?next:v);d.changed=JSON.stringify(next)!==JSON.stringify(d.previous);draw();
 }
 function end(e){if(!drag||e.pointerId!==drag.pointerId)return;const d=drag;if(e.type==='pointerup')move(e);const next=prints.find(p=>p.id===d.id);stopDrag(e.type!=='pointerup');if(e.type==='pointerup'&&d.changed){revision++;emit('print-photo-change',{id:d.id,print:clone(next),previous:d.previous,commit:true,reason:d.kind});}}
 canvas.addEventListener('pointerdown',down);canvas.addEventListener('pointermove',move);for(const type of ['pointerup','pointercancel','lostpointercapture'])canvas.addEventListener(type,end);
 const observer=new ResizeObserver(draw);observer.observe(host);
 const png=canvas=>{const data=atob(canvas.toDataURL('image/png').split(',')[1]);return new Blob([Uint8Array.from(data,c=>c.charCodeAt(0))],{type:'image/png'});};
 function makeCanvas(w,h){const c=document.createElement('canvas');c.width=w;c.height=h;return c;}
 async function exportSurfaceGuides({size=1024}={}){
  if(disposed||!Number.isInteger(size)||size<256||size>2048)throw new Error('图片导出尺寸无效');
  if(drag)throw new Error('请结束印花拖动后再保存');
  if(!SIDES.every(s=>baseImages[s]))throw new Error('请先生成完整四面参考图');
  const ticket=revision,items=prints.map(clone),bases={...baseImages},urls=new Map(artworkUrls),views={},visibleIds=new Set();
  const assets=await Promise.all([...SIDES.map(s=>load(bases[s])),...items.map(p=>load(urls.get(p.artworkId)||assetUrl(p.artworkId)))]);
  if(disposed||ticket!==revision)throw new Error('图片或印花已变化，请重新保存');
  for(let sideIndex=0;sideIndex<SIDES.length;sideIndex++){
   const face=SIDES[sideIndex],image=assets[sideIndex],w=image.naturalWidth,h=image.naturalHeight;
   if(w<256||h<256||w>2048||h>2048)throw new Error('四面参考图须为256至2048像素，请重新准备参考图');
   const blank=makeCanvas(w,h),bc=blank.getContext('2d');bc.drawImage(image,0,0,w,h);
   const rendered=makeCanvas(w,h),rc=rendered.getContext('2d');rc.drawImage(blank,0,0);
   items.forEach((p,i)=>{if(p.side===face)transformed(rc,p,{x:0,y:0,width:w,height:h},()=>rc.drawImage(assets[SIDES.length+i],-p.width*w/2,-p.height*h/2,p.width*w,p.height*h));});
   const guide=photoLocator(items,face,w,h);guide.visible.forEach(id=>visibleIds.add(id));
   const uv=makeCanvas(w,h);uv.getContext('2d').putImageData(new ImageData(guide.data,w,h),0,0);
   const mask=makeCanvas(w,h),maskData=new Uint8ClampedArray(guide.data.length);for(let i=0;i<maskData.length;i+=4)if(guide.data[i+3])maskData.fill(255,i,i+4);
   mask.getContext('2d').putImageData(new ImageData(maskData,w,h),0,0);views[face]={image:png(rendered),blank:png(blank),uv:png(uv),mask:png(mask)};
  }
  if(disposed||ticket!==revision)throw new Error('图片或印花已变化，请重新保存');return {views,printIds:items.map(p=>p.id),invisiblePrintIds:items.filter(p=>!visibleIds.has(p.id)).map(p=>p.id)};
 }
 return {
  setScene(scene){const signature=JSON.stringify([scene.baseImages??baseImages,scene.side??side,scene.prints??prints,scene.sourceUrl??sourceUrl]);
   if(signature!==sceneSignature){stopDrag();revision++;sceneSignature=signature;if('baseImages'in scene)baseImages={...scene.baseImages};if('side'in scene)side=scene.side;if('prints'in scene)prints=scene.prints.map(clone);if('sourceUrl'in scene)sourceUrl=scene.sourceUrl;}
   if('selected'in scene)selected=scene.selected;if('artworkUrls'in scene)artworkUrls=new Map(scene.artworkUrls);draw();return renderScene();},
  setBaseImages(value){return this.setScene({baseImages:value});},setSide(value){return this.setScene({side:value});},setPrints(value,urls=artworkUrls){return this.setScene({prints:value,artworkUrls:urls});},
  setSelectedPrint(value){selected=value;draw();},setPlacementMode(value){placing=!!value;draw();},setVisible(value){visible=!!value;if(visible)draw();},exportSurfaceGuides,
  dispose(){disposed=true;stopDrag();observer.disconnect();canvas.remove();cache.clear();}
 };
}
