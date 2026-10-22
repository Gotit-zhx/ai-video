/* Expense ledger. The explicit preview context uses memory only; live writes use the authorized workspace API. */
(() => {
  'use strict';
  const categories=[['packaging','包装耗材'],['shipping','外部运费'],['warehouse','仓储操作'],['staff','人工'],['rent','租金'],['other','其他费用']];
  const treatments=[['extra','单独计入费用'],['settlement','已含平台结算'],['unit','已含商品单位成本'],['ad','已含广告消耗'],['pending','待核对，暂不计入']];
  const methods=[['none','仅计入店铺 / 分组期间费用'],['orders','按有效订单数'],['units','按出库商品数量'],['revenue','按净销售额']];
  const scopes=[['order','指定订单'],['shop','店铺期间费用'],['group','分组公共费用']];
  const date=n=>{const d=new Date();d.setDate(d.getDate()-n);return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;};
  const fresh=()=>({tab:'ledger',currency:'CNY',group:'',search:'',start:date(29),end:date(0),page:1,rows:[],rules:[],changes:[],totals:null,nextId:1,feedback:''});
  let state=fresh(),loadSequence=0;
  const el=(name,text,parent,cls)=>{const n=document.createElement(name);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent?.append(n);return n;};
  const label=(list,value)=>list.find(r=>r[0]===value)?.[1]||value;
  const money=value=>Number(value).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
  const button=(parent,text,fn,cls='')=>{const n=el('button',text,parent,cls);n.type='button';n.onclick=async()=>{if(n.disabled)return;n.disabled=true;try{await fn();}catch(e){const host=n.closest('dialog')?.querySelector('[role=alert]')||document.querySelector('.fe-feedback');if(host)host.textContent=e.message||'操作失败，请重试';}finally{n.disabled=false;}};return n;};
  function select(parent,name,options,value,change=()=>{}){
    const wrap=el('label',null,parent);el('span',name,wrap);const n=el('select',null,wrap);n.setAttribute('aria-label',name);
    options.forEach(([value,text])=>el('option',text,n).value=value);n.value=value;n.onchange=()=>change(n.value);return n;
  }
  function field(parent,name,value='',type='text'){
    const wrap=el('label',null,parent);el('span',name,wrap);const n=el('input',null,wrap);n.type=type;n.value=value;n.setAttribute('aria-label',name);return n;
  }
  function badge(text,tone=''){return el('span',text,null,'fc-tag '+tone);}
  function table(parent,headers,rows,empty){
    const wrap=el('div',null,parent,'fi-table-wrap'),t=el('table',null,wrap,'fi-table fc-table'),tr=el('tr',null,el('thead',null,t));
    headers.forEach(text=>el('th',text,tr).scope='col');
    const body=el('tbody',null,t);rows.forEach(values=>{const row=el('tr',null,body);values.forEach(value=>{const td=el('td',null,row);if(value instanceof Node)td.append(value);else td.textContent=value??'—';});});
    if(!rows.length)el('p',empty,parent,'fc-empty');return t;
  }
  function panel(parent,title,note){
    const n=el('section',null,parent,'fi-card fc-panel'),head=el('div',null,n,'fi-card-head'),copy=el('div',null,head);el('h3',title,copy);el('p',note,copy);return {n,head};
  }
  function dialog(title){
    const n=el('dialog',null,document.body,'fi-dialog fc-dialog fe-dialog'),head=el('div',null,n,'fi-dialog-head');
    el('h3',title,head);button(head,'×',()=>n.close(),'fi-close').setAttribute('aria-label','关闭');
    const body=el('div',null,n,'fi-dialog-body'),foot=el('div',null,n,'fi-dialog-foot');button(foot,'取消',()=>n.close());
    n.addEventListener('close',()=>n.remove());n.showModal();return {n,body,foot};
  }
  function reset(){loadSequence++;state=fresh();document.querySelectorAll('.fe-dialog').forEach(n=>n.remove());}
  const query=()=>new URLSearchParams({currency:state.currency,group:state.group,search:state.search,start:state.start,end:state.end,page:state.page}).toString();
  function mount(root,{shops=[],groups=[],canManage=false,live=false,data=null,request,embedded=false}={}){
    function hydrate(data){state.rows=data.rows||[];state.rules=data.rules||[];state.changes=data.changes||[];state.totals=data.totals||null;}
    if(live&&data)hydrate(data);
    async function refresh(){const run=++loadSequence;if(live){const data=await request('/expenses?'+query());if(run!==loadSequence||!root.isConnected)return;hydrate(data);}draw();}
    async function filter(){state.page=1;try{await refresh();}catch(e){state.feedback=e.message;draw();}}
    const json=body=>({headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    const shopName=id=>shops.find(s=>s.id===id)?.display_name||shops.find(s=>s.id===id)?.name||'店铺待选择';
    const groupName=id=>groups.find(g=>g.id===id)?.name||'分组待选择';
    const owner=row=>row.scope==='group'?groupName(row.target):shopName(row.target);
    const visible=row=>!state.group||(state.group==='UNGROUPED'?row.scope!=='group'&&!shops.find(s=>s.id===row.target)?.group_id:row.scope==='group'?row.target===state.group:shops.find(s=>s.id===row.target)?.group_id===state.group);
    function explain(title,text){const d=dialog(title);el('p',text,d.body,'fi-muted');}
    function entry(existing){
      const d=dialog((existing?'调整费用':'新增费用')+(live?'':' · 仅预览'));
      el('p',live?'保存后公司内有财务权限的成员可查看。调整会保留原记录、原因与操作人。':'本页仅保存草稿。正式启用后，修改会保留原记录、调整原因与操作人。',d.body,'fi-muted');
      const form=el('div',null,d.body,'fe-form'),kind=select(form,'费用类别',categories,existing?.category||'packaging');
      const scope=select(form,'归属方式',scopes,existing?.scope||'order',()=>renderTarget());
      const targetArea=el('div',null,form,'fe-span2');let target,order,start,end;
      function renderTarget(){
        targetArea.replaceChildren();target=select(targetArea,scope.value==='group'?'归属分组':'归属店铺',[['','请选择'],...(scope.value==='group'?groups.map(g=>[g.id,g.name]):shops.map(s=>[s.id,s.display_name||s.name]))],existing?.scope===scope.value?existing.target:'');
        if(scope.value==='order'){order=field(targetArea,'订单编号',existing?.order||'');start=field(targetArea,'费用日期',existing?.start||'','date');end=null;}
        else{order=null;start=field(targetArea,'费用开始日期',existing?.start||'','date');end=field(targetArea,'费用结束日期',existing?.end||'','date');}
      }
      renderTarget();
      const recordDate=live?field(form,'计入财务日期',existing?.recordDate||date(0),'date'):null;
      const amount=field(form,'金额',existing?.amount??'','number');amount.min='0.01';amount.step='0.01';
      const currency=select(form,'费用币种',[['CNY','人民币 CNY'],['PHP','菲律宾比索 PHP'],['THB','泰铢 THB'],['MYR','马来西亚林吉特 MYR'],['USD','美元 USD']],existing?.currency||state.currency);
      const source=field(form,'费用来源编号',existing?.source||'');source.placeholder='运单、发票或内部费用单编号';
      const treatment=select(form,'计入方式',treatments,existing?.treatment||'pending');
      const note=field(form,existing?'调整原因':'费用说明',existing?'':existing?.note||'');note.parentElement.classList.add('fe-span2');
      el('p','商品采购单价在「成本管理」维护；此处记录额外费用。来源编号用于核查重复录入。',d.body,'fi-muted');
      const feedback=el('p','',d.body,'fc-alert');feedback.setAttribute('role','alert');
      button(d.foot,live?(existing?'保存调整':'保存费用'):(existing?'应用调整到预览':'添加到预览'),async()=>{
        const value=Number(amount.value);
        if(!target.value||!start.value||(end&&(!end.value||end.value<start.value))||(order&&!order.value.trim())||!Number.isFinite(value)||value<=0||!source.value.trim()){
          feedback.textContent='请补齐归属、订单或费用期间、正数金额和来源编号。';return;
        }
        if(existing&&!note.value.trim()){feedback.textContent='请填写调整原因。';return;}
        const next={id:existing?.id||state.nextId,version:existing?.version||0,category:kind.value,scope:scope.value,target:target.value,order:order?.value.trim()||'',start:start.value,end:end?.value||start.value,recordDate:recordDate?.value||start.value,amount:live?amount.value:value,currency:currency.value,source:source.value.trim(),treatment:treatment.value,note:note.value.trim()};
        if(live){
          await request('/expenses'+(existing?'/'+encodeURIComponent(existing.id):''),{method:existing?'PUT':'POST',...json(next)});
          state.currency=next.currency;state.group='';state.page=1;state.search='';state.start=next.recordDate<state.start?next.recordDate:state.start;state.end=next.recordDate>state.end?next.recordDate:state.end;
          state.feedback=next.scope==='group'&&next.treatment==='extra'?'费用已保存，等待分摊；尚未计入店铺经营结果。':'费用已保存，调整记录可追溯。';d.n.close();await refresh();return;
        }
        if(state.rows.some(r=>r.id!==next.id&&r.source===next.source&&r.category===next.category)){
          feedback.textContent='同类费用已有相同来源编号，请核对现有记录后调整。';return;
        }
        if(existing){state.changes.unshift({before:{...existing},after:{...next},reason:note.value.trim(),time:new Date().toLocaleString('zh-CN',{hour12:false})});state.rows=state.rows.map(r=>r.id===existing.id?next:r);}
        else{state.rows.push(next);state.nextId++;}
        state.currency=next.currency;d.n.close();draw();
      },'fi-primary');
    }
    function ruleDialog(){
      const d=dialog('配置分摊规则'+(live?'':' · 仅预览')),form=el('div',null,d.body,'fe-form');
      const category=select(form,'费用类别',categories,'warehouse'),scope=select(form,'分摊范围',[['group','按店铺分组'],['shop','按单个店铺']],'group',()=>renderTarget());
      const host=el('div',null,form,'fe-span2');let target;
      function renderTarget(){host.replaceChildren();target=select(host,'适用范围',[['','请选择'],...(scope.value==='group'?groups.map(g=>[g.id,g.name]):shops.map(s=>[s.id,s.display_name||s.name]))],'');}
      renderTarget();
      const method=select(form,'分摊依据',methods,'none'),start=field(form,'生效日期','','date');
      el('p','只分摊“单独计入费用”的记录。先预览覆盖订单、基数和差额，再确认；无有效基数时保留待分摊，不平均硬摊。',d.body,'fi-muted');
      el('p','规则按版本保存，不自动重算历史费用。分摊执行入口暂未启用，公共费用保持待分摊。',d.body,'fi-muted');
      const feedback=el('p','',d.body,'fc-alert');
      button(d.foot,live?'保存规则':'保存规则到预览',async()=>{
        if(!target.value||!start.value){feedback.textContent='请选择适用范围和生效日期。';return;}
        const rule={category:category.value,scope:scope.value,target:target.value,method:method.value,start:start.value};
        if(live){await request('/expense-rules',{method:'POST',...json(rule)});d.n.close();await refresh();}
        else{state.rules.push(rule);d.n.close();draw();}
      },'fi-primary');
    }
    function ledger(parent){
      const p=panel(parent,'外部费用台账',live?'公司共享 · 按计入财务日期筛选 · 已含费用不重复扣减':'当前展示本页草稿 · 平台与广告明细仍保留在现有正式页面');
      if(canManage){const actions=el('div',null,p.head,'fi-actions');button(actions,live?'导入 / 原有明细':'批量导入设计',()=>live?location.hash='finance-fee-records':explain('批量导入 · 字段预留','支持费用来源编号、费用类型、日期或期间、店铺或分组、订单编号、金额、币种、计入方式。先检查重复编号、缺失归属与计价口径，再确认导入；此预览不上传文件。'));button(actions,'新增费用',()=>entry(),'fi-primary');}
      const tools=el('div',null,p.n,'fc-table-tools');
      select(tools,'费用分组',[['','全部分组'],['UNGROUPED','未分组'],...groups.map(g=>[g.id,g.name])],state.group,v=>{state.group=v;filter();});
      select(tools,'费用币种',[['CNY','人民币 CNY'],['PHP','菲律宾比索 PHP'],['THB','泰铢 THB'],['MYR','马来西亚林吉特 MYR'],['USD','美元 USD']],state.currency,v=>{state.currency=v;filter();});
      if(live)for(const [name,key]of[['费用开始日期','start'],['费用结束日期','end']]){const n=field(tools,name,state[key],'date');n.onchange=()=>{state[key]=n.value;filter();};}
      const search=field(tools,'搜索费用',state.search);search.placeholder='订单编号 / 来源编号';search.onchange=()=>{state.search=search.value.trim();filter();};
      const rows=state.rows.filter(r=>visible(r)&&r.currency===state.currency&&[r.source,r.order,r.note].join(' ').includes(state.search));
      table(p.n,['日期 / 期间','费用类型','归属 / 订单','金额','计入方式','来源编号','操作'],rows.map(row=>{
        const destination=el('div',owner(row));el('small',row.scope==='order'?row.order:label(scopes,row.scope),destination);
        const period=el('div',row.start===row.end?row.start:row.start+' ～ '+row.end);if(live)el('small','计入：'+row.recordDate,period);
        const treatment=el('div');treatment.append(badge(label(treatments,row.treatment),row.treatment==='extra'?'good':'warn'));if(live)el('small',row.status,treatment);
        return [period,label(categories,row.category),destination,money(row.amount)+' '+row.currency,treatment,row.source,canManage?button(null,'调整',()=>entry(row),'fi-record-link'):'只读'];
      }),live?'当前筛选没有手工费用。平台费用与已有导入记录可从“平台费用 / 原有明细”查看。':'尚无符合筛选的预览费用。可以新增一笔草稿，查看订单归属、计入方式与调整记录。');
      if(live){const pager=el('div',null,p.n,'fi-pagebar');el('span',`共 ${state.totals?.records||0} 笔 · 第 ${state.page} 页`,pager);const actions=el('div',null,pager,'fi-actions');button(actions,'上一页',()=>{state.page--;return refresh();}).disabled=state.page<=1;button(actions,'下一页',()=>{state.page++;return refresh();}).disabled=state.page*50>=Number(state.totals?.records||0);}
      el('p',live?'只有“单独计入费用”的订单 / 店铺费用计入 BI 外部成本。分组公共费用待分摊，已含费用与待核对记录不再扣减。':'这里的金额仅来自本页手工草稿，未关联正式订单，也不会计入财务 BI。',p.n,'fc-inline-note');
    }
    function rules(parent){
      const p=panel(parent,'期间费用分摊规则','先归属，再分摊；未分摊金额保持独立展示');
      if(canManage)button(p.head,'配置分摊规则',ruleDialog,'fi-primary');
      table(p.n,['费用类别','适用范围','分摊依据','生效日期','状态'],state.rules.map(r=>[label(categories,r.category),r.scope==='group'?groupName(r.target):shopName(r.target),label(methods,r.method),r.start,badge(live?'已保存 · 待执行':'预览草稿','warn')]),'还没有分摊规则。公共费用可以仅在店铺或分组期间统计，不强制分到每笔订单。');
      if(live)el('p','最近 100 个规则版本。分摊执行尚未启用，保存规则不会自动改变订单成本。',p.n,'fc-inline-note');
      const flow=el('div',null,p.n,'fc-flow');[['选择期间费用','剔除已包含或待核对费用'],['检查覆盖范围','明确店铺、订单与分摊基数'],['预览分摊结果','原金额 = 已分摊 + 未分摊'],['确认并留痕','按原币处理尾差，锁定版本']].forEach(([title,note],i)=>{const step=el('div',null,flow);el('i',i+1,step);el('strong',title,step);el('small',note,step);});
    }
    function changes(parent){
      const p=panel(parent,'费用调整记录','修改金额、归属或计入方式时，保留调整前后内容');
      table(p.n,['调整时间','来源编号','调整前','调整后','原因','操作人'],state.changes.map(c=>{
        const values=r=>({'金额':r.amount==null?'—':money(r.amount)+' '+r.currency,'类别':label(categories,r.category)||'—','归属':r.scope?label(scopes,r.scope)+' · '+owner(r):'—','订单编号':r.order||'—','期间':r.start?(r.start===r.end?r.start:r.start+' ～ '+r.end):'—','计入日期':r.recordDate||'—','计入方式':label(treatments,r.treatment)||'—','来源编号':r.source||'—'});
        const before=values(c.before),after=values(c.after),keys=Object.keys(before).filter(k=>before[k]!==after[k]);
        const describe=values=>{const n=el('div');if(!keys.length)n.textContent='费用内容未变更';keys.forEach(k=>el('small',k+'：'+values[k],n));return n;};
        return [c.time,c.after.source,describe(before),describe(after),c.reason,live?c.actor:'预览操作'];
      }),'尚无调整记录。在费用台账中调整一笔草稿，可查看前后变化。');
      el('p',live?'展示公司最近 100 次费用操作；修改前后内容和操作人均保留。平台原始账单不被改写。':'正式设计采用追加调整记录；平台原始账单保留原值。新增费用、费用冲回和历史分摊调整各有来源，防止直接覆盖后无法追溯。',p.n,'fc-inline-note');
    }
    function sources(parent){
      const grid=el('div',null,parent,'fe-source-grid');
      for(const [title,desc,route]of [
        ['平台费用','佣金、支付手续费、平台物流等随结算明细展示。已在结算净额扣除的部分，不再次扣减。','结算中心'],
        ['广告费用','接口消耗与手工导入保留来源。相同广告账户、日期和数据范围需查重，分摊不增加总费用。','广告分析'],
        ['商品成本','按店铺商品规格 SKU 找到成本型号，结合仓库、月份与包装换算生成订单成本快照。','成本管理'],
        ['额外费用','包装、外部运费、仓储、人工和租金按订单或期间归属。是否已含在其他成本中必须明确。','费用台账']
      ]){const p=panel(grid,title,route);el('p',desc,p.n,'fe-source-copy');p.n.append(badge(title==='商品成本'?'关联设计中':'核算口径','good'));}
      const p=panel(parent,'每笔费用都要能回答','通过来源和计入方式检查是否漏记、重复或归属错误');
      table(p.n,['检查项','规则设计'],[
        ['归属谁','订单、店铺或分组必须明确；分组统计只引用一次'],
        ['什么时候发生','记录实际费用日期或期间；成本版本与汇率保持可追溯'],
        ['从哪里来','保留接口 / 导入 / 手工来源，以及账单、运单或来源编号'],
        ['是否已经计算过','已含结算、商品成本或广告的费用只展示，不再加计'],
        ['如何分摊','先看原币总额、基数与剩余金额；确认后才应用到订单']
      ]);
    }
    function draw(){
      root.replaceChildren();root.className=embedded?'fe-workspace':'workspace-body finance-workspace fe-workspace';
      if(!embedded){const head=el('header',null,root,'fi-head'),copy=el('div',null,head);
      el('span','FINANCE / ANALYTICS',copy,'fi-eyebrow');el('h1','费用与调整',copy,'fi-title');el('p','把费用归到正确的订单、店铺和分组，每次调整都有据可查。',copy,'fi-subtitle');
      const actions=el('div',null,head,'fi-actions');button(actions,'查看成本管理',()=>{location.hash='finance-costs';});
      if(live){button(actions,'平台费用 / 原有明细',()=>{location.hash='finance-fee-records';});button(actions,'刷新',refresh);}
      }
      const workspace=el('div',null,root,'fc-workspace'),banner=el('div',null,workspace,'fc-preview-note');
      el('span',live?'费用口径':'设计预览',banner,'fc-preview-label');el('span',live?'费用按原币和计入财务日期统计；分组公共费用先归集，确认分摊前不计入各店铺结果。':'所有录入仅保留在当前页面，刷新后清空；尚未接入正式账目、订单校验与分摊。',banner);
      const feedback=el('p',state.feedback,workspace,'fc-feedback fe-feedback');feedback.setAttribute('role','status');
      const rows=state.rows.filter(r=>visible(r)&&r.currency===state.currency),metrics=el('div',null,workspace,'fc-metrics');
      for(const [title,value,note]of [
        [live?'费用台账总额':'预览草稿金额',money(live?state.totals?.amount||0:rows.reduce((sum,r)=>sum+Number(r.amount),0)),state.currency+' · '+(live?state.totals?.records||0:rows.length)+(live?' 笔费用':' 笔草稿')],
        [live?'已单独计入':'拟单独计入',money(live?state.totals?.posted||0:rows.filter(r=>r.treatment==='extra').reduce((sum,r)=>sum+Number(r.amount),0)),state.currency+(live?' · BI 外部成本':' · 未计入正式核算')],
        ['已含其他口径',(live?state.totals?.included||0:rows.filter(r=>['unit','settlement','ad'].includes(r.treatment)).length)+' 笔','仅作核对，不重复加计'],
        [live?'待核对 / 待分摊':'待核对',(live?state.totals?.pending||0:rows.filter(r=>r.treatment==='pending').length)+' 笔','确认归属与计入方式后启用']
      ]){const n=el('div',null,metrics,'fc-metric');el('span',title,n);el('strong',value,n);el('small',note,n);}
      const tabs=el('div',null,workspace,'fc-tabs');tabs.setAttribute('role','tablist');
      [['ledger','费用台账'],['rules','分摊规则'],['changes','调整记录'],['sources','来源与口径']].forEach(([id,title],i)=>{
        const n=button(tabs,'',()=>{state.tab=id;draw();});n.setAttribute('role','tab');n.setAttribute('aria-selected',String(state.tab===id));el('span',String(i+1).padStart(2,'0'),n);el('strong',title,n);
      });
      const host=el('div',null,workspace,'fe-content');host.setAttribute('role','tabpanel');
      ({ledger,rules,changes,sources})[state.tab](host);
    }
    draw();
  }
  function setFilters(filters){state.group=filters.groupId||'';state.start=filters.start||state.start;state.end=filters.end||state.end;if(filters.currency)state.currency=filters.currency;state.page=1;}
  globalThis.FinanceExpensesDesign={mount,reset,query,setFilters};
})();
