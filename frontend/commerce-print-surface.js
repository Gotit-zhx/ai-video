/* Original photo → coherent AI references → direct photo artwork. No image credentials in the browser. */
(() => {
 const emptyRenders=()=>({renderedViews:{},blankRenderedViews:{},surfaceUvViews:{},placementMasks:{},renderPrintIds:[],renderBinding:null});
 const assetUrl=id=>'/api/v1/commerce-print-templates/base-images/'+id;
 const testPatterns=[{id:'starburst',label:'星芒',sha256:'91ba0bee73369b694cea7345dc7269da99a99fb70b4b94db8de0b18efb17b9e7'},{id:'flower',label:'花瓣',sha256:'a640e3d7cd32283257eb2b9ead481de175247712e73f2597323ccb6d9a2000c0'},{id:'checker',label:'棋盘',file:'checker-full',sha256:'5a5230ba3abc10629ee66d39f0871cc5350f670ec60b900e5dc8c622149a25eb'}];
 function drawTestPattern(canvas,id){
  canvas.width=canvas.height=512;const g=canvas.getContext('2d');if(!g)throw new Error('当前浏览器无法生成测试花纹');
  g.clearRect(0,0,512,512);g.save();g.translate(256,256);
  if(id==='starburst'){
   g.fillStyle='#202020';g.beginPath();
   for(let i=0;i<24;i++){const angle=i*Math.PI/12-Math.PI/2,r=i%2?92:190;const x=Math.cos(angle)*r,y=Math.sin(angle)*r;i?g.lineTo(x,y):g.moveTo(x,y);}
   g.closePath();g.fill();g.fillStyle='#dea844';g.beginPath();g.arc(0,0,44,0,Math.PI*2);g.fill();
  }else if(id==='flower'){
   g.fillStyle='#d87561';
   for(let i=0;i<8;i++){g.save();g.rotate(i*Math.PI/4);g.beginPath();g.ellipse(0,-104,42,80,0,0,Math.PI*2);g.fill();g.restore();}
   g.fillStyle='#242424';g.beginPath();g.arc(0,0,52,0,Math.PI*2);g.fill();
  }else{
   for(let y=0;y<5;y++)for(let x=0;x<5;x++){g.fillStyle=(x+y)%2?'#b2c9c1':'#345e68';g.fillRect(x*102.4-256,y*102.4-256,102.4,102.4);}
  }
  g.restore();return canvas;
 }
 async function testPatternPng(id){
  const pattern=testPatterns.find(pattern=>pattern.id===id);if(!pattern)throw new Error('测试花纹无效');
  // Fixed bytes keep one marker group stable across browsers and saved template versions.
  const response=await fetch('/assets/print-patterns/'+(pattern.file||id)+'.png?v='+pattern.sha256,{cache:'force-cache'});
  if(!response.ok)throw new Error('测试花纹读取失败，请重试');
  const bytes=await response.arrayBuffer(),digest=await crypto.subtle.digest('SHA-256',bytes);
  const actual=Array.from(new Uint8Array(digest),byte=>byte.toString(16).padStart(2,'0')).join('');
  if(actual!==pattern.sha256)throw new Error('测试花纹内容不完整，请刷新后重试');
  return new Blob([bytes],{type:'image/png'});
 }
 window.CommercePrintSurface={mount(ctx){
  const {root,sidebar,host,n}=ctx;
  let sourceImageId=null,referenceSetId=null,prints=[],renders=emptyRenders(),selected=null,artworkId=null;
  let undoStack=[],redoStack=[],working=false,attempt=0,pending=null,legacyConfiguration=null,sourceUrl=null,localSource=null,testPattern='starburst',artworkPreparing=false;
  const panel=n('section','',sidebar);panel.className='print-surface-panel print-inspector-section';
  const heading=n('div','',panel);heading.className='print-section-heading';n('span','03',heading).className='print-step-number';n('h3','印花位置',heading);
  n('p','花纹只标记位置，裂变画风来自来源商品。相同标记共用新图案。',panel).className='print-section-description';
  const panelEmpty=n('p','四面参考就绪后，即可添加印花。',panel);panelEmpty.className='print-panel-empty';
  const patternPicker=n('div','',panel);patternPicker.className='print-test-patterns';patternPicker.setAttribute('role','group');patternPicker.setAttribute('aria-label','测试花纹');
  const patternButtons=testPatterns.map(pattern=>{
   const button=n('button','',patternPicker);button.id='print-test-pattern-'+pattern.id;button.setAttribute('aria-label',pattern.label+'测试花纹');button.setAttribute('aria-pressed',String(pattern.id===testPattern));
   const thumbnail=n('canvas','',button);thumbnail.setAttribute('aria-hidden','true');try{drawTestPattern(thumbnail,pattern.id);}catch{thumbnail.hidden=true;}
   n('span',pattern.label,button);return {pattern,button};
  });
  const useTest=n('button','使用测试花纹',panel);useTest.id='print-test-pattern-use';
  const artworkUpload=n('details','',panel);artworkUpload.className='print-artwork-optional';n('summary','使用自己的图片（可选）',artworkUpload);
  const fileLabel=n('label','上传印花',artworkUpload);fileLabel.className='print-artwork-upload';const file=n('input','',fileLabel);file.type='file';file.accept='image/png,image/jpeg';file.id='print-surface-artwork';
  const artworkPreview=n('img','',panel);artworkPreview.className='print-artwork-thumb';artworkPreview.hidden=true;artworkPreview.alt='当前印花素材';
  const actions=n('div','',panel);actions.className='print-surface-actions';
  const place=n('button','放置印花',actions),remove=n('button','删除印花',actions);place.id='print-surface-place';
  const printCount=n('p','',panel);printCount.id='print-surface-count';printCount.className='print-placement-count';
  const printList=n('div','',panel);printList.className='print-placement-list';printList.setAttribute('aria-label','已放置的印花');
  const select=n('select','',panel);select.setAttribute('aria-label','图片上的印花');select.id='print-surface-selection';select.className='print-placement-select';
  const scaleLabel=n('label','整体大小',panel);scaleLabel.className='print-size-slider';
  const scale=n('input','',scaleLabel);scale.type='range';scale.min='1';scale.max='100';scale.step='1';scale.setAttribute('aria-label','印花整体大小');
  const scaleValue=n('output','',scaleLabel);let resizeBefore=null;
  const sizing=n('div','',panel);sizing.className='print-surface-sizing';
  const width=n('input','',n('label','宽度（图片 %）',sizing)),height=n('input','',n('label','高度（图片 %）',sizing)),rotation=n('input','',n('label','旋转 °',sizing));
  for(const input of [width,height,rotation]){input.type='number';input.step='1';}
  width.min=height.min='1';width.max=height.max='100';rotation.min='-360';rotation.max='360';
  const history=n('div','',panel);history.className='print-surface-actions print-history-actions';
  const undo=n('button','撤回',history),redo=n('button','重做',history);undo.id='print-surface-undo';redo.id='print-surface-redo';
  const note=n('p','先准备四面参考，再添加印花。',panel);note.setAttribute('role','status');note.id='print-surface-status';
  const legacyNotice=n('div','',panel);legacyNotice.className='print-legacy-notice';legacyNotice.hidden=true;
  n('p','此历史模板的印花使用空间坐标，无法直接转成图片位置。原版本与素材会保留；重新设置后保存为新版本。',legacyNotice);
  const convertLegacy=n('button','在四面图重新设置印花',legacyNotice);convertLegacy.type='button';
  convertLegacy.addEventListener('click',()=>{if(isLocked())return;const before=snapshot();legacyConfiguration=null;prints=[];selected=null;renders=emptyRenders();changed(before);note.textContent='已切换为四面图片编辑。选择花纹并重新放置，保存后产生新版本。';});
  const sourceCard=n('div','',ctx.baseTools);sourceCard.id='print-source-card';sourceCard.className='print-source-card';sourceCard.hidden=true;
  const original=n('img','',sourceCard);original.id='print-source-preview';original.className='print-original-thumb';original.alt='已选择的商品原图';
  const sourceInfo=n('div','',sourceCard),sourceName=n('strong','',sourceInfo);sourceName.id='print-source-name';
  const sourceState=n('p','',sourceInfo);sourceState.id='print-source-state';
  const referenceActions=n('div','',ctx.baseTools);referenceActions.className='print-surface-actions';
  const resume=n('button','继续准备四面参考',ctx.referenceActions||referenceActions);resume.id='print-reference-resume';resume.type='button';
  const retry=n('button','重试参考图处理',referenceActions);retry.hidden=true;
  const detach=n('button','停止等待',referenceActions);detach.hidden=true;
  const findSource=n('button','找回已上传原图',referenceActions);findSource.id='print-source-history';
  const sourceHistory=n('div','',ctx.baseTools);sourceHistory.className='print-source-history';sourceHistory.hidden=true;
  const jobNote=n('p','准备同组四面参考后，直接在图片上放置印花。',ctx.referencePanel||ctx.baseTools);jobNote.setAttribute('role','status');jobNote.id='print-reference-status';
  panel.querySelectorAll('button').forEach(b=>b.type='button');referenceActions.querySelectorAll('button').forEach(b=>b.type='button');
  const org=()=>ctx.organizationId?.()||'';
  const storageKey=()=>org()?'heyi:print-reference:'+org():null;
  function remember(value){const key=storageKey();if(!key)return;try{if(value)localStorage.setItem(key,JSON.stringify(value));else localStorage.removeItem(key);}catch{}}
  function remembered(){try{return JSON.parse(localStorage.getItem(storageKey())||'null');}catch{return null;}}
  const recoveryMessage=record=>record?.notStarted?'原图已找回。点击「生成四面参考」继续。':'已找回上次上传原图，点击「继续准备四面参考」恢复原任务。';
  const request=(url,options={})=>api(url,{...(typeof AbortSignal!=='undefined'&&AbortSignal.timeout?{signal:AbortSignal.timeout(90000)}:{}),...options});
  function releaseLocalSource(){if(localSource?.url)URL.revokeObjectURL(localSource.url);localSource=null;}
  function jobStatus(message,status){jobNote.textContent=message;if(pending){pending={...pending,message,status:status||pending.status};remember(pending);}}
  function drawSource(){
   const record=localSource||pending||(!sourceImageId&&!Object.keys(ctx.configuration().baseImages||{}).length?remembered():null),id=record?.request?.sourceImageId||record?.sourceImageId||sourceImageId;
   const url=record?.url||(id?assetUrl(id):null);sourceCard.hidden=!url;original.hidden=!url;
   if(url&&original.getAttribute?.('src')!==url)original.src=url;else if(!url)original.removeAttribute('src');
   sourceUrl=url;ctx.sourcePreview?.(url);
   sourceName.textContent=record?.fileName||'商品原图';sourceName.title=sourceName.textContent;
   sourceState.textContent=record?.error?'上传未完成，请重新选择此图片上传。':record?.url?'原图已选择，正在上传；生成前会先保存原图。':id?'原图已保存，生成中断也可找回。':'';
  }
  const fields=()=>({sourceImageId,referenceSetId,photoPrints:structuredClone(prints),surfacePrints:[],reconstruction:null,areas:[],...renders});
  const snapshot=()=>({...ctx.configuration(),...fields(),_legacyConfiguration:legacyConfiguration});
  function checkpoint(before){undoStack.push(before);if(undoStack.length>40)undoStack.shift();redoStack=[];}
  function changed(before){checkpoint(before);renders=emptyRenders();ctx.changed();draw();}
  const current=()=>prints.find(p=>p.id===selected);
  const isLocked=()=>working||!!ctx.isLocked?.();
  const faceSelections=new Map();let activeSide=null;
  function constrainPrint(p){
   const aspect=ctx.imageAspect?.(p.side)||1,c=Math.abs(Math.cos(p.rotation||0)),s=Math.abs(Math.sin(p.rotation||0));
   let w=p.width*c+p.height*s/aspect,h=p.width*s*aspect+p.height*c;
   const shrink=Math.min(1,1/w,1/h),cx=p.x+p.width/2,cy=p.y+p.height/2;p.width*=shrink;p.height*=shrink;w*=shrink;h*=shrink;
   const hx=Math.max(w,p.width)/2,hy=Math.max(h,p.height)/2;
   p.x=Math.max(hx,Math.min(1-hx,cx))-p.width/2;p.y=Math.max(hy,Math.min(1-hy,cy))-p.height/2;return p;
  }
  const sideLabels={FRONT:'正面',BACK:'背面',LEFT:'左侧',RIGHT:'右侧'};
  let drawing=0,lastSceneRenderer=null,lastSceneKey=null;
  async function preview(){return ctx.ensurePreview();}
  function draw(){
   const prior=current();if(prior)faceSelections.set(prior.side,prior.id);
   if(activeSide!==ctx.side()){activeSide=ctx.side();selected=prints.find(p=>p.id===faceSelections.get(activeSide)&&p.side===activeSide)?.id||prints.find(p=>p.side===activeSide)?.id||null;if(current())artworkId=current().artworkId;}
   const locked=isLocked();
   const config=ctx.configuration(),hasReference=!!config.baseImages?.[ctx.side()],p=current(),recovery=pending||(!sourceImageId&&!Object.keys(ctx.configuration().baseImages||{}).length?remembered():null);
   panelEmpty.hidden=hasReference;panel.dataset.hasPrints=String(prints.length>0);
   resume.hidden=!recovery||(recovery.referenceSetId===referenceSetId&&!!referenceSetId);resume.disabled=locked;
   resume.textContent=locked?'正在准备四面参考…':recovery?.notStarted?'生成四面参考':'继续准备四面参考';findSource.disabled=locked;
   useTest.disabled=!hasReference||locked||!!legacyConfiguration||prints.length>=32;useTest.textContent=artworkPreparing?'正在准备花纹…':'使用测试花纹';
   for(const {pattern,button} of patternButtons){button.disabled=locked;button.setAttribute('aria-pressed',String(pattern.id===testPattern));}
   printCount.textContent=prints.length?'已放置 '+prints.length+' / 32 个印花':'尚未添加印花';printList.replaceChildren();
   prints.forEach((item,i)=>{const button=n('button','',printList);button.type='button';button.className='print-placement-item';button.setAttribute('aria-pressed',String(item.id===selected));button.disabled=locked;
    const image=n('img','',button);image.src=assetUrl(item.artworkId);image.alt='';const label=testPatterns.find(pattern=>pattern.sha256===item.artworkId)?.label||'印花';n('span',sideLabels[item.side]+' · '+label+' '+(i+1),button);
    button.addEventListener('click',()=>{selected=item.id;artworkId=item.artworkId;ctx.preview()?.setPlacementMode?.(false);ctx.setSide(item.side);draw();});
   });
   select.replaceChildren();n('option',prints.length?'选择印花':'尚未放置印花',select).value='';prints.forEach((item,i)=>n('option',sideLabels[item.side]+' · 印花 '+(i+1),select).value=item.id);select.value=selected||'';
   scale.disabled=!p||locked;scale.min='1';scale.value=p?String(Math.max(p.width,p.height)*100):'24';scaleValue.textContent=p?Math.round(Math.max(p.width,p.height)*100)+'%':'—';width.value=p?(p.width*100).toFixed(1):'';height.value=p?(p.height*100).toFixed(1):'';rotation.value=p?(p.rotation*180/Math.PI).toFixed(1):'';
   for(const input of [width,height,rotation])input.disabled=!p||locked;
   remove.disabled=!p||locked;place.disabled=!hasReference||!artworkId||locked||!!legacyConfiguration||prints.length>=32;file.disabled=!hasReference||locked||!!legacyConfiguration;
   select.disabled=locked;undo.disabled=!undoStack.length||locked;redo.disabled=!redoStack.length||locked;
   artworkPreview.hidden=!artworkId;if(artworkId)artworkPreview.src=assetUrl(artworkId);else artworkPreview.removeAttribute('src');
   legacyNotice.hidden=!legacyConfiguration;convertLegacy.disabled=locked;
   drawSource();const generation=++drawing,ticket=ctx.revision();
   if(Object.keys(config.baseImages||{}).length||sourceUrl)preview().then(renderer=>{
    if(!renderer||generation!==drawing||ticket!==ctx.revision())return;
    const scene={baseImages:config.baseImages,side:ctx.side(),prints,sourceUrl},key=JSON.stringify(scene);
    // Picking an existing print must not reset an in-progress pointer gesture.
    if(renderer===lastSceneRenderer&&key===lastSceneKey){renderer.setSelectedPrint?.(selected);return;}
    lastSceneRenderer=renderer;lastSceneKey=key;
    return renderer.setScene({...scene,selected,artworkUrls:new Map(prints.map(p=>[p.artworkId,assetUrl(p.artworkId)]))}).catch(error=>{lastSceneKey=null;throw error;});
   }).catch(error=>{if(generation===drawing)note.textContent='图片预览未完成：'+error.message;});
   ctx.lockSource?.(locked);host.style.pointerEvents=locked?'none':'';ctx.stateChanged?.();
  }
  function restore(config){
   pending=null;faceSelections.clear();activeSide=null;
   sourceImageId=config.sourceImageId||null;referenceSetId=config.referenceSetId||null;prints=structuredClone(config.photoPrints||[]);
   renders={...emptyRenders(),...Object.fromEntries(Object.keys(emptyRenders()).map(k=>[k,config[k]??emptyRenders()[k]]))};
   selected=prints[0]?.id||null;artworkId=prints[0]?.artworkId||null;legacyConfiguration=config._legacyConfiguration||null;ctx.apply(config);ctx.changed();draw();
  }
  undo.addEventListener('click',()=>{if(isLocked())return;if(resizeBefore){checkpoint(resizeBefore);resizeBefore=null;}if(!undoStack.length)return;redoStack.push(snapshot());restore(undoStack.pop());note.textContent='已撤回上一步。';});
  redo.addEventListener('click',()=>{if(!redoStack.length||isLocked())return;undoStack.push(snapshot());restore(redoStack.pop());note.textContent='已恢复。';});
  root.addEventListener('keydown',event=>{if(!event.ctrlKey&&!event.metaKey)return;if(/INPUT|TEXTAREA|SELECT/.test(event.target?.tagName||''))return;if(event.key.toLowerCase()==='z'){event.preventDefault();(event.shiftKey?redo:undo).click();}});
  select.addEventListener('change',()=>{selected=select.value;const p=current();if(p){artworkId=p.artworkId;ctx.setSide(p.side);}ctx.preview()?.setPlacementMode?.(false);draw();});
  scale.addEventListener('input',()=>{const p=current(),value=Number(scale.value);if(!p||isLocked()||!Number.isFinite(value)||value<Number(scale.min)||value>100)return;resizeBefore||=snapshot();const factor=value/100/Math.max(p.width,p.height);p.width*=factor;p.height*=factor;constrainPrint(p);renders=emptyRenders();ctx.changed();draw();});
  scale.addEventListener('change',()=>{if(resizeBefore){checkpoint(resizeBefore);resizeBefore=null;draw();}});
  for(const [input,key,convert] of [[width,'width',v=>v/100],[height,'height',v=>v/100],[rotation,'rotation',v=>v*Math.PI/180]])input.addEventListener('change',()=>{
   const p=current(),v=Number(input.value);if(!p||isLocked()||!Number.isFinite(v))return;
   if(key==='rotation'?Math.abs(v)>360:v<1||v>100){draw();return;}const before=snapshot();p[key]=convert(v);constrainPrint(p);changed(before);
  });
  remove.addEventListener('click',()=>{if(!current()||isLocked())return;const before=snapshot();prints=prints.filter(p=>p.id!==selected);selected=prints.at(-1)?.id||null;artworkId=current()?.artworkId||artworkId;changed(before);});
  for(const {pattern,button} of patternButtons)button.addEventListener('click',()=>{if(isLocked())return;testPattern=pattern.id;draw();});
  async function chooseArtwork(imageFactory,{fileName,test=false,fillSelected=false}={}){
   if(isLocked())return;const ticket=ctx.revision(),operation=++attempt,company=org();
   const active=()=>ticket===ctx.revision()&&operation===attempt&&company===org();
   const fillId=fillSelected?current()?.id:null;
   working=true;artworkPreparing=test;draw();note.textContent=test?'正在读取并保存测试花纹…':'正在保存印花素材…';
   try{
    const image=await imageFactory();if(!active())return;
    const form=new FormData();if(fileName)form.append('file',image,fileName);else form.append('file',image);
    const r=await request('/api/v1/commerce-print-templates/base-images',{method:'POST',body:form});
    if(!active())return;if(!/^[a-f0-9]{64}$/.test(r?.data?.id||''))throw new Error('印花素材没有保存成功，请重试');
    const renderer=await preview();if(!active())return;
    if(fillId&&current()?.id===fillId){const before=snapshot();current().artworkId=r.data.id;artworkId=r.data.id;renderer?.setPlacementMode?.(false);changed(before);note.textContent='棋盘格已铺满当前选框，请保存模板。';return;}
    artworkId=r.data.id;selected=null;renderer?.setPlacementMode?.(true);
    note.textContent=test?'测试花纹已就绪，点击当前面图片放置；可继续选择其他花纹。':'点击当前面图片上的位置放置印花。PNG 的透明背景会保留。';
   }catch(e){if(active())note.textContent='印花准备未完成：'+e.message+'。可重新选择后重试。';}
   finally{if(operation===attempt){working=false;artworkPreparing=false;file.value='';draw();}}
  }
  useTest.addEventListener('click',()=>{if(useTest.disabled)return;const pattern=testPattern;return chooseArtwork(()=>testPatternPng(pattern),{fileName:'test-pattern-'+pattern+'.png',test:true,fillSelected:pattern==='checker'&&current()?.artworkId==='fe522dfad98c24b65844a62978c6b9c1ccd48a0e4831aced03df6317a7e13a82'});});
  file.addEventListener('change',async()=>{
   const image=file.files[0];if(!image||isLocked())return;if(image.size>8*1024*1024){note.textContent='印花图片不能超过8MiB';file.value='';return;}
   await chooseArtwork(()=>image);
  });
  place.addEventListener('click',async()=>{if(place.disabled)return;const operation=attempt,ticket=ctx.revision();await preview();if(operation!==attempt||ticket!==ctx.revision())return;ctx.preview()?.setPlacementMode?.(true);note.textContent='切换到需要的方向，然后点击图片上的印花位置。';});
  host.addEventListener('print-photo-pick',event=>{
   if(isLocked())return;const data=event.detail||{};if(data.existing){selected=data.printId||data.id;artworkId=current()?.artworkId||artworkId;draw();return;}
   if(!artworkId||!ctx.configuration().baseImages?.[ctx.side()]||prints.length>=32||!Number.isFinite(data.x)||!Number.isFinite(data.y))return;
   const before=snapshot();selected='print-'+crypto.randomUUID();
   prints.push({id:selected,artworkId,side:ctx.side(),x:Math.max(0,Math.min(.76,data.x-.12)),y:Math.max(0,Math.min(.76,data.y-.12)),width:.24,height:.24,rotation:0});
   ctx.preview()?.setPlacementMode?.(false);changed(before);note.textContent='印花已放置。拖图案移动，拖角点缩放；也可输入尺寸、旋转或撤回。';
  });
  host.addEventListener('print-photo-change',event=>{
   if(isLocked()||!event.detail?.commit)return;const {id,print}=event.detail,index=prints.findIndex(p=>p.id===id);if(index<0)return;
   const before=snapshot();prints[index]={...prints[index],...print};selected=id;changed(before);
  });
  async function watch(job,operation,ticket){
   let data=job;const started=Date.now();let failures=0;
   while(operation===attempt&&ticket===ctx.revision()){
    pending={...(pending||{}),id:data.id,sourceImageId:data.originalImageId||pending?.sourceImageId,referenceSetId:data.referenceSetId||pending?.referenceSetId};jobStatus(data.message||data.stage||'正在生成参考图…',data.status);draw();
    if(data.status==='READY'){
     if(!data.baseImages||!Object.keys(data.baseImages).length)throw new Error('参考图任务已完成，但图片暂不可读取，请恢复原任务重试');
     const same=referenceSetId===data.referenceSetId&&sourceImageId===data.originalImageId;
     if(!same){const before=snapshot();sourceImageId=data.originalImageId;referenceSetId=data.referenceSetId;prints=[];renders=emptyRenders();selected=null;artworkId=null;legacyConfiguration=null;ctx.applyReferences(data.baseImages);checkpoint(before);}
     else ctx.applyReferences(data.baseImages);
     ctx.setUploading(false);detach.hidden=true;jobStatus('四面参考已就绪，选择花纹后直接点击图片放置。','READY');note.textContent='拖动图案移动，拉动角点缩放，顶部圆点旋转；支持多个花纹与撤回。';draw();return;
    }
    if(['FAILED','UNKNOWN'].includes(data.status)){
     retry.hidden=!data.retryable;note.textContent=data.message||'参考图未完成';jobStatus(data.status==='UNKNOWN'?'原图已保存，生图结果待核查。恢复任务只查询结果，不重复提交生图。':(data.message||'参考图生成失败')+'；原图已保留。');return;
    }
    if(Date.now()-started>20*60*1000){jobStatus('后台仍在处理，原图已保留。稍后点击「继续准备四面参考」恢复原任务。');return;}
    await new Promise(resolve=>setTimeout(resolve,2000));if(operation!==attempt||ticket!==ctx.revision())return;
    try{data=(await request('/api/v1/commerce-print-templates/references/'+data.id)).data;failures=0;}
    catch(e){if(++failures>=5)throw e;jobNote.textContent='连接暂时中断，正在重新查询原任务…';}
   }
  }
  async function runPending({retryJob=false}={}){
   if(isLocked())return;pending=pending||remembered();if(!pending){jobNote.textContent='没有待恢复的任务，请上传商品图。';return;}
   const operation=++attempt,ticket=ctx.revision();working=true;ctx.setUploading(true);detach.hidden=false;retry.hidden=true;draw();jobStatus(pending.id?'正在查询原任务…':'原图已保存，正在创建四面参考任务…');
   try{
    if(!pending.id){pending.notStarted=false;remember(pending);}
    const data=pending.id?(await request('/api/v1/commerce-print-templates/references/'+pending.id+(retryJob?'/retry':''),retryJob?{method:'POST'}:{})).data:
      (await request('/api/v1/commerce-print-templates/references',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(pending.request)})).data;
    if(operation!==attempt||ticket!==ctx.revision())return;await watch(data,operation,ticket);
   }catch(e){if(operation===attempt&&ticket===ctx.revision())jobStatus(e.message+'；原图已保留，可恢复同一任务继续查询。');}
   finally{if(operation===attempt){working=false;ctx.setUploading(false);detach.hidden=true;draw();}}
  }
  resume.addEventListener('click',()=>runPending());retry.addEventListener('click',()=>runPending({retryJob:true}));
  findSource.addEventListener('click',async()=>{
   if(isLocked())return;const ticket=ctx.revision(),operation=attempt;findSource.disabled=true;jobNote.textContent='正在查找已保存的原图…';
   try{const response=await request('/api/v1/commerce-samples');if(ticket!==ctx.revision()||operation!==attempt)return;
    const samples=response.data.filter(s=>s.assetKind==='PRODUCT_IMAGE'&&s.model==='UPLOAD'&&s.status==='READY'&&/^[a-f0-9-]{36}$/.test(s.id));sourceHistory.replaceChildren();sourceHistory.hidden=!samples.length;
    for(const sample of samples){
     const button=n('button','',sourceHistory);button.type='button';button.setAttribute('aria-label','找回原图 '+sample.id.slice(0,8));
     const thumbnail=n('img','',button);thumbnail.src='/api/v1/commerce-samples/'+sample.id+'/image';thumbnail.alt='已上传商品原图';n('span','找回此图',button);
     button.addEventListener('click',async()=>{
      if(isLocked())return;const operation=++attempt,ticket=ctx.revision();working=true;draw();jobNote.textContent='正在恢复已保存的原图…';
      try{const response=await fetch('/api/v1/commerce-samples/'+sample.id+'/image',{credentials:'same-origin',...(typeof AbortSignal!=='undefined'&&AbortSignal.timeout?{signal:AbortSignal.timeout(30000)}:{})});if(!response.ok)throw new Error('原图读取失败，请稍后重试');
       const bytes=await response.arrayBuffer(),digest=await crypto.subtle.digest('SHA-256',bytes),sourceId=Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join('');
       if(operation!==attempt||ticket!==ctx.revision())return;
       releaseLocalSource();const previous=remembered();pending=previous?.request?.sourceImageId===sourceId?previous:{fileName:'已上传商品原图',notStarted:true,request:{sourceImageId:sourceId,alreadyPlain:!!ctx.isPlain?.(),requestId:crypto.randomUUID()}};
       jobStatus(recoveryMessage(pending),pending.status||'UPLOADED');sourceHistory.hidden=true;
      }catch(e){if(operation===attempt&&ticket===ctx.revision())jobNote.textContent=e.message;}
      finally{if(operation===attempt){working=false;draw();}}
     });
    }
    jobNote.textContent=samples.length?'选择要找回的原图。找回图片不会重复生图。':'暂未找到可恢复的原图，请上传商品图片。';
   }catch(e){if(ticket===ctx.revision()&&operation===attempt)jobNote.textContent=e.message;}
   finally{if(operation===attempt)draw();}
  });
  detach.addEventListener('click',()=>{attempt++;working=false;ctx.setUploading(false);detach.hidden=true;jobStatus('已停止等待。原图与后台任务保留，可点击恢复继续查询。');draw();});
  async function uploadSource(image,alreadyPlain){
   if(isLocked())return;if(image.size>8*1024*1024)throw new Error('商品图片不能超过8MiB');
   const operation=++attempt,ticket=ctx.revision();releaseLocalSource();localSource={url:URL.createObjectURL(image),fileName:image.name};pending=null;working=true;ctx.setUploading(true);retry.hidden=true;draw();jobNote.textContent='正在保存商品原图…';
   try{const form=new FormData();form.append('file',image);const result=await request('/api/v1/commerce-print-templates/base-images',{method:'POST',body:form});
    if(operation!==attempt||ticket!==ctx.revision())return;
    pending={fileName:image.name,request:{sourceImageId:result.data.id,alreadyPlain,requestId:crypto.randomUUID()}};releaseLocalSource();jobStatus('原图已保存，准备生成四面参考图…','UPLOADED');ctx.defaultName(image.name.replace(/\.[^.]+$/,''));
   }catch(e){if(operation===attempt){if(localSource)localSource.error=true;jobNote.textContent='原图上传未完成：'+e.message+'。图片仍在预览中，请重新上传。';}return;}
   finally{if(operation===attempt){working=false;ctx.setUploading(false);draw();}}
   if(operation===attempt&&ticket===ctx.revision())await runPending();
  }
  function saveBlocker(config=ctx.configuration()){
   if(legacyConfiguration)return '此历史模板使用空间印花坐标，请点击「在四面图重新设置印花」后另存新版本；原版本会保留';
   const legacyBases=!sourceImageId&&Object.keys(config.baseImages||{}).length>0;
   const record=pending||(!sourceImageId&&!legacyBases?remembered():null);
   if(localSource?.error)return '商品图上传未完成，请重新上传后保存';
   if(localSource)return '商品图已保留，请完成四面参考后保存';
   const expectedSource=record?.request?.sourceImageId||record?.sourceImageId;
   if(record&&(!record.referenceSetId||record.referenceSetId!==referenceSetId||expectedSource!==sourceImageId))return '商品图已保留，请完成四面参考后保存';
   if(!Object.keys(config.baseImages||{}).length)return '请先准备商品参考图，再保存模板';
   if(!prints.length)return '请先在参考图上放置至少一个印花，再保存模板';
   return null;
  }
  async function prepareSave(config){
   const blocker=saveBlocker(config);if(blocker){note.textContent=blocker;ctx.stateChanged?.();throw new Error(blocker);}
   const result={...config,...fields()};if(!prints.length)return result;
   const operation=++attempt,ticket=ctx.revision();const active=()=>operation===attempt&&ticket===ctx.revision();
   working=true;draw();note.textContent='正在保存四面效果与印花定位…';
   try{
    const renderer=await preview();if(!active())throw new Error('模板已切换，已停止保存');if(!renderer?.exportSurfaceGuides)throw new Error('图片印花组件未就绪，请刷新页面');
    await renderer.setScene({baseImages:config.baseImages,side:ctx.side(),prints,selected,artworkUrls:new Map(prints.map(p=>[p.artworkId,assetUrl(p.artworkId)])),sourceUrl});
    if(!active())throw new Error('模板已切换，已停止保存');
    const guides=await renderer.exportSurfaceGuides({size:1024});
    if(!active())throw new Error('模板已切换，已停止保存');
    if(JSON.stringify(guides.printIds)!==JSON.stringify(prints.map(p=>p.id)))throw new Error('印花已变化，请重新保存');
    if(guides.invisiblePrintIds?.length)throw new Error('有印花素材透明或超出图片范围，无法生成定位图。请检查素材并调整位置后保存。');
    const form=new FormData();form.append('configuration',JSON.stringify(result));
    for(const side of Object.keys(guides.views))for(const key of ['image','blank','mask','uv'])form.append(key+'_'+side,guides.views[side][key],side+'-'+key+'.png');
    const response=await api('/api/v1/commerce-print-templates/rendered-views',{method:'POST',body:form});
    if(!active())throw new Error('模板已切换，已停止保存');renders=response.data;note.textContent='四面效果与精确印花定位已归档。';return {...result,...renders};
   }finally{if(operation===attempt){working=false;draw();}}
  }
  function load(config){faceSelections.clear();activeSide=null;resizeBefore=null;attempt++;working=false;artworkPreparing=false;testPattern='starburst';releaseLocalSource();sourceHistory.replaceChildren();sourceHistory.hidden=true;ctx.setUploading(false);undoStack=[];redoStack=[];pending=null;
   sourceImageId=config.sourceImageId||null;referenceSetId=config.referenceSetId||null;prints=structuredClone(config.photoPrints||[]);renders={...emptyRenders()};legacyConfiguration=(config.surfacePrints?.length||config.areas?.length)?structuredClone(config):null;
   for(const key of Object.keys(renders))if(config[key]!=null)renders[key]=config[key];selected=prints[0]?.id||null;artworkId=prints[0]?.artworkId||null;note.textContent=prints.length?'已恢复四面图上的印花位置，可继续编辑。':Object.keys(config.baseImages||{}).length?'选择测试花纹后点击图片放置，可添加多个图案。':'先准备四面参考，再添加印花。';retry.hidden=true;detach.hidden=true;jobNote.textContent=!sourceImageId&&remembered()?recoveryMessage(remembered()):'准备同组四面参考后，直接在图片上放置印花。';draw();
  }
  function reset(){load({});jobNote.textContent='准备同组四面参考后，直接在图片上放置印花。';retry.hidden=true;detach.hidden=true;}
  let autoRecoveryKey=null;
  const refreshRecovery=()=>{
   draw();const record=remembered();if(!working&&!sourceImageId&&!localSource&&!pending&&record){jobNote.textContent=recoveryMessage(record);
    const key=org()+':'+record.id;
    // READY recovery only queries its existing task; it never creates another image request.
    if(record.id&&record.status==='READY'&&!Object.keys(ctx.configuration().baseImages||{}).length&&autoRecoveryKey!==key){autoRecoveryKey=key;Promise.resolve().then(()=>runPending());}
   }
  };
  // Login finishes after deferred scripts; restore the company-qualified recovery entry then.
  if(typeof MutationObserver!=='undefined'){
   const dashboard=document.getElementById('dashboard-view');
   if(dashboard)new MutationObserver(refreshRecovery).observe(dashboard,{attributes:true,attributeFilter:['hidden']});
  }
  window.addEventListener('hashchange',refreshRecovery);
  Promise.resolve().then(refreshRecovery);
  return {fields,saveBlocker,hasUnfinishedSource:()=>!!localSource||!!pending&&(!pending.referenceSetId||pending.referenceSetId!==referenceSetId||(pending.request?.sourceImageId||pending.sourceImageId)!==sourceImageId),isWorking:()=>working,uploadSource,prepareSave,load,reset,draw,
   saved(config){const record=pending||remembered();if(record?.referenceSetId&&record.referenceSetId===config.referenceSetId&&(record.request?.sourceImageId||record.sourceImageId)===config.sourceImageId){remember(null);pending=null;jobNote.textContent='商品原图、四面参考与印花位置已保存到模板。';draw();}},
   invalidate(){prints=[];renders=emptyRenders();referenceSetId=null;selected=null;legacyConfiguration=null;draw();}
  };
 }};
})();
