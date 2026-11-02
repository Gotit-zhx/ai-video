/* Company authorization and bounded, read-only Qianyi after-sales evidence. */
(() => {
  'use strict';
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;parent?.append(n);return n;};
  const json=(method,body)=>({method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  const syncStates={READY:'等待同步',SYNCING:'同步中',SUCCESS:'同步完成',ERROR:'自动重试中',PAUSED:'已暂停',DISABLED:'已停用'};
  const returnStates={NEW:'待提交',WAIT_AUDIT:'待审核',WAIT_BUYER_RETURN:'待买家退回',WAIT_STORAGE:'待收货',RECEIVING:'部分收货',FINISH:'收货完成',CLOSE:'关闭收货',NO_NEED_RETURN:'无需退货'};
  const refundStates={WAIT_AUDIT:'待审核',WAIT_PAYMENT:'待退款',REFUSE_REFUND:'拒绝退款',FINISH:'退款完成',PAY_FAILED:'退款失败',CLOSE:'退款关闭'};
  const types={SALES_RETURN:'退货单',RETURN:'退款单',RESHIP:'补发货单'};
  const num=value=>value==null?'—':Number(value).toLocaleString('zh-CN',{maximumFractionDigits:6});
  const state=(map,value)=>map[value]||value||'千易未返回';
  const stamp=(value,zone='Asia/Shanghai')=>{if(value==null||value==='null'||value===''||String(value)==='0')return '—';const d=new Date(/^\d+$/.test(String(value))?Number(value):value);return Number.isFinite(d.getTime())?d.toLocaleString('zh-CN',{timeZone:zone,hour12:false}):'—';};
  function modal(title,wide=false){
    const d=el('dialog',null,document.body,'fi-dialog fq-dialog'+(wide?' fq-wide':''));
    const head=el('div',null,d,'fi-dialog-head');el('h3',title,head);
    const body=el('div',null,d,'fi-dialog-body'),feedback=el('p','',body,'fi-feedback');feedback.setAttribute('role','status');feedback.setAttribute('aria-live','polite');
    const foot=el('div',null,d,'fi-dialog-foot');
    const alive=()=>d.isConnected&&d.open;
    function button(parent,label,action,cls=''){
      const node=el('button',label,parent,cls);node.type='button';
      node.onclick=async()=>{if(node.disabled)return;node.disabled=true;try{await action();}catch(e){if(alive())feedback.textContent=e.message||'读取失败，请重试';}finally{if(node.isConnected)node.disabled=false;}};return node;
    }
    button(head,'关闭',()=>d.close()).setAttribute('aria-label','关闭'+title);button(foot,'关闭',()=>d.close());
    d.addEventListener('close',()=>d.remove(),{once:true});d.showModal();return {d,body,foot,feedback,button,alive};
  }
  function input(parent,label,type,value=''){
    const wrap=el('label',null,parent);el('span',label,wrap);const n=el('input',null,wrap);n.type=type;n.value=value;n.setAttribute('aria-label',label);return n;
  }
  function select(parent,label,values,value=''){
    const wrap=el('label',null,parent);el('span',label,wrap);const n=el('select',null,wrap);n.setAttribute('aria-label',label);
    values.forEach(([id,text])=>el('option',text,n).value=id);n.value=value;return n;
  }
  function table(parent,headers,rows){
    const wrap=el('div',null,parent,'fi-table-wrap');wrap.tabIndex=0;
    const t=el('table',null,wrap,'fi-table'),head=el('tr',null,el('thead',null,t));headers.forEach(text=>el('th',text,head).scope='col');
    const body=el('tbody',null,t);rows.forEach(row=>{const tr=el('tr',null,body);row.forEach(value=>{const td=el('td',null,tr);if(value instanceof Node)td.append(value);else td.textContent=value??'—';});});
  }
  function tag(text,raw,warning=false){const n=el('span',text,null,'fi-status '+(warning?'running':raw==='FINISH'||raw==='READY'?'success':''));n.title=raw||'千易接口未返回此字段';return n;}
  async function openCompany({request,shops=[],groups=[]}){
    const m=modal('千易全局配置');let closed=false,timer,sequence=0,shopSequence=0,page=1,config;
    m.d.addEventListener('close',()=>{closed=true;clearTimeout(timer);sequence++;shopSequence++;});
    m.feedback.textContent='正在读取公司千易配置…';
    try{config=await request('/qianyi');}catch(e){m.feedback.textContent=e.message;return;}
    if(!m.alive())return;m.feedback.textContent='';
    el('p','公司只需配置一次。系统按平台和平台店铺编号自动关联全部财务店铺，之后新授权的店铺也会自动匹配。',m.body,'fi-muted');
    const form=el('div',null,m.body,'fq-form');
    const environment=select(form,'千易环境',[['CN','国内正式环境'],['ASIA','海外正式环境'],['TEST','测试环境']],config.environment||'CN');
    const app=input(form,'千易 App ID','text',config.app_id||''),secret=input(form,'千易 App Secret','password');
    app.autocomplete='off';secret.autocomplete='new-password';secret.placeholder=config.hasSecret?'已保存，留空保持原密钥':'请输入 App Secret';
    const start=input(form,'历史同步开始日期','date',String(config.sync_start||'').slice(0,10));
    const enabled=select(form,'千易自动同步',[['true','开启'],['false','暂停']],String(config.enabled!==false));
    el('p','仅用于售后数据。关联不到或编号不唯一的店铺会保留为未关联，不按相似名称猜测。',m.body,'fi-muted');
    const progress=el('p','',m.body,'fq-progress');progress.setAttribute('role','status');
    const actions=el('div',null,m.body,'fi-actions'),list=el('div',null,m.body);
    function show(value){progress.textContent=(value.configured?state(syncStates,value.status):'尚未配置')+' · 已关联 '+(value.bound_count||0)+' / '+(value.shop_count||0)+' 家店铺'+(value.last_success_at?' · 最近关联检查 '+stamp(value.last_success_at):'')+(value.error_message?' · '+value.error_message:'');}
    async function refresh(){
      clearTimeout(timer);const current=++sequence,value=await request('/qianyi');if(!m.alive()||current!==sequence)return;
      config=value;show(value);if(value.enabled)timer=setTimeout(()=>{if(!closed&&m.alive())refresh().catch(e=>{if(m.alive())m.feedback.textContent=e.message;});},10000);
    }
    async function associations(){
      const current=++shopSequence,currentPage=page,rows=await request('/qianyi/shops?page='+page);if(!m.alive()||current!==shopSequence)return;
      list.replaceChildren();table(list,['财务店铺','千易店铺','售后同步'],rows.map(r=>[
        r.name,r.remote_shop_name||'未关联：核对平台店铺编号',r.status?state(syncStates,r.status)+(r.error_message?' · '+r.error_message:''):'待自动关联'
      ]));
      const pages=el('div',null,list,'fi-actions');m.button(pages,'上一批店铺',()=>{page=currentPage-1;return associations();}).disabled=currentPage<=1;
      el('span','第 '+currentPage+' 页',pages);m.button(pages,'下一批店铺',()=>{page=currentPage+1;return associations();}).disabled=rows.length<50;
    }
    m.button(actions,'立即同步全部',async()=>{m.feedback.textContent=await request('/qianyi/sync',{method:'POST'});await refresh();});
    m.button(actions,'刷新关联状态',async()=>{await Promise.all([refresh(),associations()]);});
    m.button(actions,'查看千易售后',()=>openReturns({request,shops,groups}));
    m.button(m.foot,'保存全局配置',async()=>{
      const message=await request('/qianyi',json('PUT',{environment:environment.value,appId:app.value.trim(),appSecret:secret.value,syncStart:start.value,enabled:enabled.value==='true'}));
      if(!m.alive())return;secret.value='';secret.placeholder='已保存，留空保持原密钥';m.feedback.textContent=message;await Promise.all([refresh(),associations()]);
    },'fi-primary');
    show(config);await associations();if(config.enabled)timer=setTimeout(()=>refresh().catch(e=>{if(m.alive())m.feedback.textContent=e.message;}),10000);
  }
  async function openDetail({request,shopId,returnNumber}){
    const m=modal('千易售后单详情',true);m.feedback.textContent='正在读取千易单据…';
    try{
      const value=await request('/qianyi/returns/detail?'+new URLSearchParams({shopId,returnNumber}));if(!m.alive())return;
      m.feedback.textContent='';const s=value.sourceFields||{},grid=el('dl',null,m.body,'fq-facts');
      const fields=[['数据来源','千易 ERP · 售后接口'],['千易售后单号',value.return_number],['平台订单号',value.order_id],['千易销售订单号',s.orderNumber],['财务店铺',value.shop_name],['千易店铺',s.shop],['类型',state(types,s.type)],['退货状态',state(returnStates,s.returnStatus||s.status)],['退款状态',state(refundStates,s.refundStatus)],['千易仓库',s.warehouse],['入库单号',s.asnNumber||s.warehouseEntryNumber],['创建时间（北京时间）',stamp(s.createTime)],['更新时间（北京时间）',stamp(s.updateTime)],['收货时间（北京时间）',stamp(s.receiveTime)],['完成时间（北京时间）',stamp(s.finishTime)],['入库归属日期（店铺时区）',value.receipt_date?String(value.receipt_date).slice(0,10):'千易未返回'],['千易订单退款金额（'+(s.currency||'币种未返回')+'）',num(s.orderRefundAmount)]];
      for(const [label,text]of fields){const item=el('div',null,grid);el('dt',label,item);el('dd',text||'—',item);}
      el('h3','千易退货商品',m.body);const lines=s.returnSkuList||[];
      table(m.body,['商品 SKU','千易订单商品 ID','退货数量','良品数量','不良品数量','明细状态'],lines.map(r=>[r.sku,r.orderSkuId,num(r.quantity),num(r.goodQuantity),num(r.badQuantity),r.isDeleted===1||r.isDeleted===true?'千易已删除':r.selected===0?'千易未选中':'有效']));
      if(!lines.length)el('p','千易尚未返回商品明细。',m.body,'fi-muted');
      const raw=el('details',null,m.body,'fq-source');el('summary','查看千易接口原始字段',raw);el('p','仅展示单据、商品和金额字段。',raw,'fi-muted');el('pre',JSON.stringify(s,null,2),raw);
    }catch(e){if(m.alive())m.feedback.textContent=e.message||'千易单据读取失败';}
  }
  async function openReturns(options){return returnsPanel(modal('千易售后明细',true),options);}
  function mountReturns(host,options){
    host.replaceChildren();host.classList.add('fq-panel');let disposed=false;
    const feedback=el('p','',host,'fi-feedback');feedback.setAttribute('role','status');
    const alive=()=>!disposed&&host.isConnected;
    const m={d:host,body:host,feedback,alive,button(parent,label,action,cls=''){
      const b=el('button',label,parent,cls);b.type='button';b.onclick=async()=>{
        if(b.disabled)return;b.disabled=true;
        try{await action();}catch(e){if(alive())feedback.textContent=e.message||'读取失败，请重试';}
        finally{if(b.isConnected)b.disabled=false;}
      };return b;
    }};
    returnsPanel(m,options).catch(e=>{if(alive())feedback.textContent=e.message;});
    return ()=>{disposed=true;host.dispatchEvent(new Event('close'));};
  }
  async function returnsPanel(m,{request,shops=[],groups=[],selection={},onSelection=()=>{}}){
    const filters=el('div',null,m.body,'fq-filters');let sequence=0,page=1,controller,exportController,applied;
    el('p','数据来源：千易 ERP · 默认退货单。可按售后类型、退货状态、退款状态筛选，合计覆盖全部筛选结果。',m.body,'fi-muted');
    const choices=map=>[['','全部'],...Object.entries(map),['NOT_RETURNED','千易未返回']];
    const group=select(filters,'店铺分组',[['','全部分组'],['UNGROUPED','未分组'],...groups.map(g=>[g.id,g.name])],selection.groupId||'');
    const platform=select(filters,'平台',[['','全部平台'],['SHOPEE','虾皮'],['TIKTOK_SHOP','TikTok']],selection.platform||'');
    const shop=select(filters,'财务店铺',[['','全部店铺'],...shops.map(s=>[s.id,s.display_name||s.name])],selection.shopId||'');
    const type=select(filters,'售后类型',choices(types),selection.type??'SALES_RETURN');
    const returned=select(filters,'退货状态',choices(returnStates),selection.returnStatus||'');
    const refunded=select(filters,'退款状态',choices(refundStates),selection.refundStatus||'');
    const date=select(filters,'日期口径',[['CREATED','千易创建时间（北京时间）'],['UPDATED','千易更新时间（北京时间）'],['RECEIPT','实际退回入库日期（店铺时区）']],selection.dateField||'CREATED');
    const start=input(filters,'开始日期','date',selection.start||''),end=input(filters,'结束日期','date',selection.end||'');
    const search=input(filters,'平台订单号 / 千易售后单号','search',selection.numbers||'');search.placeholder='精确编号，可用空格分隔多个';search.maxLength=16000;
    const actions=el('div',null,m.body,'fi-actions'),status=el('p','',m.body,'fq-progress');status.setAttribute('role','status');
    const totals=el('div',null,m.body,'fq-totals'),records=el('div',null,m.body),pages=el('div',null,m.body,'fi-actions'),basis=el('p','',m.body,'fi-muted');
    function query(){return {...selection,currency:'',platform:platform.value,groupId:group.value,shopId:shop.value,type:type.value,returnStatus:returned.value,refundStatus:refunded.value,dateField:date.value,start:start.value,end:end.value,numbers:search.value.trim(),page};}
    function showSummary(result){
      totals.replaceChildren();const summary=result.summary||{};
      const rows=[['筛选单据',num(result.total)+' 笔']];
      for(const amount of summary.refundTotals||[])rows.push(['千易订单退款金额（'+(amount.currency==='UNKNOWN'?'币种未返回':amount.currency)+'）',
        amount.currency==='UNKNOWN'?'币种待确认，暂不合计':num(amount.amount)+(Number(amount.known_count)<Number(amount.records)?' · 部分金额未返回':'')]);
      for(const [label,value]of rows){const item=el('section',null,totals);el('span',label,item);el('strong',value,item);}
    }
    async function load(){
      controller?.abort();controller=new AbortController();
      const current=++sequence,selected=query();onSelection(selected);status.textContent='正在读取千易售后数据…';status.classList.add('is-loading');m.feedback.textContent='';records.setAttribute('aria-busy','true');download.disabled=true;totals.hidden=true;records.hidden=true;pages.hidden=true;
      try{
        const result=await request('/qianyi/returns?'+new URLSearchParams(selected),{signal:controller.signal});if(!m.alive()||current!==sequence)return;
        applied=selected;showSummary(result);totals.hidden=false;records.hidden=false;pages.hidden=false;download.disabled=false;
        records.replaceChildren();pages.replaceChildren();
        for(const [node,key,map]of [[returned,'return_state',returnStates],[refunded,'refund_state',refundStates],[type,'aftersale_type',types]])for(const row of result.items){const raw=row[key];if(raw&&![...node.options].some(o=>o.value===raw))el('option',state(map,raw),node).value=raw;}
        table(records,['店铺 / 平台','千易售后单 / 平台订单','类型','退货状态','退款状态','创建 / 收货时间','千易订单退款金额','操作'],result.items.map(r=>{
          const lead=el('div',null,null);el('span',r.platform==='SHOPEE'?'虾皮':'TK',lead,'fi-tag');el('span',' '+r.shop_name,lead);
          const ids=el('div',null,null);m.button(ids,r.return_number,()=>openDetail({request,shopId:r.shop_id,returnNumber:r.return_number}),'fi-cost-link');el('small',r.order_id||'千易未返回平台订单号',ids);
          const time=el('div',stamp(r.source_created_at),null);el('small','收货：'+stamp(r.receive_time),time);
          const refund=el('div',num(r.refund_amount),null);el('small',r.refund_currency==='UNKNOWN'?'千易未返回币种':r.refund_currency||'千易未返回币种',refund);
          return [lead,ids,state(types,r.aftersale_type),tag(state(returnStates,r.return_state),r.return_state),tag(state(refundStates,r.refund_state),r.refund_state),time,refund,m.button(null,'查看千易数据',()=>openDetail({request,shopId:r.shop_id,returnNumber:r.return_number}),'fi-cost-link')];
        }));
        if(!result.items.length)el('p','当前筛选没有千易售后单。可切换日期口径，或清除日期查看尚未入库的单据。',records,'fi-muted');
        const currentPage=Number(result.page);m.button(pages,'上一页',()=>{page=currentPage-1;return load();}).disabled=currentPage<=1;
        el('span','第 '+currentPage+' 页 · 每页 50 条',pages);m.button(pages,'下一页',()=>{page=currentPage+1;return load();}).disabled=currentPage*50>=result.total;
        status.textContent='千易售后 · 共 '+num(result.total)+' 条';basis.textContent=result.basis;
      }catch(e){if(m.alive()&&current===sequence){status.textContent='读取失败，请重试当前筛选。';m.feedback.textContent=e.message;}}
      finally{if(m.alive()&&current===sequence){status.classList.remove('is-loading');records.setAttribute('aria-busy','false');}}
    }
    m.button(actions,'查询',()=>{page=1;return load();},'fi-primary');m.button(actions,'清除日期',()=>{start.value='';end.value='';page=1;return load();});
    const download=m.button(actions,'导出千易售后 Excel',async()=>{
      if(!applied)return;const current=sequence;exportController?.abort();exportController=new AbortController();
      const timer=setTimeout(()=>exportController.abort(),120000);
      try{
        const response=await fetch('/api/v1/finance/qianyi/returns/export?'+new URLSearchParams(applied),{credentials:'same-origin',cache:'no-store',signal:exportController.signal});
        if(!response.ok){let message='千易报表导出失败';try{message=(await response.json()).message||message;}catch{}throw Error(message);}
        const blob=await response.blob();if(!m.alive()||current!==sequence)return;
        const url=URL.createObjectURL(blob),a=el('a',null,m.body);a.href=url;a.download='千易售后-'+(applied.start||'全部')+'-'+(applied.end||'日期')+'.xlsx';a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),10000);
      }finally{clearTimeout(timer);}
    });
    function shopOptions(){
      const value=shop.value;shop.replaceChildren();el('option','全部店铺',shop).value='';
      shops.filter(s=>(!platform.value||s.platform===platform.value)&&(!group.value||(group.value==='UNGROUPED'?!s.group_id:s.group_id===group.value)))
        .forEach(s=>el('option',s.display_name||s.name,shop).value=s.id);shop.value=value;
    }
    for(const field of [group,platform,shop,type,returned,refunded,date,start,end])field.onchange=()=>{if(field===group||field===platform){shop.value='';shopOptions();}page=1;load();};
    search.onkeydown=e=>{if(e.key==='Enter'){page=1;load();}};m.d.addEventListener('close',()=>{sequence++;controller?.abort();exportController?.abort();});shopOptions();await load();
  }
  globalThis.FinanceQianyi={openCompany,openReturns,openDetail,mountReturns};
})();
