import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main></main>',{url:'https://example.test/',runScripts:'outside-only'}),w=dom.window,d=w.document,root=d.querySelector('main');
const shops=[{id:'a1',name:'甲店一',group_id:'ga',platform:'SHOPEE',region:'PH'},{id:'a2',name:'甲店二',group_id:'ga',platform:'SHOPEE',region:'TH'},{id:'b1',name:'乙店',group_id:'gb',platform:'TIKTOK_SHOP',region:'MY'}];
const groups=[{id:'ga',name:'甲组'},{id:'gb',name:'乙组'},{id:'empty',name:'空组'}];
const preview={live:true,shopId:'a1',groupId:'ga',costs:[],manualCosts:[],products:[],mappings:{},settings:{},imports:[]};
const records=[...Array.from({length:55},(_,i)=>({id:'a1-'+i,shop_id:'a1',order_id:'甲一待补-'+i,pending:true,known:1})),
  {id:'a2-1',shop_id:'a2',order_id:'甲二待补-1',pending:true,known:2},{id:'a2-2',shop_id:'a2',order_id:'甲二待补-2',pending:true,known:2},
  {id:'b1-1',shop_id:'b1',order_id:'乙待补-1',pending:true,known:4},{id:'ready',shop_id:'a1',order_id:'甲一已计算',pending:false,known:10}];
const reads=[],exports=[],catalogReads=[];let delayedGroup='',finishOldRead,failCatalogueShop='',delayedCatalogue='',finishCatalogue;
const context={preview,shops,groups,canManage:false,downloadOrderCosts:async selection=>{exports.push(JSON.parse(JSON.stringify(selection)));return {status:'QUEUED'};},request:async(path,options={})=>{
  assert(!options.method||options.method==='GET','query filter changes must not write cost configuration');
  const u=new URL(path,'https://example.test'),q=Object.fromEntries(u.searchParams);
  if(u.pathname==='/costs'||u.pathname==='/costs/context'){
    catalogReads.push(q);if(failCatalogueShop&&failCatalogueShop===q.shopId)throw Error('成本设置读取失败');const shop=shops.find(s=>s.id===q.shopId)||shops[0];
    const result={...preview,shopId:shop.id,groupId:shop.group_id};
    if(delayedCatalogue&&delayedCatalogue===q.shopId)return new Promise(resolve=>{finishCatalogue=()=>resolve(result);});
    return result;
  }
  assert.equal(u.pathname,'/costs/orders');reads.push(q);
  const scoped=records.filter(r=>(!q.groupId||shops.find(s=>s.id===r.shop_id).group_id===q.groupId)&&(!q.shopId||r.shop_id===q.shopId));
  const selected=scoped.filter(r=>q.costStatus!=='PENDING'||r.pending),page=Number(q.page),pending=scoped.filter(r=>r.pending);
  const result={items:selected.slice((page-1)*50,page*50).map(r=>({...r,product_cost_pending:r.pending?1:0,product_cost_state:r.pending?'待补齐':'已计算',product_cost_cny:r.pending?null:r.known})),
    total:selected.length,page,pageSize:50,costAmountSummary:{order_count:selected.length,pending_count:pending.length,known_amount_cny:selected.reduce((n,r)=>n+r.known,0),amount_cny:null},
    productCosts:{total:{pending_count:pending.length,ready_count:scoped.length-pending.length,known_amount_cny:scoped.reduce((n,r)=>n+r.known,0)}}};
  if(delayedGroup&&delayedGroup===q.groupId)return new Promise(resolve=>{finishOldRead=()=>resolve(result);});
  return result;
}};
const source=process.env.FINANCE_COSTS_SCRIPT||new URL('../../frontend/finance-costs.js',import.meta.url);
w.eval(await fs.readFile(source,'utf8'));
const settle=()=>new Promise(r=>setTimeout(r,15));
const button=text=>[...d.querySelectorAll('button')].find(b=>b.textContent.trim()===text||b.textContent.trim().endsWith(text));
async function click(text){const b=button(text);assert(b,text);b.click();await settle();}
async function choose(label,value){const field=d.querySelector(`[aria-label="${label}"]`);assert([...field.options].some(o=>o.value===value),label+' option '+value);field.value=value;field.dispatchEvent(new w.Event('change'));await settle();}
const selector=label=>d.querySelector(`[aria-label="${label}"]`);
const scope=selection=>Object.fromEntries(['shopId','groupId','kind','recordId','costStatus','start','end','numbers'].map(k=>[k,selection[k]||'']));
const expectExport=async expected=>{await click('导出 Excel');assert.deepEqual(scope(exports.at(-1)),expected);assert(!('page' in exports.at(-1)),'export covers the full filtered query, including other pages');};
const base={kind:'SETTLED_ORDER',recordId:'',costStatus:'PENDING',start:'2026-10-01',end:'2026-10-31',numbers:''};
w.FinanceCosts.openOrders({shopId:'a1',groupId:'ga',start:base.start,end:base.end});w.FinanceCosts.mount(root,context);await settle();
for(const label of ['成本分组','财务店铺'])assert([...selector(label).options].some(o=>o.value===''&&o.textContent==='全部'),label+' must offer all');
await click('下一页');assert.equal(reads.at(-1).page,'2');
d.querySelector('[data-cost-status=PENDING]').click();await settle();
assert.deepEqual(scope(reads.at(-1)),{...base,shopId:'a1',groupId:'ga'});assert.equal(reads.at(-1).page,'1');
assert(!d.body.textContent.includes('所有店铺待补齐'));assert(!d.querySelector('tbody').textContent.includes('乙待补'));
assert(d.querySelector('.fc-order-amount-summary').textContent.includes('¥ 55'));assert(d.body.textContent.includes('甲组')&&d.body.textContent.includes('甲店一'));
await click('下一页');assert.equal(d.querySelectorAll('tbody tr').length,5);await expectExport({...base,shopId:'a1',groupId:'ga'});
await choose('财务店铺','');assert.equal(reads.at(-1).page,'1');assert.equal(reads.at(-1).groupId,'ga');assert.equal(reads.at(-1).shopId,'');
assert(d.querySelector('.fc-order-amount-summary').textContent.includes('¥ 59'));assert(button('成本表设置').disabled,'all-shop scope must not edit a hidden specific shop');
await expectExport({...base,shopId:'',groupId:'ga'});
await choose('成本分组','');assert.equal(selector('财务店铺').value,'');assert.equal(reads.at(-1).groupId,'');assert.equal(reads.at(-1).shopId,'');
assert(d.querySelector('.fc-order-amount-summary').textContent.includes('58 笔订单'),JSON.stringify({summary:d.querySelector('.fc-order-amount-summary').textContent,query:reads.at(-1)}));assert(d.querySelector('.fc-order-amount-summary').textContent.includes('¥ 63'));
await expectExport({...base,shopId:'',groupId:''});
w.FinanceCosts.mount(root,{...context,preview:{...preview}});await settle();
assert.equal(selector('成本分组').value,'');assert.equal(selector('财务店铺').value,'','parent remount must not replace all with the catalogue default shop');
await choose('财务店铺','b1');assert.equal(selector('成本分组').value,'');assert.equal(reads.at(-1).shopId,'b1');
assert(d.querySelector('tbody').textContent.includes('乙待补-1'));assert(!d.querySelector('tbody').textContent.includes('甲一待补'));
await expectExport({...base,shopId:'b1',groupId:''});
assert.equal(catalogReads.length,0,'order filters must not fetch or modify a specific shop catalogue');
await click('商品成本表');assert.equal(selector('成本分组').value,'ga');assert.equal(selector('财务店铺').value,'a1');
assert(![...selector('财务店铺').options].some(o=>o.value===''),'configuration tabs remain scoped to a real shop');
await click('订单成本');assert.equal(selector('成本分组').value,'');assert.equal(selector('财务店铺').value,'b1');
await choose('成本分组','ga');assert.equal(selector('财务店铺').value,'');assert.deepEqual([...selector('财务店铺').options].map(o=>o.value),['','a1','a2']);
await choose('成本分组','empty');assert.equal(reads.at(-1).groupId,'empty');assert.equal(reads.at(-1).shopId,'');assert.deepEqual([...selector('财务店铺').options].map(o=>o.value),['']);
assert(d.body.textContent.includes('当前日期和店铺范围内没有待补齐订单。'));
delayedGroup='gb';await choose('成本分组','gb');assert.equal(typeof finishOldRead,'function');assert(button('导出 Excel').disabled,'export waits for the new filter result');
delayedGroup='';await choose('成本分组','ga');finishOldRead();await settle();
assert.equal(selector('成本分组').value,'ga');assert(d.querySelector('.fc-order-amount-summary').textContent.includes('¥ 59'),'late results must not replace the active query');
await expectExport({...base,shopId:'',groupId:'ga'});
await choose('成本分组','');await choose('财务店铺','b1');failCatalogueShop='b1';
await click('成本表设置');const readsBeforeRefresh=reads.length;await click('刷新成本');
assert(reads.length>readsBeforeRefresh,'a failed settings request must not permanently disable the existing order refresh');
d.querySelector('[data-cost-status=ALL]').click();await settle();
assert.equal(reads.at(-1).shopId,'b1','failed settings reads must not reset the order query to the catalogue shop');
failCatalogueShop='';await click('成本表设置');assert.equal(catalogReads.at(-1).shopId,'b1');
assert.equal(selector('财务店铺').value,'b1','settings must belong to the selected order shop');
await click('订单成本');assert.equal(selector('成本分组').value,'');assert.equal(selector('财务店铺').value,'b1');
await choose('财务店铺','a1');delayedCatalogue='a1';await click('成本表设置');assert.equal(typeof finishCatalogue,'function');
await choose('成本分组','gb');finishCatalogue();await settle();
assert(d.querySelector('[role="tab"][aria-selected="true"]').textContent.includes('订单成本'),'a late settings response cannot replace a newer order filter');
assert.equal(selector('成本分组').value,'gb');assert.equal(selector('财务店铺').value,'');
await choose('成本分组','ga');await choose('财务店铺','a1');delayedGroup='ga';
w.FinanceCosts.mount(root,{...context,preview:{...preview,shopId:'b1',groupId:'gb'}});await settle();
const completeInvalidatedOrder=finishOldRead;assert(button('导出 Excel').disabled);
delayedGroup='';delayedCatalogue='';failCatalogueShop='a1';await click('成本表设置');
completeInvalidatedOrder();await settle();
assert(!button('导出 Excel').disabled,'settings failure during an order query must restore a usable query and export');
assert(!d.querySelector('.fc-order-amount-summary').textContent.includes('查询中'));
const readsAfterRecovery=reads.length;await click('刷新成本');assert(reads.length>readsAfterRecovery);
assert.equal(selector('成本分组').value,'ga');assert.equal(selector('财务店铺').value,'a1');
w.FinanceCosts.reset();dom.window.close();
console.log('PASS order-cost filters: all groups/shops, scoped pending, pagination, export, remount and stale-response protection.');
