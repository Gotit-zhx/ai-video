(() => {
  const root=document.getElementById('commerce-design-workspace');if(!root)return;
  const text=(tag,value,parent=root)=>{const e=document.createElement(tag);e.textContent=value;parent.append(e);return e;};
  const button=(label,parent=root)=>{const e=text('button',label,parent);e.type='button';return e;};
  const contextBar=text('div','');contextBar.className='commerce-source-context';
  text('small','设计裂变 / 来源商品与版本全程关联',contextBar);
  const sourceBack=button('回看来源商品',contextBar);sourceBack.hidden=true;
  const searchLabel=text('label','搜索已采集商品 '),query=text('input','',searchLabel);
  const search=button('搜索商品'),previous=button('上一页'),next=button('下一页');
  const pageLabel=text('p',''),products=text('div','');products.className='commerce-batch-products';
  const selectedLabel=text('p','尚未选择来源商品');
  const form=text('form','');form.className='commerce-plan-form';
  const mode=text('select','',text('label','设计用途',form));
  [['STOCK_MARKETING','现货营销图（不改变实物）'],['POD_DESIGN','POD设计（需生产文件）']].forEach(([value,label])=>{text('option',label,mode).value=value;});
  const variants=text('input','',text('label','设计草稿数量（1–10）',form));variants.type='number';variants.min='1';variants.max='10';variants.step='1';variants.value='10';variants.defaultValue='10';variants.required=true;
  const brief=text('textarea','',text('label','设计说明',form));brief.maxLength=2000;brief.required=true;
  const rights=text('textarea','',text('label','真实使用权依据或内部记录编号',form));rights.maxLength=2000;rights.required=true;
  const create=text('button','创建草稿（不生成图片）',form);create.type='submit';
  const status=text('p',''),refresh=button('刷新设计草稿'),drafts=text('div','');
  const library=text('details','');library.className='studio-source-picker';
  text('summary','选择 / 更换来源商品',library);
  [searchLabel,search,previous,next,pageLabel,products].forEach(node=>library.append(node));
  const canvas=text('div','');canvas.className='studio-design-canvas';
  const sourcePane=text('section','',canvas);sourcePane.className='studio-source-pane';
  text('h3','来源参考',sourcePane);sourcePane.append(selectedLabel);
  const sourcePreview=text('div','尚未选择来源商品。可从商品资料库带入，或展开上方商品选择器。',sourcePane);sourcePreview.className='studio-source-preview';
  const recipe=text('section','',canvas);recipe.className='studio-recipe';text('h3','创作设置',recipe);recipe.append(form);
  const results=text('section','');results.className='studio-design-results';text('h3','设计版本与生成结果',results);
  [status,refresh,drafts].forEach(node=>results.append(node));
  text('p','选择来源商品后，设计版本会显示在这里。尚未生成的草稿不会显示示例图片。',drafts);
  const extraction=text('div','',sourcePane);extraction.className='studio-extraction-placeholder';text('strong','图案提取',extraction);text('p','提取能力待接入，暂不展示虚构的透明底结果。',extraction);
  const dock=text('footer','');dock.className='studio-design-dock';
  const back=button('← 回到采集商品',dock);back.addEventListener('click',()=>{
    if(selected)sourceBack.click();else document.getElementById('nav-publish').click();
  });
  text('small','通过质量复核的设计，可在批次中选择并绑定。',dock);
  const toBatch=button('下一步 · 批次准备 →',dock);toBatch.addEventListener('click',()=>document.querySelector('[data-commerce-route="commerce-batches"]').click());
  status.setAttribute?.('role','status');
  const flowNotice=text('p','');flowNotice.className='studio-flow-notice';flowNotice.hidden=true;flowNotice.setAttribute?.('role','status');
  const workboard=text('section','');workboard.className='studio-workboard';
  [contextBar,library,sourcePane,results,dock].forEach(node=>workboard.append(node));
  root.append(recipe);root.append(flowNotice);canvas.remove?.();
  sourcePane.append(selectedLabel);sourcePane.append(extraction);
  text('p','只处理自有或获授权素材。草稿不调用 AI；生成需单独确认。质量通过不等于已上架。',recipe).className='studio-recipe-note';
  function flow(message,working=false){
    flowNotice.hidden=false;flowNotice.textContent=message;
    flowNotice.classList?.toggle('is-working',working);
    flowNotice.setAttribute?.('aria-busy',String(working));
  }
  let generation=0,selectionGeneration=0,draftRevision=0,selected=null,pending=null,page=0,totalPages=0,searchQuery='',busy=false;
  globalThis.addEventListener?.('heyi:workbench-scope-reset',()=>{selectionGeneration++;draftRevision++;selected=null;pending=null;sourceBack.hidden=true;sourcePreview.replaceChildren();sourcePreview.textContent='等待批次商品';selectedLabel.textContent='尚未选择来源商品';drafts.replaceChildren();flowNotice.hidden=true;});
  async function selectSource(id,title,expectedVersion){
    if(busy){status.textContent='正在保存设计草稿，请完成后再切换来源。';return;}
    const selectionTicket=++selectionGeneration,companyTicket=generation;
    selected=null;pending=null;sourceBack.hidden=true;selectedLabel.textContent='正在读取商品版本…';
    drafts.replaceChildren();
    sourcePreview.replaceChildren();sourcePreview.textContent='正在读取参考图…';
    sourcePane.classList?.add('is-loading');flow('正在带入来源商品与参考图片…',true);
    try{
      const detail=await api(`/api/v1/product-captures/${encodeURIComponent(id)}`);
      if(selectionTicket!==selectionGeneration||companyTicket!==generation)return;
      const capture=detail.data;
      if(capture.id!==id||!Number.isSafeInteger(capture.version))throw new Error('商品版本信息无效，请重新读取。');
      if(expectedVersion!==undefined&&capture.version!==expectedVersion)throw new Error('商品版本已变化，请回商品资料库刷新后重新进入设计。');
      selected={id,version:capture.version};sourceBack.hidden=false;
      selectedLabel.textContent=`已固定：${capture.title||title||id} · v${selected.version} · ${capture.images.length} 张参考图`;
      sourcePreview.textContent='';sourcePreview.replaceChildren();
      capture.images.slice(0,1).forEach(source=>{
        const raw=source.processedUrl||source.originalUrl||source.url;if(!raw)return;
        try{const url=new URL(raw,location.origin);if(!['http:','https:'].includes(url.protocol))return;
          const img=text('img','',sourcePreview);img.alt='来源商品参考图';img.referrerPolicy='no-referrer';img.src=url.href;
          img.addEventListener('error',()=>{if(selectionTicket!==selectionGeneration||companyTicket!==generation)return;sourcePreview.replaceChildren();sourcePreview.textContent='来源图片暂时无法加载，请回到商品资料库核对原图链接。';});
        }catch(_){/* Invalid reference URLs do not become image requests. */}
      });
      if(!sourcePreview.children.length)sourcePreview.textContent='该来源暂无可预览的图片。';
      library.open=false;
      flow('来源已带入。确认创作设置后，将自动保存并展示设计草稿；不会自动产生模型费用。');
      await loadDrafts();
    }catch(e){if(selectionTicket===selectionGeneration&&companyTicket===generation){selectedLabel.textContent=e.message;flow('来源读取未完成，请检查提示后重试。');}}
    finally{if(selectionTicket===selectionGeneration&&companyTicket===generation)sourcePane.classList?.remove('is-loading');}
  }
  sourceBack.addEventListener('click',()=>{if(selected)globalThis.dispatchEvent(new CustomEvent('heyi:design-source-return',{detail:{captureId:selected.id}}));});
  globalThis.addEventListener?.('heyi:design-source-selected',event=>{
    if(typeof event.detail?.captureId!=='string'||!Number.isSafeInteger(event.detail.captureVersion))return;
    return selectSource(event.detail.captureId,'',event.detail.captureVersion);
  });
  async function loadPage(target){
    const ticket=++generation;search.disabled=previous.disabled=next.disabled=true;
    try{
      const r=await api(`/api/v1/product-captures?page=${target}&size=50&query=${encodeURIComponent(searchQuery)}`);
      if(ticket!==generation)return;products.replaceChildren();page=r.data.page;totalPages=r.data.totalPages;
      pageLabel.textContent=totalPages?`第 ${page+1} / ${totalPages} 页`:'暂无商品';
      r.data.content.forEach(p=>{
        const choose=button(`${p.title} · ${p.sourcePlatform}`,products);
        choose.addEventListener('click',()=>selectSource(p.id,p.title));
      });
    }catch(e){if(ticket===generation)status.textContent=e.message;}
    finally{if(ticket===generation){search.disabled=false;previous.disabled=page===0;next.disabled=page+1>=totalPages;}}
  }
  search.addEventListener('click',()=>{searchQuery=query.value.trim();return loadPage(0);});
  previous.addEventListener('click',()=>loadPage(Math.max(0,page-1)));next.addEventListener('click',()=>loadPage(page+1));
  previous.disabled=next.disabled=true;
  async function showGeneration(draft,box,ticket){
    box.replaceChildren();text('p','正在读取生成状态…',box);
    try{
      const response=await api(`/api/v1/commerce-designs/${encodeURIComponent(draft.id)}/generation`);if(ticket!==generation)return;
      const state=response.data;box.replaceChildren();text('p',`${state.status} · ${state.message}`,box);
      const reload=button('刷新生成状态',box);reload.addEventListener('click',()=>showGeneration(draft,box,ticket));
      if(state.status==='OUTPUT_ARCHIVED'){await showOutput(draft.id,box,ticket);return;}
      if(['SUBMITTING','UNKNOWN'].includes(state.status))return;
      const downloadOnly=state.status==='RESULT_RECEIVED';
      if(!state.generationEnabled&&!downloadOnly)return;
      const sources=JSON.parse(draft.sourceImagesJson),inputs=[];
      sources.forEach((source,index)=>{
        const label=text('label',`参考图 ${index+1} · ${source.role||'GALLERY'}`,box),input=text('input','',label);input.type='checkbox';input.value=source.imageId;input.checked=state.referenceImageIds.includes(source.imageId);input.disabled=!!state.attemptId;inputs.push(input);
      });
      const confirmation=text('input','',text('label',downloadOnly?'确认重试下载已有结果（不重新生成）':'确认本设计生成一张图片，可能产生模型费用；不自动发布',box));confirmation.type='checkbox';
      const submit=button(downloadOnly?'重试结果下载':'确认生成单张图片',box),message=text('p','',box);
      submit.addEventListener('click',async()=>{
        const imageIds=state.attemptId?state.referenceImageIds:inputs.filter(i=>i.checked).map(i=>i.value);
        if(!confirmation.checked||imageIds.length<1||imageIds.length>4){message.textContent='请选择1至4张参考图并明确确认。';return;}
        submit.disabled=reload.disabled=true;message.textContent='正在处理；即使等待超时也请先刷新状态，不要重复生成。';
        flow(downloadOnly?'正在归档已有结果，成功后自动展示质量复核。':'已确认生成，正在等待真实结果；归档成功后自动展示质量复核。',true);
        try{const result=await api(`/api/v1/commerce-designs/${encodeURIComponent(draft.id)}/generation`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({imageIds,confirmed:true})});if(ticket!==generation)return;message.textContent=result.data.message;await showGeneration(draft,box,ticket);}
        catch(e){if(ticket===generation)message.textContent=`${e.message}。请刷新生成状态后再决定下一步。`;}
        finally{reload.disabled=false;if(ticket===generation)flow('生成请求已结束。请核对当前真实状态；不确定的任务不会自动重复生成。');}
      });
    }catch(e){if(ticket===generation)text('p',e.message,box);}
  }
  async function showOutput(designId,box,ticket){
    box.replaceChildren();text('p','正在读取生成结果…',box);
    try{
      const response=await api(`/api/v1/commerce-designs/${encodeURIComponent(designId)}/output`);if(ticket!==generation)return;
      box.replaceChildren();const output=response.data;if(!output){text('p','尚无归档生成结果，草稿不等于已生成图片。',box);return;}
      text('p',`图片状态：${output.status}；质量批准不等于生产或发布批准。`,box);
      const image=text('img','',box);image.src=`/api/v1/commerce-designs/${encodeURIComponent(designId)}/output/image`;image.alt='待复核的生成结果';image.style.maxWidth='100%';image.style.maxHeight='480px';
      if(output.review){text('p',`${output.review.decision} · ${output.review.reviewedAt} · ${output.review.notes}`,box);
        if(output.status==='QUALITY_APPROVED'&&output.review.decision==='APPROVE')await showBatchTargets(designId,output,image,box,ticket);
        return;}
      const checks={};[['singleImage','单张图片，无九宫格或拼贴'],['noAnatomyArtifacts','没有多手、多肢体等异常'],['matchesDesignScope','符合设计用途；现货未改变实物款式'],['noInventedBackPrint','未凭空添加或复制背面图案']].forEach(([key,label])=>{
        const wrap=text('label',label,box),input=text('input','',wrap);input.type='checkbox';checks[key]=input;
      });
      const notes=text('textarea','',text('label','复核说明（必填）',box));notes.maxLength=2000;
      const approve=button('确认质量通过',box),reject=button('不通过',box),reviewStatus=text('p','',box);
      let loaded=false;approve.disabled=reject.disabled=true;
      image.addEventListener('load',()=>{loaded=true;approve.disabled=reject.disabled=false;});
      image.addEventListener('error',()=>{reviewStatus.textContent='图片加载失败，请重新查看后审核。';});
      async function review(decision){
        if(!loaded||!notes.value.trim()){reviewStatus.textContent='请先查看图片并填写说明。';return;}
        approve.disabled=reject.disabled=true;
        try{await api(`/api/v1/commerce-designs/${encodeURIComponent(designId)}/output/review`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({contentSha256:output.contentSha256,decision,checks:Object.fromEntries(Object.entries(checks).map(([key,input])=>[key,input.checked])),notes:notes.value.trim(),confirmed:true})});if(ticket===generation)await showOutput(designId,box,ticket);}
        catch(e){if(ticket===generation)reviewStatus.textContent=e.message;}finally{approve.disabled=reject.disabled=false;}
      }
      approve.addEventListener('click',()=>review('APPROVE'));reject.addEventListener('click',()=>review('REJECT'));
    }catch(e){if(ticket===generation)text('p',e.message,box);}
  }
  async function showBatchTargets(designId,output,image,box,ticket){
    const sourceTicket=selectionGeneration;
    const current=()=>ticket===generation&&sourceTicket===selectionGeneration;
    const section=text('section','',box);section.className='studio-batch-targets';
    text('h4','下一环节 · 绑定上架批次',section);
    const message=text('p','正在匹配来源版本、设计用途和可用图片位置…',section);
    try{
      const response=await api(`/api/v1/commerce-designs/${encodeURIComponent(designId)}/batch-targets`);if(!current())return;
      const targets=response.data;
      if(!targets.length){message.textContent='没有匹配的草稿批次：可先在批次准备中加入同一商品版本、匹配模板和目标店铺。已审核或已绑定的批次不会重复出现。';return;}
      message.textContent='选择批次后绑定当前审核图片；不会发布商品或生成新图片。';
      const select=text('select','',text('label','目标批次 / 店铺',section));
      targets.forEach((target,index)=>{text('option',`${target.batchName} / ${target.shopName} · 图片 ${target.nextImagePosition}`,select).value=String(index);});select.value='0';
      const consent=text('input','',text('label','确认将当前审核结果绑定到所选批次（不执行发布）',section));consent.type='checkbox';
      const bind=button('确认绑定并继续',section);bind.disabled=true;
      let viewed=Boolean(image.complete&&image.naturalWidth>0),saving=false;
      const update=()=>{bind.disabled=!viewed||!consent.checked||saving;};
      image.addEventListener('load',()=>{viewed=true;update();});image.addEventListener('error',()=>{viewed=false;update();message.textContent='图片加载失败，不能确认绑定。请重新查看结果。';});consent.addEventListener('change',update);
      select.addEventListener('change',()=>{consent.checked=false;update();});
      update();
      bind.addEventListener('click',async()=>{
        if(!current()||!viewed||!consent.checked||saving)return;
        const target=targets[Number(select.value)];if(!target)return;
        saving=true;update();select.disabled=consent.disabled=true;
        try{
          await api(`/api/v1/commerce-batches/${encodeURIComponent(target.batchId)}/design-assets`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({itemId:target.itemId,designId,contentSha256:output.contentSha256,imagePosition:target.nextImagePosition,confirmed:true})});
          if(!current())return;
          message.textContent=`已绑定到 ${target.batchName} / ${target.shopName}。尚未发布商品，可进入批次准备继续核对。`;
          bind.textContent='已绑定';
          const next=button('进入批次准备 →',section);next.addEventListener('click',()=>document.querySelector('[data-commerce-route="commerce-batches"]').click());
        }catch(e){if(current()){message.textContent=`${e.message}。如位置或审核状态已变化，请重新读取结果；不要重复创建批次。`;saving=false;select.disabled=consent.disabled=false;update();}}
      });
    }catch(e){if(current())message.textContent=`无法读取可用批次：${e.message}`;}
  }
  async function loadDrafts(){
    const ticket=generation,sourceTicket=selectionGeneration,revision=++draftRevision;
    const current=()=>ticket===generation&&sourceTicket===selectionGeneration&&revision===draftRevision;
    refresh.disabled=true;
    try{const r=await api('/api/v1/commerce-designs');if(!current())return;drafts.replaceChildren();
      const visible=selected?r.data.filter(d=>d.captureId===selected.id&&d.captureVersion===selected.version):r.data;
      visible.sort((a,b)=>a.variantIndex-b.variantIndex);
      if(!visible.length)text('p','当前来源尚无设计版本。确认右侧创作设置后创建草稿，生成结果将显示在这里。',drafts);
      const previews=[];
      visible.forEach((d,index)=>{const box=text('details','',drafts);const summary=text('summary',`V${String(d.variantIndex).padStart(2,'0')} · ${d.status}`,box);
        const preview=text('span',index<12?'正在读取结果…':'展开后查看结果',summary);preview.className='studio-real-preview';
        if(index<12)previews.push(async()=>{
          try{const response=await api(`/api/v1/commerce-designs/${encodeURIComponent(d.id)}/output`);if(!current())return;
            const output=response.data;
            if(!output){preview.textContent='尚无生成结果';return;}
            preview.textContent=output.status==='QUALITY_APPROVED'?'已通过质量复核':'已有结果 · 点击复核';
            preview.style.backgroundImage=`url("/api/v1/commerce-designs/${encodeURIComponent(d.id)}/output/image")`;
            preview.setAttribute?.('aria-label','已归档设计预览，点击展开查看完整图片与审核状态');
          }catch(e){if(current())preview.textContent='预览读取失败 · 可展开重试';}
        });
        text('p',d.mode==='POD_DESIGN'?'POD设计：尚需生产文件与审核':'现货营销图：保留真实款式、颜色与结构',box);text('p',d.brief,box);text('p',`创建时间：${d.createdAt}；草稿不是生成结果。`,box);
        const view=button('查看结果 / 质量审核',box),outputBox=text('div','',box);view.addEventListener('click',()=>showOutput(d.id,outputBox,ticket));
        const generate=button('生成图片 / 查看进度',box),generationBox=text('div','',box);generate.addEventListener('click',()=>showGeneration(d,generationBox,ticket));
      });
      // Read-only previews, at most three in flight; never triggers paid generation.
      let nextPreview=0;
      await Promise.all(Array.from({length:Math.min(3,previews.length)},async()=>{while(current()&&nextPreview<previews.length)await previews[nextPreview++]();}));
    }catch(e){if(current())status.textContent=e.message;}finally{if(revision===draftRevision)refresh.disabled=false;}
  }
  refresh.addEventListener('click',loadDrafts);form.addEventListener('input',()=>{pending=null;});
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy)return;
    if(!selected){status.textContent='请先选择来源商品并读取版本。';return;}
    const body=JSON.stringify({captureId:selected.id,captureVersion:selected.version,mode:mode.value,variants:Number(variants.value),brief:brief.value.trim(),rightsEvidence:rights.value.trim()});
    if(!pending||pending.body!==body)pending={key:crypto.randomUUID(),body};
    const request=pending,ticket=generation;busy=true;create.disabled=true;
    flow('正在保存设计草稿，成功后自动进入版本列表…',true);
    try{const r=await api('/api/v1/commerce-designs',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':request.key},body:request.body});
      if(ticket!==generation)return;status.textContent=`已保存 ${r.data.length} 个设计草稿，尚未生成图片。`;await loadDrafts();
      if(ticket===generation){flow('草稿已保存，已进入设计版本区。真实生成仍需确认；尚未接入的环节不会跳过。');results.scrollIntoView?.({block:'start',behavior:globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'});}
    }catch(e){if(ticket===generation){status.textContent=`${e.message}；内容不变重试会复用原请求。`;flow('保存未完成，已停留在当前步骤。请检查错误后重试。');}}
    finally{busy=false;create.disabled=false;}
  });
  globalThis.addEventListener?.('heyi:company-reset',()=>{generation++;selectionGeneration++;selected=null;pending=null;sourceBack.hidden=true;flowNotice.hidden=true;sourcePane.classList?.remove('is-loading');sourcePreview.replaceChildren();sourcePreview.textContent='尚未选择来源商品';form.reset();query.value='';searchQuery='';page=totalPages=0;products.replaceChildren();drafts.replaceChildren();pageLabel.textContent='';selectedLabel.textContent='尚未选择来源商品';status.textContent='';search.disabled=false;previous.disabled=next.disabled=true;});
})();
