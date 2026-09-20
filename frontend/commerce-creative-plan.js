(() => {
  const execution = document.querySelector('.workbench-execution');
  if (!execution) return;
  const panel = document.createElement('details');
  panel.className = 'creative-plan';
  panel.hidden = true;
  const summary = document.createElement('summary');
  summary.textContent = '创作配置 · 选择套版模板与提取原图';
  const form = document.createElement('form');
  const fields = document.createElement('fieldset');
  const templateLabel = document.createElement('label');
  templateLabel.textContent = '商品套版模板';
  const template = document.createElement('select');
  template.required = true;
  templateLabel.append(template);
  const renderLabel=document.createElement('label');renderLabel.className='creative-render-policy';renderLabel.textContent='商品呈现';
  const renderPolicySelect=document.createElement('select');renderPolicySelect.setAttribute('aria-label','商品呈现方式');
  renderPolicySelect.add(new Option('原有模式 · 商品背景复用','GROK_SCENE_V1'));
  renderPolicySelect.add(new Option('自然搭放 · 母图复用（每商品1次）','GROK_DRAPE_REUSE_V1'));
  renderPolicySelect.add(new Option('固定底图 · 本地套版（已有配置）','LOCAL_PHOTO_V1'));
  const renderNote=document.createElement('small');renderLabel.append(renderPolicySelect,renderNote);renderLabel.hidden=true;
  const variantsLabel = document.createElement('label');
  variantsLabel.textContent = '每组印花裂变数量';
  const variants = document.createElement('input');
  Object.assign(variants, {type:'number', min:'1', max:'20', step:'1', value:'1', required:true});
  variantsLabel.append(variants);
  const artworkChoices=document.createElement('fieldset');artworkChoices.hidden=true;
  const artworkLegend=document.createElement('legend');artworkLegend.textContent='图片印花位置分组';
  const artworkOptions=document.createElement('div');artworkOptions.className='creative-plan-images';
  const variationHint=document.createElement('p');
  const outputHint=document.createElement('p');outputHint.className='creative-output-rule';outputHint.hidden=true;
  const artworkHint=document.createElement('p');artworkHint.hidden=true;
  artworkChoices.append(artworkLegend,artworkOptions);
  const sources = document.createElement('div');
  sources.className = 'creative-plan-sources';
  const save = document.createElement('button');
  save.type = 'submit';
  save.textContent = '保存创作配置';
  fields.append(templateLabel, renderLabel, variantsLabel, artworkChoices, sources, save);
  const status = document.createElement('p');
  status.setAttribute('role','status');
  const reload = document.createElement('button');
  reload.type = 'button';
  reload.textContent = '重新读取';
  const uploads=document.createElement('details');uploads.className='creative-source-uploads';
  const uploadTitle=document.createElement('summary');uploadTitle.textContent='原图无法远程读取？上传所选原图文件';uploads.append(uploadTitle);
  form.append(fields);
  panel.append(summary, variationHint, outputHint, artworkHint, form, status, reload, uploads);
  execution.before(panel);
  let batchId = null, revision = 0, view = null, busy = false, locked = false, dirty = false, validSources = false, executionStatus=null;
  let renderPolicyEdited=false;
  let cleanup=new Map();
  let templateRows=[],artworkCount=0,artworkIds=[],surfaceMode=false;
  const localPhoto=()=>['LOCAL_PHOTO_V1','GROK_SCENE_V1','GROK_DRAPE_REUSE_V1'].includes(view?.plan?.renderPolicy);
  const sceneBackground=()=>view?.plan?.renderPolicy==='GROK_SCENE_V1';
  const drapeReuse=()=>view?.plan?.renderPolicy==='GROK_DRAPE_REUSE_V1';
  const drapeHint='先精确套版，再生成一次自然搭放母图，之后本地换色换花；需纯白服装模板 + 单处正面印花，背面、侧面补图沿用原有方式。每个来源商品额外 1 次 Grok 调用，所有款式与颜色共用。';
  const sceneHint=()=>drapeReuse()?drapeHint:sceneBackground()?'提取商品背景风格 · 同商品多款多色共用。Grok 根据所选商品图的场景氛围、配色、材质与光线重新设计背景。每个来源商品分别准备一次，已有相同来源背景可复用。':'';
  const outputCount=()=>view?.plan?.outputPolicy==='SOURCE_SKU_COLORS'?(view.plan.sources||[]).reduce((n,source)=>n+(source.colorTargets?.length||0),0)*view.plan.variantsPerPrint:null;
  const savedArtwork=()=>view?.plan?.artworkSource==='SAVED_ARTWORK';
  const reusedArtworks=()=>Array.isArray(view?.plan?.reusedArtworks)?view.plan.reusedArtworks:[];
  const reuseReady=()=>reusedArtworks().length>0&&artworkIds.length>0&&reusedArtworks().every(variant=>artworkIds.every(id=>variant.groupArtifacts?.[id]?.id&&/^[a-f0-9]{64}$/.test(variant.groupArtifacts[id].contentSha256||'')));
  const legacySurface=()=>locked&&surfaceMode&&!savedArtwork()&&view?.plan?.artworkSource!=='SOURCE_PRODUCT';
  const sourceHint=()=>savedArtwork()?'来源商品图用于商品底图与颜色对应；印花使用本任务已固定的透明素材。':surfaceMode?(legacySurface()?'本任务沿用已固定的模板印花来源和图片位置。':'从下方所选商品原图提取真实印花与画风；测试图案只标记图片位置，版型使用模板原图。'):'使用原商品底图时，标记旧印花及照片水印；模板已有底图时无需标记。';
  function drapeBlocker(config){
    if(!config?.photoPrints?.length)return '自然搭放需要已保存的图片印花模板。';
    const front=config.photoPrints.filter(print=>print.side==='FRONT');
    if(front.length!==1)return '自然搭放需要单处正面胸花，当前模板有 '+front.length+' 处正面印花；背面和侧面位置可保留。';
    const p=front[0];
    if(![p.x,p.y,p.width,p.height].every(Number.isFinite)||p.width<=0||p.height<=0||p.x<0||p.x+p.width>1||p.y<0||p.y+p.height>1)return '印花位置超出图片边界，请调整位置后继续。';
    return null;
  }
  const announce = () => window.dispatchEvent(new CustomEvent('heyi:creative-plan-state', {
    detail: {batchId, version:view?.version || 0, renderPolicy:view?.plan?.renderPolicy||null, expectedOutputs:outputCount(), sourceCount:new Set((view?.sources||[]).map(source=>source.captureId)).size, locked,
      ready:(view?.plan?.artworkSource==='SOURCE_PRODUCT'||savedArtwork()&&reuseReady()) && localPhoto() && validSources && (!surfaceMode||artworkCount>0) && !dirty && !busy && !locked}
  }));
  function renderArtworkChoices() {
    const config=templateRows.find(row=>row.snapshotId===template.value)?.configuration;
    const photo=!!config?.photoPrints?.length,drapeIssue=drapeBlocker(config);
    if(!view?.plan&&!renderPolicyEdited&&config)renderPolicySelect.value=photo?'GROK_DRAPE_REUSE_V1':'GROK_SCENE_V1';
    renderLabel.hidden=!photo&&renderPolicySelect.value!=='GROK_DRAPE_REUSE_V1';
    renderPolicySelect.children[1].disabled=!!drapeIssue;renderPolicySelect.children[1].title=drapeIssue||'';
    renderPolicySelect.children[2].hidden=view?.plan?.renderPolicy!=='LOCAL_PHOTO_V1';
    renderPolicySelect.disabled=busy||locked||savedArtwork()||renderLabel.hidden;
    const sourceCount=new Set((view?.sources||[]).map(source=>source.captureId)).size;
    renderNote.textContent=config&&drapeIssue?drapeIssue+(renderPolicySelect.value==='GROK_DRAPE_REUSE_V1'?' 已保留自然搭放选择，请调整模板或手动选择原有模式后保存。':' 当前模板使用原有模式。'):renderPolicySelect.value==='GROK_DRAPE_REUSE_V1'?`需纯白服装模板 + 单处正面印花；背面、侧面补图沿用原有方式。母图生成前自动检查模板布色。每来源商品 1 次额外场景调用${sourceCount?'，本批最多 '+sourceCount+' 次':''}，不随款色数增加；透明印花仍按原调用规则。`:'保持已保存或手动选择的原有模式。尚未保存创作配置的新批次默认自然搭放。';
    const positions=config?.photoPrints?.length?config.photoPrints:(config?.surfacePrints||[]);
    surfaceMode=!!(config?.sourceImageId||config?.referenceSetId||positions.length);
    artworkHint.hidden=!surfaceMode;
    const artworks=[...new Set(positions.map(print=>print.artworkId).filter(Boolean))];artworkIds=artworks;artworkCount=artworks.length;
    const legacy=legacySurface();
    const colorOutput=view?.plan?.outputPolicy==='SOURCE_SKU_COLORS';
    outputHint.hidden=!colorOutput&&!localPhoto();
    if(localPhoto())outputHint.textContent=`${sceneHint()}固定底图 · 本地套版 · 每色每款1张${outputCount()?' · 共 '+outputCount()+' 张商品主图':''}。同款同组共用透明母版，尺码不增加出图。`;
    else if(colorOutput)outputHint.textContent=`保留来源商品颜色 · 每色每款 1 张 · 共 ${outputCount()} 张商品效果图${locked?' · 沿用已固定的生成流程':' · 保存后升级为本地套版'}`;
    variationHint.textContent=savedArtwork()?'使用已有透明印花，直接套版。素材和位置已固定，本任务不再生成新印花。'+sceneHint():locked&&view?.plan?.artworkSource!=='SOURCE_PRODUCT'?'本任务沿用启动时已固定的印花来源与创作规则。':'从来源商品原图提取印花的线条、笔触、色彩关系与质感，保持统一画风；主体可自由二创，例如猫变成狗或其他动物。';
    artworkHint.textContent=savedArtwork()?`已固定 ${reusedArtworks().length} 款已有印花。下方按位置分组展示实际选用的素材，每款按固定位置套版。`:legacy?'本任务使用固定模板中选中的印花进行裂变；同款多位置同步替换，其他图案沿用原配置。':'测试图案只标位置。同一件商品中，前后同款标记共用一张新图；不同标记分别生成，同样保持来源商品的画风。';
    artworkLegend.textContent=savedArtwork()?'已固定的透明印花与位置':legacy?'已固定的模板印花':'图片印花位置分组';
    artworkOptions.replaceChildren();artworkChoices.hidden=!surfaceMode||!artworks.length;
    artworks.forEach((id,index)=>{
      const label=document.createElement('label'),img=document.createElement('img'),caption=document.createElement('span');
      const count=positions.filter(print=>print.artworkId===id).length;
      if(savedArtwork()){
        reusedArtworks().forEach((variant,variantIndex)=>{const asset=variant.groupArtifacts?.[id];if(!asset?.id)return;const preview=variantIndex===0?img:document.createElement('img');preview.src='/api/v1/commerce-artwork-library/'+encodeURIComponent(asset.id)+'/image';preview.alt=`第${variantIndex+1}款印花 · 位置组${index+1}`;preview.loading='lazy';label.append(preview);});
      }else{img.src='/api/v1/commerce-print-templates/base-images/'+encodeURIComponent(id);img.alt='印花 '+(index+1);img.loading='lazy';label.append(img);}
      caption.textContent=savedArtwork()?`位置组 ${index+1} · ${count} 个位置 · ${reusedArtworks().length} 款素材`:legacy?`印花 ${index+1} · ${count} 个位置${id===(view.plan.variationArtworkId||artworks[0])?' · 本次裂变':' · 保留'}`:`图案组 ${index+1} · ${count} 个位置`;
      label.append(caption);artworkOptions.append(label);
    });
    sources.querySelectorAll('[data-cleanup-action]').forEach(button=>button.hidden=surfaceMode);
    sources.querySelectorAll('[data-source-hint]').forEach(hint=>hint.textContent=sourceHint());
  }
  template.addEventListener('change',()=>{if(locked||savedArtwork()){template.value=view?.plan?.printTemplateSnapshotId||'';return;}dirty=true;renderArtworkChoices();announce();});
  renderPolicySelect.addEventListener('change',()=>{if(locked||savedArtwork()){renderPolicySelect.value=view?.plan?.renderPolicy||'GROK_SCENE_V1';return;}renderPolicyEdited=true;dirty=true;renderArtworkChoices();status.textContent='呈现方式已修改，请保存后启动。';announce();});
  function controls() { fields.disabled = busy || locked || savedArtwork() || !view; renderPolicySelect.disabled=fields.disabled||renderLabel.hidden;reload.disabled = busy;uploads.querySelectorAll('input').forEach(input=>input.disabled=busy||savedArtwork()||['RUNNING','QUEUED','PAUSING','COMPLETED'].includes(executionStatus)); announce(); }
  function render(data, templates) {
    view = data; locked = data.locked; dirty = false;renderPolicyEdited=false;
    validSources = data.sources.length > 0 && data.sources.every(source => source.version === source.currentVersion && source.images.length > 0);
    template.replaceChildren(new Option('请选择已保存的商品图片与印花布局',''));
    templates.forEach(row => template.add(new Option(`${row.name} · v${row.version}`,row.snapshotId)));
    if (data.plan && !templates.some(row => row.snapshotId === data.plan.printTemplateSnapshotId)) {
      template.add(new Option('已保存的套版模板版本',data.plan.printTemplateSnapshotId));
    }
    templateRows=[...templates];
    if(data.plan&&data.printTemplateConfiguration&&!templateRows.some(row=>row.snapshotId===data.plan.printTemplateSnapshotId))
      templateRows.push({snapshotId:data.plan.printTemplateSnapshotId,configuration:data.printTemplateConfiguration});
    template.value = data.plan?.printTemplateSnapshotId || '';
    renderPolicySelect.value=data.plan?.renderPolicy||'GROK_SCENE_V1';
    renderArtworkChoices();
    variants.value = String(data.plan?.variantsPerPrint || 1);variantsLabel.textContent=savedArtwork()?'已固定印花款数':'每组印花裂变数量';variantsLabel.append(variants);uploads.hidden=savedArtwork();
    sources.replaceChildren();
    cleanup=new Map((data.plan?.sources||[]).map(s=>[s.captureId,{imageId:s.imageId,regions:s.cleanupAreas||[]}]));
    uploads.replaceChildren(uploadTitle);
    data.sources.forEach(source => {
      const group = document.createElement('fieldset');
      const title = document.createElement('legend');
      title.textContent = `${source.title || '来源商品'} · v${source.version}`;
      group.append(title);
      if (source.version !== source.currentVersion || !source.images.length) {
        const issue = document.createElement('p');
        issue.textContent = source.version !== source.currentVersion ? '来源版本已变化，请重新准备批次。' : '来源没有可提取的原图。';
        group.append(issue);
      }
      const options = document.createElement('div');
      options.className = 'creative-plan-images';
      source.images.forEach((image, index) => {
        const label = document.createElement('label');
        const radio = document.createElement('input');
        Object.assign(radio, {type:'radio', name:`source-${source.captureId}`, value:image.id, required:true});
        radio.dataset.captureId = source.captureId;
        radio.dataset.version = String(source.version);
        radio.checked = data.plan?.sources.some(selected => selected.captureId === source.captureId && selected.version === source.version && selected.imageId === image.id) || false;
        const thumbnail = document.createElement('img');
        thumbnail.alt = `原图 ${index+1}`;
        thumbnail.loading = 'lazy';
        thumbnail.referrerPolicy = 'no-referrer';
        try { const url = new URL(image.url, location.origin); if (['http:','https:'].includes(url.protocol)) thumbnail.src = url.href; } catch (_) {}
        const caption = document.createElement('span');
        caption.textContent = `原图 ${index+1}${image.role === 'MAIN' ? ' · 主图' : ''}`;
        label.append(radio,thumbnail,caption);
        options.append(label);
      });
      group.append(options);
      const colorTargets=data.plan?.sources.find(selected=>selected.captureId===source.captureId)?.colorTargets;
      if(data.plan?.outputPolicy==='SOURCE_SKU_COLORS'&&colorTargets?.length){const colors=document.createElement('p');colors.className='creative-source-colors';colors.textContent=`原商品颜色：${colorTargets.map(color=>color.label).join('、')}。不同尺码共用对应颜色的图片。`;group.append(colors);}
      const mark=document.createElement('button');mark.type='button';mark.textContent=`标记旧印花与水印范围${cleanup.get(source.captureId)?.regions.length ? ' · 已保存' : ''}`;
      mark.hidden=surfaceMode;mark.dataset.cleanupAction='';
      mark.addEventListener('click',async()=>{const selected=[...options.querySelectorAll('input:checked')][0];if(!selected){status.textContent='请先选择提取原图。';return;}const ticket=revision,old=cleanup.get(source.captureId),image=source.images.find(i=>i.id===selected.value);const regions=await window.heyiEditPrintRegions(image.url,old?.imageId===image.id?old.regions:[]);if(regions&&ticket===revision){cleanup.set(source.captureId,{imageId:image.id,regions});mark.textContent=`标记旧印花与水印范围 · ${regions.length} 处`;dirty=true;status.textContent='范围已标记，请保存创作配置。';announce();}});
      const hint=document.createElement('small');hint.dataset.sourceHint='';hint.textContent=sourceHint();group.append(mark,hint);
      sources.append(group);
      const uploadLabel=document.createElement('label');uploadLabel.textContent=`${source.title || '来源商品'} · 上传已选图片的原文件`;
      const file=document.createElement('input');file.type='file';file.accept='image/png,image/jpeg,image/webp';uploadLabel.append(file);uploads.append(uploadLabel);
      file.addEventListener('change',async()=>{
        if(savedArtwork()){file.value='';return;}
        const selected=[...sources.querySelectorAll('input:checked')].find(input=>input.dataset.captureId===source.captureId);
        if(!selected||!file.files[0]){status.textContent='请先选择此商品的提取原图。';file.value='';return;}
        if(file.files[0].size>8*1024*1024){status.textContent='原图不能超过8MiB。';file.value='';return;}
        const ticket=revision,body=new FormData();body.append('file',file.files[0]);busy=true;controls();
        try{await api(`/api/v1/product-captures/${encodeURIComponent(source.captureId)}/images/${encodeURIComponent(selected.value)}/source-file?version=${source.version}`,{method:'POST',body});if(ticket===revision)status.textContent='所选原图已归档；如任务因原图读取暂停，可点击恢复执行。';}
        catch(error){if(ticket===revision)status.textContent=error.message;}
        finally{if(ticket===revision){busy=false;file.value='';controls();}}
      });
    });
    const needsArtwork=surfaceMode&&!artworkCount,needsUpgrade=!!data.plan&&!savedArtwork()&&(data.plan.artworkSource!=='SOURCE_PRODUCT'||!localPhoto());
    if(!locked&&needsUpgrade)dirty=true;
    panel.open = !data.plan||(!data.locked&&(needsArtwork||needsUpgrade));
    summary.textContent = savedArtwork()?`创作配置 · 使用已有印花 · ${reusedArtworks().length} 款（只读）`:data.locked ? `创作配置 · 已固定${data.version ? ` v${data.version}` : '（旧任务未保存）'}` : needsArtwork?'创作配置 · 请配置图片印花位置':needsUpgrade?'创作配置 · 请保存更新后的规则': data.plan ? `创作配置 · 已保存 v${data.version}` : '创作配置 · 选择套版模板与提取原图';
    status.textContent = savedArtwork()?(reuseReady()?'使用已有透明印花，直接套版。已保存的素材与位置保持固定。'+sceneHint():'已有印花记录不完整，请重新读取；仍缺失时请在新建裂变中重新选择素材。'):data.locked ? (localPhoto()?sceneHint()+'任务已固定为照片底图与透明母版本地套版，成品仍需质量复核。':'任务沿用启动时的生成流程，已有结果与配置保持固定。') : !validSources ? '来源需要处理，暂不能启动。' : needsArtwork?'请先在模板图片上放置图案，保存模板后重新读取。':needsUpgrade?'旧草稿尚未启用固定底图与透明母版，请保存一次升级为本地套版后启动。':data.plan ? sceneHint()+'配置已保存。透明印花使用 IMG2.0，再按每款每色本地合成主图；左右、背面不额外生图。' : '每个商品选择一张提取原图；同一商品分配到多个店铺只配置一次。';
  }
  async function read() {
    if (!batchId || busy) return;
    const ticket = ++revision, id = batchId;
    busy = true; view = null; controls(); status.textContent = '正在读取创作配置…';
    try {
      const [response, templates] = await Promise.all([
        api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/creative-plan`),
        api('/api/v1/commerce-print-templates')
      ]);
      if (ticket !== revision) return;
      render(response.data,templates.data);
    } catch (error) { if (ticket === revision) status.textContent = `创作配置读取失败：${error.message}`; }
    finally { if (ticket === revision) { busy = false; controls(); } }
  }
  form.addEventListener('input', () => {
    if(locked||savedArtwork())return;
    dirty = true; status.textContent = '配置已修改，请保存后启动。'; announce();
  });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (busy || locked || savedArtwork() || !view || !form.reportValidity()) return;
    if(surfaceMode&&!artworkCount){status.textContent='请先在模板图片上放置图案并保存。';return;}
    const config=templateRows.find(row=>row.snapshotId===template.value)?.configuration,photo=!!config?.photoPrints?.length;
    if(renderPolicySelect.value==='GROK_DRAPE_REUSE_V1'){const issue=drapeBlocker(config);if(issue){status.textContent=issue;return;}}
    const ticket = revision, id = batchId;
    const selections = [...sources.querySelectorAll('input:checked')].map(input => ({captureId:input.dataset.captureId,version:Number(input.dataset.version),imageId:input.value,cleanupAreas:cleanup.get(input.dataset.captureId)?.imageId===input.value?cleanup.get(input.dataset.captureId).regions:[]}));
    busy = true; controls();
    try {
      const response = await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/creative-plan`, {
        method:'POST', headers:{'Content-Type':'application/json'},
        body:JSON.stringify({expectedVersion:view.version, printTemplateSnapshotId:template.value, variantsPerPrint:Number(variants.value), variationArtworkId:null, artworkSource:'SOURCE_PRODUCT', sources:selections,...(photo?{renderPolicy:renderPolicySelect.value||'GROK_SCENE_V1'}:{})})
      });
      if (ticket !== revision) return;
      view = response.data; dirty = !localPhoto();
      renderArtworkChoices();
      summary.textContent = `创作配置 · 已保存 v${view.version}`;
      status.textContent = localPhoto()?sceneHint()+'已保存。固定底图 · 本地套版 · 每色每款1张；相同图案位置与各颜色共用透明母版，自动检查通过后上传店铺草稿。':'保存结果尚未启用本地套版，请重新读取并保存配置后启动。';
      panel.open = !localPhoto();
    } catch (error) { if (ticket === revision) { dirty = true; status.textContent = error.message; } }
    finally { if (ticket === revision) { busy = false; controls(); } }
  });
  reload.addEventListener('click',read);
  window.addEventListener('heyi:workbench-scope-reset', () => {
    revision++; batchId = null; view = null; busy = false; locked = false; dirty = false;renderPolicyEdited=false;executionStatus=null;templateRows=[];artworkCount=0;artworkIds=[];surfaceMode=false;artworkOptions.replaceChildren();artworkChoices.hidden=true;uploads.replaceChildren(uploadTitle);
    panel.hidden = true; sources.replaceChildren(); template.replaceChildren(); controls();
  });
  window.addEventListener('heyi:workbench-batch-selected', event => {
    batchId = event.detail.batchId; panel.hidden = false; read();
  });
  window.addEventListener('heyi:execution-state', event => {
    executionStatus=event.detail.run?.status || null;
    if (event.detail.run) { locked = true;renderArtworkChoices(); panel.open = false; summary.textContent = savedArtwork()?`创作配置 · 使用已有印花 · ${reusedArtworks().length} 款（只读）`:`创作配置 · 已固定${view?.version ? ` v${view.version}` : ''}`; }
    controls();
  });
})();
