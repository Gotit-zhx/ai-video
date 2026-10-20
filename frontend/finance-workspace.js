/* Finance workspace. Tabler-inspired density; local ECharts; no synthetic production data. */
(() => {
  'use strict';
  const root=document.getElementById('finance-page');if(!root)return;
  const colors=['#4263eb','#53a8c9','#9a85d8','#e4ac62','#e7828c','#8198be'];
  const routes={overview:'财务总览',settlements:'结算中心',reconciliation:'对账中心',orders:'订单与退款',fees:'费用与调整',ads:'广告分析',shops:'财务店铺管理'};
  const kinds={ORDER:'订单',REFUND:'退款',SETTLEMENT:'结算明细',BILL:'结算账单',FEE:'平台费用',AD:'广告费用',AD_GMV:'GMV Max 分析',COST:'外部成本',PENDING:'待结算'};
  const states={READY:'待同步',RUNNING:'同步中',SYNCING:'历史回补中',SUCCESS:'已同步',ERROR:'同步异常',ACTIVE:'已授权',TOKEN_EXPIRED:'授权过期',DISABLED:'已断开',SETTLED:'已结算',IMPORTED:'已导入',REPORTED:'平台报表',PARTIAL:'明细同步中',UNSETTLED:'预计待结算',MATCHED:'核对一致',MISSING:'缺少明细',DIFFERENCE:'金额有差异'};
  const paths={chart:'M4 19h16M7 15v-4m5 4V5m5 10V8',download:'M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4',refresh:'M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-2l2 3M18 17a7 7 0 0 1-12 2l-2-3',shop:'M3 10h18l-2-7H5zM5 10v11h14V10M9 21v-7h6v7',wallet:'M3 6h18v15H3zM3 6V3h15v3M16 12h5v5h-5z',arrow:'M7 17 17 7M7 7h10v10',refund:'m9 6-5 5 5 5M4 11h10a5 5 0 0 1 0 10',ad:'m3 11 15-6v14L3 14zM6 15v6h4v-5M21 9v6',fee:'M7 3h10l4 4v14H3V7zM8 9h8M8 13h8M8 17h5',info:'M12 11v6M12 7v1M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',plus:'M12 4v16M4 12h16',check:'m5 12 4 4 10-10'};
  let active=false,generation=0,view='overview',data=null,chartPromise,charts=[],observer,themeObserver,refreshTimer=null,page=1,selectedKind='ORDER';
  const today=()=>new Date().toISOString().slice(0,10),ago=n=>new Date(Date.now()-n*86400000).toISOString().slice(0,10);
  let filters={start:ago(29),end:today(),shopId:'',platform:'',currency:'',granularity:'day',displayCurrency:'ORIGINAL',fxSnapshot:''};
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=String(text);if(cls)n.className=cls;parent?.append(n);return n;};
  function icon(name,parent){const n=document.createElementNS('http://www.w3.org/2000/svg','svg');n.setAttribute('viewBox','0 0 24 24');n.classList.add('fi-svg');n.setAttribute('aria-hidden','true');const path=document.createElementNS(n.namespaceURI,'path');path.setAttribute('d',paths[name]||paths.chart);n.append(path);parent?.append(n);return n;}
  function button(label,parent,fn,cls=''){const n=el('button',label,parent,cls);n.type='button';n.addEventListener('click',async()=>{try{await fn(n);}catch(e){notify(e.message,true);}});return n;}
  function action(label,symbol,parent,fn,cls=''){const n=button('',parent,fn,'fi-btn-icon '+cls);icon(symbol,n);el('span',label,n);return n;}
  const number=v=>v==null?'—':Number(v).toLocaleString('zh-CN',{maximumFractionDigits:2});
  const allowed=()=>typeof currentUser!=='undefined'&&currentUser?.platformAdmin===true;
  const qs=extra=>new URLSearchParams({...filters,...extra}).toString();
  async function request(path,options={}){
    if(!allowed())throw Error('财务 BI 仅限超级管理员使用');
    return (await api('/api/v1/finance'+path,options)).data;
  }
  const json=(method,body)=>({method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  function notify(message,error=false){const n=root.querySelector('.fi-state');if(n){n.textContent=message;n.hidden=!message;n.classList.toggle('is-error',error);}}
  function navigate(route){location.hash='finance-'+route;}
  function cleanup(){charts.forEach(c=>c.dispose());charts=[];observer?.disconnect();themeObserver?.disconnect();observer=themeObserver=null;}
  function reset(){active=false;generation++;clearTimeout(refreshTimer);cleanup();root.replaceChildren();data=null;page=1;filters={start:ago(29),end:today(),shopId:'',platform:'',currency:'',granularity:'day',displayCurrency:'ORIGINAL',fxSnapshot:''};document.querySelectorAll('.fi-dialog').forEach(d=>d.remove());}
  function leave(){active=false;generation++;clearTimeout(refreshTimer);cleanup();}
  function ensureCharts(){
    if(globalThis.echarts)return Promise.resolve(echarts);
    if(!chartPromise)chartPromise=new Promise(resolve=>{const s=document.createElement('script');const timer=setTimeout(()=>{chartPromise=null;resolve(null);s.remove();},12000);s.src='/vendor/echarts/echarts.min.js?v=6.1.0';s.onload=()=>{clearTimeout(timer);resolve(globalThis.echarts||null);};s.onerror=()=>{clearTimeout(timer);chartPromise=null;resolve(null);};document.head.append(s);});
    return chartPromise;
  }
  function select(parent,label,options,value,onchange){const wrap=el('label',null,parent);el('span',label,wrap);const n=el('select',null,wrap);for(const [v,name] of options){el('option',name,n).value=v;}n.value=value||'';n.setAttribute('aria-label',label);n.onchange=()=>onchange(n.value);return n;}
  function dateInput(parent,label,value,key){const n=el('input',null,parent);n.type='date';n.value=value;n.setAttribute('aria-label',label);n.onchange=()=>{filters[key]=n.value;page=1;load();};return n;}
  function shell(){
    cleanup();root.replaceChildren();root.className='workspace-body finance-workspace';
    const head=el('header',null,root,'fi-head'),copy=el('div',null,head);
    el('span','FINANCE / ANALYTICS',copy,'fi-eyebrow');el('h1',routes[view],copy,'fi-title');
    el('p',view==='shops'?'独立管理财务店铺与同步，把每一份数据连接起来。':'看清每一笔收入、每一项费用，与每家店铺的经营表现。',copy,'fi-subtitle');
    const actions=el('div',null,head,'fi-actions');
    if(view!=='shops')action('店铺管理','shop',actions,()=>navigate('shops'));
    action('刷新','refresh',actions,()=>{filters.fxSnapshot='';return load();});
    if(view==='shops'){action('连接店铺','plus',actions,()=>authorizeDialog(),'fi-primary');}
    else{action('导入账单','plus',actions,()=>importDialog());action('导出报表','download',actions,()=>exportDialog(),'fi-primary');}
    if(view!=='shops'){
      const bar=el('div',null,root,'fi-filters');
      select(bar,'平台',[['','全部平台'],['SHOPEE','Shopee'],['TIKTOK_SHOP','TikTok Shop']],filters.platform,v=>{filters.platform=v;filters.shopId='';page=1;load();});
      select(bar,'店铺',[['','全部店铺'],...(data?.shops||[]).filter(s=>!filters.platform||s.platform===filters.platform).map(s=>[s.id,s.display_name||s.name])],filters.shopId,v=>{filters.shopId=v;page=1;load();});
      select(bar,'原币',[['',filters.displayCurrency==='CNY'?'全部币种':'自动选择原币'],...(data?.currencies||[]).map(c=>[c,c])],filters.currency,v=>{filters.currency=v;page=1;load();});
      const currencyToggle=el('div',null,bar,'fi-pills');currencyToggle.setAttribute('aria-label','金额展示币种');
      for(const [key,label] of [['ORIGINAL','原币'],['CNY','人民币 ¥']])button(label,currencyToggle,()=>{filters.displayCurrency=key;filters.currency='';filters.fxSnapshot='';page=1;load();}).setAttribute('aria-pressed',String(filters.displayCurrency===key));
      select(bar,'范围',[['30','近 30 天'],['7','近 7 天'],['90','近 90 天'],['180','近半年'],['365','近一年'],['custom','自定义']],String(Math.round((new Date(filters.end)-new Date(filters.start))/86400000)+1),v=>{if(v==='custom')return;filters.start=ago(Number(v)-1);filters.end=today();page=1;load();});
      const date=el('div',null,bar,'fi-date-range');dateInput(date,'开始日期',filters.start,'start');el('span','—',date);dateInput(date,'结束日期',filters.end,'end');
    }
    const state=el('div','正在读取财务数据…',root,'fi-state');state.setAttribute('role','status');state.setAttribute('aria-live','polite');
    return el('div',null,root,'fi-content');
  }
  function meta(parent){
    const row=el('div',null,parent,'fi-meta'),left=el('div',null,row);el('i',null,left,'fi-dot');el('span','当前口径 · ',left);el('span',data?.filters?.displayCurrency||data?.filters?.currency||'尚无币种数据',left,'fi-currency');
    if(data?.fx){el('span',' · 参考汇率 '+new Date(data.fx.time).toLocaleDateString('zh-CN')+' · ',left);const source=el('a',data.fx.provider,left);source.href='https://www.exchangerate-api.com';source.target='_blank';source.rel='noopener noreferrer';source.style.color='inherit';el('span',' · 按同一参考汇率折算',left);}
    else el('span',' · 原币独立核算',left);
    const right=el('div',null,row,'fi-actions');const pills=el('div',null,right,'fi-pills');
    for(const [key,label] of [['day','日 K'],['week','周 K'],['month','月 K'],['quarter','季度 K'],['halfyear','半年 K'],['year','年 K']]){
      const n=button(label,pills,()=>{filters.granularity=key;load();});n.setAttribute('aria-pressed',String(filters.granularity===key));
    }
  }
  function total(kind){return data?.totals?.find(r=>r.kind===kind);}
  function sum(kind){return total(kind)?.amount;}
  function metricCards(parent){
    const cards=el('div',null,parent,'fi-metrics');
    const definitions=[['SETTLEMENT','已结算净额','平台最终结算口径','wallet','settlements'],['ORDER','订单金额','已支付且未取消订单','shop','orders'],['REFUND','成功退款','待退款不计入金额','refund','orders'],['FEE','平台费用','已计入结算，不重复扣减','fee','fees'],['AD','广告消耗','TikTok 账户报表 / 导入','ad','ads'],['COST','外部成本','采购、头程等补充成本','chart','fees']];
    definitions.forEach(([kind,label,description,symbol,route],i)=>{
      const record=total(kind),n=button('',cards,()=>{selectedKind=kind;navigate(route);},'fi-metric'+(i===0?' is-featured':''));
      n.style.setProperty('--accent',colors[i]);const title=el('div',null,n,'fi-metric-label');el('span',label,title);icon(symbol,el('span',null,title,'fi-metric-icon'));
      const value=el('div',null,n,'fi-metric-value');el('strong',number(record?.amount),value);el('small',data.filters.displayCurrency||data.filters.currency||'',value);
      const foot=el('div',null,n,'fi-metric-note');el('span',description,foot);icon('arrow',foot);
    });
  }
  function card(parent,title,subtitle,span=4){const n=el('section',null,parent,'fi-card fi-span-'+span);const h=el('div',null,n,'fi-card-head'),copy=el('div',null,h);el('h3',title,copy);el('p',subtitle,copy);return {n,h};}
  function theme(){const dark=document.documentElement.dataset.theme==='dark';return {text:dark?'#d9e2f5':'#25344f',muted:dark?'#8f9db7':'#8995aa',grid:dark?'#2b364b':'#eef1f7',surface:dark?'#182236':'#fff'};}
  function base(){const t=theme();return {color:colors,animation:!matchMedia('(prefers-reduced-motion: reduce)').matches,animationDuration:350,textStyle:{fontFamily:'Inter, -apple-system, PingFang SC, sans-serif',fontSize:11,color:t.text},tooltip:{trigger:'axis',renderMode:'richText',backgroundColor:t.surface,borderColor:t.grid,textStyle:{color:t.text,fontSize:11},confine:true},legend:{bottom:5,itemWidth:7,itemHeight:7,icon:'circle',textStyle:{color:t.muted,fontSize:10}},grid:{left:8,right:15,top:25,bottom:36,containLabel:true}};}
  function axis(type='value'){return {type,axisLine:{show:false},axisTick:{show:false},axisLabel:{color:theme().muted,fontSize:10,hideOverlap:true},splitLine:{show:type==='value',lineStyle:{color:theme().grid,type:'dashed'}}};}
  function plot(parent,title,builder,hasData=true){
    if(!hasData){const empty=el('div',null,parent,'fi-chart-empty');icon('chart',empty);el('span','同步后在这里查看'+title,empty);return;}
    const node=el('div',null,parent,'fi-chart');node.setAttribute('role','img');node.setAttribute('aria-label',title);
    if(!globalThis.echarts){el('p','图表组件加载失败，请刷新；下方明细仍可查看。',node,'fi-muted');return;}
    const c=echarts.init(node,null,{renderer:'svg'});charts.push(c);c.setOption({...base(),...builder()});c.financeBuilder=builder;
  }
  function trend(parent,keys,title,subtitle,span=8){
    const {n}=card(parent,title,subtitle,span);
    plot(n,title,()=>({xAxis:{...axis('category'),data:data.trend.map(r=>r.period),boundaryGap:false},yAxis:axis(),legend:{...base().legend,data:keys.map(([k,label])=>label)},
      series:keys.map(([key,name],i)=>({name,type:'line',data:data.trend.map(r=>r[key]??null),smooth:false,connectNulls:false,showSymbol:data.trend.length<2,
        lineStyle:{width:2.5},areaStyle:i===0?{color:{type:'linear',x:0,y:0,x2:0,y2:1,colorStops:[{offset:0,color:colors[0]+'28'},{offset:1,color:colors[0]+'01'}]}}:undefined}))}),keys.some(([k])=>data.trend.some(r=>r[k]!=null)));
  }
  function donut(parent,title,subtitle,items,span=4){
    const {n}=card(parent,title,subtitle,span);const nonzero=items.filter(i=>i.value>0);
    plot(n,title,()=>({tooltip:{...base().tooltip,trigger:'item'},legend:{...base().legend,type:'scroll'},series:[{type:'pie',radius:['54%','72%'],center:['50%','45%'],avoidLabelOverlap:true,itemStyle:{borderRadius:5,borderWidth:4,borderColor:theme().surface},label:{show:false},emphasis:{label:{show:true,formatter:'{b}\\n{d}%',fontSize:13,fontWeight:600}},data:nonzero}]}),nonzero.length>0);
  }
  function ranking(parent){
    const {n}=card(parent,'店铺结算排行','按所选币种 · 已结算净额',4);
    const rows=data.ranking.filter(r=>r.kind==='SETTLEMENT'&&r.amount!=null).sort((a,b)=>Number(b.amount)-Number(a.amount)).slice(0,6).reverse();
    plot(n,'店铺排行',()=>({legend:{show:false},xAxis:axis(),yAxis:{...axis('category'),data:rows.map(r=>shopName(r.shop_id)),axisLabel:{...axis().axisLabel,width:82,overflow:'truncate'}},
      series:[{type:'bar',barMaxWidth:12,data:rows.map(r=>r.amount),itemStyle:{color:'#6a88ed',borderRadius:[0,4,4,0]}}]}),rows.length>0);
  }
  function shopName(id){if(id==='UNASSIGNED')return '未分配店铺';const s=data?.shops?.find(s=>s.id===id);return s?.display_name||s?.name||id;}
  function footnote(parent,text){const n=el('div',null,parent,'fi-note');icon('info',n);el('span',text,n);}
  function overview(parent){
    parent.style.display='grid';parent.style.gap='15px';meta(parent);metricCards(parent);
    const pending=total('PENDING');if(pending)footnote(parent,'TikTok 预计待结算 '+number(pending.amount)+' '+(data.filters.displayCurrency||data.filters.currency)+' · 动态预估，已与最终结算分开统计。');
    const grid=el('div',null,parent,'fi-chart-grid');
    trend(grid,[['SETTLEMENT','结算净额'],['ORDER','订单金额']],'收入与结算趋势','两种业务日期分别统计，不视为同期应收对账');ranking(grid);
    donut(grid,'费用构成','仅展示正向费用；退款与负向调整见明细',data.fees.map(r=>({name:feeName(r.category),value:Number(r.amount)||0})));
    const status=data.statuses.filter(r=>r.kind==='ORDER');donut(grid,'订单状态','订单数分布 · 已取消与待付款单独展示',status.map(r=>({name:statusName(r.status),value:Number(r.records)})));
    trend(grid,[['AD','广告消耗'],['COST','外部成本']],'广告与外部成本','广告消耗按账户日期；成本需补充',4);
    const table=card(grid,'店铺经营明细','从汇总下钻到每一家店铺',12);storeSummary(table.n);
    footnote(parent,data.basis+' 广告充值不是广告消耗，GMV Max 归因收入不是净利润。');
  }
  function storeSummary(parent){
    const ids=[...new Set(data.ranking.map(r=>r.shop_id))];
    table(parent,['店铺','订单数','订单金额','结算净额','平台费用','广告消耗'],ids.map(id=>{
      const rows=data.ranking.filter(r=>r.shop_id===id),get=k=>rows.find(r=>r.kind===k);
      return [shopName(id),number(get('ORDER')?.records),number(get('ORDER')?.amount),number(get('SETTLEMENT')?.amount),number(get('FEE')?.amount),number(get('AD')?.amount)];
    }),'启用财务店铺同步后，经营数据将自动汇总。');
  }
  function table(parent,headers,rows,empty='当前范围暂无记录'){
    const wrap=el('div',null,parent,'fi-table-wrap');wrap.tabIndex=0;wrap.setAttribute('aria-label','可横向滚动的数据表');
    const t=el('table',null,wrap,'fi-table'),tr=el('tr',null,el('thead',null,t));
    headers.forEach(h=>el('th',h,tr).scope='col');const body=el('tbody',null,t);
    rows.forEach(row=>{const tr=el('tr',null,body);row.forEach(value=>{const td=el('td',null,tr);if(value instanceof Node)td.append(value);else td.textContent=value??'—';});});
    if(!rows.length)el('p',empty,parent,'fi-empty');return t;
  }
  function badge(status){const n=el('span',states[status]||status||'—',null,'fi-status');n.classList.toggle('success',['SUCCESS','SETTLED','MATCHED','ACTIVE'].includes(status));n.classList.toggle('error',['ERROR','DIFFERENCE','TOKEN_EXPIRED'].includes(status));n.classList.toggle('running',['RUNNING','SYNCING','READY','PARTIAL'].includes(status));return n;}
  function statusName(status){return ({UNPAID:'待付款',CANCELLED:'已取消',COMPLETED:'已完成',DELIVERED:'已送达',AWAITING_SHIPMENT:'待发货',IN_TRANSIT:'运输中',READY_TO_SHIP:'待发货',SHIPPED:'已发货'})[status]||states[status]||status;}
  function feeName(key){return ({commission_fee:'佣金',service_fee:'服务费',seller_transaction_fee:'支付手续费',final_shipping_fee:'物流费用',reverse_shipping_fee:'逆向物流',order_ams_commission_fee:'达人佣金',campaign_fee:'活动费用',withholding_tax:'代扣税',fbs_fee:'仓储履约',seller_order_processing_fee:'订单处理费',pay_per_sale:'成交广告费'})[key]||key||'其他';}
  async function recordView(parent,run){
    parent.style.display='grid';parent.style.gap='15px';
    const allowedKinds=view==='orders'?['ORDER','REFUND']:view==='settlements'?['SETTLEMENT','BILL','PENDING']:['FEE','COST'];
    if(!allowedKinds.includes(selectedKind))selectedKind=allowedKinds[0];
    const pills=el('div',null,parent,'fi-tabs');allowedKinds.forEach(k=>button(kinds[k],pills,()=>{selectedKind=k;page=1;load();}).setAttribute('aria-pressed',String(k===selectedKind)));
    meta(parent);
    const grid=el('div',null,parent,'fi-chart-grid');trend(grid,allowedKinds.map(k=>[k,kinds[k]]),routes[view]+'趋势','按业务日期及当前展示币种统计',12);
    const result=await request('/records?'+qs({kind:selectedKind,page}));if(run!==generation)return;
    const {n,h}=card(parent,kinds[selectedKind]+'明细','当前筛选 · 共 '+number(result.total)+' 条',12);el('span',data.filters.currency||'原币',h,'fi-tag');
    const rows=result.items.map(r=>{
      const link=button(r.external_id,null,()=>detailDialog(r.id));const row=[r.record_date,shopName(r.shop_id),link,r.order_id||'—',feeName(r.category),badge(r.status),r.currency,number(r.amount)];if(data.fx)row.push(number(r.cny_amount));return row;
    });
    table(n,['业务日期','店铺','记录编号','订单编号','类型','状态','原币','原币金额',...(data.fx?['折算人民币']:[])],rows);
    const bar=el('div',null,n,'fi-pagebar');el('span',`第 ${result.page} 页 · 每页 ${result.pageSize} 条`,bar);const controls=el('div',null,bar,'fi-actions');
    button('上一页',controls,()=>{page--;load();}).disabled=page<=1;
    button('下一页',controls,()=>{page++;load();}).disabled=page*result.pageSize>=result.total;
    footnote(parent,'未知金额显示为 —；退款仅完成后计入。预计待结算为 TikTok 官方预估，完整分页同步后移除已结算项。平台费用已含于净结算，不重复扣减。');
  }
  async function reconciliation(parent,run){
    meta(parent);const result=await request('/reconciliation?'+qs());if(run!==generation)return;
    parent.style.display='grid';parent.style.gap='15px';
    const stats=el('div',null,parent,'fi-mini-stats');
    for(const [key,label]of[['MATCHED','核对一致'],['DIFFERENCE','存在差异'],['MISSING','待补齐 / 同步中']]){const n=el('div',null,stats);el('span',label,n);el('strong',result.items.filter(r=>r.result===key||(key==='MISSING'&&r.result==='PARTIAL')).length,n);el('span','当前展示账单',n);}
    const {n}=card(parent,'平台结算账单核对','账单金额 ↔ 同账单、同币种的结算交易明细 · 最多展示 1,000 张账单',12);
    table(n,['账单日期','店铺','账单编号','来源','原币','账单金额','明细合计','原币差异',...(data.fx?['人民币差异']:[]),'结果'],result.items.map(r=>[r.record_date,shopName(r.shop_id),r.external_id,r.source==='API'?'官方接口':'导入账单',r.currency,number(r.amount),number(r.detail_amount),number(r.difference),...(data.fx?[number(r.cny_difference)]:[]),badge(r.result)]),'暂无可核对账单。连接店铺同步结算数据，或导入平台结算账单。');
    footnote(parent,'按账单编号与币种精确匹配。缺少数据不会标记一致；这里核对平台结算，不确认银行到账。');
  }
  async function shopView(parent,run){
    const res=await request('/shops');if(run!==generation)return;
    data={...(data||{}),shops:res.items,sync:res.sync};parent.style.display='grid';parent.style.gap='15px';
    const enabledCount=res.items.filter(r=>Number(r.enabled)===1||r.enabled===true).length;
    const mini=el('div',null,parent,'fi-mini-stats');
    for(const [value,label,sub]of[[res.items.length,'可连接店铺','复用现有店铺授权'],[enabledCount,'已开启财务同步','订单 / 退款 / 结算'],['10 分钟','订单与退款同步','结算与广告每小时同步']]){const n=el('div',null,mini);el('span',label,n);el('strong',value,n);el('span',sub,n);}
    const {n}=card(parent,'财务店铺','授权与商品运营共用，财务开关、数据和同步进度独立管理',12);
    table(n,['店铺 / 平台','国家','币种 / 时区','授权','财务同步','最近成功同步','操作'],res.items.map(shop=>{
      const name=el('div',null,null,'fi-platform'+(shop.platform==='SHOPEE'?' shopee':''));el('i',shop.platform==='SHOPEE'?'S':'TK',name);const c=el('div',null,name);el('span',shop.display_name||shop.name,c,'fi-shop-name');el('small',shop.external_shop_id,c);
      const currency=el('div',shop.currency||'待配置');el('small',shop.timezone,currency);
      const channels=res.sync.filter(r=>r.shop_id===shop.id);const sync=el('div');
      if(channels.length)channels.forEach(state=>{const line=el('div',null,sync,'fi-progress');line.append(badge(state.status));el('span',kinds[state.channel]||state.channel,line);if(state.error_message){line.title=state.error_message;el('small',state.error_message,sync);}});
      else el('span','尚未开启',sync,'fi-muted');
      const controls=el('div',null,null,'fi-shop-controls');button('配置',controls,()=>shopDialog(shop));button('立即同步',controls,async b=>{b.disabled=true;try{const message=await request('/shops/'+encodeURIComponent(shop.id)+'/sync',{method:'POST'});notify(message);setTimeout(()=>{if(active)load();},1000);}finally{b.disabled=false;}}).disabled=!shop.enabled;
      return [name,shop.region||'—',currency,badge(shop.authorization_status),sync,shop.last_success_at?new Date(shop.last_success_at).toLocaleString('zh-CN'):'尚未完成',controls];
    }),'还没有授权店铺。点击右上角「连接店铺」，授权后开启财务同步。');
    footnote(parent,'首次分批补齐最近 90 天可用数据；同步失败自动退避重试。暂停财务同步不影响店铺发布功能。');
  }
  async function adsView(parent,run){
    parent.style.display='grid';parent.style.gap='15px';meta(parent);
    const a=el('div',null,parent,'fi-actions');action('连接广告账户','plus',a,()=>adsDialog(),'fi-primary');
    const grid=el('div',null,parent,'fi-chart-grid');trend(grid,[['AD','广告总消耗']],'广告费用趋势','广告账户报表金额 · GMV Max 分析不重复计入总消耗',8);
    const t=total('AD'),stats=card(grid,'投放概览','当前币种与筛选',4);
    table(stats.n,['指标','数值'],[['广告消耗',number(t?.amount)],['曝光量',number(t?.impressions)],['点击量',number(t?.clicks)],['点击率',t?.impressions?number(Number(t.clicks)/Number(t.impressions)*100)+'%':'—'],['平均点击成本',t?.clicks?number(Number(t.amount)/Number(t.clicks)):'—']]);
    const gmv=total('AD_GMV');const gmvCard=card(parent,'GMV Max 专项分析','独立口径，不与账户广告消耗相加；归因收入含自然流量影响',12);table(gmvCard.n,['专项消耗','归因收入','SKU 订单数','归因 ROI'],[[number(gmv?.amount),number(gmv?.revenue),number(gmv?.conversions),gmv?.amount?number(Number(gmv.revenue)/Number(gmv.amount)):'—']]);
    const accounts=await request('/ads');if(run!==generation)return;
    const {n}=card(parent,'TikTok 广告账户','单独验证广告授权；仅专属一个店铺的账户才绑定店铺',12);
    table(n,['广告账户','归属','币种','状态','最近同步','操作'],accounts.map(account=>{
      const controls=el('div',null,null,'fi-actions');button(account.enabled?'暂停':'启用',controls,async()=>{await request('/ads/'+account.advertiser_id,json('PUT',{enabled:!account.enabled}));load();});button('更新授权',controls,()=>adsDialog(account));
      const status=el('div');status.append(badge(account.status));if(account.error_message)el('small',account.error_message,status);if(account.gmv_store_id)el('small','GMV Max · '+(states[account.gmv_status]||account.gmv_status)+(account.gmv_error?' · '+account.gmv_error:''),status);
      return [account.name+' · '+account.advertiser_id,account.shop_id?shopName(account.shop_id):'未分配店铺',account.currency,status,account.last_success_at?new Date(account.last_success_at).toLocaleString('zh-CN'):'尚未完成',controls];
    }),'连接已授权的 TikTok 广告账户后自动同步，也可以导入广告费用。');
    footnote(parent,'广告归因收入与平台结算收入口径不同；暂不据此计算净利润。未分配账户费用仅计入全店汇总，不强行分摊。');
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
    const d=dialog('配置财务店铺');el('p',shop.display_name||shop.name,d.body,'fi-muted');
    const currency=field(d.body,'店铺结算币种（如 PHP、THB）','text',shop.currency||'');
    const zone=field(d.body,'店铺业务时区','text',shop.timezone||'Asia/Shanghai');
    const enabled=select(d.body,'自动同步',[['true','开启'],['false','暂停']],String(!!shop.enabled),()=>{});
    d.submit('保存配置',async()=>{await request('/shops/'+shop.id,json('PUT',{currency:currency.value.trim().toUpperCase(),timezone:zone.value.trim(),enabled:enabled.value==='true'}));d.d.close();load();});
  }
  function authorizeDialog(){
    const d=dialog('连接财务店铺');const platform=select(d.body,'平台',[['SHOPEE','Shopee'],['TIKTOK_SHOP','TikTok Shop']],'SHOPEE',()=>{});
    const market=select(d.body,'授权市场',[['ROW','东南亚 / 全球（非美国）'],['US','美国']],'ROW',()=>{});
    el('p','授权完成后，回到财务店铺管理开启同步。已有店铺无需重复授权。',d.body,'fi-muted');
    d.submit('获取官方授权链接',async()=>{const result=await request('/shops/authorize',json('POST',{platform:platform.value,market:market.value}));
      const url=new URL(result.authorizationUrl);if(url.protocol!=='https:')throw Error('平台授权地址无效');
      const link=el('a','前往官方授权页面',d.body,'fi-action-link');link.href=url.href;link.target='_blank';link.rel='noopener noreferrer';});
  }
  function adsDialog(account={}){
    const d=dialog('连接 TikTok 广告账户');
    const id=field(d.body,'广告账户 ID','text',account.advertiser_id||'');id.disabled=!!account.advertiser_id;
    const shop=select(d.body,'专属店铺',[['','暂不分配（跨店账户）'],...(data.shops||[]).filter(s=>s.platform==='TIKTOK_SHOP').map(s=>[s.id,s.display_name||s.name])],account.shop_id||'',()=>{});shop.disabled=!!account.advertiser_id;
    const gmvStore=field(d.body,'GMV Max 店铺 ID（可选，来自广告平台）','text',account.gmv_store_id||'');
    const token=field(d.body,'已授权的 Access Token','password');token.autocomplete='off';
    el('p','使用 TikTok for Business 应用授权的令牌，需有账户查看及报表权限。令牌加密保存，保存后不会回显。店铺授权不能替代广告授权。',d.body,'fi-muted');
    d.submit('验证并连接',async()=>{await request('/ads',json('POST',{advertiserId:id.value.trim(),shopId:shop.value,accessToken:token.value.trim(),gmvStoreId:gmvStore.value.trim()}));token.value='';d.d.close();load();});
  }
  async function detailDialog(id){const d=dialog('财务记录详情');try{const row=await request('/records/'+encodeURIComponent(id)+'?'+qs());const dl=el('dl',null,d.body);
    const labels={record_date:'业务日期',external_id:'记录编号',order_id:'订单编号',statement_id:'账单编号',currency:'币种',amount:'原币金额',cny_amount:'折算人民币',cny_rate:'参考汇率',source:'来源',status:'状态',category:'类别',updated_at:'最后同步'};
    for(const [key,label]of Object.entries(labels)){if(key.startsWith('cny_')&&!row.fx)continue;el('dt',label,dl);el('dd',row[key]??'—',dl);}if(row.fx)el('p',`${row.fx.provider} · ${row.fx.time} · 参考折算`,d.body,'fi-muted');}catch(e){d.feedback.textContent=e.message;}}
  function exportDialog(){
    const d=dialog('导出财务报表');el('p',`${filters.start} 至 ${filters.end} · 当前店铺、平台和币种筛选 · 全部符合条件的记录`,d.body,'fi-muted');
    const type=select(d.body,'导出类型',[['','全部财务明细'],...Object.entries(kinds)],'',()=>{});
    const format=select(d.body,'文件格式',[['xlsx','Excel（含结算对账与统计口径）'],['csv','CSV 明细']],'xlsx',()=>{});
    d.submit('下载报表',async()=>{
      const response=await fetch('/api/v1/finance/export?'+qs({kind:type.value,format:format.value}),{credentials:'same-origin',cache:'no-store'});
      if(!response.ok){let message='导出失败';try{message=(await response.json()).message||message;}catch{}throw Error(message);}
      const url=URL.createObjectURL(await response.blob());const a=el('a',null,d.body);a.href=url;a.download='财务报表-'+filters.start+'.'+format.value;a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),10000);d.d.close();
    });
  }
  function importDialog(){
    const d=dialog('导入平台账单 / 费用');
    const shop=select(d.body,'对应店铺',(data?.shops||[]).map(s=>[s.id,s.display_name||s.name]),filters.shopId||data?.shops?.[0]?.id||'',()=>{});
    const kind=select(d.body,'数据类型',[['BILL','结算账单（用于对账）'],['COST','外部成本'],['AD','广告费用（未启用接口的店铺）']],'BILL',()=>{});
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
  async function load(){
    if(!active||!allowed())return;const run=++generation;clearTimeout(refreshTimer);
    const content=shell();
    try{
      if(view!=='shops'){const response=await request('/overview?'+qs());if(run!==generation||!active)return;data=response;filters.currency=data.filters.currency||'';filters.fxSnapshot=data.fx?.id||'';
        // Build filters after authorized shop/currency options have arrived.
        content.remove();const body=shell();await ensureCharts();if(run!==generation||!active)return;
        if(view==='overview')overview(body);else if(view==='reconciliation')await reconciliation(body,run);else if(view==='ads')await adsView(body,run);else await recordView(body,run);
      }else await shopView(content,run);
      if(run!==generation||!active)return;
      const errors=(data?.sync||[]).filter(s=>s.status==='ERROR').length;
      notify(errors?`${errors} 项同步需要处理，可在财务店铺管理查看权限或连接问题。`:'');
      observer=new ResizeObserver(()=>charts.forEach(c=>c.resize()));observer.observe(root);
      themeObserver=new MutationObserver(()=>charts.forEach(c=>c.setOption({...base(),...c.financeBuilder()},true)));themeObserver.observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});
      if(view==='shops')refreshTimer=setTimeout(load,30000);
    }catch(e){if(run===generation){cleanup();notify(e.message||'财务数据暂时无法加载',true);}}
  }
  globalThis.FinanceWorkspace={allowed,enter(route){if(!allowed())return;view=routes[route]?route:'overview';active=true;page=1;load();},leave,reset};
  window.addEventListener('heyi:company-reset',reset);
})();
