(() => {
  const form=document.getElementById("shop-settings-form");if(!form)return;
  const selector=document.getElementById("shop-settings-shop"),status=document.getElementById("shop-settings-status");
  const refresh=document.getElementById("shop-settings-refresh"),templateSelect=document.getElementById("shop-settings-template");
  const save=form.querySelector('button[type="submit"]');
  let version=null,generation=0,shops=[],templates=[];
  const option=(value,label,parent)=>{const o=document.createElement("option");o.value=value;o.textContent=label;parent.append(o);};
  async function load(){
    const ticket=++generation;version=null;save.disabled=true;
    const shop=shops.find(s=>s.id===selector.value);if(!shop)return;
    try{
      const r=await api(`/api/v1/shops/${encodeURIComponent(shop.id)}/operating-settings`);
      if(ticket!==generation)return;
      const config=r.data;version=config.version;
      form.elements.namedItem("groupName").value=config.groupName;
      form.elements.namedItem("environment").value=config.environment;
      form.elements.namedItem("dailyListingLimit").value=config.dailyListingLimit;
      templateSelect.replaceChildren();option("","无默认模板",templateSelect);
      const eligible=templates.filter(t=>shop.status==="ACTIVE"
        && ["SHOPEE","TIKTOK_SHOP"].includes(shop.platform)
        && ["PH","SG","MY","TH","VN","ID"].includes(String(shop.region || "").trim().toUpperCase())
        && (!t.configuration.shopIds?.length || t.configuration.shopIds.includes(shop.id)));
      eligible.forEach(t=>option(t.snapshotId,`${t.name} · v${t.version}`,templateSelect));
      if(config.defaultTemplateSnapshotId && !eligible.some(t=>t.snapshotId===config.defaultTemplateSnapshotId))
        option(config.defaultTemplateSnapshotId,"当前固定的历史模板版本",templateSelect);
      templateSelect.value=config.defaultTemplateSnapshotId||"";
      status.textContent=`已读取 ${shop.name}，配置版本 ${version}。`;
      save.disabled=!hasAnyPermission("product:publish");
    }catch(e){if(ticket===generation)status.textContent=e.message;}
  }
  selector.addEventListener("change",load);
  refresh.addEventListener("click",async()=>{
    const ticket=++generation;refresh.disabled=true;save.disabled=true;version=null;
    selector.replaceChildren();templateSelect.replaceChildren();
    try{
      const [s,t]=await Promise.all([api("/api/v1/shops"),api("/api/v1/commerce-templates")]);
      if(ticket!==generation)return;
      shops=s.data;templates=t.data;
      shops.forEach(s=>option(s.id,`${s.name} · ${s.platform} / ${s.region}`,selector));
      if(shops.length)await load();else status.textContent="暂无店铺，请先授权。";
    }catch(e){if(ticket===generation)status.textContent=e.message;}
    finally{refresh.disabled=false;}
  });
  form.addEventListener("submit",async e=>{
    e.preventDefault();if(version===null)return;
    const ticket=generation, id=selector.value;
    const fields=Object.fromEntries(new FormData(form));
    fields.dailyListingLimit=Number(fields.dailyListingLimit);fields.expectedVersion=version;
    save.disabled=true;selector.disabled=true;refresh.disabled=true;
    try{
      const r=await api(`/api/v1/shops/${encodeURIComponent(id)}/operating-settings`,{method:"PUT",headers:{"Content-Type":"application/json"},body:JSON.stringify(fields)});
      if(ticket!==generation)return;
      version=r.data.version;status.textContent=`配置已保存为 v${version}，不会触发上架或修改官方API。`;
    }catch(error){if(ticket===generation)status.textContent=error.message;}
    finally{save.disabled=version===null || !hasAnyPermission("product:publish");selector.disabled=false;refresh.disabled=false;}
  });
  globalThis.addEventListener?.("heyi:company-reset",()=>{generation++;version=null;shops=[];templates=[];form.reset();selector.replaceChildren();templateSelect.replaceChildren();status.textContent="";save.disabled=true;});
})();
