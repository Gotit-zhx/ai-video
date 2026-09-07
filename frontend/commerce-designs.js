(() => {
  const root=document.getElementById('commerce-design-workspace');if(!root)return;
  const text=(tag,value,parent=root)=>{const e=document.createElement(tag);e.textContent=value;parent.append(e);return e;};
  const button=(label,parent=root)=>{const e=text('button',label,parent);e.type='button';return e;};
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
  let generation=0,selectionGeneration=0,selected=null,pending=null,page=0,totalPages=0,searchQuery='',busy=false;
  async function loadPage(target){
    const ticket=++generation;search.disabled=previous.disabled=next.disabled=true;
    try{
      const r=await api(`/api/v1/product-captures?page=${target}&size=50&query=${encodeURIComponent(searchQuery)}`);
      if(ticket!==generation)return;products.replaceChildren();page=r.data.page;totalPages=r.data.totalPages;
      pageLabel.textContent=totalPages?`第 ${page+1} / ${totalPages} 页`:'暂无商品';
      r.data.content.forEach(p=>{
        const choose=button(`${p.title} · ${p.sourcePlatform}`,products);
        choose.addEventListener('click',async()=>{
          if(busy)return;
          const selectionTicket=++selectionGeneration,companyTicket=generation;selected=null;pending=null;selectedLabel.textContent='正在读取商品版本…';
          try{const detail=await api(`/api/v1/product-captures/${encodeURIComponent(p.id)}`);if(selectionTicket!==selectionGeneration||companyTicket!==generation)return;
            selected={id:p.id,version:detail.data.capture.version};selectedLabel.textContent=`已固定：${p.title} · v${selected.version} · ${detail.data.images.length} 张参考图`;
          }catch(e){if(selectionTicket===selectionGeneration&&companyTicket===generation)selectedLabel.textContent=e.message;}
        });
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
      if(['SUBMITTING','UNKNOWN','OUTPUT_ARCHIVED'].includes(state.status))return;
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
        try{const result=await api(`/api/v1/commerce-designs/${encodeURIComponent(draft.id)}/generation`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({imageIds,confirmed:true})});if(ticket!==generation)return;message.textContent=result.data.message;await showGeneration(draft,box,ticket);}
        catch(e){if(ticket===generation)message.textContent=`${e.message}。请刷新生成状态后再决定下一步。`;}
        finally{reload.disabled=false;}
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
      if(output.review){text('p',`${output.review.decision} · ${output.review.reviewedAt} · ${output.review.notes}`,box);return;}
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
  async function loadDrafts(){
    const ticket=generation;refresh.disabled=true;
    try{const r=await api('/api/v1/commerce-designs');if(ticket!==generation)return;drafts.replaceChildren();
      if(!r.data.length)text('p','暂无设计草稿。',drafts);
      r.data.forEach(d=>{const box=text('details','',drafts);text('summary',`商品 ${d.captureId} / v${d.captureVersion} · 变体 ${d.variantIndex} · ${d.status}`,box);
        text('p',d.mode==='POD_DESIGN'?'POD设计：尚需生产文件与审核':'现货营销图：保留真实款式、颜色与结构',box);text('p',d.brief,box);text('p',`创建时间：${d.createdAt}；草稿不是生成结果。`,box);
        const view=button('查看结果 / 质量审核',box),outputBox=text('div','',box);view.addEventListener('click',()=>showOutput(d.id,outputBox,ticket));
        const generate=button('生成图片 / 查看进度',box),generationBox=text('div','',box);generate.addEventListener('click',()=>showGeneration(d,generationBox,ticket));
      });
    }catch(e){if(ticket===generation)status.textContent=e.message;}finally{refresh.disabled=false;}
  }
  refresh.addEventListener('click',loadDrafts);form.addEventListener('input',()=>{pending=null;});
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy)return;
    if(!selected){status.textContent='请先选择来源商品并读取版本。';return;}
    const body=JSON.stringify({captureId:selected.id,captureVersion:selected.version,mode:mode.value,variants:Number(variants.value),brief:brief.value.trim(),rightsEvidence:rights.value.trim()});
    if(!pending||pending.body!==body)pending={key:crypto.randomUUID(),body};
    const request=pending,ticket=generation;busy=true;create.disabled=true;
    try{const r=await api('/api/v1/commerce-designs',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':request.key},body:request.body});
      if(ticket!==generation)return;status.textContent=`已保存 ${r.data.length} 个设计草稿，尚未生成图片。`;await loadDrafts();
    }catch(e){if(ticket===generation)status.textContent=`${e.message}；内容不变重试会复用原请求。`;}
    finally{busy=false;create.disabled=false;}
  });
  globalThis.addEventListener?.('heyi:company-reset',()=>{generation++;selectionGeneration++;selected=null;pending=null;form.reset();query.value='';searchQuery='';page=totalPages=0;products.replaceChildren();drafts.replaceChildren();pageLabel.textContent='';selectedLabel.textContent='尚未选择来源商品';status.textContent='';search.disabled=false;previous.disabled=next.disabled=true;});
})();
