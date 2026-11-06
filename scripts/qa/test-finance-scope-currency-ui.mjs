import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-overview',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,root=d.querySelector('main'),reads=[],exports=[];
w.currentUser={organizationId:'org',userId:'user',permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){},getWidth(){return 1;},getHeight(){return 1;}})};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
w.URL.createObjectURL=()=> 'blob:scope-test';w.URL.revokeObjectURL=()=>{};
w.HTMLAnchorElement.prototype.click=function(){};
w.fetch=async path=>{exports.push(new URL(path,'https://example.test'));return {ok:true,blob:async()=>new w.Blob(['test'])};};
const groups=[{id:'baby',name:'母婴组'},{id:'clothes',name:'服装组'}];
const shops=[
  {id:'ph',name:'菲律宾店',platform:'TIKTOK_SHOP',currency:'PHP',group_id:'baby',amount:123.45},
  {id:'my',name:'马来店',platform:'TIKTOK_SHOP',currency:'MYR',group_id:'baby',amount:678.9},
  {id:'th',name:'泰国店',platform:'SHOPEE',currency:'THB',group_id:'clothes',amount:246.8},
  {id:'sg',name:'未分组店',platform:'SHOPEE',currency:'SGD',amount:357.9}
];
const fields=['order_id','amount'].map(id=>({id,label:id==='amount'?'原币金额':'订单编号',type:id==='amount'?'money':'text'}));
const fieldSettings={fields,visible:fields.map(f=>f.id),exported:fields.map(f=>f.id)};
let heldShop='',heldRecords='',failedShop='',pending=[],scopeResponse=0;
function scoped(q){
  return shops.filter(s=>(!q.shopId||q.shopId===s.id)&&(!q.platform||q.platform===s.platform)
    &&(!q.groupId||(q.groupId==='UNGROUPED'?!s.group_id:q.groupId===s.group_id)));
}
function response(q){
  const cny=q.displayCurrency==='CNY',available=scoped(q),currency=q.currency||(cny?'':available[0]?.currency||'');
  const selected=available.filter(s=>!currency||currency===s.currency);
  const amount=selected.reduce((n,s)=>n+s.amount,0),displayCurrency=cny?'CNY':currency;
  const quote={id:'fx-'+(++scopeResponse),time:'2026-10-29T00:00:00Z',provider:'test'};
  return {
    filters:{...q,currency,displayCurrency},fx:cny?quote:null,currencies:[...new Set(available.map(s=>s.currency))],
    groups,shops,sync:[],fees:[],statuses:[],trend:[],hourly:[],recent:[],updatedAt:'2026-10-29T00:00:00Z',
    totals:['ORDER','SETTLEMENT','BILL','PENDING','AD','FEE'].map(kind=>({kind,records:selected.length,known_amounts:selected.length,known_revenues:selected.length,amount,revenue:amount})),
    ranking:selected.map(s=>({shop_id:s.id,kind:'SETTLEMENT',amount:s.amount,settled_gross_amount:s.amount,currency:displayCurrency,records:1})),
    profit:{currency:displayCurrency,settledGrossAmount:amount,platformFees:0,advertising:0,productCost:10,operatingCost:10,logisticsLabelCost:0,amount:amount-10,notes:[]},
    items:selected.map(s=>({id:s.id,order_id:'ORDER-'+s.id,shop_id:s.id,platform:s.platform,kind:q.kind||'BILL',currency:s.currency,amount:s.amount})),
    fieldSettings,total:selected.length?100:0,page:Number(q.page)||1,pageSize:50
  };
}
w.api=async (path,options={})=>{
  const url=new URL(path,'https://example.test'),q=Object.fromEntries(url.searchParams),endpoint=url.pathname.replace('/api/v1/finance','');
  reads.push({url,q,endpoint,signal:options.signal});
  if(['/overview','/reconciliation','/screen','/records'].includes(endpoint)){
    if(failedShop&&q.shopId===failedShop)throw Error('Internal server error');
    const result=response(q);
    if((heldShop&&q.shopId===heldShop&&endpoint!=='/records')||(heldRecords&&q.shopId===heldRecords&&endpoint==='/records'))await new Promise(resolve=>pending.push(resolve));
    return {data:result};
  }
  if(endpoint==='/ads')return {data:[]};
  if(endpoint==='/shops')return {data:{items:shops,groups,sync:[]}};
  if(endpoint.startsWith('/fields/'))return {data:fieldSettings};
  if(endpoint==='/workspace/charges')return {data:{rules:[],imports:[],rows:[],totals:{records:0,unmatched:0},marked:{orders:0}}};
  if(endpoint==='/workspace/costs'){
    const shop=shops.find(s=>s.id===q.shopId)||shops.find(s=>!q.groupId||s.group_id===q.groupId);
    return {data:{live:true,shopId:shop.id,groupId:shop.group_id,groups,costs:[],manualCosts:[],products:[],mappings:{},settings:{},shopCatalogs:{}}};
  }
  if(endpoint==='/workspace/costs/orders'){
    const available=scoped(q).filter(s=>!q.currency||q.currency===s.currency);
    return {data:{items:available.map(s=>({id:s.id,order_id:'COST-'+s.id,shop_id:s.id,product_cost_cny:10,settlement_currency:s.currency,settlement_goods_amount:s.amount})),
      total:available.length,page:1,pageSize:50,productCosts:{total:{amount_cny:available.length*10,ready_count:available.length,pending_count:0}}}};
  }
  throw Error('Unexpected API '+path);
};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-costs.js','finance-charges.js','finance-workspace.js']){
  const path=file==='finance-workspace.js'&&process.env.FINANCE_WORKSPACE_SOURCE||new URL('../../frontend/'+file,import.meta.url);
  w.eval(await fs.readFile(path,'utf8'));
}
async function wait(test){for(let i=0;i<200;i++){if(test())return;await new Promise(r=>setTimeout(r,5));}throw Error('UI timeout: '+root.textContent.slice(-350));}
const ready=()=>wait(()=>root.getAttribute('aria-busy')==='false');
const pause=()=>new Promise(r=>setTimeout(r,10));
const button=(text,host=d)=>[...host.querySelectorAll('button')].find(b=>b.textContent.trim()===text);
async function click(text,host=d){const b=button(text,host);assert(b,'button '+text);b.click();await ready();}
function change(label,value){
  const input=d.querySelector(`[aria-label="${label}"]`);assert(input,label);input.value=value;assert.equal(input.value,value,label+' option');
  input.dispatchEvent(new w.Event('change'));
}
async function choose(label,value){change(label,value);await ready();}
const reports=()=>reads.filter(r=>['/overview','/screen','/reconciliation'].includes(r.endpoint));
const lastReport=()=>reports().at(-1);
function assertScopeRequest(shopId,displayCurrency){
  const q=lastReport().q;
  assert.equal(q.shopId,shopId);assert.equal(q.currency,'','new scope must resolve its own native currency');
  assert.equal(q.fxSnapshot,'','new scope must obtain its own FX snapshot');assert.equal(q.displayCurrency,displayCurrency);
}
function assertVisible(route,shop){
  assert.equal(d.querySelector('[aria-label="店铺"]').value,shop.id);
  if(route==='overview')assert.equal(d.querySelector('[data-metric="SETTLEMENT"] strong').textContent,String(shop.amount));
  else if(route==='live')assert.equal(d.querySelector('.fi-live-amount strong').textContent,String(shop.amount));
  else if(route==='ads')assert(root.textContent.includes(String(shop.amount)));
  else assert([...root.querySelectorAll('tbody tr')].some(r=>r.textContent.includes('ORDER-'+shop.id)&&r.textContent.includes(String(shop.amount))),route+' renders new shop rows immediately');
}
try{
  // Every reporting route shares the same scope transitions, in both display modes.
  for(const route of ['overview','settlements','orders','reconciliation','fee-records','ads','live']){
    w.FinanceWorkspace.reset();w.FinanceWorkspace.enter(route);await ready();
    if(route!=='live'){await choose('开始日期','2026-09-01');await choose('结束日期','2026-09-30');}
    for(const mode of ['ORIGINAL','CNY']){
      if(route==='live')await choose('金额口径',mode);else await click(mode==='CNY'?'人民币 ¥':'原币',d.querySelector('.fi-pills'));
      await choose('店铺','ph');
      if(mode==='CNY'&&route!=='live')await choose('原币','PHP'); // Explicit native filter must not leak either.
      await choose('店铺','my');assertScopeRequest('my',mode);assertVisible(route,shops[1]);
      if(route!=='live'){assert.equal(lastReport().q.start,'2026-09-01');assert.equal(lastReport().q.end,'2026-09-30');}
      const tabs={settlements:[['SETTLEMENT','结算明细合计'],['BILL','结算账单合计'],['PENDING','待结算合计']],
        // Qianyi refunds retain upstream currencies; their native filters are
        // covered by test-finance-qianyi-refunds-ui.mjs.
        orders:[['ORDER','订单']], 'fee-records':[['FEE','平台费用'],['COST','物流贴单成本']],
        ads:[['AD','广告费用明细'],['AD_GMV','GMV Max 分析明细']]}[route]||[];
      for(const [kind,label]of tabs){
        const tab=[...d.querySelectorAll('.fi-settlement-totals button,.fi-tabs button')].find(b=>b.textContent.startsWith(label));
        assert(tab,label);tab.click();await ready();
        await choose('店铺','ph');await choose('店铺','my');assertScopeRequest('my',mode);assertVisible(route,shops[1]);
        assert.equal(reads.filter(r=>r.endpoint==='/records').at(-1).q.kind,kind);
      }
      if(['settlements','orders','fee-records'].includes(route)){
        const rowQuery=reads.filter(r=>r.endpoint==='/records').at(-1).q;
        assert.equal(rowQuery.currency,mode==='CNY'?'':'MYR');assert.equal(rowQuery.shopId,'my');
        if(mode==='CNY')assert(rowQuery.fxSnapshot.startsWith('fx-'),'rows wait for the newly resolved FX');
        await click('下一页');await choose('店铺','ph');
        assert.equal(reads.filter(r=>r.endpoint==='/records').at(-1).q.page,'1');
      }
      await choose('店铺','th');assertScopeRequest('th',mode);assertVisible(route,shops[2]);
      await choose('店铺','');assertScopeRequest('',mode);
      assert.equal(d.querySelector('[aria-label="原币"]')?.value,mode==='CNY'?(route==='live'?undefined:''):'PHP');
    }
  }
  w.FinanceWorkspace.reset();w.FinanceWorkspace.enter('overview');await ready();
  await click('原币',d.querySelector('.fi-pills'));await choose('店铺','ph');
  await choose('平台','SHOPEE');assertScopeRequest('','ORIGINAL');assert.equal(d.querySelector('[aria-label="原币"]').value,'THB');
  await choose('平台','');assertScopeRequest('','ORIGINAL');
  await choose('店铺分组','clothes');assertScopeRequest('','ORIGINAL');assert.equal(d.querySelector('[aria-label="原币"]').value,'THB');
  await choose('店铺分组','UNGROUPED');assertScopeRequest('','ORIGINAL');assert.equal(d.querySelector('[aria-label="原币"]').value,'SGD');
  await choose('店铺分组','');await choose('店铺','ph');
  await click('母婴组',d.querySelector('[data-update-key="分组经营明细"]'));
  assertScopeRequest('','ORIGINAL');assert.equal(lastReport().q.groupId,'baby');
  // Changing the native currency intentionally inside a scope still works.
  await choose('原币','MYR');assert.equal(lastReport().q.currency,'MYR');
  // Late PHP responses may not overwrite the newer MYR selection or its rows.
  await choose('店铺分组','');heldShop='ph';change('店铺','ph');await wait(()=>pending.length===1);
  assert.equal(root.getAttribute('aria-busy'),'true');
  change('店铺','my');await ready();assertVisible('overview',shops[1]);
  heldShop='';pending.splice(0).forEach(resolve=>resolve());await pause();assertVisible('overview',shops[1]);
  // A scope change hides old amounts and blocks stale drilldowns.
  heldShop='ph';change('店铺','ph');await wait(()=>pending.length===1);
  assert(root.classList.contains('fi-selection-pending'));
  assert(root.querySelector('.fi-content').hidden,'old-scope amounts must be hidden while the new query runs');
  assert(root.textContent.includes('正在查询所选店铺和日期'));
  d.querySelector('[data-metric="OPERATING_COST"]').click();
  assert.equal(d.querySelector('dialog'),null,'old amounts cannot open a new-shop detail');
  const abandoned=lastReport();change('店铺','my');await ready();
  assert.equal(abandoned.signal.aborted,true,'superseded network reads are cancelled');
  assert(!root.classList.contains('fi-selection-pending'));
  heldShop='';pending.splice(0).forEach(resolve=>resolve());await pause();
  failedShop='ph';change('店铺','ph');await ready();
  assert(root.querySelector('.fi-content').hidden,'a failed new-scope request must not show old amounts');
  assert(root.querySelector('.fi-state').textContent.includes('已停止自动重试'));
  assert(!root.querySelector('.fi-state').textContent.includes('Internal server error'));
  assert(root.querySelector('.fi-state').classList.contains('is-error'),'an actual failed request must not pretend it is still computing');
  failedShop='';await click('刷新');assertVisible('overview',shops[0]);
  assert(!root.querySelector('.fi-content').hidden,'a successful retry restores the selected results');
  failedShop='ph';await click('刷新');
  assert(!root.querySelector('.fi-content').hidden,'same-scope refresh failure keeps the last matching results');
  assert(root.querySelector('.fi-state').textContent.includes('相同条件的上次结果'));
  failedShop='';await choose('店铺','my');
  // Overview can finish before its rows: do not publish half of a new scope.
  w.FinanceWorkspace.enter('settlements');await ready();heldRecords='ph';
  change('店铺','ph');await wait(()=>pending.length===1);
  assert(root.classList.contains('fi-selection-pending'));
  assert(root.textContent.includes('ORDER-my'),'last complete view remains mounted while pending');
  assert(root.querySelector('.fi-content').hidden,'old rows remain hidden until the new overview and rows both finish');
  assert.equal(reads.filter(r=>r.endpoint==='/records').at(-1).q.currency,'PHP');
  const oldRecords=reads.filter(r=>r.endpoint==='/records').at(-1);
  change('店铺','my');await ready();assertVisible('settlements',shops[1]);
  assert.equal(oldRecords.signal.aborted,true);
  heldRecords='';pending.splice(0).forEach(resolve=>resolve());await pause();assertVisible('settlements',shops[1]);
  w.FinanceWorkspace.enter('overview');await ready();
  // The live display uses an independent today scope; reports retain their selected dates/shop/mode.
  await choose('开始日期','2026-09-01');await choose('结束日期','2026-09-30');
  w.FinanceWorkspace.enter('live');await ready();await choose('店铺','ph');
  w.FinanceWorkspace.enter('overview');await ready();
  assert.equal(lastReport().q.shopId,'my');assert.equal(lastReport().q.start,'2026-09-01');assert.equal(lastReport().q.displayCurrency,'ORIGINAL');
  // Expense selectors must invalidate the report's old native filter before returning.
  await choose('店铺','ph');w.FinanceWorkspace.enter('expense-ledger');await ready();await pause();
  change('店铺','my');await pause();
  w.FinanceWorkspace.enter('overview');await ready();assertScopeRequest('my','ORIGINAL');assertVisible('overview',shops[1]);
  w.FinanceWorkspace.enter('expense-ledger');await ready();await pause();change('费用分组','clothes');await pause();
  w.FinanceWorkspace.enter('overview');await ready();assertScopeRequest('','ORIGINAL');assert.equal(lastReport().q.groupId,'clothes');
  // Settlement exports and costs drilldowns inherit the new scope; new cost selections drop old drilldown currency.
  await choose('店铺分组','');await choose('店铺','ph');
  w.FinanceWorkspace.enter('settlements');await ready();await choose('店铺','my');
  await click('导出 Excel');await click('下载报表',d.querySelector('dialog'));await pause();
  assert.equal(exports.at(-1).searchParams.get('shopId'),'my');assert.equal(exports.at(-1).searchParams.get('currency'),'MYR');
  w.FinanceCosts.openOrders({...lastReport().q,currency:'MYR',kind:'SETTLED_ORDER'});
  w.FinanceWorkspace.enter('costs');await ready();await wait(()=>root.textContent.includes('COST-my'));
  change('财务店铺','ph');await wait(()=>root.textContent.includes('COST-ph'));
  let costQuery=reads.filter(r=>r.endpoint==='/workspace/costs/orders').at(-1).q;
  assert.equal(costQuery.shopId,'ph');assert(!costQuery.currency);
  await click('导出 Excel');await pause();assert.equal(exports.at(-1).searchParams.get('shopId'),'ph');assert(!exports.at(-1).searchParams.get('currency'));
  change('成本分组','clothes');await wait(()=>root.textContent.includes('COST-th'));
  costQuery=reads.filter(r=>r.endpoint==='/workspace/costs/orders').at(-1).q;
  assert.equal(costQuery.groupId,'clothes');assert(!costQuery.currency);
  console.log('Finance scope currency passed: all reporting routes, PHP/MYR/THB/SGD, CNY/native, groups/platforms, stale responses, dates, expense round-trips, costs and exports.');
}finally{
  pending.splice(0).forEach(resolve=>resolve());w.FinanceWorkspace.reset();dom.window.close();
}
