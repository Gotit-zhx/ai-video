(() => {
  'use strict';
  const base='/api/v1/finance/report-exports';
  const targets='[data-finance-export-status]';
  const panelSelector='[data-finance-report-export-tasks]';
  const labels={READY:'可导出',PROCESSING:'处理中',PARTIAL:'部分待补齐',ERROR:'处理异常'};
  const jobsLabels={QUEUED:'排队中',WAITING:'等待数据或重试',RUNNING:'生成中',READY:'可下载',FAILED:'生成失败'};
  const reportNames={RECORDS:'财务报表',ADVERTISING:'广告消耗',FEE_MATRIX:'平台费用汇总',ORDER_COST:'订单成本',QIANYI:'千易售后'};
  const scopeKeys=new Set(['start','end','shopId','groupId','platform','currency','displayCurrency','granularity','fxSnapshot',
    'view','kind','format','orderScope','numbers','category','advertisingOnly','search','assignment','recordId','costStatus',
    'dateField','receiptScope','receiptStatus','type','returnStatus','refundStatus']);
  const cache=new Map(), painted=new WeakMap(), attempts=new Map(), taskTabs=new WeakMap(), taskPainted=new WeakMap();
  let owner='',version=0,table=null,reading=null,lastRead=0,message='',timer=null,queued=false,requestedOpen=false;
  let activity=null;

  function identity(){
    return typeof currentUser==='undefined'||!currentUser?.organizationId||!currentUser?.userId
      ?'':JSON.stringify([currentUser.organizationId,currentUser.userId]);
  }
  function syncOwner(){
    const next=identity();
    if(next!==owner){
      owner=next;version++;cache.clear();attempts.clear();
      table=null;reading=null;lastRead=0;message='';requestedOpen=false;
      activity?.controller?.abort();activity=null;
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
  const post=(path,body,headers={},signal)=>api(base+path,{
    method:'POST',headers:{'Content-Type':'application/json',...headers},body:JSON.stringify(body),signal
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
      message='已订阅就绪提醒。处理完成后可在这里提交导出。';
      openTasks();await readTasks(true);
    });
    if(result?.durableExports&&['PROCESSING','PARTIAL'].includes(result.state))action(node,'就绪后生成',async()=>{
      ensureCurrent(node,request);
      await submit(request.type,{...request.selection,format:'xlsx'});
      if(request.type==='RECORDS')node.closest('dialog')?.close();
    });
    const availableNow=result?.state==='PROCESSING'&&request.type==='ORDER_COST';
    if(result?.state==='PARTIAL'||availableNow)action(node,availableNow?'导出当前数据':'导出核对版',async()=>{
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
    if(document.hidden)return;
    for(const node of document.querySelectorAll(targets)){
      const key=node.dataset.exportRequest;
      if(!key||pending(node)){
        if(node.textContent!=='等待当前查询完成')noData(node);
        painted.delete(node);
        continue;
      }
      const request=JSON.parse(key);
      paintStatus(node,request,cache.get(key));
      if(!node.closest('#finance-page[aria-busy="true"]')&&node.getClientRects().length&&!cache.get(key)?.error)
        readStatus(request).catch(()=>{});
    }
    const panel=document.querySelector(panelSelector);
    if(panel){
      if(document.hidden||panel.closest('[hidden]'))return;
      if(panel.open&&!panel.querySelector('.fi-export-panel'))paintTasks();
      readActivity(panel);
      readTasks().catch(()=>{});
    }
  }
  function schedule(){
    if(queued)return;
    queued=true;queueMicrotask(reconcile);
  }
  function applyActivityScope(panel,page,selection){
    const values=page&&selection?scope(page,selection).selection:null;
    if(values&&page==='LABELS'&&selection.fileId)values.fileId=selection.fileId;
    const key=values?JSON.stringify({page,selection:values}):'';
    if(panel.dataset.activityRequest!==key)panel.dataset.activityRequest=key;
    schedule();
  }
  function setActivityScope(page,selection){
    const panel=document.querySelector(panelSelector);
    if(panel)applyActivityScope(panel,page,selection);
  }
  function activityKey(panel){
    return pending(panel)?'':panel.dataset.activityRequest||'';
  }
  function activityFor(panel){
    return activity?.key===activityKey(panel)?activity:null;
  }
  function readActivity(panel,force=false){
    const key=activityKey(panel);
    if((activity?.key??'')!==key){
      activity?.controller?.abort();
      activity=null;
      paintTasks();
    }
    if(!key||activity?.controller||!force&&activity?.checkedAt&&Date.now()-activity.checkedAt<15000)return;
    const entry=activity??={key};
    const controller=new AbortController(),run=version;
    entry.controller=controller;
    return post('/activity',JSON.parse(key),{},controller.signal).then(items=>{
      if(activity!==entry||version!==run||identity()!==owner)return;
      if(!Array.isArray(items))throw Error('任务状态响应不完整');
      entry.items=items;entry.error=false;
    }).catch(()=>{
      if(activity===entry&&!controller.signal.aborted){entry.items=[];entry.error=true;}
    }).finally(()=>{
      if(activity!==entry||version!==run||identity()!==owner)return;
      entry.controller=null;entry.checkedAt=Date.now();
      paintTasks();
    });
  }
  function mountTasks(parent,context=null){
    const panel=el(parent,'details',null,'fi-export-menu fi-report-export-tasks');
    panel.dataset.financeReportExportTasks='';
    panel.dataset.updateKey='finance-report-export-tasks';
    el(panel,'summary','任务','fi-export-trigger');
    applyActivityScope(panel,context?.page,context?.selection);
    panel.ontoggle=event=>{
      const current=event.currentTarget;
      if(current.open){
        if(!taskTabs.has(current))taskTabs.set(current,current.dataset.activityRequest?'current':'exports');
        readActivity(current,true);readTasks(true).catch(()=>{});paintTasks();
      }
    };
    if(!timer)timer=setInterval(()=>{if(!document.hidden&&document.querySelector(panelSelector))schedule();},10000);
    schedule();
    return panel;
  }
  function openTasks(){
    const panel=document.querySelector(panelSelector);
    if(!panel){requestedOpen=true;return;}
    taskTabs.set(panel,'exports');
    panel.open=true;
    paintTasks();
  }
  function closeTasks(panel,restoreFocus=false){
    if(!panel?.open)return;
    requestedOpen=false;
    panel.open=false;
    if(restoreFocus)panel.querySelector('summary')?.focus();
  }
  function readTasks(force=false){
    if(!syncOwner())return Promise.resolve();
    if(reading)return reading;
    if(!force&&Date.now()-lastRead<10000)return Promise.resolve();
    const run=version;
    reading=api(base,{cache:'no-store'}).then(({data})=>{
      if(run!==version||identity()!==owner)return;
      table=data;
      lastRead=Date.now();
    }).catch(error=>{
      if(run!==version)return;
      lastRead=Date.now();message=error.message||'无法读取导出任务';
    }).finally(()=>{
      if(run!==version)return;
      reading=null;paintTasks();
    });
    return reading;
  }
  function caption(item){
    const start=item.start??item.start_date,end=item.end??item.end_date;
    return [start&&end?start+' 至 '+end:'全部收货日期',item.platform,
      item.currency||item.display_currency].filter(Boolean).join(' · ');
  }
  function selectTaskTab(panel,tab){
    taskTabs.set(panel,tab);
    paintTasks();
  }
  function paintActivity(body,panel,current){
    const items=current?.items??[];
    if(!activityKey(panel)){el(body,'p','当前页面暂无后台任务','fi-muted');return;}
    if(current?.error){el(body,'p','暂时无法读取后台任务','fi-muted');return;}
    if(!current?.checkedAt){el(body,'p','正在读取后台任务…','fi-muted');return;}
    if(!items.length){el(body,'p','当前范围没有后台任务','fi-muted');return;}
    for(const [sync,label] of [[true,'数据同步'],[false,'数据处理']]){
      const entries=items.filter(item=>(item.category==='SYNC')===sync);
      if(!entries.length)continue;
      el(body,'strong',label);
      for(const item of entries){
        const row=el(body,'div',null,'fi-page-task');
        const caption=el(row,'div',null,'fi-page-task-caption');
        el(caption,'strong',item.label);
        if(item.affectsExport)el(caption,'small','影响完整导出','fi-muted');
        const details=[...item.details??[]];
        if(item.retryAt)details.push('下次重试 '+formatTime(item.retryAt));
        el(row,'small',details.join(' · '),'fi-muted');
      }
    }
  }
  function paintTasks(){
    const panel=document.querySelector(panelSelector);
    if(!panel)return;
    const jobs=[...(table?.jobs??[]).map(j=>({...j,path:base})),
      ...(table?.costJobs??[]).map(j=>({...j,path:'/api/v1/finance/cost-exports'})),
      ...(table?.feeJobs??[]).map(j=>({...j,path:'/api/v1/finance/fee-exports'}))];
    const reminders=table?.reminders??[];
    const ready=reminders.filter(item=>item.state==='READY');
    const active=jobs.filter(j=>['RUNNING','QUEUED','WAITING'].includes(j.status)).length;
    const current=activityFor(panel),count=current?.items?.length??0;
    const summary=panel.querySelector('summary');
    const title=count?'任务 · '+count+' 类处理中':'任务';
    if(summary.textContent!==title)summary.textContent=title;
    let notice=panel.parentElement.querySelector('[data-finance-report-reminder-notice]');
    if(ready.length&&!notice){
      notice=el(null,'span',null,'fi-report-export-reminder');
      notice.dataset.financeReportReminderNotice='';
      notice.setAttribute('role','status');
      panel.after(notice);
    }
    if(notice){
      const hidden=ready.length===0;
      if(notice.hidden!==hidden)notice.hidden=hidden;
      const content=ready.length?ready.length+' 个所订阅报表可导出':'';
      if(notice.textContent!==content)notice.textContent=content;
    }
    if(requestedOpen){requestedOpen=false;taskTabs.set(panel,'exports');panel.open=true;}
    if(!panel.open)return;
    let body=panel.querySelector('.fi-export-panel');
    if(!body){
      body=el(panel,'section',null,'fi-export-panel');
      body.setAttribute('aria-label','财务任务');
      const head=el(body,'div',null,'fi-export-panel-head');
      const tabs=el(head,'div',null,'fi-task-tabs');
      tabs.setAttribute('role','group');tabs.setAttribute('aria-label','任务分类');
      for(const [tab,label] of [['current','后台任务'],['exports','导出任务']]){
        const button=el(tabs,'button',label);button.type='button';button.dataset.taskTab=tab;
        button.onclick=()=>selectTaskTab(panel,tab);
      }
      const controls=el(head,'div',null,'fi-export-panel-actions');
      action(controls,'刷新',()=>taskTabs.get(panel)==='current'?readActivity(panel,true):readTasks(true));
      const close=el(controls,'button','关闭','fi-action-link');
      close.type='button';
      close.onclick=event=>closeTasks(event.currentTarget.closest(panelSelector),true);
    }
    const tab=taskTabs.get(panel)??'current';
    const buttons=body.querySelectorAll('[data-task-tab]');
    for(const button of buttons)button.setAttribute('aria-pressed',String(button.dataset.taskTab===tab));
    const exportTitle=active||ready.length?'导出任务（'+(active+ready.length)+'）':'导出任务';
    if(buttons[1].textContent!==exportTitle)buttons[1].textContent=exportTitle;
    const signature=JSON.stringify(tab==='current'?[tab,version,activityKey(panel),current?.items,!!current?.checkedAt,current?.error]:
      [tab,version,jobs,reminders,message,reading&&!jobs.length&&!reminders.length]);
    if(taskPainted.get(body)===signature)return;
    taskPainted.set(body,signature);
    for(const item of [...body.children].slice(1))item.remove();
    if(tab==='current'){paintActivity(body,panel,current);return;}
    if(message)el(body,'p',message,'fi-export-message');
    if(!jobs.length&&!reminders.length)el(body,'p',reading?'正在读取任务…':'暂无导出任务或提醒','fi-muted');
    for(const reminder of reminders){
      const row=el(body,'article',null,'fi-export-job');
      el(row,'strong',reportNames[reminder.report_type]||'报表处理提醒');
      el(row,'small',caption(reminder),'fi-export-scope');
      const ready=reminder.state==='READY';
      el(row,'span',ready?'处理完成 · 可导出':'就绪提醒 · 等待数据，尚未生成文件','fi-export-status');
      if(ready||reminder.report_type==='ORDER_COST')action(row,ready?'生成 Excel':'导出当前数据',async()=>{
        const current=syncOwner();
        const {data}=await api(base+'/reminders/'+encodeURIComponent(reminder.id),{cache:'no-store'});
        if(identity()!==current)throw Error('登录信息已变化');
        await submit(data.type,{...data.selection,format:'xlsx'},!ready);
      });
      action(row,ready?'关闭提醒':'取消订阅',async()=>{
        await post('/reminders/'+encodeURIComponent(reminder.id)+'/dismiss',{});
        table.reminders=table.reminders.filter(item=>item.id!==reminder.id);
        paintTasks();await readTasks(true);
      });
    }
    for(const job of jobs){
      const row=el(body,'article',null,'fi-export-job');
      el(row,'strong',job.filename||(job.kind&&job.path==='/api/v1/finance/cost-exports'?'订单成本':'财务报表'));
      el(row,'small',caption(job),'fi-export-scope');
      el(row,'span',jobsLabels[job.status]||'状态待确认','fi-export-status '+job.status.toLowerCase());
      if(job.status==='RUNNING'&&job.total>0)el(row,'small','已处理 '+job.processed+' / '+job.total+' 笔订单','fi-muted');
      if(job.status==='WAITING'&&job.error)el(row,'small',job.error,'fi-muted');
      if(job.status==='READY'&&['PARTIAL','PROCESSING'].includes(job.dataStatus?.state))el(row,'small','部分数据 · 仅供核对','fi-export-message');
      if(job.status==='READY'&&job.dataStatus?.extractedAt)el(row,'small','取数时间 '+formatTime(job.dataStatus.extractedAt)+'（北京时间）','fi-muted');
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
    const payload={...request,partial};
    const legacyCostReview=type==='ORDER_COST'&&partial;
    if(!legacyCostReview&&typeof table?.durableExports!=='boolean'){
      await readTasks(true);
      if(identity()!==principal)throw Error('登录信息已变化，请重新打开导出');
    }
    if(!legacyCostReview&&!table?.durableExports){
      const status=await readStatus(request,true);
      if(identity()!==principal)throw Error('登录信息已变化，请重新打开导出');
      if(!status.durableExports){
        if(status.state!=='READY'&&!(partial&&status.state==='PARTIAL'))
          throw Error(status.state==='PARTIAL'?'当前存在缺口，可选择导出核对版':'当前报表尚未就绪，请查看数据状态');
        payload.revision=status.revision;
      }
    }
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
    const list=type==='ORDER_COST'&&!job.durable?'costJobs':'jobs';
    table[list]=[job,...(table[list]??[]).filter(item=>item.id!==job.id)];
    openTasks();
    readTasks(true).catch(()=>{});
    return job;
  }
  document.addEventListener('pointerdown',event=>{
    const panel=document.querySelector(panelSelector+'[open]');
    if(panel&&!event.composedPath().includes(panel))closeTasks(panel);
  },true);
  document.addEventListener('keydown',event=>{
    if(event.key!=='Escape'||event.defaultPrevented||document.querySelector('dialog[open]'))return;
    const panel=document.querySelector(panelSelector+'[open]');
    if(!panel||!panel.getClientRects().length)return;
    event.preventDefault();
    closeTasks(panel,true);
  });
  new MutationObserver(schedule).observe(document.getElementById('finance-page')||document.body,{
    childList:true,subtree:true,attributes:true,attributeFilter:['data-export-request','data-activity-request','aria-busy','hidden']
  });
  document.addEventListener('visibilitychange',schedule);
  globalThis.FinanceReportExports={decorate,mountTasks,setActivityScope,submit};
})();
