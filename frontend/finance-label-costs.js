/* Shared monthly invoice workspace. Uploads stream to a bounded server queue. */
(() => {
  'use strict';
  const disposers=new Set();
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent?.append(n);return n;};
  const money=v=>v==null?'—':Number(v).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
  const status={QUEUED:'排队中',PARSING:'校验中',PREPARED:'自动匹配中',READY:'已完成',FAILED:'处理失败',EXPIRED:'请重新上传',REMOVE_QUEUED:'移除中'};
  function mount(root,options){
    const {request,groups=[],shops=[],canManage=false}=options;
    let disposed=false,timer=null,sequence=0,data=null,controller=null,periodKey='';
    const scope={start:options.filters?.start||options.dates?.start||'',end:options.filters?.end||options.dates?.end||'',
      groupId:options.filters?.groupId||'',shopId:options.filters?.shopId||'',platform:options.filters?.platform||'',
      fileId:'',matchStatus:'',numbers:'',page:1};
    const dispose=()=>{disposed=true;sequence++;clearTimeout(timer);controller?.abort();disposers.delete(dispose);};disposers.add(dispose);
    root.replaceChildren();
    const toolbar=el('div',null,root,'fq-toolbar'),heading=el('div',null,toolbar);
    el('h3','月度贴单账单',heading);el('p','上传仓库账单，按平台订单号自动绑定。金额固定为人民币，原表 0 元按实际 0 元统计。',heading,'fi-muted');
    const controls=el('div',null,toolbar,'fi-actions'),feedback=el('p','',root,'fc-feedback');feedback.setAttribute('role','status');
    function button(parent,text,fn,cls=''){
      const n=el('button',text,parent,cls);n.type='button';
      n.onclick=async event=>{const active=event.currentTarget||n;if(active.disabled)return;active.disabled=true;try{await fn();}catch(e){(active.closest('dialog')?.querySelector('[role=alert]')||feedback).textContent=e.message||'操作失败，请重试';}finally{if(active.isConnected)active.disabled=false;}};return n;
    }
    function select(parent,name,items,value,change){
      const l=el('label',null,parent);el('span',name,l);const n=el('select',null,l);n.setAttribute('aria-label',name);
      items.forEach(([v,t])=>{el('option',t,n).value=v;});n.value=value;n.onchange=()=>change(n.value);return n;
    }
    function table(parent,headers,rows){
      const wrap=el('div',null,parent,'fi-table-wrap'),t=el('table',null,wrap,'fi-table'),h=el('tr',null,el('thead',null,t));
      headers.forEach(s=>el('th',s,h));const body=el('tbody',null,t);
      rows.forEach(({key,cells})=>{const tr=el('tr',null,body);tr.dataset.updateKey=key;cells.forEach(v=>{const td=el('td',null,tr);v instanceof Node?td.append(v):td.textContent=v??'—';});});
      if(!rows.length)el('p','暂无记录',parent,'fi-empty');return t;
    }
    function dialog(title){
      const n=el('dialog',null,document.body,'fi-dialog fc-dialog fl-dialog'),head=el('div',null,n,'fi-dialog-head');el('h3',title,head);
      button(head,'关闭',()=>n.close());const body=el('div',null,n,'fi-dialog-body'),alert=el('p','',body,'fc-alert');alert.setAttribute('role','alert');
      const foot=el('div',null,n,'fi-dialog-foot');button(foot,'取消',()=>n.close());n.onclose=()=>n.remove();n.showModal();return {n,body,foot,alert};
    }
    function upload(){
      const d=dialog('导入月度贴单账单'),label=el('label',null,d.body);el('span','选择 XLSX（可多选）',label);
      const file=el('input',null,label);file.type='file';file.multiple=true;file.accept='.xlsx';file.setAttribute('aria-label','月度贴单账单文件');
      el('p','支持订单费用明细和仓库月度账单。参考号作为平台订单号，读取金额或操作费，全部为人民币。每份最多 96 MB、50 万条记录。',d.body,'fi-muted');
      el('p','文件逐份上传，后台分批校验和匹配。重复来源自动去重，未匹配订单长期保留；后续加入店铺并同步订单后自动补绑定。完成结果及异常见文件列表。',d.body,'fi-muted');
      button(d.foot,'上传并自动匹配',async()=>{
        const chosen=[...file.files];if(!chosen.length)throw Error('请先选择账单文件');
        for(const f of chosen)if(f.size>96*1024*1024)throw Error(f.name+' 超过 96 MB，请拆分');
        let accepted=0,duplicate=0;file.disabled=true;
        try{
          for(let index=0;index<chosen.length;index++){
            d.alert.textContent=`正在上传 ${index+1} / ${chosen.length}：${chosen[index].name}`;
            const body=new FormData();body.append('file',chosen[index]);
            const result=await request('/label-costs/import',{method:'POST',body});
            result.duplicate?duplicate++:accepted++;
          }
          d.n.close();await refresh();feedback.textContent=`已加入后台队列 ${accepted} 份，跳过重复文件 ${duplicate} 份。处理进度见下方文件列表。`;
        }catch(e){await refresh();throw Error(`已接收 ${accepted} 份、重复 ${duplicate} 份。${e.message}；重传会自动去重。`);}
        finally{file.disabled=false;}
      },'fi-primary');
    }
    if(canManage){button(controls,'导入月度账单',upload,'fi-primary');button(controls,'重新匹配',async()=>{await request('/label-costs/rematch',{method:'POST'});await refresh();});}
    button(controls,'刷新',()=>refresh());
    const filters=el('div',null,root,'fi-filters');
    const change=(detailsOnly=false)=>{scope.page=1;options.onFiltersChange?.({...scope});syncStatusFilters();refresh({detailsOnly}).catch(e=>{feedback.textContent=e.message;});};
    select(filters,'账单所属分组',[['','全部分组'],['UNGROUPED','未分组'],...groups.map(g=>[g.id,g.name])],scope.groupId,v=>{scope.groupId=v;scope.shopId='';shopOptions();change();});
    const shopSelection=select(filters,'账单匹配店铺',[],'',v=>{scope.shopId=v;change();});
    function shopOptions(){
      const available=shops.filter(s=>(!scope.groupId||(scope.groupId==='UNGROUPED'?!s.group_id:s.group_id===scope.groupId))&&(!scope.platform||s.platform===scope.platform));
      if(!available.some(s=>s.id===scope.shopId))scope.shopId='';
      shopSelection.replaceChildren();el('option','全部店铺',shopSelection).value='';
      for(const s of available)el('option',s.display_name||s.name,shopSelection).value=s.id;
      shopSelection.value=scope.shopId;
    }
    shopOptions();
    for(const [key,name]of [['start','结算开始日期'],['end','结算结束日期']]){
      const label=el('label',null,filters);el('span',name,label);const n=el('input',null,label);n.type='date';n.value=scope[key];n.setAttribute('aria-label',name);
      n.onchange=()=>{if(!n.value)return;const next={...scope,[key]:n.value};if(next.start&&next.end&&next.start>next.end){feedback.textContent='开始日期不能晚于结束日期';n.value=scope[key];return;}scope[key]=n.value;change();};
    }
    const selection=el('div',null,root,'fi-filters');
    const statusSelect=select(selection,'匹配状态',[['','全部状态'],['UNMATCHED','待匹配'],['REVIEW','绑定待核对'],['CONFLICT','费用待核对'],['MATCHED','已匹配']],scope.matchStatus,v=>{scope.matchStatus=v;change(true);});
    const search=el('input',null,selection);search.type='search';search.placeholder='按订单编号查询';search.setAttribute('aria-label','贴单订单编号');
    search.onchange=()=>{scope.numbers=search.value.trim();change(true);};
    const quickFilters=el('div',null,root,'fi-tabs fl-status-filters');quickFilters.setAttribute('role','group');quickFilters.setAttribute('aria-label','订单明细筛选');
    for(const [key,title]of [['','全部记录'],['UNMATCHED','待匹配'],['MATCHED','已匹配']]){
      const n=button(quickFilters,title,()=>{scope.matchStatus=key;change(true);});n.dataset.matchStatus=key;
    }
    function syncStatusFilters(){
      statusSelect.value=scope.matchStatus;
      for(const n of quickFilters.children)n.setAttribute('aria-pressed',String(n.dataset.matchStatus===scope.matchStatus));
    }
    syncStatusFilters();
    const loading=el('div',null,root,'fl-processing fl-read-progress');loading.hidden=true;loading.setAttribute('role','status');loading.setAttribute('aria-live','polite');
    el('progress',null,loading).setAttribute('aria-label','明细读取进度');el('span','正在读取所选明细…',loading);
    let content=el('div',null,root,'fl-content');
    function draw(){
      const next=content.cloneNode(false);
      if(!data){el('p','正在读取账单…',next,'fi-loading');content.replaceChildren(...next.childNodes);return;}
      const metrics=el('div',null,next,'fc-metrics');
      for(const [title,value]of [['结算期贴单金额（人民币）','¥ '+money(data.period.amount_cny)],['期间已匹配结算订单',data.period.orders],['期间计费记录',data.period.shipments],['计费待核对记录',data.period.conflicts]]){
        const item=el('div',null,metrics,'fc-metric');el('span',title,item);el('strong',String(value),item);
      }
      el('p',data.basis,next,'fq-description');
      el('p','上方按订单结算日期统计：例如 9 月贴单、10 月结算，费用计入 10 月。下方保留账单原始交易日期及全部来源记录。',next,'fi-muted');
      if(data.processing||data.files.some(f=>['QUEUED','PARSING','PREPARED','REMOVE_QUEUED'].includes(f.status))){
        const busy=el('div',null,next,'fl-processing');busy.setAttribute('role','status');
        const progress=el('progress',null,busy);progress.setAttribute('aria-label','贴单账单处理进度');
        el('span','正在校验和匹配，完成后统一更新金额…',busy);
      }
      if(data.state?.error_message)el('p',data.state.error_message,next,'fc-alert');
      const files=el('section',null,next,'fq-section');el('h4','导入文件',files);
      table(files,['文件 / 账单周期','处理状态','来源行数','绑定结果','文件金额合计（¥）','操作'],data.files.map(f=>{
        const title=el('span',f.source_name);if(f.period_start)el('small',`${String(f.period_start).slice(0,10)} 至 ${String(f.period_end).slice(0,10)}`,title,'fi-muted');
        const problems=Number(f.unmatched_count||0)+Number(f.review_count||0)+Number(f.conflict_count||0);
        const progress=el('span',f.status==='READY'&&problems?'已完成 · 有待处理项':status[f.status]||f.status,null,'fc-tag '+(f.status==='READY'&&!problems?'good':'warn'));
        if(f.status==='PARSING')el('small',` 已读取 ${f.row_count} 行`,progress);
        if(f.error_message)el('small',f.error_message,progress,'fc-alert');
        const result=el('span',`已匹配 ${f.matched_count}`);
        for(const [key,label]of [['unmatched_count','待同步匹配'],['review_count','绑定待核对'],['conflict_count','计费待核对']])
          if(Number(f[key])>0)el('small',`${label} ${f[key]}`,result,'fc-alert');
        const actions=el('span',null,null,'fi-actions');
        button(actions,scope.fileId===f.id?'查看全部':'查看明细',()=>{scope.fileId=scope.fileId===f.id?'':f.id;scope.page=1;return refresh({detailsOnly:true});});
        if(canManage){
          if(f.status==='FAILED')button(actions,'重试',async()=>{await request('/label-costs/imports/'+encodeURIComponent(f.id)+'/retry',{method:'POST'});await refresh();});
          if(!['PARSING','PREPARED','REMOVE_QUEUED'].includes(f.status))button(actions,'移除',()=>{
            const d=dialog('移除账单');el('p',`移除 ${f.source_name} 的费用来源。其他账单、商品成本和手动成本保留；金额在后台核对完成后统一更新。`,d.body);
            button(d.foot,'确认移除',async()=>{await request('/label-costs/imports/'+encodeURIComponent(f.id),{method:'DELETE'});d.n.close();if(scope.fileId===f.id)scope.fileId='';await refresh();},'fi-primary');
          });
        }
        return {key:f.id,cells:[title,progress,f.row_count,result,['QUEUED','PARSING'].includes(f.status)?'待校验':'¥ '+money(f.amount_cny),actions]};
      }));
      const records=el('section',null,next,'fq-section');el('h4',scope.fileId?'所选账单明细':'订单绑定明细',records);
      table(records,['平台订单号 / 参考号','匹配店铺 / 客户','仓库 / 出库单号','账单交易日期','贴单金额（¥）','状态'],data.rows.map(r=>{
        const shop=el('span',r.shop_name||'待匹配');el('small',r.customer||r.shop_hint||'来源未填店铺',shop,'fi-muted');
        const warehouse=el('span',r.warehouse);if(r.document_no)el('small',r.document_no,warehouse,'fi-muted');
        const conflict=Number(r.conflict_count)>0;
        const flag=el('span',conflict?'费用待核对':r.match_status==='MATCHED'?'已匹配':r.match_status==='REVIEW'?'待核对':'待同步匹配',null,'fc-tag '+(r.match_status==='MATCHED'&&!conflict?'good':'warn'));
        flag.title=conflict?'该订单存在费用来源或订单归属冲突，未确认部分暂不计入':r.match_note;
        if(r.import_status!=='READY')el('small',' · '+(status[r.import_status]||r.import_status),flag);
        return {key:r.import_id+':'+r.source_row,cells:[r.order_id||'参考号为空',shop,warehouse,String(r.transaction_date).slice(0,10),'¥ '+money(r.amount_cny),flag]};
      }));
      const pages=el('div',null,records,'fi-actions fq-pagination');
      button(pages,'上一页',()=>{scope.page--;return refresh({detailsOnly:true});}).disabled=scope.page<=1;
      el('span',`第 ${scope.page} 页 · 共 ${data.total} 条来源`,pages);
      button(pages,'下一页',()=>{scope.page++;return refresh({detailsOnly:true});}).disabled=scope.page*50>=Number(data.total);
      if(globalThis.IncrementalDOM)IncrementalDOM.patch(content,next);else content.replaceChildren(...next.childNodes);
    }
    async function refresh({detailsOnly=false,quiet=false}={}){
      clearTimeout(timer);controller?.abort();const current=new AbortController();controller=current;
      const run=++sequence,key=JSON.stringify([scope.start,scope.end,scope.groupId,scope.shopId,scope.platform]);
      const partial=Boolean(detailsOnly&&data&&periodKey===key);
      content.setAttribute('aria-busy','true');loading.hidden=quiet;
      const timeout=setTimeout(()=>current.abort(new Error('明细读取超时，请点击刷新重试')),30000);
      try{
        const q=Object.fromEntries(Object.entries(scope).filter(([,v])=>v!==''));if(partial)q.detailsOnly='true';
        const incoming=await request('/label-costs?'+new URLSearchParams(q),{signal:current.signal});
        if(disposed||run!==sequence||!root.isConnected)return;
        data=partial?{...data,...incoming}:incoming;if(!partial)periodKey=key;feedback.textContent='';draw();
        if(data.processing||data.files.some(f=>['QUEUED','PARSING','PREPARED','REMOVE_QUEUED'].includes(f.status)))
          timer=setTimeout(()=>{if(root.isConnected&&!disposed)refresh({quiet:true}).catch(e=>{feedback.textContent=e.message;});else dispose();},3000);
      }catch(error){
        if(disposed||run!==sequence)return;
        if(root.isConnected)
          timer=setTimeout(()=>{if(root.isConnected&&!disposed)refresh({quiet:true}).catch(e=>{feedback.textContent=e.message;});else dispose();},10000);
        throw current.signal.aborted?current.signal.reason:error;
      }finally{clearTimeout(timeout);if(run===sequence){content.setAttribute('aria-busy','false');loading.hidden=true;}}
    }
    draw();refresh().catch(e=>{feedback.textContent=e.message;});
    return {dispose,upload};
  }
  globalThis.FinanceLabelCosts={mount,reset(){[...disposers].forEach(fn=>fn());document.querySelectorAll('.fl-dialog').forEach(n=>n.remove());}};
})();
