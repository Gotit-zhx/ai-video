(function(){
  'use strict';
  const money=['amount','cash_amount','credit_amount','grant_amount'];
  const labels={amount:'消耗合计',cash_amount:'现金消耗',credit_amount:'信用额度消耗',grant_amount:'广告费用赠款消耗',net_amount:'净消耗（剔除赠款）'};
  const sources={IMPORT_FUNDING:'资金消耗报表',API_GMV:'GMV Max 报表',API_BASIC:'账户投放报表',API_CAMPAIGN:'系列 / 商品投放报表',LEGACY_IMPORT:'历史导入'};
  const states={PENDING:'待分配',PARTIAL:'余款待分配',CONFIRMED:'已核实分配',OFFICIAL:'来源已归店',REVIEW:'来源变化待复核',SOURCE_REVIEW:'账户来源待核实 · 未叠加'};
  const numeric=v=>v==null||v===''?'未提供':Number(v).toLocaleString('zh-CN',{maximumFractionDigits:2});
  const moneyText=v=>v==null||v===''?'未提供':Number(v).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
  function amount(row,key){
    if(row[key]!=null)return moneyText(row[key]);
    return Number(row['known_'+key])>0?moneyText(row['partial_'+key])+'（已知部分）':row['reason_'+key]||'未提供';
  }
  function current(ctx,owner){if(!ctx.allowed()||ctx.company()!==owner)throw Error('当前登录公司或权限已变化，请重新打开此页面');}
  function render(parent,ctx){
    const {el,card,table,button,report}=ctx,main=el('section',null,parent,'fi-ad-spend');main.dataset.adSpendWorkspace='';
    el('p','统一展示 TikTok 与 Shopee 已取得的投放消耗。现金、信用额度、广告赠款分别保留；账户、推广系列可能跨店，缺少可靠依据的金额保留待分配。当前合计不表示已完整同步或完成对账。',main,'fi-muted');
    const totals=el('div',null,main,'fi-metrics');
    for(const total of report.totals||[])for(const k of [...money,'net_amount']){const box=el('div',null,totals,'fi-metric');el('span',labels[k],box,'fi-metric-label');el('strong',amount(total,k),box);el('small',total.currency,box);}
    if(!(report.totals||[]).length)el('p','当前范围没有已取得的投放消耗，不能解释为实际零消耗。',main,'fi-empty');
    if(report.overlapExcluded)el('p','已排除 '+numeric(report.overlapExcluded)+' 条重叠来源：相同系列资金明细优先；账户汇总与系列明细不叠加。来源覆盖尚需核对，不能视为全量已完成对账。',main,'fi-muted');
    if(report.overlapUnresolved)el('p',numeric(report.overlapUnresolved)+' 条历史来源缺少账户身份，与 API 存在潜在重叠；明细保留，汇总显示可确认的已知部分，请使用真实账户 ID 核实来源。',main,'fi-feedback');
    const headers=['现金消耗','信用额度消耗','广告费用赠款消耗','消耗合计','净消耗（剔除赠款）'];
    const values=r=>['cash_amount','credit_amount','grant_amount','amount','net_amount'].map(k=>amount(r,k));
    el('p','净消耗来自 TikTok net_cost / billed_cost，表示剔除广告赠款、优惠券后的消耗，不等于现金付款。信用额度是授信资金，不能与广告赠款（ad credit）混为一项；接口未拆分的金额不会从合计倒算。',main,'fi-muted');
    const grouped=card(main,'分组广告消耗','按平台、分组和展示币种统计；待分配金额保留在未分组中',12);
    table(grouped.n,['平台','店铺分组','有记录店铺数','币种',...headers,'待分配记录数'],(report.groups||[]).map(r=>[
      ctx.platformBadge(r.platform),button(r.group_name||'未分组',null,()=>ctx.selectFilters({groupId:r.group_id||'UNGROUPED',shopId:''}),'fi-action-link'),numeric(r.shop_count),r.currency,...values(r),numeric(r.pending_records)]));
    const stores=card(main,'店铺广告消耗','仅使用来源明确归店或人工核实的金额；未知分项不补零',12);
    table(stores.n,['平台','店铺','分组','币种',...headers,'记录数','归属状态'],(report.shops||[]).map(r=>[
      ctx.platformBadge(r.platform),r.shop_id==='UNASSIGNED'?r.shop_name:button(r.shop_name,null,()=>ctx.selectFilters({shopId:r.shop_id}),'fi-action-link'),r.group_name||'未分组',r.currency,...values(r),numeric(r.records),r.source_review_records?'来源待核对':r.pending_records?'待分配':r.records?'已归属':'尚未取得数据']));
    const detail=card(main,'店铺消耗明细','汇总覆盖全部查询页；下方系列查询及归属筛选只影响此明细表',12);
    const search=el('form',null,detail.n,'fi-actions');const input=el('input',null,search);input.placeholder='系列名称 / ID / 账户 / 店铺';input.setAttribute('aria-label','消耗系列或账户查询');input.value=ctx.search||'';
    const assignment=el('select',null,search);assignment.setAttribute('aria-label','消耗归属状态');for(const [v,l]of [['','全部归属状态'],['PENDING','待分配（含余款及待复核）'],['CONFIRMED','已核实分配'],['OFFICIAL','来源已归店']]){const n=el('option',l,assignment);n.value=v;}assignment.value=ctx.assignment||'';
    const submit=el('button','查询',search);submit.type='submit';search.onsubmit=e=>{e.preventDefault();ctx.query(input.value,assignment.value);};
    table(detail.n,['日期','平台','店铺','展示币种',...headers,'归属状态','账户 / 来源店铺 ID','推广系列 / 广告名称及 ID','分组','投放类型','原币','原币合计','来源','金额口径','操作'],(report.items||[]).map(r=>{
      const account=el('span',null),campaign=el('span',null),basis=el('span',r.source==='API_GMV'?'官方总额及净额':r.source==='API_BASIC'?'账户日报':r.source==='IMPORT_FUNDING'?'资金分项报表':'以来源字段为准');basis.title=r.funding_basis||'仅展示来源明确提供的金额';el('span',r.account_name||'账户未提供',account);el('small',r.account_id||'ID 未提供',account);el('span',r.campaign_name||'名称未提供',campaign);el('small',r.campaign_id||'账户汇总（未提供系列 ID）',campaign);
      const operation=ctx.canManage()&&!['OFFICIAL','SOURCE_REVIEW'].includes(r.assignment_status)&&(r.shop_id==='UNASSIGNED'||r.source==='IMPORT_FUNDING')?button('分配 / 核实',null,()=>allocate(ctx,r.source_key),'fi-action-link'):'—';if(operation instanceof Node)operation.dataset.adSpendAllocate='';
      return [r.record_date,ctx.platformBadge(r.platform),r.shop_name,r.currency,...values(r),states[r.assignment_status]||r.assignment_status,account,campaign,r.group_name||'未分组',r.report_type||'未提供',r.source_currency,moneyText(r.original_amount),sources[r.source]||r.source,basis,operation];
    }));
    const pager=el('div',null,detail.n,'fi-pagebar');el('span','共 '+numeric(report.total)+' 条 · 第 '+numeric(report.page)+' 页 · 每页 50 条',pager);const buttons=el('div',null,pager,'fi-actions');
    button('上一页',buttons,()=>ctx.page(-1)).disabled=report.page<=1;const next=button('下一页',buttons,()=>ctx.page(1));next.dataset.adSpendNext='';next.disabled=report.page*report.pageSize>=report.total;
    const funding=el('details',null,main,'fi-card fi-ad-spend-support');funding.dataset.adFunding='';el('summary','账户级资金核对（不计入店铺合计）',funding);
    el('p','以下为普通投放账户日报的资金指标，仅供账户核对；与 GMV Max 或店铺明细的覆盖范围可能不同，不能相加或按比例拆店。现金和赠券指标可能延迟 24–48 小时，净消耗可能延迟 11 小时。余额、充值、授信额度均不等于广告消耗。',funding,'fi-muted');
    if((report.accountFunding||[]).length)table(funding,['平台','账户','账户 ID','展示币种',...headers],report.accountFunding.map(r=>[ctx.platformBadge(r.platform),r.account_name||'账户名称未提供',r.account_id,r.currency,...values(r)]));
    else el('p','当前范围尚无可核对的账户资金日报；店铺已取得的 GMV Max 净消耗仍在上方展示。',funding,'fi-empty');
    el('p','资金消耗和投放效果、平台结算扣费是不同口径；本工作台不重复加计广告佣金，也不自动改写利润流水。原币与参考汇率折算均保留。',main,'fi-muted');
    for(const t of main.querySelectorAll('table'))[...t.querySelectorAll('thead th')].forEach((th,i)=>{if(/消耗|合计/.test(th.textContent))for(const row of t.rows)if(row.cells[i])row.cells[i].dataset.adMoney='';});
  }
  async function allocate(ctx,key){
    const owner=ctx.company(),d=ctx.dialog('核实店铺消耗分配');
    try{
      const source=await ctx.request('/advertising/spend/'+encodeURIComponent(key));current(ctx,owner);if(!d.d.isConnected)return;
      const {el,button,select,field}=ctx;
      el('p','按原币 '+source.currency+' 核实各店金额。仅填写已核实的部分，剩余金额继续待分配；不会按比例、系列名称或账户访问关系猜分。',d.body,'fi-muted');
      el('p',money.map(k=>labels[k]+'：'+numeric(source[k])).join(' · '),d.body,'fi-muted');
      const host=el('div',null,d.body),rows=[];const options=(ctx.shops||[]).filter(s=>s.platform===source.platform).map(s=>[s.id,s.display_name||s.name]);
      if(!options.length){el('p','当前公司没有该平台的可分配财务店铺，请先连接店铺。',d.body,'fi-muted');return;}
      function add(initial={}){
        const n=el('div',null,host,'fi-grid2'),shop=select(n,'店铺',options,initial.shop_id||options[0][0],()=>{}),controls={};
        for(const k of money){if(source[k]==null)continue;const control=field(n,labels[k]+'（'+source.currency+'）','number',initial[k]??(Number(source[k])===0?'0':''));control.step='0.000001';control.dataset.allocationField=k;controls[k]=control;}
        const row={n,shop,controls};rows.push(row);button('移除此分配行',n,()=>{n.remove();rows.splice(rows.indexOf(row),1);});
      }
      for(const a of source.allocations||[])if(a.fingerprint===source.fingerprint)add(a);
      const addButton=button('添加店铺分配',d.body,()=>add());addButton.dataset.allocationAdd='';
      el('p','移除全部人工分配后会重新尝试来源匹配，无法明确匹配的记录恢复待分配；此前版本仍保留。',d.body,'fi-muted');
      d.submit('保存核实金额',async()=>{
        current(ctx,owner);const allocations=rows.map(row=>({shop_id:row.shop.value,...Object.fromEntries(Object.entries(row.controls).map(([k,n])=>{if(n.value.trim()==='')throw Error('请填写来源已提供的全部金额分项');return [k,n.value];}))}));
        await ctx.request('/advertising/spend/'+encodeURIComponent(key)+'/allocations',ctx.json('PUT',{organizationId:owner,fingerprint:source.fingerprint,allocations}));current(ctx,owner);d.d.close();await ctx.reload();
      });
    }catch(e){d.feedback.textContent=e.message;}
  }
  function importDialog(ctx){
    const owner=ctx.company(),{el,field,select}=ctx,d=ctx.dialog('导入广告资金消耗');let ready=false,previewSignature='',previewFile=null;
    el('p','API 自动同步继续运行。此入口补充平台资金报表的现金、信用额度、广告赠款三项，不重复发布到利润流水。无需填写令牌。',d.body,'fi-muted');
    const platform=select(d.body,'平台',[['TIKTOK_SHOP','TikTok'],['SHOPEE','Shopee']],ctx.filters.platform||'TIKTOK_SHOP',()=>{ready=false;sign.value=platform.value==='TIKTOK_SHOP'?'true':'false';});
    const available=()=>platform.value==='TIKTOK_SHOP'?(ctx.accounts||[]).map(a=>({id:a.advertiser_id,name:a.name||a.advertiser_id})):(ctx.shops||[]).filter(s=>s.platform==='SHOPEE'&&s.external_shop_id).map(s=>({id:s.external_shop_id,name:s.display_name||s.name}));
    const choices=()=>[['','手动填写来源平台 ID'],...available().map(s=>[s.id,s.name])];
    let id,name;const account=select(d.body,'已有投放来源（可选）',choices(),'',value=>{id.value=value;name.value=available().find(a=>a.id===value)?.name||'';ready=false;});
    id=field(d.body,'TK 广告账户 ID / Shopee 平台店铺 ID');name=field(d.body,'账户或来源店铺名称');
    platform.addEventListener('change',()=>{account.replaceChildren();for(const [value,label]of choices())el('option',label,account).value=value;account.value='';id.value='';name.value='';ready=false;});
    const sign=select(d.body,'源金额符号',[['true','扣费为负数（TK 资金报表）'],['false','消耗为正数']],platform.value==='TIKTOK_SHOP'?'true':'false',()=>{ready=false;});
    const file=field(d.body,'广告消耗报表 CSV / XLSX','file');file.accept='.csv,.xlsx';
    el('p','必需列：日期、推广系列 ID（Shopee 可为广告 ID）、金额、币种。现金消耗、信用额度消耗、广告费用赠款消耗存在时自动读取。上限 12 MiB / 20,000 行；文件名和系列名称不用于自动归店。已有 API 记录时请填写真实账户 ID 核对重叠。',d.body,'fi-muted');
    const preview=el('div',null,d.body);
    const form=mode=>{current(ctx,owner);if(!file.files?.[0])throw Error('请选择文件');if(!id.value.trim()||!name.value.trim())throw Error('请填写真实广告账户 ID 及来源名称');const f=new FormData();for(const [k,v]of Object.entries({file:file.files[0],organizationId:owner,platform:platform.value,accountId:id.value.trim(),accountName:name.value.trim(),debitNegative:sign.value,preview:String(mode)}))f.append(k,v);return f;};
    const signature=()=>JSON.stringify([platform.value,id.value,name.value,sign.value]);
    for(const n of [file,id,name])for(const event of ['change','input'])n.addEventListener(event,()=>{ready=false;});
    ctx.button('校验并预览',d.body,async b=>{b.disabled=true;ready=false;const selectedFile=file.files?.[0],selection=signature();try{const response=await ctx.request('/advertising/imports',{method:'POST',body:form(true)});current(ctx,owner);if(selectedFile!==file.files?.[0]||selection!==signature())throw Error('文件或来源已变化，请重新校验');preview.replaceChildren();el('p','读取 '+response.rows+' 行，导入后先保留待分配；有精确官方归属时自动匹配。',preview);ctx.table(preview,['币种','现金消耗','信用额度消耗','广告费用赠款消耗','消耗合计'],response.totals.map(r=>[r.currency,...['cash_amount','credit_amount','grant_amount','amount'].map(k=>amount(r,k))]));previewSignature=selection;previewFile=selectedFile;ready=true;d.feedback.textContent='校验通过，请核对金额控制合计。';}catch(e){d.feedback.textContent=e.message;}finally{b.disabled=false;}});
    d.submit('导入资金消耗',async()=>{if(!ready||previewSignature!==signature()||previewFile!==file.files?.[0])throw Error('请先校验并核对预览');const result=await ctx.request('/advertising/imports',{method:'POST',body:form(false)});current(ctx,owner);d.d.close();await ctx.reload();ctx.notify('新增 '+result.inserted+' 条；已存在 '+result.duplicateRows+' 条，未重复累计。');});
  }
  async function exportReport(ctx){
    const owner=ctx.company();current(ctx,owner);const response=await fetch('/api/v1/finance/advertising/export?'+new URLSearchParams({...ctx.filters,search:ctx.search||'',assignment:ctx.assignment||''}),{credentials:'same-origin'});
    if(!response.ok){let message='广告消耗导出失败';try{message=(await response.json()).message||message;}catch{}throw Error(message);}const blob=await response.blob();current(ctx,owner);
    const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='广告消耗三表-'+ctx.filters.start+'-'+ctx.filters.end+'.xlsx';document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),10000);
  }
  window.FinanceAdSpend={render,importDialog,exportReport};
})();
