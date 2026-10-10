(() => {
  const form=document.getElementById("commerce-batch-form");
  if(!form) return;
  const get=id=>document.getElementById(`commerce-batch-${id}`);
  const status=get("status"), selector=get("template"), summary=get("template-summary"), shopBox=get("shops"), products=get("products");
  const renderSelector=get("render-policy"),renderField=get("render-policy-field"),renderHint=get("render-policy-hint");
  if(renderSelector)renderSelector.value='GROK_SCENE_V1';
  let explicitRenderPolicy=null;
  let templates=[],printTemplates=[],shops=[], selectedProducts=new Set(), page=0, query="", pages=0;
  let artworkPicker=null,pickerWorkflowId=null,pickerSnapshotId=null;
  let pendingRequest=null,pendingStorageKey="",launchController=null, generation=0, loading=false, refreshing=false, submitting=false, detailGeneration=0, printSelectionGeneration=0, printLoading=false,printLoadError=false;
  const text=(tag,value,parent)=>{const e=document.createElement(tag);e.textContent=value;parent.append(e);return e;};
  function clearPendingRequest(){
    if(pendingStorageKey)try{globalThis.sessionStorage?.removeItem(pendingStorageKey);}catch(_){}
    pendingRequest=null;pendingStorageKey="";
  }
  function readPendingRequest(){
    const organizationId=typeof currentUser!=="undefined"?currentUser?.organizationId:"";
    const storageKey=organizationId?`heyi:commerce-batch-launch:v1:${encodeURIComponent(organizationId)}`:"";
    if(storageKey!==pendingStorageKey){
      pendingRequest=null;pendingStorageKey=storageKey;
      if(storageKey)try{
        const stored=JSON.parse(globalThis.sessionStorage?.getItem(storageKey)||"null");
        if(typeof stored?.key==="string"&&/^[a-zA-Z0-9_-]{1,128}$/.test(stored.key)&&typeof stored.body==="string"&&stored.body.length<=2000000)pendingRequest={key:stored.key,body:stored.body};
      }catch(_){}
    }
    return pendingRequest;
  }
  function preparedRequest(body){
    const previous=readPendingRequest();
    if(!previous)return null;
    try{
      const {publicationRequests,...input}=JSON.parse(previous.body);
      // An unconfirmed launch may already be executing. Reuse its frozen
      // preparation as well as its key, even if live shop metadata has changed.
      const comparable={...body};
      // A changed application default is not a user request to launch again.
      if(!explicitRenderPolicy){delete input.renderPolicy;delete comparable.renderPolicy;}
      else if(!Object.hasOwn(input,'renderPolicy')&&comparable.renderPolicy==='GROK_SCENE_V1')delete comparable.renderPolicy;
      return Array.isArray(publicationRequests)&&publicationRequests.length===body.targets.length
        &&JSON.stringify(input)===JSON.stringify(comparable)?previous:null;
    }catch(_){return null;}
  }
  function launchRequest(body){
    readPendingRequest();
    // Search, template changes and reopening the dialog are not proof that an
    // earlier request failed. Only a different final payload receives a new key.
    const serialized=JSON.stringify(body);
    if(!pendingRequest||pendingRequest.body!==serialized)pendingRequest={key:crypto.randomUUID(),body:serialized};
    if(pendingStorageKey)try{globalThis.sessionStorage?.setItem(pendingStorageKey,JSON.stringify(pendingRequest));}catch(_){}
    return pendingRequest;
  }
  globalThis.addEventListener?.("heyi:company-reset",()=>{
    generation++;printSelectionGeneration++;templates=[];printTemplates=[];shops=[];selectedProducts.clear();clearPendingRequest();page=0;pages=0;loading=false;refreshing=false;submitting=false;printLoading=false;printLoadError=false;
    launchController?.abort();launchController=null;artworkPicker?.reset();artworkPicker?.setLocked(false);pickerWorkflowId=null;pickerSnapshotId=null;
    [products,shopBox,selector,get("list"),get("detail")].forEach(e=>e.replaceChildren());
    status.textContent="";get("selected-count").textContent="已选 0 款";form.reset();
    explicitRenderPolicy=null;if(renderSelector)renderSelector.value='GROK_SCENE_V1';
    form.querySelectorAll("input,select,button").forEach(control=>control.disabled=false);get("more").disabled=true;get("refresh").disabled=false;renderTemplateSummary();
  });
  function selectedTemplate(){return templates.find(t=>t.snapshotId===selector.value);}
  function selectedPrintTemplate(){const bound=selectedTemplate()?.configuration?.printTemplateSnapshotId;return bound?printTemplates.find(t=>t.snapshotId===bound):null;}
  const printPositionCount=template=>{const config=template?.configuration||{};return config.photoPrints?.length||config.surfacePrints?.length||config.areas?.length||0;};
  function templateBlocker(){
    if(!selectedTemplate()?.configuration?.printTemplateSnapshotId)return "此模板尚未绑定印花位置，请到模板配置完成并保存。";
    if(printLoading)return "正在读取模板的印花位置，请稍后开始。";
    if(printLoadError||!selectedPrintTemplate())return "模板印花位置读取失败，请重试；仍失败时请到模板配置重新保存。";
    if(!printPositionCount(selectedPrintTemplate()))return "此模板没有印花位置，请到模板配置添加印花并保存。";
    return null;
  }
  const selectedShopIds=()=>Array.from(form.querySelectorAll('input[name="shop"]:checked')).map(input=>input.value);
  const drapeReuse=()=>renderSelector?.value==='GROK_DRAPE_REUSE_V1';
  function drapeBlocker(config){
    if(!config?.photoPrints?.length)return '自然搭放需要已保存的图片印花模板。';
    const front=config.photoPrints.filter(print=>print.side==='FRONT');
    if(front.length!==1)return '自然搭放需要单处正面胸花，当前模板有 '+front.length+' 处正面印花；背面和侧面位置可保留。';
    const p=front[0];
    if(![p.x,p.y,p.width,p.height].every(Number.isFinite)||p.width<=0||p.height<=0||p.x<0||p.x+p.width>1||p.y<0||p.y+p.height>1)return '印花位置超出图片边界，请调整位置后继续。';
    return null;
  }
  function launchInput(reusedArtworks){
    const targets=selectedShopIds();
    return {name:form.elements.namedItem('name').value.trim(),templateSnapshotId:selectedTemplate()?.snapshotId,
      targets:Array.from(selectedProducts).map((captureId,i)=>({captureId,shopId:targets[i%targets.length]})),printTemplateSnapshotId:selectedPrintTemplate()?.snapshotId,confirmed:true,...(reusedArtworks?{reusedArtworks}:{})};
  }
  const requestPolicy=request=>JSON.parse(request.body).renderPolicy==='GROK_DRAPE_REUSE_V1'?'GROK_DRAPE_REUSE_V1':'GROK_SCENE_V1';
  function pendingForSelection(){
    if(explicitRenderPolicy||!selectedTemplate()||!selectedPrintTemplate()||!selectedProducts.size||!selectedShopIds().length)return null;
    try{return preparedRequest(launchInput(artworkPicker?.getSelection()||null));}catch(_){return null;}
  }
  function renderTemplateSummary(){
    const workflow=selectedTemplate(),print=selectedPrintTemplate(),artworkState=artworkPicker?.getState();
    const photo=!!print?.configuration?.photoPrints?.length,drapeIssue=drapeBlocker(print?.configuration),replay=pendingForSelection();
    if(renderSelector){
      if(explicitRenderPolicy)renderSelector.value=explicitRenderPolicy;
      else if(replay)renderSelector.value=requestPolicy(replay);
      else if(print&&!printLoading&&!printLoadError)renderSelector.value=photo?'GROK_DRAPE_REUSE_V1':'GROK_SCENE_V1';
      const option=Array.from(renderSelector.children).find(option=>option.value==='GROK_DRAPE_REUSE_V1');if(option){option.disabled=!!drapeIssue;option.title=drapeIssue||'';}
      renderSelector.disabled=!print||!photo&&!drapeReuse()||printLoading||submitting;
    }
    if(renderField)renderField.hidden=!photo&&!drapeReuse();
    if(renderHint)renderHint.textContent=print&&drapeIssue?drapeIssue+(drapeReuse()?' 已保留自然搭放选择，请调整模板或手动选择原有模式后再开始。':' 当前模板使用原有模式。'):drapeReuse()?'已选择自然搭放：纯白服装模板 + 单处正面印花；背面、侧面补图沿用原有方式。母图生成前自动检查模板布色。':'当前使用原有模式，保持模板版型并复用商品背景。新批次默认自然搭放，也可手动选择原有模式。';
    const workflowText=workflow?`${workflow.name} · 每组 ${artworkState?.reuse?(artworkState.count||0):(workflow.configuration.designsPerProduct||1)} 款`:"选择模板，带入商品图片、印花位置和裂变款数";
    const layout=print?.configuration?.photoPrints?.length?"四面图片":print?.configuration?.surfacePrints?.length?"历史印花布局":"图片印花布局";
    const printText=!workflow?"配置一次，裂变时直接使用":templateBlocker()||`${layout} · ${printPositionCount(print)}处印花位置`;
    summary.replaceChildren();text("strong",workflowText,summary);text("span",printText,summary);
    if(replay)text('small',`继续上次未确认的请求，沿用${requestPolicy(replay)==='GROK_DRAPE_REUSE_V1'?'自然搭放':'原有模式'}及原请求标识，避免重复创建批次。`,summary);
    if(printLoadError&&!printLoading){const retry=text("button","重试读取",summary);retry.type="button";retry.addEventListener("click",()=>{if(submitting||refreshing)return;status.textContent="";return syncPrintTemplate();});}
    if(artworkState?.reuse)text("small",`已选择 ${artworkState.count||0} 款已有印花，本次复用选定素材。`,summary);
    text("small",artworkState?.reuse?"先自动匹配店铺类目、仓库和包裹参数，再复用选定印花完成套版；自动检查通过后上传到店铺草稿。":"先自动匹配店铺类目、仓库和包裹参数，再按模板提取印花、裂变和套版；自动检查通过后上传到店铺草稿。",summary);
    if(photo)text("small",drapeReuse()?`先精确套版，再生成一次自然搭放母图，之后本地换色换花。每个来源商品额外 1 次 Grok 调用${selectedProducts.size?'，本次最多 '+selectedProducts.size+' 次':''}；同商品所有款式与颜色共用，不按款数或颜色数追加场景调用。`:"提取商品背景风格 · 同商品多款多色共用。Grok 根据所选商品图的场景氛围、配色、材质与光线重新设计背景。每个来源商品分别准备一次，已有相同来源背景可复用。背景准备与印花生成分别计入实际调用。",summary);
    text("small",artworkState?.reuse?"已有印花直接用于套版，不再生成新印花；商品底图准备与草稿上传仍按流程处理。":"透明印花统一使用 IMG2.0，每张最多 3 次尝试，成功即停止；仅透明检查失败或明确的临时服务故障会自动重试，计入本批次调用上限。",summary);
    get("shop-count").textContent=`已选 ${selectedShopIds().length} / 50 家`;
  }
  function syncArtworkTemplate(print){
    if(!artworkPicker||!print||pickerSnapshotId===print.snapshotId)return;
    artworkPicker.setTemplate(print.configuration);pickerSnapshotId=print.snapshotId;
  }
  async function syncPrintTemplate(){
    const ticket=generation,selectionTicket=++printSelectionGeneration;
    const current=()=>ticket===generation&&selectionTicket===printSelectionGeneration;
    const workflowId=selector.value,snapshotId=selectedTemplate()?.configuration?.printTemplateSnapshotId;
    if(pickerWorkflowId!==workflowId){pickerWorkflowId=workflowId;pickerSnapshotId=null;artworkPicker?.reset();}
    printLoading=false;printLoadError=false;
    renderTemplateSummary();
    if(!snapshotId)return;
    if(selectedPrintTemplate()){syncArtworkTemplate(selectedPrintTemplate());renderTemplateSummary();return;}
    printLoading=true;renderTemplateSummary();
    try{
      const response=await api(`/api/v1/commerce-print-templates/snapshots/${encodeURIComponent(snapshotId)}`);
      if(!current())return;
      if(response.data?.snapshotId!==snapshotId||!response.data.configuration)throw new Error("模板绑定版本不可用");
      if(!printTemplates.some(template=>template.snapshotId===snapshotId))printTemplates.push(response.data);
      syncArtworkTemplate(response.data);
    }catch(error){if(current()){printLoadError=true;status.textContent="模板印花位置读取失败，请重试；仍失败时请到模板配置重新保存。";}}
    finally{if(current()){printLoading=false;renderTemplateSummary();}}
  }
  function renderShops(){
    const selected=new Set(selectedShopIds());
    shopBox.replaceChildren();
    const matches=shops.filter(s=>s.status==="ACTIVE"
      && ["SHOPEE","TIKTOK_SHOP"].includes(s.platform)
      && ["PH","SG","MY","TH","VN","ID"].includes(String(s.region || "").toUpperCase()));
    matches.forEach(s=>{
      const label=text("label","",shopBox), input=document.createElement("input");
      input.type="checkbox";input.name="shop";input.value=s.id;input.checked=selected.has(s.id);label.append(input);
      text("span",`${s.name} · ${s.platform} / ${s.region}`,label);
      input.addEventListener("change",renderTemplateSummary);
    });
    if(!matches.length) text("p","暂无可用的已授权店铺，请先完成店铺授权。",shopBox);
    renderTemplateSummary();
  }
  async function loadProducts(reset){
    if(loading) return;
    loading=true; get("more").disabled=true;get("search").disabled=true;
    const ticket=generation;
    const next=reset?0:page+1;
    if(reset){query=get("query").value.trim();products.replaceChildren();}
    try{
      const r=await api(`/api/v1/product-captures?page=${next}&size=50&query=${encodeURIComponent(query)}`);
      if(ticket!==generation)return;
      page=r.data.page;pages=r.data.totalPages;
      r.data.content.forEach(p=>{
        const label=text("label","",products), input=document.createElement("input");
        input.type="checkbox";input.value=p.id;input.checked=selectedProducts.has(p.id);label.append(input);
        label.className="batch-source-card";
        const visual=text("div","",label);visual.className="batch-source-visual";
        const fallback=text("span","暂无主图",visual);fallback.className="batch-source-placeholder";
        if(p.thumbnailUrl){
          try {
            const url=new URL(p.thumbnailUrl,globalThis.location?.origin || 'http://localhost');
            if(['http:','https:'].includes(url.protocol)){
              const img=document.createElement('img');img.alt=p.title || '商品主图';img.loading='lazy';img.referrerPolicy='no-referrer';img.src=url.href;
              img.addEventListener('error',()=>{img.remove();});visual.append(img);
            }
          }catch(_){}
        }
        const info=text('div','',label);info.className='batch-source-info';
        text('strong',p.title || '未命名商品',info);
        text('small',`${p.sourcePlatform || '商品'}${p.currency ? ' · '+p.currency : ''}${p.priceMin != null ? ' '+p.priceMin : ''}`,info);

        input.addEventListener("change",()=>{
          if(input.checked)selectedProducts.add(p.id);else selectedProducts.delete(p.id);
          get("selected-count").textContent=`已选 ${selectedProducts.size} 款（含其他搜索页）`;
          renderTemplateSummary();
        });
      });
      if(!r.data.content.length && reset)text("p","未找到商品。请先用采集插件导入。",products);
    }catch(e){if(ticket===generation)status.textContent=e.message;}
    finally{if(ticket===generation){loading=false;get("more").disabled=page+1>=pages;get("search").disabled=false;}}
  }
  async function showBatch(id){
    const ticket=generation;
    const detailTicket=++detailGeneration;
    const current=()=>ticket===generation && detailTicket===detailGeneration;
    try{
      const r=await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}`), b=r.data;
      if(!current())return;
      const detail=get("detail");detail.replaceChildren();
      text("h4",`${b.name} · ${b.status}`,detail);
      text("p",`固定模板快照：${b.templateSnapshotId}；${b.items.length}条实际商品店铺组合。`,detail);
      const assetBox=text("details","",detail);text("summary","设计素材 · 绑定审核后的图片",assetBox);
      text("p","只选择当前商品同版本的已审核设计。绑定不会发布；图片顺序固定，批次审核后不能追加。POD仍需生产规格审核。",assetBox);
      const itemLabel=text("label","目标商品 / 店铺",assetBox),itemSelect=text("select","",itemLabel);
      b.items.forEach(item=>{const option=text("option",`${item.shopName || '店铺'} · 商品 ${item.captureId} · v${item.captureVersion}`,itemSelect);option.value=item.id;});
      const readAssets=text("button","读取已审核图片与绑定记录",assetBox);readAssets.type="button";
      const choices=text("div","",assetBox);choices.className="commerce-asset-choices";
      const assetStatus=text("p","",assetBox);let assetGeneration=0;
      itemSelect.addEventListener("change",()=>{assetGeneration++;choices.replaceChildren();assetStatus.textContent="请重新读取所选商品的设计图片。";});
      readAssets.addEventListener("click",async()=>{
        const assetTicket=++assetGeneration,itemId=itemSelect.value;if(!itemId)return;
        choices.replaceChildren();readAssets.disabled=true;
        try{
          const root=`/api/v1/commerce-batches/${encodeURIComponent(id)}/design-assets`;
          const [available,bound]=await Promise.all([api(`${root}/candidates?itemId=${encodeURIComponent(itemId)}`),api(root)]);
          if(!current()||assetTicket!==assetGeneration)return;
          const existing=bound.data.filter(a=>a.itemId===itemId);
          assetStatus.textContent=`本商品已绑定 ${existing.length} 张。候选来自同版本最近100份设计，绑定记录不可覆盖。`;
          existing.forEach(a=>text("p",`图片 ${a.imagePosition} · 设计 ${a.designId}`,choices));
          available.data.forEach(candidate=>{
            const card=text("article","",choices);text("strong",`设计版本 ${candidate.variantIndex} · ${candidate.mode}`,card);
            const preview=text("img","",card);preview.alt=`设计版本 ${candidate.variantIndex} 预览`;preview.loading="lazy";
            const positionLabel=text("label","图片顺序（1–9）",card),position=text("input","",positionLabel);position.type="number";position.min="1";position.max="9";position.step="1";position.value=String(existing.length+1);
            const confirmLabel=text("label","",card),confirmed=text("input","",confirmLabel);confirmed.type="checkbox";text("span","我已查看图片并确认用于该商品",confirmLabel);
            const bind=text("button","固定这张图片",card);bind.type="button";bind.disabled=true;let imageLoaded=false;
            preview.addEventListener("load",()=>{imageLoaded=true;bind.disabled=b.status!=="DRAFT";});
            preview.addEventListener("error",()=>{imageLoaded=false;bind.disabled=true;text("small","图片读取失败，不能绑定。",card);});
            preview.src=`/api/v1/commerce-designs/${encodeURIComponent(candidate.designId)}/output/image`;
            bind.addEventListener("click",async()=>{
              if(!imageLoaded||!confirmed.checked){assetStatus.textContent="请先查看图片并勾选确认。";return;}
              const imagePosition=Number(position.value);if(!Number.isInteger(imagePosition)||imagePosition<1||imagePosition>9){assetStatus.textContent="图片顺序须为1至9。";return;}
              bind.disabled=true;
              try{await api(root,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({itemId,designId:candidate.designId,contentSha256:candidate.contentSha256,imagePosition,confirmed:true})});
                if(current()&&assetTicket===assetGeneration)assetStatus.textContent=`已固定图片 ${imagePosition}，不会自动发布。`;
              }catch(e){if(current()&&assetTicket===assetGeneration){assetStatus.textContent=e.message;bind.disabled=false;}}
            });
          });
          if(!available.data.length)text("p","暂无已通过审核的同版本设计，请先到图片设计完成生成与审核。",choices);
        }catch(e){if(current()&&assetTicket===assetGeneration)assetStatus.textContent=e.message;}
        finally{readAssets.disabled=false;}
      });
      const check=text("button","检查发布前条件（只读）",detail);check.type="button";
      const reportBox=text("div","",detail);
      if(b.status==="DRAFT"){
        const quotaBox=text("fieldset","",detail);text("legend","预留额度 / 取消草稿",quotaBox);
        text("p","预留仅占用本系统批次额度，不自动发布。按店铺站点当地日期计算；取消会保留记录并释放预留。",quotaBox);
        const dateLabel=text("label","首日刊登日期 ",quotaBox), date=document.createElement("input");date.type="date";dateLabel.append(date);
        const confirmation=text("label","",quotaBox), agree=document.createElement("input");agree.type="checkbox";confirmation.append(agree);text("span","确认占用这些店铺的日额度",confirmation);
        const reserve=text("button","预留额度（不发布）",quotaBox);reserve.type="button";
        const reasonLabel=text("label","取消原因 ",quotaBox),reason=document.createElement("input");reason.maxLength=500;reasonLabel.append(reason);
        const cancel=text("button","取消此草稿并释放额度",quotaBox);cancel.type="button";
        const quotaStatus=text("p","",quotaBox);
        reserve.addEventListener("click",async()=>{
          if(!date.value||!agree.checked){quotaStatus.textContent="请选择日期并确认占用额度。";return;}
          reserve.disabled=true;cancel.disabled=true;
          try{
            const r=await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/quota-reservations`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({firstListingDate:date.value,confirmed:true})});
            if(ticket!==generation)return;
            quotaStatus.textContent=`已预留 ${r.data.length} 条额度，不会自动执行。`;
            if(current())await showBatch(id);
          }catch(e){quotaStatus.textContent=e.message;}
          finally{reserve.disabled=false;cancel.disabled=false;}
        });
        cancel.addEventListener("click",async()=>{
          if(!reason.value.trim()){quotaStatus.textContent="请填写取消原因。";return;}
          if(!window.confirm("确认取消这个草稿批次并释放其预留额度？批次记录将保留。"))return;
          reserve.disabled=true;cancel.disabled=true;
          try{
            await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/quota-reservations/cancel-batch`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({confirmed:true,reason:reason.value.trim()})});
            if(ticket!==generation)return;
            await showBatch(id);
          }catch(e){quotaStatus.textContent=e.message;reserve.disabled=false;cancel.disabled=false;}
        });
      }
      check.addEventListener("click",async()=>{
        check.disabled=true;reportBox.textContent="正在核对商品版本、审核资料和店铺状态…";
        try{
          const response=await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/preflight`);
          if(ticket!==generation)return;
          const report=response.data;reportBox.replaceChildren();
          text("p",`检查 ${report.checkedItems} 条，其中 ${report.itemsWithoutDataBlockers} 条未发现条目级资料问题。检查不代表可执行。`,reportBox);
          report.batchBlockers.forEach(message=>text("p",`批次：${message}`,reportBox));
          report.items.filter(i=>i.blockers.length).forEach(i=>text("p",`商品 ${i.captureId} → 店铺 ${i.shopId}：${i.blockers.join("；")}`,reportBox));
        }catch(e){reportBox.textContent=e.message;}
        finally{check.disabled=false;}
      });
      b.items.forEach(i=>text("p",`第${i.plannedDay}天 · ${i.shopName} · 商品 ${i.captureId} / v${i.captureVersion} · ${i.status}`,detail));
      const reservedBox=text("details","",detail);text("summary","查看已预留日期与额度",reservedBox);
      const reservedStatus=text("p","正在读取额度记录…",reservedBox);
      try{
        const response=await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/quota-reservations`);
        if(!current())return;
        const reservations=response.data;
        reservedStatus.textContent=reservations.length?`当前有效预留 ${reservations.length} 条；预留不代表已发布。`:"当前没有有效预留。";
        const days=new Map();
        reservations.forEach(r=>{
          const key=JSON.stringify([r.shopId,r.listingDate,r.timeZone]);days.set(key,(days.get(key)||0)+1);
        });
        days.forEach((count,key)=>{
          const [shopId,date,zone]=JSON.parse(key), name=b.items.find(i=>i.shopId===shopId)?.shopName||shopId;
          text("p",`${name} · ${date}（${zone}）· ${count} 条`,reservedBox);
        });
      }catch(e){if(current())reservedStatus.textContent=`额度记录读取失败：${e.message}`;}
      const reviewBox=text("fieldset","",detail);text("legend","逐条审核依据",reviewBox);
      const reviewStatus=text("p","正在读取审核记录…",reviewBox);
      try{
        const response=await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/review`);
        if(!current())return;
        const saved=response.data;
        reviewStatus.textContent=saved?`已记录审核人 ${saved.reviewedBy} · ${saved.reviewedAt}；记录不可修改，不代表已发布。`:"填写真实来源或内部记录编号；这些说明不是平台授权或文件真实性验证。POD 商品必须补充生产依据。";
        const evidence=saved?JSON.parse(saved.evidenceJson):[];
        const rows=b.items.map(item=>{
          const section=text("details","",reviewBox);
          text("summary",`${item.shopName} · 商品 ${item.captureId} / v${item.captureVersion}`,section);
          const prior=evidence.find(e=>e.itemId===item.id);
          const fields={};
          [["rightsEvidence","图片及商品使用权依据"],["supplySku","供应商 SKU / 货源记录"],["qualityReview","规格、价格及图片质量复核记录"],["productionEvidence","POD 基础款、生产规格及文件依据（普通商品可留空）"]].forEach(([key,label])=>{
            const wrap=text("label",label,section), input=document.createElement("textarea");
            input.maxLength=2000;input.rows=2;input.value=prior?.[key]||"";input.disabled=!!saved||b.status!=="DRAFT";wrap.append(input);fields[key]=input;
          });
          return {item,fields,section};
        });
        if(!saved && b.status==="DRAFT" && rows.length){
          const label=text("label","",reviewBox), agree=document.createElement("input");agree.type="checkbox";label.append(agree);
          text("span","我已逐条复核，确认依据真实；提交后不可修改",label);
          const submit=text("button","保存审核依据（不发布）",reviewBox);submit.type="button";
          submit.addEventListener("click",async()=>{
            if(!agree.checked){reviewStatus.textContent="请先确认已逐条复核。";return;}
            const items=rows.map(({item,fields})=>({itemId:item.id,captureVersion:item.captureVersion,...Object.fromEntries(Object.entries(fields).map(([key,input])=>[key,input.value.trim()]))}));
            const missing=items.findIndex(e=>!e.rightsEvidence||!e.supplySku||!e.qualityReview);
            if(missing>=0){rows[missing].section.open=true;reviewStatus.textContent="请补齐该条目的使用权、货源和质量复核依据。";return;}
            submit.disabled=true;
            try{
              await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/review`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({confirmed:true,items})});
              if(current())await showBatch(id);
            }catch(e){if(current())reviewStatus.textContent=e.message;}
            finally{submit.disabled=false;}
          });
        }
      }catch(e){if(current())reviewStatus.textContent=`审核记录读取失败：${e.message}。请重新打开批次后重试。`;}
    }catch(e){if(current())status.textContent=e.message;}
  }
  globalThis.addEventListener?.('heyi:workbench-batch-selected',event=>{if(typeof event.detail?.batchId==='string')return showBatch(event.detail.batchId);});
  function renderBatches(batches){
    get("list").replaceChildren();
    batches.forEach(b=>{const button=text("button",`${b.name} · ${b.status}`,get("list"));button.type="button";button.addEventListener("click",()=>showBatch(b.id));});
  }
  get("refresh").addEventListener("click",async()=>{
    if(loading||refreshing||submitting)return;
    const ticket=++generation;printSelectionGeneration++;refreshing=true;printLoading=false;printLoadError=false;get("refresh").disabled=true;selectedProducts.clear();
    products.replaceChildren();get("detail").replaceChildren();get("list").replaceChildren();shopBox.replaceChildren();selector.replaceChildren();
    get("selected-count").textContent="已选 0 款";templates=[];printTemplates=[];shops=[];renderTemplateSummary();
    try{
      const [t,s,b]=await Promise.all([api("/api/v1/commerce-templates"),api("/api/v1/shops"),api("/api/v1/commerce-batches")]);
      if(ticket!==generation)return;
      templates=t.data;shops=s.data;
      templates.forEach(v=>{const option=text("option",`${v.name} · v${v.version}`,selector);option.value=v.snapshotId;});
      selector.value=templates[0]?.snapshotId||"";
      renderShops();renderBatches(b.data);
      status.textContent="已读取当前公司数据。商品每款只分配给一家选中店铺，不自动复制到所有店铺。";
      await Promise.all([syncPrintTemplate(),loadProducts(true)]);
    }catch(e){if(ticket===generation)status.textContent=e.message;}
    finally{if(ticket===generation){refreshing=false;get("refresh").disabled=false;}}
  });
  selector.addEventListener("change",()=>{status.textContent="";return syncPrintTemplate();});
  renderSelector?.addEventListener('change',()=>{explicitRenderPolicy=renderSelector.value;status.textContent='';renderTemplateSummary();});
  form.addEventListener('input',event=>{if(event.target===form.elements.namedItem('name'))renderTemplateSummary();});
  get("search").addEventListener("click",()=>loadProducts(true));
  get("more").addEventListener("click",()=>loadProducts(false));
  form.addEventListener("submit",async event=>{
    event.preventDefault();
    if(submitting||refreshing)return;
    const template=selectedTemplate(),printTemplate=selectedPrintTemplate(),targets=selectedShopIds();
    if(!template){status.textContent="请选择模板。";return;}
    const blocker=templateBlocker();if(blocker){status.textContent=blocker;return;}
    if(!selectedProducts.size||selectedProducts.size>500){status.textContent="请选择1至500款商品。";return;}
    if(!targets.length||targets.length>50){status.textContent="请选择1至50家已授权目标店铺。";return;}
    if(selectedProducts.size<targets.length){status.textContent="商品数量不能少于选中店铺数。";return;}
    let reusedArtworks=null;
    try{
      const artworkState=artworkPicker?.getState();
      if(artworkState?.busy)throw new Error("正在读取已有印花素材，请稍后开始。");
      if(artworkState?.reuse&&artworkState.error)throw new Error(artworkState.error);
      reusedArtworks=artworkPicker?.getSelection()||null;
      if(reusedArtworks)reusedArtworks=JSON.parse(JSON.stringify(reusedArtworks));
      if(artworkState?.reuse&&(!Array.isArray(reusedArtworks)||reusedArtworks.length<1||reusedArtworks.length>20))throw new Error("请选择 1 至 20 款完整的已有印花素材，再开始裂变。");
    }catch(error){status.textContent=error.message;return;}
    const body={...launchInput(reusedArtworks),...(printTemplate.configuration?.photoPrints?.length?{renderPolicy:drapeReuse()?'GROK_DRAPE_REUSE_V1':'GROK_SCENE_V1'}:{})};
    const replay=preparedRequest(body);
    if(replay){if(renderSelector)renderSelector.value=requestPolicy(replay);renderTemplateSummary();}
    else if(drapeReuse()){const issue=drapeBlocker(printTemplate?.configuration);if(issue){status.textContent=issue;return;}}
    const ticket=generation;
    const controls=Array.from(form.querySelectorAll("input,select,button"));
    const disabled=controls.map(c=>c.disabled);submitting=true;controls.forEach(c=>c.disabled=true);artworkPicker?.setLocked(true);get("refresh").disabled=true;
    status.textContent="正在检查全部商品与店铺的上传配置…";
    const controller=new AbortController();launchController=controller;let timedOut=false,onAbort,deadline;
    const interrupted=new Promise((_,reject)=>{
      onAbort=()=>reject(new Error(timedOut?"尚未确认启动结果，重试会核对原批次":"启动请求等待已中断"));
      controller.signal.addEventListener("abort",onAbort,{once:true});
    });
    try{
      let request=replay;
      if(!request){
        if(!globalThis.CommercePublication)throw new Error("上传准备组件未加载，请刷新页面");
        const publicationRequests=await Promise.race([globalThis.CommercePublication.prepare(body.targets,{shops,isCurrent:()=>ticket===generation,
          onProgress:(done,total)=>{if(ticket===generation)status.textContent=`正在检查上传配置 ${done} / ${total}…`;}}),interrupted]);
        if(ticket!==generation)return;
        request=launchRequest({...body,publicationRequests});
      }
      if(ticket!==generation)return;
      status.textContent="上传配置已就绪，正在启动提取、裂变、套版和草稿上传…";
      deadline=setTimeout(()=>{timedOut=true;controller.abort();},60000);
      // Abort only this client's wait. The server may already have started;
      // retaining the same body/key lets a retry recover that persisted run.
      const r=await Promise.race([api("/api/v1/commerce-batches/create-and-start",{method:"POST",headers:{"Content-Type":"application/json","Idempotency-Key":request.key},body:request.body,signal:controller.signal}),interrupted]);
      if(ticket!==generation)return;
      const {batch,execution,maxModelCalls}=r.data||{};
      if(!batch?.id||!execution?.id||execution.batchId!==batch.id||typeof execution.status!=="string"||!execution.status
        ||!Number.isInteger(maxModelCalls)||maxModelCalls<0||maxModelCalls>1000)throw new Error("创建与启动结果尚未确认，请使用原配置重试以恢复已有批次");
      const runStatus={QUEUED:"已排队，等待执行提取、裂变和套版",RUNNING:"正在执行提取、裂变和套版",PAUSING:"正在等待暂停",PAUSED:"执行已暂停",WAITING_INPUT:"执行等待补充资料",WAITING_CAPABILITY:"执行等待所需功能就绪",UNKNOWN:"执行结果待核查",FAILED:"执行遇到问题，请查看进度",COMPLETED:execution.stage==='PUBLISH'?"已上传草稿":"创作已完成"}[execution.status]||`执行状态：${execution.status}`;
      status.textContent=`${batch.name}：${runStatus}。本批次模型调用上限 ${maxModelCalls} 次，含透明印花每张最多 3 次尝试；${requestPolicy(request)==='GROK_DRAPE_REUSE_V1'?'自然搭放按每个来源商品 1 次计入，不按款色重复调用；':''}成功即停止，不会为用满上限补生成。自动检查后上传草稿。`;clearPendingRequest();
      selectedProducts.clear();products.querySelectorAll('input[type="checkbox"]').forEach(c=>c.checked=false);
      get("selected-count").textContent="已选 0 款";
      // The workbench selects this persisted run and starts polling. Do not
      // hold the launch response behind unrelated legacy review/detail reads.
      globalThis.dispatchEvent?.(new CustomEvent('heyi:batch-created',{detail:{batchId:batch.id,batch,execution,maxModelCalls}}));
    }catch(e){if(ticket===generation){
      // Only these explicit pre-generation validation responses prove no run
      // was started. Network/unknown failures retain the original frozen key.
      const preparationRejected=e.status===409&&/^(?:发布配置缺失|自动准备发布参数未完成)[:：]/.test(e.message||'');
      const templateRejected=e.status===400&&/^(?:商品套版模板预检未通过[:：]|模型或印花位置已变化|印花渲染顺序已变化|请先保存当前模型的完整印花效果)/.test(e.message||'');
      if(preparationRejected||templateRejected)clearPendingRequest();
      status.textContent=timedOut?"等待响应已超过60秒，尚未确认启动结果，重试会核对原批次。请保留当前配置重试。":e.preparationFailed?e.message:
        preparationRejected?`${e.message}；尚未启动生图，重试将重新准备上传配置。`:templateRejected?`${e.message}。本次未启动裂变；修复模板后可重新提交。`:`${e.message}；保留当前配置重试会核对原批次。`;
    }}
    finally{
      clearTimeout(deadline);controller.signal.removeEventListener("abort",onAbort);if(launchController===controller)launchController=null;
      if(ticket===generation){submitting=false;controls.forEach((c,i)=>c.disabled=disabled[i]);artworkPicker?.setLocked(false);get("refresh").disabled=false;}
    }
  });
  const artworkHost=get("artwork-picker");if(artworkHost&&globalThis.CommerceArtworkPicker)artworkPicker=globalThis.CommerceArtworkPicker.mount({host:artworkHost,api,onChange:renderTemplateSummary});
})();
