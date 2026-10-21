/* Finance workspace. Tabler-inspired density; local ECharts; no synthetic production data. */
(() => {
  'use strict';
  const root=document.getElementById('finance-page');if(!root)return;
  const colors=['#4263eb','#53a8c9','#9a85d8','#e4ac62','#e7828c','#8198be'];
  const routes={overview:'财务总览',live:'实时大屏',settlements:'结算中心',reconciliation:'对账中心',orders:'订单与退款',fees:'费用与调整',ads:'广告分析',shops:'财务店铺管理'};
  const kinds={ORDER:'订单',REFUND:'退款',SETTLEMENT:'结算明细',BILL:'结算账单',FEE:'平台费用',AD:'广告费用',AD_GMV:'GMV Max 分析',COST:'外部成本',PENDING:'待结算'};
  const states={READY:'待同步',RUNNING:'同步中',SYNCING:'历史回补中',SUCCESS:'已同步',ERROR:'同步异常',ACTIVE:'已授权',TOKEN_EXPIRED:'授权过期',DISABLED:'已断开',SETTLED:'已结算',IMPORTED:'已导入',REPORTED:'平台报表',PARTIAL:'明细同步中',UNSETTLED:'预计待结算',MATCHED:'核对一致',MISSING:'缺少明细',DIFFERENCE:'金额有差异'};
  const paths={chart:'M4 19h16M7 15v-4m5 4V5m5 10V8',download:'M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4',refresh:'M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-2l2 3M18 17a7 7 0 0 1-12 2l-2-3',shop:'M3 10h18l-2-7H5zM5 10v11h14V10M9 21v-7h6v7',wallet:'M3 6h18v15H3zM3 6V3h15v3M16 12h5v5h-5z',arrow:'M7 17 17 7M7 7h10v10',refund:'m9 6-5 5 5 5M4 11h10a5 5 0 0 1 0 10',ad:'m3 11 15-6v14L3 14zM6 15v6h4v-5M21 9v6',fee:'M7 3h10l4 4v14H3V7zM8 9h8M8 13h8M8 17h5',info:'M12 11v6M12 7v1M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',plus:'M12 4v16M4 12h16',check:'m5 12 4 4 10-10'};
  let callbackNotice='';const callbackParams=new URLSearchParams(location.search);if(callbackParams.has('financeAuth')){callbackNotice=callbackParams.get('financeMessage')||(callbackParams.get('financeAuth')==='success'?'财务店铺已连接':'财务授权未完成');callbackParams.delete('financeAuth');callbackParams.delete('financeMessage');history.replaceState(null,'',location.pathname+(callbackParams.size?'?'+callbackParams:'')+location.hash);}
  let active=false,generation=0,view='overview',data=null,chartPromise,charts=[],observer,themeObserver,refreshTimer=null,clockTimer=null,page=1,selectedKind='ORDER',mountedView='';
  const dateText=d=>`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
  const today=()=>dateText(new Date()),ago=n=>{const d=new Date();d.setDate(d.getDate()-n);return dateText(d);};
  function dateGranularity(start,end){const days=Math.round((Date.parse(end)-Date.parse(start))/86400000)+1;return days<=45?'day':days<=200?'week':days<=400?'month':days<=900?'quarter':days<=1500?'halfyear':'year';}
  let filters={start:ago(29),end:today(),shopId:'',platform:'',currency:'',granularity:'day',displayCurrency:'ORIGINAL',fxSnapshot:''};
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=String(text);if(cls)n.className=cls;parent?.append(n);return n;};
  function icon(name,parent){const n=document.createElementNS('http://www.w3.org/2000/svg','svg');n.setAttribute('viewBox','0 0 24 24');n.classList.add('fi-svg');n.setAttribute('aria-hidden','true');const path=document.createElementNS(n.namespaceURI,'path');path.setAttribute('d',paths[name]||paths.chart);n.append(path);parent?.append(n);return n;}
  function button(label,parent,fn,cls=''){const n=el('button',label,parent,cls);n.type='button';n.onclick=async event=>{try{await fn(event.currentTarget);}catch(e){notify(e.message,true);}};return n;}
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
  function loadingIndicator(parent){const n=el('span','正在加载所选数据…',parent,'fi-loading');n.hidden=true;n.setAttribute('role','status');n.setAttribute('aria-live','polite');}
  function setLoading(busy){root.setAttribute('aria-busy',String(busy));const n=root.querySelector('.fi-loading');if(n){n.hidden=!busy;n.textContent=view==='shops'?'正在读取店铺同步进度…':view==='live'?'正在读取最新经营数据…':'正在加载所选日期…';n.title=filters.start+' 至 '+filters.end;}}
  function navigate(route){location.hash='finance-'+route;}
  function cleanup(){clearInterval(clockTimer);clockTimer=null;charts.forEach(c=>c.dispose());charts=[];observer?.disconnect();themeObserver?.disconnect();observer=themeObserver=null;mountedView='';}
  function reset(){active=false;generation++;clearTimeout(refreshTimer);cleanup();root.replaceChildren();data=null;page=1;filters={start:ago(29),end:today(),shopId:'',platform:'',currency:'',granularity:'day',displayCurrency:'ORIGINAL',fxSnapshot:''};document.querySelectorAll('.fi-dialog').forEach(d=>d.remove());}
  function leave(){active=false;generation++;clearTimeout(refreshTimer);cleanup();}
  function refreshWhenIdle(){
    if(!active)return;
    if(document.hidden||document.querySelector('.fi-dialog[open]')){refreshTimer=setTimeout(refreshWhenIdle,15000);return;}
    load(false);
  }
  function ensureCharts(){
    if(globalThis.echarts)return Promise.resolve(echarts);
    if(!chartPromise)chartPromise=new Promise(resolve=>{const s=document.createElement('script');const timer=setTimeout(()=>{chartPromise=null;resolve(null);s.remove();},12000);s.src='/vendor/echarts/echarts.min.js?v=6.1.0';s.onload=()=>{clearTimeout(timer);resolve(globalThis.echarts||null);};s.onerror=()=>{clearTimeout(timer);chartPromise=null;resolve(null);};document.head.append(s);});
    return chartPromise;
  }
  function select(parent,label,options,value,onchange){const wrap=el('label',null,parent);wrap.dataset.updateKey='select:'+label;el('span',label,wrap);const n=el('select',null,wrap);for(const [v,name] of options){el('option',name,n).value=v;}n.value=value||'';n.setAttribute('aria-label',label);n.onchange=e=>onchange(e.currentTarget.value);return n;}
  function dateInput(parent,label,value,key){const n=el('input',null,parent);n.type='date';n.setAttribute('value',value);n.setAttribute('aria-label',label);n.max=today();n.onchange=e=>{const value=e.currentTarget.value;if(!/^\d{4}-\d{2}-\d{2}$/.test(value))return;const next={...filters,[key]:value};if(next.start>next.end){notify('开始日期不能晚于结束日期',true);return;}filters[key]=value;filters.granularity=dateGranularity(filters.start,filters.end);page=1;load();};return n;}
  function shell(root){
    root.className='workspace-body finance-workspace';
    const head=el('header',null,root,'fi-head'),copy=el('div',null,head);
    el('span','FINANCE / ANALYTICS',copy,'fi-eyebrow');el('h1',routes[view],copy,'fi-title');
    el('p',view==='shops'?'统一管理财务店铺、广告账户与数据同步。':'看清每一笔收入、每一项费用，与每家店铺的经营表现。',copy,'fi-subtitle');
    const actions=el('div',null,head,'fi-actions');
    loadingIndicator(actions);
    if(view!=='shops')action('财务店铺管理','shop',actions,()=>navigate('shops'));
    action('刷新','refresh',actions,()=>{filters.fxSnapshot='';return load();});
    if(view==='shops'){action('连接店铺','plus',actions,()=>authorizeDialog(),'fi-primary');}
    else{action('字段设置','fee',actions,()=>fieldsDialog(currentFieldView()));action('导入账单','plus',actions,()=>importDialog());action('导出报表','download',actions,()=>exportDialog(),'fi-primary');}
    if(view!=='shops'){
      const bar=el('div',null,root,'fi-filters');
      select(bar,'平台',[['','全部平台'],['SHOPEE','Shopee'],['TIKTOK_SHOP','TikTok Shop']],filters.platform,v=>{filters.platform=v;filters.shopId='';page=1;load();});
      select(bar,'店铺',[['','全部店铺'],...(data?.shops||[]).filter(s=>!filters.platform||s.platform===filters.platform).map(s=>[s.id,s.display_name||s.name])],filters.shopId,v=>{filters.shopId=v;page=1;load();});
      select(bar,'原币',[['',filters.displayCurrency==='CNY'?'全部币种':'自动选择原币'],...(data?.currencies||[]).map(c=>[c,c])],filters.currency,v=>{filters.currency=v;page=1;load();});
      const currencyToggle=el('div',null,bar,'fi-pills');currencyToggle.setAttribute('aria-label','金额展示币种');
      for(const [key,label] of [['ORIGINAL','原币'],['CNY','人民币 ¥']])button(label,currencyToggle,()=>{filters.displayCurrency=key;filters.currency='';filters.fxSnapshot='';page=1;load();}).setAttribute('aria-pressed',String(filters.displayCurrency===key));
      const date=el('div',null,bar,'fi-date-range');dateInput(date,'开始日期',filters.start,'start');el('span','—',date);dateInput(date,'结束日期',filters.end,'end');
    }
    const state=el('div','正在读取财务数据…',root,'fi-state');state.setAttribute('role','status');state.setAttribute('aria-live','polite');
    return el('div',null,root,'fi-content');
  }
  function meta(parent){
    const row=el('div',null,parent,'fi-meta'),left=el('div',null,row);el('i',null,left,'fi-dot');el('span','当前口径 · ',left);el('span',data?.filters?.displayCurrency||data?.filters?.currency||'尚无币种数据',left,'fi-currency');
    if(data?.fx){el('span',' · 参考汇率 '+new Date(data.fx.time).toLocaleDateString('zh-CN')+' · ',left);const source=el('a',data.fx.provider,left);source.href='https://www.exchangerate-api.com';source.target='_blank';source.rel='noopener noreferrer';source.style.color='inherit';el('span',' · 按同一参考汇率折算',left);}
    else el('span',' · 原币独立核算',left);
    el('span',' · '+filters.start+' 至 '+filters.end,left);
  }
  function total(kind){return data?.totals?.find(r=>r.kind===kind);}
  function sum(kind){return total(kind)?.amount;}
  function metricCards(parent){
    const cards=el('div',null,parent,'fi-metrics');
    const definitions=[['SETTLEMENT','已结算净额','平台最终结算口径','wallet','settlements'],['ORDER','订单金额','已支付且未取消订单','shop','orders'],['REFUND','成功退款','待退款不计入金额','refund','orders'],['FEE','平台费用','结算净额已扣除此费用','fee','fees'],['AD','广告消耗','TikTok 账户报表 / 导入','ad','ads'],['COST','外部成本','采购、头程等补充成本','chart','fees']];
    definitions.forEach(([kind,label,description,symbol,route],i)=>{
      const record=total(kind),n=button('',cards,()=>{selectedKind=kind;navigate(route);},'fi-metric'+(i===0?' is-featured':''));
      n.style.setProperty('--accent',colors[i]);const title=el('div',null,n,'fi-metric-label');el('span',label,title);icon(symbol,el('span',null,title,'fi-metric-icon'));
      const value=el('div',null,n,'fi-metric-value');el('strong',number(record?.amount),value);el('small',data.filters.displayCurrency||data.filters.currency||'',value);
      const foot=el('div',null,n,'fi-metric-note');el('span',description,foot);icon('arrow',foot);
    });
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
      const tick=()=>{const clock=root.querySelector('.fi-live-clock');if(clock)clock.textContent=new Date().toLocaleString('zh-CN',{hour12:false});};
      tick();clockTimer=setInterval(tick,1000);
    }
  }
  function trend(parent,keys,title,subtitle,span=8){
    const {n}=card(parent,title,subtitle,span);
    plot(n,title,()=>({xAxis:{...axis('category'),data:data.trend.map(r=>r.period),boundaryGap:false},yAxis:axis(),legend:{...base().legend,data:keys.map(([k,label])=>label)},
      series:keys.map(([key,name],i)=>({name,type:'line',data:data.trend.map(r=>r[key]??null),smooth:false,connectNulls:true,showSymbol:data.trend.filter(r=>r[key]!=null).length<2,
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
    if(data.sync.some(s=>s.channel!=='ORDER_LIVE'&&s.window_start))
      footnote(parent,'历史回补尚未完成，所选期间的金额与笔数可能继续补齐。可在「财务店铺管理」查看各项同步进度。');
    const pending=total('PENDING');if(pending)footnote(parent,'TikTok 预计待结算 '+number(pending.amount)+' '+(data.filters.displayCurrency||data.filters.currency)+' · 动态预估，已与最终结算分开统计。');
    const grid=el('div',null,parent,'fi-chart-grid');
    trend(grid,[['SETTLEMENT','结算净额'],['ORDER','订单金额']],'收入与结算趋势','按各自业务日期统计；空缺日期连线，不补造金额');ranking(grid);
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
  function table(parent,headers,rows,empty='当前范围暂无记录',keys=[]){
    const wrap=el('div',null,parent,'fi-table-wrap');wrap.tabIndex=0;wrap.setAttribute('aria-label','可横向滚动的数据表');
    const t=el('table',null,wrap,'fi-table'),tr=el('tr',null,el('thead',null,t));
    headers.forEach(h=>el('th',h,tr).scope='col');const body=el('tbody',null,t);
    rows.forEach((row,i)=>{const tr=el('tr',null,body);if(keys[i]!=null)tr.dataset.updateKey=String(keys[i]);row.forEach(value=>{const td=el('td',null,tr);if(value instanceof Node)td.append(value);else td.textContent=value??'—';});});
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
    renderFieldsTable(n,result.items,result.fieldSettings);action('字段设置','fee',h,()=>fieldsDialog(selectedKind,result.fieldSettings));
    const bar=el('div',null,n,'fi-pagebar');el('span',`第 ${result.page} 页 · 每页 ${result.pageSize} 条`,bar);const controls=el('div',null,bar,'fi-actions');
    button('上一页',controls,()=>{page--;load();}).disabled=page<=1;
    button('下一页',controls,()=>{page++;load();}).disabled=page*result.pageSize>=result.total;
    footnote(parent,'未知金额显示为 —；退款仅完成后计入。预计待结算为 TikTok 官方预估，完整分页同步后移除已结算项。平台费用已含于净结算，不重复扣减。');
  }
  function reconciliation(parent,result){
    meta(parent);
    parent.style.display='grid';parent.style.gap='15px';
    const stats=el('div',null,parent,'fi-mini-stats');
    for(const [key,label]of[['MATCHED','核对一致'],['DIFFERENCE','存在差异'],['MISSING','待补齐 / 同步中']]){const n=el('div',null,stats);el('span',label,n);el('strong',result.items.filter(r=>r.result===key||(key==='MISSING'&&r.result==='PARTIAL')).length,n);el('span','本页账单',n);}
    const {n,h}=card(parent,'平台结算账单核对','账单金额 ↔ 同账单、同币种的结算交易明细 · 共 '+number(result.total)+' 张账单',12);
    action('字段设置','fee',h,()=>fieldsDialog('RECONCILIATION',result.fieldSettings));
    renderFieldsTable(n,result.items,result.fieldSettings,'暂无可核对账单。连接财务店铺同步结算数据，或导入平台结算账单。');
    const bar=el('div',null,n,'fi-pagebar');el('span',`第 ${result.page} 页 · 每页 ${result.pageSize} 条`,bar);const controls=el('div',null,bar,'fi-actions');
    button('上一页',controls,()=>{page--;load();}).disabled=page<=1;
    button('下一页',controls,()=>{page++;load();}).disabled=page*result.pageSize>=result.total;
    footnote(parent,'按账单编号与币种精确匹配。缺少数据不会标记一致；这里核对平台结算，不确认银行到账。');
  }
  async function shopView(parent,run){
    const res=await request('/shops');if(run!==generation)return;
    data={...(data||{}),shops:res.items,sync:res.sync};parent.style.display='grid';parent.style.gap='15px';
    const enabledCount=res.items.filter(r=>Number(r.enabled)===1||r.enabled===true).length;
    const mini=el('div',null,parent,'fi-mini-stats');
    for(const [value,label,sub]of[[res.items.length,'独立授权店铺','财务专用配置'],[enabledCount,'已开启财务同步','最新订单与历史回补独立运行'],['约 1 分钟','最新订单同步目标','退款每 10 分钟 · 结算与广告每小时']]){const n=el('div',null,mini);el('span',label,n);el('strong',value,n);el('span',sub,n);}
    const {n}=card(parent,'财务店铺','在这里独立授权、配置和同步财务数据',12);
    table(n,['店铺 / 平台','国家','币种 / 时区','授权','财务同步','最近成功同步','操作'],res.items.map(shop=>{
      const name=el('div',null,null,'fi-platform'+(shop.platform==='SHOPEE'?' shopee':''));el('i',shop.platform==='SHOPEE'?'S':'TK',name);const c=el('div',null,name);el('span',shop.display_name||shop.name,c,'fi-shop-name');el('small',shop.external_shop_id,c);
      const currency=el('div',shop.currency||'待配置');el('small',shop.timezone,currency);
      const channels=res.sync.filter(r=>r.shop_id===shop.id);const sync=el('div');
      if(channels.length)channels.sort((a,b)=>(a.channel==='ORDER_LIVE'?-1:0)-(b.channel==='ORDER_LIVE'?-1:0)).forEach(state=>{
        const line=el('div',null,sync,'fi-progress');line.dataset.updateKey=state.channel;line.append(badge(state.channel==='ORDER_LIVE'&&state.status==='SYNCING'?'RUNNING':state.status));
        el('span',state.channel==='ORDER_LIVE'?'最新订单':state.channel==='ORDER'?'历史订单':kinds[state.channel]||state.channel,line);
        if(state.window_start&&state.window_end){
          const day=stamp=>new Date(Number(stamp)*1000).toLocaleDateString('zh-CN',{timeZone:shop.timezone||'Asia/Shanghai'});
          el('small','正在同步 '+day(state.window_start)+' — '+day(Number(state.window_end)-1),line);
        }else if(state.channel==='ORDER_LIVE'&&state.last_success_at)el('small','已同步至 '+new Date(state.last_success_at).toLocaleTimeString('zh-CN',{hour12:false}),line);
        if(state.error_message){line.title=state.error_message;el('small',state.error_message,line);}
      });
      else el('span','尚未开启',sync,'fi-muted');
      const controls=el('div',null,null,'fi-shop-controls');button('配置',controls,()=>shopDialog(shop));button('立即同步',controls,async b=>{b.disabled=true;try{const message=await request('/shops/'+encodeURIComponent(shop.id)+'/sync',{method:'POST'});notify(message);setTimeout(()=>{if(active)load();},1000);}finally{b.disabled=false;}}).disabled=!shop.enabled;
      button('移除',controls,()=>removeShopDialog(shop));
      return [name,shop.region||'—',currency,badge(shop.authorization_status),sync,shop.last_success_at?new Date(shop.last_success_at).toLocaleString('zh-CN'):'尚未完成',controls];
    }),'尚未配置财务店铺。点击右上角「连接店铺」独立授权，再开启同步。',res.items.map(s=>s.id));
    footnote(parent,'首次分批补齐最近 90 天可用数据，最新订单独立持续同步；平台回传、分页与多店排队会有延迟，失败自动退避重试。暂停财务同步不影响店铺发布功能。');
    await adAccountSettings(parent,run);
  }
  async function adAccountSettings(parent,run){
    const accounts=await request('/ads');if(run!==generation)return;
    const {n,h}=card(parent,'广告账户','TikTok 广告独立授权；仅专属一个店铺的账户才绑定店铺',12);
    const actions=el('div',null,h,'fi-actions');
    button('广告应用配置',actions,()=>adApplicationDialog());
    action('连接广告账户','plus',actions,()=>adsDialog(),'fi-primary');
    table(n,['广告账户','归属','币种','状态','最近同步','操作'],accounts.map(account=>{
      const controls=el('div',null,null,'fi-actions');button(account.enabled?'暂停':'启用',controls,async()=>{await request('/ads/'+account.advertiser_id,json('PUT',{enabled:!account.enabled}));load();});button('更新授权',controls,()=>adsDialog(account));
      const status=el('div');status.append(badge(account.status));if(account.error_message)el('small',account.error_message,status);if(account.gmv_store_id)el('small','GMV Max · '+(states[account.gmv_status]||account.gmv_status)+(account.gmv_error?' · '+account.gmv_error:''),status);
      return [account.name+' · '+account.advertiser_id,account.shop_id?shopName(account.shop_id):'未分配店铺',account.currency,status,account.last_success_at?new Date(account.last_success_at).toLocaleString('zh-CN'):'尚未完成',controls];
    }),'尚未连接广告账户。点击「连接广告账户」完成独立授权，广告数据将在「广告分析」展示。',accounts.map(a=>a.advertiser_id));
  }
  async function adsView(parent,run){
    parent.style.display='grid';parent.style.gap='15px';meta(parent);
    const grid=el('div',null,parent,'fi-chart-grid');trend(grid,[['AD','广告总消耗']],'广告费用趋势','广告账户报表金额 · GMV Max 分析不重复计入总消耗',8);
    const t=total('AD'),stats=card(grid,'投放概览','当前币种与筛选',4);
    table(stats.n,['指标','数值'],[['广告消耗',number(t?.amount)],['曝光量',number(t?.impressions)],['点击量',number(t?.clicks)],['点击率',t?.impressions?number(Number(t.clicks)/Number(t.impressions)*100)+'%':'—'],['平均点击成本',t?.clicks?number(Number(t.amount)/Number(t.clicks)):'—']]);
    const gmv=total('AD_GMV');const gmvCard=card(parent,'GMV Max 专项分析','独立口径，不与账户广告消耗相加；归因收入含自然流量影响',12);table(gmvCard.n,['专项消耗','归因收入','SKU 订单数','归因 ROI'],[[number(gmv?.amount),number(gmv?.revenue),number(gmv?.conversions),gmv?.amount?number(Number(gmv.revenue)/Number(gmv.amount)):'—']]);
    const detailKinds=el('div',null,parent,'fi-tabs');if(!['AD','AD_GMV'].includes(selectedKind))selectedKind='AD';
    for(const k of ['AD','AD_GMV'])button(kinds[k]+'明细',detailKinds,()=>{selectedKind=k;page=1;load();}).setAttribute('aria-pressed',String(k===selectedKind));
    const records=await request('/records?'+qs({kind:selectedKind,page}));if(run!==generation)return;
    const detail=card(parent,kinds[selectedKind]+'明细','当前筛选 · 共 '+number(records.total)+' 条',12);action('字段设置','fee',detail.h,()=>fieldsDialog(selectedKind,records.fieldSettings));renderFieldsTable(detail.n,records.items,records.fieldSettings);
    const pager=el('div',null,detail.n,'fi-pagebar');el('span',`第 ${records.page} 页 · 每页 ${records.pageSize} 条`,pager);const controls=el('div',null,pager,'fi-actions');
    button('上一页',controls,()=>{page--;load();}).disabled=page<=1;button('下一页',controls,()=>{page++;load();}).disabled=page*records.pageSize>=records.total;
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
    el('p','为财务模块单独连接店铺。授权完成后在此开启同步，商品运营中的店铺不会自动加入。',d.body,'fi-muted');
    d.submit('生成授权链接',async()=>{const result=await request('/shops/authorize',json('POST',{platform:platform.value,market:market.value}));authorizationLink(d,result);});
  }
  function authorizationLink(d,result){
    const url=new URL(result.authorizationUrl);if(url.protocol!=='https:')throw Error('平台授权地址无效');
    d.body.querySelector('.fi-authorization-link')?.remove();
    const wrap=el('div',null,d.body,'fi-authorization-link');
    const input=field(wrap,'官方授权链接','text',url.href);input.readOnly=true;
    button('复制授权链接',wrap,async b=>{await copyValue(input);b.textContent='已复制链接';});
    el('p','将链接发给账户管理员，在浏览器打开并完成授权。授权成功后会回到财务店铺管理。'+(result.expiresAt?' 有效至 '+new Date(result.expiresAt).toLocaleString('zh-CN')+'。':''),wrap,'fi-muted');
    const link=el('a','在当前设备打开授权',wrap,'fi-action-link');link.href=url.href;link.target='_blank';link.rel='noopener noreferrer';
  }
  async function copyValue(input){
    try{await navigator.clipboard.writeText(input.value);}
    catch{input.focus();input.select();if(!document.execCommand('copy'))throw Error('请选中链接后手动复制');}
  }
  async function adApplicationDialog(){
    const d=dialog('TikTok 广告应用配置');
    try{
      const config=await request('/ads/application');if(!d.d.isConnected)return;
      const id=field(d.body,'广告应用 App ID','text',config.appId||'');
      const secret=field(d.body,'App Secret'+(config.configured?'（不修改可留空）':''),'password');secret.autocomplete='new-password';
      const callback=field(d.body,'请在广告开放平台登记此回调地址','text',config.callbackUrl);callback.readOnly=true;
      button('复制回调地址',d.body,async b=>{await copyValue(callback);b.textContent='已复制回调地址';});
      el('p','填写 TikTok for Business 广告应用信息，并开通账户查看与报表权限。配置按当前账号独立保存；密钥加密保存，不回显。',d.body,'fi-muted');
      d.submit('保存广告应用',async()=>{await request('/ads/application',json('PUT',{appId:id.value.trim(),appSecret:secret.value.trim()}));secret.value='';d.d.close();load();});
    }catch(e){d.feedback.textContent=e.message;}
  }
  async function adsDialog(account={}){
    const d=dialog(account.advertiser_id?'更新广告授权':'连接 TikTok 广告账户');
    let config;try{config=await request('/ads/application');}catch(e){d.feedback.textContent=e.message;return;}
    if(!d.d.isConnected)return;
    if(!config.configured){
      el('p','首次连接需先配置 TikTok for Business 广告应用。配置完成后即可生成链接，由广告账户管理员授权。',d.body,'fi-muted');
      d.submit('配置广告应用',()=>{d.d.close();return adApplicationDialog();});return;
    }
    const id=field(d.body,'广告账户 ID（可选，留空连接本次授权的账户）','text',account.advertiser_id||'');id.disabled=!!account.advertiser_id;
    const shop=select(d.body,'专属店铺',[['','暂不分配（跨店账户）'],...(data.shops||[]).filter(s=>s.platform==='TIKTOK_SHOP').map(s=>[s.id,s.display_name||s.name])],account.shop_id||'',()=>{});shop.disabled=!!account.advertiser_id;
    const gmvStore=field(d.body,'GMV Max 店铺 ID（可选，来自广告平台）','text',account.gmv_store_id||'');
    el('p','复制链接给广告账户管理员，选择账户并授权。无需填写 Access Token。绑定专属店铺时请指定一个广告账户；跨店授权可全部留空。',d.body,'fi-muted');
    d.submit('生成授权链接',async()=>{const result=await request('/ads/authorize',json('POST',{advertiserId:id.value.trim(),shopId:shop.value,gmvStoreId:gmvStore.value.trim()}));authorizationLink(d,result);});
  }
  function currentFieldView(){return view==='reconciliation'?'RECONCILIATION':view==='overview'?'ALL':view==='ads'?(selectedKind==='AD_GMV'?'AD_GMV':'AD'):selectedKind;}
  function columnValue(row,f){
    const value=f.id==='fx_time'?row.fx?.time:f.id==='fx_provider'?row.fx?.provider:row[f.id];
    if(f.id==='shop_id')return shopName(value);
    if(f.id==='external_id'&&row.id)return button(value||'查看详情',null,()=>detailDialog(row.id));
    if(f.id==='status'||f.id==='result')return badge(value);
    if(f.id==='kind')return kinds[value]||value;
    if(f.id==='platform')return ({SHOPEE:'Shopee',TIKTOK_SHOP:'TikTok Shop'})[value]||value;
    if(f.id==='source')return ({API:'官方接口',IMPORT:'导入账单'})[value]||value;
    if(f.id==='category')return feeName(value);
    if(f.type==='money'||f.type==='number')return number(value);
    if(f.type==='datetime'&&value)return f.label.includes('UTC')?String(value).replace('T',' ').replace('Z',' UTC'):new Date(value).toLocaleString('zh-CN');
    return value==null||value===''?'—':value;
  }
  function renderFieldsTable(parent,items,settings,empty){
    const fields=settings.visible.map(id=>settings.fields.find(f=>f.id===id)).filter(f=>f&&(!f.cny||data.fx));
    table(parent,fields.map(f=>f.id==='shop_id'?'店铺':f.label),items.map(row=>fields.map(f=>columnValue(row,f))),empty,items.map(row=>row.id||[row.shop_id,row.statement_id,row.currency].join(':')));
  }
  async function fieldsDialog(key,initial){
    const d=dialog('字段显示与导出');d.d.classList.add('fi-fields-dialog');
    el('p','主要字段默认选中，扩展字段可按需要添加。页面显示和文件导出独立保存，仅影响当前账号。未返回或不适用的字段显示 —；扩展金额按原币展示。',d.body,'fi-muted');
    let settings=initial,checks=[];
    const choose=select(d.body,'数据类型',[['ALL','全部财务明细'],...Object.entries(kinds),['RECONCILIATION','结算对账']],key,async v=>{try{await render(v);}catch(e){d.feedback.textContent=e.message;}});
    const list=el('div',null,d.body,'fi-field-list');
    const toolbar=el('div',null,d.body,'fi-actions');
    button('恢复主要字段',toolbar,()=>{for(const c of checks){c.visible.checked=settings.defaults.visible.includes(c.id);c.exported.checked=settings.defaults.exported.includes(c.id);}});
    button('导出与显示相同',toolbar,()=>checks.forEach(c=>c.exported.checked=c.visible.checked));
    let renderRun=0;
    async function render(v){const run=++renderRun;list.textContent='正在读取字段…';checks=[];settings=null;
      const result=initial?.view===v?initial:await request('/fields/'+v);if(run!==renderRun)return;settings=result;list.replaceChildren();
      const header=el('div',null,list,'fi-field-row is-header');el('span','字段',header);el('span','显示',header);el('span','导出',header);
      for(const group of [...new Set(settings.fields.map(f=>f.group))]){
        el('div',group,list,'fi-field-group');
        for(const f of settings.fields.filter(f=>f.group===group)){
          const row=el('div',null,list,'fi-field-row');el('span',f.label+(f.cny?' · 人民币模式':''),row);
          const controls={id:f.id};for(const mode of ['visible','exported']){const label=el('label',null,row),input=el('input',null,label);input.type='checkbox';input.checked=settings[mode].includes(f.id);input.setAttribute('aria-label',f.label+(mode==='visible'?' 页面显示':' 导出包含'));controls[mode]=input;}checks.push(controls);
        }
      }
    }
    d.submit('保存字段配置',async()=>{if(!settings||settings.view!==choose.value)throw Error('请等待字段加载完成');await request('/fields/'+choose.value,json('PUT',{visible:checks.filter(c=>c.visible.checked).map(c=>c.id),exported:checks.filter(c=>c.exported.checked).map(c=>c.id)}));d.d.close();load();});
    try{await render(key);}catch(e){d.feedback.textContent=e.message;}
  }
  function removeShopDialog(shop){const d=dialog('移除财务店铺');el('p','移除「'+(shop.display_name||shop.name)+'」后停止财务同步，并从财务看板隐藏该店铺。历史记录保留，商品运营中的店铺不变。',d.body);d.submit('确认移除',async()=>{await request('/shops/'+encodeURIComponent(shop.id),{method:'DELETE'});if(filters.shopId===shop.id)filters.shopId='';d.d.close();load();});}
  async function detailDialog(id){const d=dialog('财务记录详情');try{const row=await request('/records/'+encodeURIComponent(id)+'?'+qs());const settings=await request('/fields/'+(row.kind||'ALL'));const dl=el('dl',null,d.body);
    for(const f of settings.fields){if(f.cny&&!row.fx)continue;const value=columnValue(row,f);if(row[f.id]==null&&!f.id.startsWith('fx_'))continue;el('dt',f.label,dl);const dd=el('dd',null,dl);if(value instanceof Node)dd.textContent=value.textContent;else dd.textContent=value;}if(row.fx)el('p',`${row.fx.provider} · ${row.fx.time} · 参考折算`,d.body,'fi-muted');}catch(e){d.feedback.textContent=e.message;}}
  function exportDialog(){
    const d=dialog('导出财务报表');el('p',`${filters.start} 至 ${filters.end} · 当前店铺、平台和币种筛选 · 全部符合条件的记录`,d.body,'fi-muted');
    const type=select(d.body,'导出类型',[['ALL','全部财务明细'],...Object.entries(kinds),['RECONCILIATION','结算对账']],currentFieldView(),()=>{});
    button('配置导出字段',d.body,()=>fieldsDialog(type.value));
    const format=select(d.body,'文件格式',[['xlsx','Excel（含结算对账与统计口径）'],['csv','CSV 明细']],'xlsx',()=>{});
    d.submit('下载报表',async()=>{
      const response=await fetch('/api/v1/finance/export?'+qs({view:type.value,format:format.value}),{credentials:'same-origin',cache:'no-store'});
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
  function liveScreen(root){
    root.className='workspace-body finance-workspace fi-live-screen';
    const top=el('header',null,root,'fi-live-head'),brand=el('div',null,top);
    el('span','HOYEE / LIVE COMMERCE',brand,'fi-eyebrow');el('h1','实时大屏',brand);el('p','每一份增长，都值得被看见',brand);
    const controls=el('div',null,top,'fi-actions');
    loadingIndicator(controls);
    select(controls,'店铺',[['','全部财务店铺'],...data.shops.map(shop=>[shop.id,shop.display_name||shop.name])],filters.shopId,v=>{filters.shopId=v;load();});
    select(controls,'金额口径',[['CNY','人民币 ¥'],['ORIGINAL','原币']],filters.displayCurrency,v=>{filters.displayCurrency=v;filters.currency='';filters.fxSnapshot='';load();});
    if(filters.displayCurrency==='ORIGINAL')select(controls,'原币',data.currencies.map(c=>[c,c]),data.filters.currency,v=>{filters.currency=v;load();});
    button('返回总览',controls,async()=>{if(document.fullscreenElement)await document.exitFullscreen();navigate('overview');});
    button('全屏展示',controls,async()=>{if(document.fullscreenElement)await document.exitFullscreen();else await document.getElementById('finance-page').requestFullscreen();});
    el('div',new Date().toLocaleString('zh-CN',{hour12:false}),top,'fi-live-clock');
    const errors=data.sync.filter(r=>r.status==='ERROR'),orders=data.sync.filter(r=>r.channel==='ORDER_LIVE');
    const successes=orders.map(s=>s.last_success_at).filter(Boolean).sort();
    const strip=el('div',null,root,'fi-live-strip');el('span',errors.length?'部分同步异常 · 正在自动重试':orders.some(r=>['RUNNING','SYNCING','READY'].includes(r.status))?'数据同步中':'数据看板在线',strip,errors.length?'fi-live-warning':'fi-live-online');
    el('span',successes.length?'订单最近完整同步：'+new Date(successes[0]).toLocaleString('zh-CN'):'等待财务店铺完成首次同步',strip);
    el('span','页面 15 秒刷新 · 订单同步目标约 1 分钟',strip);el('span','平台回传、历史回补和多店排队会有延迟',strip);
    const state=el('div','',root,'fi-state');state.hidden=true;state.setAttribute('role','status');
    const hero=el('div',null,root,'fi-live-hero');
    const sales=el('section',null,hero,'fi-live-sales');const headline=el('div',null,sales,'fi-live-sales-heading');el('div','TODAY’S SALES',headline,'fi-live-kicker');el('span','今日成交战报',headline,'fi-live-sales-badge');el('h2','今日订单金额',sales);
    const amount=el('div',null,sales,'fi-live-amount');el('strong',number(sum('ORDER')),amount);el('span',data.filters.displayCurrency||data.filters.currency||'—',amount);
    el('p','已支付且未取消 · 以平台订单数据为准',sales);
    if(data.fx)el('small','参考折算 · '+data.fx.provider+' · '+new Date(data.fx.time).toLocaleDateString('zh-CN'),sales);
    const kpis=el('div',null,hero,'fi-live-kpis');
    const count=total('ORDER')?.known_amounts,aov=count&&sum('ORDER')!=null?Number(sum('ORDER'))/Number(count):null;
    for(const [label,value,unit,note] of [['有效订单',count,'单','有已知支付金额的订单'],['平均客单价',aov,data.filters.displayCurrency||data.filters.currency,'订单金额 / 有效订单数'],['今日成功退款',sum('REFUND'),data.filters.displayCurrency||data.filters.currency,'按退款业务日统计'],['今日结算净额',sum('SETTLEMENT'),data.filters.displayCurrency||data.filters.currency,'结算日口径，含历史订单']]){
      const k=el('section',null,kpis,'fi-live-kpi');el('span',label,k);icon(label==='有效订单'?'shop':label==='平均客单价'?'chart':label==='今日成功退款'?'refund':'wallet',el('span',null,k,'fi-live-kpi-icon'));const valueNode=el('div',null,k);el('strong',number(value),valueNode);el('small',unit||'',valueNode);el('p',note,k);
    }
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
    table(recent.n,['时间','店铺','订单编号','状态','金额'],data.recent.map(r=>[r.create_time?new Date(r.create_time).toLocaleTimeString('zh-CN',{hour12:false}):r.record_date,shopName(r.shop_id),r.external_id,statusName(r.status),number(data.fx?r.cny_amount:r.amount)]),'今日尚无订单，授权并开启财务店铺同步后自动显示。',data.recent.map(r=>r.id||r.shop_id+':'+r.external_id));
    donut(grid,'订单状态分布','包含未付款与取消订单，独立展示',data.statuses.filter(r=>r.kind==='ORDER').map(r=>({name:statusName(r.status),value:Number(r.records)})));
    const footer=el('footer',null,root,'fi-live-footer');el('span','HOYEE INTELLIGENCE · 让每一笔增长看得见',footer);el('span','更新时间 '+new Date(data.updatedAt).toLocaleTimeString('zh-CN',{hour12:false})+' · 仅超级管理员可见',footer);
  }
  async function loadLive(run,interactive){
    if(document.hidden){refreshTimer=setTimeout(()=>load(false),15000);return;}
    filters.start=today();filters.end=today();filters.granularity='day';
    if(!root.classList.contains('fi-live-screen')){cleanup();root.replaceChildren();root.className='workspace-body finance-workspace fi-live-screen';el('div','正在连接今日经营数据…',root,'fi-state');}
    setLoading(interactive);
    try{const response=await request('/screen?'+qs(),{method:'POST'});if(run!==generation||!active)return;data=response;filters.currency=data.filters.currency||'';filters.fxSnapshot=data.fx?.id||'';await ensureCharts();if(run!==generation||!active)return;const next=root.cloneNode(false);liveScreen(next);commit(next);}
    catch(e){if(run===generation){notify((data&&root.querySelector('.fi-live-head')?'更新暂未完成，保留上次数据。':'暂未读取到今日数据。')+'15 秒后自动重试。'+e.message,true);if(!root.querySelector('.fi-live-head')&&!root.querySelector('.fi-live-recovery')){const controls=el('div',null,root,'fi-actions fi-live-recovery');button('以原币查看',controls,()=>{filters.displayCurrency='ORIGINAL';filters.currency='';filters.fxSnapshot='';load();});button('返回总览',controls,()=>navigate('overview'));}}}
    finally{if(run===generation&&active){setLoading(false);refreshTimer=setTimeout(()=>load(false),15000);}}
  }
  async function load(interactive=true){
    if(!active||!allowed())return;const run=++generation;clearTimeout(refreshTimer);
    if(view==='live')return loadLive(run,interactive);
    if(mountedView!==view){cleanup();root.replaceChildren();const initial=root.cloneNode(false);shell(initial);IncrementalDOM.patch(root,initial);}
    setLoading(interactive);
    try{
      const next=root.cloneNode(false);
      if(view==='reconciliation'){
        const response=await request('/reconciliation?'+qs({page}));if(run!==generation||!active)return;
        data=response;filters.currency=data.filters.currency||'';filters.fxSnapshot=data.fx?.id||'';
        reconciliation(shell(next),response);
      }else if(view!=='shops'){const response=await request('/overview?'+qs());if(run!==generation||!active)return;data=response;filters.currency=data.filters.currency||'';filters.fxSnapshot=data.fx?.id||'';
        // Build filters after authorized shop/currency options have arrived.
        const body=shell(next);await ensureCharts();if(run!==generation||!active)return;
        if(view==='overview')overview(body);else if(view==='ads')await adsView(body,run);else await recordView(body,run);
      }else await shopView(shell(next),run);
      if(run!==generation||!active)return;
      commit(next);
      const errors=(data?.sync||[]).filter(s=>s.status==='ERROR').length;
      notify(callbackNotice||(errors?`${errors} 项同步需要处理，可在财务店铺管理查看权限或连接问题。`:''));callbackNotice='';
      if(view==='shops'||(data?.sync||[]).some(s=>['READY','RUNNING','SYNCING'].includes(s.status)))
        refreshTimer=setTimeout(refreshWhenIdle,15000);
    }catch(e){if(run===generation){notify((mountedView===view?'更新暂未完成，已保留当前数据。':'')+(e.message||'财务数据暂时无法加载'),true);refreshTimer=setTimeout(refreshWhenIdle,15000);}}
    finally{if(run===generation)setLoading(false);}
  }
  globalThis.FinanceWorkspace={allowed,enter(route){if(!allowed())return;const wasLive=view==='live';view=routes[route]?route:'overview';if(view==='live'){filters.displayCurrency='CNY';filters.currency='';filters.fxSnapshot='';}else if(wasLive){filters.start=ago(29);filters.end=today();filters.granularity='day';}active=true;page=1;load();},leave,reset};
  window.addEventListener('heyi:company-reset',reset);
})();
