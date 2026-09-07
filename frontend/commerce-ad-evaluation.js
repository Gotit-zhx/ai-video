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
  const decisions={BUDGET_LIMIT_REVIEW:'预算上限：需人工处理',OBSERVE:'继续观察',WAIT_ATTRIBUTION:'等待归因结算',INSUFFICIENT_DELIVERY:'投放样本不足',LOW_CONVERSION_REVIEW:'转化样本不足：需复核',OPTIMIZE_CANDIDATE:'进入优化候选',RETEST_REVIEW:'未达门槛：评估重测'};
  form.addEventListener('input',()=>{generation++;result.textContent='数据已修改，请重新评估。';});
  globalThis.addEventListener?.('heyi:company-reset',()=>{generation++;form.reset();result.replaceChildren();});
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
