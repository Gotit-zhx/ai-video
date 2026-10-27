/* Internal expense sources and order tags. Import/removal never edits platform transactions. */
(() => {
  'use strict';
  let state={group:'',tab:'brush',page:1,numbers:''},sequence=0,pollTimer=null;
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent?.append(n);return n;};
  const money=v=>v==null?'待核对':Number(v).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
  const json=body=>({headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  function mount(root,options){
    const {groups=[],shops=[],request,canManage=false}=options;
    clearTimeout(pollTimer);
    if(groups.some(g=>g.id===options.filters?.groupId))state.group=options.filters.groupId;
    if(!groups.some(g=>g.id===state.group))state.group=groups[0]?.id||'';
    const today=new Date(),startDay=new Date(today);startDay.setDate(today.getDate()-29);
    const scope={start:options.filters?.start||startDay.toLocaleDateString('sv-SE'),end:options.filters?.end||today.toLocaleDateString('sv-SE'),platform:options.filters?.platform||'',shopId:options.filters?.shopId||''};
    if(!shops.some(s=>s.id===scope.shopId&&s.group_id===state.group))scope.shopId='';
    const remember=()=>options.onFiltersChange?.({...scope,groupId:state.group});
    let data=null,feedback,content,primary,busy=false;
    function button(parent,text,fn,cls=''){
      const n=el('button',text,parent,cls);n.type='button';
      n.onclick=async()=>{if(n.disabled)return;n.disabled=true;try{await fn();}catch(e){(n.closest('dialog')?.querySelector('[role=alert]')||feedback).textContent=e.message||'操作失败，请重试';}finally{n.disabled=false;}};
      return n;
    }
    function field(parent,name,value='',type='text'){
      const l=el('label',null,parent);el('span',name,l);const n=el('input',null,l);n.type=type;n.value=value;n.setAttribute('aria-label',name);return n;
    }
    function select(parent,name,values,value,fn){
      const l=el('label',null,parent);el('span',name,l);const n=el('select',null,l);n.setAttribute('aria-label',name);
      values.forEach(([v,t])=>el('option',t,n).value=v);n.value=value;n.onchange=()=>fn?.(n.value);return n;
    }
    function table(parent,headers,rows){
      const wrap=el('div',null,parent,'fi-table-wrap'),t=el('table',null,wrap,'fi-table'),h=el('tr',null,el('thead',null,t));
      headers.forEach(v=>el('th',v,h));const body=el('tbody',null,t);
      rows.forEach(cells=>{const row=el('tr',null,body);cells.forEach(v=>{const td=el('td',null,row);v instanceof Node?td.append(v):td.textContent=v??'—';});});
      if(!rows.length)el('p','暂无记录',parent,'fi-empty');
    }
    function dialog(title){
      const n=el('dialog',null,document.body,'fi-dialog fc-dialog fq-dialog'),head=el('div',null,n,'fi-dialog-head');el('h3',title,head);
      button(head,'关闭',()=>n.close());const body=el('div',null,n,'fi-dialog-body'),error=el('p','',body,'fc-alert');error.setAttribute('role','alert');
      const foot=el('div',null,n,'fi-dialog-foot');button(foot,'取消',()=>n.close());n.onclose=()=>n.remove();n.showModal();return {n,body,foot};
    }
    async function refresh(){
      clearTimeout(pollTimer);
      const run=++sequence,group=state.group,requestedScope={...scope};busy=true;feedback.textContent='正在更新…';content.setAttribute('aria-busy','true');
      try{
        const incoming=group?await request('/charges?'+new URLSearchParams({...requestedScope,groupId:group,page:state.page,numbers:state.numbers})):null;
        if(run!==sequence||!root.isConnected)return;
        data=incoming?{...incoming,filters:incoming.filters||requestedScope}:null;feedback.textContent='';drawContent();
        if(data?.labelSummary?.processing||Number(data?.totals?.unmatched)>0)pollTimer=setTimeout(()=>{if(run===sequence&&root.isConnected&&!document.querySelector('.fq-dialog'))refresh().catch(e=>{feedback.textContent=e.message;});},15000);
      }finally{if(run===sequence){busy=false;content.setAttribute('aria-busy','false');}}
    }
    function rule(existing){
      const group=state.group,d=dialog(existing?'调整贴单单价':'新增贴单规则'),form=el('div',null,d.body,'fe-form');
      const country=select(form,'国家',[['','分组默认'],['PH','菲律宾'],['TH','泰国'],['MY','马来西亚'],['VN','越南'],['SG','新加坡'],['ID','印度尼西亚'],['US','美国'],['GB','英国']],existing?.country||'');
      const price=field(form,'人民币单价（元 / 订单）',existing?.unit_price_cny??'','number');price.min='0';price.step='0.000001';
      country.disabled=!!existing;
      el('p','国家规则优先于分组默认。按当前单价统计查询范围内首次结算的已发货订单，每单一次；不限制下单日期。刷单不计入经营汇总。修改后后台重算历史适用订单，金额固定为人民币。',d.body,'fi-muted');
      button(d.foot,'保存并重算',async()=>{
        if(price.value===''||Number(price.value)<0)throw Error('请填写非负人民币单价');
        await request('/charges/rules',{method:'POST',...json({groupId:group,id:existing?.id||'',version:existing?.version||0,country:country.value,unitPriceCny:price.value})});
        d.n.close();await refresh();
      },'fi-primary');
    }
    function confirmRemoval(title,explanation,fn){
      const d=dialog(title);el('p',explanation,d.body);
      button(d.foot,'确认移除',async()=>{await fn();d.n.close();await refresh();},'fi-primary');
    }
    function importFile(){
      const group=state.group,d=dialog('导入刷单费用表'),file=field(d.body,'选择 Excel / CSV','','file');file.accept='.xlsx,.csv';
      el('p','自动识别订单编号、店铺、实际订单金额和人民币服务费。只标记精确匹配的订单；未匹配记录保留，订单同步后自动重试。同单多文件不重复计数。',d.body,'fi-muted');
      const preview=el('div',null,d.body);let chosen=null;
      const form=()=>{const body=new FormData();body.append('groupId',group);body.append('file',chosen);return body;};
      const submit=button(d.foot,'确认导入',async()=>{
        const result=await request('/charges/import',{method:'POST',body:form()});
        d.n.close();state.page=1;await refresh();feedback.textContent=result.duplicate?'文件已存在，未重复导入':'已导入；匹配订单已加黄色标签并排除经营汇总。';
      },'fi-primary');submit.disabled=true;
      file.onchange=()=>{submit.disabled=true;chosen=null;preview.replaceChildren();};
      button(d.foot,'解析预览',async()=>{
        const selected=file.files[0];if(!selected)throw Error('请先选择文件');chosen=selected;
        const result=await request('/charges/preview',{method:'POST',body:form()});if(file.files[0]!==selected)return;
        preview.replaceChildren();el('p',`${result.count} 条来源 · 可匹配 ${result.matched} 条 · 服务费 ¥ ${money(result.serviceFeeCny)}（文件合计，非去重经营费用）`,preview,'fq-callout');
        table(preview,['订单编号','店铺','服务费（¥）','匹配结果'],result.rows.slice(0,30).map(r=>[r.orderId,r.shopHint,money(r.serviceFeeCny),r.match.matchNote]));
        if(result.count>30)el('p','预览前 30 条；确认后保存全部有效来源行。',preview,'fi-muted');submit.disabled=false;
      });
    }
    function drawContent(){
      if(primary){primary.textContent=state.tab==='labels'?'新增贴单规则':'导入 Excel';primary.disabled=!state.group;}
      const current=content;content=current.cloneNode(false);
      renderContent();
      if(globalThis.IncrementalDOM)IncrementalDOM.patch(current,content);else current.replaceChildren(...content.childNodes);
      content=current;
    }
    function renderContent(){
      if(!state.group){el('p','请先在财务店铺管理建立分组。',content,'fi-empty');return;}
      if(!data){el('p','正在读取…',content);return;}
      const shown=data.filters||scope;
      el('p',`结算日期：${shown.start} 至 ${shown.end} · 金额固定人民币 · 按首次结算每单统计一次`,content,'fq-description');
      if(state.tab==='labels'){
        const total=data.labelSummary?.total;
        if(total){
          const metrics=el('div',null,content,'fc-metrics');
          for(const [name,value]of [['范围内结算订单',total.order_count||0],['已计算贴单成本（¥）',money(total.label_cost_cny||0)],['等待重算订单',total.queued_count||0],['费用待核对订单',total.charges_pending||0]]){
            const box=el('div',null,metrics,'fc-metric');el('span',name,box);el('strong',value,box);
          }
          if(data.labelSummary.processing){const status=el('p','贴单成本正在后台重算，完成后自动更新…',content,'fi-loading');status.setAttribute('role','status');}
        }
        el('p','按当前单价计费，不限制下单日期；国家规则优先于分组默认。未发货、未结算、已取消及刷单不计入这里。',content,'fq-description');
        if(!data.rules.length)el('p','当前分组还没有贴单单价，其他分组的规则不会自动应用。',content,'fq-callout');
        table(content,['国家','单价（¥ / 单）','状态','版本','操作'],data.rules.map(r=>{
          const actions=el('span',null,null,'fi-actions');
          if(canManage){button(actions,'调整',()=>rule(r));button(actions,'移除',()=>confirmRemoval('移除贴单规则','移除后按剩余规则重算；原平台费用保留。',()=>request('/charges/rules/'+encodeURIComponent(r.id)+'?'+new URLSearchParams({groupId:state.group,version:r.version}),{method:'DELETE'})));}
          return [({PH:'菲律宾',TH:'泰国',MY:'马来西亚',VN:'越南',SG:'新加坡',ID:'印度尼西亚',US:'美国',GB:'英国'})[r.country]||'分组默认',money(r.unit_price_cny),r.active===false?'历史配置':'当前单价',r.version,actions];
        }));
        return;
      }
      const metrics=el('div',null,content,'fc-metrics');
      const period=data.periodMarked||data.marked;
      for(const [name,value] of [['范围内已结算刷单',period?.orders||0],['范围内刷单服务费（¥）',money(period?.service_fee_cny??0)],['累计已标记订单',data.marked?.orders||0],['来源待匹配 / 待核对',data.totals.unmatched||0]]){
        const box=el('div',null,metrics,'fc-metric');el('span',name,box);el('strong',value,box);
      }
      el('p','刷单订单不计入经营汇总，服务费单独统计。原始订单、结算及对账记录保留。',content,'fq-description');
      el('p',`来源共 ${data.totals.records} 条，累计去重服务费 ¥ ${money(data.marked?.service_fee_cny??0)}。下方文件和核对记录保留全部来源，不随结算日期隐藏；未匹配、未结算及范围外订单不计入上方期间金额。`,content,'fq-description');
      if(canManage&&Number(data.totals.unmatched)>0)button(content,'重新匹配',async()=>{await request('/charges/rematch',{method:'POST',...json({groupId:state.group})});await refresh();});
      if(Number(data.marked?.conflicts)>0)el('p',`${data.marked.conflicts} 笔订单存在不同服务费来源，合计仅包含已确认部分；请核对并移除错误来源。`,content,'fq-callout');
      const files=el('section',null,content,'fq-section');el('h4','导入文件',files);
      table(files,['文件','来源行数','操作'],data.imports.map(f=>[f.source_name,f.row_count,canManage?button(null,'移除',()=>confirmRemoval('移除费用来源','只撤销这个文件的来源。同单仍有其他来源时保留标签；没有其他来源时自动恢复正常经营汇总。',()=>request('/charges/imports/'+encodeURIComponent(f.id)+'?'+new URLSearchParams({groupId:state.group}),{method:'DELETE'}))):'—']));
      const orders=el('section',null,content,'fq-section');el('h4','订单核对',orders);
      options.numberSearch?.(orders,state.numbers,value=>{state.numbers=value;state.page=1;refresh();},'订单编号');
      table(orders,['订单编号','下单时间（店铺时区）','来源店铺','本金原币（核对用）','服务费（¥）','匹配状态'],data.rows.map(r=>{
        const matched=r.match.matchStatus==='MATCHED',status=el('span',matched?'刷单':r.match.matchStatus==='REVIEW'?'待核对':'待匹配',null,matched?'fi-marketing-tag':'fi-status');
        status.title=r.match.matchNote+(r.marker?.source_count>1?' · 多文件同单只统计一次':'');const wrap=el('span',null);wrap.append(status);
        if(!matched)el('small',r.match.matchNote,wrap);
        if(r.marker?.source_count&&r.marker.service_fee_cny==null)el('small',' 服务费冲突',wrap);
        return [r.order_id,options.dateTimeCell?.(r.order_created_time,r.order_timezone||'UTC')||'—',r.shop_hint,money(r.principal_native)+' '+(r.match.currency||'币种待匹配'),money(r.service_fee_cny),wrap];
      }));
      const pages=el('div',null,orders,'fi-actions fq-pagination');
      button(pages,'上一页',async()=>{state.page--;await refresh();}).disabled=state.page<=1;
      el('span',`第 ${state.page} 页 · 共 ${data.recordTotal??data.totals.records} 条来源`,pages);
      button(pages,'下一页',async()=>{state.page++;await refresh();}).disabled=state.page*50>=Number(data.recordTotal??data.totals.records);
    }
    root.replaceChildren();root.className='fq-workspace';
    const toolbar=el('div',null,root,'fq-toolbar'),tabs=el('div',null,toolbar,'fi-tabs fq-tabs');tabs.setAttribute('aria-label','成本类型');
    for(const [key,name]of[['labels','贴单成本'],['brush','刷单成本']]){
      const n=button(tabs,name,()=>{state.tab=key;for(const b of tabs.children)b.setAttribute('aria-pressed',String(b===n));drawContent();});
      n.setAttribute('aria-pressed',String(state.tab===key));
    }
    const tools=el('div',null,toolbar,'fq-controls');
    select(tools,'费用分组',groups.map(g=>[g.id,g.name]),state.group,async group=>{state.group=group;state.page=1;scope.shopId='';remember();renderShopOptions();data=null;drawContent();try{await refresh();}catch(e){feedback.textContent=e.message;}});
    if(canManage)primary=button(tools,'导入 Excel',()=>state.tab==='labels'?rule():importFile(),'fi-primary');
    feedback=el('p','',root,'fc-feedback');feedback.setAttribute('role','status');
    const filters=el('div',null,root,'fi-filters');
    const changeScope=async()=>{remember();state.page=1;try{await refresh();}catch(e){feedback.textContent=e.message;}};
    select(filters,'平台',[['','全部平台'],['SHOPEE','Shopee'],['TIKTOK_SHOP','TikTok Shop']],scope.platform,v=>{scope.platform=v;scope.shopId='';renderShopOptions();changeScope();});
    const shopSelect=select(filters,'店铺',[],'',v=>{scope.shopId=v;changeScope();});
    function renderShopOptions(){shopSelect.replaceChildren();for(const [v,t]of [['','全部店铺'],...shops.filter(s=>s.group_id===state.group&&(!scope.platform||s.platform===scope.platform)).map(s=>[s.id,s.display_name||s.name])])el('option',t,shopSelect).value=v;shopSelect.value=scope.shopId;}
    renderShopOptions();
    const dates=el('div',null,filters,'fi-date-range');el('span','结算日期',dates);
    for(const [key,name]of [['start','开始日期'],['end','结束日期']]){
      const input=field(dates,name,scope[key],'date');
      input.onchange=()=>{if(!input.value)return;const proposed={...scope,[key]:input.value};if(proposed.start>proposed.end){feedback.textContent='开始日期不能晚于结束日期';input.value=scope[key];return;}scope[key]=input.value;changeScope();};
    }
    content=el('section',null,root,'fi-card fq-content');
    drawContent();
    refresh().catch(e=>{feedback.textContent=e.message;});
  }
  globalThis.FinanceCharges={mount,openLabels(group){state.tab='labels';if(group)state.group=group;},reset(){sequence++;clearTimeout(pollTimer);state={group:'',tab:'brush',page:1,numbers:''};document.querySelectorAll('.fq-dialog').forEach(n=>n.remove());}};
})();
