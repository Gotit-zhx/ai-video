import * as THREE from './three.module.js';
import { prepareSurfaceMesh, buildSurfaceDecal, surfaceFrame } from './surface-decal.js';
import { surfacePrintCorners, hitResizeHandle, beginSurfaceResize, resizeSurfacePrint, finishSurfaceResize } from './surface-resize.js';

// +Y is up and +Z is the front. LEFT is the garment's left (+X),
// matching the reference-image camera convention rather than the viewer's left.
const SIDE_ROTATION={FRONT:0,BACK:Math.PI,LEFT:-Math.PI/2,RIGHT:Math.PI/2};
const SIDE_AXES={FRONT:[1,0,0,0,0,1],BACK:[-1,0,0,0,0,-1],LEFT:[0,0,-1,1,0,0],RIGHT:[0,0,1,-1,0,0]};

export function createPreview(host){
 const scene=new THREE.Scene();scene.background=new THREE.Color('#f1f1f1');
 const camera=new THREE.PerspectiveCamera(38,1,.05,100);camera.position.set(0,0,5);
 const renderer=new THREE.WebGLRenderer({antialias:true,alpha:true});renderer.setPixelRatio(Math.min(window.devicePixelRatio||1,2));
 renderer.outputColorSpace=THREE.SRGBColorSpace;host.append(renderer.domElement);
 const canvas=renderer.domElement;canvas.tabIndex=0;canvas.style.cssText='display:block;width:100%;height:100%;touch-action:none;';
 const group=new THREE.Group();scene.add(group);
 scene.add(new THREE.HemisphereLight(0xffffff,0x686868,.9));
 const key=new THREE.DirectionalLight(0xffffff,2.7);key.position.set(-3,4,5);scene.add(key);
 const fill=new THREE.DirectionalLight(0xffffff,.85);fill.position.set(4,1,-4);scene.add(fill);
 const material=new THREE.MeshStandardMaterial({color:0xd4d4d4,roughness:.82,metalness:0,side:THREE.DoubleSide});
 const body=new THREE.Mesh(new THREE.BufferGeometry(),material);group.add(body);
 const wireMaterial=new THREE.LineBasicMaterial({color:0xffffff,transparent:true,opacity:.68,depthWrite:false});
 const wire=new THREE.LineSegments(new THREE.BufferGeometry(),wireMaterial);wire.visible=false;wire.renderOrder=1;group.add(wire);
 const prints=new THREE.Group();group.add(prints);
 const surfacePrints=new THREE.Group();group.add(surfacePrints);
 const anchorMaterial=new THREE.MeshBasicMaterial({color:0xffffff,depthTest:true});
 const anchor=new THREE.Mesh(new THREE.SphereGeometry(.018,16,8),anchorMaterial);anchor.renderOrder=5;anchor.visible=false;group.add(anchor);
 const selection=new THREE.Group();selection.visible=false;group.add(selection);
 const selectionLineMaterial=new THREE.LineBasicMaterial({color:0x252525,depthTest:false,depthWrite:false,toneMapped:false});
 const selectionLine=new THREE.LineLoop(new THREE.BufferGeometry(),selectionLineMaterial);selectionLine.renderOrder=8;selection.add(selectionLine);
 const handleOuterMaterial=new THREE.SpriteMaterial({color:0x222222,depthTest:false,depthWrite:false,toneMapped:false});
 const handleInnerMaterial=new THREE.SpriteMaterial({color:0xffffff,depthTest:false,depthWrite:false,toneMapped:false});
 const selectionHandles=Array.from({length:4},()=>{
  const handle=new THREE.Group(),outer=new THREE.Sprite(handleOuterMaterial),inner=new THREE.Sprite(handleInnerMaterial);
  outer.scale.set(12,12,1);inner.scale.set(8,8,1);outer.renderOrder=9;inner.renderOrder=10;
  handle.add(outer,inner);selection.add(handle);return handle;
 });
 const selectionRay=new THREE.Raycaster();let projectedHandles=[];
 // An unparented mesh keeps projection coordinates stable while the visible model rotates.
 const projectionMesh=new THREE.Mesh(body.geometry,material);projectionMesh.updateMatrixWorld(true);
 const loader=new THREE.TextureLoader(),textures=new Map();
 let disposed=false,active=true,revision=0,sourceModel=null,wireframe=false,neutral=false,drag=null;
 let partMaterials=[],partColors=[];
 let latestAreas=[],latestAssets=new Map(),latestSide='FRONT',bounds=new THREE.Box3();
 let textureError=false,projectionWarnings=[],projectionBins=null;
 let surfaceMesh=null,latestSurfacePrints=[],surfaceAssets=new Map(),surfaceRevision=0,surfaceStateSignature='',modelRevision=0,selectedPrintId=null,placementMode=false,exporting=false,pointerFrame=0,pendingPointer=null;
 const render=()=>{if(!disposed&&active&&host.clientWidth&&host.clientHeight){updateAnchor();renderer.render(scene,camera);}};
 function resize(){
  if(disposed||exporting)return;const w=host.clientWidth,h=host.clientHeight;
  if(!w||!h)return;renderer.setSize(w,h,false);camera.aspect=w/h;camera.updateProjectionMatrix();render();
 }
 const observer=new ResizeObserver(resize);observer.observe(host);
 function notify(){
  host.dataset.previewModel=sourceModel?'source':'fallback';host.dataset.textureError=String(textureError);
  host.dataset.projectionWarning=projectionWarnings.join('；');
  host.dispatchEvent(new CustomEvent('print-preview-status',{detail:{sourceBound:!!sourceModel,projectionWarnings:[...projectionWarnings],textureError}}));
 }
 function clearPrints(){
  for(const mesh of [...prints.children]){mesh.geometry.dispose();mesh.material.dispose();prints.remove(mesh);}
 }
 function clearSurfacePrints(){for(const mesh of [...surfacePrints.children]){mesh.geometry.dispose();mesh.material.dispose();surfacePrints.remove(mesh);}}
 const retainedTextureUrls=()=>new Set([...latestAreas.map(a=>latestAssets.get(a.id)),...latestSurfacePrints.map(p=>surfaceAssets.get(p.artworkId))].filter(Boolean));
 function releaseUnusedTextures(urls){
  for(const [url,entry] of textures){if(!urls.has(url)){textures.delete(url);entry.retired=true;entry.texture?.dispose();}}
 }
 function loadTexture(url){
  if(textures.has(url))return textures.get(url).promise;
  const entry={texture:null,retired:false,promise:null};
  entry.promise=new Promise((resolve,reject)=>loader.load(url,texture=>{
   if(disposed||entry.retired){texture.dispose();resolve(null);return;}
   texture.colorSpace=THREE.SRGBColorSpace;texture.anisotropy=Math.min(4,renderer.capabilities.getMaxAnisotropy());entry.texture=texture;resolve(texture);
  },undefined,error=>{if(textures.get(url)===entry)textures.delete(url);reject(error);}));
  textures.set(url,entry);return entry.promise;
 }
 function fallbackGeometry(){
  const shape=new THREE.Shape(),points=[[-.46,1],[-.9,.78],[-1.2,.25],[-.78,.05],[-.55,.43],[-.56,-1],[.56,-1],[.55,.43],[.78,.05],[1.2,.25],[.9,.78],[.46,1]];
  shape.moveTo(...points[0]);points.slice(1).forEach(p=>shape.lineTo(...p));shape.quadraticCurveTo(0,.57,-.46,1);
  const geometry=new THREE.ExtrudeGeometry(shape,{depth:.15,bevelEnabled:true,bevelSegments:4,steps:1,bevelSize:.055,bevelThickness:.045,curveSegments:24});geometry.translate(0,0,-.075);return geometry;
 }
 function geometryFromModel(model){
  if(!Array.isArray(model.vertices)||!Array.isArray(model.faces)||model.vertices.length<4||model.vertices.length>250000||!model.faces.length||model.faces.length>500000)throw new Error('粗模顶点或面数据无效');
  const positions=new Float32Array(model.vertices.length*3),indices=[];
  model.vertices.forEach((vertex,i)=>{
   if(!Array.isArray(vertex)||vertex.length!==3||!vertex.every(v=>typeof v==='number'&&Number.isFinite(v)))throw new Error('粗模存在无效坐标');
   positions.set(vertex,i*3);
  });
  model.faces.forEach(face=>{
   if(!Array.isArray(face)||face.length!==3||!face.every(i=>Number.isInteger(i)&&i>=0&&i<model.vertices.length)||new Set(face).size!==3)throw new Error('粗模存在无效三角面');
   indices.push(...face);
  });
  const rawParts=model.quality?.meshParts??[];
  if(!Array.isArray(rawParts)||rawParts.length>32)throw new Error('粗模衣片分组数据无效');
  const parts=rawParts.map(part=>{
   if(!part||!Number.isInteger(part.start)||!Number.isInteger(part.count)||part.start<0||part.count<1||part.start+part.count>model.faces.length||typeof part.color!=='string'||!/^#[\da-f]{6}$/i.test(part.color))throw new Error('粗模衣片范围或颜色无效');
   return {start:part.start,count:part.count,color:part.color,name:typeof part.name==='string'?part.name.slice(0,64):''};
  }).sort((a,b)=>a.start-b.start);
  for(let i=1;i<parts.length;i++)if(parts[i].start<parts[i-1].start+parts[i-1].count)throw new Error('粗模衣片分组不能重叠');
  const geometry=new THREE.BufferGeometry();geometry.setAttribute('position',new THREE.BufferAttribute(positions,3));geometry.setIndex(indices);geometry.computeBoundingBox();
  const center=geometry.boundingBox.getCenter(new THREE.Vector3()),size=geometry.boundingBox.getSize(new THREE.Vector3());
  if(size.y<1e-6||size.x<1e-6||size.z<1e-6){geometry.dispose();throw new Error('粗模缺少有效立体尺寸');}
  geometry.userData.sourceNormalization={center:center.clone(),scale:2.4/size.y};
  geometry.userData.meshParts=parts;
  geometry.translate(-center.x,-center.y,-center.z);geometry.scale(2.4/size.y,2.4/size.y,2.4/size.y);geometry.computeVertexNormals();return geometry;
 }
 function viewReference(side){
  const views=sourceModel?.quality?.views;
  return Array.isArray(views)?views.find(v=>String(v.view||v.name||v.side).toUpperCase()===side):(views?.[side]||views?.[side.toLowerCase()]);
 }
 function viewFrame(side){
  const frame=viewReference(side)?.frame;
  if(frame&&['left','top','width','height'].every(k=>Number.isFinite(frame[k]))&&frame.width>0&&frame.height>0)return frame;
  return null;
 }
 function clearProjectionBins(){
  if(projectionBins)for(const grid of projectionBins)for(const mesh of grid.meshes)mesh?.geometry.dispose();
  projectionBins=null;
 }
 function buildProjectionBins(){
  clearProjectionBins();if(!sourceModel)return;
  // Bucket projected triangle bounds once; each surface ray visits a small local
  // subset instead of walking the entire garment on every pointer movement.
  const count=24,position=body.geometry.attributes.position,index=body.geometry.index;
  projectionBins=[0,2].map(axis=>{
   const lo=axis===0?bounds.min.x:bounds.min.z,extent=(axis===0?bounds.max.x:bounds.max.z)-lo;
   const bottom=bounds.min.y,height=bounds.max.y-bottom,lists=Array.from({length:count*count},()=>[]);
   const cell=(v,min,size)=>THREE.MathUtils.clamp(Math.floor((v-min)/size*count),0,count-1);
   for(let i=0;i<index.count;i+=3){
    const a=index.getX(i),b=index.getX(i+1),c=index.getX(i+2);
    const hs=[a,b,c].map(j=>position.array[j*3+axis]),ys=[a,b,c].map(j=>position.getY(j));
    for(let y=cell(Math.min(...ys),bottom,height);y<=cell(Math.max(...ys),bottom,height);y++)for(let x=cell(Math.min(...hs),lo,extent);x<=cell(Math.max(...hs),lo,extent);x++)lists[y*count+x].push(a,b,c);
   }
   const meshes=lists.map(indices=>{
    if(!indices.length)return null;
    const geometry=new THREE.BufferGeometry();geometry.setAttribute('position',position);geometry.setIndex(indices);geometry.boundingSphere=body.geometry.boundingSphere.clone();
    const mesh=new THREE.Mesh(geometry,material);mesh.updateMatrixWorld(true);return mesh;
   });
   return {count,lo,extent,bottom,height,meshes};
  });
 }
 function projectionGeometry(area){
  const axes=SIDE_AXES[area.side]||SIDE_AXES.FRONT;
  const horizontal=new THREE.Vector3(...axes.slice(0,3)),outward=new THREE.Vector3(...axes.slice(3));
  const sideView=area.side==='LEFT'||area.side==='RIGHT';
  const extent=sideView?bounds.max.z-bounds.min.z:bounds.max.x-bounds.min.x;
  const height=bounds.max.y-bounds.min.y,frame=viewFrame(area.side),reference=viewReference(area.side);
  const calibration=reference?.camera,normalization=body.geometry.userData.sourceNormalization;
  const cameraReady=calibration?.projection==='orthographic'&&['scale','cx','cy','rasterSize'].every(k=>Number.isFinite(calibration[k]))&&calibration.scale>0&&calibration.rasterSize>0&&normalization;
  // Match the fitting camera, including square-canvas letterboxing, before
  // normalizing the model. Bbox mapping is only for older saved reconstructions.
  const centerHorizontal=normalization?normalization.center.dot(horizontal):0;
  const toHorizontal=u=>cameraReady?((u*calibration.rasterSize-calibration.cx)/calibration.scale-centerHorizontal)*normalization.scale:frame?((u-frame.left)/frame.width-.5)*extent:(u-.5)*extent;
  const toVertical=v=>cameraReady?((calibration.cy-v*calibration.rasterSize)/calibration.scale-normalization.center.y)*normalization.scale:frame?bounds.max.y-(v-frame.top)/frame.height*height:bounds.max.y-v*height;
  const columns=20,rows=24,positions=[],uv=[],hits=[],indices=[];
  const raycaster=new THREE.Raycaster(),origin=new THREE.Vector3(),direction=outward.clone().negate();
  const rayDistance=Math.max(4,bounds.getSize(new THREE.Vector3()).length()+1);raycaster.far=rayDistance*2;
  const grid=projectionBins?.[sideView?1:0];let hitCount=0;
  for(let y=0;y<=rows;y++)for(let x=0;x<=columns;x++){
   const u=x/columns,v=y/rows;origin.copy(horizontal).multiplyScalar(toHorizontal(area.x+u*area.width));origin.y=toVertical(area.y+v*area.height);origin.addScaledVector(outward,rayDistance);raycaster.set(origin,direction);
   const horizontalPosition=sideView?origin.z:origin.x;
   const column=grid?Math.floor((horizontalPosition-grid.lo)/grid.extent*grid.count):0,row=grid?Math.floor((origin.y-grid.bottom)/grid.height*grid.count):0;
   const bucket=grid?(column>=0&&column<grid.count&&row>=0&&row<grid.count?grid.meshes[row*grid.count+column]:null):projectionMesh;
   const hit=bucket?raycaster.intersectObject(bucket,false)[0]:null;
   const valid=!!hit&&Math.abs(hit.face.normal.dot(outward))>.12;
   hits.push(valid);if(valid){hitCount++;const p=hit.point.clone().addScaledVector(outward,.004);positions.push(p.x,p.y,p.z);}else positions.push(0,0,0);
   uv.push(u,1-v);
  }
  const horizontalStep=Math.abs(toHorizontal(area.x+area.width)-toHorizontal(area.x))/columns,verticalStep=Math.abs(toVertical(area.y+area.height)-toVertical(area.y))/rows;
  const maxEdge=Math.max(.035,Math.hypot(horizontalStep,verticalStep)*5);
  const distance=(a,b)=>Math.hypot(positions[a*3]-positions[b*3],positions[a*3+1]-positions[b*3+1],positions[a*3+2]-positions[b*3+2]);
  function triangle(a,b,c){if(hits[a]&&hits[b]&&hits[c]&&distance(a,b)<maxEdge&&distance(b,c)<maxEdge&&distance(c,a)<maxEdge)indices.push(a,b,c);}
  for(let y=0;y<rows;y++)for(let x=0;x<columns;x++){
   const a=y*(columns+1)+x,b=a+1,c=a+columns+1,d=c+1;triangle(a,c,b);triangle(b,c,d);
  }
  const coverage=hitCount/hits.length;
  if(coverage<.92)projectionWarnings.push(`${({FRONT:'正面',BACK:'背面',LEFT:'左侧',RIGHT:'右侧'})[area.side]||'当前面'}部分印区超出表面，已裁去悬空部分`);
  if(!indices.length)return null;
  const geometry=new THREE.BufferGeometry();geometry.setAttribute('position',new THREE.Float32BufferAttribute(positions,3));geometry.setAttribute('uv',new THREE.Float32BufferAttribute(uv,2));geometry.setIndex(indices);geometry.computeVertexNormals();return geometry;
 }
 function fallbackPrint(area){
  const back=area.side==='BACK',mesh=new THREE.Mesh(new THREE.PlaneGeometry(area.width*2.4,area.height*2.4));
  mesh.position.set((area.x+area.width/2-.5)*2.4*(back?-1:1),(.5-area.y-area.height/2)*2.4,back?-.126:.126);if(back)mesh.rotation.y=Math.PI;
  if(area.side==='LEFT'||area.side==='RIGHT'){const sign=area.side==='LEFT'?1:-1;mesh.position.set(sign*.62,(.5-area.y-area.height/2)*2.4,-sign*(area.x+area.width/2-.5)*.22);mesh.rotation.y=sign*Math.PI/2;mesh.scale.x=.22/2.4;}
  // Mesh creates its own default material; it is replaced by the print material below.
  mesh.material.dispose();return mesh;
 }
 function rebuildPrints(){
  const ticket=++revision;clearPrints();textureError=false;projectionWarnings=sourceModel?['印花位置为粗模预览，仍需按原图核对']:[];
  const available=latestAreas.filter(a=>['x','y','width','height'].every(k=>Number.isFinite(a[k]))&&a.width>0&&a.height>0&&latestAssets.get(a.id));
  releaseUnusedTextures(retainedTextureUrls());
  for(const area of available){
   const geometry=sourceModel?projectionGeometry(area):null;if(sourceModel&&!geometry)continue;
   const mesh=sourceModel?new THREE.Mesh(geometry):fallbackPrint(area);if(sourceModel)mesh.material.dispose();
   mesh.material=new THREE.MeshStandardMaterial({transparent:true,roughness:.88,metalness:0,side:THREE.DoubleSide,depthWrite:false,polygonOffset:true,polygonOffsetFactor:-1,polygonOffsetUnits:-1});
   mesh.visible=false;mesh.renderOrder=2;prints.add(mesh);
   loadTexture(latestAssets.get(area.id)).then(texture=>{
    if(disposed||ticket!==revision||!texture)return;
    mesh.material.map=texture;mesh.material.needsUpdate=true;mesh.visible=true;render();
   }).catch(()=>{if(disposed||ticket!==revision)return;textureError=true;notify();render();});
  }
  projectionWarnings=[...new Set(projectionWarnings)];notify();render();
 }
 function rawToDisplay(values){const p=new THREE.Vector3(...values),n=body.geometry.userData.sourceNormalization;return n?p.sub(n.center).multiplyScalar(n.scale):p;}
 function displayToRaw(p){const n=body.geometry.userData.sourceNormalization;return n?p.clone().divideScalar(n.scale).add(n.center):p.clone();}
 function updateAnchor(){
  const selected=latestSurfacePrints.find(p=>p.id===selectedPrintId);
  anchor.visible=false;selection.visible=false;projectedHandles=[];
  if(!selected||wireframe||exporting||!surfaceMesh)return;
  const rect=canvas.getBoundingClientRect();if(!rect.width||!rect.height)return;
  const frame=surfaceFrame(selected),normal=new THREE.Vector3(...frame.normal);
  const center=rawToDisplay(selected.position).addScaledVector(normal,.008);
  group.updateMatrixWorld(true);camera.updateMatrixWorld(true);
  const worldCenter=group.localToWorld(center.clone()),cameraPosition=camera.getWorldPosition(new THREE.Vector3());
  const worldNormal=normal.clone().applyNormalMatrix(new THREE.Matrix3().getNormalMatrix(group.matrixWorld));
  const towardCamera=cameraPosition.clone().sub(worldCenter).normalize(),ndc=worldCenter.clone().project(camera);
  if(ndc.z< -1||ndc.z>1||worldNormal.dot(towardCamera)<.015)return;
  selectionRay.set(cameraPosition,towardCamera.clone().negate());
  const occluder=selectionRay.intersectObject(body,false)[0],distance=worldCenter.distanceTo(cameraPosition);
  if(occluder&&occluder.distance<distance-.025)return;
  selection.visible=true;
  const corners=surfacePrintCorners(selected).map(p=>rawToDisplay(p).addScaledVector(normal,.008));
  const position=selectionLine.geometry.getAttribute('position');
  if(position){corners.forEach((p,i)=>position.setXYZ(i,p.x,p.y,p.z));position.needsUpdate=true;}
  else selectionLine.geometry.setAttribute('position',new THREE.Float32BufferAttribute(corners.flatMap(p=>p.toArray()),3));
  selectionLine.geometry.computeBoundingSphere();
  projectedHandles=corners.map((p,i)=>{
   const handle=selectionHandles[i];handle.position.copy(p);
   const world=group.localToWorld(p.clone()),screen=world.clone().project(camera),depth=-world.clone().applyMatrix4(camera.matrixWorldInverse).z;
   handle.scale.setScalar(2*Math.max(.05,depth)*Math.tan(camera.fov*Math.PI/360)/rect.height);
   return{screen:[rect.left+(screen.x+1)*rect.width/2,rect.top+(1-screen.y)*rect.height/2],visible:screen.z>=-1&&screen.z<=1};
  });
 }
 function rebuildSurfacePrints(){
  surfaceRevision++;releaseUnusedTextures(retainedTextureUrls());
  if(!surfaceMesh){clearSurfacePrints();updateAnchor();render();return;}
  const remaining=new Map(surfacePrints.children.map(mesh=>[mesh.userData.surfacePrintId,mesh]));
  const scale=body.geometry.userData.sourceNormalization?.scale||1;
  for(const [slot,print] of latestSurfacePrints.entries()){
   const url=surfaceAssets.get(print.artworkId);if(!url)continue;
   const signature=JSON.stringify([modelRevision,print.position,print.normal,print.tangent,print.width,print.height,print.rotation,print.meshPart,url]);
   const previous=remaining.get(print.id);
   if(previous?.userData.signature===signature){previous.userData.printSlot=slot;previous.renderOrder=3+slot/100;remaining.delete(print.id);continue;}
   let patch;try{patch=buildSurfaceDecal(surfaceMesh,print,{offset:.002/scale});}catch(error){host.dispatchEvent(new CustomEvent('print-surface-error',{detail:{id:print.id,message:error.message}}));continue;}
   if(!patch.positions.length)continue;
   const positions=new Float32Array(patch.positions.length);
   for(let i=0;i<patch.positions.length;i+=3)rawToDisplay(patch.positions.slice(i,i+3)).toArray(positions,i);
   const geometry=new THREE.BufferGeometry();geometry.setAttribute('position',new THREE.BufferAttribute(positions,3));
   geometry.setAttribute('uv',new THREE.Float32BufferAttribute(patch.uv,2));geometry.setAttribute('normal',new THREE.Float32BufferAttribute(patch.normals,3));
   const printMaterial=new THREE.MeshStandardMaterial({transparent:true,roughness:.88,metalness:0,side:THREE.DoubleSide,depthWrite:false,polygonOffset:true,polygonOffsetFactor:-2,polygonOffsetUnits:-2});
   const mesh=new THREE.Mesh(geometry,printMaterial);mesh.userData.surfacePrintId=print.id;mesh.userData.signature=signature;mesh.userData.coverage=patch.coverage;mesh.userData.printSlot=slot;mesh.visible=false;mesh.renderOrder=3+slot/100;surfacePrints.add(mesh);
   loadTexture(url).then(texture=>{if(disposed||mesh.parent!==surfacePrints||!texture)return;printMaterial.map=texture;printMaterial.needsUpdate=true;mesh.visible=true;render();})
    .catch(()=>{if(disposed||mesh.parent!==surfacePrints)return;textureError=true;notify();render();});
  }
  for(const mesh of remaining.values()){mesh.geometry.dispose();mesh.material.dispose();surfacePrints.remove(mesh);}
  updateAnchor();render();
 }
 // Positions/sizes use the original model.vertices units; the display's
 // centering and 2.4-unit normalization never enter the persisted placement.
 // Assets are Map<artworkId, URL>. A drag commits once through
 // print-surface-change; the caller owns history and may restore with this API.
 function setSurfacePrints(values,assets){
  if(disposed)return;
  if(!Array.isArray(values)||values.length>32)throw new Error('表面印花最多支持 32 个');
  const ids=new Set();const next=values.map(print=>{
   if(!print||typeof print.id!=='string'||!print.id||ids.has(print.id)||typeof print.artworkId!=='string'||!print.artworkId)throw new Error('表面印花标识无效');
   ids.add(print.id);surfaceFrame(print);
   return{...print,position:[...print.position],normal:[...print.normal],tangent:[...print.tangent],rotation:print.rotation||0};
  });
  const nextAssets=assets instanceof Map?new Map(assets):new Map(Array.isArray(assets)?assets:Object.entries(assets||{}));
  const signature=JSON.stringify(next.map(p=>[p.id,p.artworkId,p.position,p.normal,p.tangent,p.width,p.height,p.rotation,p.meshPart,nextAssets.get(p.artworkId)]));
  if(signature===surfaceStateSignature)return;
  cancelPointerWork();
  surfaceStateSignature=signature;latestSurfacePrints=next;surfaceAssets=nextAssets;
  rebuildSurfacePrints();
 }
 function setSelectedPrint(id){selectedPrintId=id||null;updateAnchor();render();}
 function setPlacementMode(value){placementMode=!!value;canvas.style.cursor=placementMode?'crosshair':'grab';canvas.dataset.placementMode=String(placementMode);}
 function setModel(model){
  if(disposed)return null;
  // Validate the whole replacement before disposing the accepted model.
  const geometry=model===null?fallbackGeometry():geometryFromModel(model);
  cancelPointerWork();
  modelRevision++;
  geometry.computeBoundingBox();geometry.computeBoundingSphere();
  const wireGeometry=new THREE.WireframeGeometry(geometry);
  const materials=(geometry.userData.meshParts||[]).map(part=>new THREE.MeshStandardMaterial({color:part.color,roughness:.86,metalness:0,side:THREE.DoubleSide}));
  let offset=0;
  (geometry.userData.meshParts||[]).forEach((part,i)=>{
   if(part.start>offset)geometry.addGroup(offset*3,(part.start-offset)*3,0);
   geometry.addGroup(part.start*3,part.count*3,i+1);offset=part.start+part.count;
  });
  const triangleCount=(geometry.index?.count||geometry.attributes.position.count)/3;
  if(materials.length&&offset<triangleCount)geometry.addGroup(offset*3,(triangleCount-offset)*3,0);
  for(const old of partMaterials)old.dispose();partMaterials=materials;partColors=materials.map(m=>m.color.clone());
  body.material=partMaterials.length?[material,...partMaterials]:material;
  body.geometry.dispose();wire.geometry.dispose();body.geometry=geometry;projectionMesh.geometry=geometry;wire.geometry=wireGeometry;
  sourceModel=model;surfaceMesh=model?prepareSurfaceMesh(model.vertices,model.faces,geometry.userData.meshParts):null;bounds.copy(geometry.boundingBox);buildProjectionBins();
  canvas.setAttribute('aria-label',`${model?'根据商品底图生成的服装粗模':'通用服装示意模型'}，拖动印花移动，拖动选框角点等比缩放，空白处拖动或方向键旋转，滚轮或加减键缩放模型`);
  applyColors();rebuildPrints();rebuildSurfacePrints();resize();return {sourceBound:!!model,vertexCount:geometry.attributes.position.count,faceCount:triangleCount,partCount:materials.length};
 }
 function update(areas,assets,side){
  if(disposed)return;
  latestAreas=(areas||[]).map(a=>({...a}));latestAssets=new Map(assets||[]);latestSide=side||'FRONT';
  group.rotation.set(0,SIDE_ROTATION[latestSide]||0,0);rebuildPrints();
 }
 function applyColors(){
  material.color.setHex(wireframe?0x323232:0xd4d4d4);
  partMaterials.forEach((part,i)=>{if(wireframe||neutral)part.color.setHex(wireframe?0x323232:0xd4d4d4);else part.color.copy(partColors[i]);});
 }
 function setWireframe(value){wireframe=!!value;wire.visible=wireframe;prints.visible=!wireframe;surfacePrints.visible=!wireframe;updateAnchor();applyColors();render();}
 function setNeutral(value){neutral=!!value;applyColors();render();}
 function setMaterialMode(value){if(value!=='neutral'&&value!=='source')throw new Error('未知的粗模材质模式');setNeutral(value==='neutral');}
 const pointerRay=new THREE.Raycaster();
 function rayAt(e){const rect=canvas.getBoundingClientRect();group.updateMatrixWorld(true);pointerRay.setFromCamera(new THREE.Vector2((e.clientX-rect.left)/rect.width*2-1,1-(e.clientY-rect.top)/rect.height*2),camera);return pointerRay;}
 function pickSurface(e,oldTangent){
  if(!surfaceMesh)return null;
  const ray=rayAt(e),hit=ray.intersectObject(body,false)[0];if(!hit)return null;
  const normal=(hit.normal||hit.face.normal).clone().normalize(),world=normal.clone().applyNormalMatrix(new THREE.Matrix3().getNormalMatrix(body.matrixWorld));
  if(world.dot(ray.ray.direction)>0)normal.negate();
  let tangent=oldTangent?new THREE.Vector3(...oldTangent):new THREE.Vector3().crossVectors(new THREE.Vector3(0,1,0),normal);
  tangent.addScaledVector(normal,-tangent.dot(normal));if(tangent.lengthSq()<1e-6)tangent.set(1,0,0).addScaledVector(normal,-normal.x);if(tangent.lengthSq()<1e-6)tangent.set(0,0,1).addScaledVector(normal,-normal.z);tangent.normalize();
  return{position:displayToRaw(body.worldToLocal(hit.point.clone())).toArray(),normal:normal.toArray(),tangent:tangent.toArray(),meshPart:surfaceMesh.faceParts[hit.faceIndex]||'body'};
 }
 function hitPrint(e){
  if(wireframe)return null;
  const ray=rayAt(e),bodyHit=ray.intersectObject(body,false)[0];
  const anchorHit=anchor.visible?ray.intersectObject(anchor,false)[0]:null;
  if(anchorHit&&(!bodyHit||anchorHit.distance<=bodyHit.distance+.025))return selectedPrintId;
  const hit=ray.intersectObjects(surfacePrints.children.filter(m=>m.visible),false).find(h=>!bodyHit||h.distance<=bodyHit.distance+.015);
  return hit?.object.userData.surfacePrintId||null;
 }
 const clonePrint=p=>({...p,position:[...p.position],normal:[...p.normal],tangent:[...p.tangent]});
 function cancelPointerWork(){
  if(pointerFrame)cancelAnimationFrame(pointerFrame);pointerFrame=0;pendingPointer=null;
  const previous=drag;drag=null;
  if(previous?.pointerId!=null&&canvas.hasPointerCapture?.(previous.pointerId))canvas.releasePointerCapture(previous.pointerId);
 }
 function resizeHandleAt(e){
  updateAnchor();if(!selection.visible)return null;
  return hitResizeHandle([e.clientX,e.clientY],projectedHandles,e.pointerType==='touch'?20:12);
 }
 function rawRayAt(e){
  const ray=rayAt(e).ray.clone().applyMatrix4(group.matrixWorld.clone().invert()),n=body.geometry.userData.sourceNormalization;
  if(n)ray.origin.divideScalar(n.scale).add(n.center);
  return ray;
 }
 function resizePrintAt(e){
  if(!drag||drag.kind!=='resize')return;
  const current=latestSurfacePrints.find(p=>p.id===drag.id);if(!current)return;
  const hit=drag.resize.startPoint?rawRayAt(e).intersectPlane(drag.plane,new THREE.Vector3()):null;
  const next=resizeSurfacePrint(drag.resize,{surfacePoint:hit?.toArray(),screenPoint:[e.clientX,e.clientY]});
  if(!next||next.width===current.width&&next.height===current.height)return;
  latestSurfacePrints=latestSurfacePrints.map(p=>p.id===drag.id?next:p);rebuildSurfacePrints();
 }
 function applyPointer(e){if(drag?.kind==='resize')resizePrintAt(e);else movePrintAt(e);}
 function pointerCursor(e){
  const corner=e?resizeHandleAt(e):null;
  if(corner!==null){
   const selected=latestSurfacePrints.find(p=>p.id===selectedPrintId),rect=canvas.getBoundingClientRect();
   const center=group.localToWorld(rawToDisplay(selected.position)).project(camera),handle=projectedHandles[corner].screen;
   const dx=handle[0]-(rect.left+(center.x+1)*rect.width/2),dy=handle[1]-(rect.top+(1-center.y)*rect.height/2);
   canvas.style.cursor=dx*dy>=0?'nwse-resize':'nesw-resize';
  }else canvas.style.cursor=placementMode?'crosshair':'grab';
 }
 function movePrintAt(e){
  if(!drag||drag.kind!=='print')return;
  const current=latestSurfacePrints.find(p=>p.id===drag.id);if(!current)return;
  const picked=pickSurface(e,current.tangent);if(!picked)return;
  const next={...current,...picked};latestSurfacePrints=latestSurfacePrints.map(p=>p.id===drag.id?next:p);drag.changed=true;rebuildSurfacePrints();
 }
 const onPointerDown=e=>{
  if(disposed||exporting||drag||![0,2].includes(e.button))return;e.preventDefault();canvas.focus({preventScroll:true});canvas.setPointerCapture(e.pointerId);
  if(e.button===2||e.altKey){drag={kind:'orbit',pointerId:e.pointerId,x:e.clientX,y:e.clientY};return;}
  const corner=resizeHandleAt(e),selected=latestSurfacePrints.find(p=>p.id===selectedPrintId);
  if(corner!==null&&selected){
   const frame=surfaceFrame(selected),plane=new THREE.Plane().setFromNormalAndCoplanarPoint(new THREE.Vector3(...frame.normal),new THREE.Vector3(...selected.position));
   const ray=rawRayAt(e),point=Math.abs(ray.direction.dot(plane.normal))>.18?ray.intersectPlane(plane,new THREE.Vector3()):null;
   const rect=canvas.getBoundingClientRect(),center=group.localToWorld(rawToDisplay(selected.position)).project(camera);
   try{
    const session=beginSurfaceResize({print:selected,corner,garmentHeight:(bounds.max.y-bounds.min.y)/(body.geometry.userData.sourceNormalization?.scale||1),startPoint:point?.toArray(),
     startScreen:[e.clientX,e.clientY],screenCenter:[rect.left+(center.x+1)*rect.width/2,rect.top+(1-center.y)*rect.height/2],screenCorner:projectedHandles[corner].screen});
    drag={kind:'resize',pointerId:e.pointerId,id:selected.id,resize:session,plane};pointerCursor(e);return;
   }catch(error){
    host.dispatchEvent(new CustomEvent('print-surface-error',{detail:{id:selected.id,message:error.message}}));
    if(canvas.hasPointerCapture?.(e.pointerId))canvas.releasePointerCapture(e.pointerId);return;
   }
  }
  if(placementMode){const picked=pickSurface(e);drag={kind:'place',pointerId:e.pointerId};if(picked)host.dispatchEvent(new CustomEvent('print-surface-pick',{detail:{...picked,placement:{...picked},existing:false}}));return;}
  const id=hitPrint(e),existing=latestSurfacePrints.find(p=>p.id===id);
  if(existing){setSelectedPrint(id);drag={kind:'print',pointerId:e.pointerId,id,previous:clonePrint(existing),changed:false,startX:e.clientX,startY:e.clientY};canvas.style.cursor='grabbing';
   host.dispatchEvent(new CustomEvent('print-surface-pick',{detail:{...clonePrint(existing),id,printId:id,placement:clonePrint(existing),existing:true}}));return;}
  drag={kind:'orbit',pointerId:e.pointerId,x:e.clientX,y:e.clientY};
 };
 const onPointerMove=e=>{
  if(exporting)return;if(!drag){pointerCursor(e);return;}if(e.pointerId!==drag.pointerId)return;
  if(drag.kind==='orbit'){group.rotation.y+=(e.clientX-drag.x)*.009;group.rotation.x=THREE.MathUtils.clamp(group.rotation.x+(e.clientY-drag.y)*.006,-1.4,1.4);drag.x=e.clientX;drag.y=e.clientY;render();}
  else if(drag.kind==='resize'||drag.kind==='print'&&(drag.changed||Math.hypot(e.clientX-drag.startX,e.clientY-drag.startY)>3)){
   pendingPointer={clientX:e.clientX,clientY:e.clientY};if(!pointerFrame)pointerFrame=requestAnimationFrame(()=>{pointerFrame=0;if(pendingPointer){applyPointer(pendingPointer);pendingPointer=null;}});
  }
 };
 const onPointerEnd=e=>{
  if(!drag||e.pointerId!==drag.pointerId)return;
  if(pointerFrame){cancelAnimationFrame(pointerFrame);pointerFrame=0;}
  if(e.type==='pointerup'&&(pendingPointer||drag.kind==='resize'))applyPointer(e);pendingPointer=null;
  const ended=drag;drag=null;
  if(ended.kind==='resize'){
   const result=finishSurfaceResize(ended.resize,latestSurfacePrints.find(p=>p.id===ended.id),e.type);
   if(result){latestSurfacePrints=latestSurfacePrints.map(p=>p.id===ended.id?result.print:p);rebuildSurfacePrints();
    if(result.detail)host.dispatchEvent(new CustomEvent('print-surface-change',{detail:result.detail}));}
  }else if(ended.kind==='print'&&ended.changed){
   if(e.type==='pointerup'){const print=latestSurfacePrints.find(p=>p.id===ended.id);if(print)host.dispatchEvent(new CustomEvent('print-surface-change',{detail:{id:ended.id,print:clonePrint(print),previous:ended.previous,commit:true,reason:'move'}}));}
   else{latestSurfacePrints=latestSurfacePrints.map(p=>p.id===ended.id?ended.previous:p);rebuildSurfacePrints();}
  }
  if(canvas.hasPointerCapture?.(e.pointerId))canvas.releasePointerCapture(e.pointerId);pointerCursor();
 };
 const onContextMenu=e=>e.preventDefault();
 const onWheel=e=>{e.preventDefault();if(exporting||drag?.kind==='resize')return;camera.position.z=THREE.MathUtils.clamp(camera.position.z+e.deltaY*.004,2.6,9);render();};
 const onKey=e=>{
  if(!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','+','=','-','Home'].includes(e.key))return;e.preventDefault();if(exporting||drag?.kind==='resize')return;
  if(e.key==='ArrowLeft')group.rotation.y-=.15;if(e.key==='ArrowRight')group.rotation.y+=.15;
  if(e.key==='ArrowUp')group.rotation.x-=.1;if(e.key==='ArrowDown')group.rotation.x+=.1;
  group.rotation.x=THREE.MathUtils.clamp(group.rotation.x,-1.4,1.4);
  if(e.key==='+'||e.key==='=')camera.position.z=Math.max(2.6,camera.position.z-.3);if(e.key==='-')camera.position.z=Math.min(9,camera.position.z+.3);
  if(e.key==='Home'){group.rotation.set(0,0,0);camera.position.z=5;}render();
 };
 function exportCamera(side){
  const n=body.geometry.userData.sourceNormalization,c=viewReference(side)?.camera,axes=SIDE_AXES[side];
  let extent=Math.max(bounds.getSize(new THREE.Vector3()).length()*1.12,2.8),cx=0,cy=0;
  if(side==='TOP'||side==='BOTTOM'){const size=bounds.getSize(new THREE.Vector3());extent=Math.max(size.x,size.z)*1.12;}
  else if(n&&c?.projection==='orthographic'&&['scale','cx','cy','rasterSize'].every(k=>Number.isFinite(c[k]))&&c.scale>0&&c.rasterSize>0){
   extent=c.rasterSize/c.scale*n.scale;
   cx=((c.rasterSize/2-c.cx)/c.scale-n.center.dot(new THREE.Vector3(...axes.slice(0,3))))*n.scale;
   cy=((c.cy-c.rasterSize/2)/c.scale-n.center.y)*n.scale;
  }
  const result=new THREE.OrthographicCamera(cx-extent/2,cx+extent/2,cy+extent/2,cy-extent/2,.01,100);
  result.position.set(0,0,10);result.updateMatrixWorld(true);return result;
 }
 // Blink may defer toBlob's idle encoder for a hidden tab. These bounded
 // preview PNGs are encoded now, so freezing placements cannot wait minutes
 // merely because the user switched tabs while saving a template.
 const pngBlob=async(source=canvas)=>{
  const encoded=source.toDataURL('image/png').split(',')[1];if(!encoded)throw new Error('预览图导出失败');
  const binary=atob(encoded),bytes=new Uint8Array(binary.length);for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);
  return new Blob([bytes],{type:'image/png'});
 };
 async function rawGuideBlob(viewCamera,target,size,visibleSlots){
  // IDs cannot pass through the display canvas's MSAA/premultiplied-alpha
  // resolve: e.g. slot 3 at quarter coverage can round into slot 4. Use a
  // single-sample RGBA target and encode the unchanged bytes instead.
  const pixels=new Uint8Array(size*size*4),flipped=new Uint8ClampedArray(pixels.length);
  renderer.setRenderTarget(target);renderer.render(scene,viewCamera);renderer.readRenderTargetPixels(target,0,0,size,size,pixels);renderer.setRenderTarget(null);
  if(visibleSlots)for(let i=0;i<pixels.length;i+=4)if(pixels[i+3]&&pixels[i+2])visibleSlots.add(pixels[i+2]);
  for(let y=0;y<size;y++)flipped.set(pixels.subarray((size-1-y)*size*4,(size-y)*size*4),y*size*4);
  const image=document.createElement('canvas');image.width=size;image.height=size;
  image.getContext('2d').putImageData(new ImageData(flipped,size,size),0,0);return pngBlob(image);
 }
 function guideMaterial(mode,slot=0){
  const fragment=mode==='uv'?'gl_FragColor=vec4(vUv,slot/255.0,1.0);':mode==='mask'?'gl_FragColor=vec4(1.0);':'gl_FragColor=vec4(0.0);';
  return new THREE.ShaderMaterial({uniforms:{slot:{value:slot}},vertexShader:'varying vec2 vUv; void main(){vUv=uv;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0);}',
   fragmentShader:`varying vec2 vUv; uniform float slot; void main(){${fragment}}`,side:THREE.DoubleSide,blending:THREE.NoBlending,depthWrite:mode==='occluder',
   polygonOffset:mode!=='occluder',polygonOffsetFactor:-2,polygonOffsetUnits:-2,toneMapped:false});
 }
 async function exportFrames({size=1024,guides=false}={}){
  if(disposed||exporting)throw new Error('模型预览暂时无法导出');
  if(!sourceModel)throw new Error('请先生成商品模型');
  if(!Number.isInteger(size)||size<128||size>2048)throw new Error('导出尺寸应为 128–2048');
  const ticket=surfaceRevision,legacyTicket=revision,modelTicket=modelRevision,printIds=latestSurfacePrints.map(p=>p.id);
  const unchanged=()=>{if(disposed||ticket!==surfaceRevision||legacyTicket!==revision||modelTicket!==modelRevision)throw new Error('模型或印花已变更，请重新导出');};
  await Promise.all([...retainedTextureUrls()].map(url=>loadTexture(url)));
  unchanged();
  exporting=true;
  const old={rotation:group.rotation.clone(),size:renderer.getSize(new THREE.Vector2()),ratio:renderer.getPixelRatio(),background:scene.background,
   wireframe,neutral,wireVisible:wire.visible,printsVisible:prints.visible,surfaceVisible:surfacePrints.visible,anchorVisible:anchor.visible,selectionVisible:selection.visible,bodyMaterial:body.material};
  const meshMaterials=new Map(surfacePrints.children.map(mesh=>[mesh,mesh.material])),temporary=[],views={},visibleSlots=new Set();
  const guideTarget=guides?new THREE.WebGLRenderTarget(size,size,{format:THREE.RGBAFormat,type:THREE.UnsignedByteType,samples:0,depthBuffer:true,stencilBuffer:false,minFilter:THREE.NearestFilter,magFilter:THREE.NearestFilter,generateMipmaps:false}):null;
  if(guideTarget)guideTarget.texture.colorSpace=THREE.NoColorSpace;
  const opaqueBackground=scene.background;
  try{
   renderer.setPixelRatio(1);renderer.setSize(size,size,false);wireframe=false;neutral=false;applyColors();wire.visible=false;anchor.visible=false;selection.visible=false;
   const sides=['FRONT','BACK','LEFT','RIGHT'];
   for(let viewIndex=0;viewIndex<sides.length;viewIndex++){
    const side=sides[viewIndex];
    // TOP looks from +Y, with the garment front (+Z) toward image-bottom;
    // BOTTOM looks from -Y, with +Z toward image-top. Input controls stay 4-way.
    group.rotation.set(side==='TOP'?Math.PI/2:side==='BOTTOM'?-Math.PI/2:0,SIDE_ROTATION[side]||0,0);
    group.updateMatrixWorld(true);const viewCamera=exportCamera(side);
    body.material=old.bodyMaterial;scene.background=opaqueBackground;prints.visible=true;surfacePrints.visible=true;
    for(const[mesh,mat]of meshMaterials)mesh.material=mat;
    renderer.render(scene,viewCamera);const image=await pngBlob();unchanged();
    if(!guides){views[side]=image;continue;}
    prints.visible=false;surfacePrints.visible=false;renderer.render(scene,viewCamera);const blank=await pngBlob();unchanged();
    surfacePrints.visible=true;scene.background=null;renderer.setClearColor(0x000000,0);
    const occluder=guideMaterial('occluder');temporary.push(occluder);body.material=occluder;
    for(const mesh of surfacePrints.children){const mat=guideMaterial('mask');temporary.push(mat);mesh.material=mat;}
    const mask=await rawGuideBlob(viewCamera,guideTarget,size);unchanged();
    for(const mesh of surfacePrints.children){const mat=guideMaterial('uv',mesh.userData.printSlot+1);temporary.push(mat);mesh.material=mat;}
    const uv=await rawGuideBlob(viewCamera,guideTarget,size,visibleSlots);unchanged();
    views[side]={image,blank,mask,uv};
    if(viewIndex===3&&printIds.some((_,slot)=>!visibleSlots.has(slot+1)))sides.push('TOP','BOTTOM');
   }
   return guides?{views,printIds,invisiblePrintIds:printIds.filter((_,slot)=>!visibleSlots.has(slot+1))}:views;
  }finally{
   renderer.setRenderTarget(null);guideTarget?.dispose();
   for(const[mesh,mat]of meshMaterials)if(mesh.parent===surfacePrints)mesh.material=mat;if(modelTicket===modelRevision)body.material=old.bodyMaterial;for(const mat of temporary)mat.dispose();
   if(legacyTicket===revision)group.rotation.copy(old.rotation);scene.background=old.background;renderer.setClearColor(0x000000,0);
   wireframe=old.wireframe;neutral=old.neutral;wire.visible=old.wireVisible;prints.visible=old.printsVisible;surfacePrints.visible=old.surfaceVisible;anchor.visible=old.anchorVisible;selection.visible=old.selectionVisible;applyColors();
   renderer.setPixelRatio(old.ratio);renderer.setSize(old.size.x,old.size.y,false);exporting=false;updateAnchor();resize();render();
  }
 }
 const exportViews=options=>exportFrames({...options,guides:false});
 // Each view has image/blank/mask/uv PNG Blobs. UV is unlit RGBA8:
 // R=u, G=v, B=(printIds slot + 1)/255, A=visible geometric footprint.
 // v points upward; a top-left raster samples artwork y=(1-v)*(height-1).
 // Transparent artwork pixels still have a guide footprint so a replacement
 // can expose blank cloth without retaining the previous artwork underneath.
 // TOP/BOTTOM are appended only if a print is absent from all four cardinal
 // views. invisiblePrintIds reports patches still occluded after six views;
 // no export pass disables garment depth to make an invisible patch appear.
 const exportSurfaceGuides=options=>exportFrames({...options,guides:true});
 canvas.addEventListener('pointerdown',onPointerDown);canvas.addEventListener('pointermove',onPointerMove);
 for(const event of ['pointerup','pointercancel','lostpointercapture'])canvas.addEventListener(event,onPointerEnd);
 canvas.addEventListener('wheel',onWheel,{passive:false});canvas.addEventListener('keydown',onKey);
 canvas.addEventListener('contextmenu',onContextMenu);
 setModel(null);
 return {setModel,setWireframe,setNeutral,setMaterialMode,update,setSurfacePrints,setPlacementMode,setSelectedPrint,exportViews,exportSurfaceGuides,
 setVisible(value){active=!!value;if(active)resize();},reset(){group.rotation.set(0,0,0);camera.position.z=5;render();},dispose(){
  if(disposed)return;disposed=true;revision++;surfaceRevision++;observer.disconnect();clearPrints();clearSurfacePrints();releaseUnusedTextures(new Set());
  if(pointerFrame)cancelAnimationFrame(pointerFrame);
  canvas.removeEventListener('pointerdown',onPointerDown);canvas.removeEventListener('pointermove',onPointerMove);
  for(const event of ['pointerup','pointercancel','lostpointercapture'])canvas.removeEventListener(event,onPointerEnd);
  canvas.removeEventListener('wheel',onWheel);canvas.removeEventListener('keydown',onKey);
  canvas.removeEventListener('contextmenu',onContextMenu);
  clearProjectionBins();body.geometry.dispose();wire.geometry.dispose();anchor.geometry.dispose();anchorMaterial.dispose();selectionLine.geometry.dispose();selectionLineMaterial.dispose();handleOuterMaterial.dispose();handleInnerMaterial.dispose();material.dispose();for(const part of partMaterials)part.dispose();wireMaterial.dispose();renderer.dispose();canvas.remove();
 }};
}
