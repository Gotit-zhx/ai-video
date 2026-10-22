/* Cost configuration and SKU mappings. Explicit local previews never write to the service. */
(() => {
  'use strict';
  let state,loadSequence=0;
  const defaultRules=()=>({currency:'',unit:'',basis:'shipment',missing:'pending',freight:'',inbound:''});
  const fresh=()=>({tab:'costs',shopId:'',warehouse:'',month:'',search:'',status:'',page:1,mappings:{},settings:{},rules:defaultRules()});
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
  function candidate(product,costs,warehouse){
    const code=String(product.seller_sku||'').trim(),available=costs.filter(c=>!warehouse||c.warehouse===warehouse);
    const exact=available.filter(c=>c.sku===code);
    const sizeCode=code.match(/BPants-(XXXXL|XXXL|XXL|XL|L|M|NB)(?![A-Za-z])/i)?.[1]?.toUpperCase();
    const sizeSpec=String(product.spec||'').match(/Pants[-\s]*(XXXXL|XXXL|XXL|XL|L|M|NB)(?![A-Za-z])/i)?.[1]?.toUpperCase();
    if(sizeCode&&sizeSpec&&sizeCode!==sizeSpec)return {status:'conflict',label:'规格冲突',note:`商家编码为 ${sizeCode}，销售规格为 ${sizeSpec}，请核对后关联`,code:'',factor:1};
    if(exact.length===1)return {status:'exact',label:'编码一致 · 待确认',note:'型号完全一致，确认仓库和计价单位后可关联',code:exact[0].sku,factor:1};
    if(exact.length>1)return {status:'missing',label:'请选择成本仓',note:'相同型号在多个仓库有不同成本',code:'',factor:1};
    const bundle=code.match(/^(?:[（(](.*)[）)]|(.+?))\s*[*×]\s*(\d+)$/);
    if(bundle){const base=(bundle[1]||bundle[2]).trim(),matches=available.filter(c=>c.sku===base);if(matches.length===1)return {status:'bundle',label:'组合装 · 待确认',note:'按编码识别的数量仅为建议，需确认每个销售单位对应的成本单位数',code:base,factor:Number(bundle[3])};}
    return {status:'missing',label:'待关联',note:code?'未找到唯一对应的成本型号':'平台未返回商家 SKU，需人工选择成本型号',code:'',factor:1};
  }
  function mount(root,context={}){
    const preview=context.preview||null,live=preview?.live===true,shops=context.shops||[],editable=Boolean(context.canManage),costs=preview?.costs||[],products=preview?.products||[];
    if(live){const previous=state.shopId===preview.shopId?state.warehouse:'';state.shopId=preview.shopId||'';state.mappings=preview.mappings||{};state.warehouse=previous||preview.settings?.warehouse||'';state.rules={...defaultRules(),...preview.settings?.rules};}
    const json=body=>({headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    async function reload(shopId=state.shopId){const run=++loadSequence;const data=await context.request('/costs?shopId='+encodeURIComponent(shopId));if(run!==loadSequence||!root.isConnected)return;mount(root,{...context,preview:data});}
    const months=[...new Set(costs.flatMap(c=>Object.keys(c.prices||{})))].sort().reverse(),warehouses=[...new Set(costs.map(c=>c.warehouse))];
    if(!shops.some(s=>s.id===state.shopId))state.shopId=preview?.shopId&&shops.some(s=>s.id===preview.shopId)?preview.shopId:shops[0]?.id||'';
    if(!state.month)state.month=months[0]||'';
    const getShop=()=>shops.find(s=>s.id===state.shopId);
    const applies=()=>Boolean(preview&&getShop()?.group_id===preview.groupId);
    if(!state.warehouse&&applies()&&preview?.suggestedWarehouse)state.warehouse=preview.suggestedWarehouse;
    const currentCosts=()=>applies()?costs:[];
    const currentProducts=()=>products.filter(p=>p.shop_id===state.shopId);
    const key=p=>[p.shop_id,p.product_id,p.sku_id].join(':');
    const mapped=p=>{const draft=state.mappings[key(p)+':'+state.warehouse]||state.mappings[key(p)];return draft?.warehouse===state.warehouse&&(!live||draft.groupId===preview.groupId)?draft:null;};
    const recommendation=p=>mapped(p)?{status:'draft',label:live?'已关联':'预览已关联',note:live?'已保存，公司财务成员共享':'本页草稿，尚未保存到正式系统',...mapped(p)}:candidate(p,currentCosts(),state.warehouse);
    function toast(message){root.querySelector('.fc-feedback').textContent=message;}
    function unavailable(message){const d=modal('功能预留');el('p',message||'页面结构已预留，正式保存与同步将在成本口径确认后接入。',d.body,'fi-muted');}
    function importDialog(){
      if(live){
        const d=modal('导入成本表'),group=select(d.body,'归属分组',[['','请选择'],...(context.groups||[]).map(g=>[g.id,g.name])],getShop()?.group_id||'',()=>{});
        const label=el('label',null,d.body);el('span','上传 XLSX / CSV',label);const file=el('input',null,label);file.type='file';file.accept='.xlsx,.csv';file.setAttribute('aria-label','成本表文件');
        el('p','支持「型号、仓库、月份、单价」四列，或仓库 / 型号加多个月份的横排成本表。首个工作表需为数值；公式请先转换为值。',d.body,'fi-muted');
        el('p','重复文件不会重复导入；相同型号与月份的新价格保存为新版本。币种、计价单位在“仓库与规则”确认。',d.body,'fi-muted');
        el('p','',d.body,'fc-alert').setAttribute('role','alert');
        action(d.foot,'确认导入',async()=>{if(!group.value||!file.files?.length)throw Error('请选择分组和成本文件');const form=new FormData();form.append('groupId',group.value);form.append('file',file.files[0]);const result=await context.request('/costs/import',{method:'POST',body:form});d.n.close();await reload();toast(result.duplicate?'此文件已经导入。':`已导入 ${result.rows} 条成本资料，请确认计价口径。`);},'fi-primary');return;
      }
      const d=modal('导入成本表 · 设计预览');el('p','文件先进入待确认区，核对仓库、月份和计价口径后再启用。',d.body,'fi-muted');
      const area=el('div',null,d.body,'fc-upload');el('strong',preview?.sourceName||'选择 Excel 成本表',area);el('p',preview?'当前展示的是已提供文件的只读解析结果。':'支持按月份横排的成本表，导入后转为型号、仓库、月份、单价。',area);
      const grid=el('div',null,d.body,'fi-grid2');select(grid,'归属分组',(context.groups||[]).map(g=>[g.id,g.name]),getShop()?.group_id||'',()=>{});
      select(grid,'文件用途',[['unit','SKU 单位成本表']], 'unit',()=>{});
      table(d.body,['识别字段','对应内容'],[['成本型号','型号'],['成本仓库','仓库'],['成本月份','月份列'],['单位成本','期末单价']]);
      tag(d.body,'待补充：币种、计价单位、费用是否已包含','warn');
      const n=action(d.foot,'确认导入（待接入）',()=>{});n.disabled=true;
    }
    function history(cost){
      const d=modal('月度成本记录');el('strong',cost.sku,d.body);el('p',cost.warehouse+' · 原表单价，币种与单位待确认',d.body,'fi-muted');
      table(d.body,['月份','原表单价','状态'],Object.entries(cost.prices).sort(([a],[b])=>b.localeCompare(a)).map(([month,value])=>[month,format(value),value===0?'零值待确认':value==null?'缺失':'原表数据']));
    }
    function mappingDialog(product){
      if(!preview)return unavailable();
      const d=modal(live?'关联成本型号':'关联成本型号 · 仅预览'),rec=recommendation(product);
      el('strong',product.seller_sku||'未提供商家 SKU',d.body);el('p',product.spec||product.name,d.body,'fi-muted');
      el('p',`商品 ID：${product.product_id||'待补充'} · 规格 SKU ID：${product.sku_id||'待补充'}`,d.body,'fi-muted');
      if(rec.status==='conflict')el('p',rec.note,d.body,'fc-alert');
      const choices=[...new Set(currentCosts().filter(c=>!state.warehouse||c.warehouse===state.warehouse).map(c=>c.sku))];
      const cost=select(d.body,'成本型号',[['','请选择'],...choices.map(s=>[s,s])],rec.code,()=>{});
      const quantity=el('label',null,d.body);el('span','每个销售单位对应多少个成本单位',quantity);
      const factor=el('input',null,quantity);factor.type='number';factor.min='0.000001';factor.step='any';factor.value=String(rec.factor||1);factor.setAttribute('aria-label','成本单位换算数量');
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
      if(withStatus)select(bar,'关联状态',[['','全部'],['exact','编码一致'],['bundle','组合装'],['conflict','规格冲突'],['missing','待关联'],['draft',live?'已关联':'预览已关联']],state.status,v=>{state.status=v;state.page=1;body();});
    }
    function costPanel(parent){
      const source=panel(parent,'母表与月度成本',applies()?'仅用于「'+(preview.groupName||'所选分组')+'」的成本资料':'成本资料按分组管理');
      const controls=el('div',null,source.h,'fi-actions');if(editable)action(controls,'导入成本表',importDialog,'fi-primary');
      if(applies()&&costs.length){
        const file=el('div',null,source.n,'fc-file');el('span','XLSX',file,'fc-file-icon');const copy=el('div',null,file);el('strong',preview.sourceName,copy);el('small',`主表 ${costs.length} 条 · ${warehouses.length} 个仓库 · 已识别 ${months.length} 个月份`,copy);tag(file,'待确认成本口径','warn');
        const notice=el('p',null,source.n,'fc-inline-note');notice.textContent='原表中的多包装型号保留独立成本。辅助页的赠品、别名和包装信息预留为待核对资料，不自动覆盖主表。';
      }
      toolbar(source.n);
      const rows=currentCosts().filter(c=>(!state.warehouse||c.warehouse===state.warehouse)&&(!state.search||c.sku.toLowerCase().includes(state.search.toLowerCase())));
      table(source.n,['成本型号','成本仓','月份','原表单价','计价口径','操作'],rows.slice((state.page-1)*12,state.page*12).map(c=>[
        el('span',c.sku,null,'fc-code'),c.warehouse,state.month,format(c.prices[state.month]),tag(null,state.rules.currency&&state.rules.unit?state.rules.currency+' / '+state.rules.unit+(live?'':'（预览）'):'币种 / 单位待确认','warn'),action(null,'查看历史',()=>history(c),'fi-record-link')
      ]),applies()?'当前筛选没有成本记录':'该店铺分组尚未配置成本资料。');pager(source.n,rows.length);
    }
    function productPanel(parent){
      const p=panel(parent,'店铺 SKU 与成本关联',live?`从最近最多 ${preview.orderLimit||2000} 笔已同步订单读取规格；不代表全店商品目录`:currentProducts().length?`来自已同步的 ${preview.sampleOrders||0} 笔订单样本；完整商品目录同步待接入`:'同步店铺商品后，在这里建立稳定的成本关系');
      if(editable)action(p.h,live?'刷新订单 SKU':'同步商品（待接入）',()=>live?reload():unavailable('商品列表、规格与图片同步入口已预留。当前预览仅使用已有订单中的商品样本，不代表店铺全部商品。'));
      toolbar(p.n,true);
      const rows=currentProducts().map(product=>({product,rec:recommendation(product)})).filter(({product,rec})=>(!state.status||rec.status===state.status)&&(!state.search||[product.seller_sku,product.name,product.spec].join(' ').toLowerCase().includes(state.search.toLowerCase())));
      table(p.n,['店铺商品 / 销售规格','商家 SKU','建议成本型号','换算数量','关联状态','操作'],rows.slice((state.page-1)*12,state.page*12).map(({product,rec})=>{
        const item=el('div',null,null,'fc-product');
        if(/^https:\/\//.test(product.image||'')){const image=el('img',null,item);image.src=product.image;image.alt='';image.loading='lazy';image.referrerPolicy='no-referrer';}
        else el('span','SKU',item,'fc-product-placeholder');
        const copy=el('div',null,item);const name=el('strong',product.name||'商品名称待补充',copy);name.title=product.name||'';el('small',product.spec||'规格待补充',copy);el('small','规格 SKU ID：'+(product.sku_id||'待补充'),copy);
        const status=tag(null,rec.label,rec.status==='conflict'?'danger':rec.status==='draft'?'good':'warn');status.title=rec.note;
        const button=editable?action(null,rec.status==='draft'?(live?'调整关联':'调整预览'):'关联',()=>mappingDialog(product),'fi-record-link'):'只读';
        return [item,el('span',product.seller_sku||'—',null,'fc-code'),el('span',rec.code||'待选择',null,'fc-code'),rec.code?'× '+rec.factor:'—',status,button];
      }),'当前店铺尚无商品样本，商品同步入口已预留。');pager(p.n,rows.length);
      el('p','编码和销售规格冲突时必须人工核对。相同商家 SKU 可以出现在多个商品中，关联按店铺与平台 SKU 分别保存。',p.n,'fc-inline-note');
    }
    function rulesPanel(parent){
      const layout=el('div',null,parent,'fc-rule-grid'),p=panel(layout,'仓库与成本口径','这些配置用于确定每笔订单应该采用哪份成本');
      const form=el('div',null,p.n,'fc-form');
      select(form,'默认成本仓',[['','请选择'],...(applies()?warehouses:[]).map(w=>[w,w])],state.warehouse,v=>{state.warehouse=v;}).disabled=!editable;
      const controls=[
        ['成本币种','currency',[['','待确认'],['CNY','人民币 CNY'],['PHP','菲律宾比索 PHP'],['THB','泰铢 THB'],['MYR','马来西亚林吉特 MYR']]],
        ['默认计价单位','unit',[['','待确认'],['包','包'],['片','片'],['箱','箱'],['套','套']]],
        ['成本归属月份','basis',[['shipment','发货月份'],['order','下单月份']]],
        ['当月成本缺失','missing',[['pending','标记缺失，等待补齐'],['previous','沿用最近月份，标记暂估']]],
        ['原表是否已含头程','freight',[['','待确认'],['included','已包含，不再重复加'],['excluded','未包含，另行配置']]],
        ['原表是否已含入仓费','inbound',[['','待确认'],['included','已包含，不再重复加'],['excluded','未包含，另行配置']]]
      ];
      for(const [label,key,options]of controls){const input=select(form,label,options,state.rules[key],v=>{state.rules[key]=v;});input.disabled=!editable;}
      if(editable)action(form,live?'保存成本规则':preview?'应用到预览':'保存配置（待接入）',async()=>{if(live){await context.request('/costs/'+encodeURIComponent(state.shopId)+'/settings',{method:'PUT',...json({warehouse:state.warehouse,rules:state.rules,version:preview.settingsVersion||0})});await reload();toast('成本规则已保存，历史订单尚未启用自动核算。');return;}if(!preview)return unavailable();draw();toast('成本规则已应用到预览；正式数据未变更。');},'fi-primary');
      const extra=panel(layout,'其他费用预留','商品单位成本之外的支出分开记录');
      for(const [name,desc]of [['订单费用','额外包装、外部运费、仓库操作费，按店铺和订单编号关联。'],['公共费用','人工、租金等按店铺或分组归集，再选择是否分摊。'],['去重口径','已包含在平台结算或单位成本里的费用，明确标记，不重复扣减。']]){
        const row=el('div',null,extra.n,'fc-expense');el('strong',name,row);el('p',desc,row);tag(row,'入口预留');
      }
      action(extra.n,'前往费用与调整',()=>{location.hash='finance-fees';},'fc-secondary');
    }
    function orderPanel(parent){
      const p=panel(parent,'订单成本与利润字段预留','在 SKU 关联和成本口径确认后，逐笔生成成本快照');
      const flow=el('div',null,p.n,'fc-flow');
      for(const [i,label,note]of [[1,'订单商品行','店铺、平台 SKU、销售数量'],[2,'对应成本','型号、仓库、月份、换算数量'],[3,'冻结快照','保留版本，后续改价可追溯'],[4,'订单利润','结算、成本、额外费用同单核算']]){const step=el('div',null,flow);el('i',i,step);el('strong',label,step);el('small',note,step);}
      table(p.n,['订单编号','销售 SKU / 数量','成本型号 / 版本','商品成本','其他费用','核算状态'],[],live?'订单成本计算入口已预留。先确认成本口径和 SKU 关联，自动核算暂未启用。':'完成成本口径确认后，订单成本将在这里逐笔展示。当前预览不产生利润金额。');
      const notes=el('div',null,p.n,'fc-order-notes');for(const text of ['缺成本显示“待补充”，不会当作零成本。','当月没有成本，按配置等待补齐或明确标记“暂估”。','成本表更新保留历史版本，已核算订单不静默变价。'])el('p',text,notes);
    }
    function body(){
      const host=root.querySelector('.fc-tab-content');if(!host)return;host.replaceChildren();
      ({costs:costPanel,mapping:productPanel,rules:rulesPanel,orders:orderPanel})[state.tab](host);
    }
    function draw(){
      root.replaceChildren();root.className='fc-workspace';
      const banner=el('div',null,root,'fc-preview-note');el('span',live?'成本准备':preview?'设计预览':'功能预留',banner,'fc-preview-label');el('span',live?'成本资料、SKU 关联与规则保存后公司共享；自动订单成本核算尚未启用。':preview?'成本表与店铺样本为只读展示；交互修改仅保留在本页，尚未启用正式成本核算。':'成本资料、SKU 关联和订单成本入口已预留，等待接入数据与保存功能。',banner);
      const hero=el('div',null,root,'fc-hero'),copy=el('div',null,hero);el('span','PRODUCT COST WORKSPACE',copy,'fc-eyebrow');el('h2','让每笔订单找到对应成本',copy);el('p','按商品规格关联，按仓库与月份取价，保留每次成本变化。',copy);
      const shopControl=el('div',null,hero,'fc-shop-select');select(shopControl,'财务店铺',[['','请选择店铺'],...shops.map(s=>[s.id,s.name||s.display_name])],state.shopId,v=>{
        if(live){if(v)reload(v).catch(e=>toast(e.message));return;}
        state.settings[state.shopId]={warehouse:state.warehouse,rules:{...state.rules}};state.shopId=v;
        const settings=state.settings[v];state.warehouse=settings?.warehouse||(applies()?preview.suggestedWarehouse:'')||'';state.rules=settings?.rules||defaultRules();
        state.page=1;state.search='';state.status='';draw();
      });
      const shop=getShop();if(shop){const meta=el('div',null,shopControl,'fc-shop-meta');tag(meta,shop.platform==='SHOPEE'?'虾皮':'TikTok');el('span',(shop.region||'')+' · '+(context.groups?.find(g=>g.id===shop.group_id)?.name||'未分组')+' · 已授权',meta);}
      const metrics=el('div',null,root,'fc-metrics');
      for(const [label,value,note]of [
        ['成本资料',applies()?costs.length:'—',applies()?`${warehouses.length} 个仓库 · ${preview.groupName}`:'等待导入成本表'],
        ['最新成本月份',applies()?months[0]:'—','文件更新日期不等于成本生效月份'],
        [live?'订单规格 SKU':'订单 SKU 样本',currentProducts().length||'—',currentProducts().length?`取自 ${preview.sampleOrders} 笔已同步订单`:'全量商品目录同步待接入'],
        ['正式成本核算','待启用','先确认币种、单位与费用口径']
      ]){const n=el('div',null,metrics,'fc-metric');el('span',label,n);el('strong',value,n);el('small',note,n);}
      const tabs=el('div',null,root,'fc-tabs');tabs.setAttribute('role','tablist');
      for(const [id,title,number]of [['costs','成本资料','01'],['mapping','SKU 关联','02'],['rules','仓库与规则','03'],['orders','订单成本','04']]){
        const n=action(tabs,'',()=>{state.tab=id;state.page=1;state.search='';state.status='';draw();});n.setAttribute('role','tab');n.setAttribute('aria-selected',String(state.tab===id));n.setAttribute('aria-controls','fc-panel');el('span',number,n);el('strong',title,n);
      }
      el('p','',root,'fc-feedback').setAttribute('role','status');const host=el('div',null,root,'fc-tab-content');host.id='fc-panel';host.setAttribute('role','tabpanel');body();
    }
    draw();
  }
  globalThis.FinanceCosts={mount,reset,candidate,shopId:()=>state.shopId};
})();
