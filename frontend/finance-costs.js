/* Cost configuration and SKU mappings. Explicit local previews never write to the service. */
(() => {
  'use strict';
  let state,loadSequence=0;
  const defaultRules=()=>({currency:'CNY',unit:'',basis:'order',missing:'previous',freight:'',inbound:''});
  const day=offset=>{const d=new Date();d.setDate(d.getDate()-offset);return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;};
  const fresh=()=>({tab:'labels',shopId:'',groupId:'',collapsedShopId:'',spus:{},warehouse:'',month:'',search:'',status:'',page:1,orderPage:1,orderCostStatus:'ALL',orderNumbers:'',orderContext:null,start:day(29),end:day(0),datesKey:'',mappings:{},settings:{},rules:defaultRules()});
  const reset=()=>{loadSequence++;globalThis.FinanceLabelCosts?.reset();state=fresh();document.querySelectorAll('.fc-dialog').forEach(n=>n.remove());};
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
  function productImage(parent,product,cls){
    if(!/^https:\/\//.test(product.image||''))return null;
    const open=action(parent,'',()=>{
      const d=modal('商品图片');d.n.classList.add('fc-image-view');
      const image=el('img',null,d.body);image.src=product.image;image.alt=product.spec||product.name||'商品图片';image.referrerPolicy='no-referrer';
      image.onerror=()=>{image.remove();el('p','图片暂时无法加载，请稍后重试。',d.body,'fc-empty');};
      if(product.spec||product.name)el('p',product.spec||product.name,d.body,'fc-image-caption');
    },'fc-image-button '+cls);
    open.setAttribute('aria-label','放大商品图片'+(product.spec?'：'+product.spec:''));open.title='点击放大图片';
    const image=el('img',null,open);image.src=product.image;image.alt=product.name||'商品图';image.loading='lazy';image.referrerPolicy='no-referrer';
    return open;
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
    const preview=context.preview||null,live=preview?.live===true,shops=context.shops||[],editable=Boolean(context.canManage),costs=preview?.costs||[],manualCosts=preview?.manualCosts||[],products=preview?.products||[];
    if(context.dates?.start&&context.dates?.end){
      const key=JSON.stringify(context.dates);
      if(!state.orderContext?.recordId&&state.datesKey!==key){state.start=context.dates.start;state.end=context.dates.end;state.orderPage=1;}
      state.datesKey=key;
    }
    if(live){const previous=state.shopId===preview.shopId&&state.groupId===preview.groupId?state.warehouse:'';state.shopId=preview.shopId||'';state.groupId=preview.groupId||'';state.mappings=preview.mappings||{};state.warehouse=previous||preview.settings?.warehouse||'';state.rules={...defaultRules(),...preview.settings?.rules,currency:'CNY'};}
    const json=body=>({headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    async function reload(shopId=state.shopId,groupId=shopId?'':state.groupId){const run=++loadSequence;const data=await context.request('/costs?'+new URLSearchParams({shopId,groupId}));if(run!==loadSequence||!root.isConnected)return;if(shopId!==state.shopId||groupId&&groupId!==state.groupId){state.page=1;state.search='';state.status='';state.collapsedShopId='';if(groupId&&groupId!==state.groupId){state.mappingReturn=null;state.orderContext=null;state.orderPage=1;}}mount(root,{...context,preview:data});}
    const months=[...new Set(costs.flatMap(c=>Object.keys(c.prices||{})))].sort().reverse(),warehouses=[...new Set([...costs,...manualCosts].map(c=>c.warehouse))];
    const groupShops=shops.filter(s=>!state.groupId||s.group_id===state.groupId);
    if(!groupShops.some(s=>s.id===state.shopId))state.shopId=preview?.shopId&&groupShops.some(s=>s.id===preview.shopId)?preview.shopId:groupShops[0]?.id||'';
    if(!months.includes(state.month))state.month=months[0]||'';
    const getShop=()=>shops.find(s=>s.id===state.shopId);
    if(!live)state.groupId=getShop()?.group_id||preview?.groupId||'';
    const applies=()=>Boolean(preview&&state.groupId===preview.groupId);
    if(!state.warehouse&&applies()&&preview?.suggestedWarehouse)state.warehouse=preview.suggestedWarehouse;
    const currentCosts=()=>applies()?costs:[];
    const currentProducts=()=>products.filter(p=>p.shop_id===state.shopId);
    const key=p=>[p.shop_id,p.product_id,p.sku_id].join(':');
    const savedMapping=p=>{
      const draft=state.mappings[key(p)+':'+state.warehouse]||state.mappings[key(p)];
      if(draft?.warehouse===state.warehouse&&(!live||draft.groupId===preview.groupId))return draft;
      if(state.warehouse)return null;
      const matches=Object.entries(state.mappings).filter(([id,m])=>id.startsWith(key(p)+':')&&(!live||m.groupId===preview.groupId)).map(([,m])=>m);
      return matches.length&&new Set(matches.map(m=>m.code)).size===1?{...matches[0],warehouse:'',factor:null}:null;
    };
    const mapped=p=>{
      const saved=savedMapping(p);if(!saved)return null;
      return saved.manualCostId?manualCosts.some(c=>c.id===saved.manualCostId&&(!saved.warehouse||c.warehouse===saved.warehouse))&&saved:
        currentCosts().some(c=>c.sku===saved.code&&(!saved.warehouse||c.warehouse===saved.warehouse))&&saved;
    };
    const recommendation=p=>{
      const saved=mapped(p),suggestion=candidate(p,currentCosts(),state.warehouse);
      if(!saved){const previous=savedMapping(p);return previous?{...previous,status:'missing',label:'成本已移除 · 待关联',note:'原关联的成本资料已移除，请重新选择成本或手动填写'}:suggestion;}
      const review=automaticMapping(saved)&&suggestion.status==='conflict';
      return {...saved,status:review?'review':'draft',label:review?'已关联 · 需核对':live?(automaticMapping(saved)?'已自动关联'+(saved.bundleQuantity?' · ×'+saved.bundleQuantity:''):'已关联'):'预览已关联',note:review?suggestion.note:live?(saved.bundleQuantity?`基础成本型号已关联，订单按组合数量 ×${saved.bundleQuantity} 计算成本`:'已保存，公司财务成员共享'):'本页草稿，尚未保存到正式系统'};
    };
    const tone=rec=>rec.status==='draft'?'good':rec.status==='missing'?'danger':'warn';
    const catalogFor=shop=>preview?.shopCatalogs?.[shop.id]||(shop.group_id===preview?.groupId?{costRows:costs.length,sourceName:preview.sourceName}:{costRows:0,sourceName:''});
    async function chooseShop(id){
      if(!id)return;
      state.orderContext=null;state.orderPage=1;state.mappingReturn=null;
      if(live){await reload(id);return;}
      state.settings[state.shopId]={warehouse:state.warehouse,rules:{...state.rules}};state.shopId=id;
      const settings=state.settings[id];state.warehouse=settings?.warehouse||(applies()?preview.suggestedWarehouse:'')||'';state.rules=settings?.rules||defaultRules();
      state.page=1;state.search='';state.status='';state.collapsedShopId='';draw();
    }
    async function chooseGroup(id){
      if(!id)return;
      if(live){await reload('',id);return;}
      state.settings[state.shopId]={warehouse:state.warehouse,rules:{...state.rules}};
      state.mappingReturn=null;state.orderContext=null;state.orderPage=1;state.warehouse='';
      state.groupId=id;state.shopId=shops.find(s=>s.group_id===id)?.id||'';state.page=1;
      const settings=state.settings[state.shopId];state.warehouse=settings?.warehouse||(id===preview?.groupId?preview.suggestedWarehouse:'')||'';
      state.rules=settings?.rules||defaultRules();mount(root,{...context,preview});
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
        const d=modal('导入成本表'),group=select(d.body,'归属分组',[['','请选择'],...(context.groups||[]).map(g=>[g.id,g.name])],state.groupId||'',()=>{});
        const label=el('label',null,d.body);el('span','上传 XLSX / CSV（可多选）',label);const file=el('input',null,label);file.type='file';file.accept='.xlsx,.csv';file.multiple=true;file.setAttribute('aria-label','成本表文件');
        el('p','支持「型号、仓库、月份、单价」四列，或仓库 / 型号加多个月份的横排成本表。首个工作表需为数值；公式请先转换为值。',d.body,'fi-muted');
        el('p','每份文件独立保存、独立移除。同仓、同型号、同月份以最后导入的文件为准；不修改手动成本。金额统一为人民币。',d.body,'fi-muted');
        el('p','',d.body,'fc-alert').setAttribute('role','alert');
        action(d.foot,'确认导入',async()=>{
          if(!group.value||!file.files?.length)throw Error('请选择分组和成本文件');
          const target=group.value;let imported=0,duplicates=0;group.disabled=true;file.disabled=true;
          try{for(const selected of file.files){const form=new FormData();form.append('groupId',target);form.append('file',selected);
            d.body.querySelector('[role=alert]').textContent='正在导入：'+selected.name;
            const result=await context.request('/costs/import',{method:'POST',body:form});if(result.duplicate)duplicates++;else imported++;
          }}catch(e){await reload('',target);throw Error(`已导入 ${imported} 份，重复 ${duplicates} 份。${e.message}；可修正后重试，已导入文件不会重复。`);}
          finally{group.disabled=false;file.disabled=false;}
          d.n.close();await reload('',target);toast(`已导入 ${imported} 份成本表，跳过重复文件 ${duplicates} 份。`);
        },'fi-primary');return;
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
      tag(d.body,'归属店铺：'+(getShop()?.display_name||getShop()?.name||''),'good');
      productImage(d.body,product,'fc-mapping-image');
      el('strong',product.seller_sku||'未提供商家 SKU',d.body);el('p',product.spec||product.name,d.body,'fi-muted');
      el('p',`商品 ID：${product.product_id||'待补充'} · 规格 SKU ID：${product.sku_id||'待补充'}`,d.body,'fi-muted');
      if(rec.status==='conflict')el('p',rec.note,d.body,'fc-alert');
      const mode=select(d.body,'关联方式',[['existing','选择已有成本'],['manual','手动填写成本']],costs.length||manualCosts.length?'existing':'manual',()=>renderEditor());
      const editor=el('div',null,d.body,'fc-mapping-editor');let cost,warehouse,code,price,unit,zero;
      function field(parent,label,value='',type='text'){
        const wrap=el('label',null,parent);el('span',label,wrap);const input=el('input',null,wrap);input.type=type;input.value=value;input.setAttribute('aria-label',label);return input;
      }
      function renderEditor(){
        editor.replaceChildren();
        if(mode.value==='manual'){
          el('p','保存后进入本分组「手动管理」，不受 Excel 导入或移除影响。',editor,'fc-inline-note');
          code=field(editor,'手动产品型号',product.seller_sku||'');
          const region={PH:'菲律宾',TH:'泰国',MY:'马来西亚',VN:'越南',SG:'新加坡',ID:'印尼'}[getShop()?.region];
          warehouse=field(editor,'手动成本仓',state.warehouse||preview.settings?.warehouse||(region?'海外仓-'+region:''));
          price=field(editor,'人民币单价','','number');price.min='0';price.step='0.000001';
          unit=field(editor,'成本计价单位','包');unit.placeholder='例如：包、片、箱、套';
          const label=el('label',null,editor,'fc-check');zero=el('input',null,label);zero.type='checkbox';el('span','若单价为 0，我确认该成本确实为零',label);
          el('p','此固定单价用于该规格的历史及后续订单；订单是否计入仍按已结算口径。',editor,'fc-inline-note');
        }else{
          warehouse=select(editor,'关联成本仓',[['','请选择'],...warehouses.map(w=>[w,w])],state.warehouse||rec.warehouse||(warehouses.length===1?warehouses[0]:''),()=>renderChoices());
          const holder=el('div',null,editor);
          function renderChoices(){
            holder.replaceChildren();const chosen=warehouse.value;
            cost=select(holder,'成本型号',[['','请选择'],...currentCosts().filter(c=>c.warehouse===chosen).map(c=>[c.sku,'Excel · '+c.sku]),
              ...manualCosts.filter(c=>c.warehouse===chosen).map(c=>['manual:'+c.id,`手动 · ${c.sku} · ¥ ${format(c.unitPriceCny)} / ${c.unit}`])],rec.manualCostId?'manual:'+rec.manualCostId:rec.code,()=>{});
          }
          renderChoices();
        }
      }
      renderEditor();
      const quantity=el('label',null,d.body);el('span','每个销售单位对应多少个成本单位',quantity);
      const factor=el('input',null,quantity);factor.type='number';factor.min='0.000001';factor.step='any';factor.value=String(rec.factor||rec.bundleQuantity||1);factor.setAttribute('aria-label','成本单位换算数量');
      el('p','例如：销售规格为 2 包装，成本为单包价，填 2；成本型号本身就是 2 包装，填 1。商品成本 = 人民币单价 × 购买数量 × 换算数量。',d.body,'fi-muted');
      let confirmed;if(live){const label=el('label',null,d.body,'fc-check');confirmed=el('input',null,label);confirmed.type='checkbox';el('span','我已核对销售规格、成本型号、单价和单位换算',label);}
      const feedback=el('p','',d.body,'fc-alert');feedback.setAttribute('role','alert');
      action(d.foot,live?'保存关联':'应用到预览',async()=>{
        const selectedWarehouse=warehouse.value.trim(),manualNew=mode.value==='manual',manualExisting=!manualNew&&cost.value.startsWith('manual:');
        const entry=manualExisting?manualCosts.find(c=>c.id===cost.value.slice(7)):null;
        const selectedCode=manualNew?code.value.trim():entry?.sku||cost.value;
        if(!selectedCode||!selectedWarehouse||!Number.isFinite(Number(factor.value))||Number(factor.value)<=0)throw Error('请选择或填写成本型号、成本仓，并填写大于零的换算数量。');
        if(manualNew&&(!price.value||!Number.isFinite(Number(price.value))||Number(price.value)<0||!unit.value.trim()))throw Error('请填写人民币单价和成本计价单位。');
        if(live){
          if(!confirmed.checked)throw Error('请先确认销售规格、单价与成本单位');
          const previous=state.mappings[key(product)+':'+selectedWarehouse]||{};
          const body={product_id:product.product_id,sku_id:product.sku_id,code:selectedCode,factor:factor.value,warehouse:selectedWarehouse,confirmed:true,version:previous.version||0};
          if(manualNew)Object.assign(body,{unitPriceCny:price.value,unit:unit.value.trim(),zeroConfirmed:zero.checked});
          if(entry)body.manualCostId=entry.id;
          await context.request('/costs/'+encodeURIComponent(state.shopId)+(manualNew?'/manual-mapping':'/mapping'),{method:manualNew?'POST':'PUT',...json(body)});
          state.warehouse=selectedWarehouse;d.n.close();await reload();toast(manualNew?'手动成本已保存并关联，可在「手动管理」查看。':'SKU 关联已保存。');return;
        }
        state.mappings[key(product)]={code:selectedCode,factor:Number(factor.value),warehouse:selectedWarehouse};
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
      const source=panel(parent,'分组成本表',applies()?'「'+(context.groups?.find(g=>g.id===state.groupId)?.name||preview.groupName||'所选分组')+'」内店铺共享，支持多份文件':'成本资料按分组管理');
      const controls=el('div',null,source.h,'fi-actions');if(editable)action(controls,'导入成本表',importDialog,'fi-primary').disabled=!state.groupId;
      const files=applies()?(preview?.imports||(!live&&preview?.sourceName?[{id:'preview',source_name:preview.sourceName,row_count:costs.length}]:[])):[];
      for(const entry of files){
        const file=el('div',null,source.n,'fc-file');file.dataset.sourceId=entry.id;el('span','EXCEL',file,'fc-file-icon');const copy=el('div',null,file,'fc-file-copy');
        el('strong',entry.source_name,copy);el('small',`${entry.row_count||0} 条成本资料 · 人民币`,copy);
        if(entry.legacy_merged)el('small','既有合并成本资料（升级前的完整数据）',copy);
        if(live&&editable)action(file,'移除文件',()=>removeFile(entry),'fi-record-link fc-danger');
      }
      if(!files.length)el('p','本分组尚未上传成本文件，可一次选择多份 Excel。',source.n,'fc-empty');
      el('p','同仓、同型号、同月份以最后导入的文件为准。移除后回退到其他仍存在的文件；没有可用价格的订单显示待补齐。手动成本单独维护。',source.n,'fc-inline-note');
      toolbar(source.n);
      const rows=currentCosts().filter(c=>(!state.warehouse||c.warehouse===state.warehouse)&&(!state.search||c.sku.toLowerCase().includes(state.search.toLowerCase())));
      table(source.n,['成本型号','成本仓','月份','单价（人民币）','来源文件','操作'],rows.slice((state.page-1)*12,state.page*12).map(c=>[
        el('span',c.sku,null,'fc-code'),c.warehouse,state.month,c.prices[state.month]==null?'—':'¥ '+format(c.prices[state.month]),c.priceSources?.[state.month]?.name||preview?.sourceName||'—',action(null,'查看历史',()=>history(c),'fi-record-link')
      ]),applies()?'当前筛选没有成本记录':'该店铺分组尚未配置成本资料。');pager(source.n,rows.length);
    }
    function removeFile(file){
      const d=modal('移除成本文件');el('strong',file.source_name,d.body);
      el('p','仅移除这份文件提供的成本，其他文件和手动成本保留。相关订单会重新计算，缺价的订单回到待补齐。',d.body);
      if(file.legacy_merged)el('p','这是升级前保留的完整合并成本资料，移除会撤掉其中全部价格。',d.body,'fc-alert');
      el('p','',d.body,'fc-alert').setAttribute('role','alert');
      action(d.foot,'确认移除文件',async()=>{
        await context.request('/costs/groups/'+encodeURIComponent(state.groupId)+'/files/'+encodeURIComponent(file.id),{method:'DELETE'});
        d.n.close();await reload();toast('文件成本已移除，手动成本保留；相关订单正在重算。');
      },'fi-primary');
    }
    function manualPanel(parent){
      const p=panel(parent,'手动成本管理','本分组独立维护，不受 Excel 导入或移除影响。');
      if(editable)action(p.h,'去 SKU 关联添加',()=>{state.tab='mapping';state.search='';draw();},'fi-primary').disabled=!state.shopId;
      el('p','在 SKU 关联中选择「手动填写成本」，输入产品型号、人民币单价、计价单位和换算数量。固定单价适用于关联规格的历史及后续订单，修改后会重算。',p.n,'fc-inline-note');
      const bar=el('div',null,p.n,'fc-table-tools'),search=el('input',null,bar);search.type='search';search.placeholder='搜索手动产品型号';search.value=state.search;search.setAttribute('aria-label','搜索手动产品型号');search.onchange=()=>{state.search=search.value.trim();state.page=1;body();};
      select(bar,'手动成本仓库',[['','全部成本仓'],...warehouses.map(w=>[w,w])],state.warehouse,v=>{state.warehouse=v;state.page=1;body();});
      const rows=manualCosts.filter(c=>(!state.warehouse||c.warehouse===state.warehouse)&&(!state.search||c.sku.toLowerCase().includes(state.search.toLowerCase())));
      table(p.n,['产品型号','成本仓','人民币单价','计价单位','适用范围','操作'],rows.slice((state.page-1)*12,state.page*12).map(c=>{
        const actions=el('div',null,null,'fi-actions');if(editable){action(actions,'修改',()=>editManual(c),'fi-record-link');action(actions,'删除',()=>editManual(c,true),'fi-record-link fc-danger');}
        return [el('span',c.sku,null,'fc-code'),c.warehouse,'¥ '+format(c.unitPriceCny),c.unit,'已关联规格 · 历史及后续订单',actions];
      }),'尚无手动成本，可在 SKU 关联中添加。');pager(p.n,rows.length);
    }
    function editManual(row,remove=false){
      const d=modal(remove?'删除手动成本':'修改手动成本');el('strong',row.sku+' · '+row.warehouse,d.body);
      let price,unit,confirmed,zero;
      if(remove)el('p','删除后，使用此手动成本的订单回到待补齐，需要重新关联。Excel 文件及其他手动成本不受影响。',d.body);
      else{
        for(const [label,value,type]of [['人民币单价',row.unitPriceCny,'number'],['成本计价单位',row.unit,'text']]){
          const wrap=el('label',null,d.body);el('span',label,wrap);const input=el('input',null,wrap);input.type=type;input.value=value;input.setAttribute('aria-label',label);
          if(type==='number'){price=input;input.min='0';input.step='0.000001';}else unit=input;
        }
        const label=el('label',null,d.body,'fc-check');confirmed=el('input',null,label);confirmed.type='checkbox';el('span','确认按新单价重算该型号已关联的历史及后续订单',label);
        const zeroLabel=el('label',null,d.body,'fc-check');zero=el('input',null,zeroLabel);zero.type='checkbox';el('span','若单价为 0，我确认该成本确实为零',zeroLabel);
      }
      el('p','',d.body,'fc-alert').setAttribute('role','alert');
      action(d.foot,remove?'确认删除手动成本':'保存手动成本',async()=>{
        if(!remove&&(!price.value||!unit.value.trim()||!confirmed.checked))throw Error('请填写单价、单位，并确认重算范围');
        const body=remove?{version:row.version}:{version:row.version,unitPriceCny:price.value,unit:unit.value.trim(),confirmed:confirmed.checked,zeroConfirmed:zero.checked};
        await context.request('/costs/groups/'+encodeURIComponent(state.groupId)+'/manual/'+encodeURIComponent(row.id),{method:remove?'DELETE':'PUT',...json(body)});
        d.n.close();await reload();toast(remove?'手动成本已删除，相关订单待重新核对。':'手动成本已更新，相关订单正在重算。');
      },'fi-primary');
    }
    function productRows(parent){
      const source=el('div',null,parent,'fc-shop-source');
      if(applies()&&(costs.length||manualCosts.length)){
        tag(source,'成本资料已配置','good');el('strong',preview.sourceName,source);
        el('span',`${costs.length} 条 Excel 成本 · ${manualCosts.length} 条手动成本 · ${warehouses.length} 个仓库`,source);
        action(source,'查看成本表',()=>{state.tab='costs';state.page=1;state.search='';draw();},'fi-record-link');
      }else{
        el('span','该店铺所属分组尚未导入成本表。',source);
        if(editable)action(source,'导入成本表',importDialog,'fi-record-link');
      }
      const info=el('div',null,parent,'fc-shop-sku-info');
      el('span',live?`已读取 ${currentProducts().length} 个 SKU · 最近 ${preview.sampleOrders||0} 笔已同步订单（最多 ${preview.orderLimit||2000} 笔）`:`${currentProducts().length} 个 SKU · 已同步订单样本`,info);
      if(live){
        el('span',`另补充历史待补齐规格 ${preview.historicalPendingSkuCount||0} 个；成本表已导入不代表全部历史订单已关联。`,info);
        if(preview.pendingSkusTruncated)el('span',`历史待补齐规格超过 ${preview.pendingSkuLimit||1000} 个，本页分批处理后请刷新。`,info,'fc-alert');
      }
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
        if(productImage(card,product,'fc-spu-image'))card.classList.add('has-image');
        const copy=el('span',null,heading,'fc-spu-copy');el('strong',product.name||'商品名称待补充',copy);el('small','SPU ID：'+product.product_id+' · '+group.rows.length+' 个 SKU',copy);
        const badges=el('span',null,heading,'fc-spu-counts');
        tag(badges,'已关联 '+totals.good,'good');if(totals.danger)tag(badges,'未关联 '+totals.danger,'danger');if(totals.warn)tag(badges,'需核对 '+totals.warn,'warn');
        const detail=el('div',null,null,'fc-spu-detail');detail.id='fc-spu-'+product.product_id;detail.hidden=!expanded;
        if(!expanded){card.append(detail);if((index%4===3||index===pageSpus.length-1)&&openDetail){list.append(openDetail);openDetail=null;}continue;}
        openDetail=detail;
        const detailHead=el('div',null,detail,'fc-spu-detail-head');el('strong',product.name||'商品 SKU 明细',detailHead);action(detailHead,'收起明细',()=>{state.spus[id]=false;body();},'fi-record-link');
        const visible=group.rows.filter(matches).sort((a,b)=>({danger:0,warn:1,good:2})[tone(a.rec)]-({danger:0,warn:1,good:2})[tone(b.rec)]);
        table(detail,['SKU 规格','商家 SKU','成本型号','关联状态','操作'],visible.map(({product,rec})=>{
          const item=el('div',null,null,'fc-product');productImage(item,product,'fc-sku-image');const copy=el('div',null,item);el('strong',product.spec||'规格待补充',copy);el('small','规格 SKU ID：'+product.sku_id,copy);
          if(product.pending_cost_orders){tag(copy,`历史订单待补齐 · ${product.pending_cost_orders} 笔`,'danger');el('small',product.pending_cost_reason||'请核对历史销售规格对应的成本型号',copy);}
          const badge=tag(null,rec.status==='missing'&&!rec.label.includes('已移除')?'未关联':rec.label,tone(rec));badge.title=rec.note;
          const button=editable?action(null,mapped(product)?'查看 / 调整':'核对关联',()=>mappingDialog(product),'fi-record-link'):'只读';
          const costCode=el('span',rec.code||'未找到',null,'fc-code');
          if(rec.bundleQuantity)el('small','组合数量 ×'+rec.bundleQuantity,costCode,'fc-bundle-quantity');
          if(rec.manualCostId){const manual=manualCosts.find(c=>c.id===rec.manualCostId);if(manual)el('small',`手动 · ¥ ${format(manual.unitPriceCny)} / ${manual.unit} · 换算 ×${format(rec.factor)}`,costCode,'fc-bundle-quantity');}
          return [item,el('span',product.seller_sku||'未提供商家 SKU',null,'fc-code'),costCode,badge,button];
        }));
        if(index%4===3||index===pageSpus.length-1){list.append(openDetail);openDetail=null;}
      }
      if(!spus.length)el('p',currentProducts().length?'没有符合筛选的商品，请清空搜索或调整关联状态。':'该店铺已同步订单暂未提供 SKU，成本表仍可查看。',list,'fc-empty');
      pager(parent,spus.length);
      el('p','按商家 SKU 关联与计量，销售规格文字不参与匹配。绿：关联成功 · 红：未找到型号 · 橙：成本编码存在歧义。每家店铺独立保存。',parent,'fc-inline-note');
    }
    function productPanel(parent){
      const p=panel(parent,'店铺 → SPU → SKU','相同编码自动关联。商品外层显示关联结果，未关联和需核对的优先展示。');
      if(state.mappingReturn)action(p.h,state.mappingReturn.orderCostStatus==='PENDING'?'返回待补齐订单':'返回订单成本',async()=>{Object.assign(state,state.mappingReturn);state.mappingReturn=null;state.tab='orders';if(live)await reload(state.shopId);else draw();},'fi-record-link');
      const tree=el('div',null,p.n,'fc-shop-tree');
      if(!shops.length)el('p','请先在财务店铺管理中授权店铺。',tree,'fc-empty');
      const ordered=[...groupShops].sort((a,b)=>Boolean(catalogFor(b).costRows)-Boolean(catalogFor(a).costRows));
      for(const shop of ordered){
        const selected=shop.id===state.shopId,expanded=selected&&state.collapsedShopId!==shop.id,catalog=catalogFor(shop);
        const branch=el('section',null,tree,'fc-shop-branch'+(expanded?' is-expanded':''));branch.dataset.shopId=shop.id;
        const head=action(branch,'',async()=>{
          if(selected){state.collapsedShopId=expanded?shop.id:'';body();return;}
          const pending=el('span','正在读取店铺 SKU…',branch,'fc-shop-loading');head.setAttribute('aria-busy','true');
          try{await chooseShop(shop.id);}finally{pending.remove();head.removeAttribute('aria-busy');}
        },'fc-shop-parent');
        head.setAttribute('aria-label','查看店铺 '+(shop.display_name||shop.name));head.setAttribute('aria-expanded',String(expanded));head.setAttribute('aria-controls','fc-shop-'+shop.id);
        el('span',expanded?'▾':'▸',head,'fc-shop-chevron').setAttribute('aria-hidden','true');
        const copy=el('span',null,head,'fc-shop-title');el('strong',shop.display_name||shop.name,copy);
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
      const origin=state.orderContext;
      const settled=!origin?.recordId&&origin?.kind!=='PENDING';
      const p=panel(parent,origin?.kind==='SAMPLE_SETTLED'?'样品订单商品成本':origin?.kind==='REFUND_SETTLED'?'退货退款关联商品成本':settled?'已结算订单商品成本':'关联已结算订单成本',
        settled?'按结算归属日期筛选；下单日期仅供核对，同一订单成本只计一次。':'仅核对所选来源关联的已结算订单成本，未结算不计入。');
      if(!live){el('p','正式系统按已同步订单计算；此预览不生成成本金额。',p.n,'fc-inline-note');return;}
      if(origin){
        const scopeShop=shops.find(s=>s.id===origin.shopId);
        const scopeName=origin.shopId?scopeShop?.display_name||scopeShop?.name||'所选店铺':origin.groupId?context.groups?.find(g=>g.id===origin.groupId)?.name||'所选分组':'全部店铺';
        el('p',origin.recordId?'当前仅核对所选记录关联的已结算订单，日期筛选不适用，不代表所选期间成本合计。':'当前范围：'+scopeName+' · '+(settled?'结算归属日期':'待结算不计成本'),p.n,'fc-inline-note');
        if(origin.recordId)action(p.h,'查看该店已结算订单',()=>{state.orderContext=null;state.orderPage=1;body();},'fi-record-link');
      }
      const bar=el('div',null,p.n,'fc-table-tools');
      for(const [key,title]of [['start',settled?'结算开始日期':'开始日期'],['end',settled?'结算结束日期':'结束日期']]){
        const label=el('label',null,bar);el('span',title,label);const input=el('input',null,label);input.type='date';input.value=state[key];input.setAttribute('aria-label',title);
        input.disabled=!!origin?.recordId;input.onchange=()=>{state[key]=input.value;state.orderPage=1;rememberDates();body();};
      }
      function rememberDates(){
        if(!state.start||!state.end||state.start>state.end)return;
        const dates={start:state.start,end:state.end};state.datesKey=JSON.stringify(dates);
        if(context.dates)Object.assign(context.dates,dates);context.onDatesChange?.(dates);
      }
      const selection={shopId:state.shopId,groupId:state.groupId,kind:'SETTLED_ORDER',...origin,start:state.start,end:state.end,costStatus:state.orderCostStatus,numbers:state.orderNumbers};
      context.numberSearch?.(p.n,state.orderNumbers,value=>{state.orderNumbers=value;state.orderPage=1;body();},'订单编号');
      action(bar,'刷新成本',()=>refresh(),'fi-record-link');
      let exporting=false;
      const exportButton=action(bar,'导出 Excel',async()=>{
        exporting=true;exportButton.textContent='正在生成 Excel…';
        try{
          if(!context.downloadOrderCosts)throw Error('导出功能未加载，请刷新页面后重试');
          await context.downloadOrderCosts(selection);
          toast('订单成本 Excel 已生成，包含汇总说明、当前筛选的订单及 SKU 计算明细。');
        }finally{exporting=false;exportButton.textContent='导出 Excel';}
      },'fi-primary');
      exportButton.disabled=true;
      exportButton.title='导出当前筛选的全部订单，含 SKU 单价、数量、成本月份与缺失原因';
      const amountSummary=el('div',null,bar,'fc-order-amount-summary');amountSummary.setAttribute('role','status');
      const amountLabel=el('span','成本金额合计（人民币）',amountSummary);
      const amountValue=el('strong','查询中…',amountSummary),amountNote=el('small','按当前筛选汇总全部页',amountSummary);
      const content=el('div',null,p.n,'fc-order-results'),feedback=el('p','正在读取订单成本…',p.n,'fc-inline-note');
      const run=++loadSequence;let timer,busy=false,signature='',loaded=false;
      if(!selection.start||!selection.end||selection.start>selection.end){feedback.textContent='请选择完整日期范围，开始日期不能晚于结束日期。';amountValue.textContent='—';amountNote.textContent='请先选择有效日期';return;}
      async function refresh(){
        if(busy||run!==loadSequence||!p.n.isConnected||!selection.start||!selection.end||selection.start>selection.end)return;
        busy=true;clearTimeout(timer);
        try{
        const result=await context.request('/costs/orders?'+new URLSearchParams({...selection,page:state.orderPage}));
        if(run!==loadSequence||!p.n.isConnected)return;feedback.textContent='';
        const amount=result.costAmountSummary||(selection.costStatus==='ALL'?result.productCosts?.total:null);
        const partial=Number(amount?.pending_count)>0;
        const label=partial?'成本金额（已算部分 · 人民币）':'成本金额合计（人民币）';
        const value=amount?'¥ '+format(partial?amount.known_amount_cny:amount.amount_cny):'—';
        const note=amount?`${format(amount.order_count??result.total)} 笔订单 · 全部页`+(partial?` · ${format(amount.pending_count)} 笔待补齐`:''):'暂未取得合计';
        if(amountLabel.textContent!==label)amountLabel.textContent=label;
        if(amountValue.textContent!==value)amountValue.textContent=value;
        if(amountNote.textContent!==note)amountNote.textContent=note;
        amountSummary.title='商品成本按当前店铺、结算日期、订单编号及成本状态筛选汇总，与同条件导出的商品成本一致；不含贴单金额。';
        const nextSignature=JSON.stringify(result);
        if(signature!==nextSignature){
        const next=content.cloneNode(false),totals=result.productCosts?.total||{},stats=el('div',null,next,'fi-cost-stats');
        for(const [label,value]of [[Number(totals.pending_count)>0?'已结算订单已算部分':'已结算订单商品成本','¥ '+format(Number(totals.pending_count)>0?totals.known_amount_cny:totals.amount_cny)],['已计算的结算订单',format(totals.ready_count)],['已结算待补齐订单',format(totals.pending_count)],['历史价估算',format(totals.estimated_count)]]){
          const n=el('div',null,stats);el('span',label,n);el('strong',value,n);
        }
        if(result.reportingMonthNotice){
          const notice=el('p',(result.reportingMonths||[]).map(r=>r.reporting_month+'：'+r.orders+' 笔'+(r.included?'已归入':'需完整月份查询')).join('；')+'。'+result.reportingMonthNotice,next,'fc-inline-note');
          for(const month of result.reportingMonths||[])if(!month.included&&!origin?.recordId)action(notice,'查看 '+month.reporting_month+' 整月',()=>{
            const [year,m]=month.reporting_month.split('-').map(Number);
            state.start=month.reporting_month+'-01';state.end=month.reporting_month+'-'+String(new Date(year,m,0).getDate()).padStart(2,'0');
            state.orderPage=1;rememberDates();body();
          },'fi-record-link');
        }
        const statusFilters=el('div',null,next,'fi-actions fc-order-status');statusFilters.setAttribute('aria-label','订单成本状态筛选');
        for(const [value,title]of [['ALL','全部订单'],['PENDING',`待补齐（${format(totals.pending_count||0)}）`]]){
          const button=action(statusFilters,title,()=>{state.orderCostStatus=value;state.orderPage=1;body();},state.orderCostStatus===value?'fi-primary':'');
          button.setAttribute('aria-pressed',String(state.orderCostStatus===value));button.dataset.costStatus=value;
        }
        const settlementAmount=(row,key)=>{
          const n=el('span',row[key]==null?'待补齐':format(row[key])+' '+(row.settlement_currency||''));
          n.title=row.settlement_amount_note||'首次结算账单日期的平台金额，不以原订单金额代替';return n;
        };
        const orderTable=table(next,['店铺','订单编号','商品 SKU','下单时间（店铺时区）','结算归属日期','销售收入','平台净结算','已结算商品成本（人民币）','贴单金额（人民币）','成本状态','操作'],(result.items||[]).map(row=>[
          shops.find(s=>s.id===row.shop_id)?.display_name||shops.find(s=>s.id===row.shop_id)?.name||'—',
          orderIdentifier(row),context.skuCell?.(row)||row.product_skus||'—',context.dateTimeCell?.(row.order_created_time||row.create_time,shops.find(s=>s.id===row.shop_id)?.timezone||'UTC')||'—',row.reporting_month?row.reporting_month+'（按月校正）':String(row.first_settlement_date||'').slice(0,10)||'—',
          settlementAmount(row,'settlement_goods_amount'),settlementAmount(row,'settlement_net_amount'),
          row.product_cost_cny==null?'—':context.openRecord&&row.id?action(null,'¥ '+format(row.product_cost_cny),()=>context.openRecord(row.id),'fi-cost-link'):'¥ '+format(row.product_cost_cny),
          (()=>{const label=el('span',row.label_cost_cny==null?'待补齐':'¥ '+format(row.label_cost_cny));label.title=row.label_cost_note||'仓库账单实际操作费，人民币';return label;})(),
          costState(row),
          context.openRecord&&row.id?action(null,'查看计算明细',()=>context.openRecord(row.id),'fi-record-link'):'订单待同步'
        ]));
        [...orderTable.querySelectorAll('tbody tr')].forEach((row,index)=>row.dataset.updateKey=result.items[index].id||`${result.items[index].shop_id}:${result.items[index].order_id}`);
        if(state.orderCostStatus==='PENDING'&&!result.items?.length)el('p','当前日期和店铺范围内没有待补齐订单。',next,'fc-inline-note');
        const pager=el('div',null,next,'fi-pagebar');el('span',`${result.total} 笔订单 · 第 ${result.page} 页`,pager);const controls=el('div',null,pager,'fi-actions');
        action(controls,'上一页',()=>{state.orderPage--;body();}).disabled=state.orderPage<=1;
        action(controls,'下一页',()=>{state.orderPage++;body();}).disabled=state.orderPage*result.pageSize>=result.total;
        el('p','商品成本优先采用 ERP 实际出库型号、数量及人民币成本；未提供实际凭据的使用标准成本。已出库后取消保留实际成本，确认退回入库后单独冲回。商品与贴单按首次结算计入一次，后续退款不重复计成本。',next,'fc-inline-note');
        if(result.productCosts?.rebuilding)el('p','后台正在分批计算历史订单，完成后自动更新。',next,'fc-inline-note');
        if(globalThis.IncrementalDOM)IncrementalDOM.children(content,next);else content.replaceChildren(...next.childNodes);
        signature=nextSignature;
        }
        loaded=true;exportButton.disabled=exporting;
        if(result.productCosts?.processing)timer=setTimeout(()=>{if(run===loadSequence&&p.n.isConnected&&state.tab==='orders'&&context.isActive?.()!==false&&!document.hidden)void refresh();},15000);
        }catch(error){if(run===loadSequence&&p.n.isConnected){feedback.textContent=error.message||'成本读取失败，请重试';if(!loaded){amountValue.textContent='—';amountNote.textContent='查询未完成';}}}
        finally{busy=false;if(!loaded)exportButton.disabled=true;}
      }
      await refresh();
      function costState(row){
        const cell=el('div',null,null,'fc-cost-state');tag(cell,row.product_cost_state||'后台计算中',row.product_cost_state==='已计算'?'good':Number(row.product_cost_pending)>0?'danger':'warn');
        if(row.product_cost_basis)tag(cell,row.product_cost_basis,row.product_cost_basis==='实际出库成本'?'good':'');
        if(row.product_cost_issue)el('small',row.product_cost_issue,cell);
        return cell;
      }
      function orderIdentifier(row){
        if(!row.order_type_label?.includes('样品单'))return row.order_id||row.external_id;
        const cell=el('span',null,null,'fi-settlement-amount');el('span',row.order_id||row.external_id,cell);
        const label=el('small',row.order_type_label.includes('免费')?'免费样品单':'样品单',cell,'fi-sample-tag');label.title=row.order_type_label;return cell;
      }
    }
    function body(){
      const host=root.querySelector('.fc-tab-content');if(!host)return;host.replaceChildren();
      globalThis.FinanceLabelCosts?.reset();
      if(state.tab==='labels'){
        if(globalThis.FinanceLabelCosts&&live)FinanceLabelCosts.mount(host,{...context,filters:{...state.labelFilters,start:state.start,end:state.end},
          onFiltersChange:filters=>{state.labelFilters=filters;state.start=filters.start;state.end=filters.end;context.onDatesChange?.({start:state.start,end:state.end});}});
        else el('p','月度账单导入在正式系统中使用。',host,'fi-muted');
        return;
      }
      ({costs:costPanel,manual:manualPanel,mapping:productPanel,rules:rulesPanel,orders:orderCostsPanel})[state.tab](host);
    }
    function draw(){
      root.replaceChildren();root.className='fc-workspace';
      if(!live){const banner=el('div',null,root,'fc-preview-note');el('span',preview?'设计预览':'暂无成本资料',banner,'fc-preview-label');el('span','本页预览不会修改正式数据。',banner);}
      const hero=el('div',null,root,'fc-hero'),copy=el('div',null,hero);el('h2','成本管理',copy);el('p','月度账单自动绑定订单，商品成本按分组维护。',copy);
      const shopControl=el('div',null,hero,'fc-shop-select');
      const groupSelect=select(shopControl,'成本分组',[['','请选择分组'],...(context.groups||[]).map(g=>[g.id,g.name])],state.groupId,v=>{
        if(!v){groupSelect.value=state.groupId;return;}
        groupSelect.disabled=true;shopSelect.disabled=true;
        shopSelect.replaceChildren();el('option','正在读取该分组店铺…',shopSelect).value='';
        for(const s of shops.filter(s=>s.group_id===v))el('option',s.display_name||s.name,shopSelect).value=s.id;
        shopSelect.value='';
        chooseGroup(v).catch(e=>{
          groupSelect.value=state.groupId;shopSelect.replaceChildren();
          for(const s of groupShops)el('option',(s.display_name||s.name)+(catalogFor(s).costRows?' · 已有成本资料':''),shopSelect).value=s.id;
          shopSelect.value=state.shopId;toast(e.message);
        }).finally(()=>{groupSelect.disabled=false;shopSelect.disabled=false;});
      });
      const shopSelect=select(shopControl,'财务店铺',groupShops.map(s=>[s.id,(s.display_name||s.name)+(catalogFor(s).costRows?' · 已有成本资料':'')]),state.shopId,v=>{
        shopSelect.disabled=true;chooseShop(v).catch(e=>{shopSelect.value=state.shopId;toast(e.message);}).finally(()=>{shopSelect.disabled=false;});
      });
      const shop=getShop();if(shop){const meta=el('div',null,shopControl,'fc-shop-meta');tag(meta,shop.platform==='SHOPEE'?'虾皮':'TikTok');el('span',(shop.region||'')+' · '+(context.groups?.find(g=>g.id===shop.group_id)?.name||'未分组')+' · 已授权',meta);}
      action(shopControl,'成本表设置',()=>{state.tab='rules';draw();},'fi-record-link').disabled=!state.shopId;
      const metrics=el('div',null,root,'fc-metrics fc-cost-metrics'),linked=currentProducts().filter(p=>mapped(p)).length;
      for(const [label,value,note]of [
        ['分组成本资料',applies()?costs.length+manualCosts.length:0,applies()?`${preview.imports?.length||0} 份文件 · ${manualCosts.length} 条手动成本`:'请选择成本分组'],
        ['已关联 SKU',linked,`当前店铺共 ${currentProducts().length} 个订单 SKU`],
        ['待关联 SKU',currentProducts().length-linked,'未匹配到编码的商品集中核对']
      ]){const n=el('div',null,metrics,'fc-metric');el('span',label,n);el('strong',value,n);el('small',note,n);}
      const tabs=el('div',null,root,'fc-tabs');tabs.setAttribute('role','tablist');
      if(state.tab==='labels'){shopControl.hidden=true;metrics.hidden=true;}
      for(const [id,title,number]of [['labels','月度贴单账单','01'],['costs','商品成本表','02'],['manual','手动管理','03'],['mapping','SKU 关联','04'],['orders','订单成本','05']]){
        const n=action(tabs,'',()=>{state.tab=id;state.page=1;state.search='';state.status='';draw();if(!['labels','orders'].includes(id)&&missingAutomatic())void autoMatch();});n.setAttribute('role','tab');n.setAttribute('aria-selected',String(state.tab===id));n.setAttribute('aria-controls','fc-panel');el('span',number,n);el('strong',title,n);
      }
      el('p','',root,'fc-feedback').setAttribute('role','status');const host=el('div',null,root,'fc-tab-content');host.id='fc-panel';host.setAttribute('role','tabpanel');body();
    }
    draw();
    if(!['orders','labels'].includes(state.tab)&&missingAutomatic())void autoMatch();
  }
  globalThis.FinanceCosts={mount,reset,candidate,shopId:()=>state.shopId,groupId:()=>state.groupId,openMapping:selection=>{
    state.mappingReturn=selection.returnToOrders?{shopId:state.shopId,warehouse:state.warehouse,orderPage:state.orderPage,orderContext:state.orderContext,orderCostStatus:state.orderCostStatus,start:state.start,end:state.end}:null;
    if(state.shopId!==selection.shopId)state.warehouse='';
    state.tab='mapping';state.shopId=selection.shopId;state.collapsedShopId='';state.search=selection.skuId;state.status='';state.page=1;
    state.spus={};state.spus[selection.shopId+':'+selection.productId]=true;
  },openOrders:filters=>{state.mappingReturn=null;state.tab='orders';state.orderNumbers=filters.numbers||'';state.start=filters.start;state.end=filters.end;state.orderPage=1;state.orderCostStatus=filters.costStatus==='PENDING'?'PENDING':'ALL';state.orderContext={shopId:filters.shopId||'',groupId:filters.groupId||'',platform:filters.platform||'',currency:filters.currency||'',kind:filters.kind||'SETTLED_ORDER',recordId:filters.recordId||''};if(filters.shopId)state.shopId=filters.shopId;}};
})();
