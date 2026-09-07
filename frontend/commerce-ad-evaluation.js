(() => {
  const form=document.getElementById('commerce-ad-form'), result=document.getElementById('commerce-ad-result');
  if(!form||!result)return;
  const fields={};let generation=0;
  const append=(tag,value,parent)=>{const e=document.createElement(tag);e.textContent=value;parent.append(e);return e;};
  const currency=append('select','',append('label','币种',form));
  ['PHP','SGD','MYR','THB','VND','IDR'].forEach(c=>{const option=append('option',c,currency);option.value=c;});fields.currency=currency;
  [['elapsedDays','已观察天数',7],['observationDays','规定观察天数',7],['impressions','曝光数',0],['attributedOrders','归因订单数',0],['minimumImpressions','最低曝光样本',1000],['minimumOrders','最低订单样本',5],['spend','累计广告花费',0],['netAttributedGmv','扣退款后的归因GMV',0],['totalBudgetCap','整轮总预算上限',7000],['optimizeThreshold','优化ROAS门槛',5],['campaignTarget','当前计划目标',6.5],['retestTarget','建议重测目标',9]].forEach(([key,title,value])=>{
    const input=append('input','',append('label',title,form));input.type='number';input.step=key.endsWith('Days')||['impressions','attributedOrders','minimumImpressions','minimumOrders'].includes(key)?'1':'any';input.min='0';input.required=true;input.value=String(value);input.defaultValue=String(value);fields[key]=input;
  });
  const settled=append('input','',append('label','确认归因窗口已结算、统计数据完整',form));settled.type='checkbox';fields.attributionSettled=settled;
  const button=append('button','评估建议（不花钱、不删链接）',form);button.type='submit';
  const templateBox=append('fieldset','',form);append('legend','保存测款规则模板（不保存实测数据）',templateBox);
  const selector=append('select','',append('label','已保存模板',templateBox));
  const templateName=append('input','',append('label','模板名称',templateBox));templateName.maxLength=120;
  const refresh=append('button','刷新模板',templateBox);refresh.type='button';
  const load=append('button','加载规则',templateBox);load.type='button';
  const save=append('button','另存新模板',templateBox);save.type='button';
  const version=append('button','保存为下一版本',templateBox);version.type='button';
  const templateStatus=append('p','加载规则不会覆盖已有花费、GMV、曝光和订单。切换计划时请手工清空实测数据。',templateBox);
  let templates=[],active=null;
  const policyKeys=['currency','observationDays','minimumImpressions','minimumOrders','totalBudgetCap','optimizeThreshold','campaignTarget','retestTarget'];
  refresh.addEventListener('click',async()=>{
    const ticket=generation;refresh.disabled=true;
    try{const r=await api('/api/v1/commerce-ad-templates');if(ticket!==generation)return;templates=r.data;selector.replaceChildren();templates.forEach(t=>{const o=append('option',`${t.name} · v${t.version}`,selector);o.value=t.snapshotId;});templateStatus.textContent=`已读取 ${templates.length} 个规则模板。`;}
    catch(e){if(ticket===generation)templateStatus.textContent=e.message;}finally{refresh.disabled=false;}
  });
  load.addEventListener('click',()=>{
    const t=templates.find(t=>t.snapshotId===selector.value);if(!t){templateStatus.textContent='请刷新并选择模板。';return;}
    generation++;active=t;policyKeys.forEach(key=>fields[key].value=String(t.policy[key]));templateName.value=t.name;
    fields.attributionSettled.checked=false;result.replaceChildren();templateStatus.textContent=`已加载 v${t.version}；实测数据保留，请核对币种和周期，并重新确认归因结算。`;
  });
  async function saveTemplate(asVersion){
    if(!templateName.value.trim()){templateStatus.textContent='请填写模板名称。';return;}
    if(asVersion&&!active){templateStatus.textContent='请先加载要更新的模板。';return;}
    const policy=Object.fromEntries(policyKeys.map(key=>[key,key==='currency'?fields[key].value:Number(fields[key].value)]));
    const ticket=generation;save.disabled=true;version.disabled=true;
    try{
      const response=await api('/api/v1/commerce-ad-templates',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({templateId:asVersion?active.templateId:null,expectedVersion:asVersion?active.version:0,name:templateName.value.trim(),policy})});
      if(ticket!==generation)return;active=response.data;templateStatus.textContent=`已保存 ${active.name} v${active.version}，不会执行广告。`;
    }catch(e){if(ticket===generation)templateStatus.textContent=e.message;}finally{save.disabled=false;version.disabled=false;}
  }
  save.addEventListener('click',()=>saveTemplate(false));version.addEventListener('click',()=>saveTemplate(true));
  const decisions={BUDGET_LIMIT_REVIEW:'预算上限：需人工处理',OBSERVE:'继续观察',WAIT_ATTRIBUTION:'等待归因结算',INSUFFICIENT_DELIVERY:'投放样本不足',LOW_CONVERSION_REVIEW:'转化样本不足：需复核',OPTIMIZE_CANDIDATE:'进入优化候选',RETEST_REVIEW:'未达门槛：评估重测'};
  form.addEventListener('input',()=>{generation++;result.textContent='数据已修改，请重新评估。';});
  globalThis.addEventListener?.('heyi:company-reset',()=>{generation++;form.reset();result.replaceChildren();templates=[];active=null;selector.replaceChildren();templateStatus.textContent='';});
  form.addEventListener('submit',async event=>{
    event.preventDefault();const ticket=++generation;button.disabled=true;
    const body=Object.fromEntries(Object.entries(fields).map(([key,e])=>[key,key==='currency'?e.value:key==='attributionSettled'?e.checked:Number(e.value)]));
    try{
      const response=await api('/api/v1/commerce-plans/ads/evaluate',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
      if(ticket!==generation)return;
      const r=response.data;result.replaceChildren();append('h4',decisions[r.decision]||r.decision,result);
      append('p',`实际ROAS：${r.observedRoas??'无法计算（无花费）'}；剩余预算：${r.remainingBudget} ${body.currency}`,result);
      [...r.reasons,...r.suggestedActions,...r.limitations].forEach(message=>append('p',message,result));
      if(r.proposedRetestTarget!=null)append('p',`重测目标建议：${r.proposedRetestTarget}，需另行审批预算。`,result);
    }catch(e){if(ticket===generation)result.textContent=e.message;}
    finally{button.disabled=false;}
  });
})();
