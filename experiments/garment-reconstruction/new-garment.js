import * as THREE from 'three';
import {OrbitControls} from 'three/examples/jsm/controls/OrbitControls.js';
const root='../../output/garment-etmall-20260911/';
const stage=document.querySelector('#stage');
const renderer=new THREE.WebGLRenderer({antialias:true,preserveDrawingBuffer:true});
renderer.setPixelRatio(Math.min(devicePixelRatio,2));renderer.setClearColor(0x202124);
renderer.outputColorSpace=THREE.SRGBColorSpace;stage.prepend(renderer.domElement);
const scene=new THREE.Scene(),camera=new THREE.PerspectiveCamera(32,1,.01,100);
camera.position.set(2,.9,3);const controls=new OrbitControls(camera,renderer.domElement);controls.target.set(0,.65,0);controls.enableDamping=true;
scene.add(new THREE.HemisphereLight(0xffffff,0x777777,2));
for(const [x,y,z,power] of [[-3,4,5,2.2],[4,2,-2,1.2]]){const l=new THREE.DirectionalLight(0xffffff,power);l.position.set(x,y,z);scene.add(l);}
const gray=new THREE.MeshStandardMaterial({color:0xaaaaaa,roughness:.95,side:THREE.DoubleSide});
const wire=new THREE.MeshBasicMaterial({color:0xffffff,wireframe:true,side:THREE.DoubleSide});
let model,materials,textured=false,wireframe=false;
let lastWidth=0,lastHeight=0;
function resize(){const w=stage.clientWidth,h=stage.clientHeight;if(w===lastWidth&&h===lastHeight)return;lastWidth=w;lastHeight=h;renderer.setSize(w,h);camera.aspect=w/h;camera.updateProjectionMatrix();}new ResizeObserver(resize).observe(stage);resize();
function setView(degrees){const a=degrees*Math.PI/180;camera.position.set(Math.sin(a)*3.3,.8,Math.cos(a)*3.3);controls.target.set(0,.65,0);controls.update();}
function draw(){resize();controls.update();renderer.render(scene,camera);}renderer.setAnimationLoop(draw);
document.querySelectorAll('[data-yaw]').forEach(b=>b.onclick=()=>setView(Number(b.dataset.yaw)));
function mode(){if(model)model.material=wireframe?wire:textured?materials:gray;document.querySelector('#wire').classList.toggle('active',wireframe);document.querySelector('#fabric').classList.toggle('active',textured&&!wireframe);}
document.querySelector('#wire').onclick=()=>{wireframe=!wireframe;mode();};document.querySelector('#fabric').onclick=()=>{textured=!textured;wireframe=false;mode();};
const refitButton=document.querySelector('#refit');
if(refitButton)refitButton.onclick=async()=>{
 refitButton.disabled=true;refitButton.textContent='检查输入…';
 try{
  const response=await fetch('/api/gpu-fit/start',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});
  const initial=await response.json();if(!response.ok)throw new Error(initial.error||'无法启动拟合');
  const deadline=Date.now()+120000;
  while(Date.now()<deadline){
   const state=await(await fetch('/api/gpu-fit/status',{cache:'no-store'})).json();
   if(state.jobId!==initial.jobId)throw new Error('任务已切换，请重新读取结果');
   if(state.state==='failed')throw new Error(state.error);
   if(state.state==='completed'){
    refitButton.textContent=state.cacheHit?`已复用结果 · ${state.seconds.toFixed(2)} 秒`:'重新拟合完成';
    if(!state.cacheHit){location.href='./new-garment.html';}return;
   }
   refitButton.textContent='拟合与检查中…';await new Promise(resolve=>setTimeout(resolve,500));
  }
  throw new Error('检查超时，原模型保留');
 }catch(e){document.querySelector('#error').textContent=e.message;refitButton.textContent='重试拟合';}
 finally{refitButton.disabled=false;}
};
try{
 const requestedRun=new URLSearchParams(location.search).get('run');
 const runFamily=new URLSearchParams(location.search).get('family')==='safe'?'safe-runs':'profile-runs';
 const latest=requestedRun&&/^\d{8}-\d{6}$/.test(requestedRun)
   ?{meshUrl:root+runFamily+'/'+requestedRun+'/mesh.json',summaryUrl:root+runFamily+'/'+requestedRun+'/summary.json'}
   :await(await fetch(root+'latest.json',{cache:'no-store'})).json();
 const [mesh,summary]=await Promise.all([fetch(latest.meshUrl).then(r=>r.json()),fetch(latest.summaryUrl).then(r=>r.json())]);
 if(summary.backend==='direct_profile'&&summary.structurallyUsable!==true){
  document.querySelector('#error').textContent='轮廓拟合实验候选：尚未通过曲面与穿插检查，不能作为正式改进版本。';
 }
 const geometry=new THREE.BufferGeometry();
 const positions=[],uv=[];
 const back=new Set(mesh.face_regions.back);
 const loader=new THREE.TextureLoader();
 const textures=await Promise.all(['front','back'].map(n=>loader.loadAsync(root+n+'-texture.png')));
 textures.forEach(t=>{t.colorSpace=THREE.SRGBColorSpace;t.anisotropy=renderer.capabilities.getMaxAnisotropy();});
 materials=textures.map(map=>new THREE.MeshStandardMaterial({map,roughness:1,side:THREE.DoubleSide}));
 mesh.faces.forEach((face,i)=>{
  const isBack=back.has(i),c=summary.views.find(v=>v.id===(isBack?'back':'front')).camera;
  for(const index of face){const v=mesh.vertices[index];positions.push(...v);uv.push(((isBack?-v[0]:v[0])*c.scale+c.cx)/512,1-(-v[1]*c.scale+c.cy)/512);}
  if(i===0||isBack!==back.has(i-1))geometry.addGroup(i*3,3,isBack?1:0);
  else geometry.groups[geometry.groups.length-1].count+=3;
 });
 geometry.setAttribute('position',new THREE.Float32BufferAttribute(positions,3));geometry.setAttribute('uv',new THREE.Float32BufferAttribute(uv,2));
 // Share normals across logical vertices even though face materials use
 // unindexed UV coordinates. This keeps clay shading free of triangle facets.
 const indexed=new THREE.BufferGeometry();indexed.setAttribute('position',new THREE.Float32BufferAttribute(mesh.vertices.flat(),3));indexed.setIndex(mesh.faces.flat());indexed.computeVertexNormals();
 geometry.setAttribute('normal',new THREE.Float32BufferAttribute(mesh.faces.flatMap(f=>f.flatMap(i=>[indexed.attributes.normal.getX(i),indexed.attributes.normal.getY(i),indexed.attributes.normal.getZ(i)])),3));indexed.dispose();
 model=new THREE.Mesh(geometry,gray);scene.add(model);setView(25);
 document.querySelector('#download').href=latest.meshUrl;
 document.querySelector('#info').textContent=`${mesh.vertices.length.toLocaleString()} 顶点 · ${mesh.faces.length.toLocaleString()} 三角面 · 4 个开口 · 斜肩独立模型`;
 document.querySelector('#timing').textContent=['direct_profile','bounded_refit'].includes(summary.backend)
   ?`直接轮廓拟合、评分及结构检查 ${summary.seconds.toFixed(2)} 秒，无需重复搜索候选。四面 0.95：${summary.views.every(v=>v.metrics.iou>=.95)?'通过':'未全部通过'}。`
   :`${summary.backend==='webgpu'?'GPU':'CPU'} 拟合 ${summary.evaluations} 个候选，耗时 ${summary.seconds.toFixed(1)} 秒。`;
 if(summary.referenceConsistency){const note=document.createElement('p');note.textContent='左右参考轮廓存在冲突，固定相机下共同达到 0.95 不可行；原始参考和评分均保留。';document.querySelector('#timing').after(note);}
 const labels={front:'正面',back:'背面',left:'左侧',right:'右侧'};
 for(const v of summary.views){const tr=document.createElement('tr');for(const text of [labels[v.id],v.metrics.iou.toFixed(3)]){const td=document.createElement('td');td.textContent=text;tr.append(td);}document.querySelector('#metrics').append(tr);}
 window.garmentPreview={model,mesh,summary,camera,controls,setView,capture(){draw();return renderer.domElement.toDataURL();},setMode(m){wireframe=m==='wire';textured=m==='texture';mode();}};
}catch(e){document.querySelector('#error').textContent='模型载入失败：'+e.message;window.previewError=e.message;}
