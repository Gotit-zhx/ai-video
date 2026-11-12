/* Finance workspace. Tabler-inspired density; local ECharts; no synthetic production data. */
(() => {
  'use strict';
  const root=document.getElementById('finance-page');if(!root)return;
  const colors=['#4263eb','#53a8c9','#9a85d8','#e4ac62','#e7828c','#8198be'];
  const postedAdLabel='广告费用（已入账）';
  const routes={overview:'财务总览',live:'实时大屏',settlements:'结算中心',reconciliation:'对账中心',orders:'订单与退款',fees:'费用与调整','fee-records':'费用与调整','expense-ledger':'费用与调整',costs:'成本管理',ads:'广告分析',shops:'财务店铺管理'};
  const kinds={ORDER:'订单',REFUND:'退款',SETTLEMENT:'结算明细',BILL:'结算账单',FEE:'平台费用',AD:'广告费用',AD_GMV:'GMV Max 分析',COST:'物流贴单成本',PENDING:'待结算'};
  const states={RATE_LIMITED:'限流等待重试',READY:'待同步',RUNNING:'同步中',SYNCING:'历史回补中',SUCCESS:'已同步',ERROR:'同步异常',ACTIVE:'已授权',TOKEN_EXPIRED:'授权过期',DISABLED:'已断开',SETTLED:'已结算',IMPORTED:'已导入',REPORTED:'平台报表',ACCEPTED:'已确认退款',PROCESSING:'处理中',REQUESTED:'申请中',JUDGING:'平台审核中',CLOSED:'已关闭',REFUND_PAID:'退款已支付',RETURN_OR_REFUND_REQUEST_COMPLETE:'退款已完成',RETURN_OR_REFUND_REQUEST_CANCEL:'退款已取消',RETURN_OR_REFUND_REQUEST_SUCCESS:'申请成功',AWAITING_BUYER_SHIP:'等待买家寄回',PARTIAL:'明细同步中',UNSETTLED:'预计待结算',MATCHED:'核对一致',MISSING:'缺少明细',DIFFERENCE:'金额有差异'};
  const orderStates={UNPAID:'待付款',ON_HOLD:'订单暂挂',CANCELLED:'已取消',CANCELED:'已取消',IN_CANCEL:'取消处理中',COMPLETED:'已完成',DELIVERED:'已送达',AWAITING_SHIPMENT:'待发货',AWAITING_COLLECTION:'待揽收',PARTIALLY_SHIPPING:'部分发货',PARTIALLY_SHIPPED:'部分发货',IN_TRANSIT:'运输中',READY_TO_SHIP:'待发货',PROCESSED:'已处理',RETRY_SHIP:'待重新发货',SHIPPED:'已发货',TO_CONFIRM_RECEIVE:'待确认收货',TO_RETURN:'待退货'};
  const refundStates={INITIATED:'已发起申请',REFUND_OR_RETURN_REQUESTED:'已申请退货退款',RETURN_OR_REFUND_REQUEST_PENDING:'退货退款待处理',RETURN_OR_REFUND_REQUEST_REJECT:'退货退款已拒绝',RETURN_OR_REFUND_REQUEST_REJECTED:'退货退款已拒绝',RETURN_OR_REFUND_REQUEST_APPROVED:'退货退款已同意',REFUND_OR_RETURN_REQUEST_REJECT:'退货退款已拒绝',RETURN_OR_REFUND_REQUEST_REVIEWING:'退货退款审核中',RETURN_OR_REFUND_REQUEST_REVIEWING_APPEAL:'退货退款申诉审核中',RETURN_OR_REFUND_REQUEST_APPROVED_APPEAL:'退货退款申诉已通过',RETURN_OR_REFUND_REQUEST_REJECTED_APPEAL:'退货退款申诉已拒绝',RETURN_OR_REFUND_REQUEST_PENDING_BUYER_RETURN:'待买家退货',RETURN_OR_REFUND_REQUEST_BUYER_RETURNING:'买家退货中',RETURN_OR_REFUND_REQUEST_PENDING_RECEIVE:'待卖家收货',RETURN_OR_REFUND_REQUEST_RECEIVED:'退货已收到',REPLACEMENT_REQUEST_REFUND_SUCCESS:'换货退款已完成',REPLACEMENT_REQUEST_COMPLETE:'换货已完成',REPLACEMENT_REQUEST_CANCEL:'换货已取消',REJECTED:'已拒绝',APPROVED:'已同意',REFUNDED:'已退款',REFUNDING:'退款中'};
  const paths={chart:'M4 19h16M7 15v-4m5 4V5m5 10V8',download:'M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4',refresh:'M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-2l2 3M18 17a7 7 0 0 1-12 2l-2-3',shop:'M3 10h18l-2-7H5zM5 10v11h14V10M9 21v-7h6v7',wallet:'M3 6h18v15H3zM3 6V3h15v3M16 12h5v5h-5z',arrow:'M7 17 17 7M7 7h10v10',refund:'m9 6-5 5 5 5M4 11h10a5 5 0 0 1 0 10',ad:'m3 11 15-6v14L3 14zM6 15v6h4v-5M21 9v6',fee:'M7 3h10l4 4v14H3V7zM8 9h8M8 13h8M8 17h5',info:'M12 11v6M12 7v1M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',plus:'M12 4v16M4 12h16',check:'m5 12 4 4 10-10'};
  let callbackNotice='';const callbackParams=new URLSearchParams(location.search);if(callbackParams.has('financeAuth')){callbackNotice=callbackParams.get('financeMessage')||(callbackParams.get('financeAuth')==='success'?'财务店铺已连接':'财务授权未完成');callbackParams.delete('financeAuth');callbackParams.delete('financeMessage');history.replaceState(null,'',location.pathname+(callbackParams.size?'?'+callbackParams:'')+location.hash);}
  let adSupportExpanded=false;
  let active=false,generation=0,mountedGeneration=0,view='overview',data=null,chartPromise,charts=[],observer,themeObserver,refreshTimer=null,clockTimer=null,page=1,selectedKind='ORDER',mountedView='',loadController=null,mountedSelection='';
  let adSpendSearch='',adSpendAssignment='',adSpendPage=1,shopeeEpoch=0;
  let marketFx=null,marketFxError='',marketFxLoading=false,marketFxLoaded=false,marketFxOwner='',marketFxRun=0,marketFxController=null;
  let activeLoadKey='',waitingTimer=null,shopMonthFilter='ALL',shopSearch='';
  let costExportOwner='',costExportJobs=[],costExportTimer=null,costExportLoading=false,costExportSubmitting=false,costExportMessage='',costExportUncertain=null,costExportEpoch=0,costExportRevision=0,costExportDetails=null;
  const costExportSelections=new Map(),costExportAutoAttempted=new Set();
  const calendarVersion='shop-calendar-v1',displayZone='Asia/Shanghai';
  const dateText=d=>Number.isFinite(d.getTime())?d.toISOString().slice(0,10):'';
  const today=()=>{const p=Object.fromEntries(new Intl.DateTimeFormat('en-GB',{timeZone:displayZone,year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date()).map(p=>[p.type,p.value]));return `${p.year}-${p.month}-${p.day}`;};
  function monthRange(month){const [y,m]=month.split('-').map(Number);return {start:dateText(new Date(Date.UTC(y,m-1,1))),end:dateText(new Date(Date.UTC(y,m,0)))};}
  function previousMonth(){const [y,m]=today().split('-').map(Number);return dateText(new Date(Date.UTC(y,m-2,1))).slice(0,7);}
  function dateGranularity(start,end){const days=Math.round((Date.parse(end)-Date.parse(start))/86400000)+1;return days<=45?'day':days<=200?'week':days<=400?'month':days<=900?'quarter':days<=1500?'halfyear':'year';}
  const defaultFilters=()=>({...monthRange(previousMonth()),shopId:'',groupId:'',platform:'',currency:'',granularity:'day',displayCurrency:'CNY',fxSnapshot:'',dateBasis:calendarVersion});
  let filters=defaultFilters(),numberQueries={},orderScope='ALL';
  const allOrderView=()=>view==='orders'&&selectedKind==='ORDER'&&orderScope==='ALL';
  const detailNumbers=()=>numberQueries[view+':'+currentFieldView()]||'';
  let filterScope=null,pinnedDates=false,reportFilters=null;
  const filterStorageKey=()=>typeof currentUser!=='undefined'&&currentUser?.organizationId&&currentUser?.userId
    ?'heyi:finance-filters:v1:'+JSON.stringify([currentUser.organizationId,currentUser.userId]):'';
  const currentMonthEnd=()=>monthRange(today().slice(0,7)).end;
  const validFilterDate=value=>typeof value==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(value)
    &&dateText(new Date(value+'T12:00:00Z'))===value&&value<=currentMonthEnd();
  function restoreFilters(){
    const key=filterStorageKey();if(key===filterScope)return;
    adSpendSearch='';adSpendAssignment='';adSpendPage=1;
    filterScope=key;filters=defaultFilters();pinnedDates=false;reportFilters=null;
    if(!key)return;
    try{
      const saved=JSON.parse(sessionStorage.getItem(key)||'null');if(!saved||typeof saved!=='object')return;
      for(const field of ['shopId','groupId'])if(typeof saved[field]==='string'&&saved[field].length<=128)filters[field]=saved[field];
      if(['','SHOPEE','TIKTOK_SHOP'].includes(saved.platform))filters.platform=saved.platform;
      // A new visit starts with RMB across every native currency. Older sessions
      // also saved the server's automatic MYR/PHP choice, which silently narrowed "all shops".
      if(saved.pinnedDates===true&&validFilterDate(saved.start)&&validFilterDate(saved.end)&&saved.start<=saved.end){
        filters.start=saved.start;filters.end=saved.end;pinnedDates=true;
        filters.granularity=dateGranularity(filters.start,filters.end);
      }
    }catch{/* A blocked or invalid browser cache must not prevent financial queries. */}
  }
  function rememberFilters(){
    const key=filterStorageKey(),value=view==='live'?reportFilters:filters;
    if(!key||key!==filterScope||!value)return;
    const saved={pinnedDates};
    for(const field of ['shopId','groupId','platform'])saved[field]=value[field];
    if(pinnedDates){saved.start=value.start;saved.end=value.end;}
    // FX snapshots are refreshed on re-entry; the user's calendar and shop selection are retained.
    try{sessionStorage.setItem(key,JSON.stringify(saved));}catch{}
  }
  function updateFilters(changes){
    const scopeSelected=['shopId','groupId','platform'].some(key=>key in changes);
    Object.assign(filters,changes);
    // A resolved native currency belongs to its shop/group/platform, not to the next scope.
    if(scopeSelected||'displayCurrency' in changes){filters.currency='';filters.fxSnapshot='';}
    else if(['currency','start','end'].some(key=>key in changes))filters.fxSnapshot='';
    if(scopeSelected)refreshShopFilters();
    page=1;adSpendPage=1;
  }
  function selectFilters(changes){updateFilters(changes);return load();}
  function selectCurrency(currency){return selectFilters(currency?{currency}:{currency:'',displayCurrency:'CNY'});}
  let qianyiCleanup=null,qianyiSelection={};
  const qianyiView=()=>view==='orders'&&selectedKind==='REFUND';
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=String(text);if(cls)n.className=cls;parent?.append(n);return n;};
  function icon(name,parent){const n=document.createElementNS('http://www.w3.org/2000/svg','svg');n.setAttribute('viewBox','0 0 24 24');n.classList.add('fi-svg');n.setAttribute('aria-hidden','true');const path=document.createElementNS(n.namespaceURI,'path');path.setAttribute('d',paths[name]||paths.chart);n.append(path);parent?.append(n);return n;}
  function recordPlatform(row){return row.platform||data?.shops?.find(shop=>shop.id===row.shop_id)?.platform||'';}
  function platformBadge(platform){
    const key=String(platform||'').trim().toUpperCase(),shopee=key==='SHOPEE',tiktok=['TIKTOK_SHOP','TIKTOK_ADS','TIKTOK'].includes(key);
    const label=shopee?'虾皮':tiktok?'TikTok':'平台待确认',n=el('span',null,null,'fi-market-badge '+(shopee?'is-shopee':tiktok?'is-tiktok':'is-unknown'));
    n.dataset.platform=key;n.title=shopee?'虾皮（Shopee）':tiktok?'TikTok（TK）':platform||'平台信息未返回';n.setAttribute('aria-label','平台：'+n.title);
    const mark=el('span',null,n,'fi-market-mark'),svg=icon('shop',mark);svg.replaceChildren();
    function path(d,stroke,width=1.7,transform=''){const p=document.createElementNS(svg.namespaceURI,'path');p.setAttribute('d',d);p.setAttribute('stroke',stroke);p.setAttribute('stroke-width',width);p.setAttribute('fill','none');p.setAttribute('stroke-linecap','round');p.setAttribute('stroke-linejoin','round');if(transform)p.setAttribute('transform',transform);svg.append(p);}
    if(shopee){path('M5 8h14l1 13H4L5 8Zm3 0V6a4 4 0 0 1 8 0v2','currentColor');path('M15 12c-1-1-5-1-5 1s5 1 5 3-4 3-6 1','currentColor',1.5);}
    else if(tiktok){const note='M14 4v12a4 4 0 1 1-4-4M14 4c0 4 3 5 6 5';path(note,'#25f4ee',2.5,'translate(-.8 .7)');path(note,'#fe2c55',2.5,'translate(.8 -.3)');path(note,'#fff',2);}
    else path(paths.shop,'currentColor');
    el('span',label,n);return n;
  }
  function recordLead(row,value){const n=el('span',null,null,'fi-record-lead');n.append(platformBadge(recordPlatform(row)));const content=el('span',null,n,'fi-record-value');if(value instanceof Node)content.append(value);else content.textContent=value??'—';return n;}
  // record_date is a business calendar date; never shift it through the browser's timezone.
  function businessDate(value){if(value==null||value==='')return '—';const match=String(value).match(/^(\d{4}-\d{2}-\d{2})(?:$|[T ])/);return match?match[1]:'—';}
  function recordZone(row){return row.order_timezone||data?.shops?.find(shop=>shop.id===row.shop_id)?.timezone||'UTC';}
  function dateTimeCell(value,zone='UTC'){
    if(value==null||value==='')return null;
    const text=String(value);if(/^\d{4}-\d{2}-\d{2}$/.test(text))return null;
    const stamp=/^\d{10}(?:\d{3})?$/.test(text)?Number(text)*(text.length===10?1000:1):text;
    const date=new Date(stamp);if(!Number.isFinite(date.getTime()))return null;
    let parts;
    try{parts=new Intl.DateTimeFormat('en-GB',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(date);}
    catch{zone='UTC';parts=new Intl.DateTimeFormat('en-GB',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(date);}
    const p=Object.fromEntries(parts.map(part=>[part.type,part.value])),day=`${p.year}-${p.month}-${p.day}`,clock=`${p.hour}:${p.minute}:${p.second}`;
    const n=el('time',null,null,'fi-record-time');n.dateTime=date.toISOString();n.title=`${day} ${clock} · ${zone}`;
    el('span',day,n);el('small',' '+clock,n);return n;
  }
  function button(label,parent,fn,cls=''){const n=el('button',label,parent,cls);n.type='button';n.onclick=async event=>{try{await fn(event.currentTarget);}catch(e){notify(e.message,true);}};return n;}
  function action(label,symbol,parent,fn,cls=''){const n=button('',parent,fn,'fi-btn-icon '+cls);icon(symbol,n);el('span',label,n);return n;}
  const number=v=>v==null?'—':Number(v).toLocaleString('zh-CN',{maximumFractionDigits:2});
  const canManage=()=>typeof currentUser!=='undefined'&&(currentUser?.platformAdmin===true||(currentUser?.roles||[]).includes('ADMIN')||(currentUser?.permissions||[]).includes('finance:manage'));
  const allowed=()=>canManage()||(typeof currentUser!=='undefined'&&(currentUser?.permissions||[]).includes('finance:view'));
  const groupOptions=()=>[['','全部分组'],['UNGROUPED','未分组'],...(data?.groups||[]).map(g=>[g.id,g.name])];
  const inGroup=shop=>!filters.groupId||(filters.groupId==='UNGROUPED'?!shop.group_id:shop.group_id===filters.groupId);
  function groupSelect(parent){return select(parent,'店铺分组',groupOptions(),filters.groupId,v=>selectFilters({groupId:v,shopId:''}));}
  const scopedShops=()=>(data?.shops||[]).filter(s=>inGroup(s)&&(!filters.platform||s.platform===filters.platform));
  function shopFilter(parent,allLabel='全部店铺'){
    const n=select(parent,'店铺',[['',allLabel],...scopedShops().map(s=>[s.id,s.display_name||s.name])],filters.shopId,v=>selectFilters({shopId:v}));
    n.dataset.financeShopFilter=allLabel;return n;
  }
  function refreshShopFilters(){
    const shops=scopedShops();
    if(data?.shops&&filters.shopId&&!shops.some(s=>s.id===filters.shopId))filters.shopId='';
    for(const n of root.querySelectorAll('select[data-finance-shop-filter]')){
      n.replaceChildren();
      for(const [value,label]of [['',n.dataset.financeShopFilter],...shops.map(s=>[s.id,s.display_name||s.name])])el('option',label,n).value=value;
      n.value=filters.shopId;
    }
  }
  const groupName=id=>id?(data?.groups||[]).find(g=>g.id===id)?.name||'已删除分组':'未分组';
  const qs=extra=>new URLSearchParams({...filters,...extra}).toString();
  async function request(path,options={}){
    if(!allowed())throw Error('没有财务 BI 查看权限');
    return (await api('/api/v1/finance'+path,options)).data;
  }
  const json=(method,body)=>({method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  function notify(message,error=false){const n=root.querySelector('.fi-state');if(n){n.textContent=message;n.hidden=!message;n.classList.toggle('is-error',error);}}
  const financeIdentity=()=>typeof currentUser!=='undefined'&&currentUser?.organizationId&&currentUser?.userId?JSON.stringify([currentUser.organizationId,currentUser.userId]):'';
  const costExportPending=job=>['QUEUED','RUNNING'].includes(job.status);
  const costExportStatuses={QUEUED:'排队中',RUNNING:'生成中',READY:'可下载',FAILED:'生成失败'};
  function stopCostExportPolling(){clearTimeout(costExportTimer);costExportTimer=null;}
  function resetCostExports(){
    costExportEpoch++;costExportRevision=0;stopCostExportPolling();costExportOwner='';costExportJobs=[];costExportLoading=false;costExportSubmitting=false;costExportMessage='';costExportUncertain=null;costExportDetails=null;costExportSelections.clear();costExportAutoAttempted.clear();
  }
  function leaveCostExports(){costExportEpoch++;stopCostExportPolling();costExportLoading=false;costExportSubmitting=false;costExportDetails=null;}
  function costExportSelection(selection={}){
    return Object.fromEntries(['start','end','shopId','groupId','platform','currency','kind','recordId','costStatus','numbers'].map(key=>[key,selection[key]||'']));
  }
  function costExportCaption(selection={}){
    const shop=(data?.shops||[]).find(item=>item.id===selection.shopId),group=(data?.groups||[]).find(item=>item.id===selection.groupId);
    const scope=selection.shopId?(shop?.display_name||shop?.name||'所选店铺'):
      selection.groupId==='UNGROUPED'?'未分组全部店铺':selection.groupId?(group?.name||'所选分组')+'全部店铺':'全部分组、全部店铺';
    const source={SETTLED_ORDER:'已结算订单',BILL:'账单关联订单',SAMPLE_SETTLED:'样品订单',REFUND_SETTLED:'退款关联订单'}[selection.kind]||selection.kind||'订单成本';
    const status=selection.costStatus==='PENDING'?'待补齐':selection.recordId?'所选记录 '+selection.recordId:'全部成本状态';
    const numbers=selection.numbers?' · 指定订单号 '+selection.numbers:'';
    return `${selection.start||'日期待确认'} 至 ${selection.end||'日期待确认'} · ${scope} · ${selection.platform||'全部平台'} · ${selection.currency||'全部原币'} · ${source} · ${status}${numbers}`;
  }
  function costExportFileLabel(job){
    const filename=String(job.filename||''),zip=Number(job.parts)>1||/zip/i.test(job.contentType||'')||/\.zip$/i.test(filename);
    return zip?'下载 ZIP':'下载 Excel';
  }
  function scheduleCostExportPolling(){
    stopCostExportPolling();
    if(costExportDetails?.open&&costExportDetails.isConnected&&costExportJobs.some(costExportPending))
      costExportTimer=setTimeout(()=>refreshCostExports(),3000);
  }
  function renderCostExports(){
    const details=costExportDetails;if(!details?.isConnected)return;
    details.replaceChildren();
    const activeJobs=costExportJobs.filter(costExportPending).length;
    el('summary','导出任务'+(activeJobs?`（${activeJobs}）`:'')+' ▾',details,'fi-export-trigger');
    const panel=el('section',null,details,'fi-export-panel');panel.setAttribute('aria-label','订单成本导出任务');panel.setAttribute('aria-live','polite');
    const head=el('div',null,panel,'fi-export-panel-head');el('strong','订单成本导出',head);
    const close=el('button','收起',head);close.type='button';close.onclick=()=>{details.open=false;stopCostExportPolling();};
    if(costExportMessage)el('p',costExportMessage,panel,'fi-export-message');
    if(costExportUncertain){
      el('p','上次提交结果待确认：'+costExportCaption(costExportUncertain.body),panel,'fi-export-scope');
      const confirm=el('button','确认上次任务',panel);confirm.type='button';confirm.disabled=costExportSubmitting;confirm.onclick=()=>submitCostExport(costExportUncertain);
    }
    const refresh=el('button',costExportLoading?'读取中…':'刷新任务状态',panel,'fi-export-refresh');refresh.type='button';refresh.disabled=costExportLoading;refresh.onclick=()=>refreshCostExports();
    if(!costExportJobs.length)el('p',costExportLoading?'正在读取任务…':'暂无导出任务',panel,'fi-muted');
    for(const job of costExportJobs){
      const selection=costExportSelections.get(job.id)||job,row=el('article',null,panel,'fi-export-job');row.dataset.updateKey=job.id;
      el('strong',job.filename||'订单成本导出',row);el('small',costExportCaption(selection),row);
      el('span',costExportStatuses[job.status]||'状态待确认',row,'fi-export-status '+String(job.status||'').toLowerCase());
      if(costExportPending(job)){
        const processed=Number(job.processed||0),total=Number(job.total||0),progress=total?`${number(processed)} / ${number(total)}`:`已处理 ${number(processed)}`;
        el('p',progress+(job.orders?` · ${number(job.orders)} 笔订单`:''),row,'fi-export-message');
      }
      if(job.status==='FAILED')el('p',job.error||'生成失败，请重新提交',row,'fi-export-message');
      if(job.status==='READY'){
        const meta=[];if(job.orders!=null)meta.push(number(job.orders)+' 笔订单');if(job.lines!=null)meta.push(number(job.lines)+' 条 SKU');if(Number(job.parts)>1)meta.push(number(job.parts)+' 份文件');
        if(meta.length)el('p',meta.join(' · '),row,'fi-muted');
        const link=el('a',costExportFileLabel(job),row);link.href='/api/v1/finance/cost-exports/'+encodeURIComponent(job.id)+'/download';link.download=job.filename||'';
        link.onclick=event=>{
          if(!allowed()||financeIdentity()!==costExportOwner){event.preventDefault();costExportJobs=[];costExportMessage='登录信息已变化，请重新打开导出任务。';stopCostExportPolling();renderCostExports();return;}
          costExportMessage='已请求浏览器保存；若未开始，请点击下载。';queueMicrotask(renderCostExports);
        };
      }
    }
    el('small','后台生成期间可继续操作。关闭面板不会取消任务；文件保留期内可重新打开下载。',panel,'fi-export-retention');
  }
  function mergeCostExportJobs(next,{autoReady=false,autoNewReady=false}={}){
    const previous=new Map(costExportJobs.map(job=>[job.id,job.status]));costExportJobs=Array.isArray(next)?next:[];
    renderCostExports();
    if(autoReady&&financeIdentity()===costExportOwner){
      for(const job of costExportJobs){
        const oldStatus=previous.get(job.id);
        if(job.status!=='READY'||costExportAutoAttempted.has(job.id)||(!costExportPending({status:oldStatus})&&!(autoNewReady&&oldStatus===undefined)))continue;
        const link=[...(costExportDetails?.querySelectorAll('a[href]')||[])].find(node=>node.getAttribute('href')===`/api/v1/finance/cost-exports/${encodeURIComponent(job.id)}/download`);if(!link)continue;
        costExportAutoAttempted.add(job.id);link.click();
      }
    }
    scheduleCostExportPolling();
  }
  async function refreshCostExports(){
    if(!costExportOwner||costExportLoading)return;
    const identity=costExportOwner,run=costExportEpoch,revision=costExportRevision;costExportLoading=true;renderCostExports();
    try{
      const result=await request('/cost-exports');
      if(run!==costExportEpoch||financeIdentity()!==identity)return;
      if(revision!==costExportRevision){scheduleCostExportPolling();return;}
      costExportMessage='';mergeCostExportJobs(result,{autoReady:true});
    }catch(error){
      if(run!==costExportEpoch||financeIdentity()!==identity)return;
      stopCostExportPolling();costExportMessage='任务状态暂时无法读取，后台任务不会因此重建。可点刷新查看。';
    }finally{if(run===costExportEpoch){costExportLoading=false;renderCostExports();}}
  }
  async function submitCostExport(saved){
    if(costExportSubmitting)throw Error('上一个导出请求仍在确认中，请等待完成后再提交当前筛选。');
    const identity=costExportOwner,run=costExportEpoch,body=saved?.body||costExportSelection(saved),key=saved?.key||crypto.randomUUID();
    if(!identity||financeIdentity()!==identity)throw Error('登录信息已变化，请重新打开导出');
    costExportSubmitting=true;costExportMessage='正在提交后台导出任务…';costExportDetails.open=true;renderCostExports();
    try{
      const job=await request('/cost-exports',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':key},body:JSON.stringify(body)});
      if(run!==costExportEpoch||financeIdentity()!==identity)throw Error('登录信息已变化，请重新打开导出');
      costExportRevision++;costExportUncertain=null;costExportSelections.set(job.id,body);mergeCostExportJobs([job,...costExportJobs.filter(item=>item.id!==job.id)],{autoReady:true,autoNewReady:true});
      costExportMessage=job.status==='READY'?'导出已完成，已请求浏览器保存；若未开始，请点击下载。':'任务已提交，后台生成期间可继续操作。';
      renderCostExports();return job;
    }catch(error){
      if(run!==costExportEpoch||financeIdentity()!==identity)throw error;
      costExportMessage=error.message||'导出提交未确认';
      if(!error.status||Number(error.status)>=500){costExportUncertain={body,key};renderCostExports();return {status:'UNCERTAIN'};}
      renderCostExports();throw error;
    }finally{if(run===costExportEpoch){costExportSubmitting=false;renderCostExports();}}
  }
  function mountCostExports(parent){
    const identity=financeIdentity();if(identity!==costExportOwner){resetCostExports();costExportOwner=identity;}
    stopCostExportPolling();
    const details=el('details',null,parent,'fi-export-menu');details.dataset.financeCostExports='';details.dataset.updateKey='finance-cost-exports';costExportDetails=details;
    details.ontoggle=()=>{if(details.open)refreshCostExports();else stopCostExportPolling();};renderCostExports();return details;
  }
  function loadingIndicator(parent){const n=el('span','正在加载所选数据…',parent,'fi-loading');n.hidden=true;n.setAttribute('role','status');n.setAttribute('aria-live','polite');}
  function selectionKey(){return JSON.stringify([view,filters.start,filters.end,filters.shopId,filters.groupId,filters.platform,filters.currency,filters.displayCurrency,filters.granularity,page,selectedKind,orderScope,detailNumbers(),view==='ads'?[adSpendSearch,adSpendAssignment,adSpendPage]:null]);}
  function markPending(){
    const pending=!!mountedSelection&&mountedSelection!==selectionKey();
    root.classList.toggle('fi-selection-pending',pending);
    const content=root.querySelector('.fi-content');if(content){content.hidden=pending;content.setAttribute('aria-busy',String(pending));}
    if(pending)notify('正在查询所选店铺和日期，请稍候…');
    return pending;
  }
  // Previous-scope rows remain mounted for recovery, but stay hidden and cannot be exported.
  root.addEventListener('click',event=>{
    if(!root.classList.contains('fi-selection-pending'))return;
    const target=event.target instanceof Element?event.target:null;
    if(target?.closest('.fi-content')&&!target.closest('.fi-tabs,.fi-settlement-totals')){
      event.preventDefault();event.stopImmediatePropagation();notify('正在切换筛选条件，请等待新数据加载完成。');
    }else if(target?.closest('.fi-head button')&&/导出|字段/.test(target.closest('button').textContent)){
      event.preventDefault();event.stopImmediatePropagation();notify('新筛选数据加载完成后即可导出。');
    }
  },true);
  function setLoading(busy){root.setAttribute('aria-busy',String(busy));const n=root.querySelector('.fi-loading');if(n){n.hidden=!busy;n.textContent=view==='shops'?'正在读取店铺同步进度…':view==='live'?'正在读取最新经营数据…':'正在加载所选店铺和日期…';n.title=filters.start+' 至 '+filters.end;}}
  function navigate(route){location.hash='finance-'+route;}
  function cleanup(){qianyiCleanup?.();qianyiCleanup=null;mountedSelection='';root.classList.remove('fi-selection-pending');clearInterval(clockTimer);clockTimer=null;charts.forEach(c=>c.dispose());charts=[];observer?.disconnect();themeObserver?.disconnect();observer=themeObserver=null;mountedView='';}
  function reset(){shopeeEpoch++;adSupportExpanded=false;adSpendSearch='';adSpendAssignment='';adSpendPage=1;shopMonthFilter='ALL';shopSearch='';qianyiSelection={};orderScope='ALL';active=false;generation++;activeLoadKey='';clearInterval(waitingTimer);loadController?.abort();loadController=null;clearTimeout(refreshTimer);cleanup();resetMarketFx();resetCostExports();globalThis.FinanceFeeExports?.reset();globalThis.FinanceCosts?.reset();globalThis.FinanceExpensesDesign?.reset();globalThis.FinanceCharges?.reset();root.replaceChildren();data=null;page=1;try{if(filterScope)sessionStorage.removeItem(filterScope);}catch{}filterScope=null;pinnedDates=false;reportFilters=null;filters=defaultFilters();numberQueries={};document.querySelectorAll('.fi-dialog').forEach(d=>d.remove());}
  function leave(){shopeeEpoch++;document.querySelectorAll('[data-shopee-authorization]').forEach(d=>d.remove());active=false;generation++;activeLoadKey='';clearInterval(waitingTimer);loadController?.abort();loadController=null;clearTimeout(refreshTimer);cleanup();stopMarketFx();leaveCostExports();}
  async function refreshWhenIdle(){
    if(!active)return;
    if(document.hidden||document.querySelector('.fi-dialog[open]')){refreshTimer=setTimeout(refreshWhenIdle,15000);return;}
    if(data?.readModel&&!['shops','costs','expense-ledger','live','overview'].includes(view)){
      const run=generation;
      try{
        const state=await request('/workspace/status?'+new URLSearchParams({...filters,view}));
        if(run!==generation||!active)return;
        updateOperationalState(state.operations);
        const age=Date.now()-Date.parse(data.cache?.generatedAt||data.updatedAt||'');
        const fxPending=data.fx?.status==='PARTIAL'&&age>=60000;
        if(state.status==='READY'&&(state.revision!==data.readModel.revision||data.cache?.refreshing||fxPending)){load(false);return;}
      }catch(error){/* Status outages do not discard already displayed scoped data. */}
      if(run===generation&&active)refreshTimer=setTimeout(refreshWhenIdle,15000);
      return;
    }
    load(false);
  }
  function ensureCharts(){
    if(globalThis.echarts)return Promise.resolve(echarts);
    if(!chartPromise)chartPromise=new Promise(resolve=>{const s=document.createElement('script');const timer=setTimeout(()=>{chartPromise=null;resolve(null);s.remove();},12000);s.src='/vendor/echarts/echarts.min.js?v=6.1.0';s.onload=()=>{clearTimeout(timer);resolve(globalThis.echarts||null);};s.onerror=()=>{clearTimeout(timer);chartPromise=null;resolve(null);};document.head.append(s);});
    return chartPromise;
  }
  function select(parent,label,options,value,onchange){const wrap=el('label',null,parent);wrap.dataset.updateKey='select:'+label;el('span',label,wrap);const n=el('select',null,wrap);for(const [v,name] of options){el('option',name,n).value=v;}n.value=value||'';n.setAttribute('aria-label',label);n.onchange=e=>onchange(e.currentTarget.value);return n;}
  function dateInput(parent,label,value,key){const n=el('input',null,parent);n.type='date';n.setAttribute('value',value);n.setAttribute('aria-label',label);n.max=key==='end'?currentMonthEnd():today();n.onchange=e=>{const value=e.currentTarget.value;if(!validFilterDate(value)||(key==='start'&&value>today()))return;const next={...filters,[key]:value};if(next.start>next.end){notify('开始日期不能晚于结束日期',true);return;}filters[key]=value;filters.fxSnapshot='';pinnedDates=true;filters.granularity=dateGranularity(filters.start,filters.end);page=1;adSpendPage=1;load();};return n;}
  function selectMonth(month){
    if(!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)||month>today().slice(0,7))return;
    Object.assign(filters,monthRange(month));filters.fxSnapshot='';pinnedDates=true;filters.granularity='day';page=1;adSpendPage=1;return load();
  }
  function shell(root){
    root.className='workspace-body finance-workspace';
    const head=el('header',null,root,'fi-head'),copy=el('div',null,head);
    el('span','FINANCE / ANALYTICS',copy,'fi-eyebrow');el('h1',routes[view],copy,'fi-title');
    el('p',view==='shops'?'统一管理财务店铺、广告账户与数据同步。':view==='costs'?'管理商品成本、SKU 关系与订单核算口径。':view==='ads'?'按店铺与分组核对广告资金消耗，追溯每笔归属。':'看清每一笔收入、每一项费用，与每家店铺的经营表现。',copy,'fi-subtitle');
    const actions=el('div',null,head,'fi-actions');
    loadingIndicator(actions);
    if(view!=='shops')action('财务店铺管理','shop',actions,()=>navigate('shops'));
    action('刷新','refresh',actions,()=>{filters.fxSnapshot='';return load();});
    if(view==='shops'){if(canManage())action('连接店铺','plus',actions,()=>authorizeDialog(),'fi-primary');}
    else if(view==='expense-ledger'){action('成本管理','chart',actions,()=>navigate('costs'));}
    else if(view==='ads'&&window.FinanceAdSpend){if(canManage())action('导入消耗报表','plus',actions,()=>window.FinanceAdSpend.importDialog(adSpendContext()));action('导出三张表','download',actions,()=>window.FinanceAdSpend.exportReport(adSpendContext()));}
    else if(view!=='costs'&&!qianyiView()){action('字段设置','fee',actions,()=>fieldsDialog(currentFieldView()));if(canManage())action(view==='ads'?'异常补录':'导入账单','plus',actions,()=>importDialog());action('导出报表','download',actions,()=>exportDialog(['settlements','ads'].includes(view)?selectedKind:''),'fi-primary');}
    if(view!=='shops')globalThis.FinanceFeeExports?.mount(actions,{request,
      identity:()=>typeof currentUser==='undefined'?'':JSON.stringify([currentUser?.organizationId,currentUser?.userId]),
      isPending:()=>root.classList.contains('fi-selection-pending'),
      selection:{...filters},shops:data?.shops||[],groups:data?.groups||[],canCreate:['fees','fee-records'].includes(view)});
    if(['fee-records','expense-ledger'].includes(view)){
      const sections=el('nav',null,root,'fc-tabs fi-expense-sections');sections.setAttribute('aria-label','费用数据分类');
      for(const [route,label,note]of [['fee-records','平台费用','平台同步与原有导入明细'],['expense-ledger','贴单成本与刷单成本','分组贴单价、费用导入与订单标记']]){
        const tab=button('',sections,()=>navigate(route==='fee-records'?'fees':route));tab.setAttribute('aria-pressed',String(view===route));el('strong',label,tab);el('span',note,tab);
      }
    }
    if(!['shops','costs','expense-ledger'].includes(view)&&!qianyiView()){
      const bar=el('div',null,root,'fi-filters');
      groupSelect(bar);
      select(bar,'平台',[['','全部平台'],['SHOPEE','Shopee'],['TIKTOK_SHOP','TikTok Shop']],filters.platform,v=>selectFilters({platform:v,shopId:''}));
      shopFilter(bar);
      select(bar,'原币',[['',filters.displayCurrency==='CNY'?'全部币种':view==='ads'&&window.FinanceAdSpend?'全部原币（分币种展示）':'全部币种（人民币汇总）'],...(data?.currencies||[]).map(c=>[c,c])],filters.currency,selectCurrency);
      const currencyToggle=el('div',null,bar,'fi-pills');currencyToggle.setAttribute('aria-label','金额展示币种');
      for(const [key,label] of [['ORIGINAL','原币'],['CNY','人民币 ¥']])button(label,currencyToggle,()=>selectFilters({displayCurrency:key})).setAttribute('aria-pressed',String(filters.displayCurrency===key));
      if(view==='orders'&&selectedKind==='ORDER')select(bar,'订单范围',[['ALL','全部订单（下单日期）'],['SETTLED','已结算订单（结算日期）']],orderScope,value=>{orderScope=value;page=1;load();});
      if(view!=='live'){
        const monthLabel=el('label',null,bar);el('span','整月',monthLabel);
        const month=el('input',null,monthLabel);month.type='month';month.setAttribute('aria-label','统计月份（店铺当地）');month.max=today().slice(0,7);
        const full=monthRange(filters.start.slice(0,7));month.setAttribute('value',filters.start===full.start&&filters.end===full.end?filters.start.slice(0,7):'');
        month.onchange=e=>selectMonth(e.currentTarget.value);
        button('上月整月',bar,()=>selectMonth(previousMonth()));
      }
      const date=el('div',null,bar,'fi-date-range');el('span',allOrderView()?(detailNumbers()?'下单日期（编号查询不限日期）':'下单日期（店铺时区）'):view==='live'?'订单业务日':view==='ads'?'广告日期':'结算日期（店铺报表）',date);dateInput(date,'开始日期',filters.start,'start');el('span','—',date);dateInput(date,'结束日期',filters.end,'end');
      const note=el('div',view==='ads'?'账单广告费按店铺报表日期，独立广告按广告账户报表日期；同步时间按北京时间显示。':'统计日期按店铺当地时间；同步时间按北京时间显示。',bar,'fi-calendar-note');note.setAttribute('role','note');
      const selected=data?.shops?.find(shop=>shop.id===filters.shopId);
      el('span',selected?' 当前店铺：'+selected.timezone+'。':' 多店铺分别取各自当地日期，再汇总。',note);
      if(!allOrderView()&&view!=='ads')el('span',' 账单日期与付款时间分别保留；千易收货独立按北京时间。',note);
    }
    const state=el('div','正在读取财务数据…',root,'fi-state');state.setAttribute('role','status');state.setAttribute('aria-live','polite');
    return el('div',null,root,'fi-content');
  }
  function fxBasisLabel(fx){return fx?.mode==='HISTORICAL'?'业务日期历史汇率':'汇率口径待确认';}
  function stopMarketFx(){marketFxRun++;marketFxController?.abort();marketFxController=null;marketFxLoading=false;marketFxLoaded=false;}
  function resetMarketFx(){stopMarketFx();marketFx=null;marketFxError='';marketFxLoaded=false;marketFxOwner='';}
  function marketTime(value){if(/^\d{4}-\d{2}-\d{2}$/.test(String(value||'')))return value;const date=new Date(value);return value&&Number.isFinite(date.getTime())?date.toLocaleString('zh-CN',{timeZone:displayZone,hour12:false}):'未提供';}
  function renderMarketFx(parent){
    const panel=el('section',null,parent,'fi-card fi-market-fx');panel.dataset.marketFx='';panel.dataset.updateKey='market-fx';panel.setAttribute('aria-label','当前参考汇率');
    const heading=el('div',null,panel,'fi-card-head'),copy=el('div',null,heading);el('h3','当前参考汇率',copy);el('p','当前行情参考 · 日参考率，非秒级成交报价',copy);
    const refresh=button(marketFxLoading?'获取中…':'刷新行情',heading,()=>loadMarketFx(true));refresh.disabled=marketFxLoading;
    const content=el('div',null,panel,'fi-market-fx-body');
    if(marketFx){
      el('p',(marketFx.provider||'来源待确认')+' · 报价日期：'+(marketFx.rateDate||'未提供'),content,'fi-muted');
      el('p','报价更新时间：'+marketTime(marketFx.updatedAt)+' · 获取时间：'+marketTime(marketFx.fetchedAt),content,'fi-muted');
      const status=String(marketFx.status||'').toUpperCase();
      if(status==='STALE')el('p','报价已过期，以下为上次取得的参考值，请刷新后核对。',content,'fi-market-fx-warning');
      else if(['UNAVAILABLE','ERROR','MISSING'].includes(status))el('p','当前报价暂不可用；如有旧报价，仅供核对。',content,'fi-market-fx-warning');
      else if(marketFx.error)el('p','报价来源暂时异常，以下保留参考值，请核对报价日期。',content,'fi-market-fx-warning');
      const rates=marketFx.rates||{},currencies=[...new Set([...(data?.currencies||[]),...(data?.shops||[]).map(s=>s.currency),filters.currency].filter(c=>c&&c!=='CNY'))].sort();
      const list=currencies.length?currencies:Object.keys(rates).filter(c=>c!=='CNY').sort(),quotes=el('div',null,content,'fi-market-fx-quotes');
      for(const currency of list){const value=rates[currency],rate=Number(value),label=el('span',null,quotes);label.dataset.fxCurrency=currency;label.textContent=value!=null&&Number.isFinite(rate)&&rate>0?'1 '+currency+' = '+rate.toLocaleString('en-US',{maximumFractionDigits:8,useGrouping:false})+' CNY':currency+'：报价暂不可用';}
      if(!list.length)el('p','尚未取得可用外币报价。',content,'fi-muted');
    }else el('p',marketFxLoading?'正在读取当前参考汇率…':'当前报价暂不可用。',content,'fi-muted');
    if(marketFxError)el('p',marketFxError+(marketFx?'；当前保留旧报价，不能视为最新行情。':''),content,'fi-market-fx-warning');
    el('p','财务金额按订单、结算等业务日期历史汇率换算；本卡仅供当前行情参考。刷新行情不会重算历史金额。',content,'fi-market-fx-basis');
    return panel;
  }
  function paintMarketFx(){
    if(!active||view!=='overview'||!allowed())return;
    const panel=root.querySelector('[data-market-fx]');if(!panel)return;
    const holder=document.createElement('div');IncrementalDOM.patch(panel,renderMarketFx(holder));
  }
  async function loadMarketFx(force=false){
    if(!active||view!=='overview'||!allowed())return;
    const owner=financeIdentity();if(marketFxOwner!==owner){resetMarketFx();marketFxOwner=owner;}
    if(marketFxLoading||marketFxLoaded&&!force)return;
    const run=++marketFxRun;marketFxController=new AbortController();const signal=marketFxController.signal;marketFxLoading=true;marketFxError='';paintMarketFx();
    const current=()=>run===marketFxRun&&active&&view==='overview'&&owner===financeIdentity()&&!signal.aborted;
    try{const result=await request('/fx/market',{signal,cache:'no-store'});if(!current())return;marketFx=result;marketFxLoaded=true;}
    catch(e){if(!current())return;marketFxError='当前参考汇率获取失败，请稍后刷新';marketFxLoaded=true;}
    finally{if(current()){marketFxLoading=false;marketFxController=null;paintMarketFx();}}
  }
  function meta(parent){
    const row=el('div',null,parent,'fi-meta'),left=el('div',null,row);el('i',null,left,'fi-dot');el('span','当前口径 · ',left);el('span',data?.filters?.displayCurrency||data?.filters?.currency||(allOrderView()?'各店铺原币':'尚无币种数据'),left,'fi-currency');
    if(data?.fx){el('span',' · '+fxBasisLabel(data.fx)+' · ',left);el('span',data.fx.provider||'汇率来源待确认',left);if(data.fx.mode==='HISTORICAL'&&data.fx.status==='PARTIAL')el('span',' · 部分业务日期汇率待补齐',left);}
    else el('span',' · 原币独立核算',left);
    el('span',' · '+(allOrderView()&&detailNumbers()?'编号查询 · 全部下单日期':filters.start+' 至 '+filters.end)+' · '+(filters.groupId==='UNGROUPED'?'未分组':filters.groupId?groupName(filters.groupId):'全部分组'),left);
    if(data?.cache?.refreshing)el('span',' · 正在后台更新，当前数据截至 '+new Date(data.cache.generatedAt).toLocaleTimeString('zh-CN',{timeZone:displayZone}),left);
    if(data?.reportingMonthNotice){
      const periods=(data.reportingMonths||[]).map(r=>r.reporting_month+'：'+r.orders+' 笔'+(r.included?'已按月归入':'需完整月份查询'));
      const notice=el('p',periods.join('；')+'。'+data.reportingMonthNotice,parent,'fi-muted');
      notice.setAttribute('role','note');
      for(const r of data.reportingMonths||[])if(!r.included){
        button('查看 '+r.reporting_month+' 整月',notice,()=>{
          const [year,month]=r.reporting_month.split('-').map(Number);
          filters.start=r.reporting_month+'-01';filters.end=dateText(new Date(Date.UTC(year,month,0)));
          filters.granularity='month';filters.fxSnapshot='';pinnedDates=true;page=1;load();
        },'fi-record-link');
      }
    }
    const work=el('div',null,parent);work.dataset.financeSettlementWork='';drawSettlementWork(work);
  }
  function drawSettlementWork(parent){
    const job=data?.settlementCalculation;
    if(job?.status==='RUNNING'){
      const status=calculationIndicator(parent,'结算金额计算中');
      status.title=(job.scope||'历史结算')+' · 已处理 '+number(job.processed)+' / '+number(job.total)+' 条；当前筛选统计会自动更新';
      el('span','已处理 '+number(job.processed)+' / '+number(job.total)+' 条',status,'fi-calculation-detail');
    }else if(['FAILED','INTERRUPTED'].includes(job?.status))el('span','历史结算计算暂时中断，已保留数据与进度',parent,'fi-calculation-paused');
    parent.hidden=!parent.childNodes.length;
  }

  function calculationIndicator(parent,label){
    const status=el('div',null,parent,'fi-calculation-status');status.dataset.updateKey='calculation:'+label;
    status.setAttribute('role','status');status.setAttribute('aria-live','polite');
    el('span',label,status);
    const rail=el('span',null,status,'fi-calculation-rail');rail.setAttribute('role','progressbar');rail.setAttribute('aria-label',label);
    el('i',null,rail);return status;
  }
  function metricCalculation(parent,kind,amount){
    const settlement=data?.settlementCalculation?.status==='RUNNING',cost=!!data?.productCosts?.processing;
    if((['SETTLEMENT','FEE'].includes(kind)&&settlement)||(['PRODUCT_COST','OPERATING_COST'].includes(kind)&&cost)||(kind==='PROFIT'&&amount==null&&(settlement||cost))){
      calculationIndicator(parent,kind==='SETTLEMENT'?'结算金额计算中':kind==='FEE'?'费用明细更新中':['PRODUCT_COST','OPERATING_COST'].includes(kind)?(data?.productCosts?.labelProcessing&&!data?.productCosts?.productProcessing?'贴单费用更新中':'商品成本计算中'):'等待结算与成本计算');
    }else if(amount==null||(kind==='SETTLEMENT'&&data?.profit?.settledGrossAmount==null&&Number(total(kind)?.records)>0)){
      const stopped=['FAILED','INTERRUPTED'].includes(data?.settlementCalculation?.status);
      const missing=['PRODUCT_COST','OPERATING_COST','PROFIT'].includes(kind)||(total(kind)&&Number(total(kind).records)>0);
      const pending=kind==='PROFIT'?Number(data?.productCosts?.total?.pending_count||0):0;
      el('div',stopped&&['SETTLEMENT','PROFIT'].includes(kind)?'计算暂时中断':pending>0?`${number(pending)} 笔订单成本待补齐 · 点击核对`:missing?'待补齐':'暂无数据',parent,'fi-calculation-paused');
    }
  }
  function total(kind){return data?.totals?.find(r=>r.kind===kind);}
  function adHasRecords(row){return !!row&&row.hasRecords!==false&&(row.records==null||Number(row.records)>0);}
  function adAmount(row){return !adHasRecords(row)?null:row.complete===false?(Number(row.known_amounts)===0?null:row.knownAmount):row.amount;}
  function adAmountText(row){return adHasRecords(row)?number(adAmount(row))+(row.complete===false?' · 已知部分':''):'暂无记录';}
  function shopAds(id){return data?.advertising?.shops?.find(row=>row.shop_id===id);}
  function groupAds(id){return data?.advertising?.groups?.find(row=>row.group_id===id);}
  function adStoreIds(account){return [...new Set([account.shop_id,...(account.stores||[]).filter(s=>s.active&&s.match_status==='MATCHED').map(s=>s.shop_id)].filter(Boolean))];}
  function adAccountInGroup(account){
    return !filters.groupId||(!account.shop_id&&filters.groupId==='UNGROUPED')||adStoreIds(account).some(id=>inGroup(data.shops.find(s=>s.id===id)||{}));
  }
  function adAccountInScope(account){
    if(filters.platform&&filters.platform!=='TIKTOK_SHOP'||filters.currency&&filters.currency!==account.currency)return false;
    const ids=adStoreIds(account);
    if(filters.shopId&&!ids.includes(filters.shopId))return false;
    return adAccountInGroup(account);
  }
  function adOwnership(account){
    const n=el('div'),stores=(account.stores||[]).filter(s=>s.active);
    if(account.shop_id)el('div','原专属账户：'+shopName(account.shop_id),n);
    for(const store of stores){
      const label=store.match_status==='MATCHED'?shopName(store.shop_id):store.match_status==='REMOVED'?'财务店铺已移除':store.match_status==='AMBIGUOUS'?'官方 ID 对应多个店铺，待核对':'待连接对应财务店铺';
      el('div',(store.store_name||store.store_id)+' · '+label+(store.report_eligible?'':' · 采集已停用'),n);
    }
    if(!stores.length&&!account.shop_id)el('div',account.discovery_status==='ERROR'?'店铺发现异常，自动重试':account.discovery_status==='EMPTY'?'平台未返回可访问店铺':account.discovery_status?'正在自动发现店铺':'未分配店铺',n);
    if(stores.length&&!account.shop_id)el('small','普通账户总消耗保留在未归属项；GMV Max 按官方店铺匹配',n);
    return n;
  }
  function adSyncStatus(account){
    const n=el('div');n.append(account.enabled===false||account.enabled===0?el('span','已暂停',null,'fi-status'):account.status==='PARTIAL'?el('span','部分数据待补齐',null,'fi-status running'):badge(account.status));
    if(account.error_message)el('small',account.error_message,n);
    if(account.discovery_error)el('small',account.discovery_error,n);
    for(const stream of account.streams||[])el('small',(stream.channel==='BASIC'?'普通广告':'GMV Max · '+stream.store_id)+'：'+(states[stream.status]||stream.status)+(stream.error_message?' · '+stream.error_message:''),n);
    if(!account.streams&&account.gmv_store_id)el('small','GMV Max · '+(states[account.gmv_status]||account.gmv_status)+(account.gmv_error?' · '+account.gmv_error:''),n);
    return n;
  }
  function adMissingIndependent(shopId){
    if(data.adAccountError)return '同步状态暂不可用';
    const accounts=(data.adAccounts||[]).filter(a=>!shopId||adStoreIds(a).includes(shopId));
    if(!accounts.length)return '尚无可归属的接口数据';
    if(accounts.some(a=>a.discovery_status==='PENDING'))return '正在自动发现店铺';
    if(accounts.some(a=>a.discovery_status==='ERROR'||a.streams?.some(s=>s.status==='ERROR')))return '同步异常，自动重试';
    if(shopId&&accounts.every(a=>a.shop_id!==shopId))return '账户总消耗待归属；GMV Max 见右列';
    if(accounts.some(a=>a.streams?.some(s=>s.status==='SYNCING'||s.status==='READY')))return '自动补齐中';
    return '待核实覆盖范围';
  }
  function adGmvStoreLinks(shopId){
    return (data.adAccounts||[]).filter(adAccountInScope).flatMap(account=>(account.stores||[])
      .filter(store=>store.active&&store.match_status==='MATCHED'&&store.shop_id===shopId).map(store=>({account,store})));
  }
  function adScopedTikTokShops(){
    return scopedShops().filter(shop=>shop.platform==='TIKTOK_SHOP'&&(!filters.shopId||shop.id===filters.shopId)
      &&(!filters.currency||shop.currency===filters.currency||adGmvStoreLinks(shop.id).length));
  }
  function adStoreDiscoveryIssue(){
    if(data.adAccountError)return '广告店铺关联状态暂不可用';
    const accounts=(data.adAccounts||[]).filter(adAccountInScope);
    if(!accounts.length)return '当前筛选暂无已核实的店铺发现结果';
    if(accounts.some(account=>account.discovery_status==='ERROR'))return '店铺关系发现失败待重试';
    return accounts.some(account=>!['SUCCESS','EMPTY'].includes(account.discovery_status))?'店铺关系发现待完成':'';
  }
  function adMissingGmv(shopId){
    if(data.adAccountError)return '广告店铺关联状态暂不可用';
    const links=adGmvStoreLinks(shopId),discoveryIssue=adStoreDiscoveryIssue();
    if(shopId==='UNASSIGNED'&&!links.length)return '暂无可归属的 GMV Max 记录';
    if(discoveryIssue)return (links.length?'已保留广告店铺关联 · ':'')+discoveryIssue;
    if(!links.length)return '未关联广告店铺 · 未在当前发现关系范围';
    const available=links.filter(({store})=>store.report_eligible!==false);
    if(!available.length)return '已关联广告店铺 · 采集范围待核实';
    const empty=available.every(({account,store})=>account.coverage?.some(row=>row.channel!=='BASIC'&&row.store_id===store.store_id
      &&adCoverageRecordCount(row)===0&&Number(row.covered_days)>0));
    return empty?'已关联广告店铺 · 所采集日期接口未返回记录':'已关联广告店铺 · 当前范围报表待返回或补齐';
  }
  function adCoverageRecordCount(row){
    const value=row?.records;
    return value!=null&&String(value).trim()!==''&&Number.isSafeInteger(Number(value))&&Number(value)>=0?Number(value):null;
  }
  function advertisingCoverage(parent){
    const report=data?.advertising;if(!report)return;
    const count=(report.shops||[]).filter(row=>row.shop_id!=='UNASSIGNED'&&adHasRecords(row)).length;
    let note='广告数据范围：'+number(count)+' 家店铺有已入库广告费用，共 '+number(report.total.records)+' 条；未记录的店铺不代表实际消耗为零。';
    if(!adHasRecords(report.independent))note+=' 当前范围暂无独立广告报表或导入消耗，实际投放费用尚未完整核实。';
    if(scopedShops().some(shop=>shop.platform==='SHOPEE'&&(!filters.shopId||shop.id===filters.shopId)&&(!filters.currency||shop.currency===filters.currency)))
      note+=' Shopee 当前已接入结算账单中的广告费，独立投放报表尚未接入自动同步；没有记录不代表没有投放。';
    if(view==='ads'&&!data.adAccountError&&Array.isArray(data.adAccounts)&&!data.adAccounts.length)note+=' 当前公司尚未连接广告账户。';
    footnote(parent,note);
    if(view==='ads'&&Array.isArray(data.adAccounts)){
      const shops=adScopedTikTokShops();
      if(shops.length){
        const linked=shops.filter(shop=>adGmvStoreLinks(shop.id).length).length;
        const scope=el('div',null,parent,'fi-note');scope.dataset.financeAdStoreScope='';icon('info',scope);
        const discoveryIssue=adStoreDiscoveryIssue();
        el('span',`当前筛选有 ${shops.length} 家 TikTok 财务店铺，已关联 ${linked} 家，`+(discoveryIssue
          ?discoveryIssue+'；其余店铺关系尚不能确认。'
          :`${shops.length-linked} 家未在当前发现关系范围；此结论仅指已完成的店铺发现，不代表没有权限或没有投放。`)
          +'按店铺去重，多账户访问关系不重复计数；历史已入库费用继续保留。',scope);
      }
    }
  }
  function sum(kind){const row=total(kind);return ['SETTLEMENT','BILL'].includes(kind)?row&&Number(row.known_revenues)===Number(row.records)?row.revenue:null:row?.amount;}
  function operatingAmount(profit=data?.profit,known=false){
    if(!profit)return null;
    const key=known?'knownOperatingCost':'operatingCost';
    if(Object.hasOwn(profit,key))return profit[key];
    // Older cached snapshots can safely combine only components already in the same display currency.
    let product=profit.productCost;
    if(known&&profit.currency==='CNY')product=data?.productCosts?.total?.known_amount_cny??product;
    return product!=null&&profit.logisticsLabelCost!=null?Number(product)+Number(profit.logisticsLabelCost):null;
  }
  function metricCards(parent){
    const cards=el('div',null,parent,'fi-metrics fi-metrics-expanded fi-metrics-operating'),profit=data?.profit;
    const partialCost=operatingAmount()==null&&operatingAmount(profit,true)!=null;
    const settled=total('SETTLEMENT'),settledPending=Math.max(0,Number(settled?.records||0)-Number(settled?.known_revenues||0));
    const partialSettlement=profit?.settledGrossAmount==null&&settledPending>0;
    const definitions=[['SETTLEMENT','销售收入','销售收入 · 按结算账单日期','wallet','settlements'],['ORDER','已结算订单金额','原订单金额合计，按首次结算日期归属；非平台净结算，不参与利润计算。','shop','orders'],['FEE','已结算平台费用','已结算订单的佣金、物流、税费及平台调整；逐项可查','fee','fee-records'],['AD',postedAdLabel,'平台账单广告费（含广告佣金）+ 普通独立广告费用','ad','ads'],['OPERATING_COST','营业成本',partialCost?'仅已算部分 · 查看商品、贴单、样品及退货退款明细':'商品成本 + 物流贴单成本 · 查看样品及退货退款明细','chart',''],['PROFIT','利润额',profit?.amount==null?'数据待补齐 · 查看计算口径':profit.estimated?'暂估 · 查看费用完整性':'扣除平台费用、广告及营业成本','wallet','']];
    definitions.forEach(([kind,label,description,symbol,route],i)=>{
      const amount=kind==='SETTLEMENT'?profit?.settledGrossAmount??(partialSettlement&&Number(settled?.known_revenues)>0?settled.revenue:null):kind==='OPERATING_COST'?operatingAmount()??operatingAmount(profit,true):kind==='PROFIT'?profit?.amount:kind==='AD'&&data.advertising?adAmount(data.advertising.total):total(kind)?.amount;
      const n=button('',cards,()=>{if(kind==='PROFIT'){profitDialog();return;}if(kind==='OPERATING_COST'){operatingCostDialog();return;}selectedKind=kind;
        if(kind==='AD'&&data.advertising&&!adHasRecords(data.advertising.independent))
          selectedKind=adHasRecords(data.advertising.settlement)?'FEE':adHasRecords(data.advertising.gmv)?'AD_GMV':'AD';
        if(kind==='ORDER')orderScope='SETTLED';navigate(route);},'fi-metric'+(i===0?' is-featured':'')+(kind==='PROFIT'?' is-profit':''));
      n.dataset.updateKey='metric:'+kind;n.dataset.metric=kind;
      if(kind==='PROFIT'&&amount!=null&&Number(amount)<0)n.classList.add('is-negative');
      n.style.setProperty('--accent',colors[i%colors.length]);const title=el('div',null,n,'fi-metric-label');el('span',label,title);icon(symbol,el('span',null,title,'fi-metric-icon'));
      if(kind==='SETTLEMENT'&&partialSettlement)el('span','部分金额',title,'fi-partial-badge');
      if(kind==='OPERATING_COST'&&partialCost)el('span','部分金额',title,'fi-partial-badge');
      if(kind==='AD'&&data.advertising?.total?.complete===false)el('span','已知部分',title,'fi-partial-badge');
      if(kind==='OPERATING_COST'&&profit?.labelConfiguration?.missingShops?.length)el('span','贴单账单待补齐',title,'fi-partial-badge');
      const value=el('div',null,n,'fi-metric-value');el('strong',number(amount),value);el('small',data.filters.displayCurrency||data.filters.currency||'',value);
      if(kind==='SETTLEMENT'&&partialSettlement)el('div','已核算 '+number(settled.known_revenues||0)+' / '+number(settled.records)+' 笔 · 待补 '+number(settledPending)+' 笔',n,'fi-settlement-coverage');
      if(kind==='AD'&&data.advertising)el('div','其中广告佣金：'+adAmountText(data.advertising.commission),n,'fi-metric-note');
      if(kind==='AD'&&adHasRecords(data.advertising?.gmv))el('div','GMV Max 报表：'+adAmountText(data.advertising.gmv)+'（待核对，未叠加）',n,'fi-metric-note');
      metricCalculation(n,kind,amount);
      const foot=el('div',null,n,'fi-metric-note');el('span',kind==='AD'&&data.advertising?'平台账单广告费（含广告佣金）+ 普通独立广告费用':description,foot);icon('arrow',foot);
    });
  }
  async function operatingCostDialog(){
    const d=dialog('营业成本明细'),snapshot=data,selection={...filters,currency:data?.filters?.currency||filters.currency},profit=snapshot?.profit;
    const currency=profit?.currency||snapshot?.filters?.displayCurrency||snapshot?.filters?.currency||'CNY';
    const displayCost=(report,pending)=>{
      const totals=report?.total||{},field=pending?'known_display_amount':'display_amount';
      const explicit=Object.hasOwn(totals,field)||Object.hasOwn(report||{},field),reportedCurrency=report?.currency||totals.currency;
      if(explicit&&reportedCurrency!==currency)return '币种待核对';
      const value=Object.hasOwn(totals,field)?totals[field]:Object.hasOwn(report||{},field)?report[field]:currency==='CNY'?totals[pending?'known_amount_cny':'amount_cny']:null;
      if(value==null||value===''||!Number.isFinite(Number(value)))return '汇率待补齐';
      if(pending&&totals.fx_complete===false&&totals.display_amount==null&&Number(value)===0)return '汇率待补齐';
      return (currency==='CNY'?'¥ ':'')+number(value);
    };
    const closeAndCosts=kind=>{d.d.close();openCosts({...selection,kind});};
    const partial=operatingAmount(profit)==null;
    const missingLabels=profit?.labelConfiguration?.missingShops||[];
    const knownProduct=profit?.knownProductCost??(currency==='CNY'?snapshot?.productCosts?.total?.known_amount_cny:null);
    const summary=el('div',null,d.body,'fi-operating-total');el('span',missingLabels.length?'营业成本 · 贴单账单待补齐':partial?'营业成本 · 已算部分':'营业成本合计',summary);
    el('strong',number(operatingAmount(profit)??operatingAmount(profit,true))+' '+currency,summary);
    table(d.body,['成本项目','金额（'+currency+'）','明细'],[
      [profit?.productCost==null&&knownProduct!=null?'商品成本（已算部分）':'商品成本',number(profit?.productCost??knownProduct),button('查看订单成本',null,()=>closeAndCosts('SETTLED_ORDER'),'fi-cost-link')],
      ['贴单金额',missingLabels.length?(Number(profit?.logisticsLabelCost||0)===0?'待导入贴单账单':number(profit.logisticsLabelCost)+'（已匹配部分）'):number(profit?.logisticsLabelCost),button('查看贴单账单',null,()=>{
        const group=missingLabels[0]?.groupId||selection.groupId||'';
        if(group&&group!==filters.groupId)updateFilters({groupId:group,shopId:''});
        globalThis.FinanceCharges?.openLabels(group);d.d.close();navigate('expense-ledger');
      },'fi-cost-link')]
    ]);
    if(missingLabels.length){
      const groups=[...new Set(missingLabels.map(s=>groupName(s.groupId)||'未分组'))];
      el('p',`${groups.join('、')}中 ${missingLabels.length} 家店铺有订单尚未匹配贴单账单。当前金额仅包含已匹配部分；导入后自动按订单首次结算日期归属。`,d.body,'fi-record-warning');
    }
    el('h3','商品成本分类',d.body,'fi-operating-heading');
    el('p','下列金额已包含在商品成本中，仅作分类核对，不再加计。成本表原值为人民币，当前统一以 '+currency+' 展示。',d.body,'fi-muted');
    const categories=el('div',null,d.body),status=el('p','正在读取样品成本…',d.body,'fi-muted');status.setAttribute('role','status');
    el('h3','退款核对 · 数据来源：千易 ERP',d.body,'fi-operating-heading');
    const refundRows=el('div',null,d.body),refundStatus=el('p','正在读取千易退款金额…',d.body,'fi-muted');
    refundStatus.setAttribute('role','status');
    const refundSelection={...selection,currency:'',type:'SALES_RETURN',returnStatus:'FINISH',refundStatus:'',receiptScope:'RECEIVED',receiptStatus:'SIGNED',dateField:'RECEIPT',page:1};
    const refundController=new AbortController();
    d.d.addEventListener('close',()=>refundController.abort(),{once:true});
    if(currency!=='CNY')footnote(d.body,'成本表人民币原值保留；展示金额由服务端按对应业务日期历史汇率换算，缺少汇率时保留待补齐，不使用当前行情或总额单一汇率倒算。');
    footnote(d.body,'金额来自千易售后接口，按北京时间的收货日期筛选，仅统计有收货时间、退货已完成且退回已签收的退货单，原币分开显示。此金额用于核对，不冲回营业成本、不改变利润。');
    async function loadRefunds(){
      refundStatus.textContent='正在读取千易退款金额…';refundRows.replaceChildren();
      try{
        const result=await request('/qianyi/returns?'+new URLSearchParams(refundSelection),{signal:refundController.signal});
        if(!d.d.isConnected)return;
        const amounts=result.summary?.refundTotals||[];
        table(refundRows,['项目','退款金额（千易原币）','说明'],amounts.map(r=>[
          button('已收货退货单（千易）',null,()=>FinanceQianyi.openReturns({request,shops:snapshot.shops,groups:snapshot.groups,selection:refundSelection}),'fi-cost-link'),
          r.currency==='UNKNOWN'?'币种未返回，暂不合计':number(r.amount)+' '+r.currency+(Number(r.known_count)<Number(r.records)?' · 部分金额未返回':''),
          '来源：千易 ERP · 仅供核对'
        ]));
        refundStatus.textContent=Number(result.summary?.awaitingLogistics)>0?'另有 '+result.summary.awaitingLogistics+' 笔待确认返程物流，未计入当前金额。':amounts.length?'':'当前筛选没有符合收货统计条件的千易退货单。';
      }catch(e){
        if(!d.d.isConnected||refundController.signal.aborted)return;
        refundStatus.textContent='千易退款金额读取失败，请重试。';
        button('重试千易退款',refundRows,loadRefunds,'fi-record-link');
      }
    }
    async function loadCategories(){
      status.textContent='正在读取样品成本…';
      const kinds=[['SAMPLE_SETTLED','其中：商品成本—样品']];
      const results=await Promise.allSettled(kinds.map(([kind])=>request('/workspace/costs/summary?'+new URLSearchParams({...selection,kind}))));
      if(!d.d.isConnected)return;
      const next=categories.cloneNode(false);
      table(next,['分类','商品成本（'+currency+'）','订单数','明细'],results.map((result,i)=>{
        const [kind,label]=kinds[i],report=result.status==='fulfilled'?result.value:null,t=report?.total;
        const knownDisplay=Number(t?.known_display_amount),partialFx=t?.fx_complete===false&&t?.display_amount==null&&t?.known_display_amount!=null&&Number.isFinite(knownDisplay)&&knownDisplay!==0;
        const pending=Number(t?.pending_count)>0||report?.processing||partialFx;
        return [label,t?displayCost(report,pending)+(pending?' · 已算部分':''):'读取失败',t?number(t.order_count):'—',button('核对订单',null,()=>closeAndCosts(kind),'fi-cost-link')];
      }));
      IncrementalDOM.patch(categories,next);status.textContent=results.some(r=>r.status==='rejected')?'部分分类暂未读取，已保留可用结果。':'';
      if(results.some(r=>r.status==='rejected'))button('重试分类',categories,loadCategories,'fi-record-link');
    }
    await Promise.all([loadCategories(),loadRefunds()]);
  }
  function profitDialog(){
    const d=dialog('利润额计算明细'),profit=data?.profit;
    if(!profit){el('p','利润数据正在更新，请刷新后重试。',d.body);return;}
    const pending=Number(data?.productCosts?.total?.pending_count||0);
    if(pending>0){
      el('p',`当前有 ${number(pending)} 笔已结算订单的商品成本待补齐，营业成本仅含已算部分，暂不展示完整利润。`,d.body,'fi-record-warning');
      button('核对 '+number(pending)+' 笔待补齐订单',d.body,()=>{d.d.close();openCosts({kind:'SETTLED_ORDER',costStatus:'PENDING'});},'fi-record-link');
    }
    el('p','利润额 = 销售收入 − 已结算平台费用（含物流及调整、账单广告费）− 独立广告消耗 − 营业成本',d.body,'fi-profit-formula');
    table(d.body,['计算项目','金额（'+profit.currency+'）'],[
      ['销售收入',number(profit.settledGrossAmount)],
      ['减：已结算平台费用（含物流及调整）',number(profit.platformFees)],['减：独立广告消耗',number(profit.advertising)],
      [button('减：营业成本',null,()=>{d.d.close();operatingCostDialog();},'fi-cost-link'),number(operatingAmount(profit))],
      [profit.estimated?'利润额（暂估）':'利润额',number(profit.amount)]
    ]);
    for(const note of [profit.operatingCostBasis||'营业成本包含商品成本和物流贴单成本，样品及退款关联成本不重复加计。',profit.settlementBasis,profit.refundBasis,profit.marketingBasis,profit.basis,...(profit.notes||[])])if(note)footnote(d.body,note);
    if(profit.fx)footnote(d.body,'成本折算：'+fxBasisLabel(profit.fx)+' · '+(profit.fx.provider||'汇率来源待确认')+'；人民币成本表原值不变。');
  }
  function card(parent,title,subtitle,span=4){const n=el('section',null,parent,'fi-card fi-span-'+span);n.dataset.updateKey=title;const h=el('div',null,n,'fi-card-head'),copy=el('div',null,h);el('h3',title,copy);el('p',subtitle,copy);return {n,h};}
  function theme(){if(view==='live')return {text:'#573e37',muted:'#8d6a5b',grid:'#f1e4dc',surface:'#fffdfb'};const dark=document.documentElement.dataset.theme==='dark';return {text:dark?'#d9e2f5':'#25344f',muted:dark?'#8f9db7':'#8995aa',grid:dark?'#2b364b':'#eef1f7',surface:dark?'#182236':'#fff'};}
  function base(){const t=theme();return {color:view==='live'?['#ee6645','#e7b243','#ae81bf','#70a6ad','#f29b7b','#b1accd']:colors,animation:!matchMedia('(prefers-reduced-motion: reduce)').matches,animationDuration:350,animationDurationUpdate:280,animationEasingUpdate:'cubicOut',textStyle:{fontFamily:'Inter, -apple-system, PingFang SC, sans-serif',fontSize:11,color:t.text},tooltip:{trigger:'axis',renderMode:'richText',backgroundColor:t.surface,borderColor:t.grid,textStyle:{color:t.text,fontSize:11},confine:true},legend:{bottom:5,itemWidth:7,itemHeight:7,icon:'circle',textStyle:{color:t.muted,fontSize:10}},grid:{left:8,right:15,top:25,bottom:36,containLabel:true}};}
  function axis(type='value'){return {type,axisLine:{show:false},axisTick:{show:false},axisLabel:{color:theme().muted,fontSize:10,hideOverlap:true},splitLine:{show:type==='value',lineStyle:{color:theme().grid,type:'dashed'}}};}
  function plot(parent,title,builder,hasData=true){
    const node=el('div',null,parent,'fi-chart');node.dataset.updateKey='chart:'+title;
    node.setAttribute('role','img');node.setAttribute('aria-label',title);
    node.financePlot={title,builder,hasData};
  }
  function commit(next){
    if(mountedView!==view){cleanup();root.replaceChildren();mountedView=view;}
    IncrementalDOM.patch(root,next);
    mountedGeneration=generation;mountedSelection=selectionKey();markPending();
    paintCharts();
    if(view==='overview')loadMarketFx();else stopMarketFx();
  }
  function paintCharts(){
    charts=charts.filter(c=>{if(root.contains(c.financeNode))return true;c.dispose();return false;});
    for(const node of root.querySelectorAll('.fi-chart')){
      const spec=node.financePlot;if(!spec)continue;
      let c=charts.find(c=>c.financeNode===node);
      if(!spec.hasData||!globalThis.echarts){
        if(c){c.dispose();charts=charts.filter(item=>item!==c);}
        const message=spec.hasData?'图表组件暂未加载，下方明细仍可查看。':'同步后在这里查看'+spec.title;
        if(node.textContent!==message)node.textContent=message;
        node.classList.add('fi-chart-empty');continue;
      }
      node.classList.remove('fi-chart-empty');
      if(!c){node.replaceChildren();c=echarts.init(node,null,{renderer:'svg'});c.financeNode=node;charts.push(c);}
      c.financeBuilder=spec.builder;
      IncrementalCharts.update(c,{...base(),...spec.builder()});
    }
    if(!observer){observer=new ResizeObserver(()=>charts.forEach(c=>IncrementalCharts.resize(c,c.financeNode)));observer.observe(root);}
    if(!themeObserver){themeObserver=new MutationObserver(()=>charts.forEach(c=>{
      IncrementalCharts.update(c,{...base(),...c.financeBuilder()});
    }));themeObserver.observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});}
    if(view==='live'&&!clockTimer){
      const tick=()=>{const clock=root.querySelector('.fi-live-clock');if(clock)clock.textContent=new Date().toLocaleString('zh-CN',{timeZone:displayZone,hour12:false});};
      tick();clockTimer=setInterval(tick,1000);
    }
  }
  function trend(parent,keys,title,subtitle,span=8,points=data.trend){
    const {n}=card(parent,title,subtitle,span);
    plot(n,title,()=>({xAxis:{...axis('category'),data:points.map(r=>r.period),boundaryGap:false},yAxis:axis(),legend:{...base().legend,data:keys.map(([k,label])=>label)},
      series:keys.map(([key,name],i)=>({name,type:'line',data:points.map(r=>r[key]??null),smooth:false,connectNulls:!keys.some(([k])=>k.startsWith('AD_')),showSymbol:points.filter(r=>r[key]!=null).length<2,
        lineStyle:{width:2.5},areaStyle:i===0?{color:{type:'linear',x:0,y:0,x2:0,y2:1,colorStops:[{offset:0,color:colors[0]+'28'},{offset:1,color:colors[0]+'01'}]}}:undefined}))}),keys.some(([k])=>points.some(r=>r[k]!=null)));
  }
  function donut(parent,title,subtitle,items,span=4){
    const {n}=card(parent,title,subtitle,span);const nonzero=items.filter(i=>i.value>0);
    plot(n,title,()=>({tooltip:{...base().tooltip,trigger:'item'},legend:{...base().legend,type:'scroll'},series:[{type:'pie',radius:['54%','72%'],center:['50%','45%'],avoidLabelOverlap:true,itemStyle:{borderRadius:5,borderWidth:4,borderColor:theme().surface},label:{show:false},emphasis:{label:{show:true,formatter:'{b}\\n{d}%',fontSize:13,fontWeight:600}},data:nonzero}]}),nonzero.length>0);
  }
  function ranking(parent){
    const {n}=card(parent,'店铺结算排行','销售收入 · 按结算账单日期',4);
    const rows=data.ranking.filter(r=>r.kind==='SETTLEMENT'&&r.settled_gross_amount!=null).sort((a,b)=>Number(b.settled_gross_amount)-Number(a.settled_gross_amount)).slice(0,6).reverse();
    plot(n,'店铺排行',()=>({legend:{show:false},xAxis:axis(),yAxis:{...axis('category'),data:rows.map(r=>shopName(r.shop_id)),axisLabel:{...axis().axisLabel,width:82,overflow:'truncate'}},
      series:[{type:'bar',barMaxWidth:12,data:rows.map(r=>r.settled_gross_amount),itemStyle:{color:'#6a88ed',borderRadius:[0,4,4,0]}}]}),rows.length>0);
  }
  function shopName(id){if(id==='UNASSIGNED')return '未分配店铺';const s=data?.shops?.find(s=>s.id===id);return s?.display_name||s?.name||id;}
  function footnote(parent,text){const n=el('div',null,parent,'fi-note');icon('info',n);el('span',text,n);}
  function openCosts(selection={}){
    globalThis.FinanceCosts?.openOrders({...filters,currency:data?.filters?.currency||filters.currency,...selection});
    navigate('costs');
  }
  function costLink(label,selection={}){
    const n=button(label,null,()=>openCosts(selection),'fi-cost-link');
    n.title='查看对应已结算订单的商品成本';return n;
  }
  function productCostSummary(parent,report=data?.productCosts,compact=false){
    if(!report?.total)return;
    const totals=report.total,block=el('section',null,parent,'fi-card fi-product-cost-summary'+(compact?' fi-cost-quality':''));
    block.dataset.updateKey='product-cost-summary-'+(report.scope||'ORDER');
    const heading=el('div',null,block,'fi-card-head'),copy=el('div',null,heading);el('h3',compact?'已结算订单成本完整性':report.scope==='REFUND'?'退款关联已结算成本 · 仅供核对':'已结算订单商品成本 · 人民币',copy);
    el('p',report.basis,copy);button('查看已结算订单成本',heading,()=>openCosts({kind:report.scope||'SETTLED_ORDER'}),'fi-record-link');
    const stats=el('div',null,block,'fi-cost-stats'),partial=Number(totals.pending_count)>0;
    const awaiting=partial&&Number(totals.ready_count||0)+Number(totals.excluded_count||0)===0&&Number(totals.known_amount_cny||0)===0;
    for(const [index,[label,value,note]]of [
      [partial?'已结算订单已算部分':'已结算商品成本',awaiting?'待补齐':'¥ '+number(partial?totals.known_amount_cny:totals.amount_cny),awaiting?'尚无可确认成本，不能按零成本计算':partial?'仅含已核算部分，待补齐成本未计入':'实际出库优先，缺少出库凭据时按人民币标准成本'],
      ['已完成成本计算',number(totals.ready_count),Number(totals.ready_count)>0?'含 GIFT 固定零成本订单；不计成本 '+number(totals.excluded_count)+' 单':'补齐成本资料后自动计算；不计成本 '+number(totals.excluded_count)+' 单'],
      ['待补齐成本的订单',number(totals.pending_count),'缺价、缺型号或正在后台计算'],
      ['历史价估算',number(totals.estimated_count),'使用最近可用月份，明细保留原月份']
    ].entries()){if(compact&&index===0)continue;const n=el('div',null,stats);el('span',label,n);const valueNode=el('strong',null,n);if(index===0)valueNode.append(costLink(value,{kind:report.scope||'ORDER'}));else valueNode.textContent=value;el('small',note,n);}
    const work=el('div',null,block);work.dataset.financeCostWork=report.scope||'ORDER';drawCostWork(work,report);
  }
  function drawCostWork(parent,report){
    if(report?.rebuilding)footnote(parent,'成本配置已更新，后台正在分批重算；完成后自动更新统计。');
    if(report?.processing)calculationIndicator(parent,report.labelProcessing&&!report.productProcessing?'贴单费用更新中 · 商品成本已保留':'商品成本计算中 · 已有结果保留');
    parent.hidden=!parent.childNodes.length;
  }
  function updateOperationalState(operations){
    if(!data||!operations||operations.revision&&operations.revision===data.operationalRevision)return;
    if(Object.hasOwn(operations,'settlementCalculation'))data.settlementCalculation=operations.settlementCalculation;
    if(Array.isArray(operations.sync))data.sync=operations.sync;
    if(Array.isArray(operations.adAccounts))data.adAccounts=operations.adAccounts;
    if(Object.hasOwn(operations,'adAccountError'))data.adAccountError=operations.adAccountError;
    if(operations.shopeeAds){data.shopeeAds=operations.shopeeAds;if(data.adSpend)data.adSpend.shopeeAds=operations.shopeeAds;}
    const idle=operations.costState?.rebuilding===false&&operations.costState?.hasProductJobs===false;
    if(idle)for(const report of [data.productCosts,data.records?.productCosts])if(report){report.processing=false;report.productProcessing=false;report.rebuilding=false;}
    for(const node of root.querySelectorAll('[data-finance-settlement-work]')){const next=node.cloneNode(false);drawSettlementWork(next);IncrementalDOM.patch(node,next);}
    for(const node of root.querySelectorAll('[data-finance-cost-work]')){
      const report=data.records?.productCosts?.scope===node.dataset.financeCostWork?data.records.productCosts:data.productCosts;
      const next=node.cloneNode(false);drawCostWork(next,report);IncrementalDOM.patch(node,next);
    }
    for(const node of root.querySelectorAll('[data-finance-ad-health]')){const holder=document.createElement('div');IncrementalDOM.patch(node,adHealthStatus(holder));}
    if(operations.shopeeAds&&window.FinanceShopeeAds)for(const node of root.querySelectorAll('[data-shopee-ads]')){
      const holder=document.createElement('div');window.FinanceShopeeAds.render(holder,{...adSpendContext(),status:operations.shopeeAds},true);
      if(holder.firstElementChild)IncrementalDOM.patch(node,holder.firstElementChild);
    }
    data.operationalRevision=operations.revision||'';data.operationalGeneratedAt=operations.generatedAt;
    const errors=(data.sync||[]).filter(state=>state.status==='ERROR').length;
    notify(errors?`${errors} 项同步需要处理，可在财务店铺管理查看权限或连接问题。`:'');
  }
  function shopCost(id){return data?.productCosts?.shops?.find(r=>r.shop_id===id);}
  function costAmountText(row,selection){return row?costLink('¥ '+number(Number(row.pending_count)>0?row.known_amount_cny:row.amount_cny)+(Number(row.pending_count)>0?' · 已算部分':''),selection):'—';}
  states.DATE_PENDING='结算日期待确认';
  function overview(parent){
    parent.style.display='grid';parent.style.gap='15px';meta(parent);metricCards(parent);renderMarketFx(parent);advertisingCoverage(parent);
    productCostSummary(parent,data?.productCosts,true);
    if(data?.profit)footnote(parent,data.profit.formula+'。退款影响以平台结算为准，不再重复扣减确认退款；利润按已同步和已录入费用计算，未录入费用会影响最终结果。');
    if(data?.advertising)footnote(parent,data.advertising.profitBasis);
    if(data.sync.some(s=>!['ORDER_LIVE','ORDER_REPAIR'].includes(s.channel)&&Number(s.window_start)>0))
      footnote(parent,'历史回补尚未完成，所选期间的金额与笔数可能继续补齐。可在「财务店铺管理」查看各项同步进度。');
    const pendingDates=data.sync.reduce((n,s)=>n+Number(s.pending_date_bills||0),0);
    if(pendingDates)footnote(parent,pendingDates+' 张账单尚无平台确认的结算完成日期，暂不归入任一结算月份；系统会继续同步，可在财务店铺管理核对。');
    const pending=total('PENDING');if(pending)footnote(parent,'TikTok 预计待结算 '+number(pending.amount)+' '+(data.filters.displayCurrency||data.filters.currency)+' · 动态预估，已与最终结算分开统计。');
    const grid=el('div',null,parent,'fi-chart-grid');
    trend(grid,[['SETTLEMENT_GROSS','销售收入'],['ORDER','已结算订单金额']],'收入与结算趋势','按结算账单日期统计，跨月订单计入结算月份');ranking(grid);
    donut(grid,'费用构成','仅展示正向费用；退款与负向调整见明细',data.fees.map(r=>({name:feeName(r.category),value:Number(r.amount)||0})));
    const status=data.statuses.filter(r=>r.kind==='ORDER');donut(grid,'订单状态','订单数分布 · 已取消与待付款单独展示',status.map(r=>({name:statusName(r.status),value:Number(r.records)})));
    const adPoints=new Map((data.advertising?.trend||[]).map(row=>[row.period,row]));
    const adTrend=data.advertising?data.trend.map(row=>({...row,AD:adPoints.get(row.period)?.AD_TOTAL??null})):data.trend;
    trend(grid,[['AD',postedAdLabel],['COST','物流贴单成本']],'广告与物流贴单成本','广告包含平台账单广告费、广告佣金及独立广告消耗；按各自费用日期统计',4,adTrend);
    const grouped=card(grid,'分组经营明细','按店铺所属类目汇总 · 广告费用含广告佣金；GMV Max 报表单列待核对，不重复相加',12);groupSummary(grouped.n);
    const table=card(grid,'店铺经营明细','广告费用为已入账账单广告费及普通独立消耗；GMV Max 单列，不重复扣减利润',12);storeSummary(table.n);
    footnote(parent,data.basis+' 广告充值不是广告消耗，GMV Max 归因收入不是净利润。');
  }
  function storeSummary(parent){
    const ids=[...new Set([...data.ranking.map(r=>r.shop_id),...(data.productCosts?.shops||[]).map(r=>r.shop_id),...(data.advertising?.shops||[]).map(r=>r.shop_id)])];
    table(parent,['店铺','已结算订单数','订单金额','销售收入','已结算平台费用',postedAdLabel,'其中广告佣金','GMV Max 报表消耗（待核对）','营业成本 · 商品（人民币）','营业成本 · 贴单（人民币）'],ids.map(id=>{
      const rows=data.ranking.filter(r=>r.shop_id===id),get=k=>rows.find(r=>r.kind===k);
      return [shopName(id),number(get('ORDER')?.records),number(get('ORDER')?.amount),number(get('SETTLEMENT')?.settled_gross_amount),number(get('FEE')?.amount),data.advertising?adAmountText(shopAds(id)):number(get('AD')?.amount),adAmountText(shopAds(id)?.commission),adAmountText(shopAds(id)?.gmv),costAmountText(shopCost(id),{shopId:id,kind:'SETTLED_ORDER'}),'¥ '+number(shopCost(id)?.label_cost_cny||0)];
    }),'启用财务店铺同步后，经营数据将自动汇总。');
  }
  function groupSummary(parent){
    const grouped=new Map();
    for(const row of data.ranking||[]){
      const id=data.shops.find(s=>s.id===row.shop_id)?.group_id||'',key=id+':'+row.currency;
      if(!grouped.has(key))grouped.set(key,{id,currency:row.currency,kinds:{}});
      const entry=grouped.get(key),kind=entry.kinds[row.kind]||(entry.kinds[row.kind]={records:0,amount:null});
      kind.records+=Number(row.records)||0;if(row.amount!=null)kind.amount=(kind.amount??0)+Number(row.amount);
      if(row.settled_gross_amount!=null)kind.settledGross=(kind.settledGross??0)+Number(row.settled_gross_amount);
      else if(row.kind==='SETTLEMENT')kind.missingGross=true;
    }
    for(const cost of data.productCosts?.shops||[]){const shop=data.shops.find(s=>s.id===cost.shop_id),id=shop?.group_id||'',currency=data.filters?.displayCurrency||data.filters?.currency||shop?.currency||'CNY',key=id+':'+currency;if(![...grouped.values()].some(g=>g.id===id))grouped.set(key,{id,currency,kinds:{}});}
    for(const row of data.advertising?.groups||[]){const id=row.group_id||'',key=id+':'+row.currency;if(!grouped.has(key))grouped.set(key,{id,currency:row.currency,kinds:{}});}
    const groupCost=id=>{const rows=(data.productCosts?.shops||[]).filter(r=>(data.shops.find(s=>s.id===r.shop_id)?.group_id||'')===id);return rows.length?{pending_count:rows.reduce((a,r)=>a+Number(r.pending_count||0),0),known_amount_cny:rows.reduce((a,r)=>a+Number(r.known_amount_cny||0),0),amount_cny:rows.reduce((a,r)=>a+Number(r.amount_cny||0),0),label_cost_cny:rows.reduce((a,r)=>a+Number(r.label_cost_cny||0),0)}:null;};
    table(parent,['分组','币种','已结算订单数','订单金额','销售收入','已结算平台费用',postedAdLabel,'其中广告佣金','GMV Max 报表消耗（待核对）','营业成本 · 商品（人民币）','营业成本 · 贴单（人民币）'],[...grouped.values()].map(g=>[
      button(groupName(g.id),null,()=>selectFilters({groupId:g.id||'UNGROUPED',shopId:''}),'fi-action-link'),
      g.currency,number(g.kinds.ORDER?.records),number(g.kinds.ORDER?.amount),number(g.kinds.SETTLEMENT?.missingGross?null:g.kinds.SETTLEMENT?.settledGross),number(g.kinds.FEE?.amount),data.advertising?adAmountText(groupAds(g.id)):number(g.kinds.AD?.amount),adAmountText(groupAds(g.id)?.commission),adAmountText(groupAds(g.id)?.gmv),costAmountText(groupCost(g.id),{groupId:g.id||'UNGROUPED',kind:'SETTLED_ORDER'}),'¥ '+number(groupCost(g.id)?.label_cost_cny||0)
    ]),'按类目建立店铺分组后，可在这里查看分组经营表现。');
  }
  function table(parent,headers,rows,empty='当前范围暂无记录',keys=[]){
    const wrap=el('div',null,parent,'fi-table-wrap');wrap.tabIndex=0;wrap.setAttribute('aria-label','可横向滚动的数据表');
    const t=el('table',null,wrap,'fi-table'),tr=el('tr',null,el('thead',null,t));
    headers.forEach(h=>el('th',h,tr).scope='col');const body=el('tbody',null,t);
    rows.forEach((row,i)=>{const tr=el('tr',null,body);if(keys[i]!=null)tr.dataset.updateKey=String(keys[i]);row.forEach(value=>{const td=el('td',null,tr);if(value instanceof Node)td.append(value);else td.textContent=value??'—';});});
    if(!rows.length)el('p',empty,parent,'fi-empty');return t;
  }
  function badge(status){const key=String(status||'').trim().toUpperCase(),n=el('span',statusName(status),null,'fi-status');if(status)n.title=String(status);n.classList.toggle('success',['SUCCESS','SETTLED','MATCHED','ACTIVE','COMPLETED','DELIVERED','ACCEPTED','REFUNDED','RETURN_OR_REFUND_REQUEST_COMPLETE','REPLACEMENT_REQUEST_REFUND_SUCCESS'].includes(key));n.classList.toggle('error',['ERROR','DIFFERENCE','TOKEN_EXPIRED','REJECTED'].includes(key));n.classList.toggle('running',['RATE_LIMITED','RUNNING','SYNCING','READY','PARTIAL','PROCESSING','PROCESSED','AWAITING_COLLECTION','AWAITING_SHIPMENT','READY_TO_SHIP','SHIPPED','IN_TRANSIT','PARTIALLY_SHIPPING'].includes(key));return n;}
  function statusName(status){if(status==null||String(status).trim()==='')return '—';const text=String(status).trim(),key=text.toUpperCase();return key==='CONFIRMED'?'已确认':orderStates[key]||refundStates[key]||states[key]||(/[\u3400-\u9fff]/.test(text)?text:'其他状态');}
  function feeName(key){return ({packaging:'包装耗材',shipping:'外部运费',warehouse:'仓储操作',staff:'人工',rent:'租金',other:'其他费用',commission_fee:'佣金',service_fee:'服务费',seller_transaction_fee:'支付手续费',final_shipping_fee:'物流费用',reverse_shipping_fee:'逆向物流',order_ams_commission_fee:'达人佣金',campaign_fee:'活动费用',withholding_tax:'代扣税',fbs_fee:'仓储履约',seller_order_processing_fee:'订单处理费',pay_per_sale:'成交广告费'})[key]||key||'其他';}
  function recordKinds(){return view==='orders'?['ORDER','REFUND']:view==='settlements'?['SETTLEMENT','BILL','PENDING']:['FEE','COST'];}
  function recordView(parent,run,result){
    parent.style.display='grid';parent.style.gap='15px';
    const allowedKinds=recordKinds();
    if(!allowedKinds.includes(selectedKind))selectedKind=allowedKinds[0];
    const pills=el('div',null,parent,view==='settlements'?'fi-settlement-totals':'fi-tabs');
    allowedKinds.forEach(k=>{const tab=button(view==='settlements'?'':kinds[k],pills,()=>{selectedKind=k;page=1;load();});tab.setAttribute('aria-pressed',String(k===selectedKind));
      if(view==='settlements'){el('span',kinds[k]+'合计',tab);const value=el('div',null,tab);el('strong',number(sum(k)),value);el('small',data.filters.displayCurrency||data.filters.currency||'原币',value);el('small',number(total(k)?.records)+' 笔 · '+(k==='BILL'?'账单关联的销售收入；净额见对账':k==='PENDING'?'未结算预估净额，按订单日期查看':'销售收入 · 结算日期口径'),tab);}
    });
    if(qianyiView()){
      const host=el('section',null,parent);host.dataset.financeQianyi='';
      return;
    }
    meta(parent);
    const grid=el('div',null,parent,'fi-chart-grid'+(view==='settlements'?' fi-settlement-chart':''));
    if(!allOrderView())trend(grid,(view==='settlements'?[selectedKind]:allowedKinds).map(k=>[k,kinds[k]]),routes[view]+'趋势',selectedKind==='PENDING'?'尚无结算日期，按订单日期显示预估净额':'按结算账单日期及当前展示币种统计',12);
    else footnote(parent,'按店铺时区的下单日期查询全部已抓取官方订单，包含已取消、未结算及跨月结算订单。首次结算日期单列；财务总览的收入、利润和成本仍按结算口径统计。');
    if(selectedKind==='FEE'){
      const detail=card(parent,'已结算平台费用构成','正数为扣减，负数为补贴或返还；商家优惠与退款已计入销售收入',12);
      action('全体导出','download',detail.h,()=>globalThis.FinanceFeeExports?.open());
      table(detail.n,['费用项目','金额（'+(data.filters.displayCurrency||data.filters.currency)+'）','记录数'],(data.fees||[]).filter(r=>Number(r.amount)!==0).map(r=>[feeName(r.category),number(r.amount),number(r.records)]));
    }
    if(run!==generation)return;
    if(result.productCosts)data.productCosts=result.productCosts;
    if(!allOrderView())productCostSummary(parent,result.productCosts||data.productCosts);
    if(selectedKind==='REFUND')footnote(parent,'平台退款金额保留接口返回的申请/退款金额；确认退款金额仅按已完成状态计入总览。处理中的申请和已取消申请不计入确认退款。');
    const {n,h}=card(parent,selectedKind==='SETTLEMENT'?'结算明细':kinds[selectedKind]+'明细','当前筛选 · 共 '+number(result.total)+' 条',12);
    const tools=view==='settlements'?el('div',null,h,'fi-actions'):h;el('span',data.filters.currency||'原币',tools,'fi-tag');
    numberSearch(n,detailNumbers(),value=>{numberQueries[view+':'+currentFieldView()]=value;page=1;load();},allOrderView()?'订单编号':'订单 / 账单 / 退款编号');
    renderFieldsTable(n,result.items,result.fieldSettings);action('字段设置','fee',tools,()=>fieldsDialog(selectedKind,result.fieldSettings));
    if(view==='settlements')action('导出 Excel','download',tools,()=>exportDialog(selectedKind),'fi-primary');
    const bar=el('div',null,n,'fi-pagebar');el('span',`第 ${result.page} 页 · 每页 ${result.pageSize} 条`,bar);const controls=el('div',null,bar,'fi-actions');
    button('上一页',controls,()=>{page--;load();}).disabled=page<=1;
    button('下一页',controls,()=>{page++;load();}).disabled=page*result.pageSize>=result.total;
    footnote(parent,'销售收入已包含商家优惠和退款冲减；佣金、物流、税费及平台调整另列，避免重复扣减。点击订单编号可核对原始字段及实际净结算。经营汇总排除刷单；对账保留平台净额。待结算尚无结算日期，暂按下单日期查看预估净额。');
  }
  function reconciliation(parent,result){
    meta(parent);
    parent.style.display='grid';parent.style.gap='15px';
    productCostSummary(parent,result.productCosts);
    const stats=el('div',null,parent,'fi-mini-stats');
    for(const [key,label]of[['MATCHED','核对一致'],['DIFFERENCE','存在差异'],['MISSING','待补齐 / 同步中']]){const n=el('div',null,stats);el('span',label,n);el('strong',result.items.filter(r=>r.result===key||(key==='MISSING'&&r.result==='PARTIAL')).length,n);el('span','本页账单',n);}
    const {n,h}=card(parent,'平台结算账单核对','账单金额 ↔ 同账单、同币种的结算交易明细 · 共 '+number(result.total)+' 张账单',12);
    action('字段设置','fee',h,()=>fieldsDialog('RECONCILIATION',result.fieldSettings));
    numberSearch(n,detailNumbers(),value=>{numberQueries[view+':'+currentFieldView()]=value;page=1;load();});
    renderFieldsTable(n,result.items,result.fieldSettings,'暂无可核对账单。连接财务店铺同步结算数据，或导入平台结算账单。');
    const bar=el('div',null,n,'fi-pagebar');el('span',`第 ${result.page} 页 · 每页 ${result.pageSize} 条`,bar);const controls=el('div',null,bar,'fi-actions');
    button('上一页',controls,()=>{page--;load();}).disabled=page<=1;
    button('下一页',controls,()=>{page++;load();}).disabled=page*result.pageSize>=result.total;
    footnote(parent,'此处保留包含刷单在内的平台完整流水，用于真实账单核对。按账单编号与币种精确匹配。缺少数据不会标记一致；这里核对平台结算，不确认银行到账。');
  }
  const syncChannel=channel=>({ORDER_LIVE:'最新订单',ORDER:'历史订单',ORDER_REPAIR:'结算订单补齐'})[channel]||kinds[channel]||channel;
  const syncExecution=state=>state.execution_state||state.status;
  const initialIncomplete=channels=>channels.some(s=>['ORDER','SETTLEMENT'].includes(s.channel)&&(s.initial_sync_complete===false||s.initial_sync_complete==null&&!s.last_success_at));
  function shopPreviousMonth(shop,channels){
    const required=['ORDER','SETTLEMENT'].map(channel=>({channel,state:channels.find(s=>s.channel===channel)}));
    const months=[...new Set(required.map(r=>r.state?.previous_month).filter(v=>/^\d{4}-\d{2}$/.test(v||'')))];
    const month=months.length===1?months[0]:'';
    const missing=required.filter(r=>!month||r.state?.previous_month!==month||r.state?.previous_month_checked!==true||r.state?.previous_month_complete!==true);
    return {month,complete:missing.length===0,note:missing.length
      ?missing.map(r=>r.state?.previous_month_reason||syncChannel(r.channel)+'的上月明细尚未核验完成').join('；')
      :month+' · 上月订单、结算扫描已完成，未发现上月待补齐账单；抓取完成不代表已与官方原始文件逐笔对账，平台后续变动仍会自动更新'};
  }
  function filterShopRows(parent=root){
    const words=shopSearch.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
    const rows=[...parent.querySelectorAll('.fi-shops-table tbody tr')];let visible=0;
    for(const row of rows){
      const complete=row.dataset.monthComplete==='true';
      row.hidden=!(shopMonthFilter==='ALL'||(shopMonthFilter==='COMPLETE'?complete:!complete))
        ||!words.every(word=>(row.dataset.shopSearch||'').includes(word));
      if(!row.hidden)visible++;
    }
    const count=parent.querySelector('.fi-shop-results');if(count)count.textContent=`当前分组 ${rows.length} 家 · 符合条件 ${visible} 家`;
    const empty=parent.querySelector('.fi-shop-no-match');if(empty)empty.hidden=!rows.length||visible>0;
  }
  function syncStateLabel(state){
    const execution=syncExecution(state);
    return ({RUNNING:'正在抓取',QUEUED:'排队等待',WAITING_SHOP:'等待本店其他项目',API_WAIT:'等待平台请求间隔',RETRY_WAIT:'异常退避等待',RATE_LIMITED:'平台限流等待',SCHEDULED:state.status==='SYNCING'?'分页间隔等待':'等待计划时间',SUCCESS:'本轮已完成',DISABLED:'已暂停'})[execution]||statusName(state.status);
  }
  function syncWindow(state,shop){
    if(Number(state.window_start)>=946684800&&Number(state.window_end)>Number(state.window_start)&&Number(state.window_end)<4102444800){
      const day=stamp=>new Date(Number(stamp)*1000).toLocaleDateString('zh-CN',{timeZone:shop.timezone||'Asia/Shanghai'});
      return day(state.window_start)+' — '+day(Number(state.window_end)-1);
    }
    return '';
  }
  function syncDetail(parent,state,shop){
    const execution=syncExecution(state),range=syncWindow(state,shop);
    if(state.channel==='ORDER_REPAIR'&&['RUNNING','SYNCING','READY','QUEUED','WAITING_SHOP','SCHEDULED'].includes(execution))
      el('small','按结算账单补齐缺失订单，不按下单日期回扫',parent);
    else if(range)el('small','当前进度 '+range,parent);
    if(execution==='WAITING_SHOP')el('small','本店正在抓取：'+syncChannel(state.blocking_channel),parent);
    if(execution==='QUEUED'&&state.queue_position)el('small','同公司同平台待执行第 '+state.queue_position+' 项',parent);
    if(['RETRY_WAIT','RATE_LIMITED','API_WAIT'].includes(execution)){
      const retry=[state.next_at,state.platform_retry_at].filter(Boolean).map(v=>Date.parse(v)).filter(Number.isFinite);
      if(retry.length)el('small','最早继续 '+new Date(Math.max(...retry)).toLocaleString('zh-CN',{timeZone:displayZone,hour12:false}),parent);
    }
    if(state.last_progress_at)el('small','最近推进 '+new Date(state.last_progress_at).toLocaleString('zh-CN',{timeZone:displayZone,hour12:false}),parent);
    else if(state.channel==='ORDER_LIVE'&&state.last_success_at)el('small','已同步至 '+new Date(state.last_success_at).toLocaleTimeString('zh-CN',{timeZone:displayZone,hour12:false}),parent);
    if(state.error_message){
      const message=el('small',(execution==='RUNNING'?'上次问题（正在重试）：':'')+state.error_message,parent);
      message.className='fi-sync-error';
    }
    if(Number(state.pending_date_bills)>0)el('small',state.pending_date_bills+' 张账单结算日期待确认，暂未计入月度统计',parent);
  }
  function shopView(parent,res,accounts){
    data={...(data||{}),shops:res.items,sync:res.sync,groups:res.groups||[],deletions:res.deletions||[]};parent.style.display='grid';parent.style.gap='15px';
    const groupedShops=res.items.filter(inGroup),ids=new Set(groupedShops.map(s=>s.id)),groupedStates=res.sync.filter(s=>ids.has(s.shop_id));
    const byShop=new Map(groupedShops.map(s=>[s.id,groupedStates.filter(r=>r.shop_id===s.id)]));
    const months=new Map(groupedShops.map(s=>[s.id,shopPreviousMonth(s,byShop.get(s.id))]));
    const executing=groupedStates.filter(s=>syncExecution(s)==='RUNNING');
    const prioritized=groupedShops.filter(s=>byShop.get(s.id).some(r=>r.shop_priority_requested_at))
      .sort((a,b)=>Date.parse(byShop.get(a.id).find(s=>s.shop_priority_requested_at).shop_priority_requested_at)-Date.parse(byShop.get(b.id).find(s=>s.shop_priority_requested_at).shop_priority_requested_at)||a.id.localeCompare(b.id));
    const enabledCount=res.items.filter(r=>Number(r.enabled)===1||r.enabled===true).length;
    const mini=el('div',null,parent,'fi-mini-stats fi-shop-stats');
    for(const [value,label,sub]of[[res.items.length,'独立授权店铺','公司全部财务店铺'],[enabledCount,'已开启财务同步','公司全部已开启同步的店铺'],[groupedShops.filter(s=>months.get(s.id).complete).length,'上月抓取完成','当前分组 · 接口完整，不代表官方对账通过'],[executing.length,'正在抓取','当前分组 · 实际执行中的项目'],[groupedShops.filter(s=>initialIncomplete(byShop.get(s.id))).length,'历史首次补齐未完成','当前分组 · 历史订单或结算首轮未完成'],[prioritized.length,'优先同步店铺','当前分组 · 从原进度优先继续']]){const n=el('div',null,mini);el('span',label,n);el('strong',value,n);el('span',sub,n);}
    const activity=card(parent,'当前同步进度','自动刷新 · 优先请求按先后排队；平台限流时会等待，其他可执行店铺继续推进',12).n;
    const activityList=el('div',null,activity,'fi-sync-activity');
    for(const state of executing){
      const shop=groupedShops.find(s=>s.id===state.shop_id),line=el('div',null,activityList,'fi-sync-active');
      el('span','正在抓取',line,'fi-status running');el('strong',shop.display_name||shop.name,line);el('span',syncChannel(state.channel),line);
      const range=syncWindow(state,shop);if(range)el('small',range,line);
    }
    if(!executing.length)el('p','当前没有正在抓取的项目，等待调度或平台允许的请求时间。',activityList,'fi-muted');
    for(const shop of prioritized){
      const channels=byShop.get(shop.id),state=channels.find(s=>s.shop_priority_requested_at),line=el('div',null,activityList,'fi-sync-priority');
      el('span','优先第 '+state.priority_queue_position+' 家',line,'fi-status');el('strong',shop.display_name||shop.name,line);
      el('span',(shop.platform==='SHOPEE'?'Shopee':'TikTok')+' · 剩余 '+state.priority_pending_channels+' 项',line);
      el('small',channels.filter(s=>s.priority_requested_at).map(s=>syncChannel(s.channel)+'：'+syncStateLabel(s)).join('；'),line);
    }
    const {n,h}=card(parent,'财务店铺','公司共享 · 按类目分组管理财务数据',12);
    const groupTools=el('div',null,h,'fi-actions fi-group-tools');groupSelect(groupTools);if(canManage()){
      button('千易全局配置',groupTools,()=>FinanceQianyi.openCompany({request,shops:data.shops,groups:data.groups}),'fi-primary');
      button('分组管理',groupTools,()=>groupsDialog());
    }
    button('千易售后明细',groupTools,()=>FinanceQianyi.openReturns({request,shops:data.shops,groups:data.groups,selection:{groupId:filters.groupId}}));
    const filterTools=el('div',null,n,'fi-sync-filters');
    select(filterTools,'上月抓取情况',[['ALL','全部店铺'],['COMPLETE','上月抓取完成'],['INCOMPLETE','上月抓取未完成']],shopMonthFilter,value=>{shopMonthFilter=value;filterShopRows();});
    const searchLabel=el('label',null,filterTools,'fi-shop-search');searchLabel.dataset.updateKey='shop-search';el('span','查询店铺',searchLabel);
    const search=el('input',null,searchLabel);search.type='search';search.setAttribute('value',shopSearch);search.setAttribute('aria-label','查询店铺');search.placeholder='名称 / 别名 / 店铺编号';search.maxLength=120;
    search.oninput=event=>{shopSearch=event.currentTarget.value;filterShopRows();};
    button('清空',filterTools,()=>{shopSearch='';const input=root.querySelector('.fi-shop-search input');if(input)input.value='';filterShopRows();});
    el('span','',filterTools,'fi-muted fi-shop-results');
    const shopTable=table(n,['店铺 / 平台','店铺分组','国家','币种 / 时区','授权','财务同步','最近单项成功','操作'],groupedShops.map(shop=>{
      const name=el('div',null,null,'fi-platform'+(shop.platform==='SHOPEE'?' shopee':''));el('i',shop.platform==='SHOPEE'?'S':'TK',name);const c=el('div',null,name);el('span',shop.display_name||shop.name,c,'fi-shop-name');el('small',shop.external_shop_id,c);
      const month=months.get(shop.id),monthTag=el('span',(month.complete?'上月抓取完成':'上月抓取未完成')+(month.month?' · '+month.month:''),c,'fi-status fi-shop-month'+(month.complete?' success':''));
      monthTag.title=month.note;
      const currency=el('div',shop.currency||'待配置');el('small',shop.timezone,currency);
      const channels=byShop.get(shop.id);const sync=el('div',null,null,'fi-shop-sync');
      let progress=sync;
      if(channels.length){
        const rank=s=>({RUNNING:0,QUEUED:1,WAITING_SHOP:2,API_WAIT:3,RETRY_WAIT:4,RATE_LIMITED:4,SCHEDULED:s.status==='SYNCING'?5:7,SUCCESS:8,DISABLED:9})[syncExecution(s)]??6;
        const priority=channels.find(s=>s.shop_priority_requested_at),pending=channels.filter(s=>s.priority_requested_at);
        const current=channels.find(s=>syncExecution(s)==='RUNNING')||[...(pending.length?pending:channels)].sort((a,b)=>rank(a)-rank(b))[0];
        const brief=el('div',null,sync,'fi-sync-brief');
        el('span',syncStateLabel(current),brief,'fi-status '+(syncExecution(current)==='RUNNING'?'running':syncExecution(current)==='SUCCESS'?'success':''));
        el('span',syncChannel(current.channel),brief);
        if(priority)el('small','优先第 '+priority.priority_queue_position+' 家 · 剩余 '+priority.priority_pending_channels+' 项',sync,'fi-sync-brief-note');
        else if(current.queue_position)el('small','同平台待执行第 '+current.queue_position+' 项',sync,'fi-sync-brief-note');
        else if(initialIncomplete(channels))el('small','历史首次补齐尚未完成',sync,'fi-sync-brief-note');
        const details=el('details',null,sync,'fi-sync-details');details.dataset.updateKey='sync-details:'+shop.id;
        el('summary','查看 '+channels.length+' 项进度',details);
        progress=el('div',null,details);
      }
      if(channels.length)channels.sort((a,b)=>(a.channel==='ORDER_LIVE'?-1:0)-(b.channel==='ORDER_LIVE'?-1:0)).forEach(state=>{
        const line=el('div',null,progress,'fi-progress');line.dataset.updateKey=state.channel;
        const status=el('span',syncStateLabel(state),line,'fi-status '+(syncExecution(state)==='RUNNING'?'running':syncExecution(state)==='SUCCESS'?'success':''));
        if(!state.execution_state&&state.channel==='ORDER_REPAIR'&&['RUNNING','SYNCING'].includes(state.status))status.textContent='补齐中';
        el('span',syncChannel(state.channel),line);
        if(state.priority_requested_at)el('span','优先',line,'fi-sync-priority-tag');
        syncDetail(line,state,shop);
      });
      else el('span','尚未开启',sync,'fi-muted');
      const controls=el('div',null,null,'fi-shop-controls');if(canManage()){button('配置',controls,()=>shopDialog(shop));button('立即同步',controls,async b=>{b.disabled=true;try{const message=await request('/shops/'+encodeURIComponent(shop.id)+'/sync',{method:'POST'});notify(message);setTimeout(()=>{if(active)load();},1000);}finally{b.disabled=false;}}).disabled=!shop.enabled;
      button('重新授权',controls,()=>authorizeDialog(shop));button('移除',controls,()=>removeShopDialog(shop));}else el('span','只读',controls,'fi-muted');
      return [name,groupName(shop.group_id),shop.region||'—',currency,badge(shop.authorization_status),sync,shop.last_success_at?new Date(shop.last_success_at).toLocaleString('zh-CN',{timeZone:displayZone}):'尚未完成',controls];
    }),'尚未配置财务店铺。点击右上角「连接店铺」，授权成功后自动开启同步。',groupedShops.map(s=>s.id));
    shopTable.classList.add('fi-shops-table');
    [...shopTable.tBodies[0].rows].forEach((row,i)=>{
      const shop=groupedShops[i];row.dataset.monthComplete=String(months.get(shop.id).complete);
      row.dataset.shopSearch=[shop.display_name,shop.alias,shop.name,shop.external_shop_id,shop.id,shop.region,shop.platform,groupName(shop.group_id)].filter(Boolean).join(' ').toLocaleLowerCase();
    });
    el('p','当前筛选没有符合条件的店铺。',n,'fi-empty fi-shop-no-match');
    filterShopRows(parent);
    if(data.deletions.length){
      const progress=card(parent,'店铺数据删除进度','后台分批清理，中断自动续接；共享成本表和其他店铺保留',12).n;
      table(progress,['店铺 / 任务','状态','已清理记录','提交时间'],data.deletions.map(job=>[
        job.shop_label||'已删除店铺 · '+String(job.shop_id).slice(0,8),
        el('span',job.status==='COMPLETE'?'已彻底删除':job.status==='RETRY'?'正在重试，进度已保留':'正在删除',null,'fi-status '+(job.status==='COMPLETE'?'success':'running')),
        number(job.deleted_rows),new Date(job.requested_at).toLocaleString('zh-CN',{timeZone:displayZone})
      ]),'',data.deletions.map(job=>job.shop_id));
    }
    footnote(parent,'立即同步会优先推进该店铺各项任务，直到本轮完整扫描结束；重复点击不会重置进度。已发出的请求会先完成，同一店铺每次执行一项；限流和异常等待仍按平台规则恢复。首次分批补齐最近 90 天可用数据，完成后继续定时同步。');
    adAccountSettings(parent,accounts);
    if(window.FinanceShopeeAds)window.FinanceShopeeAds.render(parent,{...adSpendContext(),status:data.shopeeAds});
  }
  function adAccountSettings(parent,accounts){
    const {n,h}=card(parent,'广告账户','授权后自动发现店铺、匹配官方店铺 ID，并分别同步普通广告和 GMV Max',12);
    const actions=el('div',null,h,'fi-actions');
    if(canManage())action('复制广告授权链接','link',actions,()=>adsDialog({},true),'fi-primary');
    const displayed=accounts.filter(adAccountInGroup);
    if(displayed.length<accounts.length){
      footnote(n,'公司已连接 '+number(accounts.length)+' 个广告账户，当前分组显示 '+number(displayed.length)+' 个；未分配或其他分组的账户可切换至全部查看。');
      button('查看全部广告账户',actions,()=>selectFilters({groupId:'',shopId:''}));
    }
    table(n,['广告账户','归属','币种','状态','最近同步','操作'],displayed.map(account=>{
      const controls=el('div',null,null,'fi-actions');if(canManage()){button(account.enabled?'暂停':'启用',controls,async()=>{await request('/ads/'+account.advertiser_id,json('PUT',{enabled:!account.enabled}));load();});button('更新授权',controls,()=>adsDialog(account));}else el('span','只读',controls,'fi-muted');
      return [account.name+' · '+account.advertiser_id,adOwnership(account),account.currency,adSyncStatus(account),account.last_success_at?new Date(account.last_success_at).toLocaleString('zh-CN',{timeZone:displayZone}):'尚未完成',controls];
    }),accounts.length?'当前分组没有广告账户。':'尚未连接广告账户。复制广告授权链接，由账户管理员打开并授权，数据将在「广告分析」展示。',displayed.map(a=>a.advertiser_id));
  }
  function adSpendContext(){const epoch=shopeeEpoch;return {isActive:()=>active&&epoch===shopeeEpoch,el,card,table,button,dialog,field,select,request,json,notify,allowed,canManage,platformBadge,authorizationLink,shopeeAds:data?.adSpend?.shopeeAds,
    company:()=>typeof currentUser==='undefined'?null:currentUser?.organizationId,shops:data?.shops||[],accounts:data?.adAccounts||[],fundingAccounts:(data?.adAccounts||[]).filter(a=>adAccountInScope(a)||(data?.adSpend?.accountFunding||[]).some(r=>r.account_id===a.advertiser_id)),filters:{...filters},report:data?.adSpend,search:adSpendSearch,assignment:adSpendAssignment,reload:()=>load(),selectFilters,
    query:(search,status)=>{adSpendSearch=search.trim();adSpendAssignment=status;adSpendPage=1;return load();},page:delta=>{adSpendPage+=delta;return load();}};}
  function adsView(parent,records){
    if(window.FinanceAdSpend){
      if(data.adSpend)window.FinanceAdSpend.render(parent,adSpendContext());
      else el('p','店铺消耗读取失败，当前范围的主表金额不可用；可展开辅助核对资料。',parent,'fi-feedback is-error');
      const support=el('details',null,parent,'fi-ad-spend-support');support.dataset.adSpendSupport='';el('summary','辅助核对：广告费用（已入账）、投放效果及同步状态',support);const body=el('div',null,support);
      const run=generation,selection={...filters};let loaded=false,loading=false,supportEpoch=0,prefetched=records;
      const show=async(force=false)=>{
        adSupportExpanded=support.open;if(!support.open||!force&&(loaded||loading))return;
        const epoch=++supportEpoch;loading=true;body.textContent='正在读取广告辅助核对资料…';
        try{
          const response=!force&&prefetched?{...data,records:prefetched}:await request('/workspace/query?'+new URLSearchParams({...selection,view:'ads-support',kind:selectedKind,page,numbers:detailNumbers()}),{signal:loadController?.signal});prefetched=null;
          if(run!==generation||epoch!==supportEpoch||!active||view!=='ads')return;
          // Auxiliary coverage must not advance the primary report's revision or age.
          body.replaceChildren();const primary=data;data={...data,...response};
          try{adsSupportView(body,response.records,()=>show(true),{...selection,...response.filters,fxSnapshot:response.fx?.id||''});}finally{data=primary;}
          loaded=true;mountedSelection=selectionKey();markPending();
          ensureCharts().then(()=>{if(run===generation&&active&&view==='ads')paintCharts();});
        }catch(error){if(run===generation&&epoch===supportEpoch&&active){body.replaceChildren();el('p','辅助核对资料读取失败，可收起后重新展开。',body,'fi-feedback is-error');loaded=false;}}
        finally{if(epoch===supportEpoch)loading=false;}
      };
      support.addEventListener('toggle',()=>show());if(adSupportExpanded){support.open=true;show();}
    }else{if(data.adSpendError)el('p','店铺消耗读取失败，不能展示当前范围的主表金额。下方仅保留已有账单及同步资料，可刷新重试。',parent,'fi-feedback is-error');adsSupportView(parent,records);}
  }
  function adsSupportView(parent,records,reload=()=>load(),selectionOverride=null){
    parent.style.display='grid';parent.style.gap='15px';meta(parent);
    const report=data.advertising,currency=data.filters.displayCurrency||data.filters.currency||'';
    if(!report)footnote(parent,'广告分类统计待更新；下方暂显示独立广告报表，不能视为全部广告费用。');
    advertisingCoverage(parent);
    const ledger=el('section',null,parent,'fi-ad-ledger-check');ledger.dataset.adLedgerCheck='';
    el('h3',postedAdLabel+'核对',ledger);
    el('p','平台账单广告费（含广告佣金）+ 普通独立广告费用；与上方投放报表消耗范围不同，不相加。',ledger,'fi-muted');
    const ledgerRows=[];
    for(const [key,label,note] of [['total',postedAdLabel,'平台账单广告费（含广告佣金）+ 普通独立广告费用'],['settlement','平台账单广告费','含广告佣金，已包含在平台净结算中'],['independent','普通独立广告费用','普通广告账户报表及历史导入费用'],['unassigned','未分配店铺费用','已包含在已入账金额中，不额外加计']]){
      const item=report?.[key]||(key==='independent'?total('AD'):null),value=el('strong',number(adAmount(item))),info=el('span',note);
      if(item?.complete===false)el('small',' · 已知部分',info);
      if(!adHasRecords(item))el('small',' · '+(key==='independent'&&data.adAccounts?.some(a=>a.streams)?adMissingIndependent(filters.shopId):'暂无记录'),info);
      if(key==='settlement'&&report)el('small',' · 其中广告佣金：'+adAmountText(report.commission)+' '+currency,info);
      ledgerRows.push([label,value,currency,info]);
    }
    const ledgerTable=table(ledger,['项目','金额','币种','说明'],ledgerRows);
    [...ledgerTable.tBodies[0].rows].forEach((row,i)=>{row.dataset.adMetric=['total','settlement','independent','unassigned'][i];});
    if(report&&!report.total.hasRecords)footnote(parent,'当前筛选暂无已同步或导入的广告费用。系统会自动发现店铺、同步和重试；请查看下方覆盖范围。仅在接口确实无法提供数据时使用导入。');
    const grid=el('div',null,parent,'fi-chart-grid');
    trend(grid,report?[['AD_TOTAL',postedAdLabel],['AD_SETTLEMENT','平台账单广告费'],['AD_INDEPENDENT','独立广告消耗']]:[['AD','独立广告消耗']],
      postedAdLabel+'趋势','按各自费用日期统计 · 负向返还保留 · 无记录日期不补造金额',8,report?.trend||data.trend);
    const t=report?.performance||total('AD'),stats=card(grid,'投放概览','曝光、点击及转化仅取独立广告报表；缺失指标不补零',4);
    const coverage=(field,known,row=t)=>number(row?.[field])+(row&&Number(row[known]||0)<Number(row.records||0)&&row[field]!=null?' · 已知部分':'');
    const percentage=value=>value==null?'—':number(value)+'%';
    table(stats.n,['指标','数值'],[['独立广告消耗',adAmountText(t)],['曝光量',coverage('impressions','known_impressions')],['点击量',coverage('clicks','known_clicks')],
      ['转化量',coverage('conversions','known_conversions')],['点击率',percentage(t?.clickThroughRate)],['平均点击成本',number(t?.costPerClick)],
      ['千次曝光成本',number(t?.costPerThousand)],['平均转化成本',number(t?.costPerConversion)],['点击转化率',percentage(t?.conversionRate)]]);
    if(report){
      const categories=card(grid,'广告费用构成','充值和无法单独识别的混合字段不作为已确认广告消耗',12);
      table(categories.n,['来源','费用类别','金额（'+currency+'）','费用记录数'],report.categories.map(row=>[
        row.sourceType==='SETTLEMENT'?'平台结算账单':'独立投放',row.category,adAmountText(row),number(row.records)]));
      const grouped=card(grid,'分组广告费用（已入账）','按店铺所属类目汇总，未归属费用单列',12);
      table(grouped.n,['分组 / 类别','平台账单广告费','其中广告佣金','普通独立广告消耗','GMV Max 报表消耗（待核对）',postedAdLabel+'（'+currency+'）'],report.groups.map(row=>[
        button(groupName(row.group_id),null,()=>selectFilters({groupId:row.group_id||'UNGROUPED',shopId:''}),'fi-action-link'),
        adAmountText(row.settlement),adAmountText(row.commission),adAmountText(row.independent),adAmountText(row.gmv),adAmountText(row)]));
      const stores=card(grid,'店铺广告费用（已入账）','GMV Max 自动按官方店铺归属；与账单重叠待核对，暂不叠加到入账合计。普通账户总额不按访问店铺分摊。',12);
      const storeRows=[...report.shops];
      for(const shop of adScopedTikTokShops())
        if(!storeRows.some(row=>row.shop_id===shop.id))storeRows.push({shop_id:shop.id,group_id:shop.group_id});
      table(stores.n,['店铺','分组 / 类别','平台账单广告费','其中广告佣金','普通独立广告消耗','GMV Max 报表消耗（待核对）',postedAdLabel+'（'+currency+'）'],storeRows.map(row=>[
        row.shop_id==='UNASSIGNED'?shopName(row.shop_id):button(shopName(row.shop_id),null,()=>selectFilters({shopId:row.shop_id}),'fi-action-link'),
        groupName(row.group_id),adAmountText(row.settlement),adAmountText(row.commission),adHasRecords(row.independent)?adAmountText(row.independent):adMissingIndependent(row.shop_id),adHasRecords(row.gmv)?adAmountText(row.gmv):adMissingGmv(row.shop_id),adAmountText(row)]));
    }
    const gmv=report?.gmv||total('AD_GMV'),gmvCard=card(parent,'GMV Max 专项分析','自动获取商品及直播计划（含已删除计划）；与普通报表、账单的覆盖差异待核对，不直接相加。归因收入不是结算收入。',12);
    table(gmvCard.n,['专项消耗（'+currency+'）','归因收入','SKU 订单数','归因 ROI'],[[adAmountText(gmv),coverage('revenue','known_revenues',gmv),coverage('conversions','known_conversions',gmv),number(gmv?.roi)]]);
    adHealthStatus(parent);
    const detailKinds=el('div',null,parent,'fi-tabs');if(!['AD','FEE','AD_GMV'].includes(selectedKind))selectedKind='AD';
    const detailTitle=kind=>kind==='FEE'?'平台账单广告费明细':kind==='AD'?'普通独立广告消耗明细':kinds[kind]+'明细';
    for(const k of ['AD','FEE','AD_GMV'])button(detailTitle(k),detailKinds,()=>{selectedKind=k;page=1;reload();}).setAttribute('aria-pressed',String(k===selectedKind));
    const detail=card(parent,detailTitle(selectedKind),'当前筛选 · 共 '+number(records.total)+' 条 · 编号查询仅过滤明细，期间汇总不受分页影响',12);
    if(selectedKind==='AD')el('p','这里仅列普通独立广告报表和导入费用；平台账单广告费与 GMV Max 报表在相邻页签查看。没有返回记录不代表没有广告投放。',detail.n,'fi-muted');
    action('字段设置','fee',detail.h,()=>fieldsDialog(selectedKind,records.fieldSettings));action('导出 Excel','download',detail.h,()=>exportDialog(selectedKind,selectionOverride));
    numberSearch(detail.n,detailNumbers(),value=>{numberQueries[view+':'+currentFieldView()]=value;page=1;reload();},'费用记录 / 订单 / 账单编号');
    renderFieldsTable(detail.n,records.items,records.fieldSettings);
    const pager=el('div',null,detail.n,'fi-pagebar');el('span',`第 ${records.page} 页 · 每页 ${records.pageSize} 条`,pager);const controls=el('div',null,pager,'fi-actions');
    button('上一页',controls,()=>{page--;reload();}).disabled=page<=1;button('下一页',controls,()=>{page++;reload();}).disabled=page*records.pageSize>=records.total;
    footnote(parent,report?.basis||'广告归因收入与平台结算收入口径不同，不作为净利润。');
    if(report){footnote(parent,report.profitBasis);footnote(parent,report.coverageNote);}
  }
  function adHealthStatus(parent){
    const accounts=(data.adAccounts||[]).filter(adAccountInScope);
    const health=card(parent,'广告数据同步','账户维护在财务店铺管理完成；这里展示同步状态和可用范围',12);health.n.dataset.financeAdHealth='';
    if(data.adAccountError)footnote(health.n,'广告账户状态暂时读取失败；费用统计和明细仍展示已入库数据，可点击刷新重试。');
    table(health.n,['广告账户','归属店铺','报表币种 / 时区','状态','最近同步'],accounts.map(account=>{
      return [account.name||account.advertiser_id,adOwnership(account),account.currency+' / '+(account.timezone||'时区待确认'),adSyncStatus(account),
        dateTimeCell(account.last_success_at,displayZone)||'尚未完成'];
    }),'当前范围没有已连接广告账户；平台账单广告费和已导入费用仍按实际记录统计。');
    const coverageRows=[];
    for(const account of accounts)for(const stream of account.streams||[]){
      const store=(account.stores||[]).find(s=>s.store_id===stream.store_id);
      if(stream.channel!=='BASIC'&&(!store?.active||store.match_status==='REMOVED'||filters.shopId&&store.shop_id!==filters.shopId||filters.groupId&&store.shop_id&&!inGroup(data.shops.find(s=>s.id===store.shop_id)||{})))continue;
      const coverage=(account.coverage||[]).find(r=>r.channel===stream.channel&&r.store_id===stream.store_id),days=Number(coverage?.covered_days||0),expected=Number(account.requestedDays||0);
      const label=stream.channel==='BASIC'?'普通广告（账户报表；不含部分 Shop 创建的 GMV Max）':'GMV Max · '+(store?.shop_id?shopName(store.shop_id):store?.store_name||stream.store_id);
      const records=adCoverageRecordCount(coverage),complete=days===expected&&expected>0;
      const result=!coverage?'尚未取得范围内报表':records==null?'返回记录数未核实':records===0?'接口未返回记录'
        :coverage.amount!=null&&Number(coverage.amount)===0?'报表合计为0':'已返回报表记录';
      const state=complete?result:days?'已采集部分 · '+result:result;
      const amount=coverage?.amount,hasAmount=amount!=null&&String(amount).trim()!==''&&Number.isFinite(Number(amount));
      const amountText=records!==0&&hasAmount&&(records>0||Number(amount)!==0)?number(amount)+' '+account.currency:'—';
      coverageRows.push([account.name||account.advertiser_id,label,(states[stream.status]||stream.status)+(stream.error_message?' · '+stream.error_message:''),expected?days+' / '+expected+' 天':'未选择范围',state+(coverage&&stream.status==='ERROR'?'（上次成功数据）':''),
        number(records),amountText,dateTimeCell(coverage?.oldest_observation,displayZone)||'—']);
    }
    if(coverageRows.length){table(health.n,['广告账户','报表范围','任务状态','日期覆盖','数据状态','返回记录数','已采集消耗（原币）','范围内最早采集时间'],coverageRows);footnote(health.n,'覆盖仅指接口请求成功覆盖对应日期；返回记录数来源于接口返回的报表行，按所选日期合计。没有返回记录不代表实际零消耗；报表合计为0也不代表每条记录都为0。普通账户报表不保证包含部分 Shop 创建的 GMV Max，不能当作全媒体消耗。覆盖不等于结算对账完成。当天及近期数据可能延迟，系统每小时回查近 7 天、每日回查近 31 天，首次补齐近 90 天。');}
    return health.n;
  }

  function dialog(title){
    const d=el('dialog',null,document.body,'fi-dialog');const head=el('div',null,d,'fi-dialog-head');el('h3',title,head);button('×',head,()=>d.close(),'fi-close').setAttribute('aria-label','关闭');
    const body=el('div',null,d,'fi-dialog-body'),feedback=el('div','',body,'fi-feedback');feedback.setAttribute('role','status');
    const foot=el('div',null,d,'fi-dialog-foot');button('取消',foot,()=>d.close());
    d.addEventListener('close',()=>d.remove());d.showModal();
    return {d,body,foot,feedback,submit(label,fn){return button(label,foot,async b=>{b.disabled=true;feedback.textContent='';try{await fn();}catch(e){feedback.textContent=e.message;}finally{b.disabled=false;}},'fi-primary');}};
  }
  function field(parent,label,type='text',value=''){const wrap=el('label',null,parent);el('span',label,wrap);const n=el('input',null,wrap);n.type=type;n.value=value;return n;}
  function shopDialog(shop){
    const d=dialog('配置财务店铺');el('p','平台原名：'+shop.name,d.body,'fi-muted');
    const group=select(d.body,'店铺分组',[['','未分组'],...(data?.groups||[]).map(g=>[g.id,g.name])],shop.group_id||'',()=>{});
    const currency=field(d.body,'店铺结算币种（如 PHP、THB）','text',shop.currency||'');
    const zone=field(d.body,'店铺业务时区','text',shop.timezone||'Asia/Shanghai');
    const enabled=select(d.body,'自动同步',[['true','开启'],['false','暂停']],String(!!shop.enabled),()=>{});
    const alias=field(d.body,'店铺别名','text',shop.display_name||'');alias.maxLength=100;alias.placeholder='设置后所有成员优先看到别名，留空使用平台原名';alias.setAttribute('aria-label','店铺别名');
    d.submit('保存配置',async()=>{await request('/shops/'+shop.id,json('PUT',{groupId:group.value,currency:currency.value.trim().toUpperCase(),timezone:zone.value.trim(),enabled:enabled.value==='true',alias:alias.value.trim()}));d.d.close();load();});
  }
  async function groupsDialog(){
    const d=dialog('店铺分组管理');el('p','按类目自定义分组，同公司有财务权限的成员共享使用。已有店铺可在「配置」中调整归属。',d.body,'fi-muted');
    const create=el('div',null,d.body,'fi-group-create'),name=field(create,'新分组名称');name.maxLength=80;
    const list=el('div',null,d.body,'fi-group-list');
    async function render(){const groups=await request('/groups');if(!d.d.isConnected)return;data={...(data||{}),groups};list.replaceChildren();
      if(!groups.length)el('p','还没有分组，请先创建一个类目分组。',list,'fi-muted');
      for(const g of groups){const row=el('div',null,list,'fi-group-row');const input=field(row,'分组名称','text',g.name);input.maxLength=80;
        el('span',g.shop_count+' 家店铺',row,'fi-muted');button('保存',row,async b=>{b.disabled=true;try{await request('/groups/'+g.id,json('PUT',{name:input.value.trim()}));await render();d.feedback.textContent='分组已保存';}catch(e){d.feedback.textContent=e.message;}finally{b.disabled=false;}});
        button('删除',row,async b=>{b.disabled=true;try{await request('/groups/'+g.id,{method:'DELETE'});if(filters.groupId===g.id)updateFilters({groupId:'',shopId:''});await render();d.feedback.textContent='分组已删除';}catch(e){d.feedback.textContent=e.message;}finally{b.disabled=false;}}).disabled=Number(g.shop_count)>0;
      }
    }
    button('添加分组',create,async b=>{b.disabled=true;try{await request('/groups',json('POST',{name:name.value.trim()}));name.value='';await render();d.feedback.textContent='分组已添加';}catch(e){d.feedback.textContent=e.message;}finally{b.disabled=false;}});
    d.d.addEventListener('close',()=>{if(active)load();});try{await render();}catch(e){d.feedback.textContent=e.message;}
  }
  async function authorizeDialog(shop){
    const d=dialog(shop?'重新授权财务店铺':'连接财务店铺');let groups;
    try{groups=await request('/groups');}catch(e){d.feedback.textContent=e.message;return;}
    if(!d.d.isConnected)return;
    if(!groups.length){el('p','授权前请先创建店铺分组。具体类目名称由你配置。',d.body,'fi-muted');d.submit('创建分组',()=>{d.d.close();return groupsDialog();});return;}
    const invalidate=()=>d.body.querySelector('.fi-authorization-link')?.remove();
    const platform=select(d.body,'平台',[['SHOPEE','Shopee'],['TIKTOK_SHOP','TikTok Shop']],shop?.platform||'SHOPEE',invalidate);platform.disabled=!!shop;
    const market=select(d.body,'授权市场',[['ROW','东南亚 / 全球（非美国）'],['US','美国']],'ROW',invalidate);
    const group=select(d.body,'店铺分组（必选）',[['','请选择分组'],...groups.map(g=>[g.id,g.name])],'',invalidate);group.required=true;
    el('p',shop?'重新授权后自动开启同步，并使用本次所选分组，保留历史记录与同步进度。':'授权成功后自动开启同步，新店铺归入所选分组；同公司已存在的店铺会提示「店铺已存在」，保留原分组和数据，不重复导入。',d.body,'fi-muted');
    d.submit('生成授权链接',async()=>{if(!group.value)throw Error('请选择店铺分组');const selected={platform:platform.value,market:market.value,groupId:group.value,shopId:shop?.id||''};const result=await request('/shops/authorize',json('POST',selected));
      if(!d.d.isConnected||selected.platform!==platform.value||selected.market!==market.value||selected.groupId!==group.value)return;authorizationLink(d,result);});
  }
  function authorizationLink(d,result){
    const url=new URL(result.authorizationUrl);if(url.protocol!=='https:')throw Error('平台授权地址无效');
    d.body.querySelector('.fi-authorization-link')?.remove();
    const wrap=el('div',null,d.body,'fi-authorization-link');
    const input=field(wrap,'官方授权链接','text',url.href);input.readOnly=true;
    button('复制授权链接',wrap,async b=>{await copyValue(input);b.textContent='已复制链接';});
    el('p','将链接发给账户管理员，在浏览器打开并完成授权。授权成功后会回到财务店铺管理。'+(result.expiresAt?' 有效至 '+new Date(result.expiresAt).toLocaleString('zh-CN',{timeZone:displayZone})+'。':''),wrap,'fi-muted');
    const link=el('a','在当前设备打开授权',wrap,'fi-action-link');link.href=url.href;link.target='_blank';link.rel='noopener noreferrer';
  }
  async function copyValue(input){
    try{await navigator.clipboard.writeText(input.value);}
    catch{input.focus();input.select();if(!document.execCommand('copy'))throw Error('请选中链接后手动复制');}
  }
  async function adsDialog(account={},copyImmediately=true){
    const d=dialog(account.advertiser_id?'更新广告授权':'广告账户授权');
    d.feedback.textContent='正在准备授权链接…';
    let config;try{config=await request('/ads/application');}catch(e){d.feedback.textContent=e.message;return;}
    if(!d.d.isConnected)return;
    if(!config.configured){
      d.feedback.textContent='广告授权暂未准备好，请联系管理员完成服务端配置。';return;
    }
    d.feedback.textContent='';
    el('p','账户管理员完成官方授权后，系统自动发现店铺、按官方店铺 ID 匹配，并补齐近 90 天普通广告和 GMV Max 报表。无需填写店铺 ID 或导入文件。',d.body,'fi-muted');
    const options=el('details',null,d.body,'fi-ad-options');options.open=!!account.advertiser_id;
    el('summary','指定授权账户（可选）',options);
    const invalidate=()=>{d.body.querySelector('.fi-authorization-link')?.remove();d.feedback.textContent='选项已变更，请重新生成授权链接';};
    const id=field(options,'广告账户 ID（留空连接本次授权的账户）','text',account.advertiser_id||'');id.disabled=!!account.advertiser_id;
    const shop={value:account.shop_id||''},gmvStore={value:account.gmv_store_id||''};
    id.addEventListener('input',invalidate);
    el('p','店铺归属由系统识别；只看到店铺访问权限时，不将全部账户消耗强行分摊。',options,'fi-muted');
    let preparing=false;
    async function generate(){
      if(preparing)return;preparing=true;d.feedback.textContent='正在生成授权链接…';
      const selected={advertiserId:id.value.trim(),shopId:shop.value,gmvStoreId:gmvStore.value.trim()};
      try{
        const result=await request('/ads/authorize',json('POST',selected));
        if(!d.d.isConnected||selected.advertiserId!==id.value.trim()||selected.shopId!==shop.value||selected.gmvStoreId!==gmvStore.value.trim())return;
        authorizationLink(d,result);
        try{await copyValue(d.body.querySelector('.fi-authorization-link input'));d.feedback.textContent='授权链接已复制，发送给账户管理员即可';}
        catch{d.feedback.textContent='链接已生成，请点击「复制授权链接」或手动复制';}
      }finally{preparing=false;}
    }
    d.submit('重新生成并复制',generate);
    if(copyImmediately)try{await generate();}catch(e){d.feedback.textContent=e.message;}
  }
  function currentFieldView(){return view==='reconciliation'?'RECONCILIATION':view==='overview'?'ALL':view==='ads'?(['AD','FEE','AD_GMV'].includes(selectedKind)?selectedKind:'AD'):selectedKind;}
  function skuCell(row){
    const items=row.product_sku_details||[];
    if(items.some(item=>!item.sellerSku)){
      const n=el('div',null,null,'fi-sku-details');
      for(const item of items){
        const part=el('div',null,n,'fi-sku-item'),photo=el('span','无商品图',part,'fi-sku-photo');
        if(/^https:\/\/[^/]+\/.+/.test(item.image||'')){
          photo.textContent='';const image=el('img',null,photo);image.src=item.image;image.alt=item.name||'订单商品图';image.setAttribute('loading','lazy');image.setAttribute('decoding','async');image.referrerPolicy='no-referrer';
          image.onerror=e=>{e.currentTarget.parentElement.textContent='商品图未加载';};
        }
        const copy=el('div',null,part,'fi-sku-copy');
        if(item.name){const name=el('strong',item.name,copy);name.title=item.name;}
        el('span','订单规格：'+(item.spec||'平台未提供'),copy,'fi-sku-spec');
        if(item.quantity!=null)el('span','购买数量：'+item.quantity,copy);
        if(item.sellerSku)el('span','商家 SKU：'+item.sellerSku,copy);
        else el('span','商家 SKU 未提供',copy,'fi-sku-missing');
        if(item.skuId)el('span','平台 SKU：'+item.skuId,copy,'fi-sku-id');
        if(item.productId)el('small','SPU：'+item.productId,copy);
        if(globalThis.FinanceCosts?.openMapping&&row.shop_id&&item.productId&&item.skuId)
          button('核对关联',copy,()=>{copy.closest('dialog')?.close();FinanceCosts.openMapping({shopId:row.shop_id,productId:item.productId,skuId:item.skuId,returnToOrders:view==='costs'});if(view==='costs')load();else navigate('costs');},'fi-record-link');
      }
      return n;
    }
    const codes=row.product_sku_values||String(row.product_skus||'').split('\n').filter(Boolean);
    const n=el('span',codes.join('\n')||(row.item_names?row.item_names+'\n商家 SKU 未提供':'暂无商品 SKU 信息'),null,'fi-sku-cell');
    n.title=codes.length?'关联订单的商家 SKU'+(codes.length>1?'（'+codes.length+' 个）':'')+'\n'+codes.join('\n'):'暂无关联订单的商家 SKU';
    return n;
  }
  function presentationSettings(settings,key=settings.view||currentFieldView()){
    const orderView=['ORDER','REFUND','SETTLEMENT','PENDING','FEE','COST'].includes(key)&&settings.fields.some(f=>f.id==='order_id');
    const visible=[...new Set(settings.visible.map(id=>orderView&&id==='external_id'?'order_id':id))];
    const fields=settings.fields.map(f=>{
      if(f.id==='record_date'&&['ORDER','REFUND'].includes(key))return {...f,label:key==='ORDER'&&allOrderView()?'下单日期（店铺时区）':'首次平台结算日期',recordTime:false};
      if(f.id==='order_created_time'&&['BILL','RECONCILIATION'].includes(key))return {...f,label:'关联订单下单时间范围'};
      if(f.id==='external_id'&&['BILL','RECONCILIATION'].includes(key))return {...f,label:'账单编号'};
      return f;
    });
    if(key==='ORDER'&&allOrderView()&&fields.some(f=>f.id==='first_settlement_date')&&!visible.includes('first_settlement_date'))visible.push('first_settlement_date');
    return {...settings,fields,visible,orderView,defaults:settings.defaults?{...settings.defaults,visible:[...new Set(settings.defaults.visible.map(id=>orderView&&id==='external_id'?'order_id':id))]}:settings.defaults};
  }
  function columnValue(row,f){
    const value=f.id==='fx_time'?(row.fx?.rateDate||row.fx?.time):f.id==='fx_provider'?row.fx?.provider:row[f.id];
    if(f.id==='first_settlement_date'&&row.order_date_basis==='CREATED'&&!value)return '暂无结算';
    if(['record_date','first_settlement_date'].includes(f.id)&&row.reporting_month&&!(f.id==='record_date'&&row.order_date_basis==='CREATED')){
      const n=el('span',row.reporting_month+'（按月校正）');
      n.title=(row.reporting_date_source||'用户确认归属月份')+'；平台原始结算日期：'+(row.platform_settlement_date||'—');
      return n;
    }
    if(f.id==='shop_id')return shopName(value);
    if(f.id==='product_skus')return skuCell(row);
    if(f.id==='order_created_time'&&row.kind==='BILL'){
      const start=dateTimeCell(value,recordZone(row)),end=dateTimeCell(row.order_created_time_end,recordZone(row));
      if(!start)return '—';if(!end||value===row.order_created_time_end)return start;
      const range=el('span',null,null,'fi-order-time-range');range.append(start);el('small','至',range);range.append(end);return range;
    }
    if(['product_cost_cny','product_cost_known_cny'].includes(f.id)&&value!=null&&row.id)return costLink(number(value),{recordId:row.id,shopId:row.shop_id,kind:row.kind==='BILL'?'BILL':row.kind||'ORDER'});
    if(['external_id','order_id'].includes(f.id)&&row.id){const n=button(value||'查看详情',null,()=>detailDialog(row.id),'fi-record-link');n.title=value?String(value)+' · 查看详情':'未关联订单 · 查看详情';if(row.marketing_order){const wrap=el('span',null,null,'fi-settlement-amount');wrap.append(n);const tag=el('small','刷单',wrap,'fi-marketing-tag');tag.title='不计入经营汇总；原始订单和对账保留';return wrap;}if(row.order_type_label?.includes('样品单')){const wrap=el('span',null,null,'fi-settlement-amount');wrap.append(n);const tag=el('small',row.order_type_label.includes('免费')?'免费样品单':'样品单',wrap,'fi-sample-tag');tag.title=row.order_type_label;return wrap;}return n;}
    if(f.id==='status'||f.id==='result')return badge(value);
    if(f.id==='related_order_status')return badge(value);
    if(['SETTLEMENT','BILL'].includes(row.kind)&&['amount','cny_amount'].includes(f.id)&&(row.amount==null||Number(row.amount)<=0)){
      const n=el('span',number(value),null,'fi-settlement-amount');
      const note=row.settlement_amount_note||(row.amount==null?'金额未获取':Number(row.amount)===0?'平台返回 0':'平台负向结算');
      el('small',note,n);n.title=note;n.classList.toggle('is-missing',row.amount==null);return n;
    }
    if(row.kind==='REFUND'&&['amount','cny_amount'].includes(f.id)&&value==null){const hint=el('span',row.refund_amount_note||'未确认',null,'fi-muted');hint.title=row.refund_amount_note||'平台尚未返回已完成退款金额';return hint;}
    if(f.id==='kind')return kinds[value]||value;
    if(f.id==='platform')return platformBadge(recordPlatform(row));
    if(f.id==='source')return ({API:'官方接口',IMPORT:'导入账单',MANUAL_COST:'手工费用'})[value]||value;
    if(f.id==='category')return feeName(value);
    if(f.type==='money'||f.type==='number')return number(value);
    if(f.type==='date')return f.recordTime?(dateTimeCell(row.create_time,recordZone(row))||businessDate(value)):businessDate(value);
    if(f.type==='datetime')return dateTimeCell(value,f.label.includes('北京时间')?displayZone:f.label.includes('UTC')?'UTC':recordZone(row))||businessDate(value);
    return value==null||value===''?'—':value;
  }
  function numberSearch(parent,value,onSearch,label='订单 / 账单 / 退款编号'){
    const form=el('form',null,parent,'fi-number-search'),input=el('textarea',null,form);input.rows=1;input.value=value;input.placeholder=label+'，多个用换行或逗号分隔';input.setAttribute('aria-label',label+'查询');input.maxLength=16000;
    const submit=el('button','查询',form,'fi-primary');submit.type='submit';
    form.onsubmit=event=>{event.preventDefault();const ids=[...new Set(input.value.trim().split(/[\s,，;；]+/).filter(Boolean))];if(ids.length>100){input.setCustomValidity('最多查询 100 个编号');input.reportValidity();return;}input.setCustomValidity('');onSearch(ids.join(','));};
    input.oninput=()=>input.setCustomValidity('');input.onkeydown=event=>{if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();form.requestSubmit();}};
    if(value)button('清除',form,()=>onSearch(''));
    el('small',allOrderView()?'完整订单号跨全部下单日期查找，仍按当前店铺、分组、平台和币种筛选；最多 100 个。':value?'按完整编号查询以下明细和导出，仍受当前日期、店铺及分组限制。':'支持批量粘贴，最多 100 个完整编号。',form);return form;
  }
  function renderFieldsTable(parent,items,settings,empty){
    settings=presentationSettings(settings);
    const fields=settings.visible.map(id=>settings.fields.find(f=>f.id===id)).filter(f=>f&&(!f.cny||data.fx));
    const showLead=!fields.some(f=>f.id==='platform');
    const t=table(parent,fields.map((f,i)=>(showLead&&i===0?'平台 / ':'')+(f.id==='shop_id'?'店铺':f.label)),items.map(row=>fields.map((f,i)=>{
      let value=columnValue(row,f);if(!(value instanceof Node)&&f.type==='text'){const text=el('span',value==null||value===''?'—':value,null,'fi-cell-text');text.title=String(value??'');value=text;}
      return showLead&&i===0?recordLead(row,value):value;
    })),empty,items.map(row=>row.id||[row.shop_id,row.statement_id,row.currency].join(':')));
    fields.forEach((f,i)=>{for(const row of t.rows){const cell=row.cells[i];if(['money','number'].includes(f.type))cell.classList.add('fi-num');if(f.recordTime&&row.parentElement.tagName==='THEAD')cell.title='按店铺时区显示，悬停时间可查看时区；平台未提供具体时间时仅显示业务日期。';}});
  }
  async function fieldsDialog(key,initial){
    const d=dialog('字段显示与导出');d.d.classList.add('fi-fields-dialog');
    el('p','主要字段默认选中，扩展字段可按需要添加。页面显示和文件导出独立保存，仅影响当前账号。未返回或不适用的字段显示 —；扩展金额按原币展示。',d.body,'fi-muted');
    el('p','全部订单按店铺时区的下单日期筛选；切换到已结算订单后按首次平台结算日期筛选。下单时间和首次结算日期分别展示；平台净结算可单独选择，用于核对。',d.body,'fi-muted');
    let settings=initial,checks=[];
    const choose=select(d.body,'数据类型',[['ALL','全部财务明细'],...Object.entries(kinds),['RECONCILIATION','结算对账']],key,async v=>{try{await render(v);}catch(e){d.feedback.textContent=e.message;}});
    const list=el('div',null,d.body,'fi-field-list');
    const toolbar=el('div',null,d.body,'fi-actions');
    button('恢复主要字段',toolbar,()=>{for(const c of checks){c.visible.checked=settings.defaults.visible.includes(c.id);c.exported.checked=settings.defaults.exported.includes(c.id);}});
    button('导出与显示相同',toolbar,()=>checks.forEach(c=>c.exported.checked=c.visible.checked));
    let renderRun=0;
    async function render(v){const run=++renderRun;list.textContent='正在读取字段…';checks=[];settings=null;
      const result=initial?.view===v?initial:await request('/fields/'+v);if(run!==renderRun)return;settings=presentationSettings(result,v);list.replaceChildren();
      const header=el('div',null,list,'fi-field-row is-header');el('span','字段',header);el('span','显示',header);el('span','导出',header);
      for(const group of [...new Set(settings.fields.map(f=>f.group))]){
        el('div',group,list,'fi-field-group');
        for(const f of settings.fields.filter(f=>f.group===group)){
          const fieldLabel=f.recordTime?'业务日期':f.label,row=el('div',null,list,'fi-field-row');el('span',fieldLabel+(f.cny?' · 人民币模式':''),row);
          const controls={id:f.id};for(const mode of ['visible','exported']){const label=el('label',null,row),input=el('input',null,label);input.type='checkbox';input.checked=settings[mode].includes(f.id);input.setAttribute('aria-label',fieldLabel+(mode==='visible'?' 页面显示':' 导出包含'));if(mode==='visible'&&f.id==='external_id'&&settings.orderView){input.disabled=true;input.checked=false;label.title='页面统一使用订单编号，原始记录编号可在详情查看或导出';}controls[mode]=input;}checks.push(controls);
        }
      }
    }
    d.submit('保存字段配置',async()=>{if(!settings||settings.view!==choose.value)throw Error('请等待字段加载完成');await request('/fields/'+choose.value,json('PUT',{visible:checks.filter(c=>c.visible.checked).map(c=>c.id),exported:checks.filter(c=>c.exported.checked).map(c=>c.id)}));d.d.close();load();});
    try{await render(key);}catch(e){d.feedback.textContent=e.message;}
  }
  function removeShopDialog(shop){
    const name=shop.display_name||shop.name,d=dialog('删除财务店铺及数据');
    el('p','将永久删除「'+name+'」的财务订单、退款、结算、费用、成本关联、导入记录及本地授权凭据，同时删除该店绑定的财务广告账户。此操作不可恢复。',d.body);
    el('p','提交后立即停止同步，历史数据在后台分批清理。其他店铺、分组共享成本表和商品运营中的店铺不受影响。',d.body,'fi-muted');
    const confirmation=field(d.body,'输入店铺名称确认删除：'+name);
    confirmation.autocomplete='off';
    const submit=d.submit('永久删除',async()=>{
      if(confirmation.value!==name)throw Error('请准确输入店铺名称');
      const message=await request('/shops/'+encodeURIComponent(shop.id),{method:'DELETE'});
      if(filters.shopId===shop.id)updateFilters({shopId:''});
      d.d.close();callbackNotice=message;await load();
    });
    submit.disabled=true;confirmation.oninput=()=>{submit.disabled=confirmation.value!==name;};
  }
  async function detailDialog(id){const d=dialog('财务记录详情');try{const row=await request('/records/'+encodeURIComponent(id)+'?'+qs({orderScope:allOrderView()?'ALL':'SETTLED'}));if(row.marketing_order){el('span','刷单',d.body,'fi-marketing-tag');el('p','此订单的结算金额、关联平台费用和成本不计入经营汇总；以下保留平台原始明细。',d.body,'fi-muted');}const settings=presentationSettings(await request('/fields/'+(row.kind||'ALL')),row.kind);
    if(row.kind==='ORDER'&&row.order_id&&globalThis.FinanceQianyi?.openReturns)button('查看该订单的千易售后',d.body,()=>FinanceQianyi.openReturns({request,shops:data.shops,groups:data.groups,selection:{shopId:row.shop_id,numbers:row.order_id,start:'',end:'',type:'',returnStatus:'',refundStatus:'',receiptStatus:'',receiptScope:'ALL'}}),'fi-record-link');
    const dl=el('dl',null,d.body);
    for(const f of settings.fields){if(f.cny&&!row.fx)continue;const value=columnValue(row,f);if(row[f.id]==null&&!f.id.startsWith('fx_'))continue;el('dt',f.label,dl);const dd=el('dd',null,dl);if(value instanceof Node)dd.textContent=value.textContent;else dd.textContent=value;}
    if(row.settlement_fee_breakdown?.length){
      el('h3','销售收入 → 实际净结算：逐项扣减',d.body);
      table(d.body,['费用项目','分组','金额（'+row.currency+'）','平台原始字段'],row.settlement_fee_breakdown.map(f=>[f.label,f.group,number(f.amount),f.field]));
      footnote(d.body,'正数扣减，负数返还。商家优惠和退款已体现在销售收入中；“待核对”表示平台分项暂未解释完整。');
    }
    if(row.productCost){const cost=row.productCost;el('h3','已结算订单商品成本明细 · 人民币',d.body);el('p',cost.note+' · 成本仓：'+(cost.warehouse||'待配置'),d.body,'fi-muted');
      table(d.body,['商品 / 规格 / SKU','数量 × 组合','月份 / 单价（¥）','成本（¥）','说明'],(cost.lines||[]).map(line=>[
        costSkuCell(row,line),number(line.quantity)+' × '+number(line.factor),
        (line.priceMonth||'—')+' / '+number(line.unitPriceCny),number(line.amountCny),line.issue||(line.shipmentId?'ERP 已确认出库成本':line.excluded?'已取消，不计成本':line.manualCostId?'按手动人民币单价计算':line.previousPrice?'历史月份价格估算':'按商家 SKU 计算')
      ]));
    }
    if(row.fx)el('p',[row.fx.provider,fxBasisLabel(row.fx),row.fx.businessDate?'业务日期：'+row.fx.businessDate:'',row.fx.rateDate?'报价日期：'+row.fx.rateDate:'',row.fx.status==='MISSING'?'汇率待补齐':''].filter(Boolean).join(' · '),d.body,'fi-muted');}catch(e){d.feedback.textContent=e.message;}}
  function costSkuCell(row,line){
    if(line.shipmentId){const cell=el('div',null,null,'fi-sku-cell');el('strong',line.costCode||'—',cell);if(line.name)el('small',line.name,cell);el('small','出库单：'+line.shipmentId+' · 来源行：'+line.sourceRow,cell);return cell;}
    const items=row.product_sku_details||[];
    const item=items.find(item=>line.lineId&&item.lineId===line.lineId)
      ||items.find(item=>line.productId&&line.skuId&&item.productId===line.productId&&item.skuId===line.skuId)
      ||{sellerSku:line.sellerSku||'',productId:line.productId,skuId:line.skuId};
    const cell=skuCell({shop_id:row.shop_id,product_sku_details:[item],product_sku_values:line.sellerSku?[line.sellerSku]:[]});
    if(line.costCode)el('small','成本型号：'+line.costCode,cell);
    return cell;
  }
  function exportDialog(fixedType='',selectionOverride=null){
    const selection={...filters,...selectionOverride,numbers:detailNumbers(),orderScope:allOrderView()?'ALL':'SETTLED'},identity=()=>typeof currentUser==='undefined'?'':JSON.stringify([currentUser?.organizationId,currentUser?.userId]),owner=identity();
    const advertisingExport=view==='ads',ledgerAds=advertisingExport&&fixedType==='FEE';
    if(ledgerAds)selection.advertisingOnly='true';
    const d=dialog(ledgerAds?'导出平台账单广告费':fixedType?'导出'+kinds[fixedType]:'导出财务报表');
    el('p',`${selection.start} 至 ${selection.end} · 当前分组、店铺、平台和币种筛选 · 全部符合条件的记录（不限当前页）`,d.body,'fi-muted');
    if(selection.orderScope==='ALL')el('p','导出订单时按下单日期，包含已取消、未结算及跨月结算订单。',d.body,'fi-muted');
    if(selection.orderScope==='ALL'&&selection.numbers)el('p','当前为完整订单号查询：导出覆盖全部下单日期。',d.body,'fi-muted');
    if(selection.numbers)el('p','仅导出已查询的订单 / 账单编号：'+selection.numbers,d.body,'fi-muted');
    const type=select(d.body,'导出类型',fixedType?[[fixedType,kinds[fixedType]]]:[['ALL','全部财务明细'],...Object.entries(kinds),['RECONCILIATION','结算对账']],fixedType||currentFieldView(),()=>{});
    const configure=button('配置导出字段',d.body,()=>fieldsDialog(type.value));
    const format=select(d.body,'文件格式',[['xlsx',advertisingExport?'Excel（广告明细与统计口径）':'Excel（含结算对账与统计口径）'],['csv','CSV 明细']],'xlsx',()=>{});
    const progress=el('span','正在生成报表，请稍候…',d.body,'fi-loading');progress.hidden=true;progress.setAttribute('role','status');
    const submit=d.submit('下载报表',async()=>{
      if(!allowed()||identity()!==owner)throw Error('登录信息已变化，请重新打开导出');
      const selectedType=type.value,selectedFormat=format.value,controller=new AbortController();let timedOut=false;
      const cancel=()=>controller.abort(),timer=setTimeout(()=>{timedOut=true;controller.abort();},180000);
      d.d.addEventListener('close',cancel);progress.hidden=false;submit.textContent='正在导出…';d.d.setAttribute('aria-busy','true');
      type.disabled=format.disabled=configure.disabled=true;
      try{
        const response=await fetch('/api/v1/finance/export?'+new URLSearchParams({...selection,view:selectedType,format:selectedFormat}),{credentials:'same-origin',cache:'no-store',signal:controller.signal});
        if(!response.ok){let message='导出失败，请重试';try{message=(await response.json()).message||message;}catch{}throw Error(message);}
        const blob=await response.blob();if(!d.d.isConnected)return;
        if(!allowed()||identity()!==owner)throw Error('登录信息已变化，请重新打开导出');
        const url=URL.createObjectURL(blob),a=el('a',null,d.body);
        a.href=url;a.download=`${ledgerAds?'平台账单广告费':kinds[selectedType]||(selectedType==='RECONCILIATION'?'结算对账':'财务报表')}-${selection.start}-${selection.end}.${selectedFormat}`;
        a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),10000);d.d.close();
      }catch(e){if(d.d.isConnected)throw Error(timedOut?'导出超时，请缩小日期或店铺范围后重试':e.message);}
      finally{
        clearTimeout(timer);d.d.removeEventListener('close',cancel);progress.hidden=true;submit.textContent='下载报表';d.d.setAttribute('aria-busy','false');
        type.disabled=format.disabled=configure.disabled=false;
      }
    });
  }
  async function downloadOrderCosts(selection){
    if(!allowed())throw Error('没有财务 BI 查看权限');
    const identity=financeIdentity();
    if(!identity||identity!==costExportOwner)throw Error('登录信息已变化，请重新打开导出');
    return submitCostExport(costExportSelection(selection));
  }
  function importDialog(){
    const d=dialog('导入平台账单 / 费用');
    if(view==='ads')el('p','广告数据优先由系统自动同步。仅在接口不提供相应数据或无法取得必要权限时补录；正常同步、历史回补和自动重试期间无需导入。请先核对日期覆盖，避免重复入账。',d.body,'fi-muted');
    const shop=select(d.body,'对应店铺',(data?.shops||[]).map(s=>[s.id,s.display_name||s.name]),filters.shopId||data?.shops?.[0]?.id||'',()=>{});
    const kind=select(d.body,'数据类型',[['BILL','结算账单（用于对账）'],['COST','物流贴单成本'],['AD','广告费用（接口无法提供时补录）']],view==='ads'?'AD':'BILL',()=>{});
    const drop=el('div',null,d.body,'fi-import-drop'),file=field(drop,'上传 CSV / XLSX','file');file.accept='.csv,.xlsx';
    el('p','文件需有标题行、唯一编号、日期、币种和金额。日期使用 YYYY-MM-DD 文本，上传后可选择对应列。单次最多 20,000 行。',d.body,'fi-muted');
    const mapping=el('div',null,d.body,'fi-grid2');let controls={},ready=false;
    file.onchange=async()=>{ready=false;mapping.replaceChildren();try{const form=new FormData();form.append('file',file.files[0]);const p=await request('/imports/preview',{method:'POST',body:form});
      d.feedback.textContent=`读取到 ${p.rows} 行，请确认列对应关系。`;
      for(const [key,label,pattern]of[['external_id','唯一记录 / 账单编号',/^(id|external_id|statement.?id|账单编号|记录编号)$/i],['date','业务日期',/date|日期|时间/i],['currency','币种',/currency|币种|货币/i],['amount','金额',/amount|金额|消耗/i],['order_id','订单编号（可选）',/order.?id|order.?sn|订单/i],['category','类别（可选）',/category|类别|类型/i]]){
        const match=p.columns.findIndex(v=>pattern.test(v));controls[key]=select(mapping,label,[['','请选择列'],...p.columns.map((c,i)=>[String(i),c||'第 '+(i+1)+' 列'])],match>=0?String(match):'',()=>{});
      }ready=true;
    }catch(e){d.feedback.textContent=e.message;}};
    d.submit('确认导入',async()=>{if(!ready||!shop.value)throw Error('请选择店铺和文件');for(const k of ['external_id','date','currency','amount'])if(!controls[k]?.value)throw Error('请补齐必填列映射');
      const form=new FormData();form.append('file',file.files[0]);form.append('shopId',shop.value);form.append('kind',kind.value);form.append('mapping',JSON.stringify(Object.fromEntries(Object.entries(controls).map(([k,c])=>[k,c.value]))));
      const result=await request('/imports',{method:'POST',body:form});d.d.close();await load();notify(result.duplicate?'此文件已导入，无重复入账。':`成功导入 ${result.rows} 条记录。`);
    });
  }
  function liveScreen(root){
    root.className='workspace-body finance-workspace fi-live-screen';
    const top=el('header',null,root,'fi-live-head'),brand=el('div',null,top);
    el('span','HOYEE / LIVE COMMERCE',brand,'fi-eyebrow');el('h1','实时大屏',brand);el('p','每一份增长，都值得被看见',brand);
    const controls=el('div',null,top,'fi-actions');
    loadingIndicator(controls);
    groupSelect(controls);
    shopFilter(controls,'全部财务店铺');
    select(controls,'金额口径',[['CNY','人民币 ¥'],['ORIGINAL','原币']],filters.displayCurrency,v=>selectFilters({displayCurrency:v}));
    if(filters.displayCurrency==='ORIGINAL')select(controls,'原币',[['','全部币种（人民币汇总）'],...data.currencies.map(c=>[c,c])],data.filters.currency,selectCurrency);
    button('返回总览',controls,async()=>{if(document.fullscreenElement)await document.exitFullscreen();navigate('overview');});
    button('全屏展示',controls,async()=>{if(document.fullscreenElement)await document.exitFullscreen();else await document.getElementById('finance-page').requestFullscreen();});
    el('div',new Date().toLocaleString('zh-CN',{timeZone:displayZone,hour12:false}),top,'fi-live-clock');
    const errors=data.sync.filter(r=>r.status==='ERROR'),orders=data.sync.filter(r=>r.channel==='ORDER_LIVE');
    const successes=orders.map(s=>s.last_success_at).filter(Boolean).sort();
    const strip=el('div',null,root,'fi-live-strip');el('span',errors.length?'部分同步异常 · 正在自动重试':orders.some(r=>['RUNNING','SYNCING','READY'].includes(r.status))?'数据同步中':'数据看板在线',strip,errors.length?'fi-live-warning':'fi-live-online');
    el('span',successes.length?'订单最近完整同步：'+new Date(successes[0]).toLocaleString('zh-CN',{timeZone:displayZone}):'等待财务店铺完成首次同步',strip);
    el('span','页面 15 秒刷新 · 订单同步目标约 1 分钟',strip);el('span','平台回传、历史回补和多店排队会有延迟',strip);
    const state=el('div','',root,'fi-state');state.hidden=true;state.setAttribute('role','status');
    const hero=el('div',null,root,'fi-live-hero');
    const sales=el('section',null,hero,'fi-live-sales');const headline=el('div',null,sales,'fi-live-sales-heading');el('div','TODAY’S SALES',headline,'fi-live-kicker');el('span','今日成交战报',headline,'fi-live-sales-badge');el('h2','今日订单金额',sales);
    const amount=el('div',null,sales,'fi-live-amount');el('strong',number(sum('ORDER')),amount);el('span',data.filters.displayCurrency||data.filters.currency||'—',amount);
    el('p','已支付且未取消 · 以平台订单数据为准',sales);
    if(data.fx)el('small',fxBasisLabel(data.fx)+' · '+(data.fx.provider||'汇率来源待确认'),sales);
    const kpis=el('div',null,hero,'fi-live-kpis');
    const count=total('ORDER')?.known_amounts,aov=count&&sum('ORDER')!=null?Number(sum('ORDER'))/Number(count):null;
    for(const [label,value,unit,note] of [['有效订单',count,'单','有已知支付金额的订单'],['平均客单价',aov,data.filters.displayCurrency||data.filters.currency,'订单金额 / 有效订单数'],['今日确认退款',sum('REFUND'),data.filters.displayCurrency||data.filters.currency,'按退款业务日统计'],['今日销售收入',sum('SETTLEMENT'),data.filters.displayCurrency||data.filters.currency,'销售收入 · 结算日口径，含历史订单']]){
      const k=el('section',null,kpis,'fi-live-kpi');el('span',label,k);icon(label==='有效订单'?'shop':label==='平均客单价'?'chart':label==='今日确认退款'?'refund':'wallet',el('span',null,k,'fi-live-kpi-icon'));const valueNode=el('div',null,k);el('strong',number(value),valueNode);el('small',unit||'',valueNode);el('p',note,k);
    }
    productCostSummary(root);
    const grid=el('div',null,root,'fi-chart-grid fi-live-grid');
    const hourly=card(grid,'小时成交趋势','按各店铺当地小时汇总 · 空缺时间连线，不补造金额',8);
    plot(hourly.n,'小时成交金额',()=>({xAxis:{...axis('category'),data:data.hourly.map(r=>r.hour),boundaryGap:false},yAxis:axis(),series:[{name:'订单金额',type:'line',data:data.hourly.map(r=>r.amount??null),connectNulls:true,showSymbol:true,symbolSize:5,lineStyle:{width:3,color:'#ec6746'},itemStyle:{color:'#ec6746'},areaStyle:{color:{type:'linear',x:0,y:0,x2:0,y2:1,colorStops:[{offset:0,color:'#f39a613d'},{offset:1,color:'#f8c99505'}]}}}]}),data.hourly.some(r=>r.amount!=null));
    const rank=card(grid,'店铺成交榜','当前展示币种 · 今日有效订单',4);
    const rankingRows=data.ranking.filter(r=>r.kind==='ORDER'&&r.amount!=null).sort((a,b)=>Number(b.amount)-Number(a.amount)).slice(0,5);
    if(!rankingRows.length)el('div','连接财务店铺后显示实时排行',rank.n,'fi-empty');
    const rankingList=el('div',null,rank.n,'fi-live-ranking');
    const max=Math.max(...rankingRows.map(r=>Number(r.amount)),1);
    rankingRows.forEach((r,i)=>{const line=el('div',null,rankingList,'fi-live-rank');line.dataset.updateKey=r.shop_id;el('b',String(i+1).padStart(2,'0'),line);const body=el('div',null,line);const title=el('div',null,body);el('span',shopName(r.shop_id),title);el('strong',number(r.amount),title);const rail=el('div',null,body,'fi-live-rail');el('i',null,rail).style.width=Math.max(0,Number(r.amount)/max*100)+'%';});
    const recent=card(grid,'最新订单','仅展示订单业务信息 · 自动更新',8);
    table(recent.n,['平台 / 时间','店铺','订单编号','商品 SKU','状态','金额','已结算商品成本（¥）'],data.recent.map(r=>[recordLead(r,r.create_time?new Date(r.create_time).toLocaleTimeString('zh-CN',{timeZone:displayZone,hour12:false}):businessDate(r.record_date)),shopName(r.shop_id),r.external_id,skuCell(r),badge(r.status),number(data.fx?r.cny_amount:r.amount),r.product_cost_cny==null?r.product_cost_state||'待核对':costLink(number(r.product_cost_cny),{recordId:r.id,shopId:r.shop_id,kind:'ORDER'})]),'今日尚无订单，授权并开启财务店铺同步后自动显示。',data.recent.map(r=>r.id||r.shop_id+':'+r.external_id));
    donut(grid,'订单状态分布','包含未付款与取消订单，独立展示',data.statuses.filter(r=>r.kind==='ORDER').map(r=>({name:statusName(r.status),value:Number(r.records)})));
    const footer=el('footer',null,root,'fi-live-footer');el('span','HOYEE INTELLIGENCE · 让每一笔增长看得见',footer);el('span','更新时间 '+new Date(data.updatedAt).toLocaleTimeString('zh-CN',{timeZone:displayZone,hour12:false})+' · 财务角色授权可见',footer);
  }
  async function loadLive(run,interactive,signal){
    if(document.hidden){refreshTimer=setTimeout(()=>load(false),15000);return;}
    filters.start=today();filters.end=today();filters.granularity='day';
    if(!root.classList.contains('fi-live-screen')){cleanup();root.replaceChildren();root.className='workspace-body finance-workspace fi-live-screen';el('div','正在连接今日经营数据…',root,'fi-state');}
    markPending();setLoading(interactive);
    const selection={...filters};
    let succeeded=false;
    try{const [response]=await Promise.all([request('/screen?'+new URLSearchParams(selection),{method:'POST',signal}),ensureCharts()]);if(run!==generation||!active)return;data=response;filters.currency=data.filters.currency||'';filters.fxSnapshot=data.fx?.id||'';const next=root.cloneNode(false);liveScreen(next);commit(next);succeeded=true;}
    catch(e){if(run===generation&&!signal.aborted){notify('本次查询未完成，已停止自动重试。请检查网络或点击重新查询。',true);if(!root.querySelector('.fi-live-recovery')){const controls=el('div',null,root,'fi-actions fi-live-recovery');button('重新查询',controls,()=>load());button('返回总览',controls,()=>navigate('overview'));}}}
    finally{if(run===generation&&active){setLoading(false);if(succeeded)refreshTimer=setTimeout(()=>load(false),15000);}}
  }
  async function load(interactive=true){
    if(!active||!allowed())return;
    if(view==='orders'&&!['ORDER','REFUND'].includes(selectedKind))selectedKind='ORDER';
    const nextKey=selectionKey();
    if(activeLoadKey===nextKey&&loadController&&!loadController.signal.aborted)return;
    activeLoadKey=nextKey;
    const run=++generation;clearTimeout(refreshTimer);clearInterval(waitingTimer);
    qianyiCleanup?.();qianyiCleanup=null;
    loadController?.abort();loadController=new AbortController();const signal=loadController.signal;
    const read=path=>request(path,{signal});
    const started=Date.now();
    waitingTimer=setInterval(()=>{
      if(run===generation&&active&&!signal.aborted)
        notify(`正在计算所选数据，已等待 ${Math.floor((Date.now()-started)/1000)} 秒，请保持页面打开，无需重复点击。`);
    },10000);
    rememberFilters();
    if(view==='live'){
      try{return await loadLive(run,interactive,signal);}
      finally{if(run===generation){activeLoadKey='';clearInterval(waitingTimer);}}
    }
    if(mountedView!==view){cleanup();root.replaceChildren();const initial=root.cloneNode(false);shell(initial);IncrementalDOM.patch(root,initial);}
    const changed=markPending();setLoading(true);
    if(!changed)notify(mountedView===view?'正在更新当前数据，请稍候…':'正在查询，请稍候…');
    const selection={...filters},selectedPage=page,numbers=detailNumbers(),spendSelection={search:adSpendSearch,assignment:adSpendAssignment,page:adSpendPage};
    const query=extra=>new URLSearchParams({...selection,...extra}).toString();
    try{
      const next=root.cloneNode(false);
      if(view==='costs'||view==='expense-ledger'){
        const response=await read('/shops');if(run!==generation||!active)return;
        const nextData={shops:response.items,groups:response.groups||[],sync:[]};
        if(view==='costs'){
          const shopId=globalThis.FinanceCosts?.shopId?.(),selected=nextData.shops.find(s=>s.id===shopId);
          const wantedGroup=globalThis.FinanceCosts?.groupId?.(),groupId=selected?.group_id||nextData.groups.find(g=>g.id===wantedGroup)?.id||(!nextData.shops.length?nextData.groups[0]?.id:'')||'';
          const costScope={shopId:selected?.id||'',groupId};
          const costPath=globalThis.FinanceCosts?.initialPath?.(costScope)||('/costs?'+new URLSearchParams(costScope));
          nextData.costs=globalThis.FinanceCostPreviewData||await read('/workspace'+costPath);
          if(run!==generation||!active)return;data=nextData;const host=el('div',null,shell(next));host.dataset.financeCosts='';
        }else{
          data=nextData;const host=el('div',null,shell(next));host.dataset.financeExpenses='';
        }
      }else if(qianyiView()){
        const response=await read('/shops');if(run!==generation||!active)return;
        data={shops:response.items,groups:response.groups||[],sync:[]};
        recordView(shell(next),run,null);
      }else if(allOrderView()){
        const response=await read('/workspace/query?'+query({view:'orders',kind:'ORDER',orderScope:'ALL',page:selectedPage,numbers}));
        if(run!==generation||!active)return;
        data=response;filters.currency=data.filters.currency||'';filters.fxSnapshot=data.fx?.id||'';
        recordView(shell(next),run,response.records);
      }else if(view==='reconciliation'){
        const response=await read('/workspace/query?'+query({view:'reconciliation',page:selectedPage,numbers}));if(run!==generation||!active)return;
        data=response;filters.currency=data.filters.currency||'';filters.fxSnapshot=data.fx?.id||'';
        reconciliation(shell(next),{...response.records,...response});
      }else if(view!=='shops'){
        // Start the chart download immediately; a slow chart component must not hold up rows.
        ensureCharts().then(()=>{if(run===generation&&run===mountedGeneration&&active&&mountedView===view)paintCharts();})
          .catch(()=>{if(run===generation&&active)notify('图表暂未加载，明细仍可查看。',true);});
        const isRecordView=view!=='overview';
        const allowedKinds=view==='ads'?['AD','FEE','AD_GMV']:recordKinds();
        if(isRecordView&&!allowedKinds.includes(selectedKind))selectedKind=allowedKinds[0];
        activeLoadKey=selectionKey();
        const kind=selectedKind;
        const response=await read(view==='overview'?'/overview?'+query():
          '/workspace/query?'+query({view:view==='ads'&&!window.FinanceAdSpend?'ads-support':view,kind,page:selectedPage,numbers,orderScope,...(view==='ads'?{search:spendSelection.search,assignment:spendSelection.assignment,spendPage:spendSelection.page}:{})})).catch(async error=>{
            if(view!=='ads'||!window.FinanceAdSpend||signal.aborted)throw error;
            const [shops,accounts]=await Promise.all([read('/shops'),read('/ads').catch(()=>[])]);
            return {shops:shops.items,groups:shops.groups||[],sync:[],currencies:[],filters:{...selection},adAccounts:accounts,adSpendError:true};
          });
        if(run!==generation||!active)return;
        data=response;Object.assign(filters,{currency:response.filters.currency||'',fxSnapshot:response.fx?.id||''});
        const body=shell(next);
        if(view==='overview')overview(body);else if(view==='ads')adsView(body,response.records);else recordView(body,run,response.records);
      }else{
        const [shops,accounts,shopeeAds]=await Promise.all([read('/shops'),read('/ads'),window.FinanceShopeeAds?read('/shopee-ads').catch(error=>{if(signal.aborted)throw error;return {loadError:true};}):null]);
        if(run!==generation||!active)return;data={...(data||{}),shopeeAds};shopView(shell(next),shops,accounts);
      }
      if(run!==generation||!active)return;
      commit(next);
      if(qianyiView()){
        const host=root.querySelector('[data-finance-qianyi]');
        if(globalThis.FinanceQianyi)qianyiCleanup=FinanceQianyi.mountReturns(host,{
          request,shops:data.shops,groups:data.groups,selection:{...filters,dateField:'RECEIPT',...qianyiSelection},
          onSelection:value=>{
            if(run!==generation||!active)return;
            qianyiSelection=Object.fromEntries(['type','returnStatus','refundStatus','costStatus','receiptScope','receiptStatus','dateField','numbers'].map(k=>[k,value[k]]).filter(([,value])=>value!=null));
            if(!value.start&&!value.end)Object.assign(qianyiSelection,{start:'',end:''});
            for(const key of ['shopId','groupId','platform'])filters[key]=value[key]||'';
            filters.currency='';filters.fxSnapshot='';
            if(value.start&&value.end){filters.start=value.start;filters.end=value.end;pinnedDates=true;rememberFilters();}
            mountedSelection=selectionKey();
          }
        });
        else el('p','千易售后组件暂未加载，请刷新重试。',host,'fi-muted');
      }
      if(view==='costs'){
        const host=root.querySelector('[data-finance-costs]');
        if(globalThis.FinanceCosts)FinanceCosts.mount(host,{shops:data.shops,groups:data.groups,canManage:canManage(),preview:data.costs,dates:{start:filters.start,end:filters.end},onDatesChange:dates=>{filters.start=dates.start;filters.end=dates.end;filters.granularity=dateGranularity(dates.start,dates.end);filters.fxSnapshot='';pinnedDates=true;rememberFilters();},openRecord:detailDialog,skuCell,dateTimeCell,numberSearch,downloadOrderCosts,mountOrderCostExports:mountCostExports,isActive:()=>active&&view==='costs',request:(path,options)=>request('/workspace'+path,options)});
        else el('p','成本管理页面组件暂未加载，请刷新重试。',host,'fi-muted');
      }
      if(view==='expense-ledger'){
        const host=root.querySelector('[data-finance-expenses]');
        if(globalThis.FinanceCharges)FinanceCharges.mount(host,{shops:data.shops,groups:data.groups,canManage:canManage(),skuCell,dateTimeCell,numberSearch,filters:{...filters},onFiltersChange:scope=>{updateFilters(scope);pinnedDates=true;rememberFilters();},request:(path,options)=>request('/workspace'+path,options)});
        else el('p','费用组件暂未加载，请刷新重试。',host,'fi-muted');
      }
      const errors=(data?.sync||[]).filter(s=>s.status==='ERROR').length;
      notify(callbackNotice||(errors?`${errors} 项同步需要处理，可在财务店铺管理查看权限或连接问题。`:''));callbackNotice='';
      if(data?.readModel||view==='shops'||data?.settlementCalculation?.status==='RUNNING'||data?.cache?.refreshing||data?.productCosts?.processing||(data?.sync||[]).some(s=>['READY','RUNNING','SYNCING'].includes(s.status)))
        refreshTimer=setTimeout(refreshWhenIdle,view==='shops'&&data?.deletions?.some(job=>job.status!=='COMPLETE')?3000:15000);
    }catch(e){if(run===generation&&!signal.aborted){
      const changed=mountedView===view&&!!mountedSelection&&mountedSelection!==selectionKey();
      const content=root.querySelector('.fi-content');
      if(changed&&content){content.hidden=true;content.setAttribute('aria-busy','false');}
      const temporary=!e.message||/Internal server error|Failed to fetch|fetch failed|NetworkError|Load failed|timeout|超时/i.test(e.message);
      const message=temporary?'本次查询连接中断':e.message;
      notify(message+'。已停止自动重试，可点击刷新重新查询。'+(changed?'已隐藏上次筛选的结果。':mountedView===view?'当前显示的是相同条件的上次结果。':''),true);
    }}
    finally{if(run===generation){activeLoadKey='';clearInterval(waitingTimer);setLoading(false);}}
  }
  globalThis.FinanceWorkspace={allowed,canManage,enter(route){
    if(!allowed())return;shopeeEpoch++;restoreFilters();const wasLive=view==='live';
    view=route==='fees'?'fee-records':routes[route]?route:'overview';
    if(view!=='costs')leaveCostExports();
    if(route==='fees')selectedKind='FEE';
    if(['fee-records','expense-ledger'].includes(view))document.querySelector('[data-finance-route="fees"]')?.classList.add('active');
    if(view==='live'&&(!wasLive||!reportFilters)){
      reportFilters={...filters};filters={...filters,displayCurrency:'CNY',currency:'',fxSnapshot:''};
    }else if(view!=='live'&&wasLive&&reportFilters){
      filters={...reportFilters,fxSnapshot:''};reportFilters=null;
    }
    active=true;page=1;load();
  },leave,reset};
  window.addEventListener('heyi:company-reset',reset);
})();
