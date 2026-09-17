(() => {
  const page=document.getElementById('product-publish-page');if(!page)return;
  const select=document.getElementById('workbench-batch-select'),items=document.getElementById('workbench-item-select'),status=document.getElementById('workbench-batch-status');
  let revision=0,batch=null,knownBatches=[];
  const list=document.getElementById('workbench-batch-list');
  const statusLabels={DRAFT:'草稿 · 待执行',COMPLETED:'已完成',RUNNING:'执行中',FAILED:'已停止',PAUSED:'已暂停'};
  function renderBatches(rows){knownBatches=rows;list.replaceChildren();if(!rows.length){const empty=document.createElement('p');empty.textContent='暂无批次任务，点击上方新建。';list.append(empty);}rows.forEach(row=>{const button=document.createElement('button');button.type='button';button.dataset.batchId=row.id;const name=document.createElement('strong'),meta=document.createElement('small');name.textContent=row.name;meta.textContent=statusLabels[row.status]||row.status;button.append(name,meta);button.addEventListener('click',()=>{select.value=row.id;select.dispatchEvent(new Event('change'));});list.append(button);});}
  const option=(parent,value,label)=>{const node=document.createElement('option');node.value=value;node.textContent=label;parent.append(node);};
  const navigate=route=>document.querySelector(route==='product-capture'?'#nav-publish':`[data-commerce-route="${route}"]`).click();
  function reset(){revision++;batch=null;page.classList.remove('has-workbench-batch','workbench-configuring');page.classList.add('workbench-selecting');items.replaceChildren();option(items,'','先选择批次');window.dispatchEvent(new CustomEvent('heyi:workbench-scope-reset'));}
  function chooseItem(){
    const item=batch?.items.find(i=>i.id===items.value);if(!item)return;
    window.dispatchEvent(new CustomEvent('heyi:design-source-selected',{detail:{captureId:item.captureId,captureVersion:item.captureVersion}}));
    list.querySelectorAll('button').forEach(button=>button.setAttribute('aria-current',String(button.dataset.batchId===batch.id)));
    navigate('commerce-designs');
    status.textContent=`当前批次：${batch.name} · 商品版本 v${item.captureVersion}。处理进度以执行状态为准。`;
  }
  function renderOptions(rows){select.replaceChildren();option(select,'','请选择批次');rows.forEach(b=>option(select,b.id,`${b.name} · ${b.status}`));}
  function activate(view,launch={}){
    batch=view;items.replaceChildren();batch.items.forEach(i=>option(items,i.id,`${i.shopName} · 商品 ${i.captureId.slice(0,8)} · v${i.captureVersion}`));
    if(!batch.items.length){status.textContent='此批次暂无商品，暂不能继续创作。';return;}
    items.value=batch.items[0].id;page.classList.remove('workbench-selecting');page.classList.add('has-workbench-batch');
    window.dispatchEvent(new CustomEvent('heyi:workbench-batch-selected',{detail:{batchId:batch.id,creativeOnly:batch.items.every(item=>item.shopId==null),execution:launch.execution,maxModelCalls:launch.maxModelCalls}}));chooseItem();
  }
  async function read(){
    reset();const ticket=revision;select.disabled=true;status.textContent='正在读取当前公司的工作批次…';
    try{const r=await api('/api/v1/commerce-batches');if(ticket!==revision)return;renderBatches(r.data);renderOptions(r.data);status.textContent=r.data.length?'选择批次，继续本批次的商品处理。':'还没有批次，请先新建批次。';}
    catch(e){if(ticket===revision){status.textContent=e.message;list.replaceChildren();const error=document.createElement('p');error.textContent=`读取失败：${e.message}`;error.setAttribute('role','alert');list.append(error);}}finally{if(ticket===revision)select.disabled=false;}
  }
  select.addEventListener('change',async()=>{
    const id=select.value;reset();if(!id)return;const ticket=revision;status.textContent='正在恢复批次商品…';
    try{const r=await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}`);if(ticket!==revision)return;activate(r.data);
    }catch(e){if(ticket===revision)status.textContent=e.message;}
  });
  items.addEventListener('change',chooseItem);
  window.addEventListener('heyi:workbench-design-request',()=>{const item=batch?.items.find(i=>i.id===items.value);if(!item)return;navigate('commerce-designs');window.dispatchEvent(new CustomEvent('heyi:design-source-selected',{detail:{captureId:item.captureId,captureVersion:item.captureVersion}}));});
  document.getElementById('workbench-batch-refresh').addEventListener('click',read);
  const dialog=document.createElement('dialog');dialog.className='workbench-create-dialog fission-create-dialog';dialog.setAttribute('aria-labelledby','workbench-create-title');
  const heading=document.createElement('h2');heading.id='workbench-create-title';heading.textContent='新建裂变上架';
  const close=document.createElement('button');close.type='button';close.textContent='关闭';close.addEventListener('click',()=>dialog.close());
  const dialogHeader=document.createElement('header');dialogHeader.className='fission-dialog-header';dialogHeader.append(heading,close);dialog.append(dialogHeader);document.body.append(dialog);
  const batchForm=document.getElementById('commerce-batch-form'),batchStatus=document.getElementById('commerce-batch-status');
  dialog.append(batchForm,batchStatus);
  document.getElementById('workbench-batch-new').addEventListener('click',()=>{dialog.showModal();document.getElementById('commerce-batch-refresh').click();});
  window.addEventListener('heyi:batch-created',async event=>{
    dialog.close();const created=event.detail?.batch,id=event.detail?.batchId;
    if(!id)return;
    if(created?.id===id&&Array.isArray(created.items)&&created.items.length){
      // The launch response is already committed. Show its progress immediately,
      // even when the independent batch list request is slow or unavailable.
      reset();const ticket=revision;
      const rows=[created,...knownBatches.filter(row=>row.id!==id)];renderBatches(rows);renderOptions(rows);select.value=id;select.disabled=false;
      activate(created,event.detail);
      try{const r=await api('/api/v1/commerce-batches');if(ticket!==revision)return;
        const rows=r.data.some(row=>row.id===id)?r.data:[created,...r.data];renderBatches(rows);renderOptions(rows);select.value=id;
        list.querySelectorAll('button').forEach(button=>button.setAttribute('aria-current',String(button.dataset.batchId===id)));
      }catch(error){if(ticket===revision)status.textContent=`本批次已创建，执行进度会继续刷新。批次列表读取失败：${error.message}`;}
      return;
    }
    // Keep compatibility with the separate creative-only draft dialog.
    const ticket=revision+1;await read();if(ticket!==revision)return;
    select.value=id;select.dispatchEvent(new Event('change'));
  });
  window.addEventListener('heyi:company-reset',()=>dialog.close());
  document.getElementById('workbench-template').addEventListener('click',()=>{page.classList.remove('workbench-selecting');page.classList.add('workbench-configuring');navigate('commerce-templates');});
  window.addEventListener('heyi:company-reset',()=>{reset();knownBatches=[];list.replaceChildren();select.replaceChildren();option(select,'','请选择批次');status.textContent='公司已切换，请重新读取批次。';select.disabled=false;});
  reset();
  let loaded=false;
  const panel=document.getElementById('commerce-design-panel');
  function autoRead(){if(!loaded&&!page.hidden&&!panel.hidden){loaded=true;read();}}
  const observer=new MutationObserver(autoRead);
  [page,panel].forEach(node=>observer.observe(node,{attributes:true,attributeFilter:['hidden']}));
  window.addEventListener('heyi:company-reset',()=>{loaded=false;});
  autoRead();
})();

// Poll persisted execution state; changing the selected batch never cancels its job.
(() => {
 const hub=document.querySelector('.commerce-batch-hub');if(!hub)return;
 const box=document.createElement('section');box.className='workbench-execution';
 const message=document.createElement('p');message.setAttribute('role','status');
 const limitLabel=document.createElement('label');limitLabel.textContent='本任务最多调用图片模型（次，非金额预算）';const callLimit=document.createElement('input');callLimit.type='number';callLimit.min='0';callLimit.max='1000';callLimit.step='1';callLimit.value='0';limitLabel.append(callLimit);const limitMessage=document.createElement('small');
 const retryNote=document.createElement('small'),artworkBudgetNote='透明印花使用 IMG2.0，每张最多 3 次尝试并计入调用上限；成功即停止。仅透明检查失败或明确的临时服务故障自动重试，结果不确定时不会重复调用。';retryNote.textContent=artworkBudgetNote;
 const start=document.createElement('button'),pause=document.createElement('button'),resume=document.createElement('button'),rebuild=document.createElement('button'),recover=document.createElement('button');
 start.textContent='开始自动执行';pause.textContent='暂停';resume.textContent='恢复执行';
 rebuild.textContent='重新套版';rebuild.title='复用已有印花，重新处理透明边缘并套版，不重新生图';
 recover.textContent='核对已存结果并恢复';recover.type='button';recover.hidden=true;
 [start,pause,resume,rebuild].forEach(b=>{b.type='button';b.disabled=true;});
 box.append(message,limitLabel,limitMessage,retryNote,start,pause,resume,rebuild,recover);document.querySelector('.studio-journey').append(box);box.hidden=true;
 let batchId=null,revision=0,timer=null,busy=false,creativePlanVersion=0,planReady=false,currentRun=null,stateKnown=false,preparationError='',creativeOnly=false;
 const stages={CAPTURE:'来源校验',DETECT:'提取前检查',EXTRACT:'提取印花',VARIATE:'印花裂变',MOCKUP:'商品套版',REVIEW:'自动检查',PUBLISH:'上传草稿'};
 const steps={CAPTURE:'extract',DETECT:'extract',EXTRACT:'extract',VARIATE:'design',MOCKUP:'mockup',REVIEW:'mockup',PUBLISH:'publish'};
 const canRebuild=run=>['WAITING_INPUT','PAUSED','FAILED'].includes(run?.status)&&['MOCKUP','REVIEW','PUBLISH'].includes(run?.stage);
 function render(run){
  currentRun=run;
  limitLabel.hidden=!!run;callLimit.disabled=busy||!!run;
  box.hidden=!batchId;
  start.hidden=!!run;pause.hidden=!['QUEUED','RUNNING'].includes(run?.status);resume.hidden=!['PAUSED','WAITING_INPUT','WAITING_CAPABILITY'].includes(run?.status);recover.hidden=run?.status!=='UNKNOWN';recover.disabled=busy;
  rebuild.hidden=!canRebuild(run);rebuild.disabled=busy||!stateKnown||!canRebuild(run);
  window.dispatchEvent(new CustomEvent('heyi:execution-state',{detail:{run}}));
  start.disabled=busy||!batchId||!!run||!planReady||!stateKnown;
  pause.disabled=busy||!['QUEUED','RUNNING'].includes(run?.status);
  resume.disabled=busy||!['PAUSED','WAITING_INPUT','WAITING_CAPABILITY'].includes(run?.status);
  message.textContent=run?`${run.status==='COMPLETED'?(run.stage==='PUBLISH'?'已上传草稿':'创作完成'):run.status==='FAILED'?'已停止':stages[run.stage]||run.stage} · ${run.message}`:'保存配置后可启动自动提取、裂变和套版。模型调用可能产生费用，按本任务上限执行。';
  if(preparationError&&(!run||run.stage==='PUBLISH'&&run.status.startsWith('WAITING')))message.textContent=preparationError;
  if(batchId&&run){const meta=[...document.querySelectorAll('#workbench-batch-list button')].find(button=>button.dataset.batchId===batchId)?.querySelector('small');if(meta)meta.textContent=run.status==='COMPLETED'?(run.stage==='PUBLISH'?'已上传草稿':'创作完成'):`${stages[run.stage]||run.stage} · ${{QUEUED:'待推进',RUNNING:'处理中',PAUSING:'等待暂停',PAUSED:'已暂停',WAITING_INPUT:'需处理',UNKNOWN:'需核查',WAITING_CAPABILITY:'等待接入',FAILED:'已停止'}[run.status]||run.status}`;}
  document.querySelectorAll('[data-studio-step]').forEach(node=>{
   const active=!!run&&node.dataset.studioStep===steps[run.stage];
   node.classList.toggle('is-executing',active&&run.status==='RUNNING');
   node.classList.toggle('is-execution-waiting',active&&run.status.startsWith('WAITING'));
  });
 }
 async function refresh(){
  const ticket=revision,id=batchId;if(!id)return;
  try{const [result,limits]=await Promise.all([api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/execution`),api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/execution/limits`)]);if(ticket!==revision)return;stateKnown=true;render(result.data);limitMessage.textContent=result.data?`模型调用占用 ${limits.data.reservedCalls} / ${limits.data.maxCalls} 次（非实际账单）`:'0 表示禁止自动付费生成；启动后上限固定。';}
  catch(error){if(ticket===revision){stateKnown=false;[start,pause,resume,rebuild].forEach(b=>b.disabled=true);message.textContent=`执行状态读取失败：${error.message}`;window.dispatchEvent(new CustomEvent('heyi:execution-connection',{detail:{batchId:id,available:false}}));}}
  finally{if(ticket===revision)timer=setTimeout(refresh,4000);}
 }
 async function act(action){
  if(!batchId||busy)return;
  if(action==='start'&&(!planReady||!stateKnown)){message.textContent='请先保存创作配置并读取执行状态。';return;}
  if(action==='rebuild-local'&&(!stateKnown||!canRebuild(currentRun)))return;
  const maxModelCalls=Number(callLimit.value);if(action==='start'&&(!Number.isInteger(maxModelCalls)||maxModelCalls<0||maxModelCalls>1000)){message.textContent='模型调用上限须为0至1000次。';return;}
  const ticket=++revision,id=batchId;busy=true;preparationError='';[start,pause,resume,rebuild,recover].forEach(b=>b.disabled=true);clearTimeout(timer);
  try{
   if(['start','rebuild-local'].includes(action)&&!creativeOnly||action==='resume'&&currentRun?.stage==='PUBLISH'&&['WAITING_INPUT','WAITING_CAPABILITY'].includes(currentRun.status)
     &&/发布配置(?:缺失|不完整)|自动准备发布参数|缺少.*(?:发布|上传).*参数/.test(currentRun.message||'')){
    if(!globalThis.CommercePublication)throw new Error('上传准备组件未加载，请刷新页面');
    message.textContent='正在补齐本批次所有商品的上传配置，保留已完成素材…';
    await globalThis.CommercePublication.restore(id,{isCurrent:()=>ticket===revision&&id===batchId,
     onProgress:(done,total)=>{if(ticket===revision)message.textContent=`正在检查上传配置 ${done} / ${total}，保留已完成素材…`;}});
    if(ticket!==revision)return;
   }
   if(action==='rebuild-local')message.textContent='正在复用已有印花重新套版，不重新生图…';
   const result=await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/execution${action==='start'?'':`/${action}`}`,{method:'POST',headers:{'Content-Type':'application/json',
    ...(action==='rebuild-local'?{'Idempotency-Key':`commerce-local-rebuild-v2-${id}`}:{})},body:JSON.stringify(action==='start'?{confirmed:true,maxModelCalls,creativePlanVersion}:{})});
   if(ticket===revision){
    const run=action==='rebuild-local'?(result.data?.execution||result.data):result.data;
    if(action==='rebuild-local'&&(run?.batchId!==id||typeof run.status!=='string'))throw new Error('重新套版结果尚未确认，请重试核对同一次请求');
    busy=false;stateKnown=true;render(run);
   }}
  catch(error){if(ticket===revision){message.textContent=error.message;if(error.preparationFailed)preparationError=error.message;}}
  finally{if(ticket===revision){busy=false;timer=setTimeout(refresh,1000);}}
 }
 start.addEventListener('click',()=>act('start'));pause.addEventListener('click',()=>act('pause'));resume.addEventListener('click',()=>act('resume'));
 rebuild.addEventListener('click',()=>act('rebuild-local'));
 recover.addEventListener('click',()=>act('recover'));
 window.addEventListener('heyi:artifact-retry-started',event=>{
  const detail=event.detail,run=detail?.execution;
  if(!batchId||detail?.batchId!==batchId||run?.batchId!==batchId)return;
  // Invalidate a poll started before the explicit retry; it may still contain FAILED.
  revision++;clearTimeout(timer);busy=false;stateKnown=true;render(run);
  timer=setTimeout(refresh,1000);
 });
 window.addEventListener('heyi:creative-plan-state',event=>{if(event.detail.batchId!==batchId)return;creativePlanVersion=event.detail.version;planReady=event.detail.ready;start.disabled=busy||!batchId||!!currentRun||!planReady||!stateKnown;retryNote.textContent=artworkBudgetNote+(event.detail.renderPolicy==='GROK_DRAPE_REUSE_V1'?`自然搭放另按每个来源商品 1 次 Grok 调用计入上限${Number.isInteger(event.detail.sourceCount)&&event.detail.sourceCount>0?'（本批最多 '+event.detail.sourceCount+' 次）':''}，不按款数或颜色数追加。`:'');});
 window.addEventListener('heyi:workbench-scope-reset',()=>{revision++;batchId=null;busy=false;stateKnown=false;planReady=false;creativePlanVersion=0;preparationError='';creativeOnly=false;callLimit.value='0';clearTimeout(timer);render(null);});
 window.addEventListener('heyi:workbench-batch-selected',event=>{revision++;batchId=event.detail.batchId;creativeOnly=event.detail.creativeOnly===true;preparationError='';retryNote.textContent=artworkBudgetNote;clearTimeout(timer);
  const run=event.detail.execution;
  if(run?.batchId===batchId&&typeof run.status==='string'){
   stateKnown=true;render(run);limitMessage.textContent=`本批次模型调用上限 ${event.detail.maxModelCalls} 次（非实际账单）`;
  }
  refresh();
 });
 render(null);
})();

(() => {
 const panel=document.getElementById('commerce-design-panel');if(!panel)return;
 const box=document.createElement('details');box.className='sample-preview-box';
 const summary=document.createElement('summary');summary.textContent='示例预览 · 与正式批次分开';box.append(summary);
 const controls=document.createElement('div');controls.className='sample-preview-controls';
 const product=document.createElement('select'),variant=document.createElement('select'),parent=document.createElement('select');parent.setAttribute('aria-label','裂变参考原图');parent.add(new Option('先读取基础示例',''));
 product.setAttribute('aria-label','示例商品');variant.setAttribute('aria-label','示例版本');
 const kind=document.createElement('select');kind.setAttribute('aria-label','素材操作');[['PRINT_EXTRACT','提取独立印花'],['PRINT_VARIANT','裂变独立印花'],['BLANK_PRODUCT','去除原印花 · 纯色底图'],['PRODUCT_IMAGE','旧版商品整图示例']].forEach(([value,label])=>kind.add(new Option(label,value)));
 [['CUSHION','抱枕'],['T_SHIRT','短袖'],['TROUSERS','长裤']].forEach(([value,label])=>product.add(new Option(label,value)));
 ['基础示例','暖色变化','冷色变化','构图变化'].forEach((label,i)=>variant.add(new Option(label,String(i))));
 const read=document.createElement('button'),generate=document.createElement('button');read.type=generate.type='button';read.textContent='查看已有示例';generate.textContent='生成一张示例';
 const message=document.createElement('p');message.setAttribute('role','status');message.textContent='示例每次只调用一次；提取和裂变透明印花使用 IMG2.0，商品照片使用公司图片模型。仅作概念示例，不创建商品或发布。';
 const gallery=document.createElement('div');gallery.className='sample-preview-gallery';controls.append(kind,product,variant,parent,read,generate);box.append(controls,message,gallery);panel.prepend(box);
 let revision=0,busy=false,request=null;
 function resetRequest(){request=null;}
 product.addEventListener('change',resetRequest);variant.addEventListener('change',resetRequest);
 parent.addEventListener('change',resetRequest);
 kind.addEventListener('change',()=>{resetRequest();if(kind.value==='BLANK_PRODUCT')variant.value='0';variant.disabled=kind.value==='BLANK_PRODUCT';});
 read.addEventListener('click',async()=>{const ticket=revision;try{const response=await api('/api/v1/commerce-samples');if(ticket!==revision)return;parent.replaceChildren();parent.add(new Option('选择来源素材',''));response.data.filter(row=>row.status==='READY').forEach(row=>parent.add(new Option(`${row.product} · ${row.assetKind==='PRINT_EXTRACT'?'独立印花':row.assetKind==='PRINT_VARIANT'?'印花变体':row.assetKind==='BLANK_PRODUCT'?'纯色底图候选':'商品图'} · ${row.id.slice(0,8)}`,row.id)));}catch(error){if(ticket===revision)message.textContent=error.message;}});
 async function refresh(){const ticket=revision;try{
  const response=await api('/api/v1/commerce-samples');if(ticket!==revision)return;gallery.replaceChildren();
  const groups=new Map();response.data.forEach(row=>{const key=row.parentId||row.id;if(!groups.has(key))groups.set(key,[]);groups.get(key).push(row);});
  groups.forEach((rows,id)=>{const group=document.createElement('section'),heading=document.createElement('h4'),grid=document.createElement('div');group.className='sample-preview-group';grid.className='sample-preview-grid';heading.textContent=`${{CUSHION:'抱枕',T_SHIRT:'短袖',TROUSERS:'长裤'}[rows[0].product]||rows[0].product} · 示例图集 ${id.slice(0,8)}`;group.append(heading,grid);
   rows.sort((a,b)=>a.variant-b.variant).forEach(row=>{const card=document.createElement('article'),caption=document.createElement('p');const label=row.assetKind==='BLANK_PRODUCT'?'去印花纯色底图候选':row.assetKind==='PRINT_EXTRACT'?'提取印花候选':row.assetKind==='PRINT_VARIANT'?'独立印花变体':`商品整图 · ${['基础原图','暖色变化','冷色变化','构图变化'][row.variant]}`;caption.textContent=`${label} · ${row.status==='READY'?'已归档，待质量复核':row.status}`;card.append(caption);if(row.imageUrl){const img=document.createElement('img');img.alt='AI生成概念示例，非正式商品';img.loading='lazy';img.src=row.imageUrl;card.prepend(img);}grid.append(card);});gallery.append(group);
  });
  if(!response.data.length)message.textContent='暂无生成示例。可选择商品与版本，生成一张预览。';
 }catch(error){if(ticket===revision)message.textContent=error.message;}}
 read.addEventListener('click',refresh);
 generate.addEventListener('click',async()=>{
  if(busy||!window.confirm('确认使用当前公司图片模型生成一张示例？可能产生模型费用，不会创建商品或上架。'))return;
  if((kind.value!=='PRODUCT_IMAGE'||Number(variant.value)>0)&&!parent.value){message.textContent='请读取并选择来源：提取使用商品图，印花裂变使用已提取的印花。';return;}
  request??={key:crypto.randomUUID(),body:{product:product.value,variant:Number(variant.value),confirmed:true,parentId:kind.value!=='PRODUCT_IMAGE'||Number(variant.value)>0?parent.value:null,assetKind:kind.value}};
  const ticket=revision;busy=true;generate.disabled=true;kind.disabled=true;product.disabled=true;variant.disabled=true;parent.disabled=true;message.textContent='正在生成，请勿重复提交。结果不确定时不会自动重试。';
  try{const response=await api('/api/v1/commerce-samples',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':request.key},body:JSON.stringify(request.body)});if(ticket!==revision)return;message.textContent=`示例状态：${response.data.status}`;await refresh();}
  catch(error){if(ticket===revision)message.textContent=`${error.message}。本页重试将复用同一请求编号。`;}
  finally{if(ticket===revision){busy=false;generate.disabled=false;kind.disabled=false;product.disabled=false;variant.disabled=kind.value==='BLANK_PRODUCT';parent.disabled=false;}}
 });
 window.addEventListener('heyi:workbench-batch-selected',()=>{box.hidden=true;});
 window.addEventListener('heyi:workbench-scope-reset',()=>{box.hidden=false;});
 window.addEventListener('heyi:company-reset',()=>{revision++;request=null;gallery.replaceChildren();parent.replaceChildren();parent.add(new Option('先读取基础示例',''));busy=false;generate.disabled=false;kind.disabled=false;product.disabled=false;variant.disabled=kind.value==='BLANK_PRODUCT';parent.disabled=false;});
})();
