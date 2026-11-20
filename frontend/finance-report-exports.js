(() => {
  'use strict';
  const base='/api/v1/finance/report-exports';
  const targets='[data-finance-export-status]';
  const panelSelector='[data-finance-report-export-tasks]';
  const labels={READY:'可导出',PROCESSING:'处理中',PARTIAL:'部分待补齐',ERROR:'处理异常'};
  const jobsLabels={QUEUED:'排队中',RUNNING:'生成中',READY:'可下载',FAILED:'生成失败'};
  const reportNames={RECORDS:'财务报表',ADVERTISING:'广告消耗',FEE_MATRIX:'平台费用汇总',ORDER_COST:'订单成本',QIANYI:'千易售后'};
  const scopeKeys=new Set(['start','end','shopId','groupId','platform','currency','displayCurrency','granularity','fxSnapshot',
    'view','kind','format','orderScope','numbers','category','advertisingOnly','search','assignment','recordId','costStatus',
    'dateField','receiptScope','receiptStatus','type','returnStatus','refundStatus']);
  const cache=new Map(), painted=new WeakMap(), attempts=new Map();
  let owner='',version=0,table=null,reading=false,lastRead=0,message='',timer=null,queued=false,requestedOpen=false;

  function identity(){
    return typeof currentUser==='undefined'||!currentUser?.organizationId||!currentUser?.userId
      ?'':JSON.stringify([currentUser.organizationId,currentUser.userId]);
  }
  function syncOwner(){
    const next=identity();
    if(next!==owner){
      owner=next;version++;cache.clear();attempts.clear();
      table=null;reading=false;lastRead=0;message='';requestedOpen=false;
      paintTasks();
      for(const target of document.querySelectorAll(targets))painted.delete(target);
    }
    return owner;
  }
  const el=(parent,tag,value,style)=>{
    const node=document.createElement(tag);
    if(value!=null)node.textContent=value;
    if(style)node.className=style;
    parent?.append(node);
    return node;
  };
  function action(parent,caption,run){
    const button=el(parent,'button',caption,'fi-action-link');
    button.type='button';
    button.onclick=async event=>{
      const current=event.currentTarget;
      current.disabled=true;
      try{await run();}catch(error){message=error.message||'操作未确认，请重试';openTasks();}
      finally{current.disabled=false;}
    };
    return button;
  }
  const post=(path,body,headers={})=>api(base+path,{
    method:'POST',headers:{'Content-Type':'application/json',...headers},body:JSON.stringify(body)
  }).then(r=>r.data);
  function scope(type,selection){
    const chosen=Object.fromEntries(Object.entries(selection??{}).filter(([k,v])=>scopeKeys.has(k)&&v!=null).sort(([a],[b])=>a.localeCompare(b))
      .map(([k,v])=>[k,String(v)]));
    return {type,selection:chosen};
  }
  function keyOf(request){return JSON.stringify(request);}
  function readStatus(request,force=false){
    if(!syncOwner())return Promise.reject(Error('请先登录'));
    const key=keyOf(request),old=cache.get(key),now=Date.now();
    if(old?.promise)return old.promise;
    if(!force&&old?.time&&now-old.time<(old.result?.state==='PROCESSING'?9000:25000))
      return old.error?Promise.reject(old.error):Promise.resolve(old.result);
    const started=version;
    const entry={};
    entry.promise=post('/status',request).then(result=>{
      if(started!==version||identity()!==owner)throw Error('登录信息已变化');
      entry.result=result;
      return result;
    }).catch(error=>{entry.error=error;throw error;}).finally(()=>{
      entry.time=Date.now();delete entry.promise;schedule();
    });
    cache.set(key,entry);
    if(cache.size>40){
      for(const [candidate,item] of cache)if(!item.promise&&candidate!==key){cache.delete(candidate);break;}
    }
    return entry.promise;
  }
  function formatTime(value){
    if(!value)return '';
    const date=new Date(value);
    return Number.isFinite(date.getTime())?date.toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):'';
  }
  function noData(node){node.textContent='等待当前查询完成';}
  function decorate(button,type,selection){
    let status=button.nextElementSibling;
    if(!status?.hasAttribute('data-finance-export-status')){
      status=el(null,'span',null,'fi-report-export-status');
      status.dataset.financeExportStatus='';
      status.setAttribute('role','status');
      status.setAttribute('aria-live','polite');
      button.after(status);
    }
    status.dataset.exportRequest=selection?keyOf(scope(type,selection)):'';
    noData(status);
    painted.delete(status);
    schedule();
    return button;
  }
  function paintStatus(node,request,entry){
    const result=entry?.result;
    const key=JSON.stringify([node.dataset.exportRequest,result,!!entry?.error]);
    if(painted.get(node)===key&&node.childNodes.length)return;
    painted.set(node,key);
    node.replaceChildren();
    const label=entry?.error?'状态暂时不可用':(labels[result?.state]||'正在核对数据状态…');
    const badge=el(node,'span',label,'fi-status');
    if(result?.state==='READY')badge.classList.add('success');
    if(result?.state==='PROCESSING')badge.classList.add('running');
    if(result?.state==='ERROR'||entry?.error)badge.classList.add('error');
    if(entry?.error){
      action(node,'重试',()=>readStatus(request,true));
      return;
    }
    if(result?.dataAsOf)el(node,'small',(result.state==='READY'?' 数据截至 ':' 已确认来源截至 ')+formatTime(result.dataAsOf)+'（北京时间）','fi-muted');
    if(result?.gapCount){
      const detail=el(node,'details',null,'fi-report-export-gaps');
      el(detail,'summary','查看缺口（'+result.gapCount+'）');
      const list=el(detail,'ul');
      for(const gap of result.gaps??[])el(list,'li',[gap.shop,gap.source,gap.detail].filter(Boolean).join(' · '));
      if(result.gapCount>(result.gaps??[]).length)el(list,'li','还有 '+(result.gapCount-result.gaps.length)+' 项缺口');
    }
    if(result?.state==='PROCESSING')action(node,'完成后提醒',async()=>{
      ensureCurrent(node,request);
      await post('/reminders',request);
      message='已订阅。当前范围处理完成后将在导出任务中提醒。';
      openTasks();await readTasks(true);
    });
    if(result?.state==='PARTIAL')action(node,'导出核对版',async()=>{
      ensureCurrent(node,request);
      await submit(request.type,{...request.selection,format:'xlsx'},true);
      if(request.type==='RECORDS')node.closest('dialog')?.close();
    });
  }
  function ensureCurrent(node,request){
    if(!node.isConnected||node.dataset.exportRequest!==keyOf(request)||pending(node))
      throw Error('筛选条件已切换，请确认后再操作');
  }
  function pending(node){
    return !!node.closest('.fi-selection-pending, .fi-content[hidden]');
  }
  function reconcile(){
    queued=false;
    if(!syncOwner())return;
    for(const node of document.querySelectorAll(targets)){
      const key=node.dataset.exportRequest;
      if(!key||pending(node)){
        if(node.textContent!=='等待当前查询完成')noData(node);
        painted.delete(node);
        continue;
      }
      const request=JSON.parse(key);
      paintStatus(node,request,cache.get(key));
      if(!cache.get(key)?.error)readStatus(request).catch(()=>{});
    }
    const panel=document.querySelector(panelSelector);
    if(panel){
      if(panel.open&&!panel.querySelector('.fi-export-panel'))paintTasks();
      readTasks().catch(()=>{});
    }
  }
  function schedule(){
    if(queued)return;
    queued=true;queueMicrotask(reconcile);
  }
  function mountTasks(parent){
    const panel=el(parent,'details',null,'fi-export-menu fi-report-export-tasks');
    panel.dataset.financeReportExportTasks='';
    panel.dataset.updateKey='finance-report-export-tasks';
    el(panel,'summary','导出任务','fi-export-trigger');
    panel.ontoggle=event=>{if(event.currentTarget.open){readTasks(true).catch(()=>{});paintTasks();}};
    if(!timer)timer=setInterval(()=>{if(!document.hidden&&document.querySelector(panelSelector))schedule();},10000);
    schedule();
    return panel;
  }
  function openTasks(){
    const panel=document.querySelector(panelSelector);
    if(!panel){requestedOpen=true;return;}
    panel.open=true;
    paintTasks();
  }
  function readTasks(force=false){
    if(!syncOwner()||reading||!force&&Date.now()-lastRead<10000)return Promise.resolve();
    const run=version;reading=true;
    return api(base,{cache:'no-store'}).then(({data})=>{
      if(run!==version||identity()!==owner)return;
      table=data;
      lastRead=Date.now();
    }).catch(error=>{
      if(run!==version)return;
      lastRead=Date.now();message=error.message||'无法读取导出任务';
    }).finally(()=>{
      if(run!==version)return;
      reading=false;paintTasks();
    });
  }
  function caption(item){
    const start=item.start??item.start_date,end=item.end??item.end_date;
    return [start&&end?start+' 至 '+end:'全部收货日期',item.platform,
      item.currency||item.display_currency].filter(Boolean).join(' · ');
  }
  function paintTasks(){
    const panel=document.querySelector(panelSelector);
    if(!panel)return;
    const jobs=[...(table?.jobs??[]).map(j=>({...j,path:base})),
      ...(table?.costJobs??[]).map(j=>({...j,path:'/api/v1/finance/cost-exports'})),
      ...(table?.feeJobs??[]).map(j=>({...j,path:'/api/v1/finance/fee-exports'}))];
    const reminders=table?.reminders??[];
    const ready=reminders.filter(item=>item.state==='READY');
    const active=jobs.filter(j=>['RUNNING','QUEUED'].includes(j.status)).length;
    const summary=panel.querySelector('summary');
    const title='导出任务'+(active||ready.length?'（'+(active+ready.length)+'）':'');
    if(summary.textContent!==title)summary.textContent=title;
    let notice=panel.parentElement.querySelector('[data-finance-report-reminder-notice]');
    if(ready.length&&!notice){
      notice=el(null,'span',null,'fi-report-export-reminder');
      notice.dataset.financeReportReminderNotice='';
      notice.setAttribute('role','status');
      panel.after(notice);
    }
    if(notice){notice.hidden=!ready.length;notice.textContent=ready.length?ready.length+' 个所订阅报表可导出':'';}
    if(requestedOpen){requestedOpen=false;panel.open=true;}
    panel.querySelector('.fi-export-panel')?.remove();
    if(!panel.open)return;
    const body=el(panel,'section',null,'fi-export-panel');
    body.setAttribute('aria-label','财务报表导出和完成提醒');
    const head=el(body,'div',null,'fi-export-panel-head');
    el(head,'strong','导出任务');
    action(head,'刷新',()=>readTasks(true));
    if(message)el(body,'p',message,'fi-export-message');
    if(!jobs.length&&!reminders.length)el(body,'p',reading?'正在读取任务…':'暂无导出任务或提醒','fi-muted');
    for(const reminder of reminders){
      const row=el(body,'article',null,'fi-export-job');
      el(row,'strong',reportNames[reminder.report_type]||'报表处理提醒');
      el(row,'small',caption(reminder),'fi-export-scope');
      el(row,'span',reminder.state==='READY'?'处理完成 · 可导出':'等待数据就绪','fi-export-status');
      if(reminder.state==='READY')action(row,'生成 Excel',async()=>{
        const current=syncOwner();
        const {data}=await api(base+'/reminders/'+encodeURIComponent(reminder.id),{cache:'no-store'});
        if(identity()!==current)throw Error('登录信息已变化');
        await submit(data.type,{...data.selection,format:'xlsx'});
      });
      action(row,reminder.state==='READY'?'关闭提醒':'取消订阅',async()=>{
        await post('/reminders/'+encodeURIComponent(reminder.id)+'/dismiss',{});
        table.reminders=table.reminders.filter(item=>item.id!==reminder.id);
        paintTasks();await readTasks(true);
      });
    }
    for(const job of jobs){
      const row=el(body,'article',null,'fi-export-job');
      el(row,'strong',job.filename||'财务报表');
      el(row,'small',caption(job),'fi-export-scope');
      el(row,'span',jobsLabels[job.status]||'状态待确认','fi-export-status '+job.status.toLowerCase());
      if(job.dataStatus?.state==='PARTIAL')el(row,'small','部分数据 · 仅供核对','fi-export-message');
      if(job.status==='FAILED')el(row,'p',job.error||'生成失败，请重新提交','fi-export-message');
      if(job.status==='READY'){
        const download=el(row,'a','下载','fi-action-link');
        download.href=job.path+'/'+encodeURIComponent(job.id)+'/download';
        download.download=job.filename||'';
      }
    }
    el(body,'small','生成后可在文件保留期内下载。提醒保留 7 天。','fi-export-retention');
  }
  async function submit(type,selection,partial=false){
    const request=scope(type,selection),principal=syncOwner();
    if(!principal)throw Error('请先登录');
    const status=await readStatus(request,true);
    if(identity()!==principal)throw Error('登录信息已变化，请重新打开导出');
    if(status.state!=='READY'&&!(status.state==='PARTIAL'&&partial))
      throw Error(status.state==='PARTIAL'?'当前存在缺口，可选择导出核对版':'当前报表尚未就绪，请查看数据状态');
    const payload={...request,revision:status.revision,partial};
    const identityKey=keyOf(payload),key=attempts.get(identityKey)||crypto.randomUUID();
    attempts.set(identityKey,key);
    if(attempts.size>25)attempts.delete(attempts.keys().next().value);
    let job;
    try{job=await post('',payload,{'Idempotency-Key':key});}
    catch(error){if(error.status&&error.status<500)attempts.delete(identityKey);throw error;}
    if(identity()!==principal)throw Error('登录信息已变化，请重新打开导出');
    attempts.delete(identityKey);
    message='导出任务已提交，生成后可在这里下载。';
    if(!table)table={jobs:[],costJobs:[],reminders:[]};
    const list=type==='ORDER_COST'?'costJobs':'jobs';
    table[list]=[job,...(table[list]??[]).filter(item=>item.id!==job.id)];
    openTasks();
    readTasks(true).catch(()=>{});
    return job;
  }
  new MutationObserver(schedule).observe(document.getElementById('finance-page')||document.body,{
    childList:true,subtree:true,attributes:true,attributeFilter:['data-export-request','aria-busy']
  });
  globalThis.FinanceReportExports={decorate,mountTasks,submit};
})();
