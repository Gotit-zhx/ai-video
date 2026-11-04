/* Short-lived asynchronous fee matrices; task creation is never automatically retried. */
(() => {
  'use strict';
  let context=null,owner='',jobs=[],timer=null,loading=false,submitting=false,message='',uncertain=null,epoch=0;
  const statuses={QUEUED:'排队中',RUNNING:'生成中',READY:'可下载',FAILED:'生成失败'};
  const pending=job=>['QUEUED','RUNNING'].includes(job.status);
  const el=(tag,text,parent,cls)=>{const node=document.createElement(tag);if(text!=null)node.textContent=text;if(cls)node.className=cls;parent?.append(node);return node;};
  function reset(){epoch++;clearTimeout(timer);timer=null;context=null;owner='';jobs=[];loading=false;submitting=false;message='';uncertain=null;}
  function caption(selection,ctx=context){
    const shop=ctx.shops.find(s=>s.id===selection.shopId),group=ctx.groups.find(g=>g.id===selection.groupId);
    const scope=selection.shopId?(shop?.display_name||shop?.name||'所选店铺'):
      selection.groupId==='UNGROUPED'?'未分组全部店铺':selection.groupId?(group?.name||'所选分组')+'全部店铺':'全部分组、全部店铺';
    return `${selection.start} 至 ${selection.end} · ${scope} · ${selection.platform||'全部平台'} · ${selection.currency?'原币 '+selection.currency:'原币分币种'}`;
  }
  function repaint(){for(const node of document.querySelectorAll('[data-finance-fee-exports]'))paint(node);}
  function positionPanel(details){
    if(!details.open||!details.isConnected)return;
    const panel=details.querySelector('.fi-export-panel'),anchor=details.getBoundingClientRect();
    const workspace=details.closest('.finance-workspace')?.getBoundingClientRect();
    const left=Math.max(16,(workspace?.left||0)+16),right=Math.min(innerWidth-16,workspace?.right||innerWidth-16);
    const width=Math.min(440,Math.max(240,right-left));
    panel.style.width=width+'px';panel.style.right='auto';
    panel.style.left=(Math.max(left,Math.min(anchor.right-width,right-width))-anchor.left)+'px';
  }
  function paint(details){
    details.replaceChildren();
    const active=jobs.filter(pending).length;
    el('summary',(context.canCreate?'全体导出':'导出任务')+(active?`（${active}）`:'')+' ▾',details,'fi-export-trigger');
    const panel=el('section',null,details,'fi-export-panel');panel.setAttribute('aria-label','费用汇总导出');panel.setAttribute('aria-live','polite');
    const head=el('div',null,panel,'fi-export-panel-head');el('strong','费用汇总导出',head);
    const close=el('button','收起',head);close.type='button';close.onclick=event=>{event.currentTarget.closest('details').open=false;};
    if(context.canCreate){
      el('p',caption(context.selection),panel,'fi-export-scope');
      el('p','费用项目按行、店铺按列；固定导出原币，不同币种分别列工作表。',panel,'fi-muted');
      const start=el('button',submitting?'正在提交…':'生成 Excel',panel,'fi-primary');start.type='button';start.disabled=submitting;
      start.onclick=()=>submit();
    }
    if(message)el('p',message,panel,'fi-export-message');
    if(uncertain){
      el('p','上次提交结果待确认：'+caption(uncertain.body),panel,'fi-export-scope');
      const retry=el('button','确认上次任务',panel);retry.type='button';retry.disabled=submitting;retry.onclick=()=>submit(uncertain);
    }
    const refresh=el('button',loading?'读取中…':'刷新任务状态',panel,'fi-export-refresh');refresh.type='button';refresh.disabled=loading;refresh.onclick=()=>refreshJobs();
    if(!jobs.length)el('p',loading?'正在读取任务…':'暂无导出任务',panel,'fi-muted');
    for(const job of jobs){
      const row=el('article',null,panel,'fi-export-job');row.dataset.updateKey=job.id;
      el('strong','平台费用汇总',row);el('small',caption(job),row);
      el('span',statuses[job.status]||'状态待确认',row,'fi-export-status '+job.status.toLowerCase());
      if(job.status==='FAILED')el('p',job.error||'生成失败，请重新生成',row,'fi-export-message');
      if(job.status==='READY'){
        const link=el('button','下载 Excel',row);link.type='button';link.onclick=event=>download(job,event.currentTarget);
      }
    }
    el('small','后台生成期间可继续操作。文件保留 30 分钟，过期后可重新生成。',panel,'fi-export-retention');
    requestAnimationFrame(()=>positionPanel(details));
  }
  function schedule(){
    clearTimeout(timer);timer=null;
    if(jobs.some(pending)&&document.querySelector('[data-finance-fee-exports]'))
      timer=setTimeout(()=>refreshJobs(),3000);
  }
  async function refreshJobs(){
    if(!context||loading)return;
    const ctx=context,identity=owner,run=epoch;loading=true;
    try{
      const result=await ctx.request('/fee-exports');
      if(run!==epoch||ctx.identity()!==identity)return;
      jobs=Array.isArray(result)?result:[];message='';schedule();
    }catch(error){
      if(run!==epoch||ctx.identity()!==identity)return;
      clearTimeout(timer);message='任务状态暂时无法读取，后台任务不会因此重建。可点刷新查看。';
    }finally{if(run===epoch){loading=false;repaint();}}
  }
  async function submit(saved){
    if(!context||submitting)return;
    const ctx=context,identity=owner,run=epoch;
    if(ctx.identity()!==identity)return reset();
    if(!saved&&ctx.isPending?.()){message='正在切换筛选条件，请等待查询完成后再导出。';repaint();return;}
    const body=saved?.body||{...Object.fromEntries(['start','end','shopId','groupId','platform','currency'].map(k=>[k,ctx.selection[k]||''])),displayCurrency:'ORIGINAL'};
    const key=saved?.key||crypto.randomUUID();
    submitting=true;message='';repaint();
    try{
      const job=await ctx.request('/fee-exports',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':key},body:JSON.stringify(body)});
      if(run!==epoch||ctx.identity()!==identity)return;
      uncertain=null;jobs=[job,...jobs.filter(item=>item.id!==job.id)];schedule();
    }catch(error){
      if(run!==epoch||ctx.identity()!==identity)return;
      message=error.message||'导出提交未确认';
      if(!error.status||Number(error.status)>=500)uncertain={body,key};
    }finally{if(run===epoch){submitting=false;repaint();}}
  }
  async function download(job,button){
    const ctx=context,identity=owner;button.disabled=true;button.textContent='正在下载…';
    try{
      const response=await fetch('/api/v1/finance/fee-exports/'+encodeURIComponent(job.id)+'/download',{credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(120000)});
      if(!response.ok){let error='下载失败，请刷新任务状态';try{error=(await response.json()).message||error;}catch{}throw Error(error);}
      const blob=await response.blob();
      if(ctx.identity()!==identity)return;
      const url=URL.createObjectURL(blob),link=el('a',null,document.body);
      link.href=url;link.download=job.filename;link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),10000);
    }catch(error){if(ctx.identity()===identity){message=error.message;repaint();}}
    finally{button.disabled=false;button.textContent='下载 Excel';}
  }
  function mount(parent,ctx){
    const identity=ctx.identity();
    if(identity!==owner){reset();owner=identity;}
    context=ctx;
    const details=el('details',null,parent,'fi-export-menu');details.dataset.financeFeeExports='';details.dataset.updateKey='finance-fee-exports';
    details.ontoggle=event=>{if(event.currentTarget.open){positionPanel(event.currentTarget);refreshJobs();}};
    paint(details);return details;
  }
  function open(){const node=document.querySelector('[data-finance-fee-exports]');if(node){node.open=true;node.querySelector('summary')?.focus();}}
  addEventListener('resize',()=>document.querySelectorAll('[data-finance-fee-exports]').forEach(positionPanel));
  globalThis.FinanceFeeExports={mount,open,reset};
})();
