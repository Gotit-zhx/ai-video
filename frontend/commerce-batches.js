(() => {
  const form=document.getElementById("commerce-batch-form");
  if(!form) return;
  const get=id=>document.getElementById(`commerce-batch-${id}`);
  const status=get("status"), selector=get("template"), shopBox=get("shops"), products=get("products");
  let templates=[],shops=[], selectedProducts=new Set(), page=0, query="", pages=0;
  let pendingRequest=null, generation=0, loading=false, detailGeneration=0;
  const text=(tag,value,parent)=>{const e=document.createElement(tag);e.textContent=value;parent.append(e);return e;};
  const invalidate=()=>{pendingRequest=null;};
  globalThis.addEventListener?.("heyi:company-reset",()=>{
    generation++; templates=[];shops=[];selectedProducts.clear();pendingRequest=null;page=0;pages=0;
    [products,shopBox,selector,get("list"),get("detail")].forEach(e=>e.replaceChildren());
    status.textContent="";get("selected-count").textContent="已选 0 款";form.reset();
  });
  function selectedTemplate(){return templates.find(t=>t.snapshotId===selector.value);}
  function renderShops(){
    shopBox.replaceChildren();
    const config=selectedTemplate()?.configuration;
    const matches=shops.filter(s=>s.status==="ACTIVE" && s.platform===config?.platform && s.region===config?.market);
    matches.forEach(s=>{
      const label=text("label","",shopBox), input=document.createElement("input");
      input.type="checkbox";input.name="shop";input.value=s.id;label.append(input);
      text("span",`${s.name} · ${s.platform} / ${s.region}`,label);
    });
    if(!matches.length) text("p","没有匹配的已授权店铺，请选择模板或先完成店铺授权。",shopBox);
    invalidate();
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
        text("span",`${p.title} · ${p.sourcePlatform} · ${p.status}`,label);
        input.addEventListener("change",()=>{
          if(input.checked)selectedProducts.add(p.id);else selectedProducts.delete(p.id);
          get("selected-count").textContent=`已选 ${selectedProducts.size} 款（含其他搜索页）`;invalidate();
        });
      });
      if(!r.data.content.length && reset)text("p","未找到商品。请先用采集插件导入。",products);
    }catch(e){status.textContent=e.message;}
    finally{loading=false;get("more").disabled=page+1>=pages;get("search").disabled=false;}
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
    }catch(e){status.textContent=e.message;}
  }
  function renderBatches(batches){
    get("list").replaceChildren();
    batches.forEach(b=>{const button=text("button",`${b.name} · ${b.status}`,get("list"));button.type="button";button.addEventListener("click",()=>showBatch(b.id));});
  }
  get("refresh").addEventListener("click",async()=>{
    if(loading)return;
    const ticket=++generation;get("refresh").disabled=true;selectedProducts.clear();pendingRequest=null;
    products.replaceChildren();get("detail").replaceChildren();get("list").replaceChildren();shopBox.replaceChildren();selector.replaceChildren();
    get("selected-count").textContent="已选 0 款";templates=[];shops=[];
    try{
      const [t,s,b]=await Promise.all([api("/api/v1/commerce-templates"),api("/api/v1/shops"),api("/api/v1/commerce-batches")]);
      if(ticket!==generation)return;
      templates=t.data;shops=s.data;
      templates.forEach(v=>{const option=text("option",`${v.name} · v${v.version}`,selector);option.value=v.snapshotId;});
      renderShops();renderBatches(b.data);
      status.textContent="已读取当前公司数据。商品每款只分配给一家选中店铺，不自动复制到所有店铺。";
      await loadProducts(true);
    }catch(e){status.textContent=e.message;}
    finally{get("refresh").disabled=false;}
  });
  selector.addEventListener("change",renderShops);
  form.addEventListener("input",invalidate);
  get("search").addEventListener("click",()=>loadProducts(true));
  get("more").addEventListener("click",()=>loadProducts(false));
  form.addEventListener("submit",async event=>{
    event.preventDefault();
    const template=selectedTemplate();
    const targets=Array.from(form.querySelectorAll('input[name="shop"]:checked')).map(s=>s.value);
    if(!template || !selectedProducts.size || selectedProducts.size>500 || targets.length!==template.configuration.shopCount){
      status.textContent="请选择1至500款商品，且选中店铺数须与模板规划一致。";return;
    }
    if(selectedProducts.size<targets.length){status.textContent="商品数量不能少于选中店铺数。";return;}
    const body={name:form.elements.namedItem("name").value.trim(),templateSnapshotId:template.snapshotId,
      targets:Array.from(selectedProducts).map((captureId,i)=>({captureId,shopId:targets[i%targets.length]}))};
    if(!pendingRequest || pendingRequest.body!==JSON.stringify(body))pendingRequest={key:crypto.randomUUID(),body:JSON.stringify(body)};
    const request=pendingRequest;
    const ticket=generation;
    const controls=Array.from(form.querySelectorAll("input,select,button"));
    const disabled=controls.map(c=>c.disabled);controls.forEach(c=>c.disabled=true);get("refresh").disabled=true;
    try{
      const r=await api("/api/v1/commerce-batches",{method:"POST",headers:{"Content-Type":"application/json","Idempotency-Key":request.key},body:request.body});
      if(ticket!==generation)return;
      status.textContent=`已保存草稿 ${r.data.name}，未执行发布。`;pendingRequest=null;
      selectedProducts.clear();products.querySelectorAll('input[type="checkbox"]').forEach(c=>c.checked=false);
      get("selected-count").textContent="已选 0 款";
      await showBatch(r.data.id);
    }catch(e){status.textContent=`${e.message}；内容不变时重试将使用相同幂等键。`;}
    finally{controls.forEach((c,i)=>c.disabled=disabled[i]);get("refresh").disabled=false;}
  });
})();
