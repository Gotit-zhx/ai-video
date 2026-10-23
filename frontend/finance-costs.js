/* Cost configuration and SKU mappings. Explicit local previews never write to the service. */
(() => {
  'use strict';
  let state,loadSequence=0;
  const defaultRules=()=>({currency:'CNY',unit:'',basis:'order',missing:'previous',freight:'',inbound:''});
  const day=offset=>{const d=new Date();d.setDate(d.getDate()-offset);return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;};
  const fresh=()=>({tab:'costs',shopId:'',collapsedShopId:'',spus:{},warehouse:'',month:'',search:'',status:'',page:1,orderPage:1,orderContext:null,start:day(29),end:day(0),mappings:{},settings:{},rules:defaultRules()});
  const reset=()=>{loadSequence++;state=fresh();document.querySelectorAll('.fc-dialog').forEach(n=>n.remove());};
  reset();
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=String(text);if(cls)n.className=cls;parent?.append(n);return n;};
  const action=(parent,text,fn,cls='')=>{const n=el('button',text,parent,cls);n.type='button';n.onclick=async()=>{if(n.disabled)return;n.disabled=true;try{await fn();}catch(e){const feedback=n.closest('dialog')?.querySelector('[role=alert]')||document.querySelector('.fc-feedback');if(feedback)feedback.textContent=e.message||'操作失败，请重试';}finally{n.disabled=false;}};return n;};
  const format=value=>value==null?'—':Number(value).toLocaleString('zh-CN',{maximumFractionDigits:4});
  function select(parent,label,options,value,change){
    const wrap=el('label',null,parent);el('span',label,wrap);const n=el('select',null,wrap);n.setAttribute('aria-label',label);
    for(const [value,label]of options)el('option',label,n).value=value;
    n.value=value||'';n.onchange=()=>change(n.value);return n;
  }
  function tag(parent,text,tone=''){return el('span',text,parent,'fc-tag '+tone);}
  function table(parent,headers,rows,empty){
    const wrap=el('div',null,parent,'fi-table-wrap'),t=el('table',null,wrap,'fi-table fc-table'),h=el('tr',null,el('thead',null,t));
    headers.forEach(text=>el('th',text,h).scope='col');const body=el('tbody',null,t);
    for(const values of rows){const row=el('tr',null,body);for(const value of values){const cell=el('td',null,row);if(value instanceof Node)cell.append(value);else cell.textContent=value??'—';}}
    if(!rows.length)el('p',empty||'暂无数据',parent,'fc-empty');
    return t;
  }
  function panel(parent,title,subtitle){
    const n=el('section',null,parent,'fi-card fc-panel'),h=el('div',null,n,'fi-card-head'),copy=el('div',null,h);el('h3',title,copy);if(subtitle)el('p',subtitle,copy);return {n,h};
  }
  function modal(title){
    const n=el('dialog',null,document.body,'fi-dialog fc-dialog'),head=el('div',null,n,'fi-dialog-head');el('h3',title,head);action(head,'×',()=>n.close(),'fi-close').setAttribute('aria-label','关闭');
    const body=el('div',null,n,'fi-dialog-body'),foot=el('div',null,n,'fi-dialog-foot');action(foot,'关闭',()=>n.close());n.addEventListener('close',()=>n.remove());n.showModal();return {n,body,foot};
  }
  function normalizedSku(code){
    let value=String(code||'').replace(/\s+/g,'').replace(/（/g,'(').replace(/）/g,')').replace(/[×＊]/g,'*'),previous;
    do{previous=value;value=value.replace(/^\(([^()]+)\)(\*[1-9][0-9]*)?$/,'$1$2');}while(previous!==value);
    return value;
  }
  function matchingCosts(code,costs){
    const exact=costs.filter(c=>c.sku===code.trim());
    if(exact.length)return {rows:exact,ambiguous:false};
    const normalized=normalizedSku(code),matches=normalized?costs.filter(c=>normalizedSku(c.sku)===normalized):[];
    const ambiguous=new Set(matches.map(c=>c.sku)).size>1;
    return {rows:ambiguous?[]:matches,ambiguous};
  }
  function automaticCosts(code,costs){
    const match=matchingCosts(code,costs);
    if(match.rows.length||match.ambiguous)return match;
    const bundle=normalizedSku(code).match(/^([^*]+)\*([1-9][0-9]{0,5})$/);
    if(!bundle)return match;
    return {...matchingCosts(bundle[1],costs),bundleQuantity:Number(bundle[2])};
  }
  const automaticMapping=saved=>['EXACT_CODE','NORMALIZED_CODE','BUNDLE_CODE'].includes(saved?.method);
  function candidate(product,costs,warehouse){
    const code=String(product.seller_sku||'').trim(),available=costs.filter(c=>!warehouse||c.warehouse===warehouse);
    const match=matchingCosts(code,costs),exact=match.rows.filter(c=>!warehouse||c.warehouse===warehouse);
    if(match.ambiguous)return {status:'conflict',label:'编码重复 · 需核对',note:'忽略括号和空格后对应多个成本型号，请核对后关联',code:'',factor:1};
    if(exact.length===1)return {status:'exact',label:'编码一致 · 待确认',note:'型号完全一致，确认仓库和计价单位后可关联',code:exact[0].sku,factor:1};
    if(exact.length>1)return {status:'missing',label:'请选择成本仓',note:'相同型号在多个仓库有不同成本',code:'',factor:1};
    const bundle=normalizedSku(code).match(/^([^*]+)\*([1-9][0-9]{0,5})$/);
    if(bundle){const base=matchingCosts(bundle[1],costs),matches=base.rows.filter(c=>available.includes(c));if(base.ambiguous)return {status:'conflict',label:'编码重复 · 需核对',note:'组合装基础型号对应多个成本编码，请核对后关联',code:'',factor:1};if(matches.length===1)return {status:'bundle',label:'组合装 · 待确认',note:'按编码识别的数量仅为建议，需确认每个销售单位对应的成本单位数',code:matches[0].sku,factor:Number(bundle[2])};}
    return {status:'missing',label:'待关联',note:code?'未找到唯一对应的成本型号':'平台未返回商家 SKU，需人工选择成本型号',code:'',factor:1};
  }
  function mount(root,context={}){
    const preview=context.preview||null,live=preview?.live===true,shops=context.shops||[],editable=Boolean(context.canManage),costs=preview?.costs||[],products=preview?.products||[];
    if(live){const previous=state.shopId===preview.shopId?state.warehouse:'';state.shopId=preview.shopId||'';state.mappings=preview.mappings||{};state.warehouse=previous||preview.settings?.warehouse||'';state.rules={...defaultRules(),...preview.settings?.rules,currency:'CNY'};}
    const json=body=>({headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    async function reload(shopId=state.shopId){const run=++loadSequence;const data=await context.request('/costs?shopId='+encodeURIComponent(shopId));if(run!==loadSequence||!root.isConnected)return;if(shopId!==state.shopId){state.page=1;state.search='';state.status='';state.collapsedShopId='';}mount(root,{...context,preview:data});}
    const months=[...new Set(costs.flatMap(c=>Object.keys(c.prices||{})))].sort().reverse(),warehouses=[...new Set(costs.map(c=>c.warehouse))];
    if(!shops.some(s=>s.id===state.shopId))state.shopId=preview?.shopId&&shops.some(s=>s.id===preview.shopId)?preview.shopId:shops[0]?.id||'';
    if(!months.includes(state.month))state.month=months[0]||'';
    const getShop=()=>shops.find(s=>s.id===state.shopId);
    const applies=()=>Boolean(preview&&getShop()?.group_id===preview.groupId);
    if(!state.warehouse&&applies()&&preview?.suggestedWarehouse)state.warehouse=preview.suggestedWarehouse;
    const currentCosts=()=>applies()?costs:[];
    const currentProducts=()=>products.filter(p=>p.shop_id===state.shopId);
    const key=p=>[p.shop_id,p.product_id,p.sku_id].join(':');
    const mapped=p=>{
      const draft=state.mappings[key(p)+':'+state.warehouse]||state.mappings[key(p)];
      if(draft?.warehouse===state.warehouse&&(!live||draft.groupId===preview.groupId))return draft;
      if(state.warehouse)return null;
      const matches=Object.entries(state.mappings).filter(([id,m])=>id.startsWith(key(p)+':')&&(!live||m.groupId===preview.groupId)).map(([,m])=>m);
      return matches.length&&new Set(matches.map(m=>m.code)).size===1?{...matches[0],warehouse:'',factor:null}:null;
    };
    const recommendation=p=>{
      const saved=mapped(p),suggestion=candidate(p,currentCosts(),state.warehouse);
      if(!saved)return suggestion;
      const review=automaticMapping(saved)&&suggestion.status==='conflict';
      return {...saved,status:review?'review':'draft',label:review?'已关联 · 需核对':live?(automaticMapping(saved)?'已自动关联'+(saved.bundleQuantity?' · ×'+saved.bundleQuantity:''):'已关联'):'预览已关联',note:review?suggestion.note:live?(saved.bundleQuantity?`基础成本型号已关联，订单按组合数量 ×${saved.bundleQuantity} 计算成本`:'已保存，公司财务成员共享'):'本页草稿，尚未保存到正式系统'};
    };
    const tone=rec=>rec.status==='draft'?'good':rec.status==='missing'?'danger':'warn';
    const catalogFor=shop=>preview?.shopCatalogs?.[shop.id]||(shop.group_id===preview?.groupId?{costRows:costs.length,sourceName:preview.sourceName}:{costRows:0,sourceName:''});
    async function chooseShop(id){
      if(!id)return;
      state.orderContext=null;
      if(live){await reload(id);return;}
      state.settings[state.shopId]={warehouse:state.warehouse,rules:{...state.rules}};state.shopId=id;
      const settings=state.settings[id];state.warehouse=settings?.warehouse||(applies()?preview.suggestedWarehouse:'')||'';state.rules=settings?.rules||defaultRules();
      state.page=1;state.search='';state.status='';state.collapsedShopId='';draw();
    }
    function missingAutomatic(){
      return live&&editable&&currentProducts().some(p=>automaticCosts(String(p.seller_sku||''),currentCosts()).rows.some(c=>!state.mappings[key(p)+':'+c.warehouse]));
    }
    async function autoMatch(){
      const run=++loadSequence;toast('正在按相同编码自动关联 SKU…');
      try{
        const data=await context.request('/costs/'+encodeURIComponent(state.shopId)+'/auto-match',{method:'POST',...json({})});
        if(run!==loadSequence||!root.isConnected)return;
        mount(root,{...context,preview:data});
      }catch(e){
        if(run!==loadSequence||!root.isConnected)return;
        toast('自动关联暂未完成：'+e.message+'。');action(root.querySelector('.fc-feedback'),'重试自动关联',autoMatch,'fi-record-link');
      }
    }
    function toast(message){root.querySelector('.fc-feedback').textContent=message;}
    function unavailable(message){const d=modal('功能预留');el('p',message||'页面结构已预留，正式保存与同步将在成本口径确认后接入。',d.body,'fi-muted');}
    function importDialog(){
      if(live){
        const d=modal('导入成本表'),group=select(d.body,'归属分组',[['','请选择'],...(context.groups||[]).map(g=>[g.id,g.name])],getShop()?.group_id||'',()=>{});
        const label=el('label',null,d.body);el('span','上传 XLSX / CSV',label);const file=el('input',null,label);file.type='file';file.accept='.xlsx,.csv';file.setAttribute('aria-label','成本表文件');
        el('p','支持「型号、仓库、月份、单价」四列，或仓库 / 型号加多个月份的横排成本表。首个工作表需为数值；公式请先转换为值。',d.body,'fi-muted');
        el('p','成本金额统一按人民币（CNY）导入；相同文件不重复导入，新价格保留历史版本。',d.body,'fi-muted');
        el('p','',d.body,'fc-alert').setAttribute('role','alert');
        action(d.foot,'确认导入',async()=>{if(!group.value||!file.files?.length)throw Error('请选择分组和成本文件');const form=new FormData();form.append('groupId',group.value);form.append('file',file.files[0]);const result=await context.request('/costs/import',{method:'POST',body:form});d.n.close();await reload();toast(result.duplicate?'此文件已经导入。':`已导入 ${result.rows} 条成本资料，请确认计价口径。`);},'fi-primary');return;
      }
      const d=modal('导入成本表 · 设计预览');el('p','文件先进入待确认区，核对仓库、月份和计价口径后再启用。',d.body,'fi-muted');
      const area=el('div',null,d.body,'fc-upload');el('strong',preview?.sourceName||'选择 Excel 成本表',area);el('p',preview?'当前展示的是已提供文件的只读解析结果。':'支持按月份横排的成本表，导入后转为型号、仓库、月份、单价。',area);
      const grid=el('div',null,d.body,'fi-grid2');select(grid,'归属分组',(context.groups||[]).map(g=>[g.id,g.name]),getShop()?.group_id||'',()=>{});
      select(grid,'文件用途',[['unit','SKU 单位成本表']], 'unit',()=>{});
      table(d.body,['识别字段','对应内容'],[['成本型号','型号'],['成本仓库','仓库'],['成本月份','月份列'],['单位成本','期末单价']]);
      tag(d.body,'人民币 CNY · 计价单位待确认','warn');
      const n=action(d.foot,'确认导入（待接入）',()=>{});n.disabled=true;
    }
    function history(cost){
      const d=modal('月度成本记录');el('strong',cost.sku,d.body);el('p',cost.warehouse+' · 原表单价，人民币 CNY',d.body,'fi-muted');
      table(d.body,['月份','单价（人民币）','状态'],Object.entries(cost.prices).sort(([a],[b])=>b.localeCompare(a)).map(([month,value])=>[month,value==null?'—':'¥ '+format(value),value===0?'零值待确认':value==null?'缺失':'原表数据']));
    }
    function mappingDialog(product){
      if(!preview)return unavailable();
      const d=modal(live?'关联成本型号':'关联成本型号 · 仅预览'),rec=recommendation(product);
      tag(d.body,'归属店铺：'+(getShop()?.name||getShop()?.display_name||''),'good');
      el('strong',product.seller_sku||'未提供商家 SKU',d.body);el('p',product.spec||product.name,d.body,'fi-muted');
      el('p',`商品 ID：${product.product_id||'待补充'} · 规格 SKU ID：${product.sku_id||'待补充'}`,d.body,'fi-muted');
      if(rec.status==='conflict')el('p',rec.note,d.body,'fc-alert');
      const choices=[...new Set(currentCosts().filter(c=>!state.warehouse||c.warehouse===state.warehouse).map(c=>c.sku))];
      const cost=select(d.body,'成本型号',[['','请选择'],...choices.map(s=>[s,s])],rec.code,()=>{});
      const quantity=el('label',null,d.body);el('span','每个销售单位对应多少个成本单位',quantity);
      const factor=el('input',null,quantity);factor.type='number';factor.min='0.000001';factor.step='any';factor.value=String(rec.factor||rec.bundleQuantity||1);factor.setAttribute('aria-label','成本单位换算数量');
      el('p','例如：销售规格为 2 包装，成本为单包价，填 2；成本型号本身就是 2 包装，填 1。',d.body,'fi-muted');
      let confirmed;if(live){const label=el('label',null,d.body);confirmed=el('input',null,label);confirmed.type='checkbox';confirmed.style.width='18px';confirmed.style.minHeight='18px';el('span','我已核对销售规格、成本型号和单位换算',label);}
      const feedback=el('p','',d.body,'fc-alert');feedback.setAttribute('role','alert');
      action(d.foot,live?'保存关联':'应用到预览',async()=>{
        if(!cost.value||!state.warehouse||!Number.isFinite(Number(factor.value))||Number(factor.value)<=0){feedback.textContent='请选择成本型号、成本仓，并填写大于零的换算数量。';return;}
        if(live){if(!confirmed.checked)throw Error('请先确认销售规格与成本单位');await context.request('/costs/'+encodeURIComponent(state.shopId)+'/mapping',{method:'PUT',...json({product_id:product.product_id,sku_id:product.sku_id,code:cost.value,factor:factor.value,warehouse:state.warehouse,confirmed:true,version:rec.version||0})});d.n.close();await reload();toast('SKU 关联已保存。');return;}
        state.mappings[key(product)]={code:cost.value,factor:Number(factor.value),warehouse:state.warehouse};
        d.n.close();draw();toast('关联已应用到本页预览；尚未写入正式系统。');
      },'fi-primary').disabled=!editable;
    }
    function pager(parent,total){
      const n=el('div',null,parent,'fi-pagebar');el('span',`${total} 条 · 第 ${state.page} / ${Math.max(1,Math.ceil(total/12))} 页`,n);const actions=el('div',null,n,'fi-actions');
      action(actions,'上一页',()=>{state.page--;body();}).disabled=state.page<=1;
      action(actions,'下一页',()=>{state.page++;body();}).disabled=state.page*12>=total;
    }
    function toolbar(parent,withStatus=false){
      const bar=el('div',null,parent,'fc-table-tools');
      const search=el('input',null,bar);search.type='search';search.placeholder='搜索型号、商家 SKU 或规格';search.value=state.search;search.setAttribute('aria-label','搜索成本或商品');
      search.onchange=()=>{state.search=search.value.trim();state.page=1;body();};
      select(bar,'成本月份',[['','待导入'],...(applies()?months:[]).map(m=>[m,m])],state.month,v=>{state.month=v;state.page=1;body();});
      select(bar,'成本仓库',[['','请选择仓库'],...(applies()?warehouses:[]).map(w=>[w,w])],state.warehouse,v=>{state.warehouse=v;state.page=1;body();});
      if(withStatus)select(bar,'关联状态',[['','全部'],['good','已关联'],['danger','未关联'],['warn','需核对']],state.status,v=>{state.status=v;state.page=1;body();});
    }
    function costPanel(parent){
      const source=panel(parent,'母表与月度成本',applies()?'仅用于「'+(preview.groupName||'所选分组')+'」的成本资料':'成本资料按分组管理');
      const controls=el('div',null,source.h,'fi-actions');if(editable)action(controls,'导入成本表',importDialog,'fi-primary');
      if(applies()&&costs.length){
        const file=el('div',null,source.n,'fc-file');el('span','XLSX',file,'fc-file-icon');const copy=el('div',null,file);el('strong',preview.sourceName,copy);el('small',`主表 ${costs.length} 条 · ${warehouses.length} 个仓库 · 已识别 ${months.length} 个月份`,copy);tag(file,'人民币 CNY','good');
        const notice=el('p',null,source.n,'fc-inline-note');notice.textContent='原表中的多包装型号保留独立成本。辅助页的赠品与别名未计入主表。';
      }
      toolbar(source.n);
      const rows=currentCosts().filter(c=>(!state.warehouse||c.warehouse===state.warehouse)&&(!state.search||c.sku.toLowerCase().includes(state.search.toLowerCase())));
      table(source.n,['成本型号','成本仓','月份','单价（人民币）','币种','操作'],rows.slice((state.page-1)*12,state.page*12).map(c=>[
        el('span',c.sku,null,'fc-code'),c.warehouse,state.month,c.prices[state.month]==null?'—':'¥ '+format(c.prices[state.month]),tag(null,'人民币 CNY'),action(null,'查看历史',()=>history(c),'fi-record-link')
      ]),applies()?'当前筛选没有成本记录':'该店铺分组尚未配置成本资料。');pager(source.n,rows.length);
    }
    function productRows(parent){
      const source=el('div',null,parent,'fc-shop-source');
      if(applies()&&costs.length){
        tag(source,'成本表已导入','good');el('strong',preview.sourceName,source);
        el('span',`${costs.length} 条成本资料 · ${warehouses.length} 个仓库 · ${months.length} 个月份`,source);
        action(source,'查看成本表',()=>{state.tab='costs';state.page=1;state.search='';draw();},'fi-record-link');
      }else{
        el('span','该店铺所属分组尚未导入成本表。',source);
        if(editable)action(source,'导入成本表',importDialog,'fi-record-link');
      }
      const info=el('div',null,parent,'fc-shop-sku-info');
      el('span',live?`已读取 ${currentProducts().length} 个 SKU · 最近 ${preview.sampleOrders||0} 笔已同步订单（最多 ${preview.orderLimit||2000} 笔）`:`${currentProducts().length} 个 SKU · 已同步订单样本`,info);
      if(live)action(info,'刷新订单 SKU',()=>reload(),'fi-record-link');
      toolbar(parent,true);
      const groups=new Map();
      for(const product of currentProducts()){
        const group=groups.get(product.product_id)||{product,rows:[]};group.rows.push({product,rec:recommendation(product)});groups.set(product.product_id,group);
      }
      const counts=group=>({good:group.rows.filter(r=>tone(r.rec)==='good').length,danger:group.rows.filter(r=>tone(r.rec)==='danger').length,warn:group.rows.filter(r=>tone(r.rec)==='warn').length});
      const matches=row=>(!state.status||tone(row.rec)===state.status)&&(!state.search||[row.product.product_id,row.product.sku_id,row.product.seller_sku,row.product.name,row.product.spec].join(' ').toLowerCase().includes(state.search.toLowerCase()));
      const spus=[...groups.values()].filter(group=>group.rows.some(matches)).sort((a,b)=>{const x=counts(a),y=counts(b);return Number(Boolean(y.danger||y.warn))-Number(Boolean(x.danger||x.warn));});
      const list=el('div',null,parent,'fc-spu-list');
      const pageSpus=spus.slice((state.page-1)*12,state.page*12);
      const selected=pageSpus.find(g=>state.spus[state.shopId+':'+g.product.product_id])||(pageSpus.every(g=>state.spus[state.shopId+':'+g.product.product_id]===undefined)?pageSpus[0]:null);
      let openDetail;
      for(const [index,group]of pageSpus.entries()){
        const product=group.product,totals=counts(group),id=state.shopId+':'+product.product_id,expanded=group===selected;
        state.spus[id]=expanded;
        const status=totals.danger?'danger':totals.warn?'warn':'good',card=el('section',null,list,'fc-spu '+status);card.dataset.productId=product.product_id;
        const heading=action(card,'',()=>{for(const g of pageSpus)state.spus[state.shopId+':'+g.product.product_id]=false;state.spus[id]=!expanded;body();},'fc-spu-parent');
        card.classList.toggle('is-expanded',expanded);
        heading.setAttribute('aria-expanded',String(expanded));heading.setAttribute('aria-label','展开商品 '+(product.name||product.product_id));heading.setAttribute('aria-controls','fc-spu-'+product.product_id);
        el('span',expanded?'▾':'▸',heading,'fc-shop-chevron').setAttribute('aria-hidden','true');
        if(/^https:\/\//.test(product.image||'')){const image=el('img',null,heading,'fc-spu-image');image.src=product.image;image.alt='';image.loading='lazy';image.referrerPolicy='no-referrer';}
        const copy=el('span',null,heading,'fc-spu-copy');el('strong',product.name||'商品名称待补充',copy);el('small','SPU ID：'+product.product_id+' · '+group.rows.length+' 个 SKU',copy);
        const badges=el('span',null,heading,'fc-spu-counts');
        tag(badges,'已关联 '+totals.good,'good');if(totals.danger)tag(badges,'未关联 '+totals.danger,'danger');if(totals.warn)tag(badges,'需核对 '+totals.warn,'warn');
        const detail=el('div',null,null,'fc-spu-detail');detail.id='fc-spu-'+product.product_id;detail.hidden=!expanded;
        if(!expanded){card.append(detail);if((index%4===3||index===pageSpus.length-1)&&openDetail){list.append(openDetail);openDetail=null;}continue;}
        openDetail=detail;
        const detailHead=el('div',null,detail,'fc-spu-detail-head');el('strong',product.name||'商品 SKU 明细',detailHead);action(detailHead,'收起明细',()=>{state.spus[id]=false;body();},'fi-record-link');
        const visible=group.rows.filter(matches).sort((a,b)=>({danger:0,warn:1,good:2})[tone(a.rec)]-({danger:0,warn:1,good:2})[tone(b.rec)]);
        table(detail,['SKU 规格','商家 SKU','成本型号','关联状态','操作'],visible.map(({product,rec})=>{
          const item=el('div',null,null,'fc-product');const copy=el('div',null,item);el('strong',product.spec||'规格待补充',copy);el('small','规格 SKU ID：'+product.sku_id,copy);
          const badge=tag(null,rec.status==='missing'?'未关联':rec.label,tone(rec));badge.title=rec.note;
          const button=editable?action(null,mapped(product)?'查看 / 调整':'核对关联',()=>mappingDialog(product),'fi-record-link'):'只读';
          const costCode=el('span',rec.code||'未找到',null,'fc-code');
          if(rec.bundleQuantity)el('small','组合数量 ×'+rec.bundleQuantity,costCode,'fc-bundle-quantity');
          return [item,el('span',product.seller_sku||'—',null,'fc-code'),costCode,badge,button];
        }));
        if(index%4===3||index===pageSpus.length-1){list.append(openDetail);openDetail=null;}
      }
      if(!spus.length)el('p',currentProducts().length?'没有符合筛选的商品，请清空搜索或调整关联状态。':'该店铺已同步订单暂未提供 SKU，成本表仍可查看。',list,'fc-empty');
      pager(parent,spus.length);
      el('p','按商家 SKU 关联与计量，销售规格文字不参与匹配。绿：关联成功 · 红：未找到型号 · 橙：成本编码存在歧义。每家店铺独立保存。',parent,'fc-inline-note');
    }
    function productPanel(parent){
      const p=panel(parent,'店铺 → SPU → SKU','相同编码自动关联。商品外层显示关联结果，未关联和需核对的优先展示。');
      const tree=el('div',null,p.n,'fc-shop-tree');
      if(!shops.length)el('p','请先在财务店铺管理中授权店铺。',tree,'fc-empty');
      const ordered=[...shops].sort((a,b)=>Boolean(catalogFor(b).costRows)-Boolean(catalogFor(a).costRows));
      for(const shop of ordered){
        const selected=shop.id===state.shopId,expanded=selected&&state.collapsedShopId!==shop.id,catalog=catalogFor(shop);
        const branch=el('section',null,tree,'fc-shop-branch'+(expanded?' is-expanded':''));branch.dataset.shopId=shop.id;
        const head=action(branch,'',async()=>{
          if(selected){state.collapsedShopId=expanded?shop.id:'';body();return;}
          const pending=el('span','正在读取店铺 SKU…',branch,'fc-shop-loading');head.setAttribute('aria-busy','true');
          try{await chooseShop(shop.id);}finally{pending.remove();head.removeAttribute('aria-busy');}
        },'fc-shop-parent');
        head.setAttribute('aria-label','查看店铺 '+(shop.name||shop.display_name));head.setAttribute('aria-expanded',String(expanded));head.setAttribute('aria-controls','fc-shop-'+shop.id);
        el('span',expanded?'▾':'▸',head,'fc-shop-chevron').setAttribute('aria-hidden','true');
        const copy=el('span',null,head,'fc-shop-title');el('strong',shop.name||shop.display_name,copy);
        el('small',(shop.region||'')+' · '+(context.groups?.find(g=>g.id===shop.group_id)?.name||'未分组'),copy);
        tag(head,shop.platform==='SHOPEE'?'虾皮':'TikTok');
        const totals=el('span',null,head,'fc-shop-counts');
        tag(totals,catalog.costRows?`成本资料 ${catalog.costRows} 条`:'尚无成本表',catalog.costRows?'good':'');
        el('small',selected?`${currentProducts().length} 个 SKU`:'展开查看 SKU',totals);
        const children=el('div',null,branch,'fc-shop-children');children.id='fc-shop-'+shop.id;children.hidden=!expanded;
        if(expanded)productRows(children);
      }
    }
    function rulesPanel(parent){
      const p=panel(parent,'成本表设置','成本金额统一使用人民币（CNY）。'),form=el('div',null,p.n,'fc-form');
      select(form,'默认成本仓',[['','自动匹配店铺国家'],...(applies()?warehouses:[]).map(w=>[w,w])],state.warehouse,v=>{state.warehouse=v;}).disabled=!editable;
      for(const [label,key,options]of [
                ['默认计价单位','unit',[['','按商家 SKU'],['包','包'],['片','片'],['箱','箱'],['套','套']]],
                ['成本月份依据','basis',[['order','下单月份'],['shipment','发货月份（需平台返回发货时间）']]],
                ['当月缺价时','missing',[['previous','用最近历史月份估算并标记'],['pending','保持待补价，不估算']]]
      ])select(form,label,options,state.rules[key],v=>{state.rules[key]=v;}).disabled=!editable;
      el('p','商品标准成本 = 商家 SKU 对应单价 × 购买数量 × 组合数量。优先使用完整组合装价格；退款不自动冲回商品成本。修改设置后后台重算并保留记录。',p.n,'fc-inline-note');
      if(editable)action(form,live?'保存成本设置':'应用到预览',async()=>{
        if(live){await context.request('/costs/'+encodeURIComponent(state.shopId)+'/settings',{method:'PUT',...json({warehouse:state.warehouse,rules:state.rules,version:preview.settingsVersion||0})});await reload();toast('成本设置已保存。');return;}
        if(!preview)return unavailable();draw();toast('成本设置已应用到预览；正式数据未变更。');
      },'fi-primary');
    }
    async function orderCostsPanel(parent){
      const p=panel(parent,'订单商品成本','按商家 SKU 计算，每笔订单保留单价、数量和成本月份。');
      if(!live){el('p','正式系统按已同步订单计算；此预览不生成成本金额。',p.n,'fc-inline-note');return;}
      const origin=state.orderContext;
      if(origin){
        const scopeName=origin.shopId?shops.find(s=>s.id===origin.shopId)?.name||'所选店铺':origin.groupId?context.groups?.find(g=>g.id===origin.groupId)?.name||'所选分组':'全部店铺';
        el('p',origin.recordId?'当前仅展示刚才点击记录关联的订单，包含其他下单日期的订单。':'当前范围：'+scopeName+' · '+({ORDER:'下单日期',REFUND:'退款日期',SETTLEMENT:'结算日期',BILL:'账单日期',PENDING:'待结算日期',RECONCILIATION:'账单日期'}[origin.kind]||'下单日期'),p.n,'fc-inline-note');
        if(origin.recordId)action(p.h,'查看该店全部订单',()=>{state.orderContext=null;state.orderPage=1;body();},'fi-record-link');
      }
      const bar=el('div',null,p.n,'fc-table-tools');
      for(const [key,title]of [['start','开始日期'],['end','结束日期']]){
        const label=el('label',null,bar);el('span',title,label);const input=el('input',null,label);input.type='date';input.value=state[key];input.setAttribute('aria-label',title);
        input.disabled=!!origin?.recordId;input.onchange=()=>{state[key]=input.value;state.orderPage=1;body();};
      }
      action(bar,'刷新成本',()=>refresh(),'fi-record-link');
      const content=el('div',null,p.n,'fc-order-results'),feedback=el('p','正在读取订单成本…',p.n,'fc-inline-note');
      const run=++loadSequence;let timer,busy=false,signature='';
      async function refresh(){
        if(busy||run!==loadSequence||!p.n.isConnected)return;
        busy=true;clearTimeout(timer);
        try{
        const result=await context.request('/costs/orders?'+new URLSearchParams({shopId:state.shopId,...state.orderContext,start:state.start,end:state.end,page:state.orderPage}));
        if(run!==loadSequence||!p.n.isConnected)return;feedback.textContent='';
        const nextSignature=JSON.stringify(result);
        if(signature!==nextSignature){
        const next=content.cloneNode(false),totals=result.productCosts?.total||{},stats=el('div',null,next,'fi-cost-stats');
        for(const [label,value]of [[Number(totals.pending_count)>0?'已计算部分成本':'商品标准成本','¥ '+format(Number(totals.pending_count)>0?totals.known_amount_cny:totals.amount_cny)],['已计算订单',format(totals.ready_count)],['待补齐订单',format(totals.pending_count)],['历史价估算',format(totals.estimated_count)]]){
          const n=el('div',null,stats);el('span',label,n);el('strong',value,n);
        }
        const orderTable=table(next,['店铺','订单编号','商品 SKU','下单日期','商品成本（人民币）','成本状态','操作'],(result.items||[]).map(row=>[
          shops.find(s=>s.id===row.shop_id)?.display_name||shops.find(s=>s.id===row.shop_id)?.name||'—',
          row.order_id||row.external_id,context.skuCell?.(row)||row.product_skus||'—',String(row.record_date||'').slice(0,10),row.product_cost_cny==null?'—':context.openRecord&&row.id?action(null,'¥ '+format(row.product_cost_cny),()=>context.openRecord(row.id),'fi-cost-link'):'¥ '+format(row.product_cost_cny),
          tag(null,row.product_cost_state||'后台计算中',row.product_cost_state==='已计算'?'good':row.product_cost_state==='待补齐'?'danger':'warn'),
          context.openRecord&&row.id?action(null,'查看计算明细',()=>context.openRecord(row.id),'fi-record-link'):'订单待同步'
        ]));
        [...orderTable.querySelectorAll('tbody tr')].forEach((row,index)=>row.dataset.updateKey=result.items[index].id);
        const pager=el('div',null,next,'fi-pagebar');el('span',`${result.total} 笔订单 · 第 ${result.page} 页`,pager);const controls=el('div',null,pager,'fi-actions');
        action(controls,'上一页',()=>{state.orderPage--;body();}).disabled=state.orderPage<=1;
        action(controls,'下一页',()=>{state.orderPage++;body();}).disabled=state.orderPage*result.pageSize>=result.total;
        el('p','未付款、未发货即取消的订单不计标准成本；退款不会自动退回商品成本。缺价不当作零，历史价估算保留来源月份。',next,'fc-inline-note');
        if(result.productCosts?.rebuilding)el('p','后台正在分批计算历史订单，完成后自动更新。',next,'fc-inline-note');
        if(globalThis.IncrementalDOM)IncrementalDOM.children(content,next);else content.replaceChildren(...next.childNodes);
        signature=nextSignature;
        }
        if(result.productCosts?.processing)timer=setTimeout(()=>{if(run===loadSequence&&p.n.isConnected&&state.tab==='orders'&&context.isActive?.()!==false&&!document.hidden)void refresh();},15000);
        }catch(error){if(run===loadSequence&&p.n.isConnected)feedback.textContent=error.message||'成本读取失败，请重试';}
        finally{busy=false;}
      }
      await refresh();
    }
    function body(){
      const host=root.querySelector('.fc-tab-content');if(!host)return;host.replaceChildren();
      ({costs:costPanel,mapping:productPanel,rules:rulesPanel,orders:orderCostsPanel})[state.tab](host);
    }
    function draw(){
      root.replaceChildren();root.className='fc-workspace';
      if(!live){const banner=el('div',null,root,'fc-preview-note');el('span',preview?'设计预览':'暂无成本资料',banner,'fc-preview-label');el('span','本页预览不会修改正式数据。',banner);}
      const hero=el('div',null,root,'fc-hero'),copy=el('div',null,hero);el('h2','店铺成本管理',copy);el('p','导入成本表，相同 SKU 编码自动关联。',copy);
      const shopControl=el('div',null,hero,'fc-shop-select');const shopSelect=select(shopControl,'财务店铺',shops.map(s=>[s.id,(s.name||s.display_name)+(catalogFor(s).costRows?' · 已有成本表':'')]),state.shopId,v=>{
        shopSelect.disabled=true;chooseShop(v).catch(e=>{shopSelect.value=state.shopId;toast(e.message);}).finally(()=>{shopSelect.disabled=false;});
      });
      const shop=getShop();if(shop){const meta=el('div',null,shopControl,'fc-shop-meta');tag(meta,shop.platform==='SHOPEE'?'虾皮':'TikTok');el('span',(shop.region||'')+' · '+(context.groups?.find(g=>g.id===shop.group_id)?.name||'未分组')+' · 已授权',meta);}
      action(shopControl,'成本表设置',()=>{state.tab='rules';draw();},'fi-record-link');
      const metrics=el('div',null,root,'fc-metrics fc-cost-metrics'),linked=currentProducts().filter(p=>mapped(p)).length;
      for(const [label,value,note]of [
        ['成本资料',applies()?costs.length:0,applies()&&months.length?`${warehouses.length} 个仓库 · 最新 ${months[0]}`:'请选择已导入成本表的店铺'],
        ['已关联 SKU',linked,`当前店铺共 ${currentProducts().length} 个订单 SKU`],
        ['待关联 SKU',currentProducts().length-linked,'未匹配到编码的商品集中核对']
      ]){const n=el('div',null,metrics,'fc-metric');el('span',label,n);el('strong',value,n);el('small',note,n);}
      const tabs=el('div',null,root,'fc-tabs');tabs.setAttribute('role','tablist');
      for(const [id,title,number]of [['costs','成本表','01'],['mapping','SKU 关联','02'],['orders','订单成本','03']]){
        const n=action(tabs,'',()=>{state.tab=id;state.page=1;state.search='';state.status='';draw();});n.setAttribute('role','tab');n.setAttribute('aria-selected',String(state.tab===id));n.setAttribute('aria-controls','fc-panel');el('span',number,n);el('strong',title,n);
      }
      el('p','',root,'fc-feedback').setAttribute('role','status');const host=el('div',null,root,'fc-tab-content');host.id='fc-panel';host.setAttribute('role','tabpanel');body();
    }
    draw();
    if(state.tab!=='orders'&&missingAutomatic())void autoMatch();
  }
  globalThis.FinanceCosts={mount,reset,candidate,shopId:()=>state.shopId,openOrders:filters=>{state.tab='orders';state.start=filters.start;state.end=filters.end;state.orderPage=1;state.orderContext={shopId:filters.shopId||'',groupId:filters.groupId||'',platform:filters.platform||'',currency:filters.currency||'',kind:filters.kind||'ORDER',recordId:filters.recordId||''};if(filters.shopId)state.shopId=filters.shopId;}};
})();
