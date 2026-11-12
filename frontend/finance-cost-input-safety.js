/* Human cost inputs stay separate from effective prices until their checks pass. */
(() => {
  'use strict';
  const statuses={BLOCKED:'资料有误，需修正',REVIEW:'等待核对确认',READY:'等待确认',APPLIED:'已应用',REJECTED:'已驳回',STALE:'预览已过期'};
  const statusText=change=>change.status==='REVIEW'&&change.requiresIndependentReview?'等待其他财务人员复核':statuses[change.status]||change.status;
  const types={IMPORT:'导入成本表',REMOVE_SOURCE:'移除成本来源',MANUAL_CREATE:'新增手动成本',MANUAL_UPDATE:'修改手动成本',MANUAL_DELETE:'删除手动成本',MAPPING:'人工 SKU 关联',SETTINGS:'修改成本设置',UNDO:'撤销人工操作'};
  const el=(tag,text,parent,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=String(text);if(cls)n.className=cls;parent?.append(n);return n;};
  const number=value=>value==null?'未核定':Number(value).toLocaleString('zh-CN',{maximumFractionDigits:6});
  function mount(root,options){
    let disposed=false,sequence=0,timer=null,page=1;
    const {request,groupId='',shopId='',groups=[],shops=[],canManage=false,onChanged}=options,dialogs=new Set();
    root.replaceChildren();const head=el('div',null,root,'fi-card-head');el('h3','导入与操作核对',head);
    const actions=el('div',null,head,'fi-actions'),feedback=el('p','',root,'fc-feedback');feedback.setAttribute('role','status');
    el('p','这里核对人工上传和人工修改。暂存不会改变有效成本；确认前请核对来源、单位、差异及实际影响范围。',root,'fc-inline-note');
    const content=el('div',null,root,'fc-input-changes');
    function button(parent,text,fn,cls=''){
      const n=el('button',text,parent,cls);n.type='button';
      n.onclick=async()=>{if(n.disabled||disposed)return;n.disabled=true;try{await fn();}catch(error){const area=n.closest('dialog')?.querySelector('[role="alert"]')||feedback;if(area.isConnected)area.textContent=error.message||'操作未完成，请刷新后重试';}finally{if(n.isConnected)n.disabled=false;}};return n;
    }
    function modal(title){
      const n=el('dialog',null,document.body,'fi-dialog fc-dialog fc-input-dialog');dialogs.add(n);
      const h=el('div',null,n,'fi-dialog-head');el('h3',title,h);button(h,'关闭',()=>n.close(),'fi-close');
      const body=el('div',null,n,'fi-dialog-body'),alert=el('p','',body,'fc-alert');alert.setAttribute('role','alert');
      const foot=el('div',null,n,'fi-dialog-foot');n.addEventListener('close',()=>{dialogs.delete(n);n.remove();});n.showModal();return {n,body,foot,alert};
    }
    function field(parent,label,value='',kind='text'){
      const wrap=el('label',null,parent);el('span',label,wrap);const n=el(kind==='textarea'?'textarea':'input',null,wrap);
      if(kind!=='textarea')n.type=kind;n.value=value??'';n.setAttribute('aria-label',label);n.maxLength=kind==='textarea'?1000:500;return n;
    }
    function check(parent,label,checked=false){
      const wrap=el('label',null,parent,'fc-check'),n=el('input',null,wrap);n.type='checkbox';n.checked=checked;n.setAttribute('aria-label',label);el('span',label,wrap);return n;
    }
    function select(parent,label,items,value){
      const wrap=el('label',null,parent);el('span',label,wrap);const n=el('select',null,wrap);n.setAttribute('aria-label',label);
      for(const [key,title]of items)el('option',title,n).value=key;n.value=value||'';return n;
    }
    function table(parent,headers,rows){
      const wrap=el('div',null,parent,'fi-table-wrap'),t=el('table',null,wrap,'fi-table fc-table'),thead=el('tr',null,el('thead',null,t));
      for(const text of headers)el('th',text,thead).scope='col';const body=el('tbody',null,t);
      for(const row of rows){const tr=el('tr',null,body);if(row.id)tr.dataset.changeId=row.id;for(const value of row.cells){const td=el('td',null,tr);value instanceof Node?td.append(value):td.textContent=value??'—';}}
      return t;
    }
    function scopeText(change){
      if(typeof change.actualScope==='string')return change.actualScope;
      return change.actualScope?.description||change.impact?.actualScope||change.impact?.description||'以保存时的实际店铺、分组和历史范围为准';
    }
    function showImpact(parent,change){
      const impact=change.impact||{},scope=change.actualScope||{};el('strong','实际影响范围',parent);el('p',scopeText(change),parent,'fc-input-scope');
      table(parent,['范围项目','当前预览'],[
        {cells:['涉及店铺',number(impact.scopeShops??scope.scopeShops)]},
        {cells:['范围内订单',number(impact.scopeOrderCount??scope.scopeOrderCount)]},
        {cells:['预计金额变化',impact.amountChange==null?'未核定；不能把未知影响当作零':number(impact.amountChange)+' CNY']}
      ]);
      if(impact.coverage==='SCOPE_UPPER_BOUND')el('p','订单数为可能涉及的范围上限，并非已确认每笔都会改价；当前查询月份不会限制这次操作的真实范围。',parent,'fc-inline-note');
      if(impact.hasHistorical||scope.hasHistorical)el('p','这次操作可能影响已有历史成本及后续订单，需单独确认。',parent,'fc-alert');
    }
    function showIssues(parent,change){
      const issues=change.validation?.issues||[];if(!issues.length)return;
      el('h4','资料校验',parent);table(parent,['位置 / 型号','需要核对的内容','数据来源','处理方式'],issues.slice(0,100).map(issue=>({cells:[
        [issue.row?'第 '+issue.row+' 行':'',issue.sku,issue.warehouse,issue.month].filter(Boolean).join(' · ')||'本次操作',
        issue.message||issue.code,issue.source,issue.action
      ]})));
      if(issues.length>100)el('p','这里展示前 100 项异常，请先修正源表或分批核对。',parent,'fc-inline-note');
    }
    async function revise(change){
      const dialog=modal('补充人工成本资料'),meta=change.metadata||change.payload?.metadata||{};
      el('p','补充信息后重新校验；旧预览和确认将失效。编号或价格本身错误时，请修正原表重新上传。',dialog.body,'fc-inline-note');
      const unit=field(dialog.body,'成本计价单位',meta.unit||''),type=select(dialog.body,'编号类型',[['','请选择'],['SKU','商品 SKU'],['SPU','平台 SPU'],['MIXED','混合类型，逐行核对']],meta.identifierType);
      const source=field(dialog.body,'来源标识',meta.sourceKey||''),reason=field(dialog.body,'操作原因',meta.reason||'','textarea'),evidence=field(dialog.body,'依据编号或说明',meta.evidenceRef||'');
      const uniform=check(dialog.body,'确认 SPU 适用规格使用统一成本',meta.spuUniform===true),revision=check(dialog.body,'明确修订已有价格',meta.revision===true);
      button(dialog.foot,'保存并重新校验',async()=>{
        if(!reason.value.trim()||!evidence.value.trim())throw Error('请填写操作原因和核对依据');
        await request('/costs/input-changes/'+encodeURIComponent(change.id)+'/revise',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({version:change.version,metadata:{unit:unit.value.trim(),identifierType:type.value,sourceKey:source.value.trim(),reason:reason.value.trim(),evidenceRef:evidence.value.trim(),spuUniform:uniform.checked,revision:revision.checked}})});
        if(disposed)return;dialog.n.close();await refresh();if(disposed)return;feedback.textContent='资料已重新校验，请查看最新差异和状态。';
      },'fi-primary');
    }
    async function undo(change){
      const dialog=modal('申请撤销本次人工操作');el('p','系统会生成反向核对单，并重新检查当前配置。不会直接覆盖其他后续操作。',dialog.body,'fc-inline-note');
      const reason=field(dialog.body,'操作原因','','textarea'),evidence=field(dialog.body,'依据编号或说明','');
      button(dialog.foot,'生成撤销核对单',async()=>{
        if(!reason.value.trim()||!evidence.value.trim())throw Error('请填写撤销原因和依据');
        await request('/costs/input-changes/'+encodeURIComponent(change.id)+'/undo',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({version:change.version,reason:reason.value.trim(),evidenceRef:evidence.value.trim()})});
        if(disposed)return;dialog.n.close();await refresh();if(disposed)return;feedback.textContent='撤销核对单已生成，请核对后确认。';
      },'fi-primary');
    }
    async function details(id){
      const change=await request('/costs/input-changes/'+encodeURIComponent(id));if(disposed)return;
      const dialog=modal('人工成本操作核对'),meta=change.metadata||change.payload?.metadata||{};
      el('strong',change.sourceName||types[change.type]||change.type,dialog.body);
      el('p',(types[change.type]||change.type)+' · '+statusText(change),dialog.body,'fc-inline-note');
      const summary=change.inputSummary||{},validation=change.validation||{};
      const identityNames={SKU:'商品 SKU',SPU:'平台 SPU',MIXED:'SKU / SPU 混合类型，需核对'};
      const riskNames={BLOCKED:'存在硬性错误，不能应用',HIGH:'高风险，需复核',REVIEW:'需核对资料',LOW:'普通核对'};
      const risk=change.risk||validation.risk;
      table(dialog.body,['原始声明 / 校验摘要','待核对内容'],[
        {cells:['归属分组',groups.find(g=>g.id===change.groupId)?.name||change.groupId||'未提供']},
        {cells:['归属店铺',change.shopId?(shops.find(s=>s.id===change.shopId)?.display_name||shops.find(s=>s.id===change.shopId)?.name||change.shopId):'分组内相关店铺，范围见下方说明']},
        {cells:['提交人标识',change.submittedBy||'未提供']},
        {cells:['确认人标识',change.confirmedBy||'尚未确认']},
        {cells:['来源标识',meta.sourceKey||summary.sourceKey||'未填写，需对照原始凭据核对']},
        {cells:['成本计价单位',meta.unit||summary.unit||'未提供，请核对原表及配置差异']},
        {cells:['编号类型',identityNames[meta.identifierType||summary.identifierType]||'未明确，需核对']},
        {cells:['SPU 统一成本声明',meta.spuUniform===true?'已声明；需对照适用规格和依据核对':'未声明，不能推定同一 SPU 的所有规格同价']},
        {cells:['校验摘要',`新增或变更成本单元：${number(validation.changedCells)}；未变单元：${number(validation.unchangedCells)}`]},
        {cells:['风险判断',riskNames[risk]||risk||(change.requiresIndependentReview?'需要独立复核':'需要核对资料') ]}
      ]);
      if(change.requiresIndependentReview)el('p','此操作需要另一位有财务管理权限的人员复核，提交者不能自审。',dialog.body,'fc-alert');
      showImpact(dialog.body,change);showIssues(dialog.body,change);
      const diffs=Array.isArray(change.diff)?change.diff:change.diff?.items||change.diff?.rows||[];
      const valueText=value=>value!=null&&typeof value==='object'?JSON.stringify(value):value??'无';
      if(diffs.length){el('h4','旧值与新值',dialog.body);table(dialog.body,['型号 / 配置','成本仓 / 月份','原值','新值','原来源','新来源'],diffs.slice(0,100).map(item=>({cells:[item.sku||item.field||item.code,[item.warehouse,item.month].filter(Boolean).join(' · '),valueText(item.oldPrice??item.before),valueText(item.newPrice??item.after),item.oldSource||'未提供',item.newSource||'未提供']})));}
      const reason=field(dialog.body,'操作原因',meta.reason||change.reason||'','textarea'),evidence=field(dialog.body,'依据编号或说明',meta.evidenceRef||change.evidenceRef||'');
      reason.readOnly=!canManage;evidence.readOnly=!canManage;
      if(canManage&&change.canConfirm&&change.status!=='BLOCKED'&&!['APPLIED','REJECTED','STALE'].includes(change.status)){
        const impact=check(dialog.body,'我已核对实际影响范围'),historical=(change.impact?.hasHistorical||change.actualScope?.hasHistorical)?check(dialog.body,'我已确认会影响历史及后续订单'):null;
        const revision=validation.priceOverride===true||meta.revision===true||change.validation?.issues?.some(i=>['PRICE_REVISION_REQUIRED','PRICE_OVERRIDE'].includes(i.code))?check(dialog.body,'明确修订已有价格',meta.revision===true):null;
        button(dialog.foot,change.requiresIndependentReview?'复核并应用':'确认应用',async()=>{
          if(!impact.checked||historical&&!historical.checked)throw Error('请明确确认实际影响范围及历史影响');
          if(!reason.value.trim()||!evidence.value.trim())throw Error('请填写操作原因和核对依据');
          if(revision&&!revision.checked)throw Error('请明确声明本次价格修订');
          const result=await request('/costs/input-changes/'+encodeURIComponent(change.id)+'/confirm',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({version:change.version,reason:reason.value.trim(),evidenceRef:evidence.value.trim(),acknowledgeImpact:true,acknowledgeHistorical:historical?.checked===true,revision:revision?.checked===true})});
          if(disposed)return;dialog.n.close();await refresh();if(disposed)return;feedback.textContent=result.status==='APPLIED'?'本次操作已应用，相关成本按现有后台流程更新。':'本次确认已记录，请查看最新状态。';await onChanged?.();
        },'fi-primary');
      }
      if(canManage&&change.canRevise&&!['APPLIED','REJECTED'].includes(change.status))button(dialog.foot,'补充资料',async()=>{dialog.n.close();await revise(change);});
      if(canManage&&change.canReject&&!['APPLIED','REJECTED'].includes(change.status))button(dialog.foot,'驳回',async()=>{
        if(!reason.value.trim())throw Error('请填写驳回原因');
        await request('/costs/input-changes/'+encodeURIComponent(change.id)+'/reject',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({version:change.version,reason:reason.value.trim()})});
        if(disposed)return;dialog.n.close();await refresh();if(disposed)return;feedback.textContent='本次暂存已驳回，有效成本保持原结果。';
      });
      if(canManage&&change.canUndo&&change.status==='APPLIED')button(dialog.foot,'撤销本次操作',async()=>{dialog.n.close();await undo(change);});
    }
    function draw(data){
      const next=content.cloneNode(false),items=data.items||[];
      table(next,['操作 / 来源','状态','影响范围','操作'],items.map(change=>{
        const title=el('div');el('strong',change.sourceName||types[change.type]||change.type,title);el('small',types[change.type]||change.type,title);
        const controls=el('div',null,null,'fi-actions');button(controls,'查看',()=>details(change.id),'fi-record-link');
        return {id:change.id,cells:[title,statusText(change),scopeText(change),controls]};
      }));
      if(!items.length)el('p','当前范围没有人工成本核对记录。',next,'fc-empty');
      const pages=el('div',null,next,'fi-pagebar');el('span',number(data.total??items.length)+' 条 · 第 '+page+' 页',pages);
      button(pages,'上一页',async()=>{page--;await refresh();}).disabled=page<=1;
      button(pages,'下一页',async()=>{page++;await refresh();}).disabled=page*(data.pageSize||20)>=Number(data.total??items.length);
      content.replaceChildren(...next.childNodes);
    }
    async function refresh(){
      if(disposed||!root.isConnected)return;clearTimeout(timer);const run=++sequence;
      try{const q=new URLSearchParams({groupId,shopId,page});const data=await request('/costs/input-changes?'+q);if(disposed||run!==sequence)return;draw(data);feedback.textContent='';}
      catch(error){if(!disposed&&run===sequence)feedback.textContent=error.message||'核对记录读取失败，请刷新重试';}
      finally{if(!disposed&&run===sequence)timer=setTimeout(()=>{if(!document.hidden)void refresh();},15000);}
    }
    button(actions,'刷新',()=>refresh());void refresh();
    return {dispose(){disposed=true;sequence++;clearTimeout(timer);for(const dialog of dialogs)dialog.close();dialogs.clear();}};
  }
  globalThis.FinanceCostInputSafety={mount};
})();
