/* Four-view photo layout editor. The source and immutable template versions are kept separately. */
(() => {
 const root=document.getElementById('commerce-print-editor');if(!root)return;
 const n=(tag,value,parent=root)=>{const e=document.createElement(tag);e.textContent=value;parent.append(e);return e;};
 const faces={FRONT:'正面',BACK:'背面',LEFT:'左侧',RIGHT:'右侧'};
 let surfaceEditor=null,preview=null,previewPromise=null,templates=[],id=null,version=0,snapshotId=null;
 let revision=0,edit=0,savedEdit=-1,side='FRONT',baseImages={},sourcePreviewUrl=null,product='T_SHIRT';
 let busy=false,uploading=false,workflowLoading=false,workflowLoadGeneration=0,pendingWorkflowSnapshot=null;const imageAspects=new Map();
 const library=n('details','');library.className='print-editor-library';n('summary','导入已有版型',library);
 const tools=n('div','',library);tools.className='print-tools print-editor-tools';
 const name=n('input','',tools);name.placeholder='印花模板名称';name.setAttribute('aria-label','印花模板名称');name.className='print-internal-name';
 const saved=n('select','',tools);saved.setAttribute('aria-label','已保存印花模板');
 const read=n('button','读取模板',tools),save=n('button','保存新版本',tools),fresh=n('button','另存为新模板',tools);save.className='print-internal-save';fresh.className='print-internal-fresh';
 const tableWrap=n('div','',library);tableWrap.className='print-table-wrap';const table=n('table','',tableWrap);table.className='print-template-table';
 const head=n('tr','',n('thead','',table));['模板名称','参考图','印花位置','版本','操作'].forEach(value=>n('th',value,head));const tbody=n('tbody','',table);
 const layout=n('div','');layout.className='print-layout';const stage=n('div','',layout),sidebar=n('aside','',layout);stage.className='print-stage';sidebar.className='print-inspector';sidebar.setAttribute('aria-label','商品与印花设置');
 const stageToolbar=n('div','',stage);stageToolbar.className='print-stage-toolbar';const faceTools=n('div','',stageToolbar);faceTools.className='print-tools print-view-faces';faceTools.setAttribute('role','group');faceTools.setAttribute('aria-label','编辑方向');
 const faceButtons=Object.entries(faces).map(([key,label])=>{const button=n('button',label,faceTools);button.addEventListener('click',()=>setSide(key));return {key,button};});
 const stageTag=n('span','直接在参考图上设置印花',stageToolbar);stageTag.className='print-stage-tag';
 const stageBody=n('div','',stage);stageBody.className='print-stage-body';const host=n('div','',stageBody);host.className='print-photo-host';host.setAttribute('aria-label','四面图片印花编辑画布');host.tabIndex=0;
 const empty=n('div','',stageBody);empty.className='print-stage-empty';n('span','＋',empty).className='print-empty-icon';n('h3','从一张商品图开始',empty);n('p','生成同组四面参考，再把印花放到想要的位置。',empty);const emptyUpload=n('button','上传商品图片',empty);emptyUpload.className='print-empty-upload';
 const caption=n('div','',stage);caption.className='print-stage-caption';const mode=n('span','正面参考图',caption),hint=n('span','上传商品原图开始',caption);
 const referenceStrip=n('div','',stage);referenceStrip.className='print-reference-strip';referenceStrip.setAttribute('aria-label','四面参考图');referenceStrip.hidden=true;
 const section=(number,title,className)=>{const node=n('section','',sidebar);node.className=className+' print-inspector-section';const heading=n('div','',node);heading.className='print-section-heading';n('span',number,heading).className='print-step-number';n('h3',title,heading);return {node,heading};};
 const {node:baseTools}=section('01','商品底图','print-base-tools print-upload-sidebar');
 const uploadLabel=n('label','选择 / 更换商品图片',baseTools);uploadLabel.className='print-source-upload';const baseFile=n('input','',uploadLabel);baseFile.type='file';baseFile.accept='image/png,image/jpeg';baseFile.setAttribute('aria-label','上传商品图片');emptyUpload.addEventListener('click',()=>baseFile.click());
 const plainLabel=n('label','原图已经是无印花的纯色款',baseTools);plainLabel.className='print-plain-toggle';const plain=n('input','',plainLabel);plain.type='checkbox';
 const {node:referencePanel,heading:referenceHeading}=section('02','四面参考','print-reference-panel print-model-panel');referencePanel.setAttribute('aria-label','四面参考图准备');
 const referenceBadge=n('span','等待图片',referenceHeading);referenceBadge.className='print-reference-badge';n('p','同一张参考图裁成正、背、左、右四面，保留一致的版型。',referencePanel).className='print-section-description';
 const referenceActions=n('div','',referencePanel);referenceActions.className='print-model-actions';
 const recoveryActions=n('div','');recoveryActions.className='print-editor-recovery';recoveryActions.hidden=true;
 const retryLinked=n('button','重新载入关联模板',recoveryActions),saveThenReload=n('button','保存当前改动后载入',recoveryActions);retryLinked.id='print-linked-retry';saveThenReload.id='print-linked-save-retry';
 const status=n('p','上传商品图片后，在四面参考图上拖动、缩放或旋转印花。');status.className='print-editor-status';status.setAttribute('role','status');
 root.querySelectorAll('button').forEach(button=>button.type='button');
 const configuration=()=>({product,areas:[],baseImages:{...baseImages},reconstruction:null,surfacePrints:[]});
 function getState(){const fields=surfaceEditor?.fields()||{};return {snapshotId:pendingWorkflowSnapshot||snapshotId,name:name.value,dirty:!!pendingWorkflowSnapshot||savedEdit!==edit,hasContent:!!(sourcePreviewUrl||fields.sourceImageId||Object.keys(baseImages).length||snapshotId),busy:busy||uploading||workflowLoading||!!surfaceEditor?.isWorking?.(),hasSource:!!(sourcePreviewUrl||fields.sourceImageId||Object.keys(baseImages).length),hasModel:false,saveBlocker:surfaceEditor?.saveBlocker?.()||null,pendingSnapshotId:pendingWorkflowSnapshot,printCount:fields.photoPrints?.length||0,status:status.textContent};}
 function hasUnsavedEditorContent(){return edit>0&&savedEdit!==edit||!!surfaceEditor?.hasUnfinishedSource?.();}
 function notifyState(){const state=getState(),dirty=hasUnsavedEditorContent();baseFile.disabled=plain.disabled=read.disabled=saved.disabled=fresh.disabled=state.busy;recoveryActions.hidden=!pendingWorkflowSnapshot;retryLinked.hidden=dirty;saveThenReload.hidden=!dirty;retryLinked.disabled=saveThenReload.disabled=state.busy;retryLinked.textContent=workflowLoading?'正在载入关联模板…':'重新载入关联模板';if(typeof CustomEvent==='function')window.dispatchEvent(new CustomEvent('heyi:print-template-state-change',{detail:state}));}
 window.CommercePrintTemplateSelection=()=>({snapshotId:pendingWorkflowSnapshot||snapshotId,name:name.value,dirty:getState().dirty||getState().busy});
 async function ensurePreview(){
  if(preview)return preview;
  if(!previewPromise){previewPromise=import('/vendor/photo-print-preview.js?v=photo-layout-20260916-1').then(module=>{preview=module.createPhotoPrintPreview(host);return preview;}).finally(()=>{previewPromise=null;});}
  return previewPromise;
 }
 function setSide(value){if(!faces[value]||busy||uploading||surfaceEditor?.isWorking?.())return;side=value;preview?.setSide(side);draw();}
 function renderStage(){
  const hasReference=!!baseImages[side],hasSource=!!sourcePreviewUrl,hasAny=Object.keys(baseImages).length>0,locked=uploading||busy||!!surfaceEditor?.isWorking?.();
  empty.hidden=hasAny||hasSource;host.hidden=!hasAny&&!hasSource;emptyUpload.disabled=locked;
  mode.textContent=faces[side]+'参考图';hint.textContent=hasReference?'点击放置 · 拖动移动 · 拉角缩放 · 顶部圆点旋转':hasAny?'当前方向的参考图暂未准备好':hasSource?'商品原图已保存，正在准备四面参考':'上传商品原图开始';
  referenceBadge.textContent=uploading?'准备中':hasAny?Object.keys(baseImages).length+' / 4 面已就绪':'等待图片';
  for(const {key,button} of faceButtons){button.setAttribute('aria-pressed',String(key===side));button.disabled=locked||!baseImages[key];}
  referenceStrip.hidden=!hasAny;referenceStrip.replaceChildren();
  for(const face of Object.keys(faces)){
   const button=n('button','',referenceStrip);button.type='button';button.className='print-reference-thumb';button.setAttribute('aria-label','查看'+faces[face]+'参考图');button.setAttribute('aria-pressed',String(side===face));button.disabled=!baseImages[face]||locked;
   if(baseImages[face]){const img=n('img','',button);img.src='/api/v1/commerce-print-templates/base-images/'+baseImages[face];img.alt=faces[face]+'参考图';img.addEventListener('load',()=>{if(img.naturalWidth&&img.naturalHeight)imageAspects.set(img.src,img.naturalWidth/img.naturalHeight);});}else n('span','待生成',button).className='print-reference-pending';
   n('span',faces[face],button);button.addEventListener('click',()=>setSide(face));
  }
 }
 function draw(){renderStage();surfaceEditor?.draw();notifyState();}
 function renderSavedOptions(){saved.replaceChildren();n('option','选择模板',saved).value='';templates.forEach(row=>n('option',`${row.name} · v${row.version}`,saved).value=row.snapshotId);saved.value=snapshotId||'';}
 function renderTable(){tbody.replaceChildren();for(const row of templates){const config=row.configuration||{},tr=n('tr','',tbody),count=config.photoPrints?.length||config.surfacePrints?.length||config.areas?.length||0;[row.name,Object.keys(config.baseImages||{}).length+' 面',String(count),`v${row.version}`].forEach(value=>n('td',value,tr));const button=n('button','编辑',n('td','',tr));button.type='button';button.addEventListener('click',()=>{saved.value=row.snapshotId;saved.dispatchEvent(new Event('change'));layout.scrollIntoView({block:'start',behavior:'instant'});});}}
 function loadRow(row){
  workflowLoadGeneration++;pendingWorkflowSnapshot=null;workflowLoading=false;revision++;preview?.dispose();preview=null;
  const config=row.configuration||{};baseImages={...(config.baseImages||{})};product=config.product||'T_SHIRT';id=row.templateId;version=row.version;snapshotId=row.snapshotId;name.value=row.name;side=config.photoPrints?.[0]?.side||Object.keys(baseImages)[0]||'FRONT';
  surfaceEditor?.load(config);edit++;savedEdit=edit;draw();status.textContent=`已载入 ${row.name} · v${row.version}。`;notifyState();
 }
 async function loadWorkflowSnapshot(requested){
  const generation=++workflowLoadGeneration;workflowLoading=false;pendingWorkflowSnapshot=null;
  if(!requested){revision++;if(savedEdit===edit||edit===0)savedEdit=edit;snapshotId=null;saved.value='';status.textContent=getState().hasContent?'当前图片与位置已保留，保存模板时一并关联。':'';notifyState();return;}
  if(requested===snapshotId){notifyState();return;}
  if(busy||uploading||surfaceEditor?.isWorking?.()||hasUnsavedEditorContent()){pendingWorkflowSnapshot=requested;status.textContent='当前版型有未保存改动，可先保存当前改动，再载入关联模板。';notifyState();return;}
  pendingWorkflowSnapshot=requested;workflowLoading=true;const ticket=revision,edited=edit;status.textContent='正在载入关联的四面图片与印花位置…';notifyState();
  try{const row=templates.find(row=>row.snapshotId===requested)||(await api('/api/v1/commerce-print-templates/snapshots/'+encodeURIComponent(requested))).data;if(generation!==workflowLoadGeneration||ticket!==revision||edited!==edit)return;if(!row||row.snapshotId!==requested||!row.configuration)throw new Error('关联的商品印花模板不可用');if(!templates.some(t=>t.snapshotId===row.snapshotId))templates.push(row);loadRow(row);renderSavedOptions();renderTable();}
  catch(error){if(generation===workflowLoadGeneration&&ticket===revision)status.textContent='关联模板读取失败：'+error.message+'。点击重新载入可重试，当前内容已保留。';}
  finally{if(generation===workflowLoadGeneration||!pendingWorkflowSnapshot){workflowLoading=false;notifyState();}}
 }
 window.addEventListener('heyi:workflow-print-template-selected',event=>loadWorkflowSnapshot(event.detail?.snapshotId));
 retryLinked.addEventListener('click',()=>pendingWorkflowSnapshot&&loadWorkflowSnapshot(pendingWorkflowSnapshot));
 saveThenReload.addEventListener('click',async()=>{const requested=pendingWorkflowSnapshot,ticket=revision;if(!requested)return;try{await saveTemplate({},true);if(ticket===revision&&requested===pendingWorkflowSnapshot)await loadWorkflowSnapshot(requested);}catch(error){if(ticket===revision){status.textContent=error.message;notifyState();}}});
 library.addEventListener('toggle',()=>{if(library.open&&!templates.length)read.click();});
 read.addEventListener('click',async()=>{const ticket=revision;read.disabled=true;try{const response=await api('/api/v1/commerce-print-templates');if(ticket!==revision)return;templates=response.data;renderSavedOptions();renderTable();status.textContent=`已读取 ${templates.length} 套模板`;}catch(error){if(ticket===revision)status.textContent=error.message;}finally{read.disabled=false;}});
 saved.addEventListener('change',()=>{const row=templates.find(row=>row.snapshotId===saved.value);if(!row)return;if(getState().busy||hasUnsavedEditorContent()){saved.value=snapshotId||'';status.textContent='请先保存当前改动，再导入另一套版型。';return;}loadRow(row);});
 fresh.addEventListener('click',()=>{if(getState().busy)return;workflowLoadGeneration++;pendingWorkflowSnapshot=null;revision++;id=null;version=0;snapshotId=null;savedEdit=-1;name.value='';saved.value='';edit++;status.textContent='将以当前配置另存一套模板，历史版本不覆盖。';notifyState();});
 name.addEventListener('input',()=>{edit++;notifyState();});
 baseFile.addEventListener('change',async()=>{const image=baseFile.files[0];if(!image||uploading)return;if(!surfaceEditor){status.textContent='图片编辑器未载入，请刷新后重试。';return;}try{await surfaceEditor.uploadSource(image,plain.checked);}catch(error){status.textContent=error.message;}finally{if(baseFile.files[0]===image)baseFile.value='';}});
 async function saveTemplate(options={},independentSave=false){
  const refuse=message=>{status.textContent=message;notifyState();throw new Error(message);};
  if(!independentSave&&pendingWorkflowSnapshot)refuse('关联版型尚未载入，请重新载入当前模板后保存');
  const blocker=surfaceEditor?.saveBlocker?.();if(blocker)refuse(blocker);
  if(busy||uploading||surfaceEditor?.isWorking?.())refuse('商品或印花仍在处理中，请完成后保存模板');
  if(typeof options.name==='string'&&options.name.trim()!==name.value){name.value=options.name.trim();edit++;}
  if(!independentSave&&snapshotId&&savedEdit===edit)return templates.find(row=>row.snapshotId===snapshotId)||{templateId:id,snapshotId,version,name:name.value};
  busy=true;save.disabled=true;notifyState();const ticket=revision,edited=edit;
  try{
   let config=configuration();if(surfaceEditor)config=await surfaceEditor.prepareSave(config);
   if(ticket!==revision||edited!==edit)throw new Error('配置已变化，请重新保存');
   if(!independentSave&&pendingWorkflowSnapshot)throw new Error('关联版型尚未载入，请重新载入当前模板后保存');
   const response=await api('/api/v1/commerce-print-templates',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({templateId:id,expectedVersion:version,name:name.value,configuration:config})});
   if(ticket!==revision)throw new Error('模板已切换，原模板已保存，请重新载入查看');
   id=response.data.templateId;version=response.data.version;snapshotId=response.data.snapshotId;savedEdit=edited;workflowLoadGeneration++;surfaceEditor?.saved(config);
   templates=templates.filter(row=>row.templateId!==id).concat(response.data);renderSavedOptions();renderTable();
   if(!independentSave&&(pendingWorkflowSnapshot||edited!==edit))throw new Error('当前版型已保存，但模板选择或内容已变化，请重新载入核对后保存');
   status.textContent=`已保存 v${version}：四面图片与印花位置已关联，可在一键裂变中选择此模板。`;return response.data;
  }catch(error){if(ticket===revision)status.textContent=error.message;throw error;}finally{busy=false;save.disabled=uploading;surfaceEditor?.draw();notifyState();}
 }
 save.addEventListener('click',()=>saveTemplate({},true).catch(()=>{}));
 window.CommercePrintTemplateEditor={save:options=>saveTemplate(options),getState,get dirty(){return getState().dirty;},get status(){return status.textContent;}};
 window.addEventListener('heyi:company-reset',()=>{workflowLoadGeneration++;pendingWorkflowSnapshot=null;revision++;preview?.dispose();preview=null;baseImages={};sourcePreviewUrl=null;id=null;version=0;snapshotId=null;edit=0;savedEdit=-1;busy=false;uploading=false;workflowLoading=false;name.value='';templates=[];saved.replaceChildren();renderTable();surfaceEditor?.reset();status.textContent='';draw();});
 if(window.CommercePrintSurface){surfaceEditor=window.CommercePrintSurface.mount({root,sidebar,host,baseTools,referencePanel,referenceActions,n,
  configuration,stateChanged:notifyState,revision:()=>revision,preview:()=>preview,ensurePreview,isPlain:()=>plain.checked,
  side:()=>side,setSide,isLocked:()=>busy||uploading||workflowLoading,imageAspect:face=>{const suffix='/api/v1/commerce-print-templates/base-images/'+baseImages[face];for(const [url,aspect]of imageAspects)if(url.endsWith(suffix))return aspect;return 1;},organizationId:()=>typeof currentUser!=='undefined'?currentUser?.organizationId:'',
  sourcePreview:url=>{sourcePreviewUrl=url;renderStage();},
  changed:()=>{edit++;notifyState();},defaultName:value=>{if(!name.value)name.value=value.slice(0,120);notifyState();},
  lockSource:value=>{baseFile.disabled=value;},setUploading:value=>{uploading=value;baseFile.disabled=value;save.disabled=busy||value;renderStage();notifyState();},
  applyReferences:images=>{baseImages={...images};if(!baseImages[side])side=Object.keys(baseImages)[0]||'FRONT';edit++;renderStage();},
  apply:config=>{baseImages={...(config.baseImages||{})};product=config.product||'T_SHIRT';if(!baseImages[side])side=Object.keys(baseImages)[0]||'FRONT';renderStage();}
 });}
 draw();
})();
