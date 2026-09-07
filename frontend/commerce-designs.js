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
  async function loadDrafts(){
    const ticket=generation;refresh.disabled=true;
    try{const r=await api('/api/v1/commerce-designs');if(ticket!==generation)return;drafts.replaceChildren();
      if(!r.data.length)text('p','暂无设计草稿。',drafts);
      r.data.forEach(d=>{const box=text('details','',drafts);text('summary',`商品 ${d.captureId} / v${d.captureVersion} · 变体 ${d.variantIndex} · ${d.status}`,box);
        text('p',d.mode==='POD_DESIGN'?'POD设计：尚需生产文件与审核':'现货营销图：保留真实款式、颜色与结构',box);text('p',d.brief,box);text('p',`创建时间：${d.createdAt}；草稿不是生成结果。`,box);
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
