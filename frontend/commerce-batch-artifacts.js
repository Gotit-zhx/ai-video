(() => {
  const journey=document.querySelector('.studio-journey');if(!journey)return;
  const panel=document.createElement('section');panel.className='batch-artifact-panel';panel.hidden=true;
  const heading=document.createElement('h2');heading.textContent='本批次图集';
  const status=document.createElement('p');status.setAttribute('role','status');
  const grid=document.createElement('div');grid.className='batch-artifact-grid';panel.append(heading,status,grid);
  const guides=document.createElement('details');guides.className='batch-position-guides';guides.hidden=true;
  const guideHeading=document.createElement('summary');guideHeading.textContent='查看印花定位参考';
  const guideNote=document.createElement('p');guideNote.textContent='定位参考用于核对印花位置，不是最终生成的商品效果图。';
  const guideGrid=document.createElement('div');guideGrid.className='batch-artifact-grid batch-guide-grid';guides.append(guideHeading,guideNote,guideGrid);panel.append(guides);
  const materials=document.createElement('details');materials.className='batch-position-guides batch-intermediate-assets';materials.hidden=true;
  const materialHeading=document.createElement('summary');materialHeading.textContent='查看透明母版与复用底图';
  const materialNote=document.createElement('p');materialNote.textContent='这些是套版过程素材。每款同组共用透明母版，每色复用一张底图；根据所选商品图的场景氛围、配色、材质与光线重新设计背景，供同商品多款多色共用，均不计入商品成品数量。';
  const materialGrid=document.createElement('div');materialGrid.className='batch-artifact-grid batch-intermediate-grid';materials.append(materialHeading,materialNote,materialGrid);panel.append(materials);
  document.querySelector('.studio-stage-view').after(panel);
  const dialog=document.createElement('dialog');dialog.className='batch-artifact-lightbox';
  const close=document.createElement('button');close.type='button';close.textContent='关闭预览';close.addEventListener('click',()=>dialog.close());
  const preview=document.createElement('img');preview.alt='批次生成图片';dialog.append(close,preview);document.body.append(dialog);
  let batch=null,revision=0,pending=false,refreshRequested=false,step='extract',sourceNames=new Map(),sourcesLoaded=false,execution=null,connectionAvailable=true,artifactsAvailable=true,repairSignature='',renderPolicy=null,expectedOutputs=null,expectedDetails=0,colorSources=[];
  const localPhoto=()=>['LOCAL_PHOTO_V1','GROK_SCENE_V1','GROK_DRAPE_REUSE_V1'].includes(renderPolicy);
  const sceneBackground=()=>renderPolicy==='GROK_SCENE_V1';
  const drapeReuse=()=>renderPolicy==='GROK_DRAPE_REUSE_V1';
  const isScene=row=>row.kind==='SCENE_BACKGROUND';
  const cards=new Map();
  const sourcePicker=document.createElement('select');sourcePicker.setAttribute('aria-label','按来源商品筛选');sourcePicker.add(new Option('全部来源商品',''));sourcePicker.hidden=true;heading.after(sourcePicker);
  const filteredStatus=document.createElement('p');filteredStatus.setAttribute('role','status');grid.before(filteredStatus);
  const sides={FRONT:'正面',BACK:'背面',LEFT:'左侧',RIGHT:'右侧',TOP:'俯视',BOTTOM:'仰视'};
  const surfaceKinds=Object.keys(sides).flatMap(side=>['MOCKUP_'+side,'PLACEMENT_GUIDE_'+side]);
  const variantGroup=kind=>/^VARIATE_G([1-9][0-9]*)$/.exec(kind)?.[1];
  const matteGroup=kind=>/^MATTE_G([1-9][0-9]*)$/.exec(kind)?.[1];
  const isMatte=row=>row.kind==='MATTE'||!!matteGroup(row.kind);
  const stepKinds={extract:['EXTRACT'],design:['VARIATE'],mockup:['BLANK','MOCKUP','BLANK_REPAIRED','MOCKUP_REPAIRED','DRAPED_FRONT',...surfaceKinds],publish:['MOCKUP','MOCKUP_REPAIRED','DRAPED_FRONT',...surfaceKinds]};
  const gridDetail=kind=>/^MODEL_DETAIL_GRID_([1-9][0-9]*)$/.exec(kind)?.[1];
  const modelDetail=kind=>/^MODEL_DETAIL_([1-9][0-9]*)$/.exec(kind)?.[1];
  const isGuide=row=>row.kind.startsWith('PLACEMENT_GUIDE_');
  const isProduct=row=>drapeReuse()?row.kind==='DRAPED_FRONT':row.kind==='MOCKUP'||Object.keys(sides).some(side=>row.kind==='MOCKUP_'+side);
  const isDrapeMaterial=row=>drapeReuse()&&(row.kind.startsWith('DRAPE_')||row.kind.startsWith('MOCKUP'));
  const isMaterial=row=>!!gridDetail(row.kind)||isScene(row)||isDrapeMaterial(row)||localPhoto()&&(isMatte(row)||['BASE_COLOR','BLANK','BLANK_REPAIRED'].includes(row.kind));
  function filter(){
    let visible=0,guideCount=0,materialCount=0;
    cards.forEach(({card,row})=>{
      const belongs=((modelDetail(row.kind)||gridDetail(row.kind))&&['mockup','publish'].includes(step))||(isScene(row)&&['extract','mockup'].includes(step))||(isDrapeMaterial(row)&&['mockup','publish'].includes(step))||(stepKinds[step]||[]).includes(row.kind)||(step==='design'&&!!variantGroup(row.kind))
        ||(localPhoto()&&((['design','mockup'].includes(step)&&isMatte(row))
          ||(['extract','mockup'].includes(step)&&['BASE_COLOR','BLANK','BLANK_REPAIRED'].includes(row.kind))));
      card.hidden=!belongs||!!(sourcePicker.value&&sourcePicker.value!==card.dataset.captureId);
      if(!card.hidden){if(isGuide(row))guideCount++;else if(isMaterial(row))materialCount++;else visible++;}
    });
    guides.hidden=!guideCount;guideHeading.textContent=`查看印花定位参考 · ${guideCount} 张`;
    materials.hidden=!materialCount;materialHeading.textContent=`${drapeReuse()?'查看搭放母图与四宫格原图':'查看透明母版与复用底图'} · ${materialCount} 张`;
    materialNote.textContent=drapeReuse()?'精确套版图与搭放母图供各款各色共用；商品主图仅统计最终自然搭放结果。':'这些是套版过程素材。每款同组共用透明母版，每色复用一张底图，均不计入商品成品数量。';
    heading.textContent=({extract:'提取印花',design:'印花裂变',mockup:drapeReuse()?'自然搭放':localPhoto()?'本地套版':'商品套版',publish:'结果与检查'})[step]+' · 本批次图集';
    const unit=localPhoto()?(['mockup','publish'].includes(step)?' 张主图与详情图':' 张印花素材 · 非商品成品'):' 张';
    filteredStatus.textContent=visible?`当前显示 ${visible}${unit} · ${sourcePicker.value?'所选商品':'全部来源商品'}`:materialCount?'本环节已保存过程素材，可展开查看；商品主图以实际归档为准。':'当前环节还没有图片结果。';
  }
  sourcePicker.addEventListener('change',filter);
  window.addEventListener('heyi:studio-step-selected',event=>{step=event.detail.step;filter();});
  const labels={DRAPED_FRONT:'商品主图 · 自然搭放',DRAPE_MASTER:'自然搭放母图 · 同商品共用',DRAPE_GUIDE:'搭放位置参考',SCENE_BACKGROUND:'提取商品背景风格 · 同商品多款多色共用',EXTRACT:'提取印花',VARIATE:'独立印花变体',MATTE:'透明印花母版',BASE_COLOR:'每色复用底图',BLANK:'去印花底图',MOCKUP:'商品套版',BLANK_REPAIRED:'去印花底图 · 修复版',MOCKUP_REPAIRED:'商品套版 · 修复版'};
  Object.entries(sides).forEach(([side,label])=>{labels['MOCKUP_'+side]=label+' · AI 套版候选';labels['PLACEMENT_GUIDE_'+side]=label+' · 模型印花定位图';});
  const states={PREPARED:'待提交',SUBMITTING:'生成中',RESULT_RECEIVED:'待归档',READY:'已归档',UNKNOWN:'结果需核查',REJECTED:'生成被拒绝',TRANSPARENCY_REJECTED:'透明背景不合格',INVALID_IMAGE_REJECTED:'图片输出不完整',FAILED:'生成已停止'};
  const executionStates={FAILED:'已停止',UNKNOWN:'结果需核查',PAUSED:'已暂停',PAUSING:'等待暂停',WAITING_INPUT:'等待处理',WAITING_CAPABILITY:'等待接入',COMPLETED:'处理已结束',CANCELLED:'已取消'};
  const stopped=()=>!!execution&&(Object.hasOwn(executionStates,execution.status)||execution.status.startsWith('WAITING'));
  const disconnected=()=>!connectionAvailable||!artifactsAvailable;
  function motion(row){
    if(row.status==='READY')return 'ready';
    if(['REJECTED','TRANSPARENCY_REJECTED','INVALID_IMAGE_REJECTED','FAILED','UNKNOWN'].includes(row.status)||stopped())return 'stopped';
    return !disconnected()&&execution?.status==='RUNNING'&&['SUBMITTING','RESULT_RECEIVED'].includes(row.status)?'running':'waiting';
  }
  function stateText(row){
    if(row.status==='UNKNOWN'&&(modelDetail(row.kind)||gridDetail(row.kind)))return execution?.status==='FAILED'?'恢复已停止 · 已保留请求':'正在自动核对与恢复';
    if(['READY','REJECTED','TRANSPARENCY_REJECTED','INVALID_IMAGE_REJECTED','FAILED','UNKNOWN'].includes(row.status))return states[row.status];
    if(stopped())return executionStates[execution.status]||'等待处理';
    if(disconnected())return '连接中断 · 等待状态更新';
    if(row.status==='PREPARED')return localPhoto()&&(isProduct(row)||isMaterial(row))?'等待处理':states.PREPARED;
    if(['SUBMITTING','RESULT_RECEIVED'].includes(row.status)&&execution?.status!=='RUNNING')return '等待执行状态更新';
    if(isScene(row)&&row.status==='SUBMITTING')return 'Grok 场景背景准备中';
    if(localPhoto()&&row.status==='SUBMITTING'&&(isProduct(row)||isMatte(row)))return isMatte(row)?'处理透明母版中':'本地合成中';
    return states[row.status]||row.status;
  }
  function syncMotion(){cards.forEach(entry=>{
    entry.card.dataset.motion=motion(entry.row);entry.state.textContent=stateText(entry.row);
    if(entry.placeholder){const state=entry.row.status;entry.placeholder.textContent=state==='INVALID_IMAGE_REJECTED'?'来源商品 · 图片输出不完整':state==='TRANSPARENCY_REJECTED'?'来源商品 · 印花透明背景不合格':['REJECTED','FAILED'].includes(state)?'商品原图 · 本张未生成':state==='UNKNOWN'||execution?.status==='UNKNOWN'?'商品原图 · 结果待核查':stopped()?'商品原图 · 处理已暂停或停止':'商品原图 · 等待生成结果';}
    if(disconnected()||(stopped()&&execution.status!=='COMPLETED'))entry.card.classList.remove('is-revealing');
    syncRetry(entry);
  });}
  const canRetry=entry=>typeof entry.row.id==='string'&&!!entry.row.id&&cards.get(entry.row.id)===entry&&(
    execution?.status==='FAILED'&&['REJECTED','TRANSPARENCY_REJECTED','INVALID_IMAGE_REJECTED'].includes(entry.row.status)
    ||['WAITING_INPUT','PAUSED','FAILED'].includes(execution?.status)&&execution?.stage==='MOCKUP'&&entry.row.kind==='DRAPE_MASTER'&&entry.row.status==='READY');
  function syncRetry(entry){
    if(canRetry(entry)){
      if(!entry.retryButton){const button=document.createElement('button');button.type='button';button.className='artifact-retry';button.addEventListener('click',()=>retryArtifact(entry));entry.retryButton=button;}
      entry.retryButton.textContent=entry.retryPending?'正在重试…':entry.row.kind==='DRAPE_MASTER'?'按模板重做母图':'重试这张';entry.retryButton.disabled=!!entry.retryPending;
      if(entry.retryButton.parentElement!==entry.card)entry.card.append(entry.retryButton);
    }else entry.retryButton?.remove();
    if(entry.retryMessage){
      if(!entry.retryStatus){entry.retryStatus=document.createElement('p');entry.retryStatus.className='artifact-retry-status';entry.retryStatus.setAttribute('role','status');}
      entry.retryStatus.textContent=entry.retryMessage;if(entry.retryStatus.parentElement!==entry.card)entry.card.append(entry.retryStatus);
    }else entry.retryStatus?.remove();
  }
  async function retryArtifact(entry){
    if(entry.retryPending||!canRetry(entry))return;
    const ticket=revision,id=batch,artifactId=entry.row.id;entry.retryPending=true;entry.retryMessage='正在提交这张图片的重试…';syncRetry(entry);
    try{
      const response=await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/execution/artifacts/${encodeURIComponent(artifactId)}/retry`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({confirmed:true})});
      if(ticket!==revision)return;
      const run=response?.data?.execution;if(run?.batchId!==id||typeof run.status!=='string')throw new Error('未收到可核对的执行状态');
      execution=run;connectionAvailable=true;entry.retryMessage='已提交这张图片的重试，正在更新结果。';syncMotion();
      window.dispatchEvent(new CustomEvent('heyi:artifact-retry-started',{detail:{batchId:id,execution:run}}));
      await refresh();
    }catch(error){
      if(ticket!==revision)return;
      const uncertain=!error.status||error.status===408||error.status>=500;
      entry.retryMessage=uncertain?'重试请求的响应未确认，状态待核对；可再次点击“重试这张”核对同一次请求。':`本次重试未启动：${error.message}`;
    }finally{
      entry.retryPending=false;if(ticket===revision&&cards.get(artifactId)===entry)syncRetry(entry);
    }
  }
  function visibility(){panel.hidden=!batch||location.hash!=='#commerce-designs';}
  function decoration(className){const node=document.createElement('div');node.className=className;node.setAttribute('aria-hidden','true');return node;}
  function updateCard(entry,row){
    const oldStatus=entry.row?.status,sourceName=sourceNames.get(row.captureId)||row.captureId.slice(0,8);
    const next=JSON.stringify([row,sourceName,renderPolicy]);if(entry.signature===next)return;
    if(entry.backObjectUrl){URL.revokeObjectURL(entry.backObjectUrl);entry.backObjectUrl=null;}
    entry.showingBack=false;entry.signature=next;entry.row=row;
    const card=entry.card;card.dataset.kind=row.kind;card.dataset.captureId=row.captureId;card.dataset.status=row.status;
    const title=document.createElement('h3');const group=variantGroup(row.kind),matte=matteGroup(row.kind);
    const artifactLabel=gridDetail(row.kind)?'四宫格详情处理 · 第 '+gridDetail(row.kind)+' 张起':modelDetail(row.kind)?'模特实穿详情 · 第 '+modelDetail(row.kind)+' 张':drapeReuse()&&isProduct(row)?'商品主图 · 自然搭放':drapeReuse()&&row.kind==='MOCKUP_FRONT'?'精确套版 · 搭放前定位图':localPhoto()&&isProduct(row)?'商品主图 · 本地套版':matte?'透明母版 · 图案组 '+matte:group?(localPhoto()?'印花原稿 · 图案组 ':'图案组 ')+group:row.kind==='EXTRACT'&&row.message?.startsWith('模板上传')?'模板上传印花':localPhoto()&&row.kind==='VARIATE'?'独立印花原稿':labels[row.kind]||row.kind;
    title.textContent=`${artifactLabel}${row.variant?' · V'+row.variant:''}${row.colorLabel?' · '+row.colorLabel:''}${row.message?.startsWith('边缘融合')?' · 边缘融合':''}`;
    const visual=document.createElement('div');visual.className='artifact-visual';entry.visual=visual;
    entry.placeholder=null;
    if(row.imageUrl){
      const button=document.createElement('button');button.type='button';button.className='batch-artifact-open';button.setAttribute('aria-label',`放大查看${title.textContent}`);
      const img=document.createElement('img');img.src=row.imageUrl;img.alt=title.textContent;img.loading='lazy';button.append(img);
      button.addEventListener('click',()=>{preview.src=entry.showingBack?entry.backObjectUrl:row.imageUrl;preview.alt=title.textContent+(entry.showingBack?' · 背面':'');dialog.showModal();});visual.append(button);
      if(row.backImageUrl&&localPhoto()){
        const flip=document.createElement('button');flip.type='button';flip.className='artifact-flip';flip.textContent='↻ 查看背面';flip.setAttribute('aria-label',`翻转查看背面 · ${title.textContent}`);
        const note=document.createElement('span');note.className='artifact-side-label';note.textContent='正面';visual.append(note,flip);
        flip.addEventListener('click',async()=>{
          if(flip.disabled)return;
          const ticket=revision;flip.disabled=true;
          try{
            if(!entry.showingBack&&!entry.backObjectUrl){
              flip.textContent='正在准备背面…';
              const response=await fetch(row.backImageUrl,{credentials:'same-origin'});
              if(!response.ok){let message='背面预览暂时无法生成，请重试';try{const body=await response.json();message=body.message||message;}catch(_){}throw new Error(message);}
              const blob=await response.blob();
              if(ticket!==revision||cards.get(row.id)!==entry)return;
              entry.backObjectUrl=URL.createObjectURL(blob);
            }
            if(ticket!==revision||cards.get(row.id)!==entry)return;
            entry.showingBack=!entry.showingBack;
            img.src=entry.showingBack?entry.backObjectUrl:row.imageUrl;img.alt=title.textContent+(entry.showingBack?' · 背面':' · 正面');
            visual.dataset.side=entry.showingBack?'back':'front';
            note.textContent=entry.showingBack?'背面':'正面';
            flip.textContent=entry.showingBack?'↻ 返回正面':'↻ 查看背面';
            flip.setAttribute('aria-label',`翻转查看${entry.showingBack?'正面':'背面'} · ${title.textContent}`);
            button.setAttribute('aria-label',`放大查看${title.textContent}${entry.showingBack?' · 背面':''}`);
          }catch(error){if(ticket===revision){note.textContent=error.message;flip.textContent='↻ 重试背面';}}
          finally{flip.disabled=false;}
        });
      }
    }else{
      const img=document.createElement('img');img.className='artifact-source-image';img.src=`/api/v1/commerce-batches/${encodeURIComponent(batch)}/execution/artifacts/sources/${encodeURIComponent(row.captureId)}/image`;img.alt='商品原图 · '+sourceName;img.loading='lazy';
      img.addEventListener('error',()=>{img.hidden=true;});
      const label=document.createElement('span');label.className='artifact-placeholder-label';entry.placeholder=label;label.textContent='商品原图 · 等待生成结果';visual.append(img,label);
    }
    const particles=decoration('artifact-particles');for(let i=0;i<3;i++)particles.append(document.createElement('span'));
    visual.append(decoration('artifact-scan'),decoration('artifact-orbit'),particles);
    const state=document.createElement('p');state.className='artifact-state';entry.state=state;card.replaceChildren(visual,title,state);
    if(row.check){
      const check=document.createElement('p');check.className='batch-artifact-check';
      const localCheck=['PHOTO_COMPOSITED_REVIEW_REQUIRED','LOCAL_PHOTO_REVIEW_REQUIRED'].includes(row.check.status);
      const ratio=Number.isFinite(row.check.outsideChangedRatio)?`区域外变化 ${(row.check.outsideChangedRatio*100).toFixed(2)}% · `:'';
      check.textContent=gridDetail(row.kind)?(row.check.gridSplit?.status==='DONE'?'四宫格已切分 · '+row.check.gridSplit.childCount+' 张独立详情':'四宫格原图已保存，继续切分与检查'):modelDetail(row.kind)?(row.check.passed===true?'模特实穿详情已检查 · 与当前新款一致':row.check.reason?'详情图需纠正 · '+row.check.reason:'详情图已保存 · 商品一致性检查中'):drapeReuse()&&isProduct(row)?'自然搭放结果 · 每色每款一张':isDrapeMaterial(row)?'搭放过程素材 · 不计入商品主图':isScene(row)?'场景底图 · 同商品多款多色共用':localCheck?'本地套版已归档 · '+ratio+'自动检查已完成':row.kind==='BASE_COLOR'?'颜色底图 · 布料颜色与保护区域需复核':isMatte(row)?'透明母版 · 背景分离与细节需复核':`${row.check.exactGuideAvailable?'三维定位核对：模型印区外':'位置检查：区域外'}${Number.isFinite(row.check.outsideChangedRatio)?'变化 '+(row.check.outsideChangedRatio*100).toFixed(2)+'% · ':''}需复核`;
      if(row.check.limitation)check.title=row.check.limitation;card.append(check);
    }
    const source=document.createElement('small');
    const note=localPhoto()&&isProduct(row)&&row.status==='READY'
      ?(row.message||'').replace(/(?:仍)?待质量复核/g,'可查看成品效果'):row.message||'';
    source.textContent=`来源 ${sourceName}${isScene(row)?' · 同商品共用背景':''} · ${note}`;card.append(source);
    if(row.status==='READY' && /^VARIATE(?:_G[1-9][0-9]*)?$/.test(row.kind)){
      const save=document.createElement('button');save.type='button';save.textContent='保存到素材库';
      save.addEventListener('click',async()=>{
        save.disabled=true;
        try{await api(`/api/v1/commerce-artwork-library/${encodeURIComponent(row.id)}`,{method:'POST'});
          save.textContent='已保存到素材库';}
        catch(error){save.disabled=false;source.textContent=error.message;}
      });card.append(save);
    }
    if(row.status!=='READY')card.classList.remove('is-revealing');
    else if(oldStatus!=='READY'&&!isGuide(row))card.classList.add('is-revealing');
  }
  function renderCards(rows){
    const present=new Set();let outputIndex=0,guideIndex=0,materialIndex=0;rows.forEach(row=>{
      const key=row.id||JSON.stringify([row.captureId,row.kind,row.variant,row.colorKey]);present.add(key);
      let entry=cards.get(key);
      if(!entry){const card=document.createElement('article');card.className='batch-artifact-card';entry={card};cards.set(key,entry);
        card.addEventListener('animationend',event=>{if(event.target===card||event.target===entry.visual)card.classList.remove('is-revealing');});
      }
      updateCard(entry,row);
      const parent=isGuide(row)?guideGrid:isMaterial(row)?materialGrid:grid,index=isGuide(row)?guideIndex++:isMaterial(row)?materialIndex++:outputIndex++;
      if(parent.children[index]!==entry.card)parent.insertBefore(entry.card,parent.children[index]||null);
    });
    cards.forEach((entry,key)=>{if(!present.has(key)){if(entry.backObjectUrl)URL.revokeObjectURL(entry.backObjectUrl);entry.card.remove();cards.delete(key);}});syncMotion();filter();
  }
  function renderRepairs(rows){
    const captures=[...new Set(rows.filter(row=>row.kind==='BLANK'&&row.status==='READY').map(row=>row.captureId))];
    const next=JSON.stringify(captures);if(next===repairSignature)return;repairSignature=next;
    panel.querySelector('.batch-repair-actions')?.remove();if(!captures.length)return;
    const actions=document.createElement('div');actions.className='batch-repair-actions';
    captures.forEach(capture=>{const repair=document.createElement('button');repair.type='button';repair.textContent='修复商品细节 · '+capture.slice(0,8);actions.append(repair);
      repair.addEventListener('click',async()=>{
        const ticket=revision,id=batch;const regions=await window.heyiEditPrintRegions(`/api/v1/commerce-batches/${encodeURIComponent(id)}/execution/artifacts/sources/${encodeURIComponent(capture)}/image`);
        if(!regions||ticket!==revision)return;repair.disabled=true;
        try{await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/execution/artifacts/repairs`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({captureId:capture,cleanupAreas:regions})});if(ticket===revision)await refresh();}
        catch(error){if(ticket===revision)status.textContent=error.message;}finally{repair.disabled=false;}
      });
    });panel.append(actions);
  }
  async function refresh(){
    if(!batch||pending)return;const ticket=revision,id=batch;pending=true;refreshRequested=false;
    try{
      const [response,plan]=await Promise.all([api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/execution/artifacts`),sourcesLoaded?Promise.resolve(null):api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/creative-plan`)]);
      if(ticket!==revision)return;
      if(plan){
        sourceNames=new Map(plan.data.sources.map(source=>[source.captureId,source.title||source.captureId.slice(0,8)]));sourcesLoaded=true;sourcePicker.replaceChildren(new Option('全部来源商品',''));sourceNames.forEach((title,id)=>sourcePicker.add(new Option(title,id)));sourcePicker.hidden=sourceNames.size<2;
        renderPolicy=plan.data.plan?.renderPolicy||null;
        colorSources=(plan.data.plan?.sources||[]).map(source=>({captureId:source.captureId,colors:(source.colorTargets||[]).map(color=>({key:color.key,label:color.label}))}));
        const variants=plan.data.plan?.variantsPerPrint;
        const count=(plan.data.plan?.sources||[]).reduce((n,source)=>n+(source.colorTargets?.length||0),0)*variants;
        const details=(plan.data.plan?.sources||[]).reduce((n,source)=>n+(source.detailImageIds?.length||0),0)*variants;
        expectedDetails=Number.isSafeInteger(details)&&details>0?details:0;
        expectedOutputs=Number.isSafeInteger(count)&&count>0?count+expectedDetails:null;
      }
      artifactsAvailable=true;
      const rows=response.data,outputs=rows.filter(row=>localPhoto()?isProduct(row)||!!modelDetail(row.kind):!isGuide(row)&&!isScene(row)),ready=outputs.filter(row=>row.status==='READY').length,total=localPhoto()?(expectedOutputs||outputs.length):outputs.length;
      status.textContent=localPhoto()?`${drapeReuse()?'精确套版 → 一次搭放母图 → 本地换色换花 → 商品主图。搭放母图由同一来源商品的款式与颜色共用。':sceneBackground()?'提取商品背景风格 · 同商品多款多色共用。Grok 根据所选商品图的场景氛围、配色、材质与光线重新设计背景。每个来源商品分别准备一次，已有相同来源背景可复用。':''}${drapeReuse()?'自然搭放':'固定底图 · 本地套版'} · 每色每款1张。已归档 ${ready}${total?' / '+total:''} 张${expectedDetails?'主图与详情图':'商品主图'}；${expectedDetails?'每款按原详情数量生成，每组最多4张合成四宫格后切分；同款不同颜色共用，失败自动恢复，全部检查通过后进入待上架。':''}印花原稿、母图和定位底图不计入成品。`:outputs.length?`已归档 ${ready} / ${outputs.length} 张结果；候选仍需质量复核。定位参考已单独收起。`:'启动后，提取、裂变、底图和套版结果将陆续保存在这里。';
      renderCards(rows);renderRepairs(rows);
      window.dispatchEvent(new CustomEvent('heyi:artifacts-updated',{detail:{batchId:id,ready,total,renderPolicy,colorSources,previews:rows.map(row=>({id:row.id,captureId:row.captureId,kind:row.kind,variant:row.variant,status:row.status,imageUrl:row.imageUrl,colorKey:row.colorKey,colorLabel:row.colorLabel,colorHex:row.kind==='BASE_COLOR'?row.check?.observedSourceRgb:null}))}}));
    }catch(error){if(ticket===revision){artifactsAvailable=false;syncMotion();status.textContent=`图集读取失败：${error.message}`;}}
    finally{if(ticket===revision){pending=false;if(refreshRequested)refresh();}}
  }
  function reset(){
    revision++;batch=null;pending=false;refreshRequested=false;sourceNames=new Map();sourcesLoaded=false;execution=null;connectionAvailable=true;artifactsAvailable=true;repairSignature='';renderPolicy=null;expectedOutputs=null;expectedDetails=0;colorSources=[];step='extract';cards.forEach(entry=>{if(entry.backObjectUrl)URL.revokeObjectURL(entry.backObjectUrl);});cards.clear();
    sourcePicker.replaceChildren(new Option('全部来源商品',''));sourcePicker.value='';sourcePicker.hidden=true;grid.replaceChildren();guideGrid.replaceChildren();materialGrid.replaceChildren();guides.open=false;guides.hidden=true;materials.open=false;materials.hidden=true;panel.querySelector('.batch-repair-actions')?.remove();status.textContent='';filteredStatus.textContent='';dialog.close();preview.removeAttribute('src');
  }
  window.addEventListener('heyi:workbench-scope-reset',()=>{reset();visibility();});
  window.addEventListener('heyi:workbench-batch-selected',event=>{
    const id=event.detail.batchId;if(id!==batch){reset();batch=id;}
    if(event.detail.execution?.batchId===batch)execution=event.detail.execution;
    syncMotion();visibility();refresh();
  });
  window.addEventListener('heyi:execution-state',event=>{
    const run=event.detail?.run;if(!batch||(run&&run.batchId!==batch))return;
    execution=run||null;connectionAvailable=true;syncMotion();visibility();refresh();
  });
  window.addEventListener('heyi:execution-connection',event=>{
    if(event.detail?.batchId!==batch)return;connectionAvailable=event.detail.available!==false;syncMotion();
  });
  window.addEventListener('heyi:creative-plan-state',event=>{
    if(event.detail?.batchId!==batch||!event.detail.version)return;
    const policy=event.detail.renderPolicy||null;
    if(policy!==renderPolicy||(Number.isSafeInteger(event.detail.expectedOutputs)&&event.detail.expectedOutputs!==expectedOutputs)){
      sourcesLoaded=false;refreshRequested=true;refresh();
    }
  });
  window.addEventListener('hashchange',visibility);
  new MutationObserver(visibility).observe(document.getElementById('commerce-design-panel'),{attributes:true,attributeFilter:['hidden']});
})();

// Shared source mask editor. Only the marked rectangles may change.
window.heyiEditPrintRegions = (url, initial = []) => new Promise(resolve => {
  const dialog=document.createElement('dialog');dialog.className='print-region-dialog';
  dialog.innerHTML='<h2>标记旧印花与水印范围</h2><p>在图上拖动框选要清除的旧印花和叠加水印，可标记多处。避开标签、领口和商品边缘；框外保持原图。</p><canvas width="1024" height="1024"></canvas><p role="status"></p><div><button data-undo>撤销上一处</button><button data-save>保存范围</button><button data-cancel>取消</button></div>';
  document.body.append(dialog);const canvas=dialog.querySelector('canvas'),ctx=canvas.getContext('2d'),status=dialog.querySelector('[role=status]');let regions=initial.map(r=>({...r})),start=null,preview=null,loaded=false;
  const image=new Image();image.onload=()=>{loaded=true;draw();};image.onerror=()=>{status.textContent='原图读取失败，请先归档原图或检查图片地址。';};image.src=url;
  const point=e=>{const r=canvas.getBoundingClientRect();return{x:Math.max(0,Math.min(1,(e.clientX-r.left)/r.width)),y:Math.max(0,Math.min(1,(e.clientY-r.top)/r.height))};};
  function draw(){ctx.fillStyle='white';ctx.fillRect(0,0,1024,1024);if(loaded){const scale=Math.min(1024/image.naturalWidth,1024/image.naturalHeight),w=image.naturalWidth*scale,h=image.naturalHeight*scale;ctx.drawImage(image,(1024-w)/2,(1024-h)/2,w,h);}for(const r of [...regions,...(preview?[preview]:[])]){ctx.fillStyle='#f9731640';ctx.strokeStyle='#ff6a00';ctx.lineWidth=3;ctx.fillRect(r.x*1024,r.y*1024,r.width*1024,r.height*1024);ctx.strokeRect(r.x*1024,r.y*1024,r.width*1024,r.height*1024);}status.textContent=`已标记 ${regions.length} 处 · 最多 8 处`;}
  canvas.onpointerdown=e=>{if(!loaded||regions.length>=8)return;start=point(e);canvas.setPointerCapture(e.pointerId);};
  canvas.onpointermove=e=>{if(!start)return;const p=point(e);preview={x:Math.min(start.x,p.x),y:Math.min(start.y,p.y),width:Math.abs(start.x-p.x),height:Math.abs(start.y-p.y)};draw();};
  canvas.onpointerup=()=>{if(preview&&preview.width>.005&&preview.height>.005)regions.push(preview);start=null;preview=null;draw();};canvas.onpointercancel=()=>{start=null;preview=null;draw();};
  function finish(value){dialog.close();dialog.remove();window.removeEventListener('heyi:workbench-scope-reset',cancel);resolve(value);}
  const cancel=()=>finish(null);dialog.oncancel=e=>{e.preventDefault();cancel();};dialog.querySelector('[data-cancel]').onclick=cancel;dialog.querySelector('[data-undo]').onclick=()=>{regions.pop();draw();};dialog.querySelector('[data-save]').onclick=()=>{if(!regions.length){status.textContent='请至少框选一处旧印花或水印。';return;}finish(regions);};window.addEventListener('heyi:workbench-scope-reset',cancel);dialog.showModal();
});
